use std::fs;
use std::path::Path;
use std::str::FromStr;
use std::time::Duration;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use sqlx::{Pool, Sqlite};
use crate::error::Result;

pub type DbPool = Pool<Sqlite>;

pub async fn init_db(database_url: &str, data_dir: &str) -> Result<DbPool> {
    // Ensure data directory exists
    if let Err(e) = fs::create_dir_all(Path::new(data_dir)) {
        tracing::warn!("Could not ensure data directory '{}': {}", data_dir, e);
    }

    tracing::info!("Connecting to SQLite database at {}", database_url);

    let connection_options = SqliteConnectOptions::from_str(database_url)?
        .create_if_missing(true)
        .journal_mode(sqlx::sqlite::SqliteJournalMode::Wal)
        .synchronous(sqlx::sqlite::SqliteSynchronous::Normal)
        .foreign_keys(true)
        .busy_timeout(Duration::from_secs(10));

    let pool = SqlitePoolOptions::new()
        .max_connections(20)
        .min_connections(2)
        .acquire_timeout(Duration::from_secs(5))
        .connect_with(connection_options)
        .await?;

    tracing::info!("Running database migrations...");
    sqlx::migrate!("./migrations")
        .run(&pool)
        .await?;

    tracing::info!("Database migrations applied successfully.");
    Ok(pool)
}
