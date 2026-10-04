pub mod auth;
pub mod config;
pub mod db;
pub mod error;
pub mod models;
pub mod routes;
pub mod storage;

use std::sync::Arc;
use crate::auth::GoogleVerifier;
use crate::config::AppConfig;
use crate::db::DbPool;
use crate::storage::StorageService;

#[derive(Clone)]
pub struct AppState {
    pub db: DbPool,
    pub storage: StorageService,
    pub config: Arc<AppConfig>,
    pub google_verifier: Arc<GoogleVerifier>,
}
