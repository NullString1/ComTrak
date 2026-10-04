{
  description = "ComTrak: Commission Tracking System for Artisans (Rust backend + Garage S3 + Kotlin Android)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    crane.url = "github:ipetkov/crane";
  };

  outputs = { self, nixpkgs, flake-utils, crane, ... }:
    let
      # Supported systems for devShells and packages
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
    in
    flake-utils.lib.eachSystem systems (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
          };
        };

        craneLib = crane.mkLib pkgs;

        # Filter source to include Rust files, Cargo files, and SQL migration files
        sqlFilter = path: _type: builtins.match ".*\\.sql$" path != null;
        src = pkgs.lib.cleanSourceWith {
          src = ./backend;
          filter = path: type:
            (sqlFilter path type) || (craneLib.filterCargoSources path type);
        };

        commonArgs = {
          inherit src;
          pname = "comtrak-backend";
          version = "0.1.0";
          strictDeps = true;

          nativeBuildInputs = with pkgs; [
            pkg-config
          ];

          buildInputs = with pkgs; [
            openssl
            sqlite
          ] ++ pkgs.lib.optionals pkgs.stdenv.hostPlatform.isDarwin [
            pkgs.darwin.apple_sdk.frameworks.Security
            pkgs.darwin.apple_sdk.frameworks.SystemConfiguration
          ];

          # Compile SQLx in offline mode without requiring a live SQLite DB during nix build
          SQLX_OFFLINE = "true";
          doCheck = false;
        };

        # Pre-build dependencies only (cached by Nix across application code changes!)
        cargoArtifacts = craneLib.buildDepsOnly commonArgs;

        # Build application on top of cached cargo dependencies
        backendPkg = craneLib.buildPackage (commonArgs // {
          inherit cargoArtifacts;
          meta = with pkgs.lib; {
            description = "ComTrak REST and Offline-First Sync Backend";
            homepage = "https://github.com/comtrak/comtrak";
            license = licenses.mit;
            maintainers = [ ];
          };
        });

        # Layered Docker OCI image configuration
        dockerImageConfig = {
          name = "comtrak-backend";
          tag = "latest";

          contents = [
            backendPkg
            pkgs.cacert
            pkgs.sqlite
            pkgs.dockerTools.fakeNss
          ];

          config = {
            Cmd = [ "${backendPkg}/bin/comtrak-backend" ];
            Env = [
              "SSL_CERT_FILE=${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"
              "DATA_DIR=/data"
              "DATABASE_URL=sqlite:/data/comtrak.db?mode=rwc"
              "HOST=0.0.0.0"
              "PORT=8080"
              "RUST_LOG=info,comtrak_backend=debug"
            ];
            ExposedPorts = {
              "8080/tcp" = { };
            };
            Volumes = {
              "/data" = { };
            };
            WorkingDir = "/data";
          };
        };

        # Fast streaming image directly pipeable to docker load (takes < 1s)
        dockerStream = pkgs.dockerTools.streamLayeredImage dockerImageConfig;

        # Traditional compressed tarball image for archiving or offline transport
        dockerTar = pkgs.dockerTools.buildLayeredImage dockerImageConfig;

      in
      {
        packages = {
          backend = backendPkg;
          dockerImage = dockerStream;
          dockerStream = dockerStream;
          dockerTar = dockerTar;
          default = backendPkg;
        };

        # Developer environment with all tools for Rust, Android, SQLite and Garage
        devShells.default = pkgs.mkShell {
          name = "comtrak-dev-shell";

          buildInputs = with pkgs; [
            # Rust toolchain & utilities
            rustc
            cargo
            rustfmt
            clippy
            rust-analyzer
            cargo-watch
            pkg-config
            openssl

            # SQLite & SQLx
            sqlite
            sqlx-cli

            # S3 Storage & Garage
            garage
            minio-client

            # Containers
            docker
            docker-compose

            # Android & JVM tooling
            jdk17
            android-tools
            gradle
          ] ++ pkgs.lib.optionals pkgs.stdenv.hostPlatform.isDarwin [
            pkgs.darwin.apple_sdk.frameworks.Security
            pkgs.darwin.apple_sdk.frameworks.SystemConfiguration
          ];

          shellHook = ''
            echo "=========================================================="
            echo " Welcome to ComTrak Developer Shell"
            echo " Rust:    $(rustc --version)"
            echo " Cargo:   $(cargo --version)"
            echo " Garage:  $(garage -V 2>/dev/null || echo 'available')"
            echo " Java:    $(java -version 2>&1 | head -n 1)"
            echo "=========================================================="
            echo "Tip: run 'docker-compose up -d garage' to start local S3"
            echo "Tip: run 'nix build .#dockerImage' to package backend container"
            echo ""

            # Default dev environment variables
            export DATABASE_URL="sqlite:backend/dev.db?mode=rwc"
            export GARAGE_ENDPOINT="http://127.0.0.1:3900"
            export GARAGE_REGION="garage"
            export GARAGE_BUCKET="comtrak-media"
            export GARAGE_ACCESS_KEY="GKtestkey0000000000000000"
            export GARAGE_SECRET_KEY="testsecret00000000000000000000000000000000"
            export JWT_SECRET="dev-jwt-secret-do-not-use-in-production-12345678"
            export RUST_LOG="info,comtrak_backend=debug"
          '';
        };
      }
    ) // {
      # NixOS Module for running ComTrak as a native hardened systemd service
      nixosModules.default = { config, lib, pkgs, ... }:
        let
          cfg = config.services.comtrak;
        in
        {
          options.services.comtrak = {
            enable = lib.mkEnableOption "ComTrak artisan commission tracking backend";

            package = lib.mkOption {
              type = lib.types.package;
              default = self.packages.${pkgs.system}.backend;
              description = "The comtrak backend package to run.";
            };

            host = lib.mkOption {
              type = lib.types.str;
              default = "0.0.0.0";
              description = "The host IP address to bind to.";
            };

            port = lib.mkOption {
              type = lib.types.port;
              default = 8080;
              description = "The port for the ComTrak HTTP server.";
            };

            stateDir = lib.mkOption {
              type = lib.types.str;
              default = "/var/lib/comtrak";
              description = "Directory for SQLite database and data persistence.";
            };

            s3 = {
              endpoint = lib.mkOption {
                type = lib.types.str;
                default = "http://127.0.0.1:3900";
                description = "Garage / S3 endpoint URL.";
              };
              region = lib.mkOption {
                type = lib.types.str;
                default = "garage";
                description = "S3 Region.";
              };
              bucket = lib.mkOption {
                type = lib.types.str;
                default = "comtrak-media";
                description = "S3 bucket name for images.";
              };
            };

            googleClientId = lib.mkOption {
              type = lib.types.nullOr lib.types.str;
              default = null;
              description = "Google OAuth2 Client ID for ID token validation.";
            };

            environmentFile = lib.mkOption {
              type = lib.types.nullOr lib.types.path;
              default = null;
              description = "Path to environment file containing secrets (JWT_SECRET, GARAGE_ACCESS_KEY, GARAGE_SECRET_KEY).";
            };

            openFirewall = lib.mkOption {
              type = lib.types.bool;
              default = false;
              description = "Whether to open the service port in the firewall.";
            };
          };

          config = lib.mkIf cfg.enable {
            networking.firewall.allowedTCPPorts = lib.optional cfg.openFirewall cfg.port;

            systemd.services.comtrak = {
              description = "ComTrak Commission Tracking Service";
              wantedBy = [ "multi-user.target" ];
              after = [ "network-online.target" "garage.service" ];
              wants = [ "network-online.target" ];

              environment = {
                HOST = cfg.host;
                PORT = toString cfg.port;
                DATA_DIR = cfg.stateDir;
                DATABASE_URL = "sqlite://${cfg.stateDir}/comtrak.db?mode=rwc";
                GARAGE_ENDPOINT = cfg.s3.endpoint;
                GARAGE_REGION = cfg.s3.region;
                GARAGE_BUCKET = cfg.s3.bucket;
                RUST_LOG = "info,comtrak_backend=info";
              } // lib.optionalAttrs (cfg.googleClientId != null) {
                GOOGLE_CLIENT_ID = cfg.googleClientId;
              };

              serviceConfig = {
                ExecStart = "${cfg.package}/bin/comtrak-backend";
                Restart = "always";
                RestartSec = "5s";
                EnvironmentFile = lib.optional (cfg.environmentFile != null) cfg.environmentFile;

                # Security hardening
                DynamicUser = true;
                StateDirectory = "comtrak";
                ProtectSystem = "strict";
                ProtectHome = true;
                NoNewPrivileges = true;
                PrivateTmp = true;
                ProtectKernelTunables = true;
                ProtectControlGroups = true;
                RestrictNamespaces = true;
                LockPersonality = true;
              };
            };
          };
        };

      nixosModules.comtrak = self.nixosModules.default;
    };
}
