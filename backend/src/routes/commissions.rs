use axum::{
    extract::{Path, State},
    Json,
};
use crate::auth::AuthUser;
use crate::error::{AppError, Result};
use crate::models::{
    current_time_millis, new_uuid, Commission, CreateCommissionRequest, UpdateCommissionRequest,
};
use crate::AppState;

pub async fn list_commissions(
    State(state): State<AppState>,
    auth_user: AuthUser,
) -> Result<Json<Vec<Commission>>> {
    let commissions = sqlx::query_as::<_, Commission>(
        r#"
        SELECT id, user_id, title, description, customer_name, customer_phone, customer_email,
               item_name, size, colour, quantity, price_cents, due_date, status, clay_body,
               glazes, thumbnail_media_id, created_at, updated_at, deleted_at, version
        FROM commissions
        WHERE user_id = $1 AND deleted_at IS NULL
        ORDER BY 
            CASE WHEN due_date IS NULL THEN 1 ELSE 0 END,
            due_date ASC,
            created_at DESC
        "#,
    )
    .bind(&auth_user.user_id)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(commissions))
}

pub async fn create_commission(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Json(payload): Json<CreateCommissionRequest>,
) -> Result<Json<Commission>> {
    let now = current_time_millis();
    let id = payload.id.unwrap_or_else(new_uuid);
    let quantity = payload.quantity.unwrap_or(1);
    let status = payload.status.unwrap_or_else(|| "requiring_details".to_string());

    sqlx::query(
        r#"
        INSERT INTO commissions (
            id, user_id, title, description, customer_name, customer_phone, customer_email,
            item_name, size, colour, quantity, price_cents, due_date, status, clay_body,
            glazes, thumbnail_media_id, created_at, updated_at, deleted_at, version
        ) VALUES (
            $1, $2, $3, $4, $5, $6, $7,
            $8, $9, $10, $11, $12, $13, $14, $15,
            $16, $17, $18, $19, NULL, 1
        )
        "#,
    )
    .bind(&id)
    .bind(&auth_user.user_id)
    .bind(&payload.title)
    .bind(&payload.description)
    .bind(&payload.customer_name)
    .bind(&payload.customer_phone)
    .bind(&payload.customer_email)
    .bind(&payload.item_name)
    .bind(&payload.size)
    .bind(&payload.colour)
    .bind(quantity)
    .bind(payload.price_cents)
    .bind(payload.due_date)
    .bind(&status)
    .bind(&payload.clay_body)
    .bind(&payload.glazes)
    .bind(&payload.thumbnail_media_id)
    .bind(now)
    .bind(now)
    .execute(&state.db)
    .await?;

    let commission = sqlx::query_as::<_, Commission>(
        r#"
        SELECT id, user_id, title, description, customer_name, customer_phone, customer_email,
               item_name, size, colour, quantity, price_cents, due_date, status, clay_body,
               glazes, thumbnail_media_id, created_at, updated_at, deleted_at, version
        FROM commissions
        WHERE id = $1
        "#,
    )
    .bind(&id)
    .fetch_one(&state.db)
    .await?;

    Ok(Json(commission))
}

pub async fn get_commission(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Path(id): Path<String>,
) -> Result<Json<Commission>> {
    let commission = sqlx::query_as::<_, Commission>(
        r#"
        SELECT id, user_id, title, description, customer_name, customer_phone, customer_email,
               item_name, size, colour, quantity, price_cents, due_date, status, clay_body,
               glazes, thumbnail_media_id, created_at, updated_at, deleted_at, version
        FROM commissions
        WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL
        "#,
    )
    .bind(&id)
    .bind(&auth_user.user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(|| AppError::NotFound(format!("Commission '{}' not found", id)))?;

    Ok(Json(commission))
}

pub async fn update_commission(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Path(id): Path<String>,
    Json(payload): Json<UpdateCommissionRequest>,
) -> Result<Json<Commission>> {
    let now = current_time_millis();

    let existing = sqlx::query_as::<_, Commission>(
        "SELECT * FROM commissions WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL",
    )
    .bind(&id)
    .bind(&auth_user.user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(|| AppError::NotFound(format!("Commission '{}' not found", id)))?;

    let title = payload.title.unwrap_or(existing.title);
    let description = payload.description.or(existing.description);
    let customer_name = payload.customer_name.unwrap_or(existing.customer_name);
    let customer_phone = payload.customer_phone.or(existing.customer_phone);
    let customer_email = payload.customer_email.or(existing.customer_email);
    let item_name = payload.item_name.unwrap_or(existing.item_name);
    let size = payload.size.or(existing.size);
    let colour = payload.colour.or(existing.colour);
    let quantity = payload.quantity.unwrap_or(existing.quantity);
    let price_cents = payload.price_cents.or(existing.price_cents);
    let due_date = payload.due_date.or(existing.due_date);
    let status = payload.status.unwrap_or(existing.status);
    let clay_body = payload.clay_body.or(existing.clay_body);
    let glazes = payload.glazes.or(existing.glazes);
    let thumbnail_media_id = payload.thumbnail_media_id.or(existing.thumbnail_media_id);
    let version = existing.version + 1;

    sqlx::query(
        r#"
        UPDATE commissions SET
            title = $1, description = $2, customer_name = $3, customer_phone = $4,
            customer_email = $5, item_name = $6, size = $7, colour = $8,
            quantity = $9, price_cents = $10, due_date = $11, status = $12,
            clay_body = $13, glazes = $14, thumbnail_media_id = $15,
            updated_at = $16, version = $17
        WHERE id = $18 AND user_id = $19
        "#,
    )
    .bind(&title)
    .bind(&description)
    .bind(&customer_name)
    .bind(&customer_phone)
    .bind(&customer_email)
    .bind(&item_name)
    .bind(&size)
    .bind(&colour)
    .bind(quantity)
    .bind(price_cents)
    .bind(due_date)
    .bind(&status)
    .bind(&clay_body)
    .bind(&glazes)
    .bind(&thumbnail_media_id)
    .bind(now)
    .bind(version)
    .bind(&id)
    .bind(&auth_user.user_id)
    .execute(&state.db)
    .await?;

    let updated = sqlx::query_as::<_, Commission>(
        "SELECT * FROM commissions WHERE id = $1",
    )
    .bind(&id)
    .fetch_one(&state.db)
    .await?;

    Ok(Json(updated))
}

pub async fn delete_commission(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>> {
    let now = current_time_millis();

    let result = sqlx::query(
        "UPDATE commissions SET deleted_at = $1, updated_at = $1, version = version + 1 WHERE id = $2 AND user_id = $3 AND deleted_at IS NULL",
    )
    .bind(now)
    .bind(&id)
    .bind(&auth_user.user_id)
    .execute(&state.db)
    .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::NotFound(format!("Commission '{}' not found", id)));
    }

    Ok(Json(serde_json::json!({
        "success": true,
        "id": id,
        "deleted_at": now
    })))
}
