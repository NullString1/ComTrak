use axum::{
    extract::{FromRef, FromRequestParts},
    http::{header::AUTHORIZATION, request::Parts},
};
use chrono::{Duration, Utc};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use crate::error::{AppError, Result};

#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,       // User ID
    pub email: String,     // User Email
    pub exp: i64,          // Expiration timestamp
    pub iat: i64,          // Issued at timestamp
}

#[derive(Debug, Clone)]
pub struct AuthUser {
    pub user_id: String,
    #[allow(dead_code)]
    pub email: String,
}

pub fn create_jwt(user_id: &str, email: &str, secret: &str) -> Result<String> {
    let now = Utc::now();
    let exp = now + Duration::days(90);

    let claims = Claims {
        sub: user_id.to_string(),
        email: email.to_string(),
        iat: now.timestamp(),
        exp: exp.timestamp(),
    };

    encode(
        &Header::default(),
        &claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .map_err(|e| AppError::Auth(format!("Failed to generate token: {}", e)))
}

pub fn verify_jwt(token: &str, secret: &str) -> Result<Claims> {
    let validation = Validation::default();
    let token_data = decode::<Claims>(
        token,
        &DecodingKey::from_secret(secret.as_bytes()),
        &validation,
    )
    .map_err(|e| AppError::Auth(format!("Invalid or expired authentication token: {}", e)))?;

    Ok(token_data.claims)
}

impl<S> FromRequestParts<S> for AuthUser
where
    S: Send + Sync,
    crate::AppState: FromRef<S>,
{
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self> {
        let app_state = crate::AppState::from_ref(state);

        let auth_header = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|h| h.to_str().ok())
            .ok_or_else(|| AppError::Auth("Missing Authorization header".to_string()))?;

        let token = auth_header
            .strip_prefix("Bearer ")
            .ok_or_else(|| AppError::Auth("Invalid Authorization header format. Expected 'Bearer <token>'".to_string()))?;

        let claims = verify_jwt(token, &app_state.config.jwt_secret)?;

        Ok(AuthUser {
            user_id: claims.sub,
            email: claims.email,
        })
    }
}
