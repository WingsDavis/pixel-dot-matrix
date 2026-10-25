# App-Owned Complications

The Wear app supplies progress, custom text, bottom-right status, daily target,
and logo data. System and third-party provider selection remains in the Wear OS
or Pixel Watch editor; the phone never silently replaces those providers.

## Data Contract

- Progress advertises `RANGED_VALUE`; its minimum is -1, its maximum is the
  configured phase duration, and -1 represents idle or an expired timer. Modern
  Wear evaluates the remaining-time expression against the platform clock.
  Pre-API-33 devices use
  a static fallback refreshed by state events and the system's bounded schedule.
- Custom text advertises `SHORT_TEXT` and `LONG_TEXT`. Modes include saved text,
  phase, measured heart rate, next break/focus countdown, and daily target.
  Hidden or unavailable modes return `NoData`, not manufactured measurements.
- Bottom-right status advertises `SHORT_TEXT`: date, phase, timer, measured local
  battery percentage, or daily target. Date and countdown use time-dependent
  complication text rather than per-second provider requests.
- Daily target advertises `SHORT_TEXT` and uses the same persisted phone-authored
  goal as the Wear target screen. Its validity ends before the next reset instant.
- Logo advertises `SMALL_IMAGE`, respects visibility, and falls back to `ws_logo`
  when decoding fails. Transfer validation and crop controls are tracked by item 16.
- Unsupported requests return `null`; supported but unavailable content returns
  `NoData`. Heart-rate data requires an actual timestamped sample and expires
  after five minutes. No complication starts a sensor session.

## Tap Behavior

Pending intents are immutable, destination-specific, and navigation-only.
Timer/phase content opens the current timer page; daily-target content opens the
target page; logo and panic content open panic controls. Repeated taps may bring
that screen forward, but cannot start duplicate sessions or incidents. The user
must press the explicit timer or panic control to mutate state.

The legacy `panic://trigger` logo entry point follows the same navigation-only
policy. It no longer sends an unqueued panic message independently of the UI.

## Refresh Behavior

State changes refresh all timer-dependent providers, including bottom-right.
Target changes refresh the dedicated target and both configurable slots. Face
configuration changes refresh every owned provider. Logo and legacy text changes
refresh their own providers. Duplicate callbacks with identical persisted content
are coalesced, and standalone second ticks do not publish provider requests.
Heart-rate refreshes are bounded to five minutes; validity prevents a cached
measurement from remaining visible indefinitely.

The provider manifest uses a 300-second fallback interval except for the
push-driven logo. This is not a real-time polling guarantee. Time-dependent text
and dynamic ranged values supply smooth clock changes on supported systems.
See the official [data-source guide](https://developer.android.com/training/wearables/complications/exposing-data)
and [ranged-value builder API](https://developer.android.com/reference/androidx/wear/watchface/complications/data/RangedValueComplicationData.Builder).

## Verification

Host tests cover each declared type, unsupported and hidden content, navigation
intents, dynamic countdown presence, offline anchors, long elapsed time, actual
battery values, timestamped heart rate, daily expiry, logo fallback, and refresh
targets/coalescing. These tests do not prove rendering or binding on a watch.

On 2026-10-25, the host gates passed 133 phone tests and 39 Wear tests, compiled
phone instrumentation, and built all three APKs. The corrupt-image test uses
native bitmap decoding rather than the legacy desktop decoder stub. Timer
delivery tests reject stale/invalid payloads and coalesce duplicate refreshes.
Restoration persists a completed standalone phase even when its next phase is
paused. Expired phone-owned display state becomes idle without claiming timer
authority. Navigation survives canceled animations, and unchanged BPM readings
renew validity when a new timestamped sample arrives.

Remaining physical checks: select each provider in the watch editor, verify
interactive tap routing, compare target progress with the phone, disconnect the
phone and check persisted content, and verify dynamic progress on the selected
WFF face. Do not treat an APK build as successful WFF runtime validation.
