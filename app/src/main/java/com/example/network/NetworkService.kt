package com.example.network

import android.util.Log
import com.example.data.DbDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

object NetworkService {
    private const val TAG = "NetworkService"
    private const val DEFAULT_PORT = 5000
    private val DISCOVER_PATHS = listOf("/discover", "/info", "/")
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .build()

    fun getLocalSubnetBase(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip != "0.0.0.0" && ip != "127.0.0.1") {
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                return "${parts[0]}.${parts[1]}.${parts[2]}"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
        }
        return null
    }

    suspend fun pingDevice(host: String, port: Int): DbDevice? {
        return withContext(Dispatchers.IO) {
            for (path in DISCOVER_PATHS) {
                val url = "http://$host:$port$path"
                val request = Request.Builder().url(url).get().build()
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            var name = host
                            val bodyText = response.body?.string() ?: ""
                            try {
                                val contentType = response.header("Content-Type") ?: ""
                                if (contentType.contains("application/json") && bodyText.isNotEmpty()) {
                                    val json = JSONObject(bodyText)
                                    name = json.optString("name", json.optString("hostname", host))
                                } else {
                                    val trimmed = bodyText.trim()
                                    if (trimmed.isNotEmpty() && trimmed.length < 60) {
                                        name = trimmed
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse body for $host", e)
                            }
                            return@withContext DbDevice(
                                host = host,
                                port = port,
                                name = name,
                                lastSeen = System.currentTimeMillis()
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Try next path
                }
            }
            null
        }
    }

    suspend fun discoverDevices(
        port: Int = DEFAULT_PORT,
        onProgress: (scanned: Int, total: Int, found: List<DbDevice>) -> Unit
    ): List<DbDevice> {
        val subnet = getLocalSubnetBase() ?: return emptyList()
        val foundDevices = mutableListOf<DbDevice>()
        val totalHosts = 254
        var scannedCount = 0
        
        withContext(Dispatchers.IO) {
            val semaphore = Semaphore(32) // Limit concurrency to be polite to the network
            val jobs = mutableListOf<Deferred<DbDevice?>>()
            
            for (i in 1..254) {
                val host = "$subnet.$i"
                val job = async {
                    semaphore.withPermit {
                        val device = pingDevice(host, port)
                        synchronized(foundDevices) {
                            scannedCount++
                            if (device != null) {
                                foundDevices.add(device)
                            }
                            onProgress(scannedCount, totalHosts, foundDevices.toList())
                        }
                        device
                    }
                }
                jobs.add(job)
            }
            jobs.awaitAll()
        }
        return foundDevices
    }

    suspend fun sendScan(
        host: String,
        port: Int,
        data: String,
        rawData: String = data,
        type: String,
        chain: List<String> = listOf("paste", "enter", "tab"),
        autoSubmit: Boolean = true
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val url = "http://$host:$port/scan"
            val jsonObject = JSONObject().apply {
                put("data", data)
                put("raw_data", rawData)
                put("type", type)
                put("timestamp", System.currentTimeMillis())
                
                val chainArray = org.json.JSONArray()
                for (item in chain) {
                    chainArray.put(item)
                }
                put("chain", chainArray)
                put("auto_submit", autoSubmit)
            }
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonObject.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
                
            try {
                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending scan to $host", e)
                false
            }
        }
    }
}
