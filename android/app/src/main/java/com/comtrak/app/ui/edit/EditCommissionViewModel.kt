package com.comtrak.app.ui.edit

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comtrak.app.data.local.entities.CommissionEntity
import com.comtrak.app.data.repository.CommissionRepository
import com.comtrak.app.image.ImageProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EditCommissionViewModel(
    application: Application,
    private val existingId: String?
) : AndroidViewModel(application) {

    private val repository = CommissionRepository(application)
    private val imageProcessor = ImageProcessor(application)

    val title = MutableStateFlow("")
    val description = MutableStateFlow("")
    val customerName = MutableStateFlow("")
    val customerPhone = MutableStateFlow("")
    val customerEmail = MutableStateFlow("")
    val itemName = MutableStateFlow("")
    val size = MutableStateFlow("")
    val colour = MutableStateFlow("")
    val quantity = MutableStateFlow("1")
    val price = MutableStateFlow("")
    val dueDateEpoch = MutableStateFlow<Long?>(null)
    val status = MutableStateFlow("requiring_details")
    val clayBody = MutableStateFlow("")
    val glazes = MutableStateFlow("")

    val referencePhotoUris = MutableStateFlow<List<Uri>>(emptyList())
    val isSaving = MutableStateFlow(false)

    init {
        if (!existingId.isNullOrEmpty()) {
            loadExistingCommission(existingId)
        }
    }

    private fun loadExistingCommission(id: String) {
        viewModelScope.launch {
            repository.getCommissionById(id).collect { comm ->
                comm?.let {
                    title.value = it.title
                    description.value = it.description ?: ""
                    customerName.value = it.customerName
                    customerPhone.value = it.customerPhone ?: ""
                    customerEmail.value = it.customerEmail ?: ""
                    itemName.value = it.itemName
                    size.value = it.size ?: ""
                    colour.value = it.colour ?: ""
                    quantity.value = it.quantity.toString()
                    price.value = it.priceCents?.let { p -> String.format("%.2f", p / 100.0) } ?: ""
                    dueDateEpoch.value = it.dueDate
                    status.value = it.status
                    clayBody.value = it.clayBody ?: ""
                    glazes.value = it.glazes ?: ""
                }
            }
        }
    }

    fun saveCommission(onSuccess: (String) -> Unit) {
        if (title.value.isBlank() || customerName.value.isBlank() || itemName.value.isBlank()) {
            return
        }

        viewModelScope.launch {
            isSaving.value = true
            try {
                val qty = quantity.value.toLongOrNull() ?: 1L
                val priceCents = price.value.toDoubleOrNull()?.let { (it * 100).toLong() }

                val commissionId = if (existingId.isNullOrEmpty()) {
                    repository.createCommission(
                        title = title.value.trim(),
                        description = description.value.trim().ifEmpty { null },
                        customerName = customerName.value.trim(),
                        customerPhone = customerPhone.value.trim().ifEmpty { null },
                        customerEmail = customerEmail.value.trim().ifEmpty { null },
                        itemName = itemName.value.trim(),
                        size = size.value.trim().ifEmpty { null },
                        colour = colour.value.trim().ifEmpty { null },
                        quantity = qty,
                        priceCents = priceCents,
                        dueDate = dueDateEpoch.value,
                        status = status.value,
                        clayBody = clayBody.value.trim().ifEmpty { null },
                        glazes = glazes.value.trim().ifEmpty { null }
                    )
                } else {
                    val existing = repository.getCommissionById(existingId)
                    val updated = CommissionEntity(
                        id = existingId,
                        title = title.value.trim(),
                        description = description.value.trim().ifEmpty { null },
                        customerName = customerName.value.trim(),
                        customerPhone = customerPhone.value.trim().ifEmpty { null },
                        customerEmail = customerEmail.value.trim().ifEmpty { null },
                        itemName = itemName.value.trim(),
                        size = size.value.trim().ifEmpty { null },
                        colour = colour.value.trim().ifEmpty { null },
                        quantity = qty,
                        priceCents = priceCents,
                        dueDate = dueDateEpoch.value,
                        status = status.value,
                        clayBody = clayBody.value.trim().ifEmpty { null },
                        glazes = glazes.value.trim().ifEmpty { null },
                        thumbnailMediaId = null,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateCommission(updated)
                    existingId
                }

                // Process attached reference photos
                for (uri in referencePhotoUris.value) {
                    val result = imageProcessor.processAndCompress(uri)
                    result.onSuccess { processed ->
                        repository.recordMediaAttachment(
                            id = processed.id,
                            commissionId = commissionId,
                            localPath = processed.file.absolutePath,
                            mediaType = "reference",
                            fileSizeBytes = processed.fileSizeBytes
                        )
                    }
                }

                onSuccess(commissionId)
            } finally {
                isSaving.value = false
            }
        }
    }
}
