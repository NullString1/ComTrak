package one.nullstring.ComTrak.ui.edit

import android.app.DatePickerDialog
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import one.nullstring.ComTrak.domain.model.CommissionState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditCommissionScreen(
    viewModel: EditCommissionViewModel,
    isNew: Boolean,
    onNavigateBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val title by viewModel.title.collectAsState()
    val description by viewModel.description.collectAsState()
    val customerName by viewModel.customerName.collectAsState()
    val customerPhone by viewModel.customerPhone.collectAsState()
    val customerEmail by viewModel.customerEmail.collectAsState()
    val itemName by viewModel.itemName.collectAsState()
    val size by viewModel.size.collectAsState()
    val colour by viewModel.colour.collectAsState()
    val quantity by viewModel.quantity.collectAsState()
    val price by viewModel.price.collectAsState()
    val dueDateEpoch by viewModel.dueDateEpoch.collectAsState()
    val status by viewModel.status.collectAsState()
    val clayBody by viewModel.clayBody.collectAsState()
    val glazes by viewModel.glazes.collectAsState()
    val referencePhotos by viewModel.referencePhotoUris.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.referencePhotoUris.value = viewModel.referencePhotoUris.value + uris
        }
    }

    val formattedDueDate = remember(dueDateEpoch) {
        dueDateEpoch?.let {
            SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(it))
        } ?: "Tap to set SLA / Due Date"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New Commission" else "Edit Commission") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.saveCommission(onSaved) },
                        enabled = !isSaving && title.isNotBlank() && customerName.isNotBlank() && itemName.isNotBlank()
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. General Project Details
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Commission Basics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = title,
                        onValueChange = { viewModel.title.value = it },
                        label = { Text("Project Title * (e.g. Set of 4 Ramen Bowls)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = itemName,
                        onValueChange = { viewModel.itemName.value = it },
                        label = { Text("Item Name * (e.g. Ramen Bowl)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = quantity,
                            onValueChange = { viewModel.quantity.value = it },
                            label = { Text("Quantity") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = price,
                            onValueChange = { viewModel.price.value = it },
                            label = { Text("Price ($)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = size,
                            onValueChange = { viewModel.size.value = it },
                            label = { Text("Size / Dimensions") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = colour,
                            onValueChange = { viewModel.colour.value = it },
                            label = { Text("Target Colour") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }

                    // Due Date Picker Trigger
                    OutlinedTextField(
                        value = formattedDueDate,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("SLA / Due Date") },
                        trailingIcon = {
                            Icon(
                                Icons.Default.CalendarToday,
                                contentDescription = "Select Date",
                                modifier = Modifier.clickable {
                                    val calendar = Calendar.getInstance()
                                    dueDateEpoch?.let { calendar.timeInMillis = it }
                                    DatePickerDialog(
                                        context,
                                        { _, year, month, dayOfMonth ->
                                            val selected = Calendar.getInstance().apply {
                                                set(year, month, dayOfMonth, 23, 59, 59)
                                            }
                                            viewModel.dueDateEpoch.value = selected.timeInMillis
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val calendar = Calendar.getInstance()
                                dueDateEpoch?.let { calendar.timeInMillis = it }
                                DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val selected = Calendar.getInstance().apply {
                                            set(year, month, dayOfMonth, 23, 59, 59)
                                        }
                                        viewModel.dueDateEpoch.value = selected.timeInMillis
                                    },
                                    calendar.get(Calendar.YEAR),
                                    calendar.get(Calendar.MONTH),
                                    calendar.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }
                    )
                }
            }

            // 2. Lifecycle State
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Production State", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CommissionState.entries.forEach { stateItem ->
                            FilterChip(
                                selected = status == stateItem.id,
                                onClick = { viewModel.status.value = stateItem.id },
                                label = { Text(stateItem.displayName, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // 3. Customer Contact Details
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Customer Contact", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = customerName,
                        onValueChange = { viewModel.customerName.value = it },
                        label = { Text("Customer Name *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = customerPhone,
                        onValueChange = { viewModel.customerPhone.value = it },
                        label = { Text("Phone Number (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = customerEmail,
                        onValueChange = { viewModel.customerEmail.value = it },
                        label = { Text("Email Address (optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // 4. Ceramic Recipes & Materials
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ceramic Recipes & Notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = clayBody,
                        onValueChange = { viewModel.clayBody.value = it },
                        label = { Text("Clay Body (e.g. B-Mix, Speckled Buff, Porcelain)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = glazes,
                        onValueChange = { viewModel.glazes.value = it },
                        label = { Text("Glazes (e.g. Chun Plum interior, Satin Black rim)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { viewModel.description.value = it },
                        label = { Text("Optional Studio Notes & Instructions") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            }

            // 5. Reference Pictures
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
                        Text("Reference Photos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) {
                            Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Photos")
                        }
                    }

                    if (referencePhotos.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(referencePhotos) { uri ->
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context).data(uri).build(),
                                        contentDescription = "Reference photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                            .clickable {
                                                viewModel.referencePhotoUris.value =
                                                    viewModel.referencePhotoUris.value.filter { it != uri }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.saveCommission(onSaved) },
                enabled = !isSaving && title.isNotBlank() && customerName.isNotBlank() && itemName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(if (isNew) "Create Commission" else "Save Changes", fontWeight = FontWeight.Bold)
            }
        }
    }
}
