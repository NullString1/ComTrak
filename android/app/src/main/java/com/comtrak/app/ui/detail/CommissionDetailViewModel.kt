package com.comtrak.app.ui.detail

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comtrak.app.data.local.entities.CommissionEntity
import com.comtrak.app.data.local.entities.CommissionUpdateEntity
import com.comtrak.app.data.local.entities.MediaAttachmentEntity
import com.comtrak.app.data.repository.CommissionRepository
import com.comtrak.app.image.ImageProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CommissionDetailViewModel(
    application: Application,
    private val commissionId: String
) : AndroidViewModel(application) {

    private val repository = CommissionRepository(application)
    private val imageProcessor = ImageProcessor(application)

    val commission: StateFlow<CommissionEntity?> = repository.getCommissionById(commissionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val updates: StateFlow<List<CommissionUpdateEntity>> = repository.getUpdatesForCommission(commissionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaAttachments: StateFlow<List<MediaAttachmentEntity>> = repository.getMediaForCommission(commissionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isProcessingPhoto = MutableStateFlow(false)

    fun updateStatus(newStatus: String) {
        viewModelScope.launch {
            commission.value?.let { current ->
                repository.updateCommission(current.copy(status = newStatus))
            }
        }
    }

    fun addUpdate(
        toStatus: String?,
        note: String?,
        photoUris: List<Uri>,
        mediaType: String = "progress"
    ) {
        viewModelScope.launch {
            isProcessingPhoto.value = true
            try {
                val createdUpdateId = repository.addUpdate(
                    commissionId = commissionId,
                    toStatus = toStatus,
                    note = note
                )

                // Compress and attach photos
                for (uri in photoUris) {
                    val result = imageProcessor.processAndCompress(uri)
                    result.onSuccess { processed ->
                        repository.recordMediaAttachment(
                            id = processed.id,
                            commissionId = commissionId,
                            localPath = processed.file.absolutePath,
                            mediaType = mediaType,
                            fileSizeBytes = processed.fileSizeBytes,
                            updateId = createdUpdateId
                        )
                    }
                }
            } finally {
                isProcessingPhoto.value = false
            }
        }
    }

    fun addFinishedPhoto(photoUri: Uri) {
        viewModelScope.launch {
            isProcessingPhoto.value = true
            try {
                val result = imageProcessor.processAndCompress(photoUri)
                result.onSuccess { processed ->
                    repository.recordMediaAttachment(
                        id = processed.id,
                        commissionId = commissionId,
                        localPath = processed.file.absolutePath,
                        mediaType = "finished",
                        fileSizeBytes = processed.fileSizeBytes
                    )
                }
            } finally {
                isProcessingPhoto.value = false
            }
        }
    }
}
