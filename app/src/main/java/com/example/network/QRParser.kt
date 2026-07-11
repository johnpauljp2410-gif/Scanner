package com.example.network

import android.net.Uri
import com.example.data.DbDevice
import org.json.JSONObject

object QRParser {
    fun parseConnectionQR(raw: String): DbDevice? {
        val data = raw.trim()
        if (data.isEmpty()) return null

        // Try JSON
        if (data.startsWith("{")) {
            try {
                val obj = JSONObject(data)
                val host = obj.optString("host", obj.optString("ip", "")).trim()
                if (host.isNotEmpty()) {
                    val port = obj.optInt("port", 5000)
                    val name = obj.optString("name", host)
                    return DbDevice(host, port, name, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                // fall through
            }
        }

        // Try URL
        if (data.contains("://")) {
            try {
                val uri = Uri.parse(data)
                val host = uri.host ?: ""
                if (host.isNotEmpty()) {
                    val port = if (uri.port != -1) uri.port else 5000
                    val name = uri.getQueryParameter("name") ?: host
                    return DbDevice(host, port, name, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                // fall through
            }
        }

        // Try raw host:port or host
        val hostPortRegex = Regex("""^([0-9A-Za-z._-]+?)(?::(\d{1,5}))?$""")
        val matchResult = hostPortRegex.find(data)
        if (matchResult != null) {
            val host = matchResult.groups[1]?.value ?: ""
            val portStr = matchResult.groups[2]?.value
            val port = portStr?.toIntOrNull() ?: 5000
            if (host.isNotEmpty()) {
                return DbDevice(host, port, host, System.currentTimeMillis())
            }
        }

        return null
    }
}
