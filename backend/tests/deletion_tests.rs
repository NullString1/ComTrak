mod common;

use axum::extract::{Path, State};
use axum::http::HeaderMap;
use axum::Json;

use common::setup_test_context;
use comtrak_backend::error::AppError;
use comtrak_backend::models::{
    Commission, CommissionUpdate, CreateCommissionRequest, CreateUpdateRequest, MediaAttachment,
};
use comtrak_backend::routes::commissions::{
    create_commission, delete_commission, get_commission, list_commissions,
};
use comtrak_backend::routes::media::{delete_media, get_media};
use comtrak_backend::routes::updates::{add_update, delete_update, list_updates};

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
        HeaderMap::new(),
        Json(create_payload),
    )
    .await
    .unwrap();

    let comm_id = commission.id.clone();

    // 2. Soft-delete commission
    let Json(delete_resp) = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
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
    // No physical deletion occurred!
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
async fn test_cascade_soft_delete_preserves_all_rows() {
    let ctx = setup_test_context().await;

    // 1. Create commission
    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Planter with Cascade Test".to_string(),
            description: None,
            customer_name: "Customer".to_string(),
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

    let comm_id = comm.id.clone();

    // 2. Add an update
    let Json(upd) = add_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(comm_id.clone()),
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

    let update_id = upd.id.clone();

    // 3. Insert a media attachment directly
    let media_id = uuid::Uuid::new_v4().to_string();
    let now = chrono::Utc::now().timestamp_millis();
    sqlx::query(
        r#"
        INSERT INTO media_attachments (
            id, user_id, commission_id, update_id, media_type, s3_key,
            mime_type, width, height, file_size_bytes, created_at, deleted_at
        ) VALUES ($1, $2, $3, $4, 'progress', 'test_key.webp', 'image/webp', NULL, NULL, 1024, $5, NULL)
        "#,
    )
    .bind(&media_id)
    .bind(&ctx.user_a.user_id)
    .bind(&comm_id)
    .bind(&update_id)
    .bind(now)
    .execute(&ctx.state.db)
    .await
    .unwrap();

    // 4. Soft delete parent commission
    let _ = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(comm_id.clone()),
    )
    .await
    .unwrap();

    // 5. Invariant: ZERO rows were physically deleted from database!
    let comm_count: i64 = sqlx::query_scalar("SELECT COUNT(1) FROM commissions WHERE id = $1")
        .bind(&comm_id)
        .fetch_one(&ctx.state.db)
        .await
        .unwrap();
    assert_eq!(comm_count, 1, "Commission row must still exist in DB");

    let upd_count: i64 = sqlx::query_scalar("SELECT COUNT(1) FROM commission_updates WHERE id = $1")
        .bind(&update_id)
        .fetch_one(&ctx.state.db)
        .await
        .unwrap();
    assert_eq!(upd_count, 1, "Update row must still exist in DB");

    let media_count: i64 = sqlx::query_scalar("SELECT COUNT(1) FROM media_attachments WHERE id = $1")
        .bind(&media_id)
        .fetch_one(&ctx.state.db)
        .await
        .unwrap();
    assert_eq!(media_count, 1, "Media row must still exist in DB");

    // 6. Invariant: ALL rows have deleted_at populated
    let comm_record = sqlx::query_as::<_, Commission>("SELECT * FROM commissions WHERE id = $1")
        .bind(&comm_id)
        .fetch_one(&ctx.state.db)
        .await
        .unwrap();
    assert!(comm_record.deleted_at.is_some());

    let upd_record = sqlx::query_as::<_, CommissionUpdate>("SELECT * FROM commission_updates WHERE id = $1")
        .bind(&update_id)
        .fetch_one(&ctx.state.db)
        .await
        .unwrap();
    assert!(upd_record.deleted_at.is_some());

    let media_record = sqlx::query_as::<_, MediaAttachment>("SELECT * FROM media_attachments WHERE id = $1")
        .bind(&media_id)
        .fetch_one(&ctx.state.db)
        .await
        .unwrap();
    assert!(media_record.deleted_at.is_some());
}

#[tokio::test]
async fn test_update_and_media_individual_soft_delete() {
    let ctx = setup_test_context().await;

    // Create commission
    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Teacup Individual Delete Test".to_string(),
            description: None,
            customer_name: "Customer".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "Teacup".to_string(),
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

    // Add an update
    let Json(upd) = add_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(comm.id.clone()),
        Json(CreateUpdateRequest {
            id: None,
            from_status: None,
            to_status: None,
            note: Some("Mistake note".into()),
            media_ids: None,
        }),
    )
    .await
    .unwrap();

    // Delete the update individually
    let del_upd_resp = delete_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path((comm.id.clone(), upd.id.clone())),
    )
    .await;
    assert!(del_upd_resp.is_ok());

    // Invariant: list_updates omits the deleted update
    let Json(updates) = list_updates(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(comm.id.clone()),
    )
    .await
    .unwrap();
    assert!(updates.iter().all(|u| u.id != upd.id));

    // Invariant: Row is retained in DB with deleted_at set
    let raw_upd = sqlx::query_as::<_, CommissionUpdate>("SELECT * FROM commission_updates WHERE id = $1")
        .bind(&upd.id)
        .fetch_one(&ctx.state.db)
        .await
        .unwrap();
    assert!(raw_upd.deleted_at.is_some());

    // Insert media attachment and test individual media delete
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

    let del_media_resp = delete_media(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(media_id.clone()),
    )
    .await;
    assert!(del_media_resp.is_ok());

    // Invariant: get_media returns 404
    let get_media_res = get_media(State(ctx.state.clone()), Path(media_id.clone())).await;
    assert!(matches!(get_media_res, Err(AppError::NotFound(_))));

    // Invariant: Row still exists in DB
    let raw_media = sqlx::query_as::<_, MediaAttachment>("SELECT * FROM media_attachments WHERE id = $1")
        .bind(&media_id)
        .fetch_one(&ctx.state.db)
        .await
        .unwrap();
    assert!(raw_media.deleted_at.is_some());
}

#[tokio::test]
async fn test_double_delete_returns_not_found() {
    let ctx = setup_test_context().await;

    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
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
        HeaderMap::new(),
        Path(comm.id.clone()),
    )
    .await
    .unwrap();

    // Second delete fails with NotFound
    let second_delete = delete_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
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
        HeaderMap::new(),
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
        HeaderMap::new(),
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
