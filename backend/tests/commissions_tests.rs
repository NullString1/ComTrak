mod common;

use axum::extract::{Path, State};
use axum::http::HeaderMap;
use axum::Json;
use chrono::Utc;
use uuid::Uuid;

use common::setup_test_context;
use comtrak_backend::error::AppError;
use comtrak_backend::models::{CreateCommissionRequest, UpdateCommissionRequest};
use comtrak_backend::routes::commissions::{
    create_commission, get_commission, list_commissions, update_commission,
};

#[tokio::test]
async fn test_create_commission_default_values() {
    let ctx = setup_test_context().await;

    let payload = CreateCommissionRequest {
        id: None,
        title: "Large Terracotta Planter".to_string(),
        description: Some("Hand-built planter with drainage holes".to_string()),
        customer_name: "Sarah Jenkins".to_string(),
        customer_phone: Some("+447700900123".to_string()),
        customer_email: Some("sarah@garden.co.uk".to_string()),
        item_name: "Terracotta Planter".to_string(),
        size: Some("40cm diameter".to_string()),
        colour: Some("Natural Terracotta".to_string()),
        quantity: None, // Should default to 1
        price_cents: Some(8500),
        due_date: Some(Utc::now().timestamp_millis() + 86400 * 14 * 1000),
        status: None, // Should default to "requiring_details"
        clay_body: Some("Red Terracotta".to_string()),
        glazes: None,
        thumbnail_media_id: None,
    };

    let Json(commission) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(payload),
    )
    .await
    .expect("Creating commission must succeed");

    // Invariant: Status defaults to requiring_details
    assert_eq!(commission.status, "requiring_details");
    // Invariant: Quantity defaults to 1
    assert_eq!(commission.quantity, 1);
    // Invariant: Version starts at 1
    assert_eq!(commission.version, 1);
    // Invariant: Belongs to authenticated user
    assert_eq!(commission.user_id, ctx.user_a.user_id);
    // Invariant: Not deleted
    assert!(commission.deleted_at.is_none());
    // Invariant: Server generated a valid UUIDv4
    assert!(Uuid::parse_str(&commission.id).is_ok());
    // Invariant: Timestamps initialized
    assert!(commission.created_at > 0);
    assert_eq!(commission.created_at, commission.updated_at);
}

#[tokio::test]
async fn test_create_commission_preserves_client_uuid() {
    let ctx = setup_test_context().await;
    let client_uuid = "e0000000-1111-2222-3333-444444444444".to_string();

    let payload = CreateCommissionRequest {
        id: Some(client_uuid.clone()),
        title: "Matcha Chawan (Tea Bowl)".to_string(),
        description: None,
        customer_name: "Kenji Sato".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Chawan".to_string(),
        size: Some("12cm x 8cm".to_string()),
        colour: Some("Shino White with iron brushwork".to_string()),
        quantity: Some(1),
        price_cents: Some(12000),
        due_date: None,
        status: Some("ready_to_begin".to_string()),
        clay_body: Some("Mino Stoneware".to_string()),
        glazes: Some("Felspathic Shino".to_string()),
        thumbnail_media_id: None,
    };

    let Json(commission) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(payload),
    )
    .await
    .expect("Creating commission with custom UUID must succeed");

    // Invariant: Client UUID is preserved exactly for offline reconciliation
    assert_eq!(commission.id, client_uuid);
    assert_eq!(commission.status, "ready_to_begin");
    assert_eq!(commission.clay_body, Some("Mino Stoneware".to_string()));
}

#[tokio::test]
async fn test_update_commission_partial_and_version_increment() {
    let ctx = setup_test_context().await;

    // 1. Create initial commission
    let create_payload = CreateCommissionRequest {
        id: None,
        title: "Ceramic Dinner Plate Set".to_string(),
        description: Some("Set of 4 speckled dinner plates".to_string()),
        customer_name: "Emma Watson".to_string(),
        customer_phone: Some("+447700900456".to_string()),
        customer_email: Some("emma@studio.test".to_string()),
        item_name: "Dinner Plates".to_string(),
        size: Some("28cm".to_string()),
        colour: Some("Oatmeal matte".to_string()),
        quantity: Some(4),
        price_cents: Some(16000),
        due_date: Some(1800000000000),
        status: Some("requiring_details".to_string()),
        clay_body: Some("Speckled Stoneware".to_string()),
        glazes: Some("Matte Oatmeal".to_string()),
        thumbnail_media_id: None,
    };

    let Json(initial) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(create_payload),
    )
    .await
    .unwrap();

    let initial_version = initial.version;
    let initial_updated_at = initial.updated_at;

    // Short sleep to ensure timestamp difference
    tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;

    // 2. Perform partial update: modify price, status, and notes, but leave clay_body untouched
    let update_payload = UpdateCommissionRequest {
        title: None,
        description: None,
        customer_name: None,
        customer_phone: None,
        customer_email: None,
        item_name: None,
        size: None,
        colour: None,
        quantity: None,
        price_cents: Some(18500), // Price updated
        due_date: None,
        status: Some("ready_to_begin".to_string()), // Status advanced
        clay_body: None,                            // Should keep "Speckled Stoneware"
        glazes: None,
        thumbnail_media_id: None,
    };

    let Json(updated) = update_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Path(initial.id.clone()),
        Json(update_payload),
    )
    .await
    .expect("Updating commission must succeed");

    // Invariant: Version must strictly increment by 1
    assert_eq!(updated.version, initial_version + 1);
    // Invariant: Updated field has new value
    assert_eq!(updated.price_cents, Some(18500));
    assert_eq!(updated.status, "ready_to_begin");
    // Invariant: Non-modified fields are preserved
    assert_eq!(updated.clay_body, Some("Speckled Stoneware".to_string()));
    assert_eq!(updated.customer_name, "Emma Watson");
    assert_eq!(updated.quantity, 4);
    // Invariant: updated_at was bumped
    assert!(updated.updated_at > initial_updated_at);
}

#[tokio::test]
async fn test_multi_tenant_isolation_commissions() {
    let ctx = setup_test_context().await;

    // User A creates a private commission
    let create_payload = CreateCommissionRequest {
        id: None,
        title: "Confidential Commission for Royal Gallery".to_string(),
        description: None,
        customer_name: "Gallery Curator".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Monumental Vessel".to_string(),
        size: Some("100cm".to_string()),
        colour: Some("Carbon Trap Shino".to_string()),
        quantity: Some(1),
        price_cents: Some(500000),
        due_date: None,
        status: None,
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
    };

    let Json(comm_a) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(create_payload),
    )
    .await
    .unwrap();

    // 1. User B tries to view User A's commission -> must return NotFound (404)
    let get_result = get_commission(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        Path(comm_a.id.clone()),
    )
    .await;
    assert!(
        matches!(get_result, Err(AppError::NotFound(_))),
        "User B must not be able to get User A's commission"
    );

    // 2. User B tries to update User A's commission -> must return NotFound (404)
    let update_result = update_commission(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        HeaderMap::new(),
        Path(comm_a.id.clone()),
        Json(UpdateCommissionRequest {
            title: Some("Hacked Title".into()),
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
            status: None,
            clay_body: None,
            glazes: None,
            thumbnail_media_id: None,
        }),
    )
    .await;
    assert!(
        matches!(update_result, Err(AppError::NotFound(_))),
        "User B must not be able to update User A's commission"
    );

    // 3. User B calls list_commissions -> User A's commission must NOT be leaked
    let Json(user_b_list) = list_commissions(State(ctx.state.clone()), ctx.user_b.clone())
        .await
        .unwrap();
    assert!(
        user_b_list.is_empty(),
        "User B's commission list must be empty"
    );

    // 4. User A calls list_commissions -> User A sees their own commission
    let Json(user_a_list) = list_commissions(State(ctx.state.clone()), ctx.user_a.clone())
        .await
        .unwrap();
    assert_eq!(user_a_list.len(), 1);
    assert_eq!(user_a_list[0].id, comm_a.id);
}

#[tokio::test]
async fn test_sla_urgency_ordering() {
    let ctx = setup_test_context().await;
    let now = Utc::now().timestamp_millis();

    // Create 3 commissions with different SLAs:
    // Comm 1: Urgent (due tomorrow)
    let payload_urgent = CreateCommissionRequest {
        id: None,
        title: "Urgent Wedding Gift Vase".to_string(),
        description: None,
        customer_name: "Customer 1".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Vase".to_string(),
        size: None,
        colour: None,
        quantity: Some(1),
        price_cents: None,
        due_date: Some(now + 86400 * 1000), // Due in 1 day
        status: None,
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
    };

    // Comm 2: Future (due in 30 days)
    let payload_future = CreateCommissionRequest {
        id: None,
        title: "Next Month Holiday Set".to_string(),
        description: None,
        customer_name: "Customer 2".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Holiday Set".to_string(),
        size: None,
        colour: None,
        quantity: Some(1),
        price_cents: None,
        due_date: Some(now + 86400 * 30 * 1000), // Due in 30 days
        status: None,
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
    };

    // Comm 3: Open-ended (no due date)
    let payload_no_due = CreateCommissionRequest {
        id: None,
        title: "Open-ended Studio Sculpture".to_string(),
        description: None,
        customer_name: "Customer 3".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Sculpture".to_string(),
        size: None,
        colour: None,
        quantity: Some(1),
        price_cents: None,
        due_date: None, // No SLA
        status: None,
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
    };

    // Insert in non-sorted order (Future, then No Due, then Urgent)
    let Json(comm_future) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(payload_future),
    )
    .await
    .unwrap();

    let Json(comm_no_due) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(payload_no_due),
    )
    .await
    .unwrap();

    let Json(comm_urgent) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        HeaderMap::new(),
        Json(payload_urgent),
    )
    .await
    .unwrap();

    // Query list_commissions
    let Json(list) = list_commissions(State(ctx.state.clone()), ctx.user_a.clone())
        .await
        .unwrap();

    assert_eq!(list.len(), 3);
    // Invariant: Most urgent deadline comes first
    assert_eq!(
        list[0].id, comm_urgent.id,
        "First item must be the most urgent commission"
    );
    // Invariant: Later deadline comes second
    assert_eq!(
        list[1].id, comm_future.id,
        "Second item must be the future deadline commission"
    );
    // Invariant: Commissions with no due date appear last
    assert_eq!(
        list[2].id, comm_no_due.id,
        "Last item must be the commission with no due date"
    );
}

#[tokio::test]
async fn test_nonexistent_commission_returns_not_found() {
    let ctx = setup_test_context().await;
    let fake_id = "00000000-0000-0000-0000-000000000000".to_string();

    let result = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(fake_id),
    )
    .await;

    assert!(matches!(result, Err(AppError::NotFound(_))));
}
