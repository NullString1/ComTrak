pub mod audit;
pub mod auth;
pub mod commissions;
pub mod health;
pub mod media;
pub mod sync;
pub mod updates;

use axum::{
    routing::{delete, get, post},
    Router,
};
use tower_http::cors::{Any, CorsLayer};
use tower_http::trace::TraceLayer;
use crate::AppState;

pub fn create_router(state: AppState) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    Router::new()
        // Health check
        .route("/api/v1/health", get(health::health_check))

        // Authentication
        .route("/api/v1/auth/google", post(auth::google_login))
        .route("/api/v1/auth/me", get(auth::get_me))

        // Audit Logs
        .route("/api/v1/audit-logs", get(audit::list_audit_logs))

        // Commissions CRUD
        .route("/api/v1/commissions", get(commissions::list_commissions).post(commissions::create_commission))
        .route("/api/v1/commissions/{id}", get(commissions::get_commission).put(commissions::update_commission).delete(commissions::delete_commission))

        // Updates & Timeline
        .route("/api/v1/commissions/{id}/updates", get(updates::list_updates).post(updates::add_update))
        .route("/api/v1/commissions/{id}/updates/{update_id}", delete(updates::delete_update))

        // Media attachments & streaming proxy
        .route("/api/v1/media/upload", post(media::upload_media))
        .route("/api/v1/media/{id}", get(media::get_media).delete(media::delete_media))
        .route("/api/v1/commissions/{id}/media", get(media::list_commission_media))

        // Offline Delta Sync
        .route("/api/v1/sync/push", post(sync::sync_push))
        .route("/api/v1/sync/pull", get(sync::sync_pull))

        // Global middleware
        .layer(cors)
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}
