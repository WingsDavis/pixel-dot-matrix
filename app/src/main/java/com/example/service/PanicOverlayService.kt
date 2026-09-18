package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.core.timer.PomodoroState
import com.example.ui.components.BreathingCircle
import com.example.ui.theme.MyApplicationTheme

class PanicOverlayService : Service(), SensorEventListener {

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null

    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var stepSensor: Sensor? = null

    // Fallback step counter
    private var stepsTaken = mutableStateOf(0)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_SHOW) {
            showOverlay()
        } else if (action == ACTION_HIDE) {
            hideOverlay()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        stepsTaken.value = 0
        registerStepListener()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )

        val frameLayout = FrameLayout(this)

        // Setup necessary Lifecycle owners for ComposeView inside WindowManager
        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()

        frameLayout.setViewTreeLifecycleOwner(lifecycleOwner)
        frameLayout.setViewTreeViewModelStoreOwner(lifecycleOwner)
        frameLayout.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MyApplicationTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Black.copy(alpha = 0.95f) // full-screen solid black overlay
                    ) {
                        PanicOverlayContent(
                            steps = stepsTaken.value,
                            onDismiss = {
                                // Close helper
                                val dismissIntent = Intent(ACTION_OVERLAY_DISMISSED)
                                dismissIntent.putExtra("steps", stepsTaken.value)
                                sendBroadcast(dismissIntent)
                                hideOverlay()
                                stopSelf()
                            },
                            onSimulateStep = {
                                stepsTaken.value += 5
                            }
                        )
                    }
                }
            }
        }

        frameLayout.addView(composeView)
        windowManager.addView(frameLayout, params)
        overlayView = frameLayout
    }

    private fun hideOverlay() {
        unregisterStepListener()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    private fun registerStepListener() {
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun unregisterStepListener() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                stepsTaken.value += 1
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }

    companion object {
        const val ACTION_SHOW = "com.example.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.example.action.HIDE_OVERLAY"
        const val ACTION_OVERLAY_DISMISSED = "com.example.action.OVERLAY_DISMISSED"
    }
}

@Composable
fun PanicOverlayContent(
    steps: Int,
    onDismiss: () -> Unit,
    onSimulateStep: () -> Unit
) {
    val progress = (steps / 50f).coerceIn(0f, 1f)
    val remainingSteps = (50 - steps).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Title block
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Panic Intervention",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Take 50 physical steps to unlock your device",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }

        // Animated Breathing circle (60s total, pulsing at 4s intervals)
        BreathingCircle(
            modifier = Modifier.weight(1f, fill = false)
        )

        // Steps display & Unlock controls
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsWalk,
                        contentDescription = "Steps",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Steps Taken",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "$steps / 50",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                enabled = steps >= 50,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    disabledContainerColor = Color.White.copy(alpha = 0.15f),
                    disabledContentColor = Color.White.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (steps >= 50) "Dismiss Overlay" else "Walk $remainingSteps More Steps",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Simulated stepping for testing (especially on Emulator environments where user cannot walk!)
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onSimulateStep) {
                Text(
                    text = "[Simulation] Simulate +5 steps",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
