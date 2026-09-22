package com.example.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.core.content.ContextCompat

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets
import com.example.wear.ui.theme.*
import com.example.wear.ui.components.*
import java.util.UUID

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener, SensorEventListener {

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }
    private val commandOutbox by lazy { WearCommandOutbox(this, nodeClient, messageClient) }
    private val wearHaptics by lazy { WearHaptics(this) }

    // Remote State (synced from phone)
    private val _stateName = mutableStateOf("FOCUS")
    private val _secondsRemaining = mutableStateOf(1500)
    private val _isRunning = mutableStateOf(false)
    private var timerRevision = -1L
    private var timerSessionId = UUID.randomUUID().toString()
    private var timerUpdatedAtEpochMs = 0L
    private var wearLeaseExpiresAtEpochMs = 0L
    private var hasWearAuthority = false
    @Volatile
    private var lastStandalonePublishAtEpochMs = 0L
    private var focusDuration = 1500
    private var shortBreakDuration = 300
    private var longBreakDuration = 900

    // Wear Local Biometrics
    private val _heartRate = mutableStateOf(74)
    private val _heartRateThreshold = mutableStateOf(130f)
    private val _stepsTakenInPanic = mutableStateOf(0)
    private val _isFrustrated = mutableStateOf(false)

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var vibratorJob: Job? = null

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var stepDetectorSensor: Sensor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        restoreTimerState()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        if (hasHeartRatePermission()) {
            heartRateSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        val triggerFrustration = intent.getBooleanExtra("TRIGGER_FRUSTRATION", false)
        if (triggerFrustration) {
            _isFrustrated.value = true
        }

        val userTriggeredPanic = intent.getBooleanExtra("TRIGGER_PANIC", false) ||
            intent.data?.host == "trigger"
        val remotePanicUi = intent.getBooleanExtra("FORCE_PANIC_UI", false)
        if (userTriggeredPanic || remotePanicUi) {
            _stateName.value = "PANIC_MODE"
        }
        if (userTriggeredPanic) {
            triggerPanicOnPhone()
        }

        setContent {
            WearAppTheme {
                val stateName by _stateName
                val secondsRemaining by _secondsRemaining
                val isRunning by _isRunning
                val heartRate by _heartRate
                val heartRateThreshold by _heartRateThreshold
                val stepsTakenInPanic by _stepsTakenInPanic
                val isFrustrated by _isFrustrated

                LaunchedEffect(isRunning, stateName) {
                    while (isRunning && stateName != "PANIC_MODE" && _secondsRemaining.value > 0) {
                        delay(1_000L)
                        if (_isRunning.value) {
                            _secondsRemaining.value = (_secondsRemaining.value - 1).coerceAtLeast(0)
                            if (_secondsRemaining.value == 0) _isRunning.value = false
                            if (hasWearAuthority) {
                                timerUpdatedAtEpochMs = System.currentTimeMillis()
                                timerRevision = maxOf(timerRevision + 1, timerUpdatedAtEpochMs)
                                saveStateToPrefs(_stateName.value, _secondsRemaining.value, _isRunning.value)
                                if (timerUpdatedAtEpochMs - lastStandalonePublishAtEpochMs >= STANDALONE_PUBLISH_INTERVAL_MS || !_isRunning.value) {
                                    activityScope.launch(Dispatchers.IO) { publishWearSnapshot() }
                                }
                            }
                        }
                    }
                }

                // Manage haptic feedback loop in PANIC_MODE or Frustrated Mode
                LaunchedEffect(stateName, isFrustrated) {
                    if (stateName == "PANIC_MODE") {
                        // Wake up the screen and keep it on indefinitely
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(true)
                            setTurnScreenOn(true)
                        } else {
                            @Suppress("DEPRECATION")
                            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                        }
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                        startEscalatingVibrator()
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                            stepDetectorSensor?.let {
                                sensorManager.registerListener(this@MainActivity, it, SensorManager.SENSOR_DELAY_UI)
                            }
                        }
                    } else if (isFrustrated) {
                        startFrustrationVibrator()
                        stepDetectorSensor?.let { sensorManager.unregisterListener(this@MainActivity, it) }
                    } else {
                        // Allow screen to turn off normally again
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            setShowWhenLocked(false)
                            setTurnScreenOn(false)
                        }
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                        stopVibrator()
                        stepDetectorSensor?.let { sensorManager.unregisterListener(this@MainActivity, it) }
                    }
                }

                val targetBackgroundColor = when {
                    stateName == "PANIC_MODE" -> Color(0xFF3E1111) // Deep dark red
                    isFrustrated -> Color(0xFF0D2240) // Deep dark blue
                    else -> Color.Black
                }

                val animatedBackgroundColor by animateColorAsState(
                    targetValue = targetBackgroundColor,
                    animationSpec = tween(durationMillis = 800)
                )

                val pagerState = rememberPagerState(pageCount = { 5 })
                val focusRequester = remember { FocusRequester() }
                val coroutineScope = rememberCoroutineScope()
                var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
                var rotaryDirection by remember { mutableIntStateOf(0) }
                var rotaryLocked by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }

                // Sync pager to state changes
                LaunchedEffect(stateName) {
                    when (stateName) {
                        "PANIC_MODE" -> pagerState.animateScrollToPage(3)
                        "LONG_BREAK" -> pagerState.animateScrollToPage(2)
                        "SHORT_BREAK" -> pagerState.animateScrollToPage(1)
                        "FOCUS" -> pagerState.animateScrollToPage(0)
                    }
                }

                if (isFrustrated && stateName != "PANIC_MODE") {
                    FrustrationWarningScreen(
                        heartRate = heartRate,
                        onTakeBreak = {
                            sendTimerCommand("START_SHORT_BREAK")
                            _isFrustrated.value = false
                        },
                        onDismiss = {
                            _isFrustrated.value = false
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(animatedBackgroundColor)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .onPreRotaryScrollEvent { event ->
                                val direction = if (event.verticalScrollPixels > 0f) 1 else -1
                                if (direction != rotaryDirection) {
                                    rotaryDirection = direction
                                    rotaryAccumulator = 0f
                                }
                                rotaryAccumulator += kotlin.math.abs(event.verticalScrollPixels)
                                if (!rotaryLocked && rotaryAccumulator >= ROTARY_PAGE_THRESHOLD_PX) {
                                    val targetPage = (pagerState.currentPage + direction).coerceIn(0, pagerState.pageCount - 1)
                                    rotaryAccumulator = 0f
                                    if (targetPage != pagerState.currentPage) {
                                        rotaryLocked = true
                                        coroutineScope.launch {
                                            wearHaptics.play(WearHapticPattern.NAVIGATION_TICK, 32)
                                            pagerState.animateScrollToPage(targetPage)
                                            delay(ROTARY_SETTLE_MS)
                                            rotaryLocked = false
                                            focusRequester.requestFocus()
                                        }
                                    }
                                }
                                true
                            }
                            .focusRequester(focusRequester)
                            .focusable(),
                        verticalAlignment = Alignment.CenterVertically
                    ) { page ->
                        when (page) {
                        0 -> { // Focus
                            FullScreenStateTimer(
                                stateName = if (stateName == "FOCUS") stateName else "FOCUS",
                                secondsRemaining = if (stateName == "FOCUS") secondsRemaining else 1500,
                                isRunning = if (stateName == "FOCUS") isRunning else false,
                                onToggle = { if (stateName == "FOCUS") toggleTimerOnPhone(isRunning) else toggleTimerOnPhone(false, "FOCUS") },
                                onReset = ::resetTimerOnPhone,
                                onNext = ::skipTimerOnPhone
                            )
                        }
                        1 -> { // Short Break
                            FullScreenStateTimer(
                                stateName = if (stateName == "SHORT_BREAK") stateName else "SHORT_BREAK",
                                secondsRemaining = if (stateName == "SHORT_BREAK") secondsRemaining else 300,
                                isRunning = if (stateName == "SHORT_BREAK") isRunning else false,
                                onToggle = { if (stateName == "SHORT_BREAK") toggleTimerOnPhone(isRunning) else toggleTimerOnPhone(false, "SHORT_BREAK") },
                                onReset = ::resetTimerOnPhone,
                                onNext = ::skipTimerOnPhone
                            )
                        }
                        2 -> { // Long Break
                            FullScreenStateTimer(
                                stateName = if (stateName == "LONG_BREAK") stateName else "LONG_BREAK",
                                secondsRemaining = if (stateName == "LONG_BREAK") secondsRemaining else 900,
                                isRunning = if (stateName == "LONG_BREAK") isRunning else false,
                                onToggle = { if (stateName == "LONG_BREAK") toggleTimerOnPhone(isRunning) else toggleTimerOnPhone(false, "LONG_BREAK") },
                                onReset = ::resetTimerOnPhone,
                                onNext = ::skipTimerOnPhone
                            )
                        }
                        3 -> { // Panic
                             Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                             ) {
                                if (stateName == "PANIC_MODE") {
                                    var inRecovery by remember { mutableStateOf(false) }
                                    if (inRecovery) {
                                        com.example.wear.ui.screens.RecoveryScreen(
                                            onRecoveryComplete = {
                                                inRecovery = false
                                                resolvePanicOnPhone()
                                            }
                                        )
                                    } else {
                                        WearBentoCard(
                                            modifier = Modifier.size(110.dp),
                                            color = TealAccent,
                                            shape = CircleShape,
                                            onClick = { inRecovery = true }
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "RECOVER",
                                                    style = WearTypography.title2,
                                                    color = CardBlack
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    WearBentoCard(
                                        modifier = Modifier.size(110.dp),
                                        color = PanicRed,
                                        shape = CircleShape,
                                        onClick = { triggerPanicOnPhone() }
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "PANIC",
                                                style = WearTypography.title1,
                                                color = CardWhite
                                            )
                                        }
                                    }
                                }
                             }
                        }
                        4 -> {
                            WearQuickActions(
                                haptics = wearHaptics,
                                onFocus = { sendTimerCommand("START_FOCUS") },
                                onBreak = { sendTimerCommand("START_SHORT_BREAK") },
                                onDistraction = { sendMessageToPhone("/incident/mark", "DISTRACTION") }
                            )
                        }
                        }
                    }
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(pagerState.pageCount) { page ->
                            val selected = page == pagerState.currentPage
                            Box(
                                Modifier
                                    .size(if (selected) 6.dp else 4.dp)
                                    .background(
                                        if (selected) CardWhite else CardWhite.copy(alpha = 0.28f),
                                        CircleShape
                                    )
                            )
                        }
                    }
                    }
                }
            }
        }

        // Register wearable listeners
        dataClient.addListener(this)
        messageClient.addListener(this)
        activityScope.launch(Dispatchers.IO) {
            while (isActive) {
                commandOutbox.flush()
                if (hasWearAuthority && runCatching { Tasks.await(nodeClient.connectedNodes).isNotEmpty() }.getOrDefault(false)) {
                    publishWearSnapshot()
                }
                delay(15_000L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dataClient.removeListener(this)
        messageClient.removeListener(this)
        sensorManager.unregisterListener(this)
        activityScope.cancel()
        stopVibrator()
    }

    // --- Bidirectional Wearable Communication ---

    private fun toggleTimerOnPhone(isRunning: Boolean, targetState: String? = null) {
        val action = if (isRunning) {
            "PAUSE"
        } else {
            when (targetState) {
                "FOCUS" -> "START_FOCUS"
                "SHORT_BREAK" -> "START_SHORT_BREAK"
                "LONG_BREAK" -> "START_LONG_BREAK"
                else -> "START"
            }
        }
        sendTimerCommand(action)
    }

    private fun skipTimerOnPhone() {
        sendTimerCommand("SKIP")
    }

    private fun resetTimerOnPhone() {
        sendTimerCommand("RESET")
    }

    private fun triggerPanicOnPhone() {
        sendMessageToPhone("/panic/trigger", "TRIGGER_PANIC")
    }

    private fun resolvePanicOnPhone() {
        sendMessageToPhone("/panic/resolve", "RESOLVE_PANIC")
    }

    private fun sendMessageToPhone(path: String, payload: String) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val delivered = commandOutbox.sendOrQueue(path, payload, timerRevision)
                if (!delivered) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Saved. Will sync when phone connects", Toast.LENGTH_SHORT).show()
                    }
                }
                Log.d(TAG, "Sent message $payload to path $path")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to phone", e)
            }
        }
    }

    private fun sendTimerCommand(action: String) {
        activityScope.launch(Dispatchers.IO) {
            val connected = runCatching { Tasks.await(nodeClient.connectedNodes).isNotEmpty() }.getOrDefault(false)
            if (connected) {
                commandOutbox.sendOrQueue("/pomodoro/control", action, timerRevision)
            } else {
                withContext(Dispatchers.Main) { applyStandaloneAction(action) }
                publishWearSnapshot()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Running on watch until phone reconnects", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyStandaloneAction(action: String) {
        val previousState = _stateName.value
        when (action) {
            "START" -> _isRunning.value = true
            "PAUSE" -> _isRunning.value = false
            "RESET" -> {
                _stateName.value = "FOCUS"
                _secondsRemaining.value = focusDuration
                _isRunning.value = false
            }
            "SKIP" -> when (_stateName.value) {
                "FOCUS" -> setStandalonePhase("SHORT_BREAK", shortBreakDuration, false)
                "SHORT_BREAK" -> setStandalonePhase("LONG_BREAK", longBreakDuration, false)
                else -> setStandalonePhase("FOCUS", focusDuration, false)
            }
            "START_FOCUS" -> setStandalonePhase("FOCUS", focusDuration, true)
            "START_SHORT_BREAK" -> setStandalonePhase("SHORT_BREAK", shortBreakDuration, true)
            "START_LONG_BREAK" -> setStandalonePhase("LONG_BREAK", longBreakDuration, true)
        }
        if (previousState != _stateName.value) timerSessionId = UUID.randomUUID().toString()
        hasWearAuthority = true
        timerUpdatedAtEpochMs = System.currentTimeMillis()
        wearLeaseExpiresAtEpochMs = timerUpdatedAtEpochMs + WEAR_AUTHORITY_LEASE_MS
        timerRevision = maxOf(timerRevision + 1, timerUpdatedAtEpochMs)
        saveStateToPrefs(_stateName.value, _secondsRemaining.value, _isRunning.value)
    }

    private fun setStandalonePhase(state: String, seconds: Int, running: Boolean) {
        _stateName.value = state
        _secondsRemaining.value = seconds
        _isRunning.value = running
    }

    private fun publishWearSnapshot() {
        val sourceNode = runCatching { Tasks.await(nodeClient.localNode).id }.getOrDefault("wear")
        val request = PutDataMapRequest.create("/pomodoro/state").apply {
            dataMap.putString("state", _stateName.value)
            dataMap.putString("current_phase", _stateName.value)
            dataMap.putInt("seconds_remaining", _secondsRemaining.value)
            dataMap.putBoolean("is_running", _isRunning.value)
            dataMap.putBoolean("is_panic_active", _stateName.value == "PANIC_MODE")
            dataMap.putLong("timer_revision", timerRevision)
            dataMap.putString("session_id", timerSessionId)
            dataMap.putString("source_device", "wear")
            dataMap.putString("source_node", sourceNode)
            dataMap.putString("authority", "WEAR")
            dataMap.putLong("anchor_elapsed_realtime", SystemClock.elapsedRealtime())
            dataMap.putLong("lease_expires_at", wearLeaseExpiresAtEpochMs)
            dataMap.putLong("timestamp", timerUpdatedAtEpochMs)
        }.asPutDataRequest().setUrgent()
        runCatching { Tasks.await(dataClient.putDataItem(request)) }
            .onSuccess { lastStandalonePublishAtEpochMs = System.currentTimeMillis() }
            .onFailure { Log.e(TAG, "Failed to publish standalone timer snapshot", it) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("TRIGGER_FRUSTRATION", false)) {
            _isFrustrated.value = true
        }
        if (intent.getBooleanExtra("FORCE_PANIC_UI", false)) {
            _stateName.value = "PANIC_MODE"
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val item = event.dataItem
                if (item.uri.path == "/pomodoro/state") {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    if (dataMap.getString("source_device") == "wear") continue
                    val incomingRevision = dataMap.getLong("timer_revision", -1L)
                    if (incomingRevision < timerRevision && hasWearAuthority) continue
                    val newStateRaw = dataMap.getString("state") ?: dataMap.getString("current_phase") ?: "FOCUS"
                    val isPanic = dataMap.getBoolean("is_panic_active", false)

                    val newState = if (isPanic || newStateRaw == "PANIC_MODE") "PANIC_MODE" else newStateRaw

                    // State Transition Haptic (Wave)
                    if (newState != _stateName.value && newState != "PANIC_MODE" && _stateName.value != "PANIC_MODE") {
                        triggerWaveHaptic()
                    }
                    _stateName.value = newState

                    val newSeconds = dataMap.getInt("seconds_remaining")
                    // 5-minute warning Haptic (Double Tap)
                    if (newState == "FOCUS" && _secondsRemaining.value > 300 && newSeconds <= 300) {
                        triggerDoubleTapHaptic()
                    }
                    val updatedAt = dataMap.getLong("timestamp", System.currentTimeMillis())
                    val reconciledSeconds = if (dataMap.getBoolean("is_running")) {
                        (newSeconds - ((System.currentTimeMillis() - updatedAt).coerceAtLeast(0L) / 1_000L).toInt()).coerceAtLeast(0)
                    } else newSeconds
                    _secondsRemaining.value = reconciledSeconds
                    _isRunning.value = dataMap.getBoolean("is_running")
                    timerRevision = incomingRevision
                    timerSessionId = dataMap.getString("session_id") ?: timerSessionId
                    timerUpdatedAtEpochMs = System.currentTimeMillis()
                    wearLeaseExpiresAtEpochMs = 0L
                    hasWearAuthority = false
                    focusDuration = dataMap.getInt("focus_duration", focusDuration)
                    shortBreakDuration = dataMap.getInt("short_break_duration", shortBreakDuration)
                    longBreakDuration = dataMap.getInt("long_break_duration", longBreakDuration)
                    activityScope.launch(Dispatchers.IO) { commandOutbox.flush() }

                    saveStateToPrefs(_stateName.value, _secondsRemaining.value, _isRunning.value)
                    Log.d(TAG, "Watch state updated from phone: state=${_stateName.value}, running=${_isRunning.value}, panic=$isPanic")
                }
            }
        }
    }

    private fun saveStateToPrefs(state: String, secondsRemaining: Int, isRunning: Boolean) {
        val prefs = getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("state", state)
            .putInt("seconds_remaining", secondsRemaining)
            .putBoolean("is_running", isRunning)
            .putLong("timer_revision", timerRevision)
            .putString("session_id", timerSessionId)
            .putLong("timer_updated_at", timerUpdatedAtEpochMs)
            .putLong("wear_lease_expires_at", wearLeaseExpiresAtEpochMs)
            .putBoolean("wear_authority", hasWearAuthority)
            .putInt("focus_duration", focusDuration)
            .putInt("short_break_duration", shortBreakDuration)
            .putInt("long_break_duration", longBreakDuration)
            .apply()

        val requester = androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
            this,
            android.content.ComponentName(this, PomodoroProgressComplicationService::class.java)
        )
        requester.requestUpdateAll()

        val customRequester = androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
            this,
            android.content.ComponentName(this, CustomTextComplicationService::class.java)
        )
        customRequester.requestUpdateAll()
    }

    private fun restoreTimerState() {
        val prefs = getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
        _stateName.value = prefs.getString("state", "FOCUS") ?: "FOCUS"
        _secondsRemaining.value = prefs.getInt("seconds_remaining", 1500)
        _isRunning.value = prefs.getBoolean("is_running", false)
        timerRevision = prefs.getLong("timer_revision", -1L)
        timerSessionId = prefs.getString("session_id", timerSessionId) ?: timerSessionId
        timerUpdatedAtEpochMs = prefs.getLong("timer_updated_at", System.currentTimeMillis())
        wearLeaseExpiresAtEpochMs = prefs.getLong("wear_lease_expires_at", 0L)
        hasWearAuthority = prefs.getBoolean("wear_authority", false) &&
            System.currentTimeMillis() <= wearLeaseExpiresAtEpochMs
        focusDuration = prefs.getInt("focus_duration", 1500)
        shortBreakDuration = prefs.getInt("short_break_duration", 300)
        longBreakDuration = prefs.getInt("long_break_duration", 900)
        if (_isRunning.value && hasWearAuthority) {
            val elapsedSeconds = ((System.currentTimeMillis() - timerUpdatedAtEpochMs).coerceAtLeast(0L) / 1_000L).toInt()
            _secondsRemaining.value = (_secondsRemaining.value - elapsedSeconds).coerceAtLeast(0)
            if (_secondsRemaining.value == 0) _isRunning.value = false
            timerUpdatedAtEpochMs = System.currentTimeMillis()
        }
    }

    private fun hasHeartRatePermission(): Boolean {
        val hasBodySensors = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        val hasReadHeartRate = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            ContextCompat.checkSelfPermission(
                this,
                "android.permission.health.READ_HEART_RATE"
            ) == PackageManager.PERMISSION_GRANTED

        return hasBodySensors && hasReadHeartRate
    }

    private fun triggerWaveHaptic() {
        wearHaptics.play(WearHapticPattern.TRANSITION)
    }

    private fun triggerDoubleTapHaptic() {
        wearHaptics.play(WearHapticPattern.FIVE_MINUTE_WARNING)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "Watch received message: ${messageEvent.path}")
        when (messageEvent.path) {
            "/pomodoro/control_ack" -> {
                val commandId = messageEvent.data.toString(Charsets.UTF_8).substringBefore('|')
                if (commandId.isNotBlank()) commandOutbox.acknowledge(commandId)
            }
            "/panic/trigger" -> {
                _stateName.value = "PANIC_MODE"
                saveStateToPrefs("PANIC_MODE", _secondsRemaining.value, _isRunning.value)
            }
            "/panic/resolve" -> {
                _stateName.value = "FOCUS"
                _stepsTakenInPanic.value = 0
                saveStateToPrefs("FOCUS", _secondsRemaining.value, _isRunning.value)
            }
            "/pomodoro/custom_text" -> {
                val customText = String(decodeSyncPayload(messageEvent.data))
                getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE).edit()
                    .putString("custom_text", customText)
                    .apply()
                // Force update CustomTextComplicationService
                val componentName = android.content.ComponentName(this, CustomTextComplicationService::class.java)
                val requester = androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(this, componentName)
                requester.requestUpdateAll()
            }
            "/panic/frustration" -> {
                _isFrustrated.value = true
            }
        }
    }

    private fun decodeSyncPayload(bytes: ByteArray): ByteArray {
        val raw = bytes.toString(Charsets.UTF_8)
        if (!raw.startsWith("sync1|")) return bytes
        val encoded = raw.removePrefix("sync1|").substringAfter('|', missingDelimiterValue = "")
        return runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }.getOrDefault(bytes)
    }

    // --- Active Escalating Haptics for Panic Interventions ---

    private fun startEscalatingVibrator() {
        if (vibratorJob != null) return // Already running

        vibratorJob = activityScope.launch(Dispatchers.Default) {
            var cycle = 1
            while (isActive) {
                val duration = (70L * cycle).coerceAtMost(350L)
                val delayTime = (260L / cycle).coerceAtLeast(120L)

                wearHaptics.play(WearHapticPattern.PANIC_PULSE, (70 + (cycle * 18)).coerceAtMost(180))

                delay(duration + delayTime)

                cycle = if (cycle >= 5) 1 else cycle + 1
                delay(450L)
            }
        }
        Toast.makeText(this, "Escalating watch haptic loop started!", Toast.LENGTH_SHORT).show()
    }

    private fun startFrustrationVibrator() {
        if (vibratorJob != null) return // Already running

        vibratorJob = activityScope.launch(Dispatchers.Default) {
            while (isActive) {
                wearHaptics.play(WearHapticPattern.FRUSTRATION, 140)
                delay(3000L) // gap between double pulses
            }
        }
        Toast.makeText(this, "Biometric frustration alert active! Take a 5 min break.", Toast.LENGTH_SHORT).show()
    }

    private fun stopVibrator() {
        vibratorJob?.cancel()
        vibratorJob = null
        wearHaptics.cancel()
    }

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                if (event.values.isNotEmpty()) {
                    val hr = event.values[0].toInt()
                    _heartRate.value = hr

                    // Save to prefs for complication
                    val prefs = getSharedPreferences("pomodoro_sync_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putInt("current_hr", hr).apply()

                    if (hr > _heartRateThreshold.value) {
                        if (_stateName.value != "PANIC_MODE") {
                            triggerPanicOnPhone()
                        }
                    }
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                if (_stateName.value == "PANIC_MODE") {
                    val nextSteps = _stepsTakenInPanic.value + 1
                    _stepsTakenInPanic.value = nextSteps
                    if (nextSteps >= 50) {
                        resolvePanicOnPhone()
                        // Local state reset happens on response from phone
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "WearMainActivity"
        private const val ROTARY_PAGE_THRESHOLD_PX = 28f
        private const val ROTARY_SETTLE_MS = 140L
        private const val WEAR_AUTHORITY_LEASE_MS = 2 * 60 * 60 * 1_000L
        private const val STANDALONE_PUBLISH_INTERVAL_MS = 15_000L
    }
}

// --- Wear OS Simple Composable Display Cards ---

@Composable
fun FrustrationWarningScreen(
    heartRate: Int,
    onTakeBreak: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D2240))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Spa,
                contentDescription = null,
                tint = Color(0xFF7DD3FC),
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "BURNOUT",
                style = WearTypography.title2,
                color = CardWhite,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Warning",
                style = WearTypography.caption1,
                color = CardWhite.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Text(
                text = "$heartRate BPM",
                style = WearTypography.display2.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold),
                color = Color(0xFF7DD3FC),
                textAlign = TextAlign.Center
            )
            Text(
                text = "Stress spike detected",
                style = WearTypography.caption2,
                color = CardWhite.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onTakeBreak,
                    modifier = Modifier.size(width = 82.dp, height = 40.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF7DD3FC),
                        contentColor = CardBlack
                    )
                ) {
                    Text("TAKE 5", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.size(width = 82.dp, height = 40.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF1F2937),
                        contentColor = CardWhite
                    )
                ) {
                    Text("DISMISS", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WearQuickActions(
    haptics: WearHaptics,
    onFocus: () -> Unit,
    onBreak: () -> Unit,
    onDistraction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(7) { index ->
                Box(
                    Modifier
                        .size(5.dp)
                        .background(if (index < 3) TealAccent else CardWhite.copy(alpha = 0.25f), CircleShape)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "QUICK ACTIONS",
            fontFamily = DotMatrixFont,
            fontSize = 17.sp,
            color = CardWhite
        )
        Spacer(Modifier.height(10.dp))
        DotMatrixActionButton("FOCUS", Icons.Default.PlayArrow, Color(0xFF42A5F5), onFocus)
        Spacer(Modifier.height(7.dp))
        DotMatrixActionButton("BREAK", Icons.Default.Coffee, Color(0xFFFFCA28), onBreak)
        Spacer(Modifier.height(7.dp))
        DotMatrixActionButton("DISTRACTION", Icons.Default.Report, PanicRed, onDistraction)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { haptics.setEnabled(!haptics.isEnabled) },
                modifier = Modifier.weight(1f).height(34.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (haptics.isEnabled) TealAccent.copy(alpha = 0.25f) else CardWhite.copy(alpha = 0.12f),
                    contentColor = CardWhite
                )
            ) {
                Text(if (haptics.isEnabled) "HAPTIC ON" else "HAPTIC OFF", fontFamily = DotMatrixFont, fontSize = 10.sp)
            }
            Spacer(Modifier.width(6.dp))
            Text("${haptics.intensity}", fontFamily = DotMatrixFont, fontSize = 11.sp, color = CardWhite.copy(alpha = 0.65f))
            WearBentoIconButton(
                onClick = { haptics.setIntensity(haptics.intensity - 32) },
                modifier = Modifier.size(30.dp),
                color = CardWhite.copy(alpha = 0.14f),
                contentColor = CardWhite
            ) { Icon(Icons.Default.Remove, contentDescription = "Lower haptic intensity", modifier = Modifier.size(14.dp)) }
            WearBentoIconButton(
                onClick = { haptics.setIntensity(haptics.intensity + 32) },
                modifier = Modifier.size(30.dp),
                color = CardWhite.copy(alpha = 0.14f),
                contentColor = CardWhite
            ) { Icon(Icons.Default.Add, contentDescription = "Raise haptic intensity", modifier = Modifier.size(14.dp)) }
        }
        DotMatrixActionButton("TEST HAPTIC", Icons.Default.Vibration, TealAccent) {
            haptics.play(WearHapticPattern.SUCCESS)
        }
    }
}

@Composable
private fun DotMatrixActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .border(1.dp, accent, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF151817), contentColor = CardWhite)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text(label, fontFamily = DotMatrixFont, fontSize = 14.sp, color = CardWhite)
            Box(Modifier.size(6.dp).background(accent, CircleShape))
        }
    }
}

@Composable
fun FullScreenStateTimer(
    stateName: String,
    secondsRemaining: Int,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onNext: () -> Unit
) {
    val targetProgress = when (stateName) {
        "FOCUS" -> secondsRemaining.toFloat() / 1500f
        "SHORT_BREAK" -> secondsRemaining.toFloat() / 300f
        "LONG_BREAK" -> secondsRemaining.toFloat() / 900f
        else -> secondsRemaining.toFloat() / 60f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress.coerceIn(0f, 1f),
        animationSpec = tween(1000, easing = LinearOutSlowInEasing)
    )

    val minutes = secondsRemaining / 60
    val seconds = secondsRemaining % 60
    val timeString = String.format("%02d:%02d", minutes, seconds)

    val themeColor = when (stateName) {
        "FOCUS" -> Color(0xFF42A5F5)
        "SHORT_BREAK" -> Color(0xFFFFCA28)
        "LONG_BREAK" -> Color(0xFF81C784)
        "PANIC_MODE" -> Color(0xFFEF5350)
        else -> TealAccent
    }

    val phaseLabel = when (stateName) {
        "FOCUS" -> "DEEP FOCUS"
        "SHORT_BREAK" -> "SHORT BREAK"
        "LONG_BREAK" -> "LONG BREAK"
        "PANIC_MODE" -> "PANIC"
        else -> stateName.replace('_', ' ')
    }
    val clockLabel = java.time.LocalTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US))

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension / 2f - 5.dp.toPx()
            drawCircle(
                color = CardWhite.copy(alpha = 0.22f),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            repeat(60) { tick ->
                val angle = Math.toRadians((tick * 6.0) - 90.0)
                val major = tick % 15 == 0
                val inner = outerRadius - if (major) 10.dp.toPx() else 5.dp.toPx()
                val start = androidx.compose.ui.geometry.Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * inner,
                    center.y + kotlin.math.sin(angle).toFloat() * inner
                )
                val end = androidx.compose.ui.geometry.Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * outerRadius,
                    center.y + kotlin.math.sin(angle).toFloat() * outerRadius
                )
                drawLine(
                    color = if (major && tick == 0) themeColor else CardWhite.copy(alpha = if (major) 0.7f else 0.32f),
                    start = start,
                    end = end,
                    strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            val sweep = 360f * animatedProgress
            drawArc(
                color = CardWhite.copy(alpha = 0.12f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(themeColor.copy(alpha = 0.10f), -90f, sweep, false, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round))
            drawArc(themeColor.copy(alpha = 0.24f), -90f, sweep, false, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
            drawArc(themeColor, -90f, sweep, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(clockLabel, fontFamily = DotMatrixFont, fontSize = 11.sp, color = CardWhite.copy(alpha = 0.65f))
            Text(phaseLabel, fontFamily = DotMatrixFont, fontSize = 13.sp, color = themeColor)
        }

        Column(
            modifier = Modifier.align(Alignment.Center).offset(y = (-13).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(timeString, fontFamily = DotMatrixFont, fontSize = 48.sp, color = CardWhite)
            Text(
                "${(animatedProgress * 100).toInt()}% REMAINING",
                fontFamily = DotMatrixFont,
                fontSize = 9.sp,
                color = CardWhite.copy(alpha = 0.55f)
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WearBentoIconButton(
                onClick = onReset,
                modifier = Modifier.size(34.dp),
                color = CardWhite.copy(alpha = 0.16f),
                contentColor = CardWhite
            ) { Icon(Icons.Default.RestartAlt, contentDescription = "Reset", modifier = Modifier.size(17.dp)) }
            WearBentoIconButton(
                onClick = onToggle,
                modifier = Modifier.size(52.dp),
                color = themeColor,
                contentColor = CardBlack
            ) {
                Icon(if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isRunning) "Pause" else "Start", modifier = Modifier.size(27.dp))
            }
            WearBentoIconButton(
                onClick = onNext,
                modifier = Modifier.size(34.dp),
                color = CardWhite.copy(alpha = 0.16f),
                contentColor = CardWhite
            ) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(17.dp)) }
        }
    }
}

@Composable
fun AnimatedPanicSpiral() {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationZ = rotation
                scaleX = pulse
                scaleY = pulse
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            val maxRadius = size.minDimension / 1.6f
            val numArms = 5
            val dotsPerArm = 80

            for (arm in 0 until numArms) {
                val armOffset = (Math.PI * 2 / numArms) * arm

                val armColorStart = when (arm % 3) {
                    0 -> Color(0xFFE53935) // Red
                    1 -> Color(0xFF8E24AA) // Purple
                    else -> Color(0xFFFF9800) // Orange
                }

                val armColorEnd = when ((arm + 1) % 3) {
                    0 -> Color(0xFFE53935)
                    1 -> Color(0xFF8E24AA)
                    else -> Color(0xFFFF9800)
                }

                for (i in 0 until dotsPerArm) {
                    val fraction = i.toFloat() / dotsPerArm
                    val r = maxRadius * Math.pow(fraction.toDouble(), 0.7).toFloat()

                    val theta = armOffset + fraction * Math.PI * 3.0

                    val x = center.x + r * Math.cos(theta).toFloat()
                    val y = center.y + r * Math.sin(theta).toFloat()

                    val dotColor = androidx.compose.ui.graphics.lerp(armColorStart, armColorEnd, fraction)

                    val dotSize = (3f + fraction * 16f).dp.toPx()

                    if (fraction < 0.35f) {
                        drawCircle(
                            color = dotColor,
                            radius = dotSize / 2f,
                            center = androidx.compose.ui.geometry.Offset(x, y)
                        )
                    } else {
                        withTransform({
                            translate(left = x, top = y)
                            rotate(degrees = Math.toDegrees(theta).toFloat())
                            translate(left = -x, top = -y)
                        }) {
                            drawRect(
                                color = dotColor,
                                topLeft = androidx.compose.ui.geometry.Offset(x - dotSize/2f, y - dotSize/2f),
                                size = androidx.compose.ui.geometry.Size(dotSize, dotSize)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Simple Material Theme for Wear OS ---

@Composable
fun WearAppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = WearTealBentoColors,
        typography = WearTypography,
        content = content
    )
}
