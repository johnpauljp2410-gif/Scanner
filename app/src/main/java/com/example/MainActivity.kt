package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.AppViewModel
import com.example.ui.components.AdConfig
import com.example.ui.screens.BatchDetailsScreen
import com.example.ui.screens.MainTabScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.theme.MyApplicationTheme

import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.appopen.AppOpenAd
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Google Mobile Ads SDK
        try {
            MobileAds.initialize(this) {}
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            val themePreference by viewModel.theme.collectAsStateWithLifecycle()
            val darkTheme = when (themePreference) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                AppNavigation(viewModel)
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: AppViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "splash"
    ) {
        composable("splash") {
            SplashScreen(navController = navController, viewModel = viewModel)
        }

        composable("tabs") {
            MainTabScreen(
                viewModel = viewModel,
                onNavigateToScanner = { mode ->
                    navController.navigate("scanner/$mode")
                },
                onNavigateToBatchDetails = { batchId ->
                    navController.navigate("batch_details/$batchId")
                }
            )
        }

        composable(
            route = "scanner/{mode}",
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val mode = backStackEntry.arguments?.getString("mode") ?: "normal"
            ScannerScreen(
                viewModel = viewModel,
                mode = mode,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "batch_details/{batchId}",
            arguments = listOf(
                navArgument("batchId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val batchId = backStackEntry.arguments?.getString("batchId") ?: ""
            BatchDetailsScreen(
                viewModel = viewModel,
                batchId = batchId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToScanner = { mode ->
                    navController.navigate("scanner/$mode")
                }
            )
        }
    }
}

@Composable
fun SplashScreen(
    navController: NavController,
    viewModel: AppViewModel
) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Animation states
    val scale = remember { Animatable(0.5f) }
    val alpha = remember { Animatable(0f) }
    
    // Pulse animation state for the glowing ring
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    var hasNavigated by remember { mutableStateOf(false) }

    val navigateToMain = {
        if (!hasNavigated) {
            hasNavigated = true
            navController.navigate("tabs") {
                popUpTo("splash") { inclusive = true }
            }
        }
    }

    LaunchedEffect(Unit) {
        // Run logo entry animations concurrently
        launch {
            scale.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(1000)
            )
        }

        // Try to load and show App Open Ad
        if (activity != null) {
            var adShownOrFailed = false
            
            // Set a maximum timeout of 3.5 seconds to load the ad
            val timeoutJob = launch {
                delay(3500)
                if (!adShownOrFailed) {
                    navigateToMain()
                }
            }

            try {
                val adRequest = AdRequest.Builder().build()
                AppOpenAd.load(
                    context,
                    AdConfig.appOpenId,
                    adRequest,
                    object : AppOpenAd.AppOpenAdLoadCallback() {
                        override fun onAdLoaded(ad: AppOpenAd) {
                            adShownOrFailed = true
                            timeoutJob.cancel()
                            
                            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                                override fun onAdDismissedFullScreenContent() {
                                    navigateToMain()
                                }
                                override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                                    navigateToMain()
                                }
                            }
                            ad.show(activity)
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            adShownOrFailed = true
                            timeoutJob.cancel()
                            navigateToMain()
                        }
                    }
                )
            } catch (e: Exception) {
                adShownOrFailed = true
                timeoutJob.cancel()
                navigateToMain()
            }
        } else {
            delay(2000)
            navigateToMain()
        }
    }

    // Splash screen visual layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)), // Deep Slate background
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .scale(scale.value)
                    .alpha(alpha.value)
                    .size(140.dp)
            ) {
                // Outer glowing pulse ring
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(pulseScale)
                        .alpha(pulseAlpha)
                        .clip(CircleShape)
                        .background(Color(0xFF3B82F6))
                )

                // Main Logo Container
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF2563EB), Color(0xFF4F46E5))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "App Logo",
                        tint = Color.White,
                        modifier = Modifier.size(50.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Application Name
            Text(
                text = "Network Scanner",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.alpha(alpha.value)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle
            Text(
                text = "Fast Real-Time PC Entry",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.alpha(alpha.value)
            )
        }
        
        // Loading indicator at bottom
        CircularProgressIndicator(
            color = Color(0xFF3B82F6),
            strokeWidth = 3.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
                .size(24.dp)
                .alpha(alpha.value)
        )
    }
}
