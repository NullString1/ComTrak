use axum::http::{header, HeaderMap};
use crate::error::Result;
use crate::models::{current_time_millis, new_uuid};
use sqlx::SqlitePool;

pub struct AuditLogger;

impl AuditLogger {
    pub async fn log(
        db: &SqlitePool,
        user_id: Option<&str>,
        actor_email: Option<&str>,
        action: &str,
        entity_type: &str,
        entity_id: Option<&str>,
        details: Option<&serde_json::Value>,
        headers: Option<&HeaderMap>,
    ) -> Result<()> {
        let id = new_uuid();
        let now = current_time_millis();
        let details_json = details.map(|d| d.to_string());

        let (ip_address, user_agent) = match headers {
            Some(h) => extract_client_info(h),
            None => (None, None),
        };

        sqlx::query(
            r#"
            INSERT INTO audit_logs (
                id, user_id, actor_email, action, entity_type, entity_id,
                details, ip_address, user_agent, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
            "#,
        )
        .bind(&id)
        .bind(user_id)
        .bind(actor_email)
        .bind(action)
        .bind(entity_type)
        .bind(entity_id)
        .bind(&details_json)
        .bind(&ip_address)
        .bind(&user_agent)
        .bind(now)
        .execute(db)
        .await?;

        Ok(())
    }
}

pub fn extract_client_info(headers: &HeaderMap) -> (Option<String>, Option<String>) {
    let ip = headers
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.split(',').next().unwrap_or(s).trim().to_string())
        .or_else(|| {
            headers
                .get("x-real-ip")
                .and_then(|v| v.to_str().ok())
                .map(|s| s.trim().to_string())
        });

    let user_agent = headers
        .get(header::USER_AGENT)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());

    (ip, user_agent)
}
