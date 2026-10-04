package com.comtrak.app.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comtrak.app.data.local.entities.CommissionEntity
import com.comtrak.app.data.local.entities.CommissionUpdateEntity
import com.comtrak.app.data.local.entities.MediaAttachmentEntity
import com.comtrak.app.data.remote.SessionManager
import com.comtrak.app.domain.model.CommissionState
import com.comtrak.app.ui.components.CommissionProgressBar
import com.comtrak.app.ui.components.SlaBadge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommissionDetailScreen(
    viewModel: CommissionDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (String) -> Unit
) {
    val context = LocalContext.current
    val commission by viewModel.commission.collectAsState()
    val updates by viewModel.updates.collectAsState()
    val mediaList by viewModel.mediaAttachments.collectAsState()
    val isProcessingPhoto by viewModel.isProcessingPhoto.collectAsState()

    var showAddUpdateSheet by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.addFinishedPhoto(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(commission?.title ?: "Commission Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    commission?.let { comm ->
                        IconButton(onClick = { onNavigateToEdit(comm.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit details")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Button(
                    onClick = { showAddUpdateSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PostAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Studio Update & Change State")
                }
            }
        }
    ) { innerPadding ->
        val comm = commission
        if (comm == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val currentState = remember(comm.status) { CommissionState.fromId(comm.status) }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Progress & Status Card
                item {
                    StatusOverviewCard(
                        commission = comm,
                        currentState = currentState,
                        onStatusChanged = { newStatus -> viewModel.updateStatus(newStatus) }
                    )
                }

                // 2. Client & Contact Card
                item {
                    ClientInfoCard(comm = comm, context = context)
                }

                // 3. Technical Ceramic Specs Card
                item {
                    CeramicSpecsCard(comm = comm)
                }

                // 4. Photo Gallery (Reference, WIP, Finished)
                item {
                    PhotoGallerySection(
                        mediaList = mediaList,
                        onAddFinishedPhoto = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }

                // 5. Studio Timeline of Updates
                item {
                    Text(
                        text = "Studio Timeline (${updates.size} updates)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (updates.isEmpty()) {
                    item {
                        Text(
                            text = "No updates recorded yet. Post progress photos, clay/glaze notes, or state transitions below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(updates.reversed(), key = { it.id }) { update ->
                        TimelineUpdateCard(
                            update = update,
                            attachedMedia = mediaList.filter { it.updateId == update.id }
                        )
                    }
                }
            }
        }

        // Bottom Sheet for adding updates
        if (showAddUpdateSheet && commission != null) {
            AddUpdateBottomSheet(
                currentStatus = commission!!.status,
                onDismiss = { showAddUpdateSheet = false },
                onAddUpdate = { toStatus, note, uris ->
                    viewModel.addUpdate(toStatus, note, uris)
                    showAddUpdateSheet = false
                }
            )
        }

        if (isProcessingPhoto) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Compressing WebP photo...", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun StatusOverviewCard(
    commission: CommissionEntity,
    currentState: CommissionState,
    onStatusChanged: (String) -> Unit
) {
    var statusMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(currentState.color.copy(alpha = 0.15f))
                            .clickable { statusMenuExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentState.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = currentState.color
                        )
                        Text(" ▼", fontSize = 10.sp, color = currentState.color)
                    }

                    DropdownMenu(
                        expanded = statusMenuExpanded,
                        onDismissRequest = { statusMenuExpanded = false }
                    ) {
                        CommissionState.entries.forEach { state ->
                            DropdownMenuItem(
                                text = { Text(state.displayName) },
                                onClick = {
                                    statusMenuExpanded = false
                                    onStatusChanged(state.id)
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(state.color)
                                    )
                                }
                            )
                        }
                    }
                }

                SlaBadge(dueDateEpochMs = commission.dueDate, isCompleted = currentState == CommissionState.DONE)
            }

            Spacer(modifier = Modifier.height(14.dp))
            CommissionProgressBar(state = currentState, showLabel = true)
        }
    }
}

@Composable
fun ClientInfoCard(comm: CommissionEntity, context: android.content.Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Client & Order Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ordered by: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(comm.customerName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }

            if (!comm.customerPhone.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${comm.customerPhone}"))
                        context.startActivity(intent)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(comm.customerPhone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (!comm.customerEmail.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${comm.customerEmail}"))
                        context.startActivity(intent)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Email, contentDescription = "Email", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(comm.customerEmail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Text("Item: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${comm.quantity}x ${comm.itemName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            if (!comm.size.isNullOrBlank()) {
                Text("Dimensions: ${comm.size}", style = MaterialTheme.typography.bodyMedium)
            }
            if (!comm.colour.isNullOrBlank()) {
                Text("Target Colour: ${comm.colour}", style = MaterialTheme.typography.bodyMedium)
            }
            comm.priceCents?.let {
                Text("Total Price: $${String.format(Locale.US, "%.2f", it / 100.0)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun CeramicSpecsCard(comm: CommissionEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Ceramic Recipes & Materials", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Text("Clay Body:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(comm.clayBody ?: "Not yet specified", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(6.dp))
            Text("Glazes:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(comm.glazes ?: "Not yet specified", style = MaterialTheme.typography.bodyMedium)

            if (!comm.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Studio Notes:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(comm.description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun PhotoGallerySection(
    mediaList: List<MediaAttachmentEntity>,
    onAddFinishedPhoto: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Reference (${mediaList.count { it.mediaType == "reference" }})",
                      "Progress (${mediaList.count { it.mediaType == "progress" }})",
                      "Finished (${mediaList.count { it.mediaType == "finished" }})")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Photos & Reference", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (selectedTab == 2) {
                    TextButton(onClick = onAddFinishedPhoto) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Finished Pic")
                    }
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val currentType = when (selectedTab) {
                0 -> "reference"
                1 -> "progress"
                else -> "finished"
            }
            val filteredMedia = mediaList.filter { it.mediaType == currentType }

            if (filteredMedia.isEmpty()) {
                Text("No $currentType photos uploaded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(filteredMedia, key = { it.id }) { media ->
                        MediaThumbnail(media = media)
                    }
                }
            }
        }
    }
}

@Composable
fun MediaThumbnail(media: MediaAttachmentEntity) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val localFile = File(media.localPath)
    val model: Any = if (localFile.exists()) localFile else "${sessionManager.serverBaseUrl}${media.remoteUrl}"

    Box(
        modifier = Modifier
            .size(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(model).crossfade(true).build(),
            contentDescription = "Attached photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun TimelineUpdateCard(update: CommissionUpdateEntity, attachedMedia: List<MediaAttachmentEntity>) {
    val sdf = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()) }
    val dateString = remember(update.createdAt) { sdf.format(Date(update.createdAt)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(dateString, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                update.toStatus?.let {
                    val targetState = CommissionState.fromId(it)
                    Text(targetState.displayName, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = targetState.color)
                }
            }

            if (!update.note.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(update.note, style = MaterialTheme.typography.bodyMedium)
            }

            if (attachedMedia.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(attachedMedia, key = { it.id }) { media ->
                        MediaThumbnail(media = media)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddUpdateBottomSheet(
    currentStatus: String,
    onDismiss: () -> Unit,
    onAddUpdate: (toStatus: String?, note: String?, photoUris: List<Uri>) -> Unit
) {
    var note by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf(currentStatus) }
    var selectedPhotoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        selectedPhotoUris = uris
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text("Add Studio Update", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))

            Text("Change State:", style = MaterialTheme.typography.labelSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CommissionState.entries.forEach { state ->
                    FilterChip(
                        selected = selectedStatus == state.id,
                        onClick = { selectedStatus = state.id },
                        label = { Text(state.displayName, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Update notes (clay batch, glaze, firing results...)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Attach Photos (${selectedPhotoUris.size})")
                }

                Button(
                    onClick = {
                        val finalStatus = if (selectedStatus != currentStatus) selectedStatus else null
                        onAddUpdate(finalStatus, note.ifBlank { null }, selectedPhotoUris)
                    }
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Post")
                }
            }
        }
    }
}
