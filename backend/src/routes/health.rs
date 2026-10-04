use axum::{extract::State, Json};
use serde_json::{json, Value};
use crate::AppState;

pub async fn health_check(State(state): State<AppState>) -> Json<Value> {
    let db_ok = sqlx::query("SELECT 1")
        .execute(&state.db)
        .await
        .is_ok();

    Json(json!({
        "status": "healthy",
        "service": "comtrak-backend",
        "version": env!("CARGO_PKG_VERSION"),
        "database": if db_ok { "connected" } else { "error" },
        "storage": "garage-s3",
    }))
}
