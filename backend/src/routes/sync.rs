use axum::{
    extract::{Query, State},
    Json,
};
use serde::Deserialize;
use crate::auth::AuthUser;
use crate::error::Result;
use crate::models::{
    current_time_millis, Commission, CommissionUpdate, MediaAttachment,
    SyncPullResponse, SyncPushRequest, SyncPushResponse,
};
use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct SyncPullQuery {
    pub since: Option<i64>,
}

pub async fn sync_push(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Json(payload): Json<SyncPushRequest>,
) -> Result<Json<SyncPushResponse>> {
    let now = current_time_millis();
    let mut accepted_commission_ids = Vec::new();
    let mut accepted_update_ids = Vec::new();

    // 1. Process Commissions with Last-Write-Wins and Multi-tenant Validation
    for comm in payload.commissions {
        let existing = sqlx::query_as::<_, (String, i64, i64)>(
            "SELECT user_id, updated_at, version FROM commissions WHERE id = $1",
        )
        .bind(&comm.id)
        .fetch_optional(&state.db)
        .await?;

        let should_apply = match existing {
            None => true, // Brand new commission
            Some((owner_id, existing_updated_at, _)) => {
                // Reject if existing commission belongs to a different user
                if owner_id != auth_user.user_id {
                    false
                } else {
                    comm.updated_at >= existing_updated_at
                }
            }
        };

        if should_apply {
            sqlx::query(
                r#"
                INSERT INTO commissions (
                    id, user_id, title, description, customer_name, customer_phone, customer_email,
                    item_name, size, colour, quantity, price_cents, due_date, status, clay_body,
                    glazes, thumbnail_media_id, created_at, updated_at, deleted_at, version
                ) VALUES (
                    $1, $2, $3, $4, $5, $6, $7,
                    $8, $9, $10, $11, $12, $13, $14, $15,
                    $16, $17, $18, $19, $20, $21
                )
                ON CONFLICT(id) DO UPDATE SET
                    title = excluded.title,
                    description = excluded.description,
                    customer_name = excluded.customer_name,
                    customer_phone = excluded.customer_phone,
                    customer_email = excluded.customer_email,
                    item_name = excluded.item_name,
                    size = excluded.size,
                    colour = excluded.colour,
                    quantity = excluded.quantity,
                    price_cents = excluded.price_cents,
                    due_date = excluded.due_date,
                    status = excluded.status,
                    clay_body = excluded.clay_body,
                    glazes = excluded.glazes,
                    thumbnail_media_id = COALESCE(excluded.thumbnail_media_id, commissions.thumbnail_media_id),
                    updated_at = excluded.updated_at,
                    deleted_at = excluded.deleted_at,
                    version = commissions.version + 1
                WHERE excluded.updated_at >= commissions.updated_at AND commissions.user_id = excluded.user_id
                "#,
            )
            .bind(&comm.id)
            .bind(&auth_user.user_id)
            .bind(&comm.title)
            .bind(&comm.description)
            .bind(&comm.customer_name)
            .bind(&comm.customer_phone)
            .bind(&comm.customer_email)
            .bind(&comm.item_name)
            .bind(&comm.size)
            .bind(&comm.colour)
            .bind(comm.quantity)
            .bind(comm.price_cents)
            .bind(comm.due_date)
            .bind(&comm.status)
            .bind(&comm.clay_body)
            .bind(&comm.glazes)
            .bind(&comm.thumbnail_media_id)
            .bind(comm.created_at)
            .bind(comm.updated_at)
            .bind(comm.deleted_at)
            .bind(comm.version)
            .execute(&state.db)
            .await?;

            accepted_commission_ids.push(comm.id);
        }
    }

    // 2. Process Updates with Multi-tenant Validation
    for update in payload.updates {
        // Verify parent commission belongs to the user
        let parent_owner = sqlx::query_scalar::<_, String>(
            "SELECT user_id FROM commissions WHERE id = $1",
        )
        .bind(&update.commission_id)
        .fetch_optional(&state.db)
        .await?;

        if parent_owner.as_deref() != Some(&auth_user.user_id) {
            continue;
        }

        sqlx::query(
            r#"
            INSERT INTO commission_updates (
                id, commission_id, user_id, from_status, to_status, note, created_at, deleted_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            ON CONFLICT(id) DO UPDATE SET
                note = excluded.note,
                deleted_at = excluded.deleted_at
            WHERE commission_updates.user_id = excluded.user_id
            "#,
        )
        .bind(&update.id)
        .bind(&update.commission_id)
        .bind(&auth_user.user_id)
        .bind(&update.from_status)
        .bind(&update.to_status)
        .bind(&update.note)
        .bind(update.created_at)
        .bind(update.deleted_at)
        .execute(&state.db)
        .await?;

        accepted_update_ids.push(update.id);
    }

    Ok(Json(SyncPushResponse {
        accepted_commission_ids,
        accepted_update_ids,
        server_time: now,
    }))
}

pub async fn sync_pull(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Query(query): Query<SyncPullQuery>,
) -> Result<Json<SyncPullResponse>> {
    let now = current_time_millis();
    let since = query.since.unwrap_or(0);

    // Fetch all commissions modified since timestamp (including tombstones)
    let commissions = sqlx::query_as::<_, Commission>(
        r#"
        SELECT id, user_id, title, description, customer_name, customer_phone, customer_email,
               item_name, size, colour, quantity, price_cents, due_date, status, clay_body,
               glazes, thumbnail_media_id, created_at, updated_at, deleted_at, version
        FROM commissions
        WHERE user_id = $1 AND updated_at > $2
        ORDER BY updated_at ASC
        "#,
    )
    .bind(&auth_user.user_id)
    .bind(since)
    .fetch_all(&state.db)
    .await?;

    // Fetch all updates created or deleted since timestamp
    let updates = sqlx::query_as::<_, CommissionUpdate>(
        r#"
        SELECT id, commission_id, user_id, from_status, to_status, note, created_at, deleted_at
        FROM commission_updates
        WHERE user_id = $1 AND (created_at > $2 OR deleted_at > $2)
        ORDER BY created_at ASC
        "#,
    )
    .bind(&auth_user.user_id)
    .bind(since)
    .fetch_all(&state.db)
    .await?;

    // Fetch media attachments created or deleted since timestamp
    let media = sqlx::query_as::<_, MediaAttachment>(
        r#"
        SELECT * FROM media_attachments
        WHERE user_id = $1 AND (created_at > $2 OR deleted_at > $2)
        ORDER BY created_at ASC
        "#,
    )
    .bind(&auth_user.user_id)
    .bind(since)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(SyncPullResponse {
        server_time: now,
        commissions,
        updates,
        media,
    }))
}
