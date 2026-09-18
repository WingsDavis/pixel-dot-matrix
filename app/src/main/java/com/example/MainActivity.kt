package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.timer.PomodoroState
import com.example.service.PanicOverlayContent
import com.example.service.PanicOverlayService
import com.example.ui.screens.DefenseScreen
import com.example.ui.screens.InsightsScreen
import com.example.ui.screens.SetupScreen
import com.example.ui.screens.TimerScreen
import com.example.ui.screens.WearScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PomodoroViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PomodoroViewModel by viewModels()

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PanicOverlayService.ACTION_OVERLAY_DISMISSED) {
                // If service dismissed the overlay (steps met), we manually resolve on our state machine too!
                val totalSteps = intent.getIntExtra("steps", 50)
                viewModel.setSteps(totalSteps)
                viewModel.incrementStepsSimulated() // simulated extra step to fulfill 50 if needed
                viewModel.engine.resolvePanic()
            }
        }
    }

    private val wearControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.wear.CONTROL_MESSAGE") {
                val path = intent.getStringExtra("path")
                val payload = intent.getStringExtra("payload") ?: ""
                Log.d("MainActivity", "Received wear control broadcast: path=$path, payload=$payload")
                if (path == "/pomodoro/control") {
                    when (payload) {
                        "START" -> viewModel.startTimer()
                        "PAUSE" -> viewModel.pauseTimer()
                        "SKIP" -> viewModel.skipTimer()
                        "RESET" -> viewModel.resetTimer()
                        "START_FOCUS" -> {
                            viewModel.engine.pause()
                            viewModel.engine.syncState(PomodoroState.FOCUS, viewModel.engine.focusDuration, true)
                        }
                        "START_SHORT_BREAK" -> {
                            viewModel.engine.pause()
                            viewModel.engine.syncState(PomodoroState.SHORT_BREAK, viewModel.engine.shortBreakDuration, true)
                        }
                        "START_LONG_BREAK" -> {
                            viewModel.engine.pause()
                            viewModel.engine.syncState(PomodoroState.LONG_BREAK, viewModel.engine.longBreakDuration, true)
                        }
                    }
                } else if (path == "/panic/resolve") {
                    viewModel.engine.resolvePanic()
                } else if (path == "/panic/trigger") {
                    viewModel.triggerPanic()
                } else if (path == "/incident/mark") {
                    val incidentId = payload.split('|').getOrNull(1) ?: java.util.UUID.randomUUID().toString()
                    viewModel.markDistractionFromWear(incidentId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent.getBooleanExtra("TRIGGER_PANIC", false)) {
            viewModel.triggerPanic()
        }

        // Register observers
        val filter = IntentFilter(PanicOverlayService.ACTION_OVERLAY_DISMISSED)
        val controlFilter = IntentFilter("com.example.wear.CONTROL_MESSAGE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(wearControlReceiver, controlFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayReceiver, filter)
            registerReceiver(wearControlReceiver, controlFilter)
        }

        setContent {
            MyApplicationTheme {
                val currentState by viewModel.currentState.collectAsState()
                var currentTab by remember { mutableStateOf(0) }
                var showSettings by remember { mutableStateOf(false) }

                // Manage System Alert Service trigger
                LaunchedEffect(currentState) {
                    if (currentState == PomodoroState.PANIC_MODE) {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            val intent = Intent(this@MainActivity, PanicOverlayService::class.java).apply {
                                action = PanicOverlayService.ACTION_SHOW
                            }
                            startService(intent)
                        }
                    } else {
                        // Ensure service is stopped when panic is resolved
                        val intent = Intent(this@MainActivity, PanicOverlayService::class.java).apply {
                            action = PanicOverlayService.ACTION_HIDE
                        }
                        startService(intent)
                    }
                }

                // If System Overlay permission is not granted, we draw the full screen Overlay directly in App's Compose view!
                if (currentState == PomodoroState.PANIC_MODE && !Settings.canDrawOverlays(this@MainActivity)) {
                    val steps by viewModel.stepsTakenInPanic.collectAsState()
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black.copy(alpha = 0.95f)
                    ) {
                        PanicOverlayContent(
                            steps = steps,
                            onDismiss = {
                                viewModel.engine.resolvePanic()
                            },
                            onSimulateStep = {
                                viewModel.incrementStepsSimulated()
                            }
                        )
                    }
                } else {
                    // Standard App UI Layout
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            MainHeader(
                                showingSettings = showSettings,
                                onSettingsClick = { showSettings = !showSettings }
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            if (showSettings) {
                                SetupScreen(viewModel = viewModel)
                            } else when (currentTab) {
                                0 -> TimerScreen(viewModel = viewModel)
                                1 -> InsightsScreen(viewModel = viewModel)
                                2 -> DefenseScreen(viewModel = viewModel)
                                3 -> WearScreen(viewModel = viewModel)
                            }

                            // Floating Bottom Nav
                            if (!showSettings) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                                ) {
                                    FloatingBottomNav(
                                        selectedTab = currentTab,
                                        onTabSelected = { currentTab = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(overlayReceiver)
        } catch (e: Exception) {
            // Safe ignore
        }
        try {
            unregisterReceiver(wearControlReceiver)
        } catch (e: Exception) {
            // Safe ignore
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("TRIGGER_PANIC", false)) {
            viewModel.triggerPanic()
        }
    }
}

@Composable
fun MainHeader(
    showingSettings: Boolean = false,
    onSettingsClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = if (showingSettings) "LOCAL SETTINGS" else "PIXEL DOT MATRIX",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 0.5.sp
            )
            Text(
                text = if (showingSettings) "Settings" else "Focus console",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Surface(
            modifier = Modifier
                .size(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = com.example.ui.theme.RetroBlack,
            onClick = onSettingsClick
        ) {
            Icon(
                imageVector = if (showingSettings) Icons.Default.ArrowBack else Icons.Default.Settings,
                contentDescription = if (showingSettings) "Back" else "Settings",
                tint = com.example.ui.theme.RetroPaper,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

@Composable
fun FloatingBottomNav(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = com.example.ui.theme.RetroBlack
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingNavBarItem(
                icon = Icons.Default.Timer,
                isSelected = selectedTab == 0,
                onClick = { onTabSelected(0) }
            )
            FloatingNavBarItem(
                icon = Icons.Default.BarChart,
                isSelected = selectedTab == 1,
                onClick = { onTabSelected(1) }
            )
            FloatingNavBarItem(
                icon = Icons.Default.Shield,
                isSelected = selectedTab == 2,
                onClick = { onTabSelected(2) }
            )
            FloatingNavBarItem(
                icon = Icons.Default.Watch,
                isSelected = selectedTab == 3,
                onClick = { onTabSelected(3) }
            )
        }
    }
}

@Composable
fun FloatingNavBarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) com.example.ui.theme.CardWhite else Color.Transparent
    val iconColor = if (isSelected) com.example.ui.theme.CardBlack else com.example.ui.theme.CardWhite.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(backgroundColor, RoundedCornerShape(6.dp))
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
    }
}
