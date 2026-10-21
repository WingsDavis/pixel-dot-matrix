# Quick Settings Timer

Roadmap item 10 uses one shared application-scoped timer authority. It does not
create an engine for each tile binding or foreground-service instance.

## Behavior

- The tile's primary action chooses start or pause from live timer state.
- A locked tap opens the phone controls after unlock without scheduling a timer
  mutation. Starting or pausing then requires a fresh action in the app or tile.
  Cancelling unlock cannot leave a deferred toggle for a later normal unlock.
- Loading and panic states are not ordinary start/pause actions.
- Secondary/long-click opens the phone controls for phase and panic actions.
- The Activity, tile, foreground timer host, and background Wear listener share
  one application-scoped timer runtime.
- Sensor listeners and phone panic vibration remain UI-owned; headless tile
  initialization must not register motion/activity sensors.
- Timer history, selected task accounting, snapshots, offline deduplication,
  and item 9's pending-settings recovery must retain their existing semantics.

## Android Constraints

The foreground timer host requires a justified `specialUse` service type and
must handle denied background starts. A `shortService` is not a replacement for
a full Pomodoro. Publishing on Google Play requires applicable foreground-service
declarations/review; this repository cannot guarantee store approval.

A foreground host does not itself guarantee exact wakeups in every battery
mode. Elapsed-time reconciliation must remain authoritative, and screen-off,
OEM battery restrictions, notification permission denial, and reconnect behavior
need physical-device verification.

## Device Checklist

1. Add the tile through the system Quick Settings editor.
2. Start from the tile with the phone Activity closed; reopen and compare state.
3. Pause from the watch and verify tile refresh; resume from the tile.
4. Lock the phone, invoke the tile, cancel unlock, then unlock normally and verify
   no timer state change. No timer callback may be queued across keyguard.
5. Tap Start after unlocking; verify exactly one command is applied.
6. Background/close the phone UI, let a focus phase finish, and check history,
   next phase, target progress, and a single transition notification.
7. Repeat with notification permission denied and battery restrictions enabled.
8. Recreate the foreground host; it must attach to state, not replay a toggle.

## Isolated Device Tests

Build phone and Wear with `-PisolatedQa=true` to use a separate `.qa`
application ID on both devices. The default development application ID does
not change. The two devices must use the same suffix for Data Layer pairing.

`TimerRuntimeDeviceTest` and `DailyGoalSyncDeviceTest` refuse fixture mutations
outside this QA package. `InsightsInteractionTest` and
`InsightsDatasetInteractionTest` use in-memory screen fixtures and do not write
history. Use the exact device serial for installation and instrumentation;
do not run the phone instrumentation package on the watch.

The QA tile is a separate system tile. Remove it and uninstall the QA phone,
test, and Wear packages after testing. Do not clear the normal app's storage.

## Verification

On 2026-10-21, the normal build passed 133 phone and 3 Wear host tests, compiled
the phone instrumentation package, and built all three APK modules. Regression
tests cover shared ownership, foreground denial, restart/deduplication, canceled
reconciliation and restore, post-memory failed commits, cold revision handling,
queued preference edits, task capture, and long-sleep accounting.

Pixel 9 API 37 passed both isolated lifecycle tests. A real 60-second focus
finished with the Activity closed, saved exactly one completed interval, started
the short break, and posted the expected transition notification. Closing the
UI retained one foreground host and engine; repeating a pause ID was harmless.

The real QA tile started a timer after the phone process was recreated. The
Pixel Watch 3 API 37 Pause control then changed the phone tile from `Focus pause`
to `Focus start`, and the phone persisted that command. The secondary action
resolved to `MainActivity`.

The initial unlock callback deferred a toggle until a later normal unlock after
the user canceled the prompt. Locked taps now open controls without queuing any
timer callback. The user's repeated lock/tap/cancel/normal-unlock check left the
timer paused at the exact same seconds, revision, and timestamp. Fresh unlocked
Start and Pause actions then passed. This behavior deliberately requires a new
action after unlocking rather than retaining a canceled toggle.

Notification-permission denial, long-duration Doze, OEM battery restrictions,
and an OS-killed running foreground-host restart were not physically tested.
Foreground denial and host reattachment/deduplication have host test coverage;
the `specialUse` publishing policy still requires applicable store review.

References: [Quick Settings tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles),
[foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
