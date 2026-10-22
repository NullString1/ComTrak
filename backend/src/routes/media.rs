use axum::{
    body::Body,
    extract::{Multipart, Path, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use crate::audit::AuditLogger;
use crate::auth::AuthUser;
use crate::error::{AppError, Result};
use crate::models::{
    current_time_millis, new_uuid, MediaAttachment, MediaUploadResponse,
};
use crate::AppState;

pub async fn upload_media(
    State(state): State<AppState>,
    auth_user: AuthUser,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Result<Json<MediaUploadResponse>> {
    let mut file_bytes: Option<Vec<u8>> = None;
    let mut commission_id: Option<String> = None;
    let mut update_id: Option<String> = None;
    let mut media_type = "progress".to_string();
    let mut client_media_id: Option<String> = None;
    let mut mime_type = "image/webp".to_string();

    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::BadRequest(format!("Multipart error: {}", e)))?
    {
        let name = field.name().unwrap_or("").to_string();

        match name.as_str() {
            "file" => {
                if let Some(ct) = field.content_type() {
                    mime_type = ct.to_string();
                }
                let bytes = field
                    .bytes()
                    .await
                    .map_err(|e| AppError::BadRequest(format!("Failed reading file field: {}", e)))?;
                file_bytes = Some(bytes.to_vec());
            }
            "commission_id" => {
                let text = field.text().await.unwrap_or_default();
                if !text.is_empty() {
                    commission_id = Some(text);
                }
            }
            "update_id" => {
                let text = field.text().await.unwrap_or_default();
                if !text.is_empty() {
                    update_id = Some(text);
                }
            }
            "media_type" => {
                let text = field.text().await.unwrap_or_default();
                if !text.is_empty() {
                    media_type = text;
                }
            }
            "media_id" => {
                let text = field.text().await.unwrap_or_default();
                if !text.is_empty() {
                    client_media_id = Some(text);
                }
            }
            _ => {}
        }
    }

    let bytes = file_bytes.ok_or_else(|| AppError::BadRequest("No 'file' field provided in multipart form".to_string()))?;
    let comm_id = commission_id.ok_or_else(|| AppError::BadRequest("No 'commission_id' field provided".to_string()))?;

    // Verify user owns the commission
    let exists = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(1) FROM commissions WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL",
    )
    .bind(&comm_id)
    .bind(&auth_user.user_id)
    .fetch_one(&state.db)
    .await?;

    if exists == 0 {
        return Err(AppError::NotFound("Commission not found".to_string()));
    }

    let media_id = client_media_id.unwrap_or_else(new_uuid);
    let s3_key = format!("commissions/{}/{}.webp", comm_id, media_id);
    let file_size = bytes.len() as i64;
    let now = current_time_millis();

    // 1. Upload to Garage S3
    state.storage.upload_object(&s3_key, bytes, &mime_type).await?;

    // 2. Insert into database
    sqlx::query(
        r#"
        INSERT INTO media_attachments (
            id, user_id, commission_id, update_id, media_type, s3_key,
            mime_type, width, height, file_size_bytes, created_at, deleted_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, NULL, NULL, $8, $9, NULL)
        ON CONFLICT(id) DO UPDATE SET
            s3_key = excluded.s3_key,
            media_type = excluded.media_type,
            file_size_bytes = excluded.file_size_bytes
        "#,
    )
    .bind(&media_id)
    .bind(&auth_user.user_id)
    .bind(&comm_id)
    .bind(&update_id)
    .bind(&media_type)
    .bind(&s3_key)
    .bind(&mime_type)
    .bind(file_size)
    .bind(now)
    .execute(&state.db)
    .await?;

    // 3. Recompute thumbnail: latest progress photo wins; reference (just-uploaded)
    // media is thumbnail only if no progress photos exist.
    let thumbnail_id: Option<String> = sqlx::query_scalar::<_, String>(
        "SELECT id FROM media_attachments WHERE commission_id = $1 AND media_type = 'progress' AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 1",
    )
    .bind(&comm_id)
    .fetch_optional(&state.db)
    .await?;
    let thumbnail_id = thumbnail_id.unwrap_or_else(|| media_id.clone());

    sqlx::query(
        "UPDATE commissions SET thumbnail_media_id = $1 WHERE id = $2",
    )
    .bind(&thumbnail_id)
    .bind(&comm_id)
    .execute(&state.db)
    .await?;

    let media_url = format!("/api/v1/media/{}", media_id);

    AuditLogger::log(
        &state.db,
        Some(&auth_user.user_id),
        None,
        "MEDIA_UPLOAD",
        "media_attachment",
        Some(&media_id),
        Some(&serde_json::json!({
            "commission_id": comm_id,
            "media_type": media_type,
            "file_size_bytes": file_size,
            "mime_type": mime_type,
        })),
        Some(&headers),
    )
    .await?;

    Ok(Json(MediaUploadResponse {
        id: media_id,
        commission_id: comm_id,
        media_type,
        mime_type,
        url: media_url,
    }))
}

pub async fn delete_media(
    State(state): State<AppState>,
    auth_user: AuthUser,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>> {
    let now = current_time_millis();

    let result = sqlx::query(
        "UPDATE media_attachments SET deleted_at = $1 WHERE id = $2 AND user_id = $3 AND deleted_at IS NULL",
    )
    .bind(now)
    .bind(&id)
    .bind(&auth_user.user_id)
    .execute(&state.db)
    .await?;

    if result.rows_affected() == 0 {
        return Err(AppError::NotFound(format!("Media item '{}' not found", id)));
    }

    AuditLogger::log(
        &state.db,
        Some(&auth_user.user_id),
        None,
        "MEDIA_SOFT_DELETE",
        "media_attachment",
        Some(&id),
        Some(&serde_json::json!({ "deleted_at": now })),
        Some(&headers),
    )
    .await?;

    Ok(Json(serde_json::json!({
        "success": true,
        "id": id,
        "deleted_at": now
    })))
}

pub async fn get_media(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Response> {
    let media = sqlx::query_as::<_, MediaAttachment>(
        "SELECT * FROM media_attachments WHERE id = $1 AND deleted_at IS NULL",
    )
    .bind(&id)
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(|| AppError::NotFound(format!("Media item '{}' not found", id)))?;

    let (bytes, mime_type) = state.storage.get_object(&media.s3_key).await?;

    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&mime_type).unwrap_or(HeaderValue::from_static("image/webp")),
    );
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("public, max-age=31536000, immutable"),
    );

    Ok((StatusCode::OK, headers, Body::from(bytes)).into_response())
}

pub async fn list_commission_media(
    State(state): State<AppState>,
    auth_user: AuthUser,
    Path(commission_id): Path<String>,
) -> Result<Json<Vec<MediaAttachment>>> {
    let media = sqlx::query_as::<_, MediaAttachment>(
        r#"
        SELECT * FROM media_attachments
        WHERE commission_id = $1 AND user_id = $2 AND deleted_at IS NULL
        ORDER BY created_at ASC
        "#,
    )
    .bind(&commission_id)
    .bind(&auth_user.user_id)
    .fetch_all(&state.db)
    .await?;

    Ok(Json(media))
}
