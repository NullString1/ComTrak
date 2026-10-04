# -------------------------------------------------------------
# Multi-stage Cargo Chef Build for ComTrak Backend
# -------------------------------------------------------------
FROM lukemathwalker/cargo-chef:latest-rust-1-bookworm AS chef
WORKDIR /app

FROM chef AS planner
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

FROM chef AS builder
COPY --from=planner /app/recipe.json recipe.json
# Build dependencies - this is cached unless Cargo.toml/Cargo.lock changes!
RUN cargo chef cook --release --recipe-path recipe.json

# Build application
COPY . .
RUN cargo build --release --bin comtrak-backend

# -------------------------------------------------------------
# Runtime Image
# -------------------------------------------------------------
FROM debian:bookworm-slim AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    sqlite3 \
    libsqlite3-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /app/target/release/comtrak-backend /usr/local/bin/comtrak-backend
COPY --from=builder /app/migrations /app/migrations

ENV DATA_DIR=/data
ENV HOST=0.0.0.0
ENV PORT=8080
ENV RUST_LOG=info,comtrak_backend=debug

RUN mkdir -p /data

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/comtrak-backend"]
