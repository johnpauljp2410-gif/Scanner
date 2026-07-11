package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.network.NetworkService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

data class LiveScanItem(
    val id: String,
    val data: String,
    val type: String,
    val ok: Boolean?, // null = sending, true = sent, false = failed
    val duplicate: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class AddScanResult(
    val scan: DbScan,
    val isDuplicate: Boolean
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "AppViewModel"
    private val database = AppDatabase.getDatabase(application)
    private val dao = database.scanDao()
    private val prefs = PreferencesManager(application)

    // Expose settings from PreferencesManager
    private val _theme = MutableStateFlow(prefs.theme)
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _vibration = MutableStateFlow(prefs.vibration)
    val vibration: StateFlow<Boolean> = _vibration.asStateFlow()

    private val _sound = MutableStateFlow(prefs.sound)
    val sound: StateFlow<Boolean> = _sound.asStateFlow()

    private val _sendDuplicates = MutableStateFlow(prefs.sendDuplicates)
    val sendDuplicates: StateFlow<Boolean> = _sendDuplicates.asStateFlow()

    private val _keepAwake = MutableStateFlow(prefs.keepAwake)
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    private val _scanDebounceMs = MutableStateFlow(prefs.scanDebounceMs)
    val scanDebounceMs: StateFlow<Int> = _scanDebounceMs.asStateFlow()

    private val _resendDelayMs = MutableStateFlow(prefs.resendDelayMs)
    val resendDelayMs: StateFlow<Int> = _resendDelayMs.asStateFlow()

    private val _autoSubmit = MutableStateFlow(prefs.autoSubmit)
    val autoSubmit: StateFlow<Boolean> = _autoSubmit.asStateFlow()

    private val _prefix = MutableStateFlow(prefs.prefix)
    val prefix: StateFlow<String> = _prefix.asStateFlow()

    private val _suffix = MutableStateFlow(prefs.suffix)
    val suffix: StateFlow<String> = _suffix.asStateFlow()

    private val _trimStart = MutableStateFlow(prefs.trimStart)
    val trimStart: StateFlow<Int> = _trimStart.asStateFlow()

    private val _trimEnd = MutableStateFlow(prefs.trimEnd)
    val trimEnd: StateFlow<Int> = _trimEnd.asStateFlow()

    private val _submitChain = MutableStateFlow(prefs.submitChain)
    val submitChain: StateFlow<String> = _submitChain.asStateFlow()

    private val _manualHost = MutableStateFlow(prefs.manualHost)
    val manualHost: StateFlow<String> = _manualHost.asStateFlow()

    private val _manualPort = MutableStateFlow(prefs.manualPort)
    val manualPort: StateFlow<Int> = _manualPort.asStateFlow()

    private val _activeDevice = MutableStateFlow<DbDevice?>(null)
    val activeDevice: StateFlow<DbDevice?> = _activeDevice.asStateFlow()

    // Database flows
    val allBatches: StateFlow<List<DbBatch>> = dao.getAllBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allScans: StateFlow<List<DbScan>> = dao.getAllScans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knownDevices: StateFlow<List<DbDevice>> = dao.getAllDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Network Discovery States
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanProgressScanned = MutableStateFlow(0)
    val scanProgressScanned: StateFlow<Int> = _scanProgressScanned.asStateFlow()

    private val _scanProgressTotal = MutableStateFlow(254)
    val scanProgressTotal: StateFlow<Int> = _scanProgressTotal.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DbDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DbDevice>> = _discoveredDevices.asStateFlow()

    // Active session States
    private val _currentBatchId = MutableStateFlow<String?>(null)
    val currentBatchId: StateFlow<String?> = _currentBatchId.asStateFlow()

    private val _connectionOk = MutableStateFlow<Boolean?>(null)
    val connectionOk: StateFlow<Boolean?> = _connectionOk.asStateFlow()

    // Scanner live stats & items
    private val _liveScans = MutableStateFlow<List<LiveScanItem>>(emptyList())
    val liveScans: StateFlow<List<LiveScanItem>> = _liveScans.asStateFlow()

    private val _totalScans = MutableStateFlow(0)
    val totalScans: StateFlow<Int> = _totalScans.asStateFlow()

    private val _uniqueScans = MutableStateFlow(0)
    val uniqueScans: StateFlow<Int> = _uniqueScans.asStateFlow()

    private val _sentScans = MutableStateFlow(0)
    val sentScans: StateFlow<Int> = _sentScans.asStateFlow()

    private val _failedScans = MutableStateFlow(0)
    val failedScans: StateFlow<Int> = _failedScans.asStateFlow()

    private var pingJob: Job? = null

    init {
        // Load active device from preferences
        val savedHost = prefs.activeDeviceHost
        val savedPort = prefs.activeDevicePort
        val savedName = prefs.activeDeviceName
        if (savedHost != null && savedName != null) {
            _activeDevice.value = DbDevice(savedHost, savedPort, savedName, System.currentTimeMillis())
        }

        // Start ping loop if there's an active device
        startPingLoop()
    }

    // Settings Updates
    fun updateTheme(newTheme: String) {
        prefs.theme = newTheme
        _theme.value = newTheme
    }

    fun updateVibration(enabled: Boolean) {
        prefs.vibration = enabled
        _vibration.value = enabled
    }

    fun updateSound(enabled: Boolean) {
        prefs.sound = enabled
        _sound.value = enabled
    }

    fun updateSendDuplicates(enabled: Boolean) {
        prefs.sendDuplicates = enabled
        _sendDuplicates.value = enabled
    }

    fun updateKeepAwake(enabled: Boolean) {
        prefs.keepAwake = enabled
        _keepAwake.value = enabled
    }

    fun updateScanDebounceMs(ms: Int) {
        prefs.scanDebounceMs = ms
        _scanDebounceMs.value = ms
    }

    fun updateResendDelayMs(ms: Int) {
        prefs.resendDelayMs = ms
        _resendDelayMs.value = ms
    }

    fun updateAutoSubmit(enabled: Boolean) {
        prefs.autoSubmit = enabled
        _autoSubmit.value = enabled
    }

    fun updatePrefix(value: String) {
        prefs.prefix = value
        _prefix.value = value
    }

    fun updateSuffix(value: String) {
        prefs.suffix = value
        _suffix.value = value
    }

    fun updateTrimStart(value: Int) {
        prefs.trimStart = value
        _trimStart.value = value
    }

    fun updateTrimEnd(value: Int) {
        prefs.trimEnd = value
        _trimEnd.value = value
    }

    fun updateSubmitChain(value: String) {
        prefs.submitChain = value
        _submitChain.value = value
    }

    fun updateManualConnection(host: String, port: Int) {
        prefs.manualHost = host
        prefs.manualPort = port
        _manualHost.value = host
        _manualPort.value = port
    }

    // Connect To Device
    fun connectTo(device: DbDevice?) {
        _activeDevice.value = device
        if (device != null) {
            prefs.activeDeviceHost = device.host
            prefs.activeDevicePort = device.port
            prefs.activeDeviceName = device.name
            
            // Add to known devices database
            viewModelScope.launch {
                dao.insertDevice(device)
            }
        } else {
            prefs.activeDeviceHost = null
            prefs.activeDeviceName = null
            _connectionOk.value = null
        }
        startPingLoop()
    }

    fun removeDevice(host: String) {
        viewModelScope.launch {
            dao.deleteDevice(host)
            if (_activeDevice.value?.host == host) {
                connectTo(null)
            }
        }
    }

    fun renameDevice(host: String, newName: String) {
        viewModelScope.launch {
            dao.renameDevice(host, newName)
            if (_activeDevice.value?.host == host) {
                val current = _activeDevice.value ?: return@launch
                val updated = current.copy(name = newName)
                _activeDevice.value = updated
                prefs.activeDeviceName = newName
            }
        }
    }

    // Ping Loop for Connected PC
    private fun startPingLoop() {
        pingJob?.cancel()
        val device = _activeDevice.value ?: return
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val ok = NetworkService.pingDevice(device.host, device.port) != null
                _connectionOk.value = ok
                // If disconnected, retry faster (2s) to reconnect instantly, otherwise 5s
                if (ok) {
                    delay(5000)
                } else {
                    delay(2000)
                }
            }
        }
    }

    // Subnet Network Scan
    fun scanNetwork() {
        if (_scanning.value) return
        _scanning.value = true
        _discoveredDevices.value = emptyList()
        _scanProgressScanned.value = 0
        
        viewModelScope.launch {
            try {
                val results = NetworkService.discoverDevices(prefs.manualPort) { scanned, total, found ->
                    _scanProgressScanned.value = scanned
                    _scanProgressTotal.value = total
                    _discoveredDevices.value = found
                }
                _discoveredDevices.value = results
            } catch (e: Exception) {
                Log.e(TAG, "Network scan failed", e)
            } finally {
                _scanning.value = false
            }
        }
    }

    // Batches/Sessions Operations
    fun startBatch(deviceName: String? = null): DbBatch {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val format = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val name = "Session - " + format.format(Date(now))
        val batch = DbBatch(id, name, now, deviceName)
        
        viewModelScope.launch {
            dao.insertBatch(batch)
        }
        
        _currentBatchId.value = id
        // Reset scanner session variables
        _liveScans.value = emptyList()
        _totalScans.value = 0
        _uniqueScans.value = 0
        _sentScans.value = 0
        _failedScans.value = 0
        
        return batch
    }

    fun endBatch() {
        val id = _currentBatchId.value ?: return
        _currentBatchId.value = null
        
        viewModelScope.launch {
            // Check if batch is empty, if so delete it so it doesn't clutter history
            val scans = dao.getScansForBatchSync(id)
            if (scans.isEmpty()) {
                dao.deleteBatch(id)
            }
        }
    }

    fun resumeBatch(batchId: String) {
        _currentBatchId.value = batchId
        _liveScans.value = emptyList()
        viewModelScope.launch {
            val scans = dao.getScansForBatchSync(batchId)
            _totalScans.value = scans.sumOf { it.count }
            _uniqueScans.value = scans.size
            _sentScans.value = scans.count { it.sent }
            _failedScans.value = scans.count { !it.sent }
        }
    }

    suspend fun addScan(data: String, type: String): AddScanResult? {
        val batchId = _currentBatchId.value ?: return null
        val now = System.currentTimeMillis()
        
        // Find duplicate scan in current batch
        val currentScans = dao.getScansForBatchSync(batchId)
        val existing = currentScans.find { it.data == data }
        
        return if (existing != null) {
            val updated = existing.copy(
                count = existing.count + 1,
                timestamp = now
            )
            dao.insertScan(updated)
            _totalScans.value += 1
            AddScanResult(updated, isDuplicate = true)
        } else {
            val id = UUID.randomUUID().toString()
            val newScan = DbScan(
                id = id,
                batchId = batchId,
                data = data,
                type = type,
                timestamp = now,
                sent = false,
                count = 1
            )
            dao.insertScan(newScan)
            _totalScans.value += 1
            _uniqueScans.value += 1
            AddScanResult(newScan, isDuplicate = false)
        }
    }

    fun updateScanSent(scanId: String, sent: Boolean) {
        viewModelScope.launch {
            dao.updateScanSent(scanId, sent)
            if (sent) {
                _sentScans.value += 1
                _failedScans.value = maxOf(0, _failedScans.value - 1)
            } else {
                _failedScans.value += 1
            }
        }
    }

    fun addToLiveScans(item: LiveScanItem) {
        // Show only 1 ID at a time, clearing the previous scan
        _liveScans.value = listOf(item)
    }

    fun updateLiveScanStatus(id: String, ok: Boolean?) {
        _liveScans.value = _liveScans.value.map {
            if (it.id == id) it.copy(ok = ok) else it
        }
    }

    fun incrementFailedScans() {
        _failedScans.value += 1
    }

    fun incrementSentScans() {
        _sentScans.value += 1
    }

    fun getScansForBatch(batchId: String): Flow<List<DbScan>> {
        return dao.getScansForBatch(batchId)
    }

    fun renameBatch(batchId: String, name: String) {
        viewModelScope.launch {
            dao.renameBatch(batchId, name)
        }
    }

    fun deleteBatch(batchId: String) {
        viewModelScope.launch {
            dao.deleteBatch(batchId)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            dao.clearAllBatches()
            dao.clearAllScans()
        }
    }

    // Single scan sending helper
    fun resendScan(scanId: String, data: String, type: String) {
        viewModelScope.launch {
            resendScanAsync(scanId, data, type)
        }
    }

    suspend fun resendScanAsync(scanId: String, data: String, type: String): Boolean {
        val device = _activeDevice.value ?: return false
        val prefPrefix = prefs.prefix
        val prefSuffix = prefs.suffix
        val prefAutoSubmit = prefs.autoSubmit
        val prefChain = prefs.submitChain.split(",")
            .map { it.split(":") }
            .filter { it.size == 2 && it[1] == "true" }
            .map { it[0] }

        val ok = NetworkService.sendScan(
            host = device.host,
            port = device.port,
            data = prefPrefix + data + prefSuffix,
            rawData = data,
            type = type,
            chain = prefChain,
            autoSubmit = prefAutoSubmit
        )
        dao.updateScanSent(scanId, ok)
        return ok
    }

    // Bulk resending
    fun resendUnsentScans(scans: List<DbScan>) {
        val device = _activeDevice.value ?: return
        viewModelScope.launch {
            val unsent = scans.filter { !it.sent }
            val prefPrefix = prefs.prefix
            val prefSuffix = prefs.suffix
            val prefAutoSubmit = prefs.autoSubmit
            val prefChain = prefs.submitChain.split(",")
                .map { it.split(":") }
                .filter { it.size == 2 && it[1] == "true" }
                .map { it[0] }
            val delayMs = prefs.resendDelayMs.toLong()

            for (scan in unsent) {
                val ok = NetworkService.sendScan(
                    host = device.host,
                    port = device.port,
                    data = prefPrefix + scan.data + prefSuffix,
                    rawData = scan.data,
                    type = scan.type,
                    chain = prefChain,
                    autoSubmit = prefAutoSubmit
                )
                dao.updateScanSent(scan.id, ok)
                if (delayMs > 0) {
                    delay(delayMs)
                }
            }
        }
    }

    fun loadCodesFromFile(context: Context, uri: Uri, callback: (List<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val codes = mutableListOf<String>()
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val content = inputStream.bufferedReader().readText()
                    val trimmedContent = content.trim()
                    if (trimmedContent.startsWith("[") || trimmedContent.startsWith("{")) {
                        try {
                            if (trimmedContent.startsWith("[")) {
                                val jsonArray = org.json.JSONArray(trimmedContent)
                                for (i in 0 until jsonArray.length()) {
                                    val item = jsonArray.optString(i)
                                    if (item.isNotEmpty()) {
                                        codes.add(item)
                                    }
                                }
                            } else {
                                val jsonObject = org.json.JSONObject(trimmedContent)
                                val keys = jsonObject.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    val value = jsonObject.optString(key)
                                    if (value.isNotEmpty()) {
                                        codes.add(value)
                                    }
                                }
                            }
                        } catch (je: Exception) {
                            Log.w(TAG, "File looks like JSON but failed parsing", je)
                        }
                    }
                    
                    if (codes.isEmpty()) {
                        content.split("\n").forEach { line ->
                            val lineTrimmed = line.trim()
                            if (lineTrimmed.isNotEmpty()) {
                                if (lineTrimmed.contains(",") && !lineTrimmed.contains("http://") && !lineTrimmed.contains("https://")) {
                                    val parts = lineTrimmed.split(",")
                                    for (part in parts) {
                                        val p = part.trim().removeSurrounding("\"").removeSurrounding("'")
                                        if (p.isNotEmpty()) {
                                            codes.add(p)
                                        }
                                    }
                                } else {
                                    codes.add(lineTrimmed)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load and parse file", e)
            }
            
            withContext(Dispatchers.Main) {
                callback(codes)
            }
        }
    }

    fun sendBulkCodes(
        fileName: String,
        codes: List<String>,
        onProgress: (sent: Int, total: Int) -> Unit,
        onFinished: (successCount: Int, failedCount: Int) -> Unit
    ): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val batchId = UUID.randomUUID().toString()
            val batchName = "Import: $fileName"
            val device = _activeDevice.value
            val batch = DbBatch(batchId, batchName, now, device?.name)
            dao.insertBatch(batch)
            
            val prefPrefix = prefs.prefix
            val prefSuffix = prefs.suffix
            val prefAutoSubmit = prefs.autoSubmit
            val prefChain = prefs.submitChain.split(",")
                .map { it.split(":") }
                .filter { it.size == 2 && it[1] == "true" }
                .map { it[0] }
            val delayMs = prefs.resendDelayMs.toLong()
            
            var successCount = 0
            var failedCount = 0
            val total = codes.size
            
            for (i in codes.indices) {
                val code = codes[i]
                val scanId = UUID.randomUUID().toString()
                
                var ok = false
                if (device != null) {
                    ok = NetworkService.sendScan(
                        host = device.host,
                        port = device.port,
                        data = prefPrefix + code + prefSuffix,
                        rawData = code,
                        type = "FILE_IMPORT",
                        chain = prefChain,
                        autoSubmit = prefAutoSubmit
                    )
                }
                
                val dbScan = DbScan(
                    id = scanId,
                    batchId = batchId,
                    data = code,
                    type = "FILE_IMPORT",
                    timestamp = System.currentTimeMillis(),
                    sent = ok,
                    count = 1
                )
                dao.insertScan(dbScan)
                
                if (ok) {
                    successCount++
                } else {
                    failedCount++
                }
                
                withContext(Dispatchers.Main) {
                    onProgress(i + 1, total)
                }
                
                if (delayMs > 0 && i < total - 1) {
                    delay(delayMs)
                }
            }
            
            withContext(Dispatchers.Main) {
                onFinished(successCount, failedCount)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pingJob?.cancel()
    }
}
