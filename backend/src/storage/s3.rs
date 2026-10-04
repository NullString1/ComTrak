use aws_credential_types::Credentials;
use aws_sdk_s3::config::{BehaviorVersion, Region};
use aws_sdk_s3::primitives::ByteStream;
use aws_sdk_s3::Client as S3Client;
use crate::config::AppConfig;
use crate::error::{AppError, Result};

#[derive(Clone)]
pub struct StorageService {
    client: S3Client,
    bucket: String,
}

impl StorageService {
    pub async fn new(config: &AppConfig) -> Self {
        let creds = Credentials::new(
            &config.garage_access_key,
            &config.garage_secret_key,
            None,
            None,
            "static",
        );

        let sdk_config = aws_config::defaults(BehaviorVersion::latest())
            .region(Region::new(config.garage_region.clone()))
            .endpoint_url(&config.garage_endpoint)
            .credentials_provider(creds)
            .load()
            .await;

        let s3_config = aws_sdk_s3::config::Builder::from(&sdk_config)
            .force_path_style(true)
            .build();

        let client = S3Client::from_conf(s3_config);

        Self {
            client,
            bucket: config.garage_bucket.clone(),
        }
    }

    /// Ensure the target bucket exists in Garage
    pub async fn ensure_bucket(&self) -> Result<()> {
        match self.client.head_bucket().bucket(&self.bucket).send().await {
            Ok(_) => {
                tracing::info!("Garage bucket '{}' verified.", self.bucket);
                Ok(())
            }
            Err(e) => {
                tracing::warn!("Garage bucket '{}' head check failed: {}. Attempting create...", self.bucket, e);
                match self.client.create_bucket().bucket(&self.bucket).send().await {
                    Ok(_) => {
                        tracing::info!("Garage bucket '{}' created successfully.", self.bucket);
                        Ok(())
                    }
                    Err(create_err) => {
                        // Might already exist or initialized by garage-init
                        tracing::warn!("Create bucket result: {}", create_err);
                        Ok(())
                    }
                }
            }
        }
    }

    /// Upload a byte buffer (WebP image) to Garage
    pub async fn upload_object(&self, key: &str, data: Vec<u8>, mime_type: &str) -> Result<()> {
        self.client
            .put_object()
            .bucket(&self.bucket)
            .key(key)
            .body(ByteStream::from(data))
            .content_type(mime_type)
            .send()
            .await
            .map_err(|e| AppError::Storage(format!("Failed to upload object '{}': {}", key, e)))?;

        Ok(())
    }

    /// Retrieve an object stream and content type from Garage
    pub async fn get_object(&self, key: &str) -> Result<(Vec<u8>, String)> {
        let resp = self
            .client
            .get_object()
            .bucket(&self.bucket)
            .key(key)
            .send()
            .await
            .map_err(|e| AppError::Storage(format!("Failed to fetch object '{}': {}", key, e)))?;

        let mime_type = resp
            .content_type()
            .unwrap_or("image/webp")
            .to_string();

        let body = resp
            .body
            .collect()
            .await
            .map_err(|e| AppError::Storage(format!("Failed to read stream for '{}': {}", key, e)))?
            .into_bytes()
            .to_vec();

        Ok((body, mime_type))
    }

    /// Delete an object from Garage
    pub async fn delete_object(&self, key: &str) -> Result<()> {
        self.client
            .delete_object()
            .bucket(&self.bucket)
            .key(key)
            .send()
            .await
            .map_err(|e| AppError::Storage(format!("Failed to delete object '{}': {}", key, e)))?;

        Ok(())
    }
}
