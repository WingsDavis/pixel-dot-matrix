# Useful Insights

Insights reads local Room history without modifying records. Calculations live
in `InsightsCalculator`; the ViewModel runs them on `Dispatchers.Default` when
history or filters change and once per minute while Insights is subscribed.
The screen renders the result and lazily composes history and filter options.

## Dates and Filters

- Today, last 7 days, and last 30 days include the current local calendar day.
  Custom dates include both selected dates. All time has no lower date bound.
- Windows are half-open: `[local start midnight, midnight after the last date)`.
  Calendar arithmetic respects the device's time zone and daylight saving time.
  Date-picker UTC midnights represent dates, not local instants.
- Recorded focus time is clipped to the window and the calculation's current
  time. Future records are excluded. Running, unsaved focus is not projected.
- Task filters match trimmed **names saved in history**, including a separate
  No task option. They do not join to the editable task queue: old, renamed, or
  deleted tasks remain available, and equal recorded names are grouped together.
- Incident category is the trimmed audit reason, or Uncategorized if absent.
  That filter affects incident metrics only, not focus totals or focus history.
- Dismissed incidents are excluded from incident metrics. Missing audit details
  remain visible under Uncategorized. Non-panic incidents never count as panic
  recoveries or unresolved-panic penalties.

## Metric Formulas

All divisions truncate toward zero. Durations are summed with 64-bit integers.

| Metric | Formula |
|---|---|
| Focus minutes | `sum(overlap seconds of saved FOCUS intervals and selected window, capped at now) / 60`. Completed and interrupted work both count; breaks do not. |
| Completed intervals | Number of matching FOCUS records with `status == COMPLETED` and an end timestamp inside the selected window, no later than now. |
| Completion | `completed intervals * 100 / all ended focus intervals`. No ended intervals displays `--`, not a misleading 0%. |
| Uninterrupted average | `sum(full positive durations of ended focus intervals) / their count`, displayed as minutes and seconds. Uses completed and interrupted intervals; no durations displays `--`. |
| Panic recovery | `sum((resolvedAt - timestamp) / 1000) / valid resolved panic count`. A valid resolution is not before the incident or after now. No valid resolutions displays `--`. The incident start must be in the selected window; its resolution can be later. |
| Fast recoveries | Count of valid panic recoveries shorter than 180 seconds. Exactly 180 seconds is not fast. |
| Resilience | `max(0, completed intervals * 10 + fast recoveries * 5 - unresolved non-closed panics * 5)`. This is an app score, not a clinical assessment. |
| Incident categories | Count of non-dismissed incidents per reason after date, task, and incident-category filters. Sort by count descending, then name ascending for ties. Show the top five; every category remains searchable in the filter. |
| Daily focus | The same focus-overlap seconds grouped by local calendar day, divided by 60 per displayed bucket. Show the latest seven days within the selected range, including zero days. |
| Weekly focus | The same seconds grouped into Monday-start local weeks, divided by 60 per displayed bucket. Show the latest eight weeks within the selected range. First and last buckets are clipped to the date range and now. |

A pause ends a saved focus interval. Resuming starts another, and only an
actually completed countdown marks its final interval completed. Therefore
completion is an **interval** rate, not a count of reconstructed logical
Pomodoros. Legacy records use their existing start, duration, status, and end
timestamp unchanged. No migration guesses missing pause or task identity data.

History includes every matching focus record with time overlapping the range
or an end timestamp inside it, newest first. Row durations show full saved
intervals; the total clips intervals at date boundaries. Daily totals use local
midnight, independently of a configurable Daily Focus goal reset hour.

## Verification

Run the logic and UI tests:

```sh
./gradlew --no-parallel --console=plain :app:testDebugUnitTest --tests '*Insights*'
```

Add `-Proborazzi.test.record=true` to render checks under
`app/build/reports/insights/`. Tests cover empty and incident-only data, date
boundaries, task/category scope, DST, Monday weeks, invalid recovery times,
64-bit totals, 10,000 records, filter scope, and lazy history navigation.
Rendering covers a 360dp phone at 1.3x and 2x text and a landscape phone.
Picker-content tests check search, selection, duplicate labels, and no matches.

Native dialog-window interactions are in `InsightsInteractionTest` under
`app/src/androidTest`. The host renderer stalls on dialog-window idling even
though the same picker content passes independently, so it does not substitute
for that device test. CI compiles the device test but does not execute it.
With a phone connected, run:

```sh
./gradlew --no-parallel --console=plain :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.ui.screens.InsightsInteractionTest
```

The 10,000-record calculation has a generous 2-second regression ceiling to
accommodate CI variance; this is not a physical-device frame-time benchmark.
On-device scrolling, native task/category/date-picker interaction, and TalkBack
still require a connected phone check before item 8 is marked fully verified.
