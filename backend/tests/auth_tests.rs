mod common;

use chrono::{Duration, Utc};
use jsonwebtoken::{encode, EncodingKey, Header};
use comtrak_backend::auth::jwt::{create_jwt, verify_jwt, Claims};
use comtrak_backend::auth::GoogleVerifier;
use comtrak_backend::error::AppError;

#[test]
fn test_jwt_create_and_verify_roundtrip() {
    let secret = "secure-production-grade-secret-key-987654321";
    let user_id = "user_potter_12345";
    let email = "potter@ceramics-studio.co.uk";

    let token = create_jwt(user_id, email, secret).expect("Token generation must succeed");
    assert!(!token.is_empty(), "Token must not be empty");

    let claims = verify_jwt(&token, secret).expect("Token verification must succeed");
    assert_eq!(claims.sub, user_id, "Subject claim must match input user ID");
    assert_eq!(claims.email, email, "Email claim must match input email");
    assert!(claims.exp > claims.iat, "Expiration must be strictly after issued timestamp");
}

#[test]
fn test_jwt_wrong_secret_rejected() {
    let secret_correct = "correct-secret-12345678901234567890";
    let secret_wrong = "wrong-secret-09876543210987654321";
    let user_id = "potter_test";
    let email = "test@example.com";

    let token = create_jwt(user_id, email, secret_correct).unwrap();

    let result = verify_jwt(&token, secret_wrong);
    assert!(result.is_err(), "Verification with incorrect secret must fail");

    match result {
        Err(AppError::Auth(msg)) => {
            assert!(msg.contains("Invalid or expired authentication token"));
        }
        other => panic!("Expected AppError::Auth, got {:?}", other),
    }
}

#[test]
fn test_jwt_tampered_payload_rejected() {
    let secret = "test-secret-key-1234567890";
    let token = create_jwt("alice", "alice@example.com", secret).unwrap();

    // Tamper with the middle payload part of the JWT
    let parts: Vec<&str> = token.split('.').collect();
    assert_eq!(parts.len(), 3, "JWT must consist of 3 dot-separated parts");

    // Replace first char of signature
    let tampered_sig = if parts[2].starts_with('A') {
        format!("B{}", &parts[2][1..])
    } else {
        format!("A{}", &parts[2][1..])
    };
    let tampered_token = format!("{}.{}.{}", parts[0], parts[1], tampered_sig);

    let result = verify_jwt(&tampered_token, secret);
    assert!(result.is_err(), "Tampered signature must be rejected");
}

#[test]
fn test_jwt_expired_token_rejected() {
    let secret = "test-secret-key-1234567890";
    let past = Utc::now() - Duration::days(5);

    let expired_claims = Claims {
        sub: "user_expired".to_string(),
        email: "expired@example.com".to_string(),
        iat: (past - Duration::days(1)).timestamp(),
        exp: past.timestamp(), // Expired 5 days ago
    };

    let expired_token = encode(
        &Header::default(),
        &expired_claims,
        &EncodingKey::from_secret(secret.as_bytes()),
    )
    .expect("Encoding expired claims");

    let result = verify_jwt(&expired_token, secret);
    assert!(result.is_err(), "Expired token must be rejected");
}

#[tokio::test]
async fn test_google_dev_token_verifier() {
    let verifier = GoogleVerifier::new(None);

    let info = verifier
        .verify_id_token("mock_dev_token_craftsman_bob")
        .await
        .expect("Mock token must verify successfully");

    assert_eq!(info.sub, "google_sub_craftsman_bob");
    assert_eq!(info.email, "craftsman_bob@example.com");
    assert_eq!(info.name, Some("Dev User craftsman_bob".to_string()));
}

#[tokio::test]
async fn test_google_dev_token_default_suffix() {
    let verifier = GoogleVerifier::new(None);

    let info = verifier
        .verify_id_token("mock_dev_token_")
        .await
        .expect("Empty suffix mock token must fallback gracefully");

    assert_eq!(info.sub, "google_sub_dev");
    assert_eq!(info.email, "dev@example.com");
}
