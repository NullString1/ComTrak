mod auth;
mod config;
mod db;
mod error;
mod models;
mod routes;
mod storage;

use std::sync::Arc;
use tokio::net::TcpListener;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use crate::auth::GoogleVerifier;
use crate::config::AppConfig;
use crate::db::{init_db, DbPool};
use crate::storage::StorageService;

#[derive(Clone)]
pub struct AppState {
    pub db: DbPool,
    pub storage: StorageService,
    pub config: Arc<AppConfig>,
    pub google_verifier: Arc<GoogleVerifier>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // 1. Initialize logging
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,comtrak_backend=debug,tower_http=info".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    tracing::info!("Starting ComTrak Backend Service...");

    // 2. Load Configuration
    let config = Arc::new(AppConfig::from_env());
    tracing::info!("Binding to {}:{}", config.host, config.port);
    tracing::info!("Using database: {}", config.database_url);
    tracing::info!("Using Garage S3 endpoint: {}", config.garage_endpoint);

    // 3. Initialize SQLite Database
    let db = init_db(&config.database_url, &config.data_dir).await?;

    // 4. Initialize Garage S3 Storage
    let storage = StorageService::new(&config).await;
    if let Err(e) = storage.ensure_bucket().await {
        tracing::warn!("Notice during S3 bucket verification: {}. Will continue in case S3 is coming online.", e);
    }

    // 5. Initialize Google Auth Verifier
    let google_verifier = Arc::new(GoogleVerifier::new(config.google_client_id.clone()));

    // 6. Assemble AppState and Router
    let state = AppState {
        db,
        storage,
        config: config.clone(),
        google_verifier,
    };

    let app = routes::create_router(state);

    // 7. Bind TCP Listener and Serve
    let bind_addr = format!("{}:{}", config.host, config.port);
    let listener = TcpListener::bind(&bind_addr).await?;
    tracing::info!("ComTrak backend listening on http://{}", bind_addr);

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    tracing::info!("ComTrak backend shutdown cleanly.");
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {
            tracing::info!("Received Ctrl+C, initiating graceful shutdown...");
        },
        _ = terminate => {
            tracing::info!("Received SIGTERM, initiating graceful shutdown...");
        },
    }
}
