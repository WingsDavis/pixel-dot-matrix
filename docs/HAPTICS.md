# Wear Haptic Language

Haptics are optional feedback, not proof that a phone action was applied. The
quick-action receipt is authoritative for queued/sent/applied/failed status.

## Preferences and Dispatch

`WearHaptics` owns enable/intensity settings and vibration dispatch. Settings
are observable across instances so the watch controls update without restarting.
Explicit stored choices are retained; defaults are softer than the old fixed
160/255 amplitude. Overrides may soften a cue but cannot exceed the selected
ceiling. Zero intensity or disabled feedback does not play an effect.

Settings-write failures retain the last confirmed settings and report an error
instead of crashing the UI or claiming a successful save. Missing or failing
vibrators and permission/vendor errors are handled without interrupting controls.

Named patterns cover navigation, focus complete, short/long break, five-minute
warning, frustration, panic pulse, recovery, success, and failure. Navigation
uses a supported low-tick primitive where possible and a short bounded fallback.
Devices without amplitude control do not receive long full-strength pulses
disguised as low-amplitude effects.

See the official [haptics API guide](https://developer.android.com/develop/ui/views/haptics/haptics-apis)
and [custom effect guidance](https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects).

## Lifecycle and Duplication

App-owned vibration is foreground-only. The main Activity stops ongoing loops
when it stops; the dispatcher also checks foreground/interactivity before playing.
Recovery uses the same preference-respecting dispatcher and pauses the escalating
panic loop, rather than combining both vibrations.

The background Wear listener is the sole message ingress for alerts. Successful
launches are remembered only after the Activity accepts their one-use token;
silently blocked or denied launches remain retryable. Tokens expire after five
seconds and cannot apply a delayed intent or acknowledge a later retry. Transient
launch failures omit the terminal receipt so the phone's outbox can retry.
Lifecycle entry also restarts eligible loops after a rapid stop/start.
Raw legacy alerts use a short boot-aware debounce window. Existing
envelope IDs remain acknowledged idempotently.

The dispatcher coalesces repeated patterns across instances and deduplicates
discrete stable event IDs. Crown ticks and repeated panic pulses do not cause
per-tick disk writes. Phase transitions and five-minute crossings use timer
session identifiers; command results use command ID plus result status.
The local countdown emits its warning without waiting for a phone broadcast.
Restarting/resetting an interval rotates that session ID; pause/resume does not.
Loop cancellation cannot cancel a separate one-shot phase cue. Changing alert
kind replaces the previous loop instead of retaining its pattern.

## Verification

Automated tests cover pattern mappings, preferences, write failure, capability
fallback, intensity limits, disabled recovery, background suppression, shared
debounce, listener recreation, failed alert launch, and vendor exceptions.
These tests cannot establish perceptual strength or comfort.

Remaining physical checks: compare cues on Pixel Watch, evaluate the soft crown
tick and the three session cues, change intensity during recovery, disable all
feedback, background/ambient the app, and repeat a delivered alert. Do not mark
the item fully verified without that device evaluation.
