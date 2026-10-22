use chrono::Utc;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

// ==========================================
// 1. User Models
// ==========================================
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct User {
    pub id: String,
    pub google_sub: String,
    pub email: String,
    pub display_name: Option<String>,
    pub avatar_url: Option<String>,
    pub created_at: i64,
}

#[derive(Debug, Deserialize)]
pub struct GoogleAuthRequest {
    pub id_token: String,
}

#[derive(Debug, Serialize)]
pub struct AuthResponse {
    pub token: String,
    pub user: User,
}

// ==========================================
// 2. Commission Models
// ==========================================
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Commission {
    pub id: String,
    pub user_id: String,
    pub title: String,
    pub description: Option<String>,
    pub customer_name: String,
    pub customer_phone: Option<String>,
    pub customer_email: Option<String>,
    pub item_name: String,
    pub size: Option<String>,
    pub colour: Option<String>,
    pub quantity: i64,
    pub price_cents: Option<i64>,
    pub due_date: Option<i64>,
    pub status: String,
    pub clay_body: Option<String>,
    pub glazes: Option<String>,
    pub thumbnail_media_id: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    pub deleted_at: Option<i64>,
    pub version: i64,
}

#[derive(Debug, Deserialize)]
pub struct CreateCommissionRequest {
    pub id: Option<String>, // Optional client-generated UUID for offline-first creation
    pub title: String,
    pub description: Option<String>,
    pub customer_name: String,
    pub customer_phone: Option<String>,
    pub customer_email: Option<String>,
    pub item_name: String,
    pub size: Option<String>,
    pub colour: Option<String>,
    pub quantity: Option<i64>,
    pub price_cents: Option<i64>,
    pub due_date: Option<i64>,
    pub status: Option<String>,
    pub clay_body: Option<String>,
    pub glazes: Option<String>,
    pub thumbnail_media_id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateCommissionRequest {
    pub title: Option<String>,
    pub description: Option<String>,
    pub customer_name: Option<String>,
    pub customer_phone: Option<String>,
    pub customer_email: Option<String>,
    pub item_name: Option<String>,
    pub size: Option<String>,
    pub colour: Option<String>,
    pub quantity: Option<i64>,
    pub price_cents: Option<i64>,
    pub due_date: Option<i64>,
    pub status: Option<String>,
    pub clay_body: Option<String>,
    pub glazes: Option<String>,
    pub thumbnail_media_id: Option<String>,
}

// ==========================================
// 3. Commission Updates / Timeline Models
// ==========================================
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct CommissionUpdate {
    pub id: String,
    pub commission_id: String,
    pub user_id: String,
    pub from_status: Option<String>,
    pub to_status: Option<String>,
    pub note: Option<String>,
    pub created_at: i64,
    pub deleted_at: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct CreateUpdateRequest {
    pub id: Option<String>, // Optional client-generated UUID
    pub from_status: Option<String>,
    pub to_status: Option<String>,
    pub note: Option<String>,
    pub media_ids: Option<Vec<String>>,
}

// ==========================================
// 4. Media Attachment Models
// ==========================================
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct MediaAttachment {
    pub id: String,
    pub user_id: String,
    pub commission_id: String,
    pub update_id: Option<String>,
    pub media_type: String, // 'reference', 'progress', 'finished'
    pub s3_key: String,
    pub mime_type: String,
    pub width: Option<i64>,
    pub height: Option<i64>,
    pub file_size_bytes: Option<i64>,
    pub created_at: i64,
    pub deleted_at: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct MediaUploadResponse {
    pub id: String,
    pub commission_id: String,
    pub media_type: String,
    pub mime_type: String,
    pub url: String,
}

// ==========================================
// 5. Offline Sync Protocol Models
// ==========================================
#[derive(Debug, Deserialize)]
pub struct SyncPushRequest {
    pub commissions: Vec<CommissionSyncItem>,
    pub updates: Vec<CommissionUpdateSyncItem>,
    #[serde(default)]
    pub media: Vec<MediaAttachment>,
}

#[derive(Debug, Deserialize)]
pub struct CommissionSyncItem {
    pub id: String,
    pub title: String,
    pub description: Option<String>,
    pub customer_name: String,
    pub customer_phone: Option<String>,
    pub customer_email: Option<String>,
    pub item_name: String,
    pub size: Option<String>,
    pub colour: Option<String>,
    pub quantity: i64,
    pub price_cents: Option<i64>,
    pub due_date: Option<i64>,
    pub status: String,
    pub clay_body: Option<String>,
    pub glazes: Option<String>,
    pub thumbnail_media_id: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    pub deleted_at: Option<i64>,
    pub version: i64,
}

#[derive(Debug, Deserialize)]
pub struct CommissionUpdateSyncItem {
    pub id: String,
    pub commission_id: String,
    pub from_status: Option<String>,
    pub to_status: Option<String>,
    pub note: Option<String>,
    pub created_at: i64,
    pub deleted_at: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct SyncPushResponse {
    pub accepted_commission_ids: Vec<String>,
    pub accepted_update_ids: Vec<String>,
    pub accepted_media_ids: Vec<String>,
    pub server_time: i64,
}

#[derive(Debug, Serialize)]
pub struct SyncPullResponse {
    pub server_time: i64,
    pub commissions: Vec<Commission>,
    pub updates: Vec<CommissionUpdate>,
    pub media: Vec<MediaAttachment>,
}

// ==========================================
// 6. Audit Log Models
// ==========================================
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct AuditLog {
    pub id: String,
    pub user_id: Option<String>,
    pub actor_email: Option<String>,
    pub action: String,
    pub entity_type: String,
    pub entity_id: Option<String>,
    pub details: Option<String>,
    pub ip_address: Option<String>,
    pub user_agent: Option<String>,
    pub created_at: i64,
}

// Helper to get current epoch millis
pub fn current_time_millis() -> i64 {
    Utc::now().timestamp_millis()
}

pub fn new_uuid() -> String {
    Uuid::new_v4().to_string()
}

