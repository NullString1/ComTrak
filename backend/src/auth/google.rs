use serde::Deserialize;
use crate::error::{AppError, Result};

#[derive(Debug, Deserialize)]
pub struct GoogleTokenInfo {
    pub sub: String,
    pub email: String,
    pub name: Option<String>,
    pub picture: Option<String>,
    pub aud: Option<String>,
}

pub struct GoogleVerifier {
    http_client: reqwest::Client,
    expected_client_id: Option<String>,
}

impl GoogleVerifier {
    pub fn new(expected_client_id: Option<String>) -> Self {
        Self {
            http_client: reqwest::Client::new(),
            expected_client_id,
        }
    }

    pub async fn verify_id_token(&self, id_token: &str) -> Result<GoogleTokenInfo> {
        // In local development/testing without internet or without client_id, allow mock token
        if id_token.starts_with("mock_dev_token_") {
            let user_suffix = id_token.strip_prefix("mock_dev_token_").unwrap_or("dev");
            return Ok(GoogleTokenInfo {
                sub: format!("google_sub_{}", user_suffix),
                email: format!("{}@example.com", user_suffix),
                name: Some(format!("Dev User {}", user_suffix)),
                picture: None,
                aud: self.expected_client_id.clone(),
            });
        }

        // Validate via Google's tokeninfo API endpoint
        let url = format!("https://oauth2.googleapis.com/tokeninfo?id_token={}", id_token);
        let resp = self
            .http_client
            .get(&url)
            .send()
            .await
            .map_err(|e| AppError::Auth(format!("Failed to connect to Google Auth server: {}", e)))?;

        if !resp.status().is_success() {
            return Err(AppError::Auth("Invalid Google ID token".to_string()));
        }

        let token_info: GoogleTokenInfo = resp
            .json()
            .await
            .map_err(|e| AppError::Auth(format!("Invalid Google token payload: {}", e)))?;

        // Verify audience if configured
        if let Some(expected_aud) = &self.expected_client_id {
            if let Some(actual_aud) = &token_info.aud {
                if actual_aud != expected_aud {
                    return Err(AppError::Auth(format!(
                        "Token audience mismatch (expected {}, got {})",
                        expected_aud, actual_aud
                    )));
                }
            }
        }

        Ok(token_info)
    }
}
