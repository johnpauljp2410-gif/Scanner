package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.LocalIndication
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DbBatch
import com.example.data.DbScan
import com.example.ui.AppViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BatchDetailsScreen(
    viewModel: AppViewModel,
    batchId: String,
    onNavigateBack: () -> Unit,
    onNavigateToScanner: (mode: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val allBatches by viewModel.allBatches.collectAsStateWithLifecycle()
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val connectionOk by viewModel.connectionOk.collectAsStateWithLifecycle()

    val batch = remember(allBatches, batchId) {
        allBatches.find { it.id == batchId }
    }

    val scansFlow = remember(batchId) { viewModel.getScansForBatch(batchId) }
    val scans by scansFlow.collectAsStateWithLifecycle(emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf("") }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    val filteredScans = remember(scans, searchQuery) {
        if (searchQuery.isBlank()) {
            scans
        } else {
            scans.filter { it.data.contains(searchQuery, ignoreCase = true) || it.type.contains(searchQuery, ignoreCase = true) }
        }
    }

    val totalCount = remember(scans) { scans.sumOf { it.count } }
    val uniqueCount = remember(scans) { scans.size }
    val sentCount = remember(scans) { scans.count { it.sent } }
    val unsentCount = remember(scans) { scans.count { !it.sent } }

    val isConnected = activeDevice != null && connectionOk == true

    if (batch == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Session not found", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    LaunchedEffect(batch) {
        editedName = batch.name
    }

    // Export CSV Helper
    val exportCSV = {
        coroutineScope.launch {
            val scanList = scansFlow.first()
            if (scanList.isEmpty()) {
                Toast.makeText(context, "No scans to export", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val csvBuilder = StringBuilder()
            csvBuilder.append("Data,Type,Count,Sent,Timestamp,TimeFormatted\n")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            
            for (s in scanList) {
                // Escape quotes
                val escapedData = s.data.replace("\"", "\"\"")
                val formattedTime = sdf.format(Date(s.timestamp))
                csvBuilder.append("\"$escapedData\",${s.type},${s.count},${s.sent},${s.timestamp},\"$formattedTime\"\n")
            }
            
            val csvString = csvBuilder.toString()
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, csvString)
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "${batch.name} - Scanned Barcodes")
            }
            
            val shareIntent = Intent.createChooser(sendIntent, "Export Session Scans")
            context.startActivity(shareIntent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingName) {
                        TextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                            ),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = batch.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEditingName) {
                        IconButton(onClick = {
                            if (editedName.isNotBlank()) {
                                viewModel.renameBatch(batchId, editedName)
                            }
                            isEditingName = false
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(onClick = { isEditingName = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                        }
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stats Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        StatItem("Total", totalCount.toString())
                        StatItem("Unique", uniqueCount.toString())
                        StatItem("Sent", sentCount.toString(), Color(0xFF10B981))
                        StatItem("Unsent", unsentCount.toString(), Color(0xFFEF4444))
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Scan in Session (Continue Scan)
                        Button(
                            onClick = {
                                viewModel.resumeBatch(batchId)
                                onNavigateToScanner("normal")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan in Session", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan in Session")
                        }

                        // Export CSV Action
                        OutlinedButton(
                            onClick = { exportCSV() },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Export", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export CSV")
                        }
                    }

                    // Bulk Resend Action
                    if (unsentCount > 0) {
                        Button(
                            onClick = {
                                if (isConnected) {
                                    viewModel.resendUnsentScans(scans)
                                    Toast.makeText(context, "Resending unsent scans…", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Connect to a PC first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isConnected) MaterialTheme.colorScheme.primary else Color.Gray
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Resend", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Resend Unsent (${unsentCount})")
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search codes or formats…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            )

            // Scanned List
            if (filteredScans.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isEmpty()) "No barcodes scanned yet" else "No matching results",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredScans, key = { it.id }) { scan ->
                        ScanRow(
                            scan = scan,
                            isConnected = isConnected,
                            onResendAsync = {
                                viewModel.resendScanAsync(scan.id, scan.data, scan.type)
                            }
                        )
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Session?") },
            text = { Text("This will permanently delete this session and all its ${scans.size} scanned codes. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBatch(batchId)
                        showDeleteConfirm = false
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun StatItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium
        )
    }
}
@Composable
fun ScanRow(
    scan: DbScan,
    isConnected: Boolean,
    onResendAsync: suspend () -> Boolean
) {
    val sdf = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val formattedTime = remember(scan.timestamp) { sdf.format(Date(scan.timestamp)) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var isSending by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed || isSending) 0.92f else 1.0f, label = "scale")

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Compact Status Icon Indicator
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (scan.sent) Color(0xFF10B981).copy(alpha = 0.12f)
                        else Color(0xFFEF4444).copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (scan.sent) Icons.Default.Check else Icons.Default.CloudUpload,
                    contentDescription = if (scan.sent) "Sent" else "Pending",
                    tint = if (scan.sent) Color(0xFF10B981) else Color(0xFFEF4444),
                    modifier = Modifier.size(14.dp)
                )
            }

            // Compact Code & Type details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = scan.data,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    if (scan.count > 1) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(99.dp)
                        ) {
                            Text(
                                text = "×${scan.count}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 1.dp)
                ) {
                    Text(
                        text = scan.type,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        fontWeight = FontWeight.Medium
                    )
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    )
                    Text(
                        text = formattedTime,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
            }

            // Professional, compact Resend button with click & color animations
            Box(
                modifier = Modifier
                    .scale(scale)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSending) MaterialTheme.colorScheme.surfaceVariant
                        else if (isPressed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        enabled = !isSending
                    ) {
                        if (!isConnected) {
                            Toast.makeText(context, "Connect to a PC first", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        coroutineScope.launch {
                            isSending = true
                            val success = onResendAsync()
                            isSending = false
                            if (success) {
                                Toast.makeText(context, "Sent to PC successfully", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to send to PC", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "Resend",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPressed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
