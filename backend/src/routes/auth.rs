use axum::{extract::State, http::HeaderMap, Json};
use crate::audit::AuditLogger;
use crate::auth::{create_jwt, AuthUser};
use crate::error::{AppError, Result};
use crate::models::{current_time_millis, new_uuid, AuthResponse, GoogleAuthRequest, User};
use crate::AppState;

pub async fn google_login(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<GoogleAuthRequest>,
) -> Result<Json<AuthResponse>> {
    let token_info = match state.google_verifier.verify_id_token(&payload.id_token).await {
        Ok(info) => info,
        Err(e) => {
            let _ = AuditLogger::log(
                &state.db,
                None,
                None,
                "AUTH_LOGIN_FAILURE",
                "auth",
                None,
                Some(&serde_json::json!({ "reason": e.to_string() })),
                Some(&headers),
            )
            .await;
            return Err(e);
        }
    };

    let now = current_time_millis();

    // Check if user already exists
    let existing_user: Option<User> = sqlx::query_as::<_, User>(
        "SELECT id, google_sub, email, display_name, avatar_url, created_at FROM users WHERE google_sub = $1",
    )
    .bind(&token_info.sub)
    .fetch_optional(&state.db)
    .await?;

    let is_new_user = existing_user.is_none();

    let user = match existing_user {
        Some(user) => {
            // Update display name / avatar if changed
            sqlx::query(
                "UPDATE users SET display_name = COALESCE($1, display_name), avatar_url = COALESCE($2, avatar_url) WHERE id = $3",
            )
            .bind(&token_info.name)
            .bind(&token_info.picture)
            .bind(&user.id)
            .execute(&state.db)
            .await?;

            User {
                id: user.id,
                google_sub: user.google_sub,
                email: user.email,
                display_name: token_info.name.or(user.display_name),
                avatar_url: token_info.picture.or(user.avatar_url),
                created_at: user.created_at,
            }
        }
        None => {
            let new_id = new_uuid();
            sqlx::query(
                "INSERT INTO users (id, google_sub, email, display_name, avatar_url, created_at) VALUES ($1, $2, $3, $4, $5, $6)",
            )
            .bind(&new_id)
            .bind(&token_info.sub)
            .bind(&token_info.email)
            .bind(&token_info.name)
            .bind(&token_info.picture)
            .bind(now)
            .execute(&state.db)
            .await?;

            User {
                id: new_id,
                google_sub: token_info.sub,
                email: token_info.email,
                display_name: token_info.name,
                avatar_url: token_info.picture,
                created_at: now,
            }
        }
    };

    let token = create_jwt(&user.id, &user.email, &state.config.jwt_secret)?;

    AuditLogger::log(
        &state.db,
        Some(&user.id),
        Some(&user.email),
        "AUTH_LOGIN_SUCCESS",
        "user",
        Some(&user.id),
        Some(&serde_json::json!({
            "google_sub": user.google_sub,
            "is_new_user": is_new_user,
        })),
        Some(&headers),
    )
    .await?;

    Ok(Json(AuthResponse { token, user }))
}

pub async fn get_me(
    State(state): State<AppState>,
    auth_user: AuthUser,
) -> Result<Json<User>> {
    let user = sqlx::query_as::<_, User>(
        "SELECT id, google_sub, email, display_name, avatar_url, created_at FROM users WHERE id = $1",
    )
    .bind(&auth_user.user_id)
    .fetch_optional(&state.db)
    .await?
    .ok_or_else(|| AppError::NotFound("User not found".to_string()))?;

    Ok(Json(user))
}
