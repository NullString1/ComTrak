mod common;

use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::Json;

use common::setup_test_context;
use comtrak_backend::models::{
    CommissionSyncItem, CreateCommissionRequest, SyncPushRequest,
};
use comtrak_backend::routes::commissions::{create_commission, delete_commission, get_commission};
use comtrak_backend::routes::sync::{sync_pull, sync_push, SyncPullQuery};

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
