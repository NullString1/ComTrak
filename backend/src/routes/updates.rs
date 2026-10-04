use axum::{
    extract::{Path, State},
    Json,
};
use crate::auth::AuthUser;
use crate::error::{AppError, Result};
use crate::models::{
    current_time_millis, new_uuid, CommissionUpdate, CreateUpdateRequest,
};
use crate::AppState;

pub async fn list_updates(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Path(commission_id): Path<String>,
) -> Result<Json<Vec<CommissionUpdate>>> {
    // Verify commission belongs to user
    let exists = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(1) FROM commissions WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL",
    )
    .bind(&commission_id)
    .bind(&auth_user.user_id)
    .fetch_one(&state.db)
    .await?;

    if exists == 0 {
        return Err(AppError::NotFound("Commission not found".to_string()));
    }

    let updates = sqlx::query_as::<_, CommissionUpdate>(
        r#"
        SELECT id, commission_id, user_id, from_status, to_status, note, created_at, deleted_at
        FROM commission_updates
        WHERE commission_id = $1 AND deleted_at IS NULL
        ORDER BY created_at ASC
        "#,
    )
    .bind(&commission_id)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(updates))
}

pub async fn add_update(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Path(commission_id): Path<String>,
    Json(payload): Json<CreateUpdateRequest>,
) -> Result<Json<CommissionUpdate>> {
    let now = current_time_millis();
    let id = payload.id.unwrap_or_else(new_uuid);

    // Verify commission ownership
    let current_status: Option<String> = sqlx::query_scalar(
        "SELECT status FROM commissions WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL",
    )
    .bind(&commission_id)
    .bind(&auth_user.user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(|| AppError::NotFound("Commission not found".to_string()))?;

    let from_status = payload.from_status.or(current_status);
    let to_status = payload.to_status;

    // Insert update record
    sqlx::query(
        r#"
        INSERT INTO commission_updates (
            id, commission_id, user_id, from_status, to_status, note, created_at, deleted_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, NULL)
        "#,
    )
    .bind(&id)
    .bind(&commission_id)
    .bind(&auth_user.user_id)
    .bind(&from_status)
    .bind(&to_status)
    .bind(&payload.note)
    .bind(now)
    .execute(&state.db)
    .await?;

    // If status changed, update the parent commission
    if let Some(ref new_status) = to_status {
        sqlx::query(
            "UPDATE commissions SET status = $1, updated_at = $2, version = version + 1 WHERE id = $3",
        )
        .bind(new_status)
        .bind(now)
        .bind(&commission_id)
        .execute(&state.db)
        .await?;
    } else {
        // Bump updated_at on commission
        sqlx::query(
            "UPDATE commissions SET updated_at = $1, version = version + 1 WHERE id = $2",
        )
        .bind(now)
        .bind(&commission_id)
        .execute(&state.db)
        .await?;
    }

    // Link any media items to this update
    if let Some(media_ids) = payload.media_ids {
        for media_id in media_ids {
            sqlx::query(
                "UPDATE media_attachments SET update_id = $1 WHERE id = $2 AND user_id = $3",
            )
            .bind(&id)
            .bind(&media_id)
            .bind(&auth_user.user_id)
            .execute(&state.db)
            .await?;
        }
    }

    let update = sqlx::query_as::<_, CommissionUpdate>(
        "SELECT * FROM commission_updates WHERE id = $1",
    )
    .bind(&id)
    .fetch_one(&state.db)
    .await?;

    Ok(Json(update))
}
