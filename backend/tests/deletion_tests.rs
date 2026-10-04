mod common;

use axum::extract::{Path, State};
use axum::Json;

use common::setup_test_context;
use comtrak_backend::error::AppError;
use comtrak_backend::models::{Commission, CreateCommissionRequest};
use comtrak_backend::routes::commissions::{
    create_commission, delete_commission, get_commission, list_commissions,
};

#[tokio::test]
async fn test_soft_delete_lifecycle() {
    let ctx = setup_test_context().await;

    // 1. Create commission
    let create_payload = CreateCommissionRequest {
        id: None,
        title: "Experimental Salt Glazed Jug".to_string(),
        description: Some("Wood fired salt glaze test".to_string()),
        customer_name: "Tom Hardy".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Salt Jug".to_string(),
        size: Some("20cm".to_string()),
        colour: Some("Orange Peel Gray".to_string()),
        quantity: Some(1),
        price_cents: Some(9500),
        due_date: None,
        status: Some("ready_to_begin".to_string()),
        clay_body: Some("Stoneware with grog".to_string()),
        glazes: Some("Salt vapor".to_string()),
        thumbnail_media_id: None,
    };

    let Json(commission) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Json(create_payload),
    )
    .await
    .unwrap();

    let comm_id = commission.id.clone();

    // 2. Soft-delete commission
    let Json(delete_resp) = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(comm_id.clone()),
    )
    .await
    .expect("Soft-delete must succeed");

    assert_eq!(delete_resp["success"], true);
    let deleted_at = delete_resp["deleted_at"].as_i64().expect("deleted_at timestamp");
    assert!(deleted_at > 0);

    // 3. Invariant: list_commissions must omit the deleted commission
    let Json(list) = list_commissions(State(ctx.state.clone()), ctx.user_a.clone())
        .await
        .unwrap();
    assert!(list.iter().all(|c| c.id != comm_id), "Deleted item must not appear in active listings");

    // 4. Invariant: get_commission returns NotFound (404)
    let get_result = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(comm_id.clone()),
    )
    .await;
    assert!(matches!(get_result, Err(AppError::NotFound(_))));

    // 5. Invariant: Direct SQLite query reveals row is retained with deleted_at set (tombstone)
    let raw_record = sqlx::query_as::<_, Commission>(
        "SELECT * FROM commissions WHERE id = $1",
    )
    .bind(&comm_id)
    .fetch_one(&ctx.state.db)
    .await
    .expect("Record must still exist in database");

    assert_eq!(raw_record.deleted_at, Some(deleted_at));
    assert_eq!(raw_record.version, 2, "Deleting must bump version for sync delta tracking");
}

#[tokio::test]
async fn test_double_delete_returns_not_found() {
    let ctx = setup_test_context().await;

    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Test Double Delete".to_string(),
            description: None,
            customer_name: "Customer".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Item".to_string(),
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

    // First delete succeeds
    let _ = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(comm.id.clone()),
    )
    .await
    .unwrap();

    // Second delete fails with NotFound
    let second_delete = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(comm.id.clone()),
    )
    .await;

    assert!(matches!(second_delete, Err(AppError::NotFound(_))));
}

#[tokio::test]
async fn test_delete_isolation() {
    let ctx = setup_test_context().await;

    let Json(comm_a) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Potter A Cherished Vase".to_string(),
            description: None,
            customer_name: "Collector".to_string(),
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

    // User B attempts to delete User A's commission -> NotFound (404)
    let del_res = delete_commission(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        Path(comm_a.id.clone()),
    )
    .await;

    assert!(matches!(del_res, Err(AppError::NotFound(_))));

    // Invariant: User A's commission is still active and alive
    let Json(alive) = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(comm_a.id.clone()),
    )
    .await
    .unwrap();

    assert!(alive.deleted_at.is_none());
}
