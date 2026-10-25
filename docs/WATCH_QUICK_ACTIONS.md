# Watch Quick Actions

Focus, Break, and Distraction keep the compact dot-matrix control surface. The
screen scrolls on round displays and shows receipt state separately from the
timer. The crown scrolls the action list before paging away at its boundaries.

## Receipt States

- `QUEUED`: saved on the watch; application by the phone is not confirmed.
- `DELIVERED`: a connected peer accepted a message, not proof of application.
  The UI labels this `SENT` and waits for confirmation.
- `APPLIED`: the phone acknowledged completion after its durable write.
- `FAILED`: the phone rejected the action, or bounded retries were exhausted.
  The reason remains visible and Retry preserves the command ID.

Every tap provides local feedback immediately. Pending/sent feedback does not
pretend the timer or incident was applied. Failed actions are retained rather
than disappearing when a `FAILED` acknowledgement arrives. Earlier failures
remain reachable in the scrollable list.

Standalone timer actions retain the existing Wear-authority lease and persistent
timer snapshot. They show `ON WATCH`, not `APPLIED` on the phone; reconnection
reconciles that state instead of replaying a fresh Start that resets the phase.

## Durable Transport

`WearCommandOutbox` atomically writes a private versioned journal before sending.
Instances share one journal lock and observable receipt stream. Pending commands
are bounded to 128 and the journal to 512 KiB; capacity errors preserve existing
data and do not send an unsaved action. Twenty applied receipts are retained for
feedback. Corrupt or newer journal versions are not silently reset.

The original `wear_command_outbox/items` preference set migrates with stable IDs.
Its iteration order is not treated as an original command sequence. The old set
is retained for recovery, not reread after the journal exists. Legacy commands
without capture times use receipt time on the phone; new distractions preserve
their actual capture timestamp.
The legacy-message fallback includes that timestamp for updated phones. Older
phone versions still retain the stable event ID but may use arrival time.

Each action publishes an urgent DataItem under `/pomodoro/command/v1/<id>` with
`schema_version`, `command_id`, `path`, `payload`, and `created_at`. An attempt
counter/timestamp changes on retry. The payload remains the existing `v2` timer
command encoding, also sent over the original message route for mixed-version
compatibility.

The phone's application-scoped runtime validates identity, version, route, and
payload before applying it. It uses the existing persisted command deduplication;
incident capture additionally uses the unique incident ID. A durable receipt is
published under `/pomodoro/command_ack/v1/<id>` only after the effect is saved.
An updated timestamp permits re-acknowledgement after receiver interruption.
Applied command/acknowledgement DataItems are removed after the watch receipt is
persisted; failures remain queued for user review rather than being deleted.

A protected `JobService` supplies bounded background retry even when the Wear
Activity is absent. Its periodic job survives reboot and has no phone-network
constraint because Data Layer can use the paired-device transport. The minimum
15-minute fallback is not an exact latency guarantee; normal buffered DataItems
sync when connectivity returns. Force-stopping an app prevents normal background
delivery until the user reopens it. No foreground polling service is added.
When the system stops a retry job, the flush stops before starting another
transport operation; an operation already handed to the platform can finish.

See the official [DataItem guide](https://developer.android.com/training/wearables/data/data-items)
and [JobInfo builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder).

## Verification

Tests cover legacy migration, shared instances, recreation, offline publication,
bounded retries, duplicate IDs/receipts, retained failures, explicit retry,
post-write rollback, queue limits, and cleanup. Phone tests cover durable command
application, duplicate delivery, failed foreground starts, identity validation,
and editable distraction row identity. Protocol tests include API 24 encoding.
Round render/interaction tests cover 192dp and 228dp displays and increased text.
Receipt selection is saved across Activity recreation, so retrying an older
failed action cannot be replaced visually by a newer action's applied receipt.
Recreation does not replay the previous receipt's haptic alert.

On 2026-10-25, 138 phone and 91 Wear tests passed, phone instrumentation compiled,
and all three APKs built. Native round renders at 192dp/228dp with 1.3x text were
inspected. Task context follows the primary actions, status and retry stay
reachable, and individual haptic controls scroll within the round viewport.

Physical Activity-closed reconnection, radio delivery, haptic perception, and
system power-policy behavior remain pending because the watch is not connected.
