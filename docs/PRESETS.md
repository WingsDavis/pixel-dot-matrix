# Watch-Face Presets

Original, Terminal, High Contrast, and Focus are protected built-ins. User
presets can be saved, duplicated, renamed, and deleted. Names are trimmed,
nonempty, and limited to 64 non-control characters. Deleting a saved preset
does not reset the currently selected face. Presets contain colors, custom
text, logo visibility, and owned complication content modes, not a second copy
of the logo image or a third-party provider selection.

## Applying a Preset

One serialized transaction selects the preset, assigns a fresh revision and
monotonic persisted timestamp, saves the complete configuration, then publishes
that same snapshot. Older queued drafts are invalidated. One live editor model
updates immediately and ignores older writer echoes; separate field copies
cannot resurrect a stale draft during preset hydration. A later manual edit
remains unsynced and cannot be marked applied by an earlier receipt. Live backup
restoration writes through the same queue, preserving its journal on failure.
Save/send failures remain visible, and a stalled send is bounded to 15 seconds
so later edits are not blocked indefinitely. The saved configuration survives
send failure and can be replayed. Local ordering metadata is excluded from
backups and user preset copies.

The watch accepts supported schemas only, rejects an older timestamp or a
legacy schema replacing v2, and treats the same revision idempotently. Its
configuration is committed in one preference transaction. Failed writes restore
the prior in-memory values, do not refresh complications, and report failure.
The custom-text provider reads this canonical configuration instead of requiring
a second preference-file write.

## Ownership

- App-owned settings: custom text, owned left/bottom complication content, and
  logo visibility. A watch receipt confirms their persistence, not pixel output.
- Phone preview: the selected preset's saved hour/minute/second palette. Applying
  it clears a previous custom preview override. Session previews use phase colors;
  ambient preview uses the independent dim clock introduced in item 14.
- WFF-owned settings: native editor color choices, third-party/system provider
  bindings, and the ambient layout. The app does not silently change those.
- The legacy ambient preference is retained for backup/wire compatibility but
  does not offer a nonfunctional phone toggle. Ambient uses the safe WFF clock.

New face installations default the bottom slot to **Pomodoro Face Status**, with
system day/date as fallback. Existing installations retain the user's provider
selection; choose that owned provider in the watch-face editor to use its phone
content settings. A phone preset cannot replace a system/third-party provider.

## Verification

Automated checks cover preset CRUD/recreation, built-in protection, invalid
names, atomic save/publish, stale drafts and acknowledgements, timeout recovery,
clock rollback with retained phone data, receiver schema/order/write failures,
and native phone-preview pixels. WFF v2 schema and memory validation remains in
CI. Physical editor/provider binding and actual watch output remain pending.
