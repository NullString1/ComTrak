# ComTrak 🏺

**ComTrak** is an offline-first commission tracking system designed specifically for ceramicists, potters, and custom craft artisans.

The project consists of:
- **Rust Backend**: High-performance REST and bidirectional delta sync API built with Axum, SQLite (WAL mode), and S3-compatible image storage powered by **Garage** (a lightweight, open-source object store written in Rust).
- **Kotlin Android App**: Modern Android application built with Jetpack Compose, Material 3, Room SQLite for 100% offline capability, native WebP lossy compression, and WorkManager background synchronization.
- **Nix Flake Infrastructure**: Hermetic dev environment, OCI container builder, and native NixOS service module.

---

## 1. Quick Start with Nix

Enter the developer shell with Rust, Cargo, SQLite, Garage, Android tools, and JDK pre-configured:

```bash
nix develop
```

Build the native backend binary:
```bash
nix build .#backend
./result/bin/comtrak-backend
```

Build the container image using Nix:
```bash
nix build .#dockerImage
docker load < result
```

---

## 2. Running with Docker Compose

To start the Garage S3 object store and ComTrak backend:

```bash
# 1. Start Garage S3 and initialize bucket
docker-compose up -d garage garage-init

# 2. Start the backend service
docker-compose up -d backend
```

- **Backend API**: `http://localhost:8080/api/v1/health`
- **Garage S3 Endpoint**: `http://localhost:3900`
- **SQLite Database**: Persisted in Docker volume `sqlite_data` (at `/data/comtrak.db`)

---

## 3. NixOS Deployment (`configuration.nix`)

ComTrak provides a first-class NixOS module under `nixosModules.default`:

```nix
{ config, pkgs, inputs, ... }:
{
  imports = [
    inputs.comtrak.nixosModules.default
  ];

  # 1. Enable ComTrak Backend Service
  services.comtrak = {
    enable = true;
    port = 8080;
    host = "0.0.0.0";
    stateDir = "/var/lib/comtrak";
    s3 = {
      endpoint = "http://127.0.0.1:3900";
      bucket = "comtrak-media";
      region = "garage";
    };
    googleClientId = "YOUR_GOOGLE_CLIENT_ID.apps.googleusercontent.com";
    openFirewall = true;
  };

  # 2. Enable Native Garage S3 Service on NixOS
  services.garage = {
    enable = true;
    settings = {
      s3_api = {
        api_bind_addr = "127.0.0.1:3900";
        s3_region = "garage";
      };
      metadata_dir = "/var/lib/garage/meta";
      data_dir = "/var/lib/garage/data";
    };
  };
}
```

---

## 4. Backend API Overview

### Authentication
- `POST /api/v1/auth/google`: Verifies Google ID token, provisions user, and issues a 90-day session JWT.
- `GET /api/v1/auth/me`: Retrieves current authenticated user profile.

### Commissions
- `GET /api/v1/commissions`: Lists commissions sorted by SLA (due date ascending).
- `POST /api/v1/commissions`: Creates a new commission (supports client-generated UUIDs).
- `GET /api/v1/commissions/{id}`: Retrieves commission details.
- `PUT /api/v1/commissions/{id}`: Updates commission details.
- `DELETE /api/v1/commissions/{id}`: Soft-deletes a commission (records tombstone timestamp).

### Updates & Timeline
- `POST /api/v1/commissions/{id}/updates`: Appends an update with state transition, studio note, and attached photo references.
- `GET /api/v1/commissions/{id}/updates`: Lists chronological production timeline.

### Media (WebP Storage & Streaming Proxy)
- `POST /api/v1/media/upload`: Multipart upload of WebP images stored into Garage S3.
- `GET /api/v1/media/{id}`: Streams media from Garage with client-side cache headers.

### Offline Delta Sync
- `POST /api/v1/sync/push`: Batch pushes local offline mutations (Last-Write-Wins conflict resolution).
- `GET /api/v1/sync/pull?since={timestamp}`: Pulls delta changes modified or deleted since the cursor.

---

## 5. Android Application Features

- **Lifecycle Workflow States**:
  1. *Requiring Details* (0%)
  2. *Ready to Begin Making* (25%)
  3. *Need Glazing* (50%)
  4. *Payment & Delivery* (75%)
  5. *Done* (100%)
- **Homepage List**: Sorted by SLA / due date with progress bars, thumbnails, and urgency badges (Overdue, Due Today, Due Soon).
- **Long-Press Menu**:
  - **Share Receipt / Summary**: Generates a clean text receipt formatted for WhatsApp, SMS, or Email.
  - **Delete**: Soft-delete confirmation with sync queue propagation.
- **Client-Side Image Pipeline**:
  - Automatically corrects camera Exif rotation.
  - Downsamples images (max 1920px).
  - Compresses to WebP lossy (quality 80%) before saving to disk.
- **Offline First**:
  - Room SQLite database acts as the single source of truth.
  - Immediate local UI response with zero network latency.
  - Background `WorkManager` synchronization when internet connection is restored.
