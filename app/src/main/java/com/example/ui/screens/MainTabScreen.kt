package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DbBatch
import com.example.data.DbDevice
import com.example.ui.AppViewModel
import com.example.ui.components.AdBanner
import com.example.ui.components.AdConfig
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MainTabScreen(
    viewModel: AppViewModel,
    onNavigateToScanner: (mode: String) -> Unit,
    onNavigateToBatchDetails: (batchId: String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("Sessions") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> HomeTab(viewModel, onNavigateToScanner)
                    1 -> HistoryTab(viewModel, onNavigateToBatchDetails)
                    2 -> SettingsTab(viewModel, onNavigateToScanner)
                }
            }

            // Bottom-anchored banner ads corresponding to the selected tab
            when (selectedTab) {
                0 -> AdBanner(adUnitId = AdConfig.homeBannerId)
                1 -> AdBanner(adUnitId = AdConfig.historyNativeId)
                2 -> { /* No ads on Settings tab to keep it clean */ }
            }
        }
    }
}

// ==================== HOME TAB ====================
@Composable
fun HomeTab(
    viewModel: AppViewModel,
    onNavigateToScanner: (mode: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val connectionOk by viewModel.connectionOk.collectAsStateWithLifecycle()
    val knownDevices by viewModel.knownDevices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val scanProgressScanned by viewModel.scanProgressScanned.collectAsStateWithLifecycle()
    val scanProgressTotal by viewModel.scanProgressTotal.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()

    var showDeviceMenu by remember { mutableStateOf<DbDevice?>(null) }
    var renameTargetDevice by remember { mutableStateOf<DbDevice?>(null) }
    var deviceNewName by remember { mutableStateOf("") }

    var selectedFileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }
    var parsedCodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var showImportDialog by remember { mutableStateOf(false) }
    var isImportSending by remember { mutableStateOf(false) }
    var importSentCount by remember { mutableStateOf(0) }
    var importTotalCount by remember { mutableStateOf(0) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var importSuccessCount by remember { mutableStateOf(0) }
    var importFailedCount by remember { mutableStateOf(0) }
    var showFinishedDialog by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }

    var showNoDevicesInstructionsDialog by remember { mutableStateOf(false) }
    var hasScannedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(scanning) {
        if (scanning) {
            hasScannedOnce = true
        } else if (hasScannedOnce) {
            if (discoveredDevices.isEmpty()) {
                showNoDevicesInstructionsDialog = true
            }
            hasScannedOnce = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedFileUri = uri
            var fileName = "imported_file"
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                    cursor.close()
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeTab", "Error getting file name", e)
            }
            selectedFileName = fileName
            
            viewModel.loadCodesFromFile(context, uri) { codes ->
                parsedCodes = codes
                if (codes.isNotEmpty()) {
                    showImportDialog = true
                } else {
                    Toast.makeText(context, "No codes found in this file. Please make sure it's a valid CSV, TXT or JSON file.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp)
    ) {
        // App Welcome Info & Status Card
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Welcome Back",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Network Scan Sender",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
            }
        }

        // Active Connection Status Card
        item {
            val statusColor = if (activeDevice == null) Color(0xFFEF4444) 
                              else if (connectionOk == true) Color(0xFF10B981) 
                              else Color(0xFFF59E0B)
            
            // Pulsing animation for outer ring / connection status
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulseScale"
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulseAlpha"
            )
            val glowAlpha by infiniteTransition.animateFloat(
                initialValue = 0.06f,
                targetValue = 0.16f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glowAlpha"
            )

            val statusBg = if (connectionOk == true) statusColor.copy(alpha = glowAlpha) else statusColor.copy(alpha = 0.08f)

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = statusBg),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (connectionOk == true) statusColor.copy(alpha = 0.3f) else statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .then(
                        if (activeDevice == null || connectionOk != true) {
                            Modifier.clickable { onNavigateToScanner("connect") }
                        } else {
                            Modifier
                        }
                    )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier.size(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer pulsing circle
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = pulseAlpha))
                        )
                        // Inner solid dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (activeDevice == null) "Not Connected"
                                   else if (connectionOk == true) "Connected to PC"
                                   else "Reconnecting to PC…",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = activeDevice?.let { 
                                if (connectionOk == true) "${it.name} (${it.host}:${it.port})" 
                                else "Auto-reconnecting to ${it.name}…"
                            } ?: "Tap here to connect to PC server",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                        )
                    }
                    if (activeDevice != null) {
                        IconButton(onClick = { viewModel.connectTo(null) }) {
                            Icon(Icons.Default.LinkOff, "Disconnect", tint = Color(0xFFEF4444))
                        }
                    }
                }
            }
        }

        // LARGE ACTION CARDS (Polished Modern Redesign)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Primary Large "Open Scanner" Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(116.dp)
                        .clickable { onNavigateToScanner("normal") },
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF2563EB), Color(0xFF4F46E5))
                                )
                            )
                            .padding(20.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Large Circular Scanner Icon
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Scanner Icon",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            
                            // Text block
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Camera Scanner",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.3).sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Scan codes to your PC in real-time",
                                    color = Color.White.copy(alpha = 0.82f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            
                            // Chevron/Arrow Icon
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "Go",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Two Side-by-Side Secondary Action Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Left Card: Offline Local Scan
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(112.dp)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                            .clickable { onNavigateToScanner("offline") }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = "Offline Mode",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Offline Scan",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Save on device",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // Right Card: Send File
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(112.dp)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                            .clickable {
                                if (activeDevice == null) {
                                    Toast.makeText(context, "Please connect to a PC server first!", Toast.LENGTH_SHORT).show()
                                } else {
                                    filePickerLauncher.launch("*/*")
                                }
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = "Send File",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Send File",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "CSV, TXT, Excel",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Subnet Autodiscovery section
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "PC Auto-Discovery",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val subnet = com.example.network.NetworkService.getLocalSubnetBase()
                            Text(
                                text = if (subnet != null) "Searching subnet $subnet.1-254" else "No active Wi-Fi connection",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        
                        Button(
                            onClick = { viewModel.scanNetwork() },
                            enabled = !scanning,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            if (scanning) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Wifi, contentDescription = "Scan", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan")
                            }
                        }
                    }

                    // Progress indicators
                    if (scanning) {
                        val progress = scanProgressScanned.toFloat() / scanProgressTotal.toFloat()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(99.dp))
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Scanning network…",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = "$scanProgressScanned / $scanProgressTotal",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Discovered list
                    if (discoveredDevices.isNotEmpty()) {
                        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        Text(
                            text = "FOUND ON NETWORK:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        discoveredDevices.forEach { device ->
                            DeviceRow(
                                device = device,
                                isDiscovered = true,
                                onConnect = { viewModel.connectTo(device) }
                            )
                        }
                    } else if (scanning && discoveredDevices.isEmpty()) {
                        Text(
                            text = "Searching for responding servers on port 5000…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }

        // Saved / Known Devices List
        item {
            Text(
                text = "SAVED PC CONNECTIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (knownDevices.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No saved PCs. Scan your subnet or enter IP manually in settings.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(knownDevices, key = { it.host }) { device ->
                DeviceRow(
                    device = device,
                    isDiscovered = false,
                    onConnect = { viewModel.connectTo(device) },
                    onMore = { showDeviceMenu = device }
                )
            }
        }
    }

    // Context Actions Menu for Saved Devices
    showDeviceMenu?.let { device ->
        AlertDialog(
            onDismissRequest = { showDeviceMenu = null },
            title = { Text(device.name, fontWeight = FontWeight.Bold) },
            text = { Text("What would you like to do with this saved connection?") },
            confirmButton = {
                TextButton(onClick = {
                    renameTargetDevice = device
                    deviceNewName = device.name
                    showDeviceMenu = null
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDevice(device.host)
                        showDeviceMenu = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Delete")
                }
            }
        )
    }

    // Rename dialog
    renameTargetDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { renameTargetDevice = null },
            title = { Text("Rename PC Connection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a friendly nickname for this server:")
                    OutlinedTextField(
                        value = deviceNewName,
                        onValueChange = { deviceNewName = it },
                        singleLine = true,
                        placeholder = { Text(device.host) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (deviceNewName.isNotBlank()) {
                        viewModel.renameDevice(device.host, deviceNewName.trim())
                    }
                    renameTargetDevice = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetDevice = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 1. File Import Confirmation Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.InsertDriveFile, "File", tint = MaterialTheme.colorScheme.primary)
                    Text("Ready to Send File", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "File: $selectedFileName",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${parsedCodes.size} codes detected inside the file.",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "These codes will be sent sequentially to your connected PC: ${activeDevice?.name}.\n\nYou can cancel or pause the process at any time during sending.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImportDialog = false
                        importTotalCount = parsedCodes.size
                        importSentCount = 0
                        importSuccessCount = 0
                        importFailedCount = 0
                        showProgressDialog = true
                        
                        importJob = viewModel.sendBulkCodes(
                            fileName = selectedFileName,
                            codes = parsedCodes,
                            onProgress = { sent, total ->
                                importSentCount = sent
                            },
                            onFinished = { success, failed ->
                                importSuccessCount = success
                                importFailedCount = failed
                                showProgressDialog = false
                                showFinishedDialog = true
                            }
                        )
                    },
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Text("Send to PC")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 2. Bulk Sending Progress Dialog
    if (showProgressDialog) {
        AlertDialog(
            onDismissRequest = { /* Force user to use Cancel button to abort */ },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text("Sending codes…", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                ) {
                    val progressVal = if (importTotalCount > 0) importSentCount.toFloat() / importTotalCount.toFloat() else 0f
                    LinearProgressIndicator(
                        progress = { progressVal },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Sending $importSentCount of $importTotalCount",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(progressVal * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Current PC Server: ${activeDevice?.name ?: "N/A"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importJob?.cancel()
                        showProgressDialog = false
                        Toast.makeText(context, "File transfer stopped.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Stop", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 3. Bulk Sending Complete Dialog
    if (showFinishedDialog) {
        AlertDialog(
            onDismissRequest = { showFinishedDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, "Success", tint = Color(0xFF10B981))
                    Text("Transfer Finished", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "All codes processed from $selectedFileName.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.08f)),
                            modifier = Modifier.weight(1f).border(1.dp, Color(0xFF10B981).copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$importSuccessCount", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                                Text("SUCCESS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981).copy(alpha = 0.7f))
                            }
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$importFailedCount", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                Text("FAILED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                            }
                        }
                    }
                    Text(
                        text = "A new history session 'Import: $selectedFileName' has been created with all the imported codes.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showFinishedDialog = false },
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Text("OK")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showNoDevicesInstructionsDialog) {
        AlertDialog(
            onDismissRequest = { showNoDevicesInstructionsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = "PC Connection Guide",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "PC Connection Guide",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "No PC servers were found on your local network. To connect your phone and stream barcode scans, follow these instructions:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        StepItem(number = "1", text = "Download the companion PC Server app from the Settings tab.")
                        StepItem(number = "2", text = "Extract the ZIP and run the server application on your computer.")
                        StepItem(number = "3", text = "Make sure both your Phone and PC are connected to the SAME Wi-Fi network.")
                        StepItem(number = "4", text = "Scan the QR code displayed on your PC screen to pair instantly.")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showNoDevicesInstructionsDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Got It")
                }
            }
        )
    }
}

@Composable
fun StepItem(number: String, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            lineHeight = 16.sp
        )
    }
}

@Composable
fun DeviceRow(
    device: DbDevice,
    isDiscovered: Boolean,
    onConnect: () -> Unit,
    onMore: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect() }
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Computer,
                    contentDescription = "PC",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${device.host}:${device.port}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            if (isDiscovered) {
                Text(
                    text = "PAIR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else if (onMore != null) {
                IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


// ==================== HISTORY / SESSIONS TAB ====================
@Composable
fun HistoryTab(
    viewModel: AppViewModel,
    onNavigateToBatchDetails: (batchId: String) -> Unit
) {
    val allBatches by viewModel.allBatches.collectAsStateWithLifecycle()
    val allScans by viewModel.allScans.collectAsStateWithLifecycle()

    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Scan History",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Sessions",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                }

                if (allBatches.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444).copy(alpha = 0.1f))
                    ) {
                        Icon(Icons.Default.DeleteSweep, "Clear History", tint = Color(0xFFEF4444))
                    }
                }
            }
        }

        if (allBatches.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Empty",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                            modifier = Modifier.size(52.dp)
                        )
                        Text(
                            text = "No scanning sessions found.",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Open the scanner to create your first session.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(allBatches, key = { it.id }) { batch ->
                val batchScans = remember(allScans, batch.id) {
                    allScans.filter { it.batchId == batch.id }
                }
                val scanCount = remember(batchScans) { batchScans.sumOf { it.count } }

                BatchRow(
                    batch = batch,
                    scanCount = scanCount,
                    onClick = { onNavigateToBatchDetails(batch.id) }
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Wipe All History?") },
            text = { Text("Are you sure you want to permanently delete all scan sessions and their saved barcodes? This action cannot be reversed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BatchRow(
    batch: DbBatch,
    scanCount: Int,
    onClick: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    val formattedDate = remember(batch.startedAt) { sdf.format(Date(batch.startedAt)) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Session",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = batch.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formattedDate,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
                if (!batch.device.isNullOrEmpty()) {
                    Text(
                        text = "PC: ${batch.device}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(99.dp)
            ) {
                Text(
                    text = "$scanCount codes",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}


// ==================== SETTINGS / PREFERENCES TAB ====================
private data class ChainItem(val id: String, val label: String, val enabled: Boolean)

@Composable
fun SettingsToggleRowInline(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.9f)
        )
    }
}

@Composable
fun TrimStepperInline(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Trim characters from scanned code", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(
                onClick = { if (value > 0) onValueChange(value - 1) },
                modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            ) {
                Icon(Icons.Default.Remove, "Decrease", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                text = value.toString(),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(min = 18.dp),
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = { onValueChange(value + 1) },
                modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            ) {
                Icon(Icons.Default.Add, "Increase", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
fun SettingsTab(
    viewModel: AppViewModel,
    onNavigateToScanner: (mode: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val themePref by viewModel.theme.collectAsStateWithLifecycle()
    val vibrationEnabled by viewModel.vibration.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.sound.collectAsStateWithLifecycle()
    val sendDuplicates by viewModel.sendDuplicates.collectAsStateWithLifecycle()
    val manualHost by viewModel.manualHost.collectAsStateWithLifecycle()
    val manualPort by viewModel.manualPort.collectAsStateWithLifecycle()

    val prefix by viewModel.prefix.collectAsStateWithLifecycle()
    val suffix by viewModel.suffix.collectAsStateWithLifecycle()
    val trimStart by viewModel.trimStart.collectAsStateWithLifecycle()
    val trimEnd by viewModel.trimEnd.collectAsStateWithLifecycle()
    val autoSubmit by viewModel.autoSubmit.collectAsStateWithLifecycle()
    val submitChain by viewModel.submitChain.collectAsStateWithLifecycle()
    val scanDebounceMs by viewModel.scanDebounceMs.collectAsStateWithLifecycle()
    val resendDelayMs by viewModel.resendDelayMs.collectAsStateWithLifecycle()
    val keepAwakeFlow by viewModel.keepAwake.collectAsStateWithLifecycle()
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()

    var manualIPInput by remember { mutableStateOf(manualHost) }
    var manualPortInput by remember { mutableStateOf(manualPort.toString()) }
    var testingConnection by remember { mutableStateOf(false) }

    var debounceInput by remember(scanDebounceMs) { mutableStateOf(scanDebounceMs.toString()) }
    var resendDelayInput by remember(resendDelayMs) { mutableStateOf(resendDelayMs.toString()) }
    var prefixInput by remember(prefix) { mutableStateOf(prefix) }
    var suffixInput by remember(suffix) { mutableStateOf(suffix) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Preferences",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Settings",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
            }
        }

        // Connection Status Indicator
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (activeDevice != null) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (activeDevice != null) Color(0xFF4CAF50) else Color(0xFFF44336))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (activeDevice != null) "Connected to ${activeDevice?.name}" else "Not connected to PC",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (activeDevice != null) {
                            Text(
                                text = "IP: ${activeDevice?.host}:${activeDevice?.port}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        } else {
                            Text(
                                text = "Scan connection QR or enter manual IP below",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                    if (activeDevice != null) {
                        TextButton(
                            onClick = { viewModel.connectTo(null) }
                        ) {
                            Text("Disconnect", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Quick Setup QR Scanner Section
        item {
            SettingsCardSection("Quick Connect") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToScanner("connect") }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.QrCode, "QR", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Scan to Connect", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Scan connection QR code on your PC to pair instantly", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(99.dp)
                    ) {
                        Text(
                            text = "OPEN",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Manual PC Setup Section
        item {
            SettingsCardSection("Manual Connection") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Connect by IP address if subnet auto-discovery doesn't find your PC.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = manualIPInput,
                            onValueChange = { manualIPInput = it },
                            label = { Text("IP Address") },
                            placeholder = { Text("192.168.1.42") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(2f)
                        )

                        OutlinedTextField(
                            value = manualPortInput,
                            onValueChange = { manualPortInput = it },
                            label = { Text("Port") },
                            placeholder = { Text("5000") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = {
                            val ip = manualIPInput.trim()
                            val port = manualPortInput.toIntOrNull() ?: 5000
                            if (ip.isEmpty() || port <= 0 || port > 65535) {
                                Toast.makeText(context, "Please enter a valid IP and port", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            testingConnection = true
                            viewModel.updateManualConnection(ip, port)
                            
                            coroutineScope.launch {
                                val testedDevice = com.example.network.NetworkService.pingDevice(ip, port)
                                testingConnection = false
                                if (testedDevice != null) {
                                    viewModel.connectTo(testedDevice)
                                    Toast.makeText(context, "Connected to manual PC successfully!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not reach PC server at $ip:$port", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !testingConnection,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (testingConnection) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Link, "Link", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect")
                        }
                    }
                }
            }
        }

        // Appearance Section
        item {
            SettingsCardSection("Appearance") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Choose theme preference. System follows your phone's dark mode theme.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeSelectorItem("system", "System", Icons.Default.SettingsSuggest, themePref == "system") {
                            viewModel.updateTheme("system")
                        }
                        ThemeSelectorItem("light", "Light", Icons.Default.LightMode, themePref == "light") {
                            viewModel.updateTheme("light")
                        }
                        ThemeSelectorItem("dark", "Dark", Icons.Default.DarkMode, themePref == "dark") {
                            viewModel.updateTheme("dark")
                        }
                    }
                }
            }
        }

        // Tactile / Audible Feedback Section
        item {
            SettingsCardSection("Feedback") {
                Column {
                    SettingsToggleRow(
                        icon = Icons.Default.Vibration,
                        title = "Vibration",
                        subtitle = "Haptic feedback on each successful scan",
                        checked = vibrationEnabled,
                        onCheckedChange = { viewModel.updateVibration(it) }
                    )
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    SettingsToggleRow(
                        icon = Icons.Default.VolumeUp,
                        title = "Beep Sound",
                        subtitle = "Audible chime on each successful scan",
                        checked = soundEnabled,
                        onCheckedChange = { viewModel.updateSound(it) }
                    )
                }
            }
        }

        // Scan Behavior Section
        item {
            SettingsCardSection("Scan Behavior") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Send duplicates to PC
                    SettingsToggleRowInline(
                        title = "Send duplicates to PC",
                        subtitle = "When disabled, repeat scans of the same code stay on device only.",
                        checked = sendDuplicates,
                        onCheckedChange = { viewModel.updateSendDuplicates(it) }
                    )
                    
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // Keep screen awake
                    SettingsToggleRowInline(
                        title = "Keep screen awake",
                        subtitle = "Prevent device screen from sleeping while scanner is open.",
                        checked = keepAwakeFlow,
                        onCheckedChange = { viewModel.updateKeepAwake(it) }
                    )
                    
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // Scan speed (debounce)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Scan speed (debounce)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Minimum duration between consecutive scans", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            OutlinedTextField(
                                value = debounceInput,
                                onValueChange = {
                                    debounceInput = it
                                    val parsed = it.toIntOrNull()
                                    if (parsed != null && parsed >= 0) {
                                        viewModel.updateScanDebounceMs(parsed)
                                    }
                                },
                                singleLine = true,
                                suffix = { Text("ms", fontSize = 11.sp) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.width(110.dp).height(52.dp),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                        
                        val speedPresets = listOf(
                            "300ms" to 300,
                            "500ms" to 500,
                            "800ms" to 800,
                            "1.00s" to 1000,
                            "1.50s" to 1500,
                            "2.00s" to 2000,
                            "3.00s" to 3000
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            speedPresets.forEach { (label, value) ->
                                val isSelected = scanDebounceMs == value
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.updateScanDebounceMs(value) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // Send delay
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Send delay", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Interval delay when resending barcodes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                            OutlinedTextField(
                                value = resendDelayInput,
                                onValueChange = {
                                    resendDelayInput = it
                                    val parsed = it.toIntOrNull()
                                    if (parsed != null && parsed >= 0) {
                                        viewModel.updateResendDelayMs(parsed)
                                    }
                                },
                                singleLine = true,
                                suffix = { Text("ms", fontSize = 11.sp) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.width(110.dp).height(52.dp),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                        
                        val delayPresets = listOf(
                            "Off" to 0,
                            "100ms" to 100,
                            "200ms" to 200,
                            "500ms" to 500,
                            "1.00s" to 1000,
                            "2.00s" to 2000,
                            "5.00s" to 5000
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            delayPresets.forEach { (label, value) ->
                                val isSelected = resendDelayMs == value
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.updateResendDelayMs(value) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Input Behavior Section
        item {
            SettingsCardSection("Input Behavior") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Auto-submit
                    SettingsToggleRowInline(
                        title = "Auto-submit",
                        subtitle = "Execute automated post-actions like Enter/Tab on the server side",
                        checked = autoSubmit,
                        onCheckedChange = { viewModel.updateAutoSubmit(it) }
                    )
                    
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // Prefix
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Prefix", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Text prepended to every scan", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                        OutlinedTextField(
                            value = prefixInput,
                            onValueChange = {
                                prefixInput = it
                                viewModel.updatePrefix(it)
                            },
                            placeholder = { Text("e.g. [SCAN]") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.width(140.dp).height(52.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // Suffix
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Suffix", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Text appended to every scan", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                        OutlinedTextField(
                            value = suffixInput,
                            onValueChange = {
                                suffixInput = it
                                viewModel.updateSuffix(it)
                            },
                            placeholder = { Text("e.g. \\n") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.width(140.dp).height(52.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // Trim from Start Stepper
                    TrimStepperInline(
                        label = "Trim from Start",
                        value = trimStart,
                        onValueChange = { viewModel.updateTrimStart(it) }
                    )

                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // Trim from End Stepper
                    TrimStepperInline(
                        label = "Trim from End",
                        value = trimEnd,
                        onValueChange = { viewModel.updateTrimEnd(it) }
                    )

                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // Action Chain Reorderable list
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column {
                            Text("Action Chain", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Drag/Reorder actions and toggle active post-submit actions", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                        
                        val chainItems = remember(submitChain) {
                            submitChain.split(",")
                                .mapNotNull {
                                    val parts = it.split(":")
                                    if (parts.size == 2) {
                                        val id = parts[0]
                                        val enabled = parts[1].toBoolean()
                                        val label = when(id) {
                                            "paste" -> "Paste barcode"
                                            "enter" -> "Submit enter"
                                            "tab" -> "Submit tab"
                                            else -> id.replaceFirstChar { c -> c.uppercase() }
                                        }
                                        ChainItem(id, label, enabled)
                                    } else null
                                }
                        }
                        
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            chainItems.forEachIndexed { index, item ->
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    if (index > 0) {
                                                        val mutable = chainItems.toMutableList()
                                                        val temp = mutable[index]
                                                        mutable[index] = mutable[index - 1]
                                                        mutable[index - 1] = temp
                                                        viewModel.updateSubmitChain(mutable.joinToString(",") { "${it.id}:${it.enabled}" })
                                                    }
                                                },
                                                enabled = index > 0,
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ArrowUpward,
                                                    "Up",
                                                    tint = if (index > 0) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    if (index < chainItems.size - 1) {
                                                        val mutable = chainItems.toMutableList()
                                                        val temp = mutable[index]
                                                        mutable[index] = mutable[index + 1]
                                                        mutable[index + 1] = temp
                                                        viewModel.updateSubmitChain(mutable.joinToString(",") { "${it.id}:${it.enabled}" })
                                                    }
                                                },
                                                enabled = index < chainItems.size - 1,
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ArrowDownward,
                                                    "Down",
                                                    tint = if (index < chainItems.size - 1) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                            Text(
                                                text = item.label,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(start = 4.dp)
                                            )
                                        }
                                        Switch(
                                            checked = item.enabled,
                                            onCheckedChange = { isChecked ->
                                                val mutable = chainItems.toMutableList()
                                                mutable[index] = item.copy(enabled = isChecked)
                                                viewModel.updateSubmitChain(mutable.joinToString(",") { "${it.id}:${it.enabled}" })
                                            },
                                            modifier = Modifier.scale(0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // About / Explanations section
        item {
            SettingsCardSection("About & Support") {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.Info, "Info", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Run the desktop server on your computer on port 5000. Each barcode scan will be sent instantly as a JSON payload via POST to /scan.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            lineHeight = 18.sp
                        )
                    }
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.HelpCenter, "Help", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Network auto-discovery pings all local network addresses on your current WiFi connection to automatically locate responding PC servers.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        // Download PC Server section (Beautified & in English)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Download Server",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Setup PC Companion Server",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Instantly type barcode scans directly into your PC",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Setup steps list
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        StepItem(number = "1", text = "Download and extract the PC Server ZIP on your computer.")
                        StepItem(number = "2", text = "Open and run the server companion application on your PC.")
                        StepItem(number = "3", text = "Ensure both your Phone and PC are on the same Wi-Fi network.")
                        StepItem(number = "4", text = "Scan the QR code shown on the PC screen to pair instantly.")
                    }

                    Button(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://litter.catbox.moe/dhsylh.zip")
                            )
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Download PC Server",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download PC Server (ZIP)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.ThemeSelectorItem(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onSelect: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveBg = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
    
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) activeColor else inactiveBg
        ),
        modifier = Modifier
            .weight(1f)
            .clickable { onSelect() }
            .border(
                width = 1.dp,
                color = if (active) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SettingsToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, title, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsCardSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(Locale.getDefault()),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp)),
            content = { content() }
        )
    }
}
