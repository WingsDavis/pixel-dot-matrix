# Architecture

Pixel Dot Matrix is an Android multi-module project.

## Modules

- `app`: phone UI, timer authority, Room data, insights, DNS protection, and
  Watch Face Studio.
- `wear`: circular Wear UI, sensors, haptics, offline command queue, and
  complication providers.
- `watchface`: resource-only Watch Face Format v2 package.

## Synchronization

The phone and watch communicate through the Wear OS Data Layer.

- Persistent state and watch-face configuration use DataItems.
- Immediate controls use messages and durable outboxes for offline delivery.
- Timer snapshots include revisions, source, session ID, timestamps, and an
  authority marker to reject stale updates.
- Watch-face configuration is acknowledged by revision after Wear persistence.

## Phone Timer Ownership

`TimerRuntime` owns one process-wide engine, settings, task accounting, timer
snapshots, and Wear synchronization. The phone ViewModel, Quick Settings tile,
foreground timer host, and background Wear listener use that same runtime.
Only the visible phone UI registers its motion sensor and panic haptics.

The foreground host keeps a user-started countdown available after the Activity
closes. It restores state on restart instead of replaying a toggle. Monotonic
elapsed-time reconciliation accounts for delayed ticks; persisted snapshots and
stable focus-record IDs cover process recreation. See
[Quick Settings Timer](QUICK_SETTINGS.md) for platform limits and verification.

## Data

The phone stores sessions, incidents, and pending sync operations in Room.
Preferences and watch-face editor state remain local. The Wear app keeps the
latest synchronized timer and complication configuration in private storage.

## Watch Face Boundary

The WFF module renders declared colors and app-owned complication data. System
or third-party complication selection remains owned by the Wear OS watch-face
editor.
