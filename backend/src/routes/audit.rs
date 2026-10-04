use axum::{
    extract::{Query, State},
    Json,
};
use serde::Deserialize;
use crate::auth::AuthUser;
use crate::error::Result;
use crate::models::AuditLog;
use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct AuditLogQuery {
    pub limit: Option<i64>,
    pub action: Option<String>,
    pub entity_type: Option<String>,
    pub entity_id: Option<String>,
    pub since: Option<i64>,
}

pub async fn list_audit_logs(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Query(query): Query<AuditLogQuery>,
) -> Result<Json<Vec<AuditLog>>> {
    let limit = query.limit.unwrap_or(50).clamp(1, 200);
    let since = query.since.unwrap_or(0);

    let logs = sqlx::query_as::<_, AuditLog>(
        r#"
        SELECT id, user_id, actor_email, action, entity_type, entity_id,
               details, ip_address, user_agent, created_at
        FROM audit_logs
        WHERE user_id = $1
          AND created_at > $2
          AND ($3 IS NULL OR action = $3)
          AND ($4 IS NULL OR entity_type = $4)
          AND ($5 IS NULL OR entity_id = $5)
        ORDER BY created_at DESC
        LIMIT $6
        "#,
    )
    .bind(&auth_user.user_id)
    .bind(since)
    .bind(query.action)
    .bind(query.entity_type)
    .bind(query.entity_id)
    .bind(limit)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(logs))
}
