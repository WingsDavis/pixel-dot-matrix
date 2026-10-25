# Ambient Safeguards

The authoritative face is `watchface/src/main/res/raw/watchface.xml`, not a
resource inside the executable Wear app. Its WFF v2 ambient presentation is a
single dim dot-matrix clock on black. It uses hour/minute sources only, with no
dependency on a complication, phone connection, running timer, or logo asset.
Every interactive root group and complication slot is hidden in ambient mode.

The clock's 250 x 90 text rectangle occupies at most 14.15% of the 450px circular
canvas even if every pixel in that rectangle were lit. This is a conservative
geometry bound, not a screenshot measurement. Tests check its corners at 192,
228, and 450px, including a physical +/-10px shift in either direction. Content
has ample margin for system burn-in shifting; the face does not add independent
jitter. Actual screen behavior still requires hardware validation.

Seconds, progress arcs, branding, custom text, date, and session countdowns are
interactive-only. Ambient always shows current wall-clock time, never a stale
second-precision countdown. Wear OS controls ambient refresh and power policy;
the face does not invent a low-battery time or stop the system clock. Provider
periods remain push-only or five minutes, with time-dependent countdown text and
coalesced updates rather than per-second provider refreshes.

## Automated Evidence

- Google's WFF validator 1.7.0 accepts the XML as format v2.
- The official memory evaluator reports 145,660 ambient bytes and 456,148 active
  bytes for the debug APK, below the 10/100 MB limits.
- Structural/geometry tests prevent imagery, seconds, or complication data from
  leaking into the ambient subtree and guard provider update periods.
- CI and preview releases download the official memory evaluator, verify its
  SHA-256, and validate schema plus memory before publishing an APK. A changed
  upstream binary fails closed until its checksum is deliberately reviewed.
- The schema audit corrected unsupported Font/shape variants, required group
  names, provider fallback types, complication bounds, static text templates,
  and text ellipsis syntax. Android packaging alone did not detect these errors.

See Google's [ambient guidance](https://developer.android.com/training/wearables/wff/ambient),
[memory limits](https://developer.android.com/training/wearables/wff/memory-usage),
[debugging guide](https://developer.android.com/training/wearables/wff/debug),
and [official tools](https://github.com/google/watchface).

## Physical Checks Still Required

Confirm nonblank interactive/ambient rendering, all four complication bindings,
editor previews, round-screen clipping, and logo tap priority. Compare battery
use against the prior APK using the same brightness, AOD, radio, and workload
settings. Run a 24-hour ambient soak and inspect renderer failures. Automated
schema/geometry/memory checks do not establish any of these device outcomes.
