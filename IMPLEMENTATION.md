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

### Feature 9: Shared Timer Authority
* **Versioned state contract (`TimerSyncContract.kt`)**:
  - Timer snapshots include revision, session ID, source node, authority, phase, remaining time, running state, wall-clock update time, monotonic anchor, and Wear lease expiry.
  - The phone accepts only newer Wear snapshots with a live lease and rejects echoes, stale snapshots, expired leases, and commands based on an old revision.
  - Running snapshots reconcile the full elapsed interval while guarding against clocks that are unexpectedly ahead.
* **Phone authority (`WearableSyncManager.kt` and `TimerSnapshotStore.kt`)**:
  - The phone persists its last authoritative snapshot and hydrates `PomodoroEngine` from it after process recreation.
  - Revisions advance for commands and meaningful state changes rather than every timer tick; elapsed time is derived from the snapshot anchor.
  - Accepted standalone Wear state is immediately republished at a newer phone-owned revision so both devices converge on one state.
* **Standalone Wear lease (`MainActivity.kt`)**:
  - When no phone node is connected, watch controls apply locally under a renewable two-hour lease and publish a persistent Wear snapshot.
  - The watch restores and advances a running leased timer after process recreation, then publishes it when connectivity returns.
  - Phone-owned timers count down locally on the watch from their timestamp, keeping the UI and complications current without one Data Layer write per second.
  - Timer commands use unique IDs, so repeated actions remain valid while duplicate delivery stays identifiable.

### Feature 10: Reliable Offline Synchronization
* **Mobile durable outbox (`SyncOutbox.kt`)**:
  - Persists message operations in Room as `PENDING`, `DELIVERED`, `APPLIED`, or `FAILED`, including retry count and the latest failure reason.
  - Coalesces superseded operations, retries on app resume and the connected-node polling loop, and uses bounded exponential backoff with a five-attempt failure limit.
  - Treats transport delivery separately from a Wear acknowledgement sent only after the operation has been applied.
* **Idempotent Wear application (`WearSyncService.kt`)**:
  - Stores recently applied envelope IDs and acknowledges duplicate delivery without repeating the operation.
  - Returns an applied or failed acknowledgement with a useful reason to the phone.
  - Watch timer commands remain queued until the phone returns an application acknowledgement; repeated user actions use distinct command IDs.
* **Persistent Data Layer operations**:
  - Timer snapshots and watch-face configurations remain urgent persistent DataItems, which coalesce to their newest value while offline.
  - Incident messages use stable coalescing keys, and the generic Room outbox is ready for compact task summaries introduced in roadmap item 6.
* **User recovery (`WearScreen.kt`)**:
  - Watch Face Studio reports pending, delivered, applied, and failed counts separately.
  - The latest failure reason is visible, and Retry resets terminal failures before immediately flushing the queue.

### Feature 11: Watch-Face Configuration Persistence
* **Phone editor DataStore (`WatchFaceConfigStore.kt`)**:
  - Migrates the legacy `phone_watchface_config` SharedPreferences file into Preferences DataStore on first read.
  - Persists colors, custom text, logo visibility, preset, left and bottom-right slot modes, ambient style, user presets, recent colors, and independent local/watch revisions.
  - Hydrates synchronously before Watch Face Studio renders and saves coherent editor drafts through a conflated queue.
* **Revision and reset behavior**:
  - Every local draft receives its own local revision while preserving the last watch-applied revision, so the Studio can report a real difference.
  - A watch acknowledgement only advances `appliedRevision`; it does not replace newer local edits.
  - Reset restores every built-in default, sends a logo-reset DataItem, and applies a new revision to the watch.
* **Editor completeness (`WearScreen.kt`)**:
  - Left-slot and bottom-right owned content modes are included in previews, drafts, presets, replay, reset, and sync payloads. The legacy ambient preference is retained for compatibility; the deployed WFF ambient layout remains authoritative.
  - The phone model is independent of the WFF package lifecycle, so reinstalling or updating the watch face cannot erase the saved phone configuration.

### Feature 12: Session Automation
* **Engine transitions (`PomodoroEngine.kt`)**:
  - Automatic focus completion follows the configured 2-8 session long-break cadence.
  - Auto-start Breaks and Auto-start Focus independently control whether the next phase begins immediately.
  - Manual Next remains an explicit Focus -> Short Break -> Long Break -> Focus cycle and does not depend on automatic cadence.
  - Completion emits one typed transition event; manual transitions are marked separately and do not produce completion alerts.
* **Process recreation (`TimerAutomationReconciler.kt`)**:
  - Completed-focus cadence progress is part of the authoritative timer snapshot and survives phone and watch process recreation.
  - Elapsed background time can advance across multiple auto-started phases, then persists the reconciled snapshot before synchronization resumes.
  - Standalone Wear leases use the same cadence and auto-start behavior and publish one resulting snapshot on reconnection.
* **Transition preferences and feedback**:
  - DataStore persists independent sound, notification, and haptic toggles alongside the automation settings.
  - The phone posts one transition notification and optional local sound/haptic; the watch uses its existing single phase-transition haptic.
  - Notification channels disable their own sound and vibration so enabling all three preferences does not duplicate feedback.

### Feature 13: Compact Task Queue
* **Room model (`FocusTaskEntity.kt` and `FocusTaskDao.kt`)**:
  - Stores stable UUID, title, order, pending/completed/skipped status, session estimate, completed-session count, and timestamps.
  - Database migration 6 -> 7 adds the queue and a nullable stable `taskId` to session history without adding a foreign key, so deleting a task cannot invalidate prior sessions.
* **Queue behavior (`FocusTaskRepository.kt` and `PomodoroViewModel.kt`)**:
  - Supports compact add, select, reorder, complete, skip, and delete actions with persistent selected-task identity.
  - Each completed focus increments the selected task and advances only after its estimated session count is reached.
  - Session records store both stable task ID and title snapshot, preserving readable history after rename or deletion.
* **Phone and Wear UI**:
  - The timer screen replaces the ephemeral task text field with a dense reusable queue, estimate stepper, progress label, and icon actions.
  - Only selected and next task summaries are published as a persistent DataItem.
  - Wear quick actions show single-line ellipsized summaries capped at 40 characters.

---

## 3. Development Reference

### Daily Focus Target (Roadmap Item 7)

- `DailyFocusGoalStore.kt` persists the enable switch, minutes/session mode,
  separate targets, reset hour, and phone time-zone ID in Preferences DataStore.
  The feature starts disabled. Target values are bounded to 1-1440 minutes or
  1-100 completed sessions; changing them never updates session history.
- The shared `DailyFocusGoal.kt` model is compiled into phone and Wear using
  their Kotlin source sets. Calendar dates define each half-open daily period
  `[reset, next reset)` so daylight-saving days may be 23 or 25 hours.
- `DailyFocusCalculator.kt` sums the seconds of saved focus intervals that overlap
  the daily period, plus the current running focus interval. Minutes are the
  combined total divided by 60, rounded down. Completed sessions count only
  records with status `COMPLETED` whose completion time is inside the period.
  Completion belongs to the ending day; elapsed minutes can span two days.
- `PomodoroEngine.kt` records elapsed focus before pausing, skipping, resetting,
  synchronizing, or automatically changing phase. Pauses and skips are recorded
  as interrupted focus rather than successful completions. Break time is excluded.
  Existing historical records are read as stored; they are not rewritten.
- Running intervals retain a stable ID in the timer snapshot. On process
  recreation, elapsed time and any completed phases are reconciled into focus
  records before the refreshed timer snapshot is saved. A durable pending record
  covers a crash between closing an interval and inserting it into Room.
- Database migration 7 -> 8 adds a nullable unique `recordId`; old rows remain
  intact, and replaying a recovered record cannot insert a duplicate. This is an
  additive forward migration; use a database backup for an APK downgrade.
- The timer home screen has a compact expandable target control with a switch,
  mode selector, validated number input, increment/decrement buttons, reset-hour
  menu, progress bar, and a visible save-error state. Time/date/zone broadcasts,
  app resume, and a bounded clock tick recalculate the current daily period.
- `/pomodoro/daily_target/v1` publishes an urgent persistent phone-authored
  snapshot independently of timer-state updates. It includes settings, exact
  period bounds, focused seconds, and completed sessions. Sub-minute changes
  are coalesced for sync; both devices display the same whole-minute count.
- `WearDailyFocusGoal.kt` validates and persists received snapshots. The Wear app
  adds a Daily Focus page. The dedicated Daily Target provider and owned left and
  bottom-right slot modes use the same snapshot. Disabled or expired targets
  return no complication data, and complication validity ends at daily reset.
- Automated logic, storage, phone interaction/render, and Wear receive tests pass.
  On 2026-10-21, Pixel 9 and Pixel Watch 3 (both API 37) agreed on a QA session
  goal of 1/3, including its period and timezone. Visible watch-page and
  watch-face refresh verification remain pending.

### Useful Insights (Roadmap Item 8)

- `InsightsCalculator.kt` owns date/task filters, focus-overlap accounting,
  completion and uninterrupted-interval metrics, panic-only recovery, category
  counts, and daily/weekly buckets. Formulas and legacy-record behavior are
  documented in `docs/INSIGHTS.md`.
- `PomodoroViewModel` exposes a cached Insights state calculated off the main
  thread from Room and filter flows, with a minute refresh while subscribed.
- `InsightsScreen.kt` uses compact dot-matrix metrics, native date selection,
  searchable task/category filters, and stable-key lazy history without the old
  20-record limit. Static retro tiles no longer have no-op click actions.
- No schema or timer-state changes are required. Host tests cover fixed
  timestamps, partial data, DST, picker search/selection, and 10,000 records.
  Pixel 9 API 37 passed native picker/filter integration, empty/incident-only
  states, and 10,000-record navigation/search on 2026-10-21. Last-row navigation
  took 120 ms. TalkBack remains pending; see `docs/INSIGHTS.md`.

### Backup and Export (Roadmap Item 9)

- `core/backup/BackupCodec.kt` defines strict schema-v1 JSON and analysis CSV.
  `BackupRepository.kt` merges local records transactionally and journals
  preference application for resumable restoration. Room migration 8 -> 9 adds
  only `restore_settings`.
- App Setup provides system document pickers, import review, retry feedback, and
  separate history/task/preference/media controls. Exported files are plaintext;
  media, permission grants, live authority, and pending commands are excluded.
- Phone and Wear automatic-backup policies now explicitly exclude app storage;
  export is user initiated. `docs/BACKUP.md` documents the scope and CSV headers.
- Host tests cover validation, round trips, deduplication, rollback, journal
  replay, policy, and rendering. Native provider/device checks remain pending.

### Quick Settings Timer (Roadmap Item 10)

- `TimerRuntime.kt` owns one application-scoped engine, synchronization manager,
  timer/goal settings, snapshots, task selection, and focus accounting. The
  ViewModel delegates these responsibilities without extending sensor or phone
  panic-haptic registration beyond the visible Activity.
- `PomodoroTileService.kt` shows the live phase, start/pause action, and remaining
  minutes. Locked taps open controls without queuing a mutation; a fresh action
  after unlock is required. It exposes unavailable loading,
  pending-restore, and panic states. The system secondary action opens the phone
  controls through `QS_TILE_PREFERENCES`.
- `TimerForegroundService.kt` is a private, user-started `specialUse` host with
  an ongoing notification. Its restart attaches to the persisted timer instead
  of replaying a toggle. Foreground-start failures produce an error rather than
  silently leaving a new timer running.
- The Wear listener copies callbacks into the same runtime; the old Activity
  control broadcast and duplicate live listeners are removed. Applied command
  IDs commit with timer state, and failed writes restore the last committed
  snapshot. Interrupted reconciliation and restore cleanup finish safely.
- Monotonic catch-up records every completed focus interval, advances task
  selection between automated sessions, and emits only the latest recovered
  transition alert. Tile refreshes are coalesced at phase/running/minute changes.
- The full host gate passed 136 tests and all three APK builds. On Pixel 9 API 37,
  both QA lifecycle tests passed, including a real 60-second completion with the
  Activity closed, one history record, a running break, and its notification.
  A cold tile start recreated the phone process; a Pixel Watch 3 pause updated
  the tile and persisted the command. The user's lock/cancel/normal-unlock check
  left the corrected timer and revision unchanged; fresh unlocked controls
  passed afterward. See `docs/QUICK_SETTINGS.md` for remaining
  physical checks and foreground-service policy limits.

### Actionable Complications (Roadmap Item 11)

- All five owned providers guard advertised preview/runtime types and attach
  immutable navigation-only tap actions. Daily targets open their dedicated
  page; panic/logo content opens panic controls without sending a second command.
  The old `panic://trigger` entry point follows the same safe navigation policy.
- `WearComplicationState.kt` reads persisted timer anchors without network access
  or writes. Time-dependent text handles date/countdown display; API 33+ ranged
  progress uses a bounded platform-clock expression with an older-system static
  fallback. Idle retains -1 and range maxima retain the phase duration.
- Missing/stale sensor values, unavailable battery readings, and unsupported
  content return no data. Heart-rate samples require timestamps and expire after
  five minutes. Daily-target validity excludes its next reset instant.
- `ComplicationUpdates.kt` coalesces repeated callbacks and refreshes all relevant
  slots after timer, goal, config, text, or logo changes. It includes bottom-right
  and daily-target providers and does not push an update on every second tick.
  The system fallback period is 300 seconds, except for push-only logo content.
- The watch is unavailable for this pass. Host tests and build results are
  recorded in `docs/COMPLICATIONS.md`; on-device binding, rendering, and tap
  verification remain pending rather than being inferred from compilation.

### Watch Quick Actions (Roadmap Item 12)

- Timer commands and applied acknowledgements share one codec between phone and
  Wear. Existing `v2` messages remain compatible; durable command/acknowledgement
  DataItems add schema/identity validation and process-independent delivery.
- The Wear outbox uses an atomic journal, shared-instance locking, bounded
  capacity/backoff, explicit failed-action retry, and retained applied receipts.
  Its protected persisted job supplies background recovery without an Activity
  or a new foreground polling service.
- The phone runtime confirms durable effects after persistence. Distractions
  preserve capture time and reload their saved primary key before editing;
  repeated delivery cannot replace completed detail with a fresh pending row.
- The dot-matrix controls scroll on round displays, announce receipt changes,
  and separate local save, sent, applied, failed, and standalone feedback. Crown
  input scrolls the action list before moving to another page.
- Automated evidence and remaining physical checks are documented in
  `docs/WATCH_QUICK_ACTIONS.md`. The watch is unavailable for radio/haptic and
  system background-policy evaluation in this pass.

### Haptic Language (Roadmap Item 13)

- `WearHaptics` centralizes named patterns, live enable/intensity settings,
  foreground/capability guards, and shared debounce. Recovery cues and component
  presses no longer bypass those preferences.
- Main uses phase-specific cues and stable event IDs. The local countdown emits
  the five-minute warning even when no Data Layer update is needed. Ongoing
  panic/frustration feedback stops on Activity stop and is quiet during recovery.
- Only the background listener handles alert messages. Boot-aware remembered
  launches avoid replay after listener recreation; failed launches remain
  retryable. Phone action receipts remain separate from vibration feedback.
- Physical strength, pattern distinction, and ambient hardware evaluation are
  pending. Automated evidence is recorded in `docs/HAPTICS.md`.

### Watch-Face Presets (Roadmap Item 15)

- Protected built-ins and user CRUD persist all supported preset fields.
- Preset apply, drafts, replay, and reset use one ordered configuration writer;
  exact revision/timestamp snapshots are persisted before publication.
- Stale drafts/receipts and late failures cannot replace newer local settings.
  Receiver validation and one-file commit protect the previous watch config.
- Phone preview clears old custom overrides, renders stored palettes and owned
  content, and uses the same ambient content policy. Native colors and provider
  choices remain editor-owned; see `docs/PRESETS.md`.

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
   - Filtered focus metrics, daily/weekly totals, incident categories, and lazy focus history.

### Watch (`:wear`) Target Files
1. **`wear/src/main/java/com/example/wear/MainActivity.kt`**
   - Main wearable companion, state consumer, haptic coordinator, stress alert layout, and circular edge progress arc.
2. **`wear/src/main/java/com/example/wear/WearSyncService.kt`**
   - Background WearableListenerService capturing direct synchronization and stress messages.
3. **`wear/src/main/java/com/example/wear/PomodoroProgressComplicationService.kt`**
   - Service broadcasting pomodoro countdown data ranges to the active watch face.
4. **`watchface/src/main/res/raw/watchface.xml`**
   - Authoritative WFF v2 layout. Interactive timers, arcs, imagery, and complications hide in ambient; an independent dim minute-precision clock remains visible.
5. **`wear/src/main/java/com/example/wear/BottomRightComplicationService.kt`**
   - Owned bottom-right complication provider whose rendered value is controlled by phone-pushed watch face config.
