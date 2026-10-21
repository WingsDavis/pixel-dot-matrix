# Pixel Dot Matrix Feature Roadmap

Last updated: 2026-10-21

This roadmap tracks the 20 planned product improvements across the mobile app,
Wear app, Watch Face Format bundle, synchronization layer, and local data model.

## Status Legend

- `[ ]` Not started
- `[-]` Partial foundation exists
- `[~]` In progress
- `[x]` Complete and verified
- `[!]` Blocked by a documented dependency or platform limitation

An item is complete only when its acceptance criteria pass on the Pixel phone and
Pixel Watch, relevant automated tests pass, and `IMPLEMENTATION.md` is updated.

## Progress Summary

| Phase | Items | Complete | In progress | Partial | Not started |
|---|---:|---:|---:|---:|---:|
| 1. State and reliability | 1-5 | 5 | 0 | 0 | 0 |
| 2. Phone productivity | 6-10 | 2 | 3 | 0 | 0 |
| 3. Wear experience | 11-14 | 0 | 3 | 0 | 1 |
| 4. Watch Face Studio | 15-17 | 0 | 3 | 0 | 0 |
| 5. Protection and privacy | 18-20 | 0 | 3 | 0 | 0 |
| **Total** | **20** | **7** | **12** | **0** | **1** |

## Master Tracker

| ID | Feature | Priority | Status | Depends on |
|---:|---|---|---|---|
| 1 | Persist timer settings | P0 | `[x]` | None |
| 2 | Shared timer authority | P0 | `[x]` | 1 |
| 3 | Reliable offline synchronization | P0 | `[x]` | 2 |
| 4 | Watch-face configuration persistence | P0 | `[x]` | 1, 3 |
| 5 | Session automation | P1 | `[x]` | 1, 2 |
| 6 | Compact task queue | P1 | `[x]` | 1 |
| 7 | Daily focus target | P1 | `[~]` | 1, 2 |
| 8 | Useful insights | P1 | `[~]` | 7 |
| 9 | Backup and export | P2 | `[~]` | 1, 8 |
| 10 | Android Quick Settings tile | P1 | `[x]` | 2 |
| 11 | Actionable complications | P1 | `[~]` | 2, 3, 7 |
| 12 | Watch-side quick actions | P1 | `[~]` | 2, 3 |
| 13 | Better haptic language | P1 | `[~]` | 1, 5 |
| 14 | Ambient safeguards | P0 | `[ ]` | 4 |
| 15 | Watch-face theme presets | P1 | `[~]` | 4 |
| 16 | Logo controls | P2 | `[~]` | 4 |
| 17 | Custom color support | P2 | `[~]` | 4, WFF constraints |
| 18 | Domain management | P1 | `[~]` | 1 |
| 19 | Unified incident capture | P1 | `[~]` | 2, 3 |
| 20 | Privacy dashboard | P1 | `[~]` | 1, 9, 18 |

## Phase 1: State and Reliability

### 1. Persist Timer Settings `[x]`

**Scope**
- Add mobile DataStore preferences for focus duration, short break duration,
  long break duration, long-break cadence, and automation settings.
- Hydrate `PomodoroEngine` before rendering the timer.
- Persist changes without resetting unrelated preferences.
- Add a schema version and migration path from current in-memory defaults.

**Acceptance criteria**
- [x] All durations survive process death, reboot, and application updates.
- [x] Invalid or old values migrate to safe bounded defaults.
- [x] Unit tests cover defaults, writes, reads, and migration.
- [x] Settings UI reflects persisted values on first composition.

### 2. Shared Timer Authority `[x]`

**Existing foundation**
- Phone and Wear already exchange timer state and control messages.
- Both devices can currently issue commands.

**Scope**
- Introduce `TimerSnapshot`: revision, source node, state, remaining time,
  running flag, session ID, updated-at timestamp, and monotonic anchor.
- Make the phone authoritative while connected; allow a leased Wear authority
  when standalone.
- Resolve stale and simultaneous commands deterministically.
- Calculate elapsed time from anchors instead of relying on message frequency.

**Acceptance criteria**
- [x] Phone and watch differ by no more than one second after reconciliation.
- [x] Stale revisions cannot overwrite newer state.
- [x] Start, pause, skip, reset, and phase selection work from either device.
- [x] Reconnecting after standalone use produces one deterministic state.
- [x] Tests cover conflict, stale message, reconnect, and clock drift cases.

### 3. Reliable Offline Synchronization `[x]`

**Existing foundation**
- Persistent DataItems, urgent writes, revisions, and watch acknowledgements exist
  for watch-face configuration.

**Scope**
- Add an outbox for timer commands, configuration, incidents, and tasks.
- Track `Pending`, `Delivered`, `Applied`, `Failed`, and retry count.
- Coalesce superseded timer/configuration updates.
- Retry on node connection, app resume, and bounded backoff.

**Acceptance criteria**
- [x] Changes made offline apply after reconnection without user repetition.
- [x] Duplicate delivery is idempotent.
- [x] UI distinguishes local save from watch application.
- [x] Failed operations expose a retry action and useful error reason.
- [x] Integration tests cover disconnect, reconnect, and duplicate delivery.

### 4. Watch-Face Configuration Persistence `[x]`

**Existing foundation**
- The Wear app stores configuration in SharedPreferences.
- Colors, custom text, logo, and acknowledgement paths exist.

**Scope**
- Persist the phone editor model in DataStore.
- Load the last applied configuration when Watch Face Studio opens.
- Add `Reset to Default` for colors, text, logo, and visibility.
- Record last local revision and last watch-applied revision separately.

**Acceptance criteria**
- [x] Editor state survives process death and reboot.
- [x] Reset removes the custom logo and restores `ws_logo`.
- [x] The phone shows when local and watch configurations differ.
- [x] Reinstalling/updating WFF does not silently erase the phone preset.

### 5. Session Automation `[x]`

**Scope**
- Add automatic break start and automatic focus restart toggles.
- Make long-break cadence configurable from 2-8 completed focus sessions.
- Separate manual Next cycling from automatic session cadence.
- Add per-transition sound, notification, and haptic preferences.

**Acceptance criteria**
- [x] Manual Next always cycles Focus -> Short -> Long -> Focus.
- [x] Automatic completion follows configured cadence.
- [x] Automation works with the app backgrounded and after process recreation.
- [x] Phone and watch transition once, without duplicate notifications.

## Phase 2: Phone Productivity

### 6. Compact Task Queue `[x]`

**Scope**
- Add a Room task entity with title, order, status, estimate, and timestamps.
- Build compact add, reorder, complete, skip, and select interactions.
- Associate each session record with a stable task ID.
- Sync only current/next task summaries to Wear.

**Acceptance criteria**
- [x] Users can prepare and reorder tasks quickly.
- [x] Completing a focus session can advance to the next task.
- [x] Deleted tasks do not invalidate historical session records.
- [x] Long titles truncate safely on the watch.

### 7. Daily Focus Target `[~]`

**Scope**
- Support target type: focused minutes or completed sessions.
- Store target, timezone, and reset boundary.
- Add a compact home metric and progress complication data.

**Acceptance criteria**
- [x] Daily progress is correct across midnight and timezone changes.
- [x] Editing a target does not rewrite historical sessions.
- [ ] Phone and watch show the same target progress.
- [x] Users can disable the target entirely.

**Verification**
- DataStore persistence, active-time recording, calendar/DST boundaries, both
  target modes, disabled/expired Wear snapshots, and phone control interaction
  are covered by automated tests.
- Process recreation retains active focus, replays pending pauses/completions
  once, and preserves old history through the additive Room 7 -> 8 migration.
- The phone UI has a rendered 360dp large-text check. All three APK modules build.
- On 2026-10-21, a QA-only fixture on Pixel 9 (API 37) published a session target
  of 3 with one completed focus. Pixel Watch 3 (API 37) stored the same enabled
  target, completed count, focus time, timezone, and reset boundary.
- Visible watch target and watch-face complication refresh still need a
  connected-device check. Keep this item in progress until that passes.
- Wear displays the latest phone-authored goal snapshot while disconnected and
  expires that snapshot at its daily reset boundary.

### 8. Useful Insights `[~]`

**Existing foundation**
- Session and panic history, resilience scoring, and incident records exist.

**Scope**
- Add daily/weekly focus minutes, completion rate, uninterrupted-session average,
  distraction categories, and panic recovery time.
- Define metrics centrally instead of calculating them directly in composables.
- Add date range and task/category filters.
- Redesign with compact retro metrics and dense history rows.

**Acceptance criteria**
- [x] Every metric has one documented formula in `docs/INSIGHTS.md`.
- [x] Empty, partial, and large datasets render correctly.
- [x] Metric tests use fixed timestamps and expected results.
- [x] Screen remains responsive with at least 10,000 local records.

**Implementation notes**
- Date presets/custom dates, recorded-task filters, and incident-category filters
  are implemented without a destructive history migration.
- Focus totals count saved interrupted work and clip at calendar boundaries;
  completion explicitly measures ended focus intervals. Recovery is panic-only.
- Calculations run off the main thread. Daily/weekly summaries and every matching
  history record are available, with lazy searchable filters and history rows.
- Fixed-time logic, isolated picker search/selection, and 10,000-record
  aggregation/lazy-navigation tests pass. Render checks cover 360dp at 1.3x/2x
  text and landscape. The device dialog integration test compiles in CI.
- Pixel 9 API 37 passed the native date/task/category picker integration,
  weekly/reset controls, empty and incident-only states, and 10,000-record
  navigation/search on 2026-10-21. Last-row navigation took 120 ms. The fixture
  tests do not modify the normal app's history.
- TalkBack remains unverified. The host renderer's native-dialog idling problem
  does not reproduce in the physical-phone picker test. Keep the overall item
  in progress until the accessibility check is complete.

### 9. Backup and Export `[~]`

**Scope**
- Export sessions, incidents, tasks, and settings as versioned JSON.
- Export analysis-friendly session and incident CSV files.
- Add Android Storage Access Framework import/export.
- Validate imports before changing the database; use a transaction.

**Acceptance criteria**
- [x] Exported CSV opens correctly with stable UTF-8 headers.
- [x] JSON round-trip restores supported local data.
- [x] Invalid or newer schemas fail without partial import.
- [ ] Users can separately delete history, preferences, and media.

**Implementation and verification**
- Versioned JSON, UTF-8 CSV, system file pickers, pre-import validation, and a
  confirmation preview are implemented. See `docs/BACKUP.md` for the contract.
- Record merge and a durable pending-settings journal commit together. Settings
  replay after interruption without importing live authority or command queues.
- Granular deletion reports completion/failure after local writes; remote logo
  reset is explicitly queued rather than falsely confirmed.
- Host tests cover round trips, hostile input, CSV escaping/formula protection,
  duplicate delivery, rollback, settings replay, and backup exclusions.
- Native file-provider, disk-full/process-kill, and connected-watch application
  remain device checks. Keep the feature in progress until those pass.

### 10. Android Quick Settings Tile `[x]`

**Scope**
- Add a `TileService` with start/pause as the primary action.
- Use secondary launch action for phase and panic controls.
- Reflect current timer phase and running state.

**Acceptance criteria**
- [x] Tile state updates after phone or watch commands.
- [x] Action works when the application UI is closed.
- [x] Locked-device behavior is explicit and secure.
- [x] Tile does not create duplicate timer engines.

**Implementation and verification**
- One application-scoped runtime backs the tile, phone UI, foreground host, and
  background Wear listener. Sensor and panic haptics remain UI-owned.
- Host tests cover cold hydration, persisted command deduplication, foreground
  denial, post-memory failed commits, canceled reconciliation/restoration,
  queued settings, automation/task accounting, and bounded tile refreshes.
- The full gate passed 133 phone and 3 Wear tests plus all three APK builds.
- Pixel 9 API 37 passed both QA lifecycle tests on 2026-10-21: closing the UI
  retains the timer, duplicate pause is harmless, and a real 60-second focus
  completes once into a running break with one saved interval and notification.
- The real system tile recreated the phone process and started the timer. A
  Pixel Watch 3 API 37 pause changed its label to `Focus start` and persisted the
  command. The secondary action resolves to the phone controls.
- The user's lock/cancel/normal-unlock retest passed after removing deferred
  toggles. The timer remained paused with identical seconds, revision, and
  timestamp. Fresh unlocked tile Start/Pause then worked. Locked taps open
  controls without scheduling a timer mutation.
- Notification denial, long-duration Doze/OEM battery restrictions, and Play
  `specialUse` approval are not established by the physical checks. See
  `docs/QUICK_SETTINGS.md`.

## Phase 3: Wear Experience

### 11. Actionable Complications `[~]`

**Existing foundation**
- Progress, custom text, bottom-right status, and logo providers exist.

**Scope**
- Add daily target and next-break provider modes.
- Add tap actions that open the correct Wear screen or perform safe controls.
- Keep third-party provider selection owned by the Pixel Watch editor.

**Acceptance criteria**
- [ ] Providers return valid preview and runtime data for every declared type.
- [ ] Taps work from interactive mode without duplicate commands.
- [ ] Missing phone connectivity produces useful standalone data.
- [ ] Provider updates are requested after every relevant state change.

### 12. Watch-Side Quick Actions `[~]`

**Existing foundation**
- Wear supports start, pause, skip, phase commands, and panic interactions.

**Scope**
- Consolidate actions into one compact Wear control surface.
- Add `Mark distraction` with deferred phone detail entry.
- Disable or explain actions that cannot be completed offline.

**Acceptance criteria**
- [ ] Every action provides immediate visual and haptic acknowledgement.
- [ ] Offline actions reconcile through the outbox.
- [ ] No action triggers twice from repeated Data Layer delivery.
- [ ] Controls fit small and large round Wear displays.

### 13. Better Haptic Language `[~]`

**Existing foundation**
- Focus, panic, and frustration flows already use vibration.

**Scope**
- Define named patterns for focus complete, short break, long break, warning,
  panic, success, and command failure.
- Add watch-side intensity and enable/disable preferences.
- Centralize haptic dispatch and debounce.

**Acceptance criteria**
- [ ] Patterns are perceptibly distinct on the Pixel Watch.
- [ ] Preferences persist and apply without restart.
- [ ] Ambient/background restrictions are handled without crashes.
- [ ] Duplicate messages cannot replay alerts within the debounce window.

### 14. Ambient Safeguards `[ ]`

**Scope**
- Audit burn-in movement, pixel ratio, and static bright regions.
- Hide seconds and nonessential imagery in ambient mode.
- Reduce complication update frequency and redraw work.
- Add battery-aware behavior where supported without misleading time display.

**Acceptance criteria**
- [ ] WFF validates and remains nonblank in interactive and ambient modes.
- [ ] No important content clips on supported round dimensions.
- [ ] Twenty-four-hour ambient soak test shows no renderer failures.
- [ ] Battery usage is compared before and after the change.

## Phase 4: Watch Face Studio

### 15. Watch-Face Theme Presets `[~]`

**Existing foundation**
- WFF flavors and independent hour/minute/second color configurations exist.

**Scope**
- Add phone presets: Original, Terminal, High Contrast, Focus, and user presets.
- Save name, colors, text, logo visibility, and app-owned complication content.
- Support duplicate, rename, apply, and delete for user presets.

**Acceptance criteria**
- [ ] Built-in presets cannot be accidentally deleted.
- [ ] Applying a preset produces one versioned configuration transaction.
- [ ] Editor preview exactly reflects the stored preset fields.
- [ ] WFF-owned and app-owned settings are clearly distinguished.

### 16. Logo Controls `[~]`

**Existing foundation**
- Phone image picker, PNG resizing, Data Layer asset transfer, Wear storage,
  fallback `ws_logo`, and SMALL_IMAGE provider exist.

**Scope**
- Add crop, pan, zoom, fit/fill, transparent background preview, and reset.
- Store original and rendered metadata without retaining unnecessary media.
- Validate dimensions, decoded memory use, and transfer size.

**Acceptance criteria**
- [ ] Portrait, landscape, square, transparent, and large images render safely.
- [ ] Reset deletes custom media and immediately restores `ws_logo`.
- [ ] Preview and watch use the same crop transform.
- [ ] Corrupt or unsupported images fail with a useful message.

### 17. Custom Color Support `[~]`

**Existing foundation**
- Twenty declared WFF color presets exist for hour, minute, and second.

**Scope**
- Add hex/RGB entry, recent colors, and contrast warnings on the phone.
- Determine whether arbitrary colors can be represented by the deployed WFF
  schema. If not, keep WFF presets authoritative and label custom colors as
  preview-only until rendering architecture changes.

**Acceptance criteria**
- [ ] Valid hex/RGB input is normalized consistently.
- [ ] Low-contrast colors receive a warning without blocking deliberate use.
- [ ] The UI never claims a custom WFF color was applied when it was not.
- [ ] Architecture decision is documented before implementation is marked done.

## Phase 5: Protection and Privacy

### 18. Domain Management `[~]`

**Existing foundation**
- The local DNS VPN parses queries and blocks a hardcoded domain set.

**Scope**
- Store user-editable domain rules and named blocking profiles.
- Normalize IDN, subdomain, casing, trailing-dot, and duplicate input.
- Add temporary unblock with an explicit countdown and audit event.
- Apply a selected profile automatically during focus sessions if enabled.

**Acceptance criteria**
- [ ] Valid domains block without breaking unrelated DNS traffic.
- [ ] Invalid rules cannot enter the active matcher.
- [ ] Temporary unblock automatically expires after process recreation.
- [ ] Tests cover DNS parsing, normalization, matching, and forwarding.

### 19. Unified Incident Capture `[~]`

**Existing foundation**
- Phone incident auditing, panic records, and Wear panic messaging exist.

**Scope**
- Create a single versioned incident entity and stable incident ID.
- Let Wear capture a minimal event; complete details later on the phone.
- Merge panic, distraction, and frustration sources without duplicate records.

**Acceptance criteria**
- [ ] One event produces one incident across both devices.
- [ ] Offline Wear incidents sync and remain editable on the phone.
- [ ] Dismissal and completion states reconcile deterministically.
- [ ] Existing panic logs migrate without data loss.

### 20. Privacy Dashboard `[~]`

**Existing foundation**
- The app is local-first and has permission and local-data controls.

**Scope**
- Show sensor, overlay, VPN, activity, notification, and Wear connectivity state.
- Explain data categories and storage locations in concise language.
- Add granular deletion for sessions, incidents, tasks, settings, and logo media.
- Add open-source license and source repository entries.

**Acceptance criteria**
- [ ] Permission state refreshes after returning from system settings.
- [ ] Every displayed data category has a working deletion path.
- [ ] Destructive actions require confirmation and report completion.
- [ ] Dashboard makes no unsupported privacy or security claims.

## Delivery Milestones

### Milestone A: Reliable Core
- Items 1-4 complete.
- Timer survives restarts and reconciles after offline Wear operation.

### Milestone B: Configurable Sessions
- Items 5, 7, 13, and 14 complete.
- Automation, cadence, feedback, and ambient behavior are device-verified.

### Milestone C: Productive Workflow
- Items 6, 8, 9, and 10 complete.
- Tasks, goals, insights, export, and system-level controls are usable.

### Milestone D: Complete Wear Experience
- Items 11, 12, and 19 complete.
- Complications, quick actions, and incidents work online and offline.

### Milestone E: Studio and Protection
- Items 15-18 and 20 complete.
- Watch Face Studio, DNS profiles, and privacy controls are release-ready.

## Tracker Maintenance Rules

1. Change an item to `[~]` only when implementation work has started.
2. Change an item to `[x]` only after every acceptance criterion is checked.
3. Record blockers inline and use `[!]`; do not hide them in commit messages.
4. Update the summary counts whenever an item status changes.
5. Add links to tests, screenshots, or device notes beneath the relevant item.
6. Keep unrelated refactors outside feature completion counts.
