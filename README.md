# feed-fire 🔥

Make a feed :fire:

## Overview

feed-fire is a Clojure project for managing and updating podcast feed lit status. It provides functionality to download, process, and update RSS feeds with podcasting 2.0 [live item](https://podcasting2.org/podcast-namespace/tags/liveItem) information.

## Features

- Download and parse RSS feeds.
- Update liveItem tag.
- Generate and upload updated feeds.
- Simple web interface for managing feed updates.

## Technologies

+ [Nix](https://nixos.org/)
+ [Clojure](https://clojure.org/)
+ [devenv](https://devenv.sh/)
+ [clj-nix](https://jlesquembre.github.io/clj-nix/)

### Clojure Libraries

- [aleph](https://github.com/aleph-io/aleph): HTTP server
- [babashka.http-client](https://github.com/babashka/http-client): HTTP client
- [clojure.data.xml](https://github.com/clojure/data.xml): XML processing
- [cognitect.aws.client.api](https://github.com/cognitect-labs/aws-api): AWS SDK for S3 operations
- [cybermonday](https://github.com/kiranshila/cybermonday): Markdown parsing
- [dev.onionpancakes.chassis](https://github.com/onionpancakes/chassis): HTML generation
- [hickory](https://github.com/davidsantiago/hickory): HTML parsing and manipulation
- [manifold](https://github.com/aleph-io/manifold): Asynchronous programming
- [muuntaja](https://github.com/metosin/muuntaja): HTTP format negotiation, encoding, and decoding
- [reitit](https://github.com/metosin/reitit): HTTP routing


## Getting Started

### Prerequisites

- Nix package manager

### Development

1. Clone the repository.
2. Enter the development environment:

```sh
./dev.sh
```

This will set up the necessary dependencies and open the project with VSCode.

### Building

To build the project:

```sh
nix build
```

To build a container:

```sh
nix build .#container
```

## License

This project is licensed under the MIT License.
