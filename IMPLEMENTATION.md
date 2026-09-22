# Pixel Dot Matrix - Implementation & Continuation Guide

> Planned enhancements and their live progress tracker are maintained in
> [FEATURE_ROADMAP.md](FEATURE_ROADMAP.md).

This document maps out all features, architectures, and state flows implemented for **Pixel Dot Matrix** across the Mobile (`:app`), Watch companion (`:wear`), and resource-only Watch Face Format (`:watchface`) modules.

---

## 1. Architecture Overview & Synchronization Model

The application leverages a real-time, low-latency, bidirectional synchronizer built on Google Play Services **Wearable Data Client** and **Message Client**.

```
  +------------------+                    +------------------+
  |  MOBILE CLIENT   |                    |   WEAR CLIENT    |
  |     (:app)       |                    |     (:wear)      |
  +--------+---------+                    +--------+---------+
           |                                       |
           | ----- [/pomodoro/state] (Data) -----> | [Updates UI State,
           |                                       |  Triggers Alarm Vibrate]
           |                                       |
           | <---- [/pomodoro/control] (Msg) ----- | [Toggles/Pauses Timer]
           |                                       |
           | <---- [/panic/trigger] (Msg) -------- | [Triggers Panic Mode]
           |                                       |
           | ----- [/panic/frustration] (Msg) ---> | [Triggers Frustration
           |                                       |  Haptic warning]
```

### Wearable Communication Routes
* **`/pomodoro/state` (Data Layer)**: Pushes complete session parameters from mobile to wear synchronously using `PutDataMapRequest`.
* **`/pomodoro/control` (Message Layer)**: Sends control strings (`START`, `PAUSE`, `SKIP`, `RESET`, `START_FOCUS`, etc.) from Wear to control the active Pomodoro session on Mobile.
* **`/panic/trigger` & `/panic/resolve` (Message Layer)**: Bidirectional triggers used to enter or resolve the step-based Panic Mode.
* **`/panic/frustration` (Message Layer)**: Immediate watch nudge triggers when Stress/HRV patterns are logged or test alerts are pressed on the phone.

---

## 2. Key Implemented Modules & Features

### Feature 1: Bidirectional Companion Data Sync
* **Mobile (`:app`) Configuration**:
  - `WearableSyncManager.kt` constructs a modern `PutDataMapRequest` on `/pomodoro/state` pushing:
    - `state` / `current_phase`: Current `PomodoroState` name.
    - `seconds_remaining`: Time left in current session.
    - `is_running`: Active timer ticking state.
    - `is_panic_active`: Urgent flag denoting active physical intervention.
* **Watch (`:wear`) Configuration**:
  - `MainActivity.kt` implements `DataClient.OnDataChangedListener` and `MessageClient.OnMessageReceivedListener`.
  - Safely falls back to `current_phase` if the standard state is empty.
  - Automatically launches the companion watch layout when state changes or immediate messages are received.

### Feature 2: "Incident Response" Distraction Audit
Treats focus breaches like network security incidents. Instead of just noting failure, the app forces a retrospective breakdown to build a personal attention threat model.
* **Database Layer (`PanicLogEntity.kt`)**: Added audit fields:
  - `targetApp`: App/Website that caused the distraction.
  - `triggerReason`: Internal catalyst (e.g., Boredom, Stuck on a bug, Notification, Habitual reflex).
  - `forensicNotes`: Descriptive breakdown of the context surrounding the focus breach.
* **UI & ViewModel Layer**:
  - When step-based Panic Mode is resolved, the system instantiates an `activeIncidentAudit` state inside `PomodoroViewModel.kt`.
  - A forensic dialog in `TimerScreen.kt` prompts the user for inputs.
  - Audit logs are persistently displayed in `DefenseScreen.kt`, while focus/session history is displayed in `InsightsScreen.kt`.

### Feature 3: On-Device Predictive Vulnerability Engine
Analyzes historic distraction audits purely locally to predict future failures.
* **Heuristics Engine (`PomodoroViewModel.kt`)**:
  - Reads previous `panicLogs` from the local Room database.
  - Group-by calculations automatically target the peak hour of distraction.
  - Calculates dynamic real-time risk scores depending on the current hour of the day and day of the week (e.g., higher vulnerability on Thursday/Friday afternoons).
  - Displays predictions directly on the **ON-DEVICE THREAT ASSESSMENT** card in `TimerScreen.kt`.

### Feature 4: Local DNS Sinkhole VPN
A physical barrier that drops distraction packets directly at the system network interface.
* **Vpn Engine (`LocalDnsSinkholeVpnService.kt`)**:
  - Implements standard Android `VpnService`.
  - Establishes a local DNS-only VPN resolver and routes queries for the synthetic DNS server through the VPN.
  - Parses UDP DNS questions, returns NXDOMAIN for blocked domains, and forwards allowed DNS queries to an upstream resolver.
  - Includes a preloaded blocklist (`SINKHOLE_DOMAINS`) matching popular networks: Facebook, Instagram, TikTok, Twitter/X, Reddit, YouTube, and Google News.
  - Integrated into `AndroidManifest.xml` requiring `BIND_VPN_SERVICE` and `FOREGROUND_SERVICE` permissions.
  - Controlled by a fluid dashboard toggle in the Mobile application.

### Feature 5: Biometric Frustration Interception
Triggers physical warnings directly on the wrist *before* the user breaks focus, targeting biometric indicators of frustration (e.g., drop in HRV, stress spikes).
* **Companion Communication**:
  - Mobile features a **TEST NUDGE** button to transmit a `TRIGGER_FRUSTRATION` action to the companion.
* **Watch Feedback**:
  - `WearSyncService.kt` intercepts `/panic/frustration` and wakes up the wearable screen.
  - `MainActivity.kt` triggers `startFrustrationVibrator()`, implementing a custom dual-pulse gentle pattern to cue deep breathing.
  - Renders a visually striking **Burnout Warning** card detailing the Stress spike, with options to:
    1. **TAKE 5 MIN BREAK**: Sends a pause signal back to Mobile and dismisses the alert.
    2. **DISMISS**: Returns to the standard companion visual interface.

### Feature 6: Watch Face Dynamic Complication Integration
* **Massive Dot Matrix Session Timer (`watchface.xml`)**:
  - Replaces the standard digital clock with a massive, color-coordinated session timer when a pomodoro session is active.
  - Features dynamic color matching: Focus (Blue), Short Break (Yellow), Long Break (Green), and Panic Mode (Red).
  - Includes dynamic hide/show logic using WFF `Transform` to perfectly transition back to the standard clock when idle (`value = -1`).
  - Automatically preserves battery in Always-On-Display (AOD) mode by hiding the massive session timer and reverting to the standard digital clock, preventing "frozen timer" artifacts.
* **Progress Complication Service (`PomodoroProgressComplicationService.kt`)**:
  - Transmits the current session `secondsRemaining` bounds and securely passes a `value = -1` idle flag to the Watch Face Format (WFF) layout engine to properly clear UI states.
  - Controls the dynamic coloring and progress of the Watch Face's right-hand seconds arc.
* **Play-Compliant Watch Face Packaging**:
  - `:watchface` is the authoritative WFF v2 bundle and contains only resources with `android:hasCode="false"`.
  - `:wear` remains executable and owns Data Layer receiving, Pomodoro UI, panic behavior, and complication data providers.
  - The WFF face references the exported providers in `:wear` by component name. The two watch APKs are built and installed separately from the same repository.
* **Watch Face Studio (`WearScreen.kt`)**:
  - Replaces the previous utility-style Wear screen with a dark, watch-matched studio surface.
  - Provides a circular live preview, state tabs, color swatches for timer/seconds/idle colors, owned complication mode controls, panic-logo visibility, and a diagnostics section.
  - Pushes app-owned complication settings over `/pomodoro/watchface/config/v2` and displays watch acknowledgement state.
  - Opens the installed Pixel Watch companion app through its verified `RootActivity` for WFF-owned color editing.
* **Phone-Driven Watch Face Config (`WearableSyncManager.kt` and `WearSyncService.kt`)**:
  - Syncs `timerColor`, `secondsColor`, `idleTimeColor`, `customText`, `leftSlotMode`, `bottomRightSlotMode`, `showPanicLogo`, `ambientStyle`, and `themePreset`.
  - Every apply includes `schema_version=2` and a unique `revision` and is sent as an urgent persistent DataItem.
  - Wear commits settings to `watchface_config_prefs`, refreshes owned complication providers, and acknowledges the exact revision over `/pomodoro/watchface/status/v2`.
  - The phone reports `Syncing`, `Applied`, or `Failed` for complication sync instead of treating local Data Layer acceptance as successful watch application.
* **Owned Complication Modes**:
  - `CustomTextComplicationService.kt` supports left-slot modes for custom text, phase, heart rate, next break, streak placeholder, and hidden.
  - `BottomRightComplicationService.kt` supports bottom-right modes for date, phase, timer, battery placeholder, and hidden.
* **WFF Configuration Boundary**:
  - App-owned complication content and behavior can sync directly through `:wear`.
  - `hour_color`, `minute_color`, and `second_color` are independent WFF `ColorConfiguration` values with Cyan, Orange, Blue, Green, Yellow, Red, and White options.
  - The default interactive clock remains Hour Orange, Minute White, and Second Arc Orange.
  - Active session digits remain state-driven: Focus Blue, Short Break Yellow, Long Break Green, and Panic Red.
  - Original, Cyan, Focus, and High Contrast WFF flavors provide editor presets.
  - Declarative layout, ambient style, visibility options, and third-party complication selection remain owned by WFF `UserConfigurations` and the Wear OS Watch Face Editor.

### Feature 7: Wear App Circular Edge Progress Interface
* **Wear App UI (`MainActivity.kt`)**:
  - Upgraded the standard timer card to a `FullScreenStateTimer` that maximizes the smartwatch screen real estate.
  - Features a thick, rounded progress arc that perfectly traces the outermost circular bezel of the smartwatch.
  - Arc and control buttons dynamically shift colors to match the session state (Focus = Blue, Short Break = Yellow, Long Break = Green).
  - Timer typography scaled up to a massive size in the center of the watch face, utilizing negative space with cleanly centralized Play/Pause/Skip buttons.

### Feature 8: Durable Timer Preferences
* **Settings storage (`TimerSettingsStore.kt`)**:
  - Stores focus, short-break, and long-break durations, long-break cadence, and both auto-start preferences in Preferences DataStore.
  - Uses schema version 1 and replaces missing, legacy, or out-of-range values with bounded defaults while preserving valid preferences.
  - Writes the latest complete timer snapshot through a conflated queue so rapid slider changes do not change unrelated DataStore keys.
* **Startup and UI (`PomodoroViewModel.kt` and `SetupScreen.kt`)**:
  - Loads and migrates timer settings before constructing `PomodoroEngine`, so the first timer and Settings composition use persisted values.
  - Exposes duration, cadence, and automation controls from one observable settings state.
  - Applies the configured long-break cadence in the timer engine instead of a hardcoded four-session interval.
  - Auto-start preferences are persisted here; transition behavior is tracked separately under roadmap item 5.

---

## 3. Development Reference

When working on this project, verify and build on top of these files:

### Mobile (`:app`) Target Files
1. **`app/src/main/java/com/example/core/sync/WearableSyncManager.kt`**
   - Wear OS communication setup and message routes.
2. **`app/src/main/java/com/example/data/entity/PanicLogEntity.kt`**
   - Forensic SQLite data entity.
3. **`app/src/main/java/com/example/service/LocalDnsSinkholeVpnService.kt`**
   - Native network interception engine.
4. **`app/src/main/java/com/example/ui/viewmodel/PomodoroViewModel.kt`**
   - Main coordinating business logic, VPN bindings, and predictive assessment model.
5. **`app/src/main/java/com/example/ui/screens/TimerScreen.kt`**
   - Focus dashboard, VPN switches, threat assessment layouts, and Audit popups.
6. **`app/src/main/java/com/example/ui/screens/DefenseScreen.kt`**
   - Local distraction logs displaying historic forensic records, predictive vulnerability, DNS Sinkhole, and frustration alert controls.
7. **`app/src/main/java/com/example/ui/screens/InsightsScreen.kt`**
   - Completed focus sessions, task history, and resilience summary.

### Watch (`:wear`) Target Files
1. **`wear/src/main/java/com/example/wear/MainActivity.kt`**
   - Main wearable companion, state consumer, haptic coordinator, stress alert layout, and circular edge progress arc.
2. **`wear/src/main/java/com/example/wear/WearSyncService.kt`**
   - Background WearableListenerService capturing direct synchronization and stress messages.
3. **`wear/src/main/java/com/example/wear/PomodoroProgressComplicationService.kt`**
   - Service broadcasting pomodoro countdown data ranges to the active watch face.
4. **`wear/src/main/res/raw/watchface.xml`**
   - Declarative Watch Face Format (WFF) defining the massive dot matrix timers, progress arcs, and dynamic ambient layout logic.
5. **`wear/src/main/java/com/example/wear/BottomRightComplicationService.kt`**
   - Owned bottom-right complication provider whose rendered value is controlled by phone-pushed watch face config.
