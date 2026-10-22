package one.nullstring.ComTrak.data.local

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import one.nullstring.ComTrak.data.local.entities.CommissionUpdateEntity
import one.nullstring.ComTrak.data.local.entities.MediaAttachmentEntity
import one.nullstring.ComTrak.data.remote.CommissionDto
import one.nullstring.ComTrak.data.remote.CommissionUpdateDto
import one.nullstring.ComTrak.data.remote.MediaAttachmentDto
import one.nullstring.ComTrak.data.remote.SyncPullResponseDto
import one.nullstring.ComTrak.data.repository.CommissionRepository
import one.nullstring.ComTrak.data.sync.SyncPullMerger
import one.nullstring.ComTrak.ui.edit.EditCommissionViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Regression tests for the sync data-loss bug family.
 *
 * Each test FAILS against the pre-fix implementation and PASSES with the fix:
 * - commission upsert was @Insert(REPLACE) = DELETE+INSERT, which cascade-wiped
 *   child updates/media on every pull merge;
 * - pull merges dropped server tombstones (deleted_at) and resurrected rows;
 * - pull merges overwrote local media rows, losing on-device localPath and
 *   PENDING upload state;
 * - addUpdate() never linked attached media (dead loop body);
 * - recordMediaAttachment() left syncPending=false so media metadata never pushed;
 * - the edit screen rebuilt the entity with thumbnailMediaId=null + fresh createdAt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Boot a plain Application: the real ComTrakApp.onCreate schedules WorkManager
// work, which has no initialized backend on the JVM unit-test harness.
@Config(application = Application::class)
class SyncDataLossRegressionTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun commission(
        id: String,
        title: String = "Test Bowl",
        status: String = "requiring_details",
        createdAt: Long = 1_700_000_000_000L,
        updatedAt: Long = 1_700_000_000_000L,
        thumbnailMediaId: String? = null,
        version: Long = 1
    ) = CommissionEntity(
        id = id,
        title = title,
        description = "desc",
        customerName = "Customer",
        customerPhone = null,
        customerEmail = null,
        itemName = "Bowl",
        size = null,
        colour = null,
        quantity = 1,
        priceCents = null,
        dueDate = null,
        status = status,
        clayBody = null,
        glazes = null,
        thumbnailMediaId = thumbnailMediaId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version
    )

    private fun update(id: String, commissionId: String, note: String = "note") =
        CommissionUpdateEntity(
            id = id,
            commissionId = commissionId,
            fromStatus = "requiring_details",
            toStatus = "ready_to_begin",
            note = note,
            createdAt = 1_700_000_000_000L,
            syncPending = true
        )

    private fun media(
        id: String,
        commissionId: String,
        localPath: String = "/data/media/$id.webp",
        uploadStatus: String = "PENDING",
        syncPending: Boolean = true,
        mediaType: String = "reference"
    ) = MediaAttachmentEntity(
        id = id,
        commissionId = commissionId,
        updateId = null,
        mediaType = mediaType,
        localPath = localPath,
        remoteUrl = null,
        fileSizeBytes = 1234L,
        uploadStatus = uploadStatus,
        createdAt = 1_700_000_000_000L,
        syncPending = syncPending
    )

    // 1a. Pull-style commission upsert must not wipe child rows.
    @Test
    fun commissionUpsert_preservesChildRows() = runTest {
        val commId = "comm-" + UUID.randomUUID()
        val updateId = "upd-" + UUID.randomUUID()
        val mediaId = "med-" + UUID.randomUUID()

        db.commissionDao().upsertCommission(commission(commId))
        db.commissionUpdateDao().insertOrUpdate(update(updateId, commId))
        db.mediaAttachmentDao().insertOrUpdate(media(mediaId, commId))

        // Simulate a pull merge delivering newer server state for the commission.
        db.commissionDao().upsertCommission(
            commission(
                commId,
                title = "Server Title",
                status = "ready_to_begin",
                updatedAt = 1_700_000_001_000L
            )
        )

        val merged = db.commissionDao().getCommissionDirect(commId)
        assertNotNull(merged)
        assertEquals("Server Title", merged!!.title)
        assertEquals("ready_to_begin", merged.status)

        assertNotNull(
            "Child update row must survive a parent commission upsert",
            db.commissionUpdateDao().getUpdateDirect(updateId)
        )
        val survivingMedia = db.mediaAttachmentDao().getMediaDirect(mediaId)
        assertNotNull("Child media row must survive a parent commission upsert", survivingMedia)
        assertEquals("/data/media/$mediaId.webp", survivingMedia!!.localPath)
    }

    // 1b. Soft-deleted rows hide from listings but persist as tombstones.
    @Test
    fun softDelete_hidesButKeepsTombstones() = runTest {
        val commId = "comm-" + UUID.randomUUID()
        val updateId = "upd-" + UUID.randomUUID()
        val mediaId = "med-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        db.commissionDao().upsertCommission(commission(commId))
        db.commissionUpdateDao().insertOrUpdate(update(updateId, commId))
        db.mediaAttachmentDao().insertOrUpdate(media(mediaId, commId))

        db.commissionDao().markDeletedLocally(commId, now)
        db.commissionUpdateDao().markDeletedForCommission(commId, now)
        db.mediaAttachmentDao().markDeletedForCommission(commId, now)

        assertTrue(db.commissionUpdateDao().getUpdatesForCommission(commId).first().isEmpty())
        assertTrue(db.mediaAttachmentDao().getMediaForCommission(commId).first().isEmpty())

        val updateTombstone = db.commissionUpdateDao().getUpdateDirect(updateId)
        assertNotNull(updateTombstone)
        assertEquals(now, updateTombstone!!.deletedAt)
        assertTrue(updateTombstone.syncPending)

        val mediaTombstone = db.mediaAttachmentDao().getMediaDirect(mediaId)
        assertNotNull(mediaTombstone)
        assertEquals(now, mediaTombstone!!.deletedAt)
        assertTrue(mediaTombstone.syncPending)
    }

    // 1c. Real pull merge: tombstones applied, PENDING local media untouched.
    @Test
    fun pullMerge_appliesTombstonesAndPreservesPendingMedia() = runTest {
        val commId = "comm-" + UUID.randomUUID()
        val tombCommId = "comm-" + UUID.randomUUID()
        val pendingMediaId = "med-" + UUID.randomUUID()
        val syncedMediaId = "med-" + UUID.randomUUID()
        val updateId = "upd-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        db.commissionDao().upsertCommission(commission(commId))
        db.commissionDao().upsertCommission(commission(tombCommId))
        db.mediaAttachmentDao().insertOrUpdate(media(pendingMediaId, commId)) // PENDING + syncPending
        db.mediaAttachmentDao().insertOrUpdate(
            media(syncedMediaId, commId, uploadStatus = "UPLOADED", syncPending = false)
        )
        db.commissionUpdateDao().insertOrUpdate(update(updateId, commId))

        val serverMediaDto = MediaAttachmentDto(
            id = pendingMediaId,
            user_id = "user-1",
            commission_id = commId,
            update_id = null,
            media_type = "reference",
            s3_key = "commissions/$commId/$pendingMediaId.webp",
            mime_type = "image/webp",
            width = null,
            height = null,
            file_size_bytes = 9999L,
            created_at = now,
            deleted_at = null
        )
        val syncedMediaTombstone = serverMediaDto.copy(id = syncedMediaId, deleted_at = now)
        val updateTombstone = CommissionUpdateDto(
            id = updateId,
            commission_id = commId,
            user_id = "user-1",
            from_status = "requiring_details",
            to_status = "ready_to_begin",
            note = "note",
            created_at = now - 10_000L,
            deleted_at = now
        )
        val commissionTombstone = CommissionDto(
            id = tombCommId,
            user_id = "user-1",
            title = "Gone",
            description = null,
            customer_name = "Customer",
            customer_phone = null,
            customer_email = null,
            item_name = "Bowl",
            size = null,
            colour = null,
            quantity = 1,
            price_cents = null,
            due_date = null,
            status = "requiring_details",
            clay_body = null,
            glazes = null,
            thumbnail_media_id = null,
            created_at = now - 10_000L,
            updated_at = now,
            deleted_at = now,
            version = 2
        )

        SyncPullMerger(db).merge(
            SyncPullResponseDto(
                server_time = now,
                commissions = listOf(commissionTombstone),
                updates = listOf(updateTombstone),
                media = listOf(serverMediaDto, syncedMediaTombstone)
            )
        )

        // PENDING local row: local file ref and upload state intact.
        val pending = db.mediaAttachmentDao().getMediaDirect(pendingMediaId)
        assertNotNull(pending)
        assertEquals("/data/media/$pendingMediaId.webp", pending!!.localPath)
        assertEquals("PENDING", pending.uploadStatus)
        assertTrue(pending.syncPending)

        // Synced-then-server-deleted media: tombstoned, not resurrected.
        val deletedMedia = db.mediaAttachmentDao().getMediaDirect(syncedMediaId)
        assertNotNull(deletedMedia)
        assertEquals(now, deletedMedia!!.deletedAt)

        // Server-deleted update: tombstoned locally.
        val deletedUpdate = db.commissionUpdateDao().getUpdateDirect(updateId)
        assertNotNull(deletedUpdate)
        assertEquals(now, deletedUpdate!!.deletedAt)

        // Server-deleted commission: locally tombstoned.
        val deletedComm = db.commissionDao().getCommissionDirect(tombCommId)
        assertNotNull(deletedComm)
        assertEquals(now, deletedComm!!.deletedAt)
    }

    // 2a. addUpdate must link the given media rows to the new update.
    @Test
    fun addUpdate_linksAttachedMedia() = runTest {
        val repo = CommissionRepository(app, db)
        val commId = "comm-" + UUID.randomUUID()
        db.commissionDao().upsertCommission(commission(commId))

        val linkedId = "med-" + UUID.randomUUID()
        val unlinkedId = "med-" + UUID.randomUUID()
        db.mediaAttachmentDao().insertOrUpdate(media(linkedId, commId))
        db.mediaAttachmentDao().insertOrUpdate(media(unlinkedId, commId))

        val updateId = repo.addUpdate(
            commissionId = commId,
            toStatus = "ready_to_begin",
            note = "started",
            attachedMediaIds = listOf(linkedId)
        )

        assertEquals(updateId, db.mediaAttachmentDao().getMediaDirect(linkedId)!!.updateId)
        assertNull(db.mediaAttachmentDao().getMediaDirect(unlinkedId)!!.updateId)
        assertNotNull(db.commissionUpdateDao().getUpdateDirect(updateId))
    }

    // 2b. recordMediaAttachment: PENDING + syncPending with thumbnail rule.
    @Test
    fun recordMediaAttachment_pendingSyncAndThumbnailRule() = runTest {
        val repo = CommissionRepository(app, db)
        val commId = "comm-" + UUID.randomUUID()
        db.commissionDao().upsertCommission(commission(commId))

        val refId = "med-" + UUID.randomUUID()
        repo.recordMediaAttachment(
            id = refId,
            commissionId = commId,
            localPath = "/data/ref.webp",
            mediaType = "reference",
            fileSizeBytes = 100L
        )

        val refRow = db.mediaAttachmentDao().getMediaDirect(refId)
        assertNotNull(refRow)
        assertEquals("PENDING", refRow!!.uploadStatus)
        assertTrue("New media must be queued for metadata push", refRow.syncPending)
        assertEquals(refId, db.commissionDao().getCommissionDirect(commId)!!.thumbnailMediaId)

        val progressId = "med-" + UUID.randomUUID()
        repo.recordMediaAttachment(
            id = progressId,
            commissionId = commId,
            localPath = "/data/progress.webp",
            mediaType = "progress",
            fileSizeBytes = 200L
        )
        assertEquals(
            "Latest progress photo wins the thumbnail",
            progressId,
            db.commissionDao().getCommissionDirect(commId)!!.thumbnailMediaId
        )

        val ref2Id = "med-" + UUID.randomUUID()
        repo.recordMediaAttachment(
            id = ref2Id,
            commissionId = commId,
            localPath = "/data/ref2.webp",
            mediaType = "reference",
            fileSizeBytes = 300L
        )
        assertEquals(
            "Reference photo must not displace an existing progress thumbnail",
            progressId,
            db.commissionDao().getCommissionDirect(commId)!!.thumbnailMediaId
        )
    }

    // 3. Edit-save must preserve thumbnailMediaId and createdAt.
    @Test
    fun editSave_preservesThumbnailAndCreatedAt() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val commId = "comm-" + UUID.randomUUID()
            val originalCreatedAt = 1_700_000_000_000L
            db.commissionDao().upsertCommission(
                commission(
                    commId,
                    title = "Old Title",
                    thumbnailMediaId = "thumb-media-1",
                    createdAt = originalCreatedAt,
                    version = 3
                )
            )

            val repo = CommissionRepository(app, db)
            val vm = EditCommissionViewModel(app, commId, repo)

            // Room suspend calls complete on background threads while the
            // ViewModel scope runs on the test dispatcher, so pump the
            // scheduler and yield real time until each async step lands.
            suspend fun pumpUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!cond()) {
                    testScheduler.advanceUntilIdle()
                    if (cond() || System.currentTimeMillis() >= deadline) break
                    Thread.sleep(20)
                }
            }

            // Wait for the init loadExistingCommission collect to populate fields
            // (a late first emission would otherwise overwrite the edited title).
            pumpUntil { vm.title.value == "Old Title" }

            // Set edited values explicitly (not relying on the async init load).
            vm.title.value = "New Title"
            vm.customerName.value = "Customer"
            vm.itemName.value = "Bowl"
            var savedId: String? = null
            vm.saveCommission { savedId = it }
            pumpUntil { savedId != null }

            assertEquals(commId, savedId)
            val saved = repo.getCommissionDirect(commId)
            assertNotNull(saved)
            assertEquals("New Title", saved!!.title)
            assertEquals(
                "Edit must preserve the existing thumbnail",
                "thumb-media-1",
                saved.thumbnailMediaId
            )
            assertEquals(
                "Edit must preserve the original createdAt",
                originalCreatedAt,
                saved.createdAt
            )
            assertEquals(4L, saved.version)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
