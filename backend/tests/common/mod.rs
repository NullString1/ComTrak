use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use uuid::Uuid;

use comtrak_backend::auth::jwt::create_jwt;
use comtrak_backend::auth::AuthUser;
use comtrak_backend::auth::GoogleVerifier;
use comtrak_backend::config::AppConfig;
use comtrak_backend::db::DbPool;
use comtrak_backend::storage::StorageService;
use comtrak_backend::AppState;

#[allow(dead_code)]
pub const TEST_JWT_SECRET: &str = "test-jwt-secret-comtrak-robust-suite-123456789";

#[allow(dead_code)]
pub struct TestContext {
    pub state: AppState,
    pub user_a: AuthUser,
    pub user_b: AuthUser,
    pub token_a: String,
    pub token_b: String,
    pub db_path: String,
}

impl Drop for TestContext {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.db_path);
        let _ = std::fs::remove_file(format!("{}-shm", self.db_path));
        let _ = std::fs::remove_file(format!("{}-wal", self.db_path));
    }
}

pub async fn setup_test_context() -> TestContext {
    let test_id = Uuid::new_v4().to_string();
    let db_path = format!("/tmp/comtrak_test_{}.db", test_id);
    let db_url = format!("sqlite://{}?mode=rwc", db_path);

    let connection_options = SqliteConnectOptions::from_str(&db_url)
        .expect("Invalid SQLite test DB URL")
        .create_if_missing(true)
        .journal_mode(sqlx::sqlite::SqliteJournalMode::Wal)
        .synchronous(sqlx::sqlite::SqliteSynchronous::Normal)
        .foreign_keys(true)
        .busy_timeout(Duration::from_secs(5));

    let db: DbPool = SqlitePoolOptions::new()
        .max_connections(5)
        .min_connections(1)
        .connect_with(connection_options)
        .await
        .expect("Failed to connect to test SQLite DB");

    // Run migrations
    sqlx::migrate!("./migrations")
        .run(&db)
        .await
        .expect("Failed to run migrations on test DB");

    let config = Arc::new(AppConfig {
        host: "127.0.0.1".into(),
        port: 0,
        database_url: db_url.clone(),
        data_dir: "/tmp".into(),
        garage_endpoint: "http://127.0.0.1:3900".into(),
        garage_region: "garage".into(),
        garage_bucket: "comtrak-media".into(),
        garage_access_key: "GKtest".into(),
        garage_secret_key: "testsecret".into(),
        jwt_secret: TEST_JWT_SECRET.into(),
        google_client_id: None,
    });

    let storage = StorageService::new(&config).await;
    let google_verifier = Arc::new(GoogleVerifier::new(None));

    let user_a_id = format!("user_a_{}", Uuid::new_v4());
    let user_b_id = format!("user_b_{}", Uuid::new_v4());
    let email_a = "potter_a@ceramics.test";
    let email_b = "potter_b@ceramics.test";

    // Insert users into database
    let now = chrono::Utc::now().timestamp_millis();
    sqlx::query(
        "INSERT INTO users (id, google_sub, email, display_name, created_at) VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(&user_a_id)
    .bind(format!("sub_{}", user_a_id))
    .bind(email_a)
    .bind("Master Potter A")
    .bind(now)
    .execute(&db)
    .await
    .expect("Failed inserting test user A");

    sqlx::query(
        "INSERT INTO users (id, google_sub, email, display_name, created_at) VALUES ($1, $2, $3, $4, $5)",
    )
    .bind(&user_b_id)
    .bind(format!("sub_{}", user_b_id))
    .bind(email_b)
    .bind("Master Potter B")
    .bind(now)
    .execute(&db)
    .await
    .expect("Failed inserting test user B");

    let token_a = create_jwt(&user_a_id, email_a, TEST_JWT_SECRET).expect("Token A");
    let token_b = create_jwt(&user_b_id, email_b, TEST_JWT_SECRET).expect("Token B");

    let state = AppState {
        db,
        storage,
        config,
        google_verifier,
    };

    TestContext {
        state,
        user_a: AuthUser {
            user_id: user_a_id,
            email: email_a.into(),
        },
        user_b: AuthUser {
            user_id: user_b_id,
            email: email_b.into(),
        },
        token_a,
        token_b,
        db_path,
    }
}
