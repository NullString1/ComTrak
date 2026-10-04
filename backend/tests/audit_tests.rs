mod common;

use axum::extract::{Path, Query, State};
use axum::http::HeaderMap;
use axum::Json;

use common::setup_test_context;
use comtrak_backend::models::{
    CreateCommissionRequest, CreateUpdateRequest, GoogleAuthRequest, SyncPushRequest,
};
use comtrak_backend::routes::audit::{list_audit_logs, AuditLogQuery};
use comtrak_backend::routes::auth::google_login;
use comtrak_backend::routes::commissions::{create_commission, delete_commission, update_commission};
use comtrak_backend::routes::media::delete_media;
use comtrak_backend::routes::sync::{sync_pull, sync_push, SyncPullQuery};
use comtrak_backend::routes::updates::{add_update, delete_update};

#[tokio::test]
async fn test_audit_login_success() {
    let ctx = setup_test_context().await;

    let login_payload = GoogleAuthRequest {
        id_token: "mock_dev_token_audit_test_user".to_string(),
    };

    let Json(auth_resp) = google_login(
        State(ctx.state.clone()),
        HeaderMap::new(),
        Json(login_payload),
    )
    .await
    .unwrap();

    let user_id = auth_resp.user.id;

    // Query audit logs for this user
    let Json(logs) = list_audit_logs(
        State(ctx.state.clone()),
        comtrak_backend::auth::AuthUser {
            user_id: user_id.clone(),
            email: auth_resp.user.email.clone(),
        },
        Query(AuditLogQuery {
            limit: Some(10),
            action: Some("AUTH_LOGIN_SUCCESS".to_string()),
            entity_type: None,
            entity_id: None,
            since: None,
        }),
    )
    .await
    .unwrap();

    assert!(!logs.is_empty(), "Must have at least one login audit log");
    let log = &logs[0];
    assert_eq!(log.action, "AUTH_LOGIN_SUCCESS");
    assert_eq!(log.entity_type, "user");
    assert_eq!(log.actor_email, Some(auth_resp.user.email));
    assert!(log.details.is_some());
    assert!(log.details.as_ref().unwrap().contains("audit_test_user"));
}

#[tokio::test]
async fn test_audit_commission_lifecycle_events() {
    let ctx = setup_test_context().await;

    // 1. Create commission -> COMMISSION_CREATE
    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Audit Test Vase".to_string(),
            description: None,
            customer_name: "Audit Customer".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Vase".to_string(),
            size: None,
            colour: None,
            quantity: Some(1),
            price_cents: Some(10000),
            due_date: None,
            status: Some("requiring_details".to_string()),
            clay_body: None,
            glazes: None,
            thumbnail_media_id: None,
        }),
    )
    .await
    .unwrap();

    // 2. Update commission -> COMMISSION_UPDATE
    let Json(_updated) = update_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(comm.id.clone()),
        Json(comtrak_backend::models::UpdateCommissionRequest {
            title: Some("Audit Test Vase (Renamed)".to_string()),
            description: None,
            customer_name: None,
            customer_phone: None,
            customer_email: None,
            item_name: None,
            size: None,
            colour: None,
            quantity: None,
            price_cents: None,
            due_date: None,
            status: Some("ready_to_begin".to_string()),
            clay_body: None,
            glazes: None,
            thumbnail_media_id: None,
        }),
    )
    .await
    .unwrap();

    // 3. Soft delete commission -> COMMISSION_SOFT_DELETE
    let _ = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(comm.id.clone()),
    )
    .await
    .unwrap();

    // Fetch all audit logs for user A
    let Json(all_logs) = list_audit_logs(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Query(AuditLogQuery {
            limit: Some(50),
            action: None,
            entity_type: Some("commission".to_string()),
            entity_id: Some(comm.id.clone()),
            since: None,
        }),
    )
    .await
    .unwrap();

    assert!(all_logs.len() >= 3, "Expected at least 3 audit logs for commission");
    let actions: Vec<&str> = all_logs.iter().map(|l| l.action.as_str()).collect();
    assert!(actions.contains(&"COMMISSION_CREATE"));
    assert!(actions.contains(&"COMMISSION_UPDATE"));
    assert!(actions.contains(&"COMMISSION_SOFT_DELETE"));
}

#[tokio::test]
async fn test_audit_update_and_media_soft_deletes() {
    let ctx = setup_test_context().await;

    // Create commission
    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Bowl for Update Audit".to_string(),
            description: None,
            customer_name: "Customer".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Bowl".to_string(),
            size: None,
            colour: None,
            quantity: None,
            price_cents: None,
            due_date: None,
            status: None,
            clay_body: None,
            glazes: None,
            thumbnail_media_id: None,
        }),
    )
    .await
    .unwrap();

    // Add an update -> UPDATE_CREATE
    let Json(upd) = add_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(comm.id.clone()),
        Json(CreateUpdateRequest {
            id: None,
            from_status: Some("requiring_details".to_string()),
            to_status: Some("ready_to_begin".to_string()),
            note: Some("Glazing started".to_string()),
            media_ids: None,
        }),
    )
    .await
    .unwrap();

    // Soft delete update -> UPDATE_SOFT_DELETE
    let _ = delete_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path((comm.id.clone(), upd.id.clone())),
    )
    .await
    .unwrap();

    // Insert dummy media row and soft delete it -> MEDIA_SOFT_DELETE
    let media_id = uuid::Uuid::new_v4().to_string();
    sqlx::query(
        r#"
        INSERT INTO media_attachments (
            id, user_id, commission_id, update_id, media_type, s3_key,
            mime_type, width, height, file_size_bytes, created_at, deleted_at
        ) VALUES ($1, $2, $3, NULL, 'reference', 'ref.webp', 'image/webp', NULL, NULL, 500, 1000, NULL)
        "#,
    )
    .bind(&media_id)
    .bind(&ctx.user_a.user_id)
    .bind(&comm.id)
    .execute(&ctx.state.db)
    .await
    .unwrap();

    let _ = delete_media(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(media_id.clone()),
    )
    .await
    .unwrap();

    // Check audit logs for UPDATE_CREATE, UPDATE_SOFT_DELETE, MEDIA_SOFT_DELETE
    let Json(logs) = list_audit_logs(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Query(AuditLogQuery {
            limit: Some(50),
            action: None,
            entity_type: None,
            entity_id: None,
            since: None,
        }),
    )
    .await
    .unwrap();

    let actions: Vec<&str> = logs.iter().map(|l| l.action.as_str()).collect();
    assert!(actions.contains(&"UPDATE_CREATE"));
    assert!(actions.contains(&"UPDATE_SOFT_DELETE"));
    assert!(actions.contains(&"MEDIA_SOFT_DELETE"));
}

#[tokio::test]
async fn test_audit_sync_push_and_pull() {
    let ctx = setup_test_context().await;

    // Trigger sync push
    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![],
        }),
    )
    .await
    .unwrap();

    // Trigger sync pull
    let _ = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();

    let Json(logs) = list_audit_logs(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Query(AuditLogQuery {
            limit: Some(10),
            action: None,
            entity_type: Some("sync".to_string()),
            entity_id: None,
            since: None,
        }),
    )
    .await
    .unwrap();

    let actions: Vec<&str> = logs.iter().map(|l| l.action.as_str()).collect();
    assert!(actions.contains(&"SYNC_PUSH"));
    assert!(actions.contains(&"SYNC_PULL"));
}

#[tokio::test]
async fn test_audit_multi_tenant_isolation() {
    let ctx = setup_test_context().await;

    // User A creates a commission and deletes it
    let Json(comm_a) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Confidential Potter A Project".to_string(),
            description: None,
            customer_name: "VIP".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Sculpture".to_string(),
            size: None,
            colour: None,
            quantity: None,
            price_cents: None,
            due_date: None,
            status: None,
            clay_body: None,
            glazes: None,
            thumbnail_media_id: None,
        }),
    )
    .await
    .unwrap();

    let _ = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(comm_a.id.clone()),
    )
    .await
    .unwrap();

    // User B queries audit logs -> Must receive 0 of User A's logs!
    let Json(user_b_logs) = list_audit_logs(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        Query(AuditLogQuery {
            limit: Some(100),
            action: None,
            entity_type: None,
            entity_id: None,
            since: None,
        }),
    )
    .await
    .unwrap();

    assert!(
        user_b_logs.iter().all(|l| l.user_id.as_deref() == Some(&ctx.user_b.user_id)),
        "User B must NEVER see any audit log belonging to User A"
    );
    assert!(
        !user_b_logs.iter().any(|l| l.entity_id.as_deref() == Some(&comm_a.id)),
        "User A's commission ID must not be in User B's audit logs"
    );
}
