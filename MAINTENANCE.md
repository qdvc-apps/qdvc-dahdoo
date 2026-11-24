# Maintaining QDVC Dahdoo

## Map

```
app/src/main/java/qdvc/dahdoo/android/app/
  DahdooApp.kt          Application (does nothing at start-up) + AppContainer (process singletons)
  MainActivity.kt       host: edge-to-edge, permissions, folder picker, shortcut, AppRoot
  AppViewModel.kt       all UI state and callbacks; navigation; playback
  model/                plain data types (Child, AppSettings, RecordingMeta, Recording, Nav)
  util/                 pure Kotlin, unit-tested: Ages, Names, Durations, Suggestions
  data/
    Codecs.kt           JSON for settings, sidecars and the private cache (unit-tested)
    FolderStore.kt      every SAF call
    SettingsRepository  folder pointer + settings; folder is the source of truth
    RecordingRepository catalogue: cache, pending queue, reconcile, edits, deletes
    Messages.kt         snackbar messages; every failed write posts here
  record/
    RecordingController MediaRecorder on its own thread; state + level flows
    RecorderService     foreground service (microphone) that keeps the process alive
  playback/Player.kt    one MediaPlayer with audio focus
  ui/                   theme, shared components, record/recordings/settings screens
```

Reading rule: *what state exists* is in `AppViewModel` and `AppContainer`; *how
it is stored* is in `data/`; screens only draw state and call back.

## The start-up path (protect it)

The brief's first requirement is that the button is usable almost instantly.

1. `DahdooApp.onCreate` does nothing. `AppContainer` is lazy.
2. `MainActivity.onCreate` calls `setContent`. The first frame needs only the
   settings mirror from SharedPreferences (one small file read) and the
   recorder's `Idle` state.
3. `AppViewModel.init` calls `AppContainer.start()`, which loads the cache and
   pending queue, then syncs settings with the folder and reconciles the
   recordings, all on `Dispatchers.IO`.

Don't add anything to steps 1 and 2 that touches SAF, the network (there is
none), a database, or a large file. If something must exist before the first
frame, mirror it into SharedPreferences.

`profileinstaller` installs the baseline profiles that ship inside the Compose
and AndroidX AARs, which matters for a sideloaded app that never receives Play's
cloud profiles. A custom baseline profile for this app (via a macrobenchmark
module) would help further.

## The record path

Tap -> `RecordingController.start()` sets `Starting` synchronously (the button
reacts on the next frame), then on the recorder thread: create a file in
`filesDir/pending/`, configure, `prepare()`, `start()`, set `Recording`, start
`RecorderService`. Stop -> `stop()`, `release()`, hand a `FinishedRecording`
to `RecordingRepository.addFinished`, which writes the local sidecar, shows the
recording at once, and copies both files into the folder. The local copy is
deleted only once both files are in the folder.

Traps here:

- `MediaRecorder.stop()` throws `RuntimeException` if no audio was captured (a
  very quick double tap). That's caught and reported as "too short".
- After `setMaxDuration` fires, the platform may already have stopped and
  finalised the file, so `stop()` may throw even though the file is good. The
  controller keeps the file if it has content.
- MPEG-4 writes its index at `stop()`. A recording cut off by the process dying
  may be unplayable; it is kept, with a note saying so, rather than silently
  dropped.
- The foreground service must start while the app is visible (Android 12+).
  It's started right after capture begins, from the user's tap.

## SAF rules (see also spec B3 and Cursed Knowledge)

- All access through `FolderStore`. Never `java.io.File` on the user's folder,
  never `DocumentFile`.
- Stay inside the granted tree; children by document id.
- Create documents with MIME `application/octet-stream` so providers don't
  append an extension (older Android maps `m4a` to `audio/mpeg`, so asking for
  `audio/mp4` could produce `x.m4a.mp4`). `createExactly` also checks the
  resulting name, since providers may de-duplicate with ` (1)`.
- Overwrite with mode `"wt"`, never `"w"`: some providers don't truncate on `"w"`.
- Never persist document ids (they change on rename and leak paths). Names are
  the identity; ids are resolved on demand and cached in memory only.
- A folder that can't be read is **not** an empty folder: on failure the last
  known list stays and the folder is marked unavailable.

## Changing the file formats

Both JSON formats carry `format` and `version`. Encoders overlay their keys on
the previous file contents, so unknown keys survive. When adding a field:
add it to the model, both codec directions and `CodecsTest`, and keep reading
files that lack it.

## Testing

`./gradlew testDebugUnitTest` covers the pure parts. What needs a device, by hand:

- Cold start to button on a release build; the "Record now" shortcut, both cold
  and with the app already open.
- First tap with no microphone permission (should record once allowed); denying
  twice (banner with "Open settings").
- Recording before any folder is chosen, then choosing one (the recording should
  lose its "On phone" pill).
- Locking the screen mid-recording (keep-screen-on off), then stopping from the
  notification.
- Hitting the time limit.
- Editing details, backing out with unsaved changes, and checking the sidecar in
  a file manager.
- Choosing a folder that already has Dahdoo files.
- Removing the folder's permission in system settings, or ejecting the SD card
  it's on, then reopening the app.
- Dark mode on every screen (Compose text outside a Surface goes black).
- Three-button navigation as well as gestures (system-bar colours).

## Changing toolchain versions

Gradle, AGP, Kotlin and Android Studio constrain each other, so don't bump just
one of them. Start from the oldest Android Studio you need to support, then
work through:

1. Studio version → highest AGP it can open (Android Developers' "Android Gradle
   plugin and Android Studio compatibility" table).
2. AGP version → minimum Gradle version (the same page) and the Kotlin Gradle
   plugin it depends on (that AGP version's release notes).
3. Gradle version → which JDKs it can run on (Gradle's compatibility matrix).
   It must run on Studio's Gradle JDK, which is the bundled JDK unless someone
   changed it. JDK 25 needs Gradle 9.1 or later.
4. Keep `kotlin-gradle-plugin` on the `buildscript` classpath and
   `org.jetbrains.kotlin.plugin.compose` at the **same** version.

Raising `compileSdk` or the Compose BOM is a separate change: newer AndroidX
libraries require a newer `compileSdk`.

## Do not

- Add an `applicationIdSuffix`: the static shortcut names the package literally.
- Add any networking dependency, or remove the `tools:node="remove"` lines.
- Re-add the `org.jetbrains.kotlin.android` plugin or a `kotlinOptions` block:
  AGP 9 compiles Kotlin itself, and both break its DSL.
- Raise `targetSdk` to 35 without reading the Cursed Knowledge entry on the
  navigation-bar scrim; `Theme.kt` uses the SDK 34 approach.
