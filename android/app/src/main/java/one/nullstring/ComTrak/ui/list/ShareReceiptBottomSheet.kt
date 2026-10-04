package one.nullstring.ComTrak.ui.list

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import one.nullstring.ComTrak.domain.model.CommissionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareReceiptBottomSheet(
    commission: CommissionEntity,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val formattedDueDate = remember(commission.dueDate) {
        commission.dueDate?.let {
            SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(it))
        } ?: "No SLA set"
    }

    val state = remember(commission.status) {
        CommissionState.fromId(commission.status)
    }

    val priceDisplay = remember(commission.priceCents) {
        commission.priceCents?.let {
            String.format(Locale.US, "$%.2f", it / 100.0)
        } ?: "Unset"
    }

    // Build the formatted commission receipt
    val receiptText = remember(commission) {
        buildString {
            appendLine("🏺 ComTrak Commission Summary")
            appendLine("═════════════════════════════════════")
            appendLine("Project: ${commission.title}")
            appendLine("Customer: ${commission.customerName}")
            if (!commission.customerPhone.isNullOrBlank()) appendLine("Phone: ${commission.customerPhone}")
            if (!commission.customerEmail.isNullOrBlank()) appendLine("Email: ${commission.customerEmail}")
            appendLine("─────────────────────────────────────")
            appendLine("Item: ${commission.itemName}")
            appendLine("Quantity: ${commission.quantity}")
            if (!commission.size.isNullOrBlank()) appendLine("Dimensions: ${commission.size}")
            if (!commission.colour.isNullOrBlank()) appendLine("Target Colour: ${commission.colour}")
            appendLine("Price: $priceDisplay")
            appendLine("Due Date: $formattedDueDate")
            appendLine("Status: ${state.displayName}")
            if (!commission.clayBody.isNullOrBlank()) appendLine("Clay Body: ${commission.clayBody}")
            if (!commission.glazes.isNullOrBlank()) appendLine("Glazes: ${commission.glazes}")
            if (!commission.description.isNullOrBlank()) {
                appendLine("Notes: ${commission.description}")
            }
            appendLine("═════════════════════════════════════")
            appendLine("Tracked with ComTrak Studio")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Commission Options",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = commission.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Receipt preview box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp)
            ) {
                Text(
                    text = receiptText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, receiptText)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Share Commission Summary")
                        context.startActivity(shareIntent)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share")
                }

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Commission Receipt", receiptText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Summary copied to clipboard", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Delete Button
            Button(
                onClick = { showDeleteConfirmation = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Commission")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Commission?") },
            text = { Text("Are you sure you want to delete '${commission.title}'? This will be removed from your list and synced across your devices.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete(commission.id)
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
