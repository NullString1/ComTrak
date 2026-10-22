mod common;

use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{HeaderMap, Request, StatusCode};
use axum::Json;
use tower::ServiceExt;

use common::setup_test_context;
use comtrak_backend::models::{
    CommissionSyncItem, CommissionUpdate, CreateCommissionRequest, CreateUpdateRequest,
    MediaAttachment, SyncPushRequest,
};
use comtrak_backend::routes::commissions::{create_commission, delete_commission};
use comtrak_backend::routes::create_router;
use comtrak_backend::routes::media::{delete_media, list_commission_media};
use comtrak_backend::routes::sync::{sync_pull, sync_push, SyncPullQuery};
use comtrak_backend::routes::updates::{add_update, delete_update, list_updates};

/// Build a real multipart request for POST /api/v1/media/upload, exercising
/// the actual handler (multipart parsing + ownership guard) through the router.
fn upload_request(token: &str, commission_id: &str, file_bytes: &[u8]) -> Request<Body> {
    let boundary = "comtrak-test-boundary";
    let mut body = Vec::new();

    let mut part = |name: &str,
                    filename: Option<&str>,
                    content_type: Option<&str>,
                    data: &[u8]| {
        body.extend_from_slice(format!("--{}\r\n", boundary).as_bytes());
        match filename {
            Some(f) => body.extend_from_slice(
                format!(
                    "Content-Disposition: form-data; name=\"{}\"; filename=\"{}\"\r\n",
                    name, f
                )
                .as_bytes(),
            ),
            None => body.extend_from_slice(
                format!("Content-Disposition: form-data; name=\"{}\"\r\n", name).as_bytes(),
            ),
        }
        if let Some(ct) = content_type {
            body.extend_from_slice(format!("Content-Type: {}\r\n", ct).as_bytes());
        }
        body.extend_from_slice(b"\r\n");
        body.extend_from_slice(data);
        body.extend_from_slice(b"\r\n");
    };

    part("commission_id", None, None, commission_id.as_bytes());
    part(
        "file",
        Some("photo.webp"),
        Some("image/webp"),
        file_bytes,
    );
    body.extend_from_slice(format!("--{}--\r\n", boundary).as_bytes());

    Request::post("/api/v1/media/upload")
        .header("authorization", format!("Bearer {}", token))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={}", boundary),
        )
        .body(Body::from(body))
        .expect("Failed building test upload request")
}

/// Regression pin for the data-loss incident: the upload endpoint 404s for
/// unknown commissions, so clients MUST push commissions before uploading.
/// Hermetic: the 404 guard runs before any S3 traffic.
#[tokio::test]
async fn test_upload_rejected_until_commission_pushed() {
    let ctx = setup_test_context().await;
    let ghost_id = "f0000000-1111-4000-8000-000000000099".to_string();
    let fake_bytes = b"RIFF\x00\x00\x00\x00WEBP-fake-bytes";

    // 1. Upload for a commission id the server has never seen -> 404.
    let app = create_router(ctx.state.clone());
    let resp = app
        .oneshot(upload_request(&ctx.token_a, &ghost_id, fake_bytes))
        .await
        .expect("Router must respond");
    assert_eq!(
        resp.status(),
        StatusCode::NOT_FOUND,
        "Upload for an unknown commission must 404 (this is why clients push first)"
    );

    // 2. Upload for another user's commission -> 404 (no cross-tenant leak).
    let Json(comm_a) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Potter A Private Mug".to_string(),
            description: None,
            customer_name: "Alice".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Mug".to_string(),
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

    let app = create_router(ctx.state.clone());
    let resp = app
        .oneshot(upload_request(&ctx.token_b, &comm_a.id, fake_bytes))
        .await
        .expect("Router must respond");
    assert_eq!(
        resp.status(),
        StatusCode::NOT_FOUND,
        "Upload for a foreign commission must 404, not leak existence"
    );

    // 3. Push the ghost commission via sync, then the SAME upload gets past
    // the ownership guard. Hermetic envs have no Garage, so success there is a
    // 500 Storage error; with Garage up it is a 200. Either way it must NOT
    // be a 404 anymore: the commission push is what unblocks the upload.
    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![CommissionSyncItem {
                id: ghost_id.clone(),
                title: "Offline Mug".to_string(),
                description: None,
                customer_name: "Ghost".to_string(),
                customer_phone: None,
                customer_email: None,
                item_name: "Mug".to_string(),
                size: None,
                colour: None,
                quantity: 1,
                price_cents: None,
                due_date: None,
                status: "ready_to_begin".to_string(),
                clay_body: None,
                glazes: None,
                thumbnail_media_id: None,
                created_at: 1000,
                updated_at: 1000,
                deleted_at: None,
                version: 1,
            }],
            updates: vec![],
            media: vec![],
        }),
    )
    .await
    .unwrap();

    let app = create_router(ctx.state.clone());
    let resp = app
        .oneshot(upload_request(&ctx.token_a, &ghost_id, fake_bytes))
        .await
        .expect("Router must respond");
    assert_ne!(
        resp.status(),
        StatusCode::NOT_FOUND,
        "After the commission is pushed, the upload guard must pass (200 with S3, 500 Storage without)"
    );
}

/// Tombstone visibility contract for media: DELETE removes the row from the
/// active listing but pull must still surface the tombstone (deleted_at set)
/// so offline clients converge. Insert bypasses S3 exactly like the existing
/// deletion tests (the 404-guard above already pins the upload path itself).
#[tokio::test]
async fn test_media_delete_listed_vs_pull_tombstone() {
    let ctx = setup_test_context().await;

    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Tombstone Vase".to_string(),
            description: None,
            customer_name: "Client T".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Vase".to_string(),
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

    let media_id = "f1000000-0000-4000-8000-000000000001".to_string();
    let now = chrono::Utc::now().timestamp_millis();
    sqlx::query(
        r#"
        INSERT INTO media_attachments (
            id, user_id, commission_id, update_id, media_type, s3_key,
            mime_type, width, height, file_size_bytes, created_at, deleted_at
        ) VALUES ($1, $2, $3, NULL, 'progress', 'commissions/tomb.webp', 'image/webp', NULL, NULL, 512, $4, NULL)
        "#,
    )
    .bind(&media_id)
    .bind(&ctx.user_a.user_id)
    .bind(&comm.id)
    .bind(now)
    .execute(&ctx.state.db)
    .await
    .unwrap();

    // Present while alive: both pull and the listing endpoint.
    let Json(pull_alive) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();
    assert!(pull_alive.media.iter().any(|m| m.id == media_id));

    let Json(listed_alive) = list_commission_media(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm.id.clone()),
    )
    .await
    .unwrap();
    assert!(listed_alive.iter().any(|m| m.id == media_id));

    // DELETE via the API.
    let _ = delete_media(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        axum::extract::Path(media_id.clone()),
    )
    .await
    .unwrap();

    // Gone from the active listing ...
    let Json(listed_dead) = list_commission_media(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm.id.clone()),
    )
    .await
    .unwrap();
    assert!(
        listed_dead.iter().all(|m| m.id != media_id),
        "Deleted media must disappear from the active listing"
    );

    // ... but present in pull WITH deleted_at so clients delete locally.
    let Json(pull_dead) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();
    let tomb = pull_dead
        .media
        .iter()
        .find(|m| m.id == media_id)
        .expect("Deleted media must arrive as a tombstone via pull");
    assert!(
        tomb.deleted_at.is_some(),
        "Pull tombstone must carry deleted_at"
    );

    // Other tenants see nothing.
    let Json(pull_b) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();
    assert!(pull_b.media.iter().all(|m| m.id != media_id));
}

/// Same tombstone contract for commission_updates via the updates endpoints.
#[tokio::test]
async fn test_update_delete_listed_vs_pull_tombstone() {
    let ctx = setup_test_context().await;

    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Timeline Bowl".to_string(),
            description: None,
            customer_name: "Client U".to_string(),
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

    let Json(upd) = add_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        axum::extract::Path(comm.id.clone()),
        Json(CreateUpdateRequest {
            id: None,
            from_status: None,
            to_status: Some("ready_to_begin".into()),
            note: Some("Trimming foot ring".into()),
            media_ids: None,
        }),
    )
    .await
    .unwrap();

    // Alive: visible in pull and in the per-commission listing.
    let Json(pull_alive) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();
    assert!(pull_alive.updates.iter().any(|u| u.id == upd.id));

    let Json(listed_alive) = list_updates(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm.id.clone()),
    )
    .await
    .unwrap();
    assert!(listed_alive.iter().any(|u| u.id == upd.id));

    // DELETE via the API.
    let _ = delete_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        axum::extract::Path((comm.id.clone(), upd.id.clone())),
    )
    .await
    .unwrap();

    let Json(listed_dead) = list_updates(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm.id.clone()),
    )
    .await
    .unwrap();
    assert!(
        listed_dead.iter().all(|u| u.id != upd.id),
        "Deleted update must disappear from the active listing"
    );

    let Json(pull_dead) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();
    let tomb = pull_dead
        .updates
        .iter()
        .find(|u| u.id == upd.id)
        .expect("Deleted update must arrive as a tombstone via pull");
    assert!(
        tomb.deleted_at.is_some(),
        "Pull tombstone must carry deleted_at"
    );

    // Row is retained server-side (soft delete, never physical).
    let raw = sqlx::query_as::<_, CommissionUpdate>(
        "SELECT * FROM commission_updates WHERE id = $1",
    )
    .bind(&upd.id)
    .fetch_one(&ctx.state.db)
    .await
    .unwrap();
    assert!(raw.deleted_at.is_some());
}

/// Commission delete cascades to updates + media server-side, and pull must
/// surface ALL THREE tombstones so clients converge in one delta.
#[tokio::test]
async fn test_commission_delete_cascade_tombstones_in_pull() {
    let ctx = setup_test_context().await;

    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Cascade Planter".to_string(),
            description: None,
            customer_name: "Client C".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Planter".to_string(),
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

    let Json(upd) = add_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        axum::extract::Path(comm.id.clone()),
        Json(CreateUpdateRequest {
            id: None,
            from_status: None,
            to_status: None,
            note: Some("Cascade note".into()),
            media_ids: None,
        }),
    )
    .await
    .unwrap();

    let media_id = "f2000000-0000-4000-8000-000000000002".to_string();
    let now = chrono::Utc::now().timestamp_millis();
    sqlx::query(
        r#"
        INSERT INTO media_attachments (
            id, user_id, commission_id, update_id, media_type, s3_key,
            mime_type, width, height, file_size_bytes, created_at, deleted_at
        ) VALUES ($1, $2, $3, NULL, 'reference', 'commissions/cascade.webp', 'image/webp', NULL, NULL, 256, $4, NULL)
        "#,
    )
    .bind(&media_id)
    .bind(&ctx.user_a.user_id)
    .bind(&comm.id)
    .bind(now)
    .execute(&ctx.state.db)
    .await
    .unwrap();

    // Delete the parent commission (cascades to updates + media).
    let _ = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        axum::extract::Path(comm.id.clone()),
    )
    .await
    .unwrap();

    // All three vanish from active listings ...
    let Json(listed_media) = list_commission_media(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm.id.clone()),
    )
    .await
    .unwrap();
    assert!(listed_media.iter().all(|m| m.id != media_id));

    // ... but pull surfaces all three tombstones in one delta.
    let Json(pull) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();

    let comm_tomb = pull
        .commissions
        .iter()
        .find(|c| c.id == comm.id)
        .expect("Deleted commission must arrive as a tombstone via pull");
    assert!(comm_tomb.deleted_at.is_some());

    let upd_tomb = pull
        .updates
        .iter()
        .find(|u| u.id == upd.id)
        .expect("Cascade-deleted update must arrive as a tombstone via pull");
    assert!(upd_tomb.deleted_at.is_some());

    let media_tomb = pull
        .media
        .iter()
        .find(|m| m.id == media_id)
        .expect("Cascade-deleted media must arrive as a tombstone via pull");
    assert!(media_tomb.deleted_at.is_some());

    // Physical rows retained (soft delete everywhere).
    let media_raw = sqlx::query_as::<_, MediaAttachment>(
        "SELECT * FROM media_attachments WHERE id = $1",
    )
    .bind(&media_id)
    .fetch_one(&ctx.state.db)
    .await
    .unwrap();
    assert!(media_raw.deleted_at.is_some());
}
