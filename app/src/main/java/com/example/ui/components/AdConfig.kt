package com.example.ui.components

import com.example.BuildConfig

object AdConfig {
    // Google's official risk-free AdMob test IDs
    private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_APP_OPEN_ID = "ca-app-pub-3940256099942544/9257395921"
    private const val TEST_NATIVE_ID = "ca-app-pub-3940256099942544/2247696110"

    // Production IDs from user's AdMob console
    private const val PROD_HOME_BANNER_ID = "ca-app-pub-6558395504048408/5613516803"
    private const val PROD_SCANNER_BANNER_ID = "ca-app-pub-6558395504048408/9653668313"
    private const val PROD_APP_OPEN_ID = "ca-app-pub-6558395504048408/2367416686"
    private const val PROD_HISTORY_NATIVE_ID = "ca-app-pub-6558395504048408/7574299884"

    // Set to true to activate original/production AdMob ads, or false to use Google's safe test ads
    private const val FORCE_PRODUCTION_ADS = true

    val homeBannerId: String
        get() = if (FORCE_PRODUCTION_ADS || !BuildConfig.DEBUG) PROD_HOME_BANNER_ID else TEST_BANNER_ID

    val scannerBannerId: String
        get() = if (FORCE_PRODUCTION_ADS || !BuildConfig.DEBUG) PROD_SCANNER_BANNER_ID else TEST_BANNER_ID

    val appOpenId: String
        get() = if (FORCE_PRODUCTION_ADS || !BuildConfig.DEBUG) PROD_APP_OPEN_ID else TEST_APP_OPEN_ID

    val historyNativeId: String
        get() = if (FORCE_PRODUCTION_ADS || !BuildConfig.DEBUG) PROD_HISTORY_NATIVE_ID else TEST_NATIVE_ID
}
