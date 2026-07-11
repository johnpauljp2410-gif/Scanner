package com.example.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("scanner_settings", Context.MODE_PRIVATE)

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit().putString("theme", value).apply()

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", false)
        set(value) = prefs.edit().putBoolean("vibration", value).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", false)
        set(value) = prefs.edit().putBoolean("sound", value).apply()

    var sendDuplicates: Boolean
        get() = prefs.getBoolean("send_duplicates", false)
        set(value) = prefs.edit().putBoolean("send_duplicates", value).apply()

    var keepAwake: Boolean
        get() = prefs.getBoolean("keep_awake", false)
        set(value) = prefs.edit().putBoolean("keep_awake", value).apply()

    var scanDebounceMs: Int
        get() = prefs.getInt("scan_debounce_ms", 150)
        set(value) = prefs.edit().putInt("scan_debounce_ms", value).apply()

    var resendDelayMs: Int
        get() = prefs.getInt("resend_delay_ms", 150)
        set(value) = prefs.edit().putInt("resend_delay_ms", value).apply()

    var autoSubmit: Boolean
        get() = prefs.getBoolean("auto_submit", false)
        set(value) = prefs.edit().putBoolean("auto_submit", value).apply()

    var prefix: String
        get() = prefs.getString("prefix", "") ?: ""
        set(value) = prefs.edit().putString("prefix", value).apply()

    var suffix: String
        get() = prefs.getString("suffix", "") ?: ""
        set(value) = prefs.edit().putString("suffix", value).apply()

    var trimStart: Int
        get() = prefs.getInt("trim_start", 0)
        set(value) = prefs.edit().putInt("trim_start", value).apply()

    var trimEnd: Int
        get() = prefs.getInt("trim_end", 0)
        set(value) = prefs.edit().putInt("trim_end", value).apply()

    var submitChain: String
        get() = prefs.getString("submit_chain", "paste:false,enter:false,tab:false") ?: "paste:false,enter:false,tab:false"
        set(value) = prefs.edit().putString("submit_chain", value).apply()

    var manualHost: String
        get() = prefs.getString("manual_host", "") ?: ""
        set(value) = prefs.edit().putString("manual_host", value).apply()

    var manualPort: Int
        get() = prefs.getInt("manual_port", 5000)
        set(value) = prefs.edit().putInt("manual_port", value).apply()

    var activeDeviceHost: String?
        get() = prefs.getString("active_device_host", null)
        set(value) = prefs.edit().putString("active_device_host", value).apply()

    var activeDevicePort: Int
        get() = prefs.getInt("active_device_port", 5000)
        set(value) = prefs.edit().putInt("active_device_port", value).apply()

    var activeDeviceName: String?
        get() = prefs.getString("active_device_name", null)
        set(value) = prefs.edit().putString("active_device_name", value).apply()
}
