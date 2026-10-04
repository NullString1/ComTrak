mod common;

use axum::extract::{Path, State};
use axum::Json;

use common::setup_test_context;
use comtrak_backend::error::AppError;
use comtrak_backend::models::{CreateCommissionRequest, CreateUpdateRequest};
use comtrak_backend::routes::commissions::{create_commission, get_commission};
use comtrak_backend::routes::updates::{add_update, list_updates};

#[tokio::test]
async fn test_status_transition_cascades_to_parent_commission() {
    let ctx = setup_test_context().await;

    // 1. Create commission at initial state "requiring_details"
    let create_payload = CreateCommissionRequest {
        id: None,
        title: "Porcelain Teapot with Bamboo Handle".to_string(),
        description: Some("2-cup teapot, unglazed outside, celadon inside".to_string()),
        customer_name: "Liam O'Connor".to_string(),
        customer_phone: Some("+447700900789".to_string()),
        customer_email: Some("liam@tea.ie".to_string()),
        item_name: "Teapot".to_string(),
        size: Some("500ml".to_string()),
        colour: Some("Celadon Jade".to_string()),
        quantity: Some(1),
        price_cents: Some(14000),
        due_date: None,
        status: Some("requiring_details".to_string()),
        clay_body: Some("Southern Ice Porcelain".to_string()),
        glazes: Some("Celadon".to_string()),
        thumbnail_media_id: None,
    };

    let Json(commission) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Json(create_payload),
    )
    .await
    .unwrap();

    assert_eq!(commission.status, "requiring_details");
    assert_eq!(commission.version, 1);

    // 2. Add an update: transition to "ready_to_begin"
    let update_payload = CreateUpdateRequest {
        id: None,
        from_status: Some("requiring_details".to_string()),
        to_status: Some("ready_to_begin".to_string()),
        note: Some("Clay wedged and centered on wheel. Dimensions confirmed with Liam.".to_string()),
        media_ids: None,
    };

    let Json(update_record) = add_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(commission.id.clone()),
        Json(update_payload),
    )
    .await
    .expect("Adding update must succeed");

    // Invariant: Update record accurately reflects state transition
    assert_eq!(update_record.from_status, Some("requiring_details".to_string()));
    assert_eq!(update_record.to_status, Some("ready_to_begin".to_string()));
    assert_eq!(update_record.commission_id, commission.id);

    // 3. Verify parent commission automatically transitioned status and bumped version
    let Json(parent) = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(commission.id.clone()),
    )
    .await
    .unwrap();

    assert_eq!(parent.status, "ready_to_begin", "Parent status must update to to_status");
    assert_eq!(parent.version, 2, "Parent version must bump from 1 to 2");
    assert!(parent.updated_at >= commission.updated_at);
}

#[tokio::test]
async fn test_progress_note_without_status_change() {
    let ctx = setup_test_context().await;

    // Create commission at "ready_to_begin"
    let create_payload = CreateCommissionRequest {
        id: None,
        title: "Carved Ceramic Vase".to_string(),
        description: None,
        customer_name: "Hannah Lee".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Vase".to_string(),
        size: None,
        colour: None,
        quantity: Some(1),
        price_cents: None,
        due_date: None,
        status: Some("ready_to_begin".to_string()),
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
    };

    let Json(commission) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Json(create_payload),
    )
    .await
    .unwrap();

    // Add note without changing status (to_status is None)
    let update_payload = CreateUpdateRequest {
        id: None,
        from_status: None,
        to_status: None,
        note: Some("Bisque fired to Cone 04 successfully without any kiln cracks.".to_string()),
        media_ids: None,
    };

    let Json(update_record) = add_update(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(commission.id.clone()),
        Json(update_payload),
    )
    .await
    .unwrap();

    assert!(update_record.to_status.is_none());

    // Invariant: Parent status remains "ready_to_begin"
    let Json(parent) = get_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(commission.id.clone()),
    )
    .await
    .unwrap();

    assert_eq!(parent.status, "ready_to_begin");
    // Invariant: Version still increments when note is added
    assert_eq!(parent.version, 2);

    // Invariant: Note is in update timeline
    let Json(updates) = list_updates(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(commission.id.clone()),
    )
    .await
    .unwrap();

    assert_eq!(updates.len(), 1);
    assert_eq!(
        updates[0].note,
        Some("Bisque fired to Cone 04 successfully without any kiln cracks.".to_string())
    );
}

#[tokio::test]
async fn test_multi_tenant_isolation_updates() {
    let ctx = setup_test_context().await;

    // User A creates commission
    let create_payload = CreateCommissionRequest {
        id: None,
        title: "User A Vase".to_string(),
        description: None,
        customer_name: "Customer A".to_string(),
        customer_phone: None,
        customer_email: None,
        item_name: "Vase".to_string(),
        size: None,
        colour: None,
        quantity: Some(1),
        price_cents: None,
        due_date: None,
        status: Some("requiring_details".to_string()),
        clay_body: None,
        glazes: None,
        thumbnail_media_id: None,
    };

    let Json(comm_a) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Json(create_payload),
    )
    .await
    .unwrap();

    // 1. User B attempts to add update to User A's commission -> NotFound (404)
    let update_res = add_update(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        Path(comm_a.id.clone()),
        Json(CreateUpdateRequest {
            id: None,
            from_status: None,
            to_status: Some("done".into()),
            note: Some("Sneaky update".into()),
            media_ids: None,
        }),
    )
    .await;

    assert!(
        matches!(update_res, Err(AppError::NotFound(_))),
        "User B must not be allowed to add update to User A's commission"
    );

    // 2. User B attempts to list updates on User A's commission -> NotFound (404)
    let list_res = list_updates(
        State(ctx.state.clone()),
        ctx.user_b.clone(),
        Path(comm_a.id.clone()),
    )
    .await;

    assert!(
        matches!(list_res, Err(AppError::NotFound(_))),
        "User B must not be allowed to list updates for User A's commission"
    );
}

#[tokio::test]
async fn test_full_pottery_workflow_state_transitions() {
    let ctx = setup_test_context().await;

    // Start at requiring_details (v1)
    let Json(comm) = create_commission(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Json(CreateCommissionRequest {
            id: None,
            title: "Custom Ceramic Coffee Dripper".to_string(),
            description: None,
            customer_name: "Coffee Shop".to_string(),
            customer_phone: None,
            customer_email: None,
            item_name: "V60 Dripper".to_string(),
            size: Some("Size 02".to_string()),
            colour: Some("Matte Black".to_string()),
            quantity: Some(6),
            price_cents: Some(18000),
            due_date: None,
            status: Some("requiring_details".to_string()),
            clay_body: None,
            glazes: None,
            thumbnail_media_id: None,
        }),
    )
    .await
    .unwrap();

    assert_eq!(comm.version, 1);
    let states = [
        ("requiring_details", "ready_to_begin", "Finalized groove count and base angle"),
        ("ready_to_begin", "need_glazing", "Thrown, trimmed, and bisque fired"),
        ("need_glazing", "payment_and_delivery", "Glaze fired to Cone 6, packed in bubble wrap"),
        ("payment_and_delivery", "done", "Invoice settled, hand-delivered to cafe"),
    ];

    let mut current_version = 1;

    for (from, to, note) in states {
        let _ = add_update(
            State(ctx.state.clone()),
            ctx.user_a.clone(),
            Path(comm.id.clone()),
            Json(CreateUpdateRequest {
                id: None,
                from_status: Some(from.to_string()),
                to_status: Some(to.to_string()),
                note: Some(note.to_string()),
                media_ids: None,
            }),
        )
        .await
        .unwrap();

        current_version += 1;

        let Json(current) = get_commission(
            State(ctx.state.clone()),
            ctx.user_a.clone(),
            Path(comm.id.clone()),
        )
        .await
        .unwrap();

        assert_eq!(current.status, to);
        assert_eq!(current.version, current_version);
    }

    // Verify all 4 updates exist in chronological order
    let Json(timeline) = list_updates(
        State(ctx.state.clone()),
        ctx.user_a.clone(),
        Path(comm.id.clone()),
    )
    .await
    .unwrap();

    assert_eq!(timeline.len(), 4);
    assert_eq!(timeline[0].to_status, Some("ready_to_begin".into()));
    assert_eq!(timeline[3].to_status, Some("done".into()));
}
