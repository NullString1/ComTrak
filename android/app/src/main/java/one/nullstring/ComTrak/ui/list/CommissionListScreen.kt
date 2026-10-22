package one.nullstring.ComTrak.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import one.nullstring.ComTrak.data.remote.SessionManager
import one.nullstring.ComTrak.domain.model.CommissionState
import one.nullstring.ComTrak.ui.components.CommissionProgressBar
import one.nullstring.ComTrak.ui.components.SlaBadge
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommissionListScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToNew: () -> Unit,
    viewModel: CommissionListViewModel = viewModel()
) {
    val commissions by viewModel.commissions.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var selectedForBottomSheet by remember { mutableStateOf<CommissionEntity?>(null) }
    var isSearchExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchExpanded) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.searchQuery.value = it },
                            placeholder = { Text("Search title, customer, clay...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            trailingIcon = {
                                IconButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        viewModel.searchQuery.value = ""
                                    } else {
                                        isSearchExpanded = false
                                    }
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "ComTrak Studio",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    if (!isSearchExpanded) {
                        IconButton(onClick = { isSearchExpanded = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search commissions")
                        }
                    }
                    IconButton(onClick = { viewModel.syncNow() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync now")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToNew,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add new commission")
            }
        }
    ) { innerPadding ->
        if (commissions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "No commissions yet" else "No matching commissions",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        // Trimmed from "Tap the + button to add your first pottery commission":
                        // at 32dp the old copy measured 1055px in a 900px measure and orphaned
                        // "commission" onto line 2; this fits on one centred line (~837px).
                        text = "Tap + to add your first pottery commission",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(commissions, key = { it.id }) { commission ->
                    CommissionCard(
                        commission = commission,
                        onClick = { onNavigateToDetail(commission.id) },
                        onLongClick = { selectedForBottomSheet = commission }
                    )
                }
            }
        }

        // Long-press BottomSheet for Share & Delete
        selectedForBottomSheet?.let { commission ->
            ShareReceiptBottomSheet(
                commission = commission,
                onDismiss = { selectedForBottomSheet = null },
                onDelete = { id -> viewModel.deleteCommission(id) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommissionCard(
    commission: CommissionEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    val state = remember(commission.status) { CommissionState.fromId(commission.status) }
    val isCompleted = state == CommissionState.DONE

    // Resolve thumbnail: check local file or remote backend URL
    val thumbnailModel: Any? = remember(commission.thumbnailMediaId) {
        commission.thumbnailMediaId?.let { mediaId ->
            val localFile = File(context.filesDir, "media/$mediaId.webp")
            if (localFile.exists()) {
                localFile
            } else {
                "${sessionManager.serverBaseUrl}/api/v1/media/$mediaId"
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Square Thumbnail
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnailModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(thumbnailModel)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Thumbnail for ${commission.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title, Customer, SLA
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = commission.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        SlaBadge(
                            dueDateEpochMs = commission.dueDate,
                            isCompleted = isCompleted
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "For ${commission.customerName} • ${commission.quantity}x ${commission.itemName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5-state Progress Bar
            CommissionProgressBar(
                state = state,
                showLabel = true
            )
        }
    }
}
