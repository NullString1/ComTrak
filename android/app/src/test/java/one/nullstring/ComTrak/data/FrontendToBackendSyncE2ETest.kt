package one.nullstring.ComTrak.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import one.nullstring.ComTrak.data.remote.ComTrakApiService
import one.nullstring.ComTrak.data.remote.CommissionDto
import one.nullstring.ComTrak.data.remote.CommissionUpdateDto
import one.nullstring.ComTrak.data.remote.CreateCommissionRequestDto
import one.nullstring.ComTrak.data.remote.CreateUpdateRequestDto
import one.nullstring.ComTrak.data.remote.GoogleAuthRequestDto
import one.nullstring.ComTrak.data.remote.SyncPushRequestDto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

class FrontendToBackendSyncE2ETest {

    private lateinit var apiService: ComTrakApiService
    private var authToken: String? = null

    private fun createClient(tokenProvider: () -> String?): ComTrakApiService {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
            tokenProvider()?.let {
                request.header("Authorization", "Bearer $it")
            }
            chain.proceed(request.build())
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("http://127.0.0.1:8080/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ComTrakApiService::class.java)
    }

    @Before
    fun setUp() {
        apiService = createClient { authToken }
    }

    private suspend fun loginUser(devSuffix: String): Pair<String, String> {
        val loginResponse = apiService.googleLogin(GoogleAuthRequestDto(id_token = "mock_dev_token_$devSuffix"))
        assertTrue("Dev login for $devSuffix must succeed", loginResponse.isSuccessful)
        val authData = loginResponse.body()
        assertNotNull(authData)
        return Pair(authData!!.token, authData.user.id)
    }

    @Test
    fun testEndToEnd_FrontendToBackendSyncJourney() = runBlocking {
        // Step 1: Frontend Google Dev Login
        val (token, userId) = loginUser("sync_journey_runner")
        authToken = token

        // Step 2: Client creates an offline commission with a client-generated UUID
        val clientCommissionId = "android-client-" + UUID.randomUUID().toString()
        val clientUpdateId = "android-update-" + UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val offlineCommission = CommissionDto(
            id = clientCommissionId,
            user_id = userId,
            title = "Wheel-Thrown Matcha Bowl",
            description = "Traditional Japanese tea bowl with faceted rim",
            customer_name = "Master Tanaka",
            customer_phone = "+81 90 1234 5678",
            customer_email = "tanaka@tea-ceremony.jp",
            item_name = "Matcha Chawan",
            size = "13cm diameter x 8cm height",
            colour = "Tenmoku black with persimmon splash",
            quantity = 1,
            price_cents = 14500, // $145.00
            due_date = now + TimeUnit.DAYS.toMillis(14),
            status = "ready_to_begin",
            clay_body = "Mogusa White Clay",
            glazes = "Oil Spot Tenmoku",
            thumbnail_media_id = null,
            created_at = now,
            updated_at = now,
            deleted_at = null,
            version = 1
        )

        val offlineUpdate = CommissionUpdateDto(
            id = clientUpdateId,
            commission_id = clientCommissionId,
            user_id = userId,
            from_status = "requiring_details",
            to_status = "ready_to_begin",
            note = "Client approved persimmon splash pattern. Wheel work starting.",
            created_at = now,
            deleted_at = null
        )

        // Step 3: Frontend triggers SyncPushRequest
        val pushRequest = SyncPushRequestDto(
            commissions = listOf(offlineCommission),
            updates = listOf(offlineUpdate)
        )

        val pushResponse = apiService.syncPush(pushRequest)
        assertTrue("Sync push must succeed with 200 OK", pushResponse.isSuccessful)
        val pushData = pushResponse.body()
        assertNotNull(pushData)

        assertTrue(
            "Server must accept and acknowledge client commission UUID",
            pushData!!.accepted_commission_ids.contains(clientCommissionId)
        )
        assertTrue(
            "Server must accept and acknowledge client update UUID",
            pushData.accepted_update_ids.contains(clientUpdateId)
        )

        // Step 4: Frontend triggers SyncPullRequest to reconcile deltas
        val pullResponse = apiService.syncPull(since = 0)
        assertTrue("Sync pull must succeed with 200 OK", pullResponse.isSuccessful)
        val pullData = pullResponse.body()
        assertNotNull(pullData)

        val pulledComm = pullData!!.commissions.find { it.id == clientCommissionId }
        assertNotNull("Pulled delta must contain the synced commission", pulledComm)
        assertEquals("Wheel-Thrown Matcha Bowl", pulledComm!!.title)
        assertEquals("Master Tanaka", pulledComm.customer_name)
        assertEquals(14500L, pulledComm.price_cents)
        assertEquals("Mogusa White Clay", pulledComm.clay_body)

        val pulledUpdate = pullData.updates.find { it.id == clientUpdateId }
        assertNotNull("Pulled delta must contain the synced timeline update", pulledUpdate)
        assertEquals("Client approved persimmon splash pattern. Wheel work starting.", pulledUpdate!!.note)

        // Step 5: Test incremental sync pull with cutoff timestamp
        val incrementalResponse = apiService.syncPull(since = now + 1000)
        assertTrue(incrementalResponse.isSuccessful)
        val incrementalData = incrementalResponse.body()
        assertNotNull(incrementalData)
        val staleCheck = incrementalData!!.commissions.find { it.id == clientCommissionId }
        assertNull("Incremental pull with future since timestamp must omit older items", staleCheck)
    }

    @Test
    fun testEndToEnd_CommissionLifecycleAndPotteryPhases() = runBlocking {
        val (token, _) = loginUser("pottery_lifecycle_runner")
        authToken = token

        // Step 1: Create a commission in the initial state
        val commissionId = "lifecycle-comm-" + UUID.randomUUID().toString()
        val createDto = CreateCommissionRequestDto(
            id = commissionId,
            title = "Terracotta Amphora Vase",
            customer_name = "Elena Rostova",
            item_name = "Amphora Vase",
            quantity = 2,
            price_cents = 28000,
            status = "requiring_details",
            clay_body = "Red Earthenware Terracotta"
        )

        val createResp = apiService.createCommission(createDto)
        assertTrue("Commission creation should return 200 OK", createResp.isSuccessful)
        val created = createResp.body()!!
        assertEquals("requiring_details", created.status)
        assertEquals(1L, created.version)

        // Step 2: Progress through the 4 pottery lifecycle stages
        val stages = listOf(
            Pair("ready_to_begin", "Handles pulled and clay wedged."),
            Pair("need_glazing", "Bisque firing completed at cone 06. Ready for wax resist & dip."),
            Pair("payment_and_delivery", "Glaze fired to cone 6 oxidation. Customer notified."),
            Pair("done", "Customer picked up and paid balance. Order complete.")
        )

        for ((idx, stage) in stages.withIndex()) {
            val (toStatus, note) = stage
            val updateResp = apiService.addUpdate(
                commissionId = commissionId,
                body = CreateUpdateRequestDto(
                    to_status = toStatus,
                    note = note
                )
            )
            assertTrue("Update to stage $toStatus must succeed", updateResp.isSuccessful)
            val updateBody = updateResp.body()!!
            assertEquals(toStatus, updateBody.to_status)
            assertEquals(note, updateBody.note)

            // Verify parent commission status and version incremented
            val currentComm = apiService.getCommission(commissionId).body()!!
            assertEquals(toStatus, currentComm.status)
            assertEquals((idx + 2).toLong(), currentComm.version)
        }

        // Step 3: Verify the full timeline updates order
        val updatesListResp = apiService.listUpdates(commissionId)
        assertTrue(updatesListResp.isSuccessful)
        val updatesList = updatesListResp.body()!!
        assertEquals(4, updatesList.size)
        assertEquals("ready_to_begin", updatesList[0].to_status)
        assertEquals("need_glazing", updatesList[1].to_status)
        assertEquals("payment_and_delivery", updatesList[2].to_status)
        assertEquals("done", updatesList[3].to_status)
    }

    @Test
    fun testEndToEnd_MediaUploadAndProxyStream() = runBlocking {
        val (token, _) = loginUser("media_e2e_runner")
        authToken = token

        // Step 1: Create a commission
        val commissionId = "media-comm-" + UUID.randomUUID().toString()
        val createDto = CreateCommissionRequestDto(
            id = commissionId,
            title = "Porcelain Teapot",
            customer_name = "Sarah Jenkins",
            item_name = "Teapot",
            status = "ready_to_begin"
        )
        apiService.createCommission(createDto)

        // Step 2: Upload a WebP image attachment
        val mediaId = UUID.randomUUID().toString()
        val testPayload = "RIFF\u0024\u0000\u0000\u0000WEBPVP8 \u0018\u0000\u0000\u0000Sample Pottery Image Bytes".toByteArray()

        val requestFile = testPayload.toRequestBody("image/webp".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", "spout_detail.webp", requestFile)
        val commIdPart = commissionId.toRequestBody("text/plain".toMediaTypeOrNull())
        val mediaIdPart = mediaId.toRequestBody("text/plain".toMediaTypeOrNull())
        val mediaTypePart = "progress".toRequestBody("text/plain".toMediaTypeOrNull())

        val uploadResp = apiService.uploadMedia(
            file = filePart,
            commissionId = commIdPart,
            mediaId = mediaIdPart,
            mediaType = mediaTypePart
        )
        assertTrue("Media upload must succeed with 200 OK", uploadResp.isSuccessful)
        val uploadData = uploadResp.body()!!
        assertEquals(mediaId, uploadData.id)
        assertEquals(commissionId, uploadData.commission_id)
        assertEquals("/api/v1/media/$mediaId", uploadData.url)

        // Step 3: Stream/Download media bytes via backend proxy
        val downloadResp = apiService.getMedia(mediaId)
        assertTrue("Media streaming must return HTTP 200", downloadResp.isSuccessful)
        val downloadedBytes = downloadResp.body()!!.bytes()
        assertArrayEquals("Downloaded bytes must match uploaded image data exactly", testPayload, downloadedBytes)

        // Step 4: Verify commission thumbnail was automatically updated
        val updatedComm = apiService.getCommission(commissionId).body()!!
        assertEquals("First uploaded image should become thumbnail", mediaId, updatedComm.thumbnail_media_id)
    }

    @Test
    fun testEndToEnd_SoftDeleteAndTombstoneReconciliation() = runBlocking {
        val (token, _) = loginUser("soft_delete_runner")
        authToken = token

        // Step 1: Create a commission
        val commissionId = "tombstone-comm-" + UUID.randomUUID().toString()
        apiService.createCommission(
            CreateCommissionRequestDto(
                id = commissionId,
                title = "Cancelled Planter Pot",
                customer_name = "Cancelled Client",
                item_name = "Planter Pot",
                status = "requiring_details"
            )
        )

        // Step 2: Soft delete the commission
        val deleteResp = apiService.deleteCommission(commissionId)
        assertTrue("Delete must return HTTP 200/204", deleteResp.isSuccessful)

        // Step 3: Directly fetching the deleted commission must return 404
        val fetchResp = apiService.getCommission(commissionId)
        assertEquals(404, fetchResp.code())

        // Step 4: Delta sync pull must return the commission with non-null deleted_at tombstone
        val pullResp = apiService.syncPull(since = 0)
        assertTrue(pullResp.isSuccessful)
        val pullData = pullResp.body()!!
        val tombstone = pullData.commissions.find { it.id == commissionId }
        assertNotNull("Tombstone for deleted commission must be returned in sync pull", tombstone)
        assertNotNull("deleted_at must be populated for tombstone", tombstone!!.deleted_at)
    }

    @Test
    fun testEndToEnd_MultiTenantIsolation() = runBlocking {
        // User Alpha
        val (tokenAlpha, _) = loginUser("tenant_alpha")
        val alphaClient = createClient { tokenAlpha }

        // User Beta
        val (tokenBeta, _) = loginUser("tenant_beta")
        val betaClient = createClient { tokenBeta }

        // Step 1: User Alpha creates a confidential commission
        val alphaCommId = "confidential-alpha-" + UUID.randomUUID().toString()
        val createResp = alphaClient.createCommission(
            CreateCommissionRequestDto(
                id = alphaCommId,
                title = "Private Gallery Vases",
                customer_name = "VIP Collector",
                item_name = "Vase Set",
                status = "ready_to_begin"
            )
        )
        assertTrue(createResp.isSuccessful)

        // Step 2: User Beta attempts to fetch User Alpha's commission -> HTTP 404
        val unauthorizedGet = betaClient.getCommission(alphaCommId)
        assertEquals("User B must not be able to read User A's commission", 404, unauthorizedGet.code())

        // Step 3: User Beta attempts to delete User Alpha's commission -> HTTP 404
        val unauthorizedDelete = betaClient.deleteCommission(alphaCommId)
        assertEquals("User B must not be able to delete User A's commission", 404, unauthorizedDelete.code())

        // Step 4: User Beta calls syncPull -> must NEVER receive User Alpha's commission
        val betaPullResp = betaClient.syncPull(since = 0)
        assertTrue(betaPullResp.isSuccessful)
        val betaItems = betaPullResp.body()!!.commissions
        assertFalse(
            "User B's sync pull must not contain any items belonging to User A",
            betaItems.any { it.id == alphaCommId }
        )
    }
}
