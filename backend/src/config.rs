use std::env;

#[derive(Clone, Debug)]
pub struct AppConfig {
    pub host: String,
    pub port: u16,
    pub database_url: String,
    pub data_dir: String,

    // Garage S3 configuration
    pub garage_endpoint: String,
    pub garage_region: String,
    pub garage_bucket: String,
    pub garage_access_key: String,
    pub garage_secret_key: String,

    // Auth configuration
    pub jwt_secret: String,
    pub google_client_id: Option<String>,
}

impl AppConfig {
    pub fn from_env() -> Self {
        dotenvy::dotenv().ok();

        let host = env::var("HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
        let port = env::var("PORT")
            .ok()
            .and_then(|p| p.parse::<u16>().ok())
            .unwrap_or(8080);
        let data_dir = env::var("DATA_DIR").unwrap_or_else(|_| "./data".to_string());
        let database_url = env::var("DATABASE_URL")
            .unwrap_or_else(|_| format!("sqlite:{}/comtrak.db?mode=rwc", data_dir));

        let garage_endpoint = env::var("GARAGE_ENDPOINT")
            .unwrap_or_else(|_| "http://127.0.0.1:3900".to_string());
        let garage_region = env::var("GARAGE_REGION")
            .unwrap_or_else(|_| "garage".to_string());
        let garage_bucket = env::var("GARAGE_BUCKET")
            .unwrap_or_else(|_| "comtrak-media".to_string());
        let garage_access_key = env::var("GARAGE_ACCESS_KEY")
            .unwrap_or_else(|_| "GKtestkey0000000000000000".to_string());
        let garage_secret_key = env::var("GARAGE_SECRET_KEY")
            .unwrap_or_else(|_| "testsecret00000000000000000000000000000000".to_string());

        let jwt_secret = env::var("JWT_SECRET")
            .unwrap_or_else(|_| "dev-secret-comtrak-development-only-change-me-in-prod".to_string());
        let google_client_id = env::var("GOOGLE_CLIENT_ID")
            .ok()
            .filter(|s| !s.trim().is_empty());

        Self {
            host,
            port,
            database_url,
            data_dir,
            garage_endpoint,
            garage_region,
            garage_bucket,
            garage_access_key,
            garage_secret_key,
            jwt_secret,
            google_client_id,
        }
    }
}
