mod common;

use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::Json;

use common::setup_test_context;
use comtrak_backend::models::{
    CommissionSyncItem, CommissionUpdateSyncItem, CreateCommissionRequest, MediaAttachment,
    SyncPushRequest,
};
use comtrak_backend::routes::commissions::{create_commission, delete_commission, get_commission};
use comtrak_backend::routes::media::list_commission_media;
use comtrak_backend::routes::sync::{sync_pull, sync_push, SyncPullQuery};
use comtrak_backend::routes::updates::list_updates;

#[tokio::test]
async fn test_sync_push_creates_offline_commissions() {
    let ctx = setup_test_context().await;
    let offline_id = "off00000-aaaa-bbbb-cccc-111122223333".to_string();

    let sync_item = CommissionSyncItem {
        id: offline_id.clone(),
        title: "Offline Studio Mug".to_string(),
        description: Some("Thrown while off-grid at the pottery retreat".to_string()),
        customer_name: "Clara Wood".to_string(),
        customer_phone: Some("+447700900999".to_string()),
        customer_email: Some("clara@ceramics.org".to_string()),
        item_name: "Mug".to_string(),
        size: Some("10oz".to_string()),
        colour: Some("Cobalt Blue drip".to_string()),
        quantity: 2,
        price_cents: Some(5000),
        due_date: Some(1850000000000),
        status: "ready_to_begin".to_string(),
        clay_body: Some("White Earthenware".to_string()),
        glazes: Some("Cobalt Oxide".to_string()),
        thumbnail_media_id: None,
        created_at: 1700000000000,
        updated_at: 1700000000000,
        deleted_at: None,
        version: 1,
    };

    let push_req = SyncPushRequest {
        commissions: vec![sync_item],
        updates: vec![],
        media: vec![],
    };

    let Json(push_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(push_req),
    )
    .await
    .expect("Sync push must succeed");

    // Invariant: Server acknowledged acceptance of the client UUID
    assert_eq!(push_resp.accepted_commission_ids, vec![offline_id.clone()]);

    // Invariant: Commission is in database and queryable
    let Json(stored) = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(offline_id.clone()),
    )
    .await
    .unwrap();

    assert_eq!(stored.id, offline_id);
    assert_eq!(stored.title, "Offline Studio Mug");
    assert_eq!(stored.quantity, 2);
    assert_eq!(stored.status, "ready_to_begin");
    assert_eq!(stored.clay_body, Some("White Earthenware".to_string()));
}

#[tokio::test]
async fn test_sync_push_last_write_wins_conflict_resolution() {
    let ctx = setup_test_context().await;
    let comm_id = "c0000000-9999-8888-7777-666655554444".to_string();

    // 1. Initial push at t = 1000 with price 5000
    let item_v1 = CommissionSyncItem {
        id: comm_id.clone(),
        title: "LWW Test Vase".to_string(),
        description: None,
        customer_name: "Tester".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Vase".to_string(),
        size: None,
        colour: None,
        quantity: 1,
        price_cents: Some(5000),
        due_date: None,
        status: "requiring_details".to_string(),
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
        created_at: 1000,
        updated_at: 1000,
        deleted_at: None,
        version: 1,
    };

    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![item_v1],
            updates: vec![],
            media: vec![],
        }),
    )
    .await
    .unwrap();

    // 2. Newer push at t = 2000 with price 7500 -> Must be applied
    let item_v2_newer = CommissionSyncItem {
        id: comm_id.clone(),
        title: "LWW Test Vase (Updated)".to_string(),
        description: None,
        customer_name: "Tester".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Vase".to_string(),
        size: None,
        colour: None,
        quantity: 1,
        price_cents: Some(7500),
        due_date: None,
        status: "ready_to_begin".to_string(),
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
        created_at: 1000,
        updated_at: 2000, // Newer timestamp
        deleted_at: None,
        version: 2,
    };

    let Json(resp_newer) = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![item_v2_newer],
            updates: vec![],
            media: vec![],
        }),
    )
    .await
    .unwrap();

    assert_eq!(resp_newer.accepted_commission_ids, vec![comm_id.clone()]);

    let Json(stored_after_newer) = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm_id.clone()),
    )
    .await
    .unwrap();

    assert_eq!(stored_after_newer.price_cents, Some(7500));
    assert_eq!(stored_after_newer.status, "ready_to_begin");

    // 3. Stale push at t = 1500 with price 3000 -> Must NOT overwrite server state (t=2000 > t=1500)
    let item_v_stale = CommissionSyncItem {
        id: comm_id.clone(),
        title: "LWW Stale Edit (Ignored)".to_string(),
        description: None,
        customer_name: "Tester".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Vase".to_string(),
        size: None,
        colour: None,
        quantity: 1,
        price_cents: Some(3000), // Stale price
        due_date: None,
        status: "requiring_details".to_string(),
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
        created_at: 1000,
        updated_at: 1500, // Older than existing 2000!
        deleted_at: None,
        version: 1,
    };

    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![item_v_stale],
            updates: vec![],
            media: vec![],
        }),
    )
    .await
    .unwrap();

    // Invariant: Server retained the newer t=2000 state, not the stale t=1500 state
    let Json(stored_after_stale) = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm_id.clone()),
    )
    .await
    .unwrap();

    assert_eq!(
        stored_after_stale.price_cents,
        Some(7500),
        "Price must remain 7500 from newer write"
    );
    assert_eq!(
        stored_after_stale.status, "ready_to_begin",
        "Status must remain ready_to_begin"
    );
}

#[tokio::test]
async fn test_sync_pull_delta_filtering_and_tombstones() {
    let ctx = setup_test_context().await;

    // 1. Create Comm 1 at t=1000
    let comm1_id = "d1111111-0000-0000-0000-000000000001".to_string();
    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![CommissionSyncItem {
                id: comm1_id.clone(),
                title: "Old Mug".to_string(),
                description: None,
                customer_name: "Client 1".to_string(),
                customer_phone: None,
                customer_email: None,
                item_name: "Mug".to_string(),
                size: None,
                colour: None,
                quantity: 1,
                price_cents: None,
                due_date: None,
                status: "done".to_string(),
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

    // 2. Create Comm 2 at t=3000
    let comm2_id = "d2222222-0000-0000-0000-000000000002".to_string();
    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![CommissionSyncItem {
                id: comm2_id.clone(),
                title: "New Bowl".to_string(),
                description: None,
                customer_name: "Client 2".to_string(),
                customer_phone: None,
                customer_email: None,
                item_name: "Bowl".to_string(),
                size: None,
                colour: None,
                quantity: 1,
                price_cents: None,
                due_date: None,
                status: "ready_to_begin".to_string(),
                clay_body: None,
                glazes: None,
                thumbnail_media_id: None,
                created_at: 3000,
                updated_at: 3000,
                deleted_at: None,
                version: 1,
            }],
            updates: vec![],
            media: vec![],
        }),
    )
    .await
    .unwrap();

    // 3. Delete Comm 1 (creating a tombstone at current time > 3000)
    tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
    let _ = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        axum::extract::Path(comm1_id.clone()),
    )
    .await
    .unwrap();

    // Query sync_pull with since = 2000 (after comm1 creation, before comm2 & comm1 deletion)
    let Json(pull_resp) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(2000) }),
    )
    .await
    .unwrap();

    // Invariant: Both comm2 (created t=3000 > 2000) AND comm1 (deleted_at > 2000) are returned
    let found_comm1 = pull_resp.commissions.iter().find(|c| c.id == comm1_id);
    let found_comm2 = pull_resp.commissions.iter().find(|c| c.id == comm2_id);

    assert!(found_comm1.is_some(), "Comm 1 must be included as a tombstone");
    assert!(
        found_comm1.unwrap().deleted_at.is_some(),
        "Comm 1 must have deleted_at populated so mobile client deletes local record"
    );

    assert!(found_comm2.is_some(), "Comm 2 must be included as new item");
    assert!(found_comm2.unwrap().deleted_at.is_none());
}

#[tokio::test]
async fn test_sync_multi_tenant_isolation() {
    let ctx = setup_test_context().await;

    // User A creates commission via normal endpoint
    let Json(comm_a) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Potter A Secret Bowl".to_string(),
            description: None,
            customer_name: "Alice".to_string(),
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

    // 1. User B performs sync_pull with since = 0 -> Must NOT receive User A's commission
    let Json(user_b_pull) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();

    assert!(
        user_b_pull.commissions.is_empty(),
        "User B sync pull must not contain User A commissions"
    );

    // 2. User B tries to push a change to User A's commission ID
    let malicious_push = SyncPushRequest {
        commissions: vec![CommissionSyncItem {
            id: comm_a.id.clone(),
            title: "Hijacked by User B".to_string(),
            description: None,
            customer_name: "Bob".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Bowl".to_string(),
            size: None,
            colour: None,
            quantity: 1,
            price_cents: None,
            due_date: None,
            status: "done".to_string(),
            clay_body: None,
            glazes: None,
            thumbnail_media_id: None,
            created_at: 1000,
            updated_at: 9999999999999,
            deleted_at: None,
            version: 99,
        }],
        updates: vec![],
        media: vec![],
    };

    // User B pushes
    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        HeaderMap::new(),
        Json(malicious_push),
    )
    .await;

    // Verify User A's commission in SQLite is completely untouched
    let Json(comm_a_after) = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm_a.id.clone()),
    )
    .await
    .unwrap();

    assert_eq!(comm_a_after.title, "Potter A Secret Bowl");
    assert_eq!(comm_a_after.customer_name, "Alice");
    assert_eq!(comm_a_after.user_id, ctx.user_a.user_id);
}

#[tokio::test]
async fn test_sync_push_media_metadata_flow() {
    let ctx = setup_test_context().await;

    // 0. Backward compat: old clients omit `media` entirely -> defaults to empty.
    let legacy: SyncPushRequest =
        serde_json::from_value(serde_json::json!({ "commissions": [], "updates": [] }))
            .expect("Old sync payloads without media must still deserialize");
    assert!(legacy.media.is_empty());

    // 1. Parent commission owned by user A.
    let comm_id = "ea111111-0000-4000-8000-000000000001".to_string();
    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![CommissionSyncItem {
                id: comm_id.clone(),
                title: "Media Sync Vase".to_string(),
                description: None,
                customer_name: "Client M".to_string(),
                customer_phone: None,
                customer_email: None,
                item_name: "Vase".to_string(),
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

    // 2. Push a metadata-only media row (file bytes still to come via /media/upload).
    let media_id = "eb222222-0000-4000-8000-000000000002".to_string();
    let placeholder_key = format!("commissions/{}/placeholder.webp", comm_id);
    let meta_only = MediaAttachment {
        id: media_id.clone(),
        user_id: ctx.user_a.user_id.clone(),
        commission_id: comm_id.clone(),
        update_id: None,
        media_type: "progress".to_string(),
        s3_key: placeholder_key.clone(),
        mime_type: "image/webp".to_string(),
        width: None,
        height: None,
        file_size_bytes: None,
        created_at: 4000,
        deleted_at: None,
    };

    let Json(push_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![],
            media: vec![meta_only.clone()],
        }),
    )
    .await
    .unwrap();

    assert_eq!(push_resp.accepted_media_ids, vec![media_id.clone()]);

    // Invariant: metadata-only row is visible via pull and the media endpoint.
    let Json(pull_resp) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();
    let pulled = pull_resp
        .media
        .iter()
        .find(|m| m.id == media_id)
        .expect("Pushed media must appear in pull");
    assert!(pulled.deleted_at.is_none());

    let Json(listed) = list_commission_media(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm_id.clone()),
    )
    .await
    .unwrap();
    assert!(listed.iter().any(|m| m.id == media_id));

    // 3. Simulate the real upload landing, then re-push stale metadata.
    // The push path must never clobber the uploaded row's s3 fields.
    let uploaded_key = format!("commissions/{}/{}.webp", comm_id, media_id);
    sqlx::query(
        "UPDATE media_attachments SET s3_key = $1, mime_type = 'image/jpeg', file_size_bytes = 2048 WHERE id = $2",
    )
    .bind(&uploaded_key)
    .bind(&media_id)
    .execute(&ctx.state.db)
    .await
    .unwrap();

    let stale_meta = MediaAttachment {
        s3_key: placeholder_key.clone(),
        mime_type: "image/webp".to_string(),
        file_size_bytes: None,
        media_type: "finished".to_string(), // descriptive change SHOULD apply
        deleted_at: None,
        ..meta_only.clone()
    };

    // Idempotency: same payload twice still yields one row and an ack each time.
    for _ in 0..2 {
        let Json(repush_resp) = sync_push(
            State(ctx.state.clone()),
            ctx.user_a.clone(),
            HeaderMap::new(),
            Json(SyncPushRequest {
                commissions: vec![],
                updates: vec![],
                media: vec![stale_meta.clone()],
            }),
        )
        .await
        .unwrap();
        assert_eq!(repush_resp.accepted_media_ids, vec![media_id.clone()]);
    }

    let stored = sqlx::query_as::<_, MediaAttachment>(
        "SELECT * FROM media_attachments WHERE id = $1",
    )
    .bind(&media_id)
    .fetch_one(&ctx.state.db)
    .await
    .unwrap();
    assert_eq!(stored.s3_key, uploaded_key, "Uploaded s3_key must survive a metadata re-push");
    assert_eq!(stored.mime_type, "image/jpeg", "Uploaded mime_type must survive a metadata re-push");
    assert_eq!(
        stored.file_size_bytes,
        Some(2048),
        "Uploaded file size must survive a metadata re-push"
    );
    assert_eq!(stored.media_type, "finished", "Descriptive fields must still update");

    // 4. Two-user isolation: user B cannot attach media to user A's commission.
    let foreign_media_id = "eb333333-0000-4000-8000-000000000003".to_string();
    let Json(b_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![],
            media: vec![MediaAttachment {
                id: foreign_media_id.clone(),
                user_id: ctx.user_b.user_id.clone(),
                commission_id: comm_id.clone(), // owned by user A!
                update_id: None,
                media_type: "reference".to_string(),
                s3_key: "commissions/evil.webp".to_string(),
                mime_type: "image/webp".to_string(),
                width: None,
                height: None,
                file_size_bytes: None,
                created_at: 5000,
                deleted_at: None,
            }],
        }),
    )
    .await
    .unwrap();
    assert!(
        b_resp.accepted_media_ids.is_empty(),
        "Media for a foreign commission must be skipped"
    );
    let foreign_count: i64 =
        sqlx::query_scalar("SELECT COUNT(1) FROM media_attachments WHERE id = $1")
            .bind(&foreign_media_id)
            .fetch_one(&ctx.state.db)
            .await
            .unwrap();
    assert_eq!(foreign_count, 0);

    // 4b. User B cannot hijack user A's media id even via their own commission.
    let Json(comm_b) = create_commission(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Potter B Bowl".to_string(),
            description: None,
            customer_name: "Bob".to_string(),
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

    let Json(hijack_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![],
            media: vec![MediaAttachment {
                id: media_id.clone(), // owned by user A
                user_id: ctx.user_b.user_id.clone(),
                commission_id: comm_b.id.clone(), // owned by user B
                update_id: None,
                media_type: "reference".to_string(),
                s3_key: "commissions/hijack.webp".to_string(),
                mime_type: "image/png".to_string(),
                width: None,
                height: None,
                file_size_bytes: Some(1),
                created_at: 6000,
                deleted_at: None,
            }],
        }),
    )
    .await
    .unwrap();
    assert!(
        hijack_resp.accepted_media_ids.is_empty(),
        "Hijack of another user's media id must be skipped"
    );
    let stored_after_hijack = sqlx::query_as::<_, MediaAttachment>(
        "SELECT * FROM media_attachments WHERE id = $1",
    )
    .bind(&media_id)
    .fetch_one(&ctx.state.db)
    .await
    .unwrap();
    assert_eq!(stored_after_hijack.commission_id, comm_id);
    assert_eq!(stored_after_hijack.s3_key, uploaded_key);

    // 5. Tombstone support: pushing deleted_at soft-deletes so deletes sync.
    let tombstone_at = chrono::Utc::now().timestamp_millis();
    let Json(tomb_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![],
            media: vec![MediaAttachment {
                deleted_at: Some(tombstone_at),
                ..stale_meta.clone()
            }],
        }),
    )
    .await
    .unwrap();
    assert_eq!(tomb_resp.accepted_media_ids, vec![media_id.clone()]);

    let tomb_stored = sqlx::query_as::<_, MediaAttachment>(
        "SELECT * FROM media_attachments WHERE id = $1",
    )
    .bind(&media_id)
    .fetch_one(&ctx.state.db)
    .await
    .unwrap();
    assert_eq!(tomb_stored.deleted_at, Some(tombstone_at));

    // Soft-deleted media disappears from the active listing but arrives as a
    // tombstone via pull so clients delete their local copy.
    let Json(listed_after) = list_commission_media(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm_id.clone()),
    )
    .await
    .unwrap();
    assert!(listed_after.iter().all(|m| m.id != media_id));

    let Json(pull_after) = sync_pull(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Query(SyncPullQuery { since: Some(0) }),
    )
    .await
    .unwrap();
    let tomb_pulled = pull_after
        .media
        .iter()
        .find(|m| m.id == media_id)
        .expect("Deleted media must arrive as a tombstone via pull");
    assert_eq!(tomb_pulled.deleted_at, Some(tombstone_at));
}

/// Reverse-order race gap (the flow test above covers push-first): when the
/// real upload lands FIRST (row created by the upload endpoint's upsert) and
/// a stale metadata-only push arrives after, the uploaded s3_key / mime_type /
/// file_size_bytes must survive. Uses the upload handler's exact DB statement
/// (post-S3) so the pinned pair is sync_push-upsert x upload-upsert.
#[tokio::test]
async fn test_sync_push_preserves_upload_first_row() {
    let ctx = setup_test_context().await;

    // Parent commission owned by user A.
    let comm_id = "ec444444-0000-4000-8000-000000000004".to_string();
    let _ = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![CommissionSyncItem {
                id: comm_id.clone(),
                title: "Race Teapot".to_string(),
                description: None,
                customer_name: "Client R".to_string(),
                customer_phone: None,
                customer_email: None,
                item_name: "Teapot".to_string(),
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

    // 1. Upload lands first: verbatim the post-S3 DB upsert from
    // routes::media::upload_media (upload_object itself needs Garage, so the
    // S3 byte transfer is bypassed exactly like the other media tests do).
    let media_id = "ec555555-0000-4000-8000-000000000005".to_string();
    let uploaded_key = format!("commissions/{}/{}.webp", comm_id, media_id);
    let file_bytes = [0x52, 0x49, 0x46, 0x46, 0x00]; // fake RIFF header
    let file_size = file_bytes.len() as i64;
    let upload_now = chrono::Utc::now().timestamp_millis();
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
    .bind(&ctx.user_a.user_id)
    .bind(&comm_id)
    .bind(Option::<String>::None)
    .bind("progress")
    .bind(&uploaded_key)
    .bind("image/webp")
    .bind(file_size)
    .bind(upload_now)
    .execute(&ctx.state.db)
    .await
    .unwrap();

    // 2. Stale metadata-only push for the same id arrives AFTER the upload.
    let Json(push_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![],
            media: vec![MediaAttachment {
                id: media_id.clone(),
                user_id: ctx.user_a.user_id.clone(),
                commission_id: comm_id.clone(),
                update_id: None,
                media_type: "progress".to_string(),
                s3_key: "commissions/stale-placeholder.webp".to_string(),
                mime_type: "image/png".to_string(),
                width: None,
                height: None,
                file_size_bytes: None,
                created_at: 1000, // older than the upload row
                deleted_at: None,
            }],
        }),
    )
    .await
    .unwrap();
    assert_eq!(push_resp.accepted_media_ids, vec![media_id.clone()]);

    // Uploaded fields intact, exactly one physical row (idempotent, no dup).
    let stored = sqlx::query_as::<_, MediaAttachment>(
        "SELECT * FROM media_attachments WHERE id = $1",
    )
    .bind(&media_id)
    .fetch_one(&ctx.state.db)
    .await
    .unwrap();
    assert_eq!(stored.s3_key, uploaded_key);
    assert_eq!(stored.mime_type, "image/webp");
    assert_eq!(stored.file_size_bytes, Some(file_size));
    let row_count: i64 =
        sqlx::query_scalar("SELECT COUNT(1) FROM media_attachments WHERE id = $1")
            .bind(&media_id)
            .fetch_one(&ctx.state.db)
            .await
            .unwrap();
    assert_eq!(row_count, 1, "Metadata re-push must not duplicate the row");
}

/// Update push validation: an update whose commission belongs to another user
/// (or does not exist) is skipped, never accepted and never stored.
#[tokio::test]
async fn test_sync_push_update_parent_validation() {
    let ctx = setup_test_context().await;

    // Commission owned by user A.
    let Json(comm_a) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Potter A Jug".to_string(),
            description: None,
            customer_name: "Alice".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Jug".to_string(),
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

    // 1. User B pushes an update for user A's commission -> skipped.
    let Json(b_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![CommissionUpdateSyncItem {
                id: "ed666666-0000-4000-8000-000000000006".to_string(),
                commission_id: comm_a.id.clone(),
                from_status: None,
                to_status: Some("done".to_string()),
                note: Some("Hijacked timeline entry".to_string()),
                created_at: 2000,
                deleted_at: None,
            }],
            media: vec![],
        }),
    )
    .await
    .unwrap();
    assert!(
        b_resp.accepted_update_ids.is_empty(),
        "Update for a foreign commission must be skipped, not accepted"
    );
    let hijack_count: i64 =
        sqlx::query_scalar("SELECT COUNT(1) FROM commission_updates WHERE commission_id = $1")
            .bind(&comm_a.id)
            .fetch_one(&ctx.state.db)
            .await
            .unwrap();
    assert_eq!(hijack_count, 0, "Foreign update push must store nothing");

    // 2. Update for a commission that does not exist -> skipped.
    let Json(ghost_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![CommissionUpdateSyncItem {
                id: "ed777777-0000-4000-8000-000000000007".to_string(),
                commission_id: "ed000000-0000-4000-8000-000000000000".to_string(),
                from_status: None,
                to_status: None,
                note: Some("Orphan entry".to_string()),
                created_at: 2000,
                deleted_at: None,
            }],
            media: vec![],
        }),
    )
    .await
    .unwrap();
    assert!(
        ghost_resp.accepted_update_ids.is_empty(),
        "Update for a nonexistent commission must be skipped"
    );

    // 3. Positive control: own commission -> accepted, stored, listed.
    let own_update_id = "ed888888-0000-4000-8000-000000000008".to_string();
    let Json(ok_resp) = sync_push(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(SyncPushRequest {
            commissions: vec![],
            updates: vec![CommissionUpdateSyncItem {
                id: own_update_id.clone(),
                commission_id: comm_a.id.clone(),
                from_status: None,
                to_status: None,
                note: Some("Legit offline note".to_string()),
                created_at: 3000,
                deleted_at: None,
            }],
            media: vec![],
        }),
    )
    .await
    .unwrap();
    assert_eq!(ok_resp.accepted_update_ids, vec![own_update_id.clone()]);

    let Json(listed) = list_updates(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        axum::extract::Path(comm_a.id.clone()),
    )
    .await
    .unwrap();
    assert!(listed.iter().any(|u| u.id == own_update_id));
}
