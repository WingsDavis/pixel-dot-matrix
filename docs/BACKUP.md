# Backup and Export

App Setup offers a versioned JSON backup and separate session/incident CSV files.
Android's Storage Access Framework chooses the destination or input document;
no broad storage permission is requested.

## Format and Privacy

JSON schema version 1 contains sessions, incidents, all task statuses, timer
preferences, daily targets, face preferences, user presets, recent colors, and
named protection profiles. All supported record fields, nullable values, stable
IDs, Unicode, timestamps, and notes are retained.

It excludes logo image files, live timer state/authority, pending sync commands,
applied watch revisions, temporary VPN unblocks, active VPN state, and Android
permission grants. Face settings receive a fresh local revision when restored;
the phone never treats a backup revision as a watch acknowledgement.

Backups and CSV files are **unencrypted** and may contain incident notes, app
names, heart-rate readings, and task names. The user-selected document provider
may be local or cloud-backed. Exported files remain outside app storage; deleting
app data does not delete them. Keep exports in a trusted location.

Both app manifests disable automatic Android backup. Explicit old/new backup
rules exclude phone and Wear app storage from cloud backup and device transfer.
This does not remove copies previously exported or previously backed up.

## Import Safety

- The entire input is bounded to 16 MiB and validated before confirmation or
  database writes. Unknown versions/fields, duplicate identifiers, malformed
  UTF-8/JSON, incorrect types, unsupported enums, and invalid settings fail.
- Confirmation lists record counts and warns that preferences are replaced.
- Existing records win on stable-ID conflicts. New records are merged in one
  Room transaction, without deleting current history or tasks. Legacy sessions
  and migration-era incident IDs receive versioned SHA-256 identities derived
  from their original ID and persisted fields so unrelated installations do not
  collide and repeated imports do not duplicate unchanged records.
- A small `restore_settings` journal is committed in that same transaction.
  Preference stores cannot share a Room transaction; the journal makes their
  application idempotent and retryable after interruption or process death.
- Preference IO failures are reported as pending, not as a complete restore.
  Retry finishes the operation; the next app startup also attempts recovery.
  Another import/export is blocked while settings remain pending.
- Restored timer preferences affect future timing without rewinding a running
  timer. Logo media and system permissions must be selected/granted separately.
- Room migration 8 -> 9 only adds the settings journal; it does not rewrite or
  remove existing records.

## CSV Contract

Files use UTF-8, CRLF row endings, a header even when empty, quoted text cells,
and doubled embedded quotes. Text that could start spreadsheet formulas is
apostrophe-prefixed; numeric columns are unchanged. CSV is for analysis, not
backup import. Times are epoch milliseconds and durations are seconds.

Session headers:
```text
id,sessionType,startTimeMillis,durationSeconds,status,taskId,taskName,timestamp,recordId
```

Incident headers:
```text
id,timestamp,heartRateSpike,stepsTaken,resolvedAt,targetApp,triggerReason,forensicNotes,taskName,incidentId,incidentType,sourceDevice,incidentStatus
```

## Deletion

App Setup separately deletes session history, incident history, tasks, timer/
daily-target preferences, face preferences/presets, and protection profiles.
Local completion or failure is reported after the write. A custom-logo reset is
reported as **queued for the watch**, not confirmed deleted while disconnected.
Deleting tasks does not erase task-name snapshots in historical records.

## Verification

```sh
./gradlew --no-parallel --console=plain :app:testDebugUnitTest --tests '*Backup*'
```

Tests cover full-field round trips, CSV escaping and formula safety, malformed
input, unsupported versions, duplicate imports, legacy IDs, transaction rollback,
journal replay, additive schema creation, backup policy, and large-text controls.
System document-provider interaction, disk-full interruption, and physical
phone/watch application still require device validation.

Platform references: [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files),
[Android backup rules](https://developer.android.com/identity/data/autobackup).
