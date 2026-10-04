package one.nullstring.ComTrak.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

// ==========================================
// DTOs matching Rust Backend
// ==========================================
data class GoogleAuthRequestDto(val id_token: String)

data class UserDto(
    val id: String,
    val google_sub: String,
    val email: String,
    val display_name: String?,
    val avatar_url: String?,
    val created_at: Long
)

data class AuthResponseDto(
    val token: String,
    val user: UserDto
)

data class CreateCommissionRequestDto(
    val id: String? = null,
    val title: String,
    val description: String? = null,
    val customer_name: String,
    val customer_phone: String? = null,
    val customer_email: String? = null,
    val item_name: String,
    val size: String? = null,
    val colour: String? = null,
    val quantity: Long? = 1,
    val price_cents: Long? = null,
    val due_date: Long? = null,
    val status: String? = null,
    val clay_body: String? = null,
    val glazes: String? = null,
    val thumbnail_media_id: String? = null
)

data class CommissionDto(
    val id: String,
    val user_id: String,
    val title: String,
    val description: String?,
    val customer_name: String,
    val customer_phone: String?,
    val customer_email: String?,
    val item_name: String,
    val size: String?,
    val colour: String?,
    val quantity: Long,
    val price_cents: Long?,
    val due_date: Long?,
    val status: String,
    val clay_body: String?,
    val glazes: String?,
    val thumbnail_media_id: String?,
    val created_at: Long,
    val updated_at: Long,
    val deleted_at: Long?,
    val version: Long
)

data class CreateUpdateRequestDto(
    val id: String? = null,
    val from_status: String? = null,
    val to_status: String? = null,
    val note: String? = null,
    val media_ids: List<String>? = null
)

data class CommissionUpdateDto(
    val id: String,
    val commission_id: String,
    val user_id: String,
    val from_status: String?,
    val to_status: String?,
    val note: String?,
    val created_at: Long,
    val deleted_at: Long?
)

data class MediaAttachmentDto(
    val id: String,
    val user_id: String,
    val commission_id: String,
    val update_id: String?,
    val media_type: String,
    val s3_key: String,
    val mime_type: String,
    val width: Long?,
    val height: Long?,
    val file_size_bytes: Long?,
    val created_at: Long,
    val deleted_at: Long?
)

data class MediaUploadResponseDto(
    val id: String,
    val commission_id: String,
    val media_type: String,
    val mime_type: String,
    val url: String
)

data class SyncPushRequestDto(
    val commissions: List<CommissionDto>,
    val updates: List<CommissionUpdateDto>
)

data class SyncPushResponseDto(
    val accepted_commission_ids: List<String>,
    val accepted_update_ids: List<String>,
    val server_time: Long
)

data class SyncPullResponseDto(
    val server_time: Long,
    val commissions: List<CommissionDto>,
    val updates: List<CommissionUpdateDto>,
    val media: List<MediaAttachmentDto>
)

data class AuditLogDto(
    val id: String,
    val user_id: String?,
    val actor_email: String?,
    val action: String,
    val entity_type: String,
    val entity_id: String?,
    val details: String?,
    val ip_address: String?,
    val user_agent: String?,
    val created_at: Long
)

interface ComTrakApiService {

    @POST("/api/v1/auth/google")
    suspend fun googleLogin(@Body body: GoogleAuthRequestDto): Response<AuthResponseDto>

    @GET("/api/v1/commissions")
    suspend fun listCommissions(): Response<List<CommissionDto>>

    @GET("/api/v1/commissions/{id}")
    suspend fun getCommission(@Path("id") id: String): Response<CommissionDto>

    @POST("/api/v1/commissions")
    suspend fun createCommission(@Body body: CreateCommissionRequestDto): Response<CommissionDto>

    @DELETE("/api/v1/commissions/{id}")
    suspend fun deleteCommission(@Path("id") id: String): Response<Unit>

    @POST("/api/v1/commissions/{id}/updates")
    suspend fun addUpdate(
        @Path("id") commissionId: String,
        @Body body: CreateUpdateRequestDto
    ): Response<CommissionUpdateDto>

    @GET("/api/v1/commissions/{id}/updates")
    suspend fun listUpdates(@Path("id") commissionId: String): Response<List<CommissionUpdateDto>>

    @DELETE("/api/v1/commissions/{id}/updates/{update_id}")
    suspend fun deleteUpdate(
        @Path("id") commissionId: String,
        @Path("update_id") updateId: String
    ): Response<Unit>

    @POST("/api/v1/sync/push")
    suspend fun syncPush(@Body body: SyncPushRequestDto): Response<SyncPushResponseDto>

    @GET("/api/v1/sync/pull")
    suspend fun syncPull(@Query("since") since: Long): Response<SyncPullResponseDto>

    @Multipart
    @POST("/api/v1/media/upload")
    suspend fun uploadMedia(
        @Part file: MultipartBody.Part,
        @Part("commission_id") commissionId: RequestBody,
        @Part("media_id") mediaId: RequestBody,
        @Part("media_type") mediaType: RequestBody,
        @Part("update_id") updateId: RequestBody? = null
    ): Response<MediaUploadResponseDto>

    @GET("/api/v1/media/{id}")
    suspend fun getMedia(@Path("id") id: String): Response<ResponseBody>

    @DELETE("/api/v1/media/{id}")
    suspend fun deleteMedia(@Path("id") id: String): Response<Unit>

    @GET("/api/v1/audit-logs")
    suspend fun listAuditLogs(
        @Query("limit") limit: Int? = null,
        @Query("since") since: Long? = null,
        @Query("action") action: String? = null,
        @Query("entity_type") entityType: String? = null,
        @Query("entity_id") entityId: String? = null
    ): Response<List<AuditLogDto>>
}
