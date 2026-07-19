# AGENTS.md

Guidance for ZCode agents working in this repository.

## Project

MediaDedup — an Android app that finds duplicate media files (images, video, audio) on the
device and lets users reclaim storage by deleting them. UI is Jetpack Compose + Material 3,
with adaptive (list/detail) layouts for foldables/tablets. Single-module Gradle project
(`:app`), package `com.example.mediadedup`.

The project plan and per-task status live in `.agent/plan.md` (excluded from AI context via
`.agent/.aiexclude`). Check it for what has been completed and what is in progress before
starting a task.

## Build & test

Windows host, Gradle wrapper. From the repo root:

```bash
./gradlew assembleDebug            # build debug APK
./gradlew installDebug             # install on connected device/emulator
./gradlew test                     # JVM unit tests (app/src/test)
./gradlew connectedAndroidTest     # instrumented tests (app/src/androidTest, needs a device)
./gradlew lint                     # Android lint
./gradlew clean
```

Notes:
- `local.properties` (SDK path) is gitignored and machine-specific; don't edit or commit it.
- Gradle config cache is enabled (`org.gradle.configuration-cache=true`).
- Tests are currently just the Android Studio templates (`ExampleUnitTest`,
  `ExampleInstrumentedTest`) — there is no real coverage to regress against.

## SDK / toolchain

- `compileSdk = 37`, `targetSdk = 37`, `minSdk = 34`. Target modern Android only.
- Java 11 source/target. Kotlin 2.4.x via the compose plugin.
- KSP is used for Room compiler and Moshi codegen — run `assembleDebug` (not just
  `compileKotlin`) when changing `@Entity`/`@JsonClass` annotations so generated code is
  produced.
- Version catalog: `gradle/libs.versions.toml`. All dependency versions and aliases live
  there; reference them as `libs.<alias>` in `app/build.gradle.kts` rather than hardcoding.

## Architecture & layer rules

The codebase is small and intentionally flat. Respect these boundaries:

- `data/` - plain Kotlin models (`MediaFile`, `MediaAlbum`, `MediaStats`, `MediaType` enum).
  No Android framework deps beyond `Uri`. Keep models immutable where possible.
  **Note:** `MediaFile.hash` and `MediaFile.perceptualHash` are `var`, mutated in
  place by `MediaScanner` during the scan passes - preserve this or update all
  call sites. `MediaFile.uri` is **nullable** so JVM unit tests can construct
  `MediaFile` without a mocked Android `Uri`; production always supplies non-null
  (use `mapNotNull { it.uri }` when collecting uris for deletion).
  - `data/media/` - `MediaFingerprintReader` interface + `AndroidMediaFingerprintReader`
    (loadThumbnail for images, MediaMetadataRetriever mid-frame for video).
  - `data/cache/` - Room: `MediaFingerprintEntity` + `MediaFingerprintDao` +
    `MediaDedupDatabase` (singleton via `MediaDedupDatabase.get(context)`).
- `scanner/` - `MediaScanner` (MediaStore queries + MD5 + pHash on `Dispatchers.IO`),
  `ScannerViewModel` (single source of truth for UI state as `StateFlow`), and
  `SimilarMediaGrouper` (pure-Kotlin near-duplicate clustering: bucketing +
  representative-based grouping + keep-recommendation - no Union-Find).
- `navigation/` — `Route` sealed class. Uses **Jetpack Navigation 3** (`androidx.navigation3`)
  with a `NavBackStack` list, not Navigation Compose `NavHost`. Routes are
  `@Serializable` and implement `NavKey`.
- `settings/` — `SettingsManager` wraps DataStore Preferences. Keys: `languagePreference`
  (String), `nearDuplicateEnabled` (Boolean, default **false**). Read via `Flow`,
  collected in `MainActivity` and passed into `ScannerViewModel.startScan`.
- `ui/screens/` — Compose screens. `MainActivity.kt` (root package) owns the NavDisplay,
  permission gating, and the adaptive `ListDetailPaneScaffold`.
- `ui/theme/` — `MediaDedupTheme`, colors, typography. Dynamic color (Material You) is on
  for Android 12+; the hand-authored light/dark schemes are the fallback.
- `util/` — `Formatter.kt` (`formatSize` and similar pure helpers) and `PerceptualHash.kt`
  (pure-Kotlin pHash: 32×32 DCT-II -> 8×8 low-freq -> 64-bit `Long`; hamming distance via
  `Long.bitCount(a xor b)`; threshold `SIMILARITY_THRESHOLD = 8`). No Android deps.

UI state pattern: `ScannerUiState` is a sealed class. Add new states here rather than
ad-hoc flags. Current variants:
- `Idle`
- `Scanning`
- `Hashing(current, totalPotential, totalFiles)` — MD5 exact-dedup pass.
- `PerceptualHashing(processedFiles, candidateFiles)` — pHash pass; only entered when
  `startScan(enableNearDuplicate = true)`.
- `GroupingSimilarContent(processedBuckets, totalBuckets)` — bucketing + representative
  clustering in `SimilarMediaGrouper`.
- `Finished(...)`
- `Error(message)`

## MediaStore & deletion gotchas

These are the project's most sensitive areas:

- **Permissions** (`MainActivity`): on Android 13+ (Tiramisu) request
  `READ_MEDIA_IMAGES/VIDEO/AUDIO`; below that fall back to `READ_EXTERNAL_STORAGE`. The app
  is gated behind `permissionState.allPermissionsGranted` before any navigation.
- **Hashing**: duplicates are detected by grouping files of identical `size`, then computing
  MD5 only on those candidates (`MediaScanner.calculateHashes`). Audio files are not filtered
  by `bucket_id` (see the `type != MediaType.AUDIO` guard in `queryMediaStore`).
- **Deletion** (`ScannerViewModel.deleteSelectedFiles`): on Android 11+ (R) deletion must go
  through `MediaStore.createDeleteRequest` and the resulting `IntentSenderRequest` is exposed
  via `pendingDeleteRequest` StateFlow, which `ResultsScreen` launches via
  `StartIntentSenderForResult`. On Android 10 (Q) it catches
  `RecoverableSecurityException` the same way. Below Q it deletes directly. Always preserve
  this flow — direct `contentResolver.delete` will throw on scoped storage for files the app
  didn't own.
- After a successful delete, `onDeletionComplete()` is called with the set of deleted IDs.
  It filters deleted IDs from `_allFiles`/`_fullMediaList` AND from `_similarGroups`, then
  recomputes `duplicateGroups`/`emptyFiles`/stats from the remaining in-memory data (no
  full rescan). For similar groups it also: recomputes the keep-recommendation if the
  representative was deleted, recomputes `distanceToRepresentative` for survivors, and
  drops any group that collapses to ≤1 item. This is what makes the UI refresh instantly
  after select-all-and-delete without an app restart.

## Near-duplicate (pHash) pipeline

The exact-dedup pass (MD5) cannot find same-content/different-clarity media. The optional
pHash pass adds perceptual matching. It is **off by default** and toggled in Settings
(`SettingsManager.nearDuplicateEnabled`, default `false`); `MainActivity` collects the
value and passes `enableNearDuplicate` into `ScannerViewModel.startScan`. When off the
entire pipeline below is skipped and `_similarGroups` stays empty.

Pipeline (only when `enableNearDuplicate = true`), all on `Dispatchers.IO`:

1. **MD5 exact pass first** (unchanged) - produces `duplicateGroups`.
2. **Cache lookup** (`MediaScanner.calculatePerceptualHashes`): batch-load
   `MediaFingerprintEntity` rows by `mediaId` from Room. A row is reused iff
   `mediaId + fileSize + modifiedAt + perceptualHashVersion` all match. Stale or missing
   rows are recomputed; the batch is upserted at the end. **Never** recompute a valid row -
   pHash (especially video frame extraction) is the expensive part.
3. **pHash compute** (`AndroidMediaFingerprintReader`):
   - IMAGE: `contentResolver.loadThumbnail(uri, Size(64,64), CancellationSignal())`
     (minSdk 34, so no `BitmapFactory` fallback is needed).
   - VIDEO: `MediaMetadataRetriever` with `OPTION_CLOSEST_SYNC` at `positionUs =
     durationMs * 1000 / 2` (mid-frame). Always `release()` in `finally`.
   - Both: scale to 32×32, BT.601 luma `0.299R+0.587G+0.114B`, then `PerceptualHash.compute`.
4. **Exact-group collapse** (`SimilarMediaGrouper.collapseExactGroups`): files that are
   MD5-identical contribute **one** representative to the similar pool, so a 10-copy exact
   duplicate set doesn't spawn 10 near-duplicate candidates.
5. **Bucketing** (`BucketKey`): candidates are split by `orientation × aspectBucket ×
   durationBucket` so only plausibly-similar files are pairwise-compared, not O(n²) over
   everything. Neighbour buckets (±1 aspect, ±1 duration) are also checked.
6. **Representative-based clustering (NOT Union-Find)**: within a bucket, sort by
   `favorite desc -> pixels desc -> size desc -> id asc`. The first item is a group's
   representative; a candidate joins only if its hamming distance to the representative
   is ≤ `SIMILARITY_THRESHOLD` (8). **No transitive closure** - if A~B and B~C but
   A≁C, C does NOT join A's group. This prevents burst-photo chains from collapsing
   everything into one giant cluster. (`SimilarMediaGrouperTest.noTransitiveUnionFindChaining`
   pins this.)
7. **Keep-recommendation** (`withKeepRecommendation`): for each group, pick the keep item
   via `compareBy` ascending on `favorite -> isEdited -> pixels -> size -> isCameraOriginal`
   then `thenByDescending { dateAdded }` (earlier wins), then `maxWithOrNull`. Note the
   ascending comparator + `max` pairing - descending + max picks the wrong element.

UI (`ResultsScreen.kt`): the results page has separate sections for **exact duplicates**
and **similar images / similar videos**. Similar groups show `similar_group_title`,
`similar_max_distance`, per-item `distance_to_representative`, and
`estimated_reclaimable_space`. `select_recommended_items` selects non-keep items in
similar IMAGE groups (video stays manual because it's experimental - single-frame match).
Deletion flows back through the same `deleteSelectedFiles` -> `onDeletionComplete` path
as exact duplicates.

## UI / i18n conventions

- All user-facing strings must be in `res/values/strings.xml` and accessed via
  `stringResource(R.string.*)`. **Never hardcode user-facing text** in Composables.
- The app ships a Simplified Chinese locale (`res/values-zh-rCN/strings.xml`). When you add
  or rename a string, add the matching entry there too.
- Language is switched at runtime via `AppCompatDelegate.setApplicationLocales` (applied in
  `MainActivity.onCreate` using the DataStore value). `MainActivity` reads it with
  `runBlocking` on first launch — be careful adding more blocking work there.
- Theme uses `Theme.AppCompat.DayNight.NoActionBar` as the XML base (needed for
  `AppCompatDelegate` locale handling), even though the UI is Compose. Don't switch the
  manifest theme to a Material Components theme without preserving AppCompat locale support.

## Conventions

- Kotlin code style is `official` (set in `gradle.properties`).
- Compose `@Composable` screens take a `viewModel` + callback lambdas (e.g. `onBack`,
  `onStartScan`); they don't navigate directly. Navigation mutations happen in
  `MainActivity.AppNavigation`.
- Back navigation is manual: `backStack.removeAt(backStack.size - 1)` with a `size > 1`
  guard. Match this pattern when adding routes.
- `@OptIn(ExperimentalMaterial3AdaptiveApi::class)` / `ExperimentalPermissionsApi` are
  required for adaptive scaffold and Accompanist permissions APIs respectively.
- Adaptive layout: `DashboardScreen` + `DashboardDetailPane` use
  `ListDetailPaneScaffold`; on expanded widths category taps call `selectCategory` instead
  of pushing a route. Keep new detail content in the detail pane pattern.
- Coil `AsyncImage` loads image thumbnails; video thumbnails are fetched via
  `ContentResolver.loadThumbnail` on `Dispatchers.IO` (see `MediaFileRow`).
