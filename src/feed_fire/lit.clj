(ns feed-fire.lit
  (:gen-class)
  (:require [aleph.http :as httpd]
            [babashka.http-client :as http]
            [clojure.data.xml :as xml]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.endpoint]
            [cognitect.aws.credentials :as aws-creds]
            [cybermonday.core :as markdown]
            [dev.onionpancakes.chassis.core :as html]
            [hickory.select :as hselect]
            [hickory.zip :as hzip]
            [manifold.deferred :as mf]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters]))

(defn- collapse-nested-lists [form]
  (cond
    (string? form)
    form

    (and (vector? form) (keyword? (first form)))
    (->> form
         (mapv #(if (seq? %) % [%]))
         (apply concat)
         (into []))

    :else
    form))

(defn xmlhiccup->xmlparsed [tree]
  (cond
    (string? tree) tree
    (number? tree) (str tree)
    (keyword? tree) (str tree)
    (contains? tree :content) tree
    (and (vector tree) (= :cdata (first tree))) (clojure.data.xml.node.CData. (peek tree))
    :else (let [metadata (meta tree)
                tree (collapse-nested-lists tree)
                [tag maybe-attrs & remain] tree
                attrs? (map? maybe-attrs)
                attrs (if attrs? maybe-attrs {})
                content (if attrs? remain (concat [maybe-attrs] remain))]
            (-> {:tag tag
                 :attrs attrs
                 :content (map xmlhiccup->xmlparsed content)}
                (with-meta metadata)))))

(defn xmlparsed->xmlhiccup [tree]
  (if (or (string? tree) (instance? clojure.data.xml.node.CData tree))
    (if (string? tree)
      tree
      [:cdata (:content tree)])
    (let [tag (:tag tree)
          attrs (:attrs tree)
          content (:content tree)
          metadata (meta tree)
          ;; TODO: non-recusive version?
          translated-content (map xmlparsed->xmlhiccup content)]
      (with-meta
        (if (empty? attrs)
          ;; skip including empty attrs
          (into [tag] translated-content)
          (into [tag attrs] translated-content))
        metadata))))

(defn contains-many? [m ks]
  (every? #(contains? m %) ks))

(defn- is-element? [x]
  (or (instance? clojure.data.xml.node.Element x)
      (and (map? x)
           (contains-many? x [:tag :attrs]))))

(defn- u->p [feed]
  (->> feed
       meta
       :clojure.data.xml/nss
       :p->u
       (into {}
             (map
              (fn [[p u]]
                [(-> u xml/uri-symbol str) p])))))

(defn- cleanup-key [namespace->shortname key]
  (let [key-name (name key)
        key-namespace (namespace key)
        namespace-shortname (namespace->shortname key-namespace)]
    (cond
      ;; no namespace, just use key as is
      (nil? namespace-shortname) key
      (empty? namespace-shortname) (keyword key-name)
      :else (keyword (str namespace-shortname ":" key-name)))))

(defn- cleanup-element [namespace->shortname el]
  (if-not (is-element? el)
    el
    (let [tag (:tag el)
          new-tag (cleanup-key namespace->shortname tag)
          attrs (:attrs el)
          ;; TODO: actually needed? properly "undone" on render?
          new-attrs (into {} (for [[k v] attrs]
                               [(cleanup-key namespace->shortname k) v]))]
      (assoc el :tag new-tag :attrs new-attrs))))

(defn cleanup-feed [feed]
  (let [namespace->shortname (u->p feed)
        cleaned-feed (walk/prewalk (partial cleanup-element namespace->shortname) feed)]
    (with-meta cleaned-feed (meta feed))))

;; download feed
(defn download-feed [feed-url]
  (-> feed-url http/get :body (xml/parse-str :skip-whitespace true) cleanup-feed))

;; extract any existing liveItem s
(defn extract-live-item [feed]
  (let [feed-zip (hzip/hickory-zip feed)
        first-live-loc (hselect/select-next-loc (hselect/tag :podcast:liveItem) feed-zip)
        first-live (if first-live-loc (zip/node first-live-loc) first-live-loc)
        first-live-loc (when first-live-loc (zip/remove first-live-loc))
        second-live-loc (when first-live-loc (hselect/select-next-loc (hselect/tag :podcast:liveItem) first-live-loc))]
    (when second-live-loc
      (println "MULTIPLE LIVE ITEMS: THIS IS UNSUPPORTED")
      (throw (ex-info "multiple live items" {:first first-live :second (zip/node second-live-loc)})))
    first-live))

(defn remove-live-items [feed]
  (let [z (hzip/hickory-zip feed)
        init-loc (hselect/select-next-loc (hselect/tag :podcast:liveItem) z)]
    (if-not init-loc
      feed
      (loop [loc init-loc]
        (let [after-remove (zip/remove loc)
              nxt (hselect/select-next-loc (hselect/tag :podcast:liveItem) after-remove)]
          (if nxt
            (recur nxt)
            (zip/root after-remove)))))))

(defn multi-tag-into [old new]
  (if (= (count new) 1)
    (let [[k v] (first new)]
      (if (contains? old k)
        (let [current-v (get old k)]
          (if (vector? current-v)
            (assoc old k (conj current-v v))
            (assoc old k [current-v v])))
        (assoc old k v)))
    (into old new)))

(defn element->data [item]
  (if (not (map? item))
    item
    (let [{:keys [tag attrs content]} item]
      (cond
        ;; no attrs + 1 content = K:V pair
        (and (empty? attrs) (= (count content) 1)) {tag (element->data (first content))}
        ;; attrs + 1 content
        (= (count content) 1)
        (let [processed-content (-> content first element->data)]
          (if (and (map? processed-content) (= (count processed-content) 1))
            (let [[k v] (first processed-content)]
              ;; if content is k/v pair, just use that
              {tag (assoc (into {} attrs) k v)})
            ;; otherwise add under a :content key
            {tag (assoc (into {} attrs) :content processed-content)}))
        ;; otherwise recurse
        :else
        {tag (reduce multi-tag-into (into {} attrs) (map element->data content))}))))

  ;; save data or initialize new pending

(def podcastDateFormatter
  (java.time.format.DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss Z"))

(defn pub-date []
  (.format (java.time.ZonedDateTime/now)
           podcastDateFormatter))

(defn lup-live-item [{:keys [guid status start end title raw-description description]}]
  [:podcast:liveItem
   {:status status,
    :start start,
    :end end}
   [:title title]
   [:itunes:subtitle title]
   [:description
    [:cdata description]]
   [:itunes:summary
    [:cdata description]]
   [:jb:rawDescription
    [:cdata raw-description]]
   [:enclosure
    {:length "33",
     :type "audio/mpeg",
     :url "https://jblive.fm"}]
   [:podcast:alternateEnclosure
    {:type "application/vnd.apple.mpegurl"
     :length "33"
     :default true
     :title "HLS"}
    [:podcast:source {:uri "https://jupiter-hls.secdn.net/jupiter-channel/play/jupiter.smil/playlist.m3u8"}]]
   [:guid
    {:isPermaLink "false"}
    guid]
   [:itunes:image
    {:href
     "https://station.us-iad-1.linodeobjects.com/art/lup-mp3.jpg"}]
   [:link {} "http://jblive.fm"]
   [:podcast:person {:group "cast"
                     :role "host"
                     :href "https://chrislas.com"
                     :img "https://www.jupiterbroadcasting.com/images/people/chris.jpg"}
    "Chris Fisher"]
   [:podcast:person {:group "cast"
                     :role "host"
                     :href "https://www.jupiterbroadcasting.com/hosts/wes"
                     :img "https://www.jupiterbroadcasting.com/images/people/wes.jpg"}
    "Wes Payne"]
   [:podcast:person {:group "cast"
                     :role "host"
                     :href "https://www.jupiterbroadcasting.com/hosts/brent/"
                     :img "https://www.jupiterbroadcasting.com/images/people/brent.jpg"}
    "Brent Gervais"]
   [:podcast:value
    {:type "lightning", :method "keysend", :suggested "0.00000005000"}
    [:podcast:valueRecipient
     {:name "JB Node",
      :type "node",
      :address
      "037d284d2d7e6cec7623adbe600450a73b42fb90800989f05a862464b05408df39",
      :split "9"
      :fee "false"}]
    ;; Chris' NodeCan
    [:podcast:valueRecipient
     {:name "Chris",
      :type "node",
      :address
      "03de23d27775ff1abc1d5770e56ee058464c9fcd4cc39837e605646e95aaf5f8f4",
      :split "30"}]
    [:podcast:valueRecipient
     {:name "Wes",
      :type "node",
      :address
      "030a58b8653d32b99200a2334cfe913e51dc7d155aa0116c176657a4f1722677a3",
      :customKey "696969",
      :customValue "peDa7Jzh9Hc7VO87yDg5",
      :split "30"}]
    [:podcast:valueRecipient
     {:name "Brent",
      :type "node",
      :address
      "030a58b8653d32b99200a2334cfe913e51dc7d155aa0116c176657a4f1722677a3",
      :customKey "696969",
      :customValue "VaVYZFXrIzAxALMAztmo",
      :split "30"}]
    [:podcast:valueRecipient
     {:name "Fountain Bot",
      :type "node",
      :address
      "03b6f613e88bd874177c28c6ad83b3baba43c4c656f56be1f8df84669556054b79",
      :split "1",
      :fee "false",
      :customKey "906608",
      :customValue "01IMQkt4BFzAiSynxcQQqd"}]]
   [:podcast:images
    {:srcset
     "https://station.us-iad-1.linodeobjects.com/art/lup-mp3.jpg 3000w"}]
   [:podcast:chat
    {:protocol "nostr"
     :server "relay.fountain.fm"
     :accountId "npub1ddngs6e6m4evw7wjqkl9wnkz6l8vvxgxrtp7w4ch8zdjv3ze38jqcg3uu5"
     :space (str "30311:6b66886b3add72c779d205be574ec2d7cec619061ac3e75717389b26445989e4:" guid)}]
   ;; [:podcast:chat
   ;;  {:server "https://bit.ly/jointhematrix",
   ;;   :protocol "matrix",
   ;;   :space "Jupiter Broadcasting"}]
   [:pubDate {} (pub-date)]
   [:itunes:explicit {} "No"]
   [:author {} "Jupiter Broadcasting"]
   [:itunes:author {} "Jupiter Broadcasting"]
   ;; TODO: replace with jblive.fm?
   [:podcast:contentLink
    {:href
     "https://jupiter-hls.secdn.net/jupiter-channel/play/jupiter.smil/playlist.m3u8"}
    "Stream the video"]])

(defn coder-live-item [{:keys [guid status start end title raw-description description]}]
  [:podcast:liveItem
   {:status status,
    :start start
    :end end}
   [:title [:cdata title]]
   [:itunes:subtitle [:cdata title]]
   [:description
    [:cdata description]]
   [:itunes:summary
    [:cdata description]]
   [:jb:rawDescription  [:cdata raw-description]]
   [:enclosure
    {:length "33",
     :type "audio/mpeg",
     :url "https://jblive.fm"}]
   [:guid
    {:isPermaLink "false"}
    guid]
   [:itunes:image
    {:href
     "https://media24.fireside.fm/file/fireside-images-2024/podcasts/images/b/b44de5fa-47c1-4e94-bf9e-c72f8d1c8f5d/cover.jpg"}]
   [:podcast:images
    {:srcset
     "https://media24.fireside.fm/file/fireside-images-2024/podcasts/images/b/b44de5fa-47c1-4e94-bf9e-c72f8d1c8f5d/cover.jpg"}]
   [:link {} "http://jblive.fm"]
   [:podcast:person {:group "cast"
                     :role "host"
                     :href "https://chrislas.com"
                     :img "https://www.jupiterbroadcasting.com/images/people/chris.jpg"}
    "Chris Fisher"]
   [:podcast:person {:group "cast"
                     :role "host"
                     :href "http://dominickm.com/"
                     :img "https://www.jupiterbroadcasting.com/images/people/michael.jpg"}
    "Michael Dominick"]
   [:podcast:value
    {:type "lightning", :method "keysend", :suggested "0.00000005000"}
    [:podcast:valueRecipient
     {:name "JB Node",
      :type "node",
      :address
      "037d284d2d7e6cec7623adbe600450a73b42fb90800989f05a862464b05408df39",
      :split "10"
      :fee "false"}]
    [:podcast:valueRecipient
     {:name "Chris",
      :type "node",
      :address
      "03de23d27775ff1abc1d5770e56ee058464c9fcd4cc39837e605646e95aaf5f8f4",
      :split "89"}]
    [:podcast:valueRecipient
     {:name "Fountain Bot",
      :type "node",
      :address
      "03b6f613e88bd874177c28c6ad83b3baba43c4c656f56be1f8df84669556054b79",
      :split "1",
      :fee "false",
      :customKey "906608",
      :customValue "01IMQkt4BFzAiSynxcQQqd"}]]
   #_[:podcast:chat
      {:protocol "nostr"
       :server "relay.fountain.fm"
       :accountId "npub1ddngs6e6m4evw7wjqkl9wnkz6l8vvxgxrtp7w4ch8zdjv3ze38jqcg3uu5"
       :space (str "30311:6b66886b3add72c779d205be574ec2d7cec619061ac3e75717389b26445989e4:" guid)}]
   ;; [:podcast:chat
   ;;  {:server "https://bit.ly/jointhematrix",
   ;;   :protocol "matrix",
   ;;   :space "Jupiter Broadcasting"}]
   [:pubDate (pub-date)]
   [:itunes:explicit "No"]
   [:author "Jupiter Broadcasting"]
   [:itunes:author "Jupiter Broadcasting"]
   [:podcast:contentLink
    {:href
     "https://jblive.fm"}
    "Stream the audio"]])

;; remove liveItem s
;; present form with current live item or new pending
;; On SUBMIT
  ;; add live item to pruned feed data
  ;; render feed
  ;; upload to s3

(defn s3-client [account-id access-key secret-key]
  (aws/client {:api :s3
               :region "us-east-1" ;; TODO: need to put something? auto doesn't work. Seems to not matter with the endpoint override.
               :endpoint-override {:protocol :https :hostname (str account-id ".r2.cloudflarestorage.com")}
               :credentials-provider (aws-creds/basic-credentials-provider {:access-key-id access-key
                                                                            :secret-access-key secret-key})}))

(defn feed->bucket [s3c bucket-name key-name content]
  (aws/invoke s3c {:op :PutObject
                   :request {:Bucket bucket-name
                             :Key key-name
                             :Body (.getBytes content)
                             :ContentType "application/xml"}}))

(defn get-live-data [liveItem]
  (if (not-empty (:podcast:liveItem liveItem))
    (let [attrs (:podcast:liveItem liveItem)
          guid (-> attrs :guid :content)
          status (-> attrs :status)
          start (-> attrs :start)
          title (-> attrs :title)
          description (-> attrs :description)
          raw-description (-> attrs :jb:rawDescription)]
      {:guid guid
       :status status
       :title title
       :description description
       :raw-description raw-description
       :start start})
    {:guid (str (random-uuid))
     :status "pending"
     :title "Untitled Live Stream"
     :description "Undescribed live stream"
     :raw-description "Undescribed live stream"
     :start (.format (java.time.ZonedDateTime/now (java.time.ZoneId/of "America/Los_Angeles"))
                     java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)}))

(defn insert-into-channel [feed subtree-hiccup]
  (let [subtree (xmlhiccup->xmlparsed subtree-hiccup)
        channel-zip (->> (hzip/hickory-zip feed)
                         (hselect/select-next-loc
                          (hselect/tag :channel)))
        channel-with-sub (zip/insert-child channel-zip subtree)]
    (zip/root channel-with-sub)))

(def FEEDS
  {"coder" {:src "https://feeds.jupiterbroadcasting.com/coder"
            :dest "rss/coder.xml"
            :liveItem coder-live-item}
   "lup" {:src "https://feeds.jupiterbroadcasting.com/lup"
          :dest "rss/lup.xml"
          :liveItem lup-live-item}})

(defn podping [{:keys [token url reason] :or {reason "update"}}]
  ;; podping
  (http/get (str "https://podping.cloud/?url=" url "&reason=" reason)
            {:headers {"User-Agent" "JupiterBroadcasting"
                       "Authorization" token}})
  ;; podcast index update api
  (http/get (str "https://api.podcastindex.org/api/1.0/hub/pubnotify?url=" url "&pretty")))

(defn view [{{:strs [show] :or {show "coder"}} :params}]
  (let [feed (download-feed (-> FEEDS (get show) :src))
        liveItem (element->data (extract-live-item feed))
        liveItemData (get-live-data liveItem)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body
     (html/html
      [html/doctype-html5
       [:html
        [:head
         [:meta {:charset "utf-8"}]
         [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
         [:meta {:name "color-scheme", :content "light dark"}]
         [:script {:type "text/javascript"}
          "function navOnShowSelect() {
             var selectedValue = this.event.target.value;
             if (selectedValue) {
               window.location.href = '/?show=' + selectedValue;
             }
           }"]
         [:title "LIVE!"]
         [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.classless.min.css"}]]
        [:body
         [:main
          [:h1 "Coder Radio LIVE!"]
          [:form {:action "/update" :method "post"}
           [:show {:for "show"} "Show: "]
           [:select#show {:name "show" :onchange "navOnShowSelect();"}
            [:option {:value "coder" :selected (= show "coder")} "coder"]
            [:option {:value "lup" :selected (= show "lup")} "lup"]]
           [:label {:for "status"} "Status: "]
           [:select#status {:name "status"}
            [:option {:value "pending" :selected (= "pending" (:status liveItemData))} "pending"]
            [:option {:value "live" :selected (= "live" (:status liveItemData))} "live"]
            [:option {:value "ended" :selected (= "ended" (:status liveItemData))} "ended"]]
           [:label {:for "start"} "Start (JB Time: America/Los_Angeles): "]
           [:input#start {:type "datetime-local", :name "start", :aria-label "Datetime local" :value (-> liveItemData :start java.time.ZonedDateTime/parse .toLocalDateTime (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm")))}]
           [:label {:for "title"} "Title: "]
           [:input#title {:type "text" :name "title" :value (:title liveItemData)}]
           ;; FIXME: bigger description box for markdown input
           [:label {:for "description"} "Description: "]
           [:textarea#description {:type "text" :name "description"} (:raw-description liveItemData)]
           [:input {:type "hidden" :name "guid" :value (:guid liveItemData)}]
           [:input {:type "hidden" :name "old_status" :value (:status liveItemData)}]
           [:input {:type "submit" :value "Update Live Item"}]]]]]])}))

(defn update-feed [s3-client podping-token]
  (fn [{{:strs [guid title description old_status status start show]} :form-params :as request}]
    (let [{:keys [src dest liveItem] :as show-config} (get FEEDS show)
          feed (download-feed src)
          cleanFeed (remove-live-items feed)
          newLiveItem (liveItem {:guid (if (and (= old_status "ended") (not= status "ended")) (str (random-uuid)) guid)
                                 :title title
                                 :description (html/html (markdown/parse-body description))
                                 :raw-description description
                                 :status status
                                 :start (.format
                                         (.atZone (java.time.LocalDateTime/parse start)
                                                  (java.time.ZoneId/of "America/Los_Angeles"))
                                         java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)
                                 :end (.format (.plus (.atZone (java.time.LocalDateTime/parse start)
                                                               (java.time.ZoneId/of "America/Los_Angeles"))
                                                      2
                                                      java.time.temporal.ChronoUnit/HOURS)
                                               java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME)})
          updatedFeed (insert-into-channel cleanFeed newLiveItem)
          attrs (:attrs updatedFeed)
          feed-meta (meta updatedFeed)
          updatedFeed (if (contains? (-> feed-meta :clojure.data.xml/nss :p->u) "jb")
                        updatedFeed
                        (assoc updatedFeed :attrs (assoc attrs :xmlns:jb "https://jupiterbroadcasing/jank")))
          feed-data (xml/indent-str updatedFeed)]
      ;; TODO: validation on show
      (feed->bucket s3-client "feeds" dest feed-data)
      (podping {:token podping-token :url src :reason (case "pending" "update"
                                                            "live" "live"
                                                            "ended" "liveEnd")}))
    {:status 200 :headers {"content-type" "text/html"}
     :body (html/html
            [html/doctype-html5
             [:html
              [:head
               [:script
                  ;; TODO: redirect to correct show. Template string?
                (str "function redirectAfterDelay() {
                            setTimeout(function() {
                                window.location.href = '/?show=" show "';
                            }, 5000);
                        }
                        window.onload = redirectAfterDelay;")]]
              [:body
               [:img
                {:src "https://media1.tenor.com/m/j5wzOHldDm0AAAAC/applause-picard.gif"
                 :width "833"
                 :height "625.5863453815261"
                 :alt "Captain Picard, in a star trek uniform, is clapping his hands in front of a starry sky"
                 :style "max-width: 833px;"}]]]])}))

;; ~~~~~~~~~~~ Top Level HTTP ~~~~~~~~~~~

(defn routes [s3-client podping-token]
  [["/" {:get {:handler view}}]
   ["/update" {:post {:handler (update-feed s3-client podping-token)}}]])

(defn http-handler [s3-client podping-token]
  (ring/ring-handler
   (ring/router
    (routes s3-client podping-token)
    {:data {:muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         reitit.ring.middleware.parameters/parameters-middleware]}})
   (ring/create-default-handler)))

(defn make-virtual [f]
  (fn [& args]
    (let [deferred (mf/deferred)]
      (Thread/startVirtualThread
       (fn []
         (try
           (mf/success! deferred (apply f args))
           (catch Exception e (mf/error! deferred e)))))
      deferred)))

(defn serve
  [s3-client podping-token]
  (httpd/start-server
   (make-virtual (http-handler s3-client podping-token))
   {:port 4444
    ;; When other than :none our handler is run on a thread pool.
    ;; As we are wrapping our handler in a new virtual thread per request
    ;; on our own, we have no risk of blocking the (aleph) handler thread and
    ;; don't need an additional threadpool onto which to offload.
    :executor :none}))

(defn -main [& _]
  (serve
   (s3-client (System/getenv "CF_ACCOUNT_ID")
              (System/getenv "CF_ACCESS_KEY")
              (System/getenv "CF_SECRET_KEY"))
   (System/getenv "PODPING_API_KEY")))

(comment
  (def  s3c (s3-client (System/getenv "CF_ACCOUNT_ID")
                       (System/getenv "CF_ACCESS_KEY")
                       (System/getenv "CF_SECRET_KEY")))
  (def PODPING_API_KEY (System/getenv "PODPING_API_KEY"))

  (def handler (make-virtual (http-handler s3c PODPING_API_KEY)))
  (def server (httpd/start-server #'handler  {:port 4444 :executor :none}))
  (.close server))