package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.network.QRParser
import com.example.ui.AppViewModel
import com.example.ui.LiveScanItem
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.example.ui.components.AdBanner
import com.example.ui.components.AdConfig

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun ScannerScreen(
    viewModel: AppViewModel,
    mode: String, // "normal", "offline", "connect"
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val connectionOk by viewModel.connectionOk.collectAsStateWithLifecycle()
    val vibrationEnabled by viewModel.vibration.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.sound.collectAsStateWithLifecycle()
    val sendDuplicates by viewModel.sendDuplicates.collectAsStateWithLifecycle()

    val prefix by viewModel.prefix.collectAsStateWithLifecycle()
    val suffix by viewModel.suffix.collectAsStateWithLifecycle()
    val trimStart by viewModel.trimStart.collectAsStateWithLifecycle()
    val trimEnd by viewModel.trimEnd.collectAsStateWithLifecycle()
    val autoSubmit by viewModel.autoSubmit.collectAsStateWithLifecycle()
    val submitChain by viewModel.submitChain.collectAsStateWithLifecycle()
    val scanDebounceMs by viewModel.scanDebounceMs.collectAsStateWithLifecycle()
    val keepAwakeFlow by viewModel.keepAwake.collectAsStateWithLifecycle()

    val window = (context as? android.app.Activity)?.window
    DisposableEffect(keepAwakeFlow) {
        if (keepAwakeFlow) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val totalScans by viewModel.totalScans.collectAsStateWithLifecycle()
    val uniqueScans by viewModel.uniqueScans.collectAsStateWithLifecycle()
    val sentScans by viewModel.sentScans.collectAsStateWithLifecycle()
    val failedScans by viewModel.failedScans.collectAsStateWithLifecycle()
    val liveScans by viewModel.liveScans.collectAsStateWithLifecycle()

    val isOfflineMode = remember { mutableStateOf(mode == "offline") }
    val isConnectMode = remember { mode == "connect" }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    // Camera Permissions
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (!granted) {
                onNavigateBack()
            }
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        if (!isConnectMode) {
            viewModel.startBatch(activeDevice?.name)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!isConnectMode) {
                viewModel.endBatch()
            }
        }
    }

    // Vibration & Sound Player helper
    val triggerFeedback = {
        if (vibrationEnabled) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }
        if (soundEnabled) {
            try {
                val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            } catch (e: Exception) {
                Log.e("ScannerScreen", "ToneGenerator failed", e)
            }
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1220)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    // Scan tracking variables
    var lastScannedData by remember { mutableStateOf("") }
    var lastScannedTime by remember { mutableStateOf(0L) }
    var isProcessingBarcode by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Camera Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                        .build()
                    val scanner = BarcodeScanning.getClient(options)

                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !isProcessingBarcode) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    val barcode = barcodes.firstOrNull()
                                    if (barcode != null) {
                                        val rawValue = barcode.rawValue ?: ""
                                        val now = System.currentTimeMillis()
                                        
                                        // Debounce scan using scanDebounceMs
                                        if (rawValue != lastScannedData || (now - lastScannedTime) > scanDebounceMs) {
                                            lastScannedData = rawValue
                                            lastScannedTime = now
                                            
                                            // Trigger callback
                                            coroutineScope.launch {
                                                isProcessingBarcode = true
                                                triggerFeedback()
                                                
                                                if (isConnectMode) {
                                                    val parsedDevice = QRParser.parseConnectionQR(rawValue)
                                                    if (parsedDevice != null) {
                                                        viewModel.connectTo(parsedDevice)
                                                        onNavigateBack()
                                                    }
                                                } else {
                                                    // Process trimming from start and end
                                                    val processedValue = run {
                                                        var s = rawValue
                                                        if (trimStart > 0 && s.length >= trimStart) {
                                                            s = s.substring(trimStart)
                                                        }
                                                        if (trimEnd > 0 && s.length >= trimEnd) {
                                                            s = s.substring(0, s.length - trimEnd)
                                                        }
                                                        s
                                                    }

                                                    // Add scan to local batch
                                                    val addResult = viewModel.addScan(processedValue, getFormatName(barcode.format))
                                                    if (addResult != null) {
                                                        val record = addResult.scan
                                                        val isDup = addResult.isDuplicate
                                                        
                                                        val tempId = record.id + "-" + System.currentTimeMillis()
                                                        val liveItem = LiveScanItem(
                                                            id = tempId,
                                                            data = record.data,
                                                            type = record.type,
                                                            ok = null, // sending
                                                            duplicate = isDup
                                                        )
                                                        viewModel.addToLiveScans(liveItem)

                                                        if (isOfflineMode.value || activeDevice == null) {
                                                            // Local only
                                                            viewModel.updateScanSent(record.id, false)
                                                            viewModel.updateLiveScanStatus(tempId, false)
                                                        } else {
                                                            // Send to PC
                                                            if (isDup && !sendDuplicates) {
                                                                // Skip duplicate, visually complete
                                                                viewModel.updateLiveScanStatus(tempId, true)
                                                            } else {
                                                                val active = activeDevice
                                                                if (active != null) {
                                                                    val prefChain = submitChain.split(",")
                                                                        .map { it.split(":") }
                                                                        .filter { it.size == 2 && it[1] == "true" }
                                                                        .map { it[0] }

                                                                    val success = com.example.network.NetworkService.sendScan(
                                                                        host = active.host,
                                                                        port = active.port,
                                                                        data = prefix + record.data + suffix,
                                                                        rawData = record.data,
                                                                        type = record.type,
                                                                        chain = prefChain,
                                                                        autoSubmit = autoSubmit
                                                                    )
                                                                    viewModel.updateScanSent(record.id, success)
                                                                    viewModel.updateLiveScanStatus(tempId, success)
                                                                } else {
                                                                    viewModel.updateScanSent(record.id, false)
                                                                    viewModel.updateLiveScanStatus(tempId, false)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                delay(800) // Delay before next scan starts processing
                                                isProcessingBarcode = false
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener {
                                    Log.e("ScannerScreen", "ML Kit processing failed", it)
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                        cameraControl = camera.cameraControl
                    } catch (e: Exception) {
                        Log.e("ScannerScreen", "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // Top HUD Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Button
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                // Title/Status badge
                val statusText = if (isConnectMode) {
                    "Scan Connection QR"
                } else if (isOfflineMode.value) {
                    "Offline Mode (Local-Only)"
                } else {
                    activeDevice?.let {
                        if (connectionOk == true) "Connected to ${it.name}" else "Reconnecting to ${it.name}…"
                    } ?: "Disconnected"
                }
                
                Surface(
                    color = if (isOfflineMode.value) Color(0xFFEF4444).copy(alpha = 0.85f)
                            else if (isConnectMode) Color(0xFF2563EB).copy(alpha = 0.85f)
                            else if (activeDevice != null && connectionOk == true) Color(0xFF10B981).copy(alpha = 0.85f)
                            else Color.DarkGray.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Text(
                            text = statusText,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Right HUD Action toggles
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Offline Mode switch
                    if (!isConnectMode) {
                        IconButton(
                            onClick = { isOfflineMode.value = !isOfflineMode.value },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isOfflineMode.value) Color(0xFFEF4444).copy(alpha = 0.8f)
                                    else Color.Black.copy(alpha = 0.5f)
                                )
                        ) {
                            Icon(
                                imageVector = if (isOfflineMode.value) Icons.Default.WifiOff else Icons.Default.Wifi,
                                contentDescription = "Toggle Offline",
                                tint = Color.White
                            )
                        }
                    }

                    // Flashlight toggle
                    IconButton(
                        onClick = {
                            isFlashOn = !isFlashOn
                            cameraControl?.enableTorch(isFlashOn)
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = if (isFlashOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                            contentDescription = "Flashlight",
                            tint = if (isFlashOn) Color(0xFFFACC15) else Color.White
                        )
                    }
                }
            }
        }

        // Live Counters Overlay + compact Resend buttons
        if (!isConnectMode) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 110.dp)
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Counter bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xE60F172A), RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    CounterStat("Total",   totalScans,  Color.White)
                    CounterStat("Unique",  uniqueScans, Color(0xFF93C5FD))
                    CounterStat("Sent",    sentScans,   Color(0xFF86EFAC))
                    CounterStat("Pending", failedScans, Color(0xFFFCA5A5))
                }

                // ── Compact Resend buttons (right-aligned, below counter) ──
                Row(
                    modifier = Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { viewModel.resendLastScan() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(99.dp))
                    ) {
                        Text(
                            text = "↺ Last",
                            color = Color(0xFF93C5FD),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = { viewModel.resendAllUnsentInCurrentBatch() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(26.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(99.dp))
                    ) {
                        Text(
                            text = "↺ All",
                            color = Color(0xFFFCA5A5),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Camera Aim Focus Frame (Center)
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.Center)
                .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
        ) {
            // Corner Reticles
            val cornerColor = Color(0xFF3B82F6)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(24.dp)
                    .border(
                        width = 4.dp,
                        color = cornerColor,
                        shape = RoundedCornerShape(topStart = 8.dp)
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .border(
                        width = 4.dp,
                        color = cornerColor,
                        shape = RoundedCornerShape(topEnd = 8.dp)
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(24.dp)
                    .border(
                        width = 4.dp,
                        color = cornerColor,
                        shape = RoundedCornerShape(bottomStart = 8.dp)
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .border(
                        width = 4.dp,
                        color = cornerColor,
                        shape = RoundedCornerShape(bottomEnd = 8.dp)
                    )
            )
        }

        // Bottom Hints
        Text(
            text = if (isConnectMode) "Point at the QR code shown by your PC server"
                   else if (isOfflineMode.value) "Offline Mode — scans saved locally, not sent"
                   else "Align the barcode inside the frame",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 220.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(99.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        )

        // Live list of scans overlay at bottom
        if (!isConnectMode && liveScans.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .padding(horizontal = 18.dp)
                    .height(160.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                reverseLayout = false
            ) {
                items(liveScans, key = { it.id }) { item ->
                    Column {
                        AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when (item.ok) {
                                        true -> Color(0xEB10B981)
                                        false -> Color(0xEBEF4444)
                                        else -> Color(0xEB0F172A)
                                    },
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when (item.ok) {
                                true -> Icon(Icons.Default.CheckCircle, "Sent", tint = Color.White, modifier = Modifier.size(16.dp))
                                false -> Icon(Icons.Default.Error, "Failed", tint = Color.White, modifier = Modifier.size(16.dp))
                                else -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                text = item.data,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (item.duplicate) {
                                Surface(
                                    color = Color.White.copy(alpha = 0.22f),
                                    shape = RoundedCornerShape(99.dp)
                                ) {
                                    Text(
                                        text = "dup",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }

        // Reconnecting modal overlay
        val isReconnecting = activeDevice != null && connectionOk == false && !isOfflineMode.value
        if (isReconnecting) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFF3B82F6))
                            Text(
                                text = "Reconnecting…",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Trying to reach ${activeDevice?.name ?: "PC Server"}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Scans are being saved and will be sent when reconnected.",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onNavigateBack,
                                border = ButtonDefaults.outlinedButtonBorder(true).copy(),
                                shape = RoundedCornerShape(99.dp)
                            ) {
                                Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }
    }
        AdBanner(
            adUnitId = AdConfig.scannerBannerId,
            modifier = Modifier.navigationBarsPadding()
        )
    }
}

@Composable
fun RowScope.CounterStat(label: String, value: Int, color: Color) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value.toString(),
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label.uppercase(Locale.getDefault()),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
    }
}

private fun getFormatName(format: Int): String {
    return when (format) {
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        Barcode.FORMAT_CODABAR -> "CODABAR"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        Barcode.FORMAT_CODE_93 -> "CODE_93"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_EAN_8 -> "EAN_8"
        Barcode.FORMAT_EAN_13 -> "EAN_13"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_UPC_A -> "UPC_A"
        Barcode.FORMAT_UPC_E -> "UPC_E"
        else -> "UNKNOWN"
    }
}
