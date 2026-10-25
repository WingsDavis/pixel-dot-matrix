# Logo Controls

The phone keeps one normalized source image and its framing metadata. It does
not retain the picker URI, original filename, raw import, or a second full-size
photo. Source images are sampled to a maximum 2048-pixel edge and 16 MiB decoded
allocation. Raw imports are limited to 32 MiB, with header limits checked before
pixel decoding. Temporary input files are removed after processing.

Fit, fill, zoom, horizontal/vertical pan, and transparent or black padding use
`WatchFaceLogoRenderer`. The 128 x 128 rendered bitmap is both the phone preview
and the PNG sent to the watch; the preview does not apply another crop or zoom.
Transparency preserves existing alpha and empty padding, not background removal
from an opaque photograph. Android 9 and later use strict ImageDecoder decoding
and embedded orientation handling. Android 7/8 retain the bounded BitmapFactory
and EXIF compatibility path; their platform decoder may recover partially
damaged input rather than report it. Unsupported metadata is ignored after the
image itself has decoded successfully.

## Persistence and Transfer

- Immutable `source-UUID.png` files are selected by an atomic, versioned manifest.
  The old source is retired only after the new manifest commit is verified.
- One pending transfer record retains its exact rendered PNG, revision,
  monotonic timestamp, checksum, attempt count, and receipt state. This small
  derivative is needed for retries; older operations are replaced, not archived.
- Publication is serialized separately from local edits. A stalled send has a
  deadline and cannot stop a later reset. Retries retain the same operation ID
  and bytes. A receipt for an older crop cannot mark a newer local crop applied.
- The watch validates schema, checksum, image size, PNG bounds, and ordering.
  Image and revision are committed together in one bounded atomic record.
- An applied receipt is emitted only after storage succeeds. It confirms stored
  artwork, not that the face is active or a third-party provider was replaced.
- Old 256-pixel transfers without a schema marker are safely normalized. Existing
  legacy image files have bounded reads/sampling; successful updates retire them.
- A newer reset tombstone rejects late uploads. The phone writes its reset
  intent before deleting source files, and finishes interrupted cleanup before
  preview/replay on restart. Local mutations run in invocation order. A later
  image selection cannot be deleted by an older failed cleanup retry.
- Reset removes the phone source and pending image copy. If local storage fails,
  the durable intent remains recoverable and the UI reports failure instead of
  claiming that the watch was cleared.

The sync operation uses `/pomodoro/watchface/logo/v1`; stored-state receipts use
`/pomodoro/watchface/logo/status/v1`. It is retained by the Data Layer while
offline. The app retries pending operations when a peer is reachable and offers
explicit retry after an error. No receipt is treated as applied merely because
the phone queued a request.

## Provider and Tap Behavior

The owned Logo provider renders stored artwork with `ws_logo` as fallback.
Hidden logos return a transparent SMALL_IMAGE with no tap action: the deployed
AndroidX provider library rejects EMPTY for this request type. There is no
separate invisible panic launch overlay. The last interactive WFF slot owns the
visible logo's existing navigation-only tap action. Panic still requires a
separate explicit action in the watch app.

The watch-face editor controls provider bindings. Select the owned Logo provider
to use phone artwork; the phone does not replace another provider silently.
The phone preview uses the same built-in `ws_logo` asset as the Wear provider.

## Privacy and Verification

Automatic backup and device transfer exclude app files. Manual JSON/CSV exports
do not include artwork. Reset removes owned source images and image bytes in
the transfer journal; the non-image reset receipt/order metadata remains for
idempotence. This is normal app-data deletion, not forensic storage erasure or a
promise to erase operating-system transport caches.

Automated checks cover geometry, alpha, bounds, corrupt images/metadata, source
recreation, preview/export pixels, failed writes, duplicate/stale operations,
legacy migration, pending retries, checksums, cancellation, and reset ordering.
Physical phone-to-watch transfer, actual crop appearance, provider binding,
hidden-logo taps, and offline reinstall/reset reconciliation still need a watch.
If ordering metadata on both devices is lost or corrupt, historical order cannot
be reconstructed automatically; reset with a correct phone clock and reapply.

The receiver releases asset descriptors, including late task completion after a
timeout. See the official [asset response contract](https://developers.google.com/android/reference/com/google/android/gms/wearable/DataClient.GetFdForAssetResponse)
and [listener threading contract](https://developers.google.com/android/reference/com/google/android/gms/wearable/WearableListenerService).
