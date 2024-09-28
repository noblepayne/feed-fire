{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    devenv.url = "github:cachix/devenv";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
    clj-nix.url = "github:jlesquembre/clj-nix";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";
  };
  outputs = {
    self,
    nixpkgs,
    devenv,
    clj-nix,
    ...
  } @ inputs: {
    formatter = builtins.mapAttrs (system: pkgs: pkgs.alejandra) nixpkgs.legacyPackages;
    devShells =
      builtins.mapAttrs (system: pkgs: let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };
      in {
        default = devenv.lib.mkShell {
          inherit inputs pkgs;
          modules = [
            (
              {
                config,
                pkgs,
                ...
              }: {
                # https://devenv.sh/reference/options/
                packages = [
                  pkgs.git
                  pkgs.babashka
                  pkgs.jet
                  pkgs.neovim
                  pkgs.cljfmt
                  #pkgs.vscode
                  (pkgs.vscode-with-extensions.override {
                    vscodeExtensions = [
                      pkgs.vscode-extensions.betterthantomorrow.calva
                      pkgs.vscode-extensions.vscodevim.vim
                      pkgs.vscode-extensions.jnoortheen.nix-ide
                    ];
                  })
                ];

                languages.clojure.enable = true;

                # N.B. picks up quotes and inline comments
                dotenv.enable = true;

                scripts.format.exec = ''
                  nix fmt
                  cljfmt fix .
                '';
                scripts.lock.exec = ''
                  nix flake lock
                  nix run .#deps-lock
                '';
                scripts.update.exec = ''
                  nix flake update
                  nix run .#deps-lock
                '';
                scripts.build.exec = ''
                  nix build .
                '';

                enterShell = ''
                  # start editor
                  code .
                '';
              }
            )
          ];
        };
      })
      nixpkgs.legacyPackages;
    packages =
      builtins.mapAttrs (system: pkgs: {
        deps-lock = clj-nix.packages.${system}.deps-lock;
        container = pkgs.dockerTools.buildLayeredImage {
          name = "feed-fire";
          tag = "latest";
          config = {
            Entrypoint = ["${self.packages.${system}.default}/bin/feed-fire"];
          };
        };
        default = clj-nix.lib.mkCljApp {
          inherit pkgs;
          modules = [
            {
              projectSrc = ./.;
              name = "com.noblepayne/feed-fire";
              main-ns = "feed-fire.lit";
              nativeImage.enable = false;
              nativeImage.extraNativeImageBuildArgs = [
                "--no-fallback"
                "--features=clj_easy.graal_build_time.InitClojureClasses"
                "--enable-url-protocols=http"
                "--enable-url-protocols=https"
              ];
            }
          ];
        };
      })
      nixpkgs.legacyPackages;
  };
}
