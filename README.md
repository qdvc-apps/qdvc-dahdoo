# QDVC Dahdoo

An Android app for catching a baby's or toddler's babbles the moment they
happen. Open it, tap the huge button, tap again. The recording is saved
straight away; who it was, what they were doing and what they seemed to say can
be filled in later, whenever there's a free hand.

Part of the [QDVC suite](https://github.com/qdvc-apps). Built to the conventions in
[qdvc-android-app-specification](https://github.com/qdvc-apps/qdvc-android-app-specification),
with the deviations listed [below](#deviations-from-the-specification).

## What it does

- **Record, fast.** The app opens on a single screen with a button about 58% of
  the screen's width. Nothing is loaded before that button is usable: the
  recordings list and the folder check happen in the background. Recording
  starts into app-private storage, so it never waits on the storage framework or
  on a folder being chosen.
- **"Record now" shortcut.** Long-press the launcher icon (or drag the shortcut
  to the home screen) to open the app already recording.
- **Saved without asking.** Stopping saves the recording and shows a small
  "Saved · Add details" card. Nothing has to be filled in.
- **Keeps going in the background.** If the screen turns off or you switch apps
  mid-babble, a foreground service keeps the microphone open, with a Stop
  button in the notification.
- **Catalogue later.** On the Recordings tab, each recording can be given:
  which child (from a list in Settings), what they were doing (with one-tap
  suggestions from your past answers), what they seemed to be saying, notes,
  and a favourite star. If the child's birthday is set, their age at the time
  of the recording is shown ("7 months, 12 days old").
- **Default child.** New recordings can be assigned to a child automatically;
  the first child you add becomes the default.
- **Play, share, delete.** Play back in the list or the detail view, share the
  audio to another app, or delete it (with confirmation).
- **Safety limits.** Recordings stop themselves after 10 minutes by default
  (2, 5, 10, 30 minutes or no limit), and the screen is kept on while
  recording (switchable).

## Your data, in your folder

Everything the app keeps lives in **one folder you choose**, as plain files, so
any sync or backup app can mirror it to a network drive:

```
<your folder>/
  dahdoo-settings.json                 children, birthdays, preferences
  dahdoo-README.txt                    explains the layout to a future reader
  recordings/
    2026-11-23_14-05-33.m4a            the audio (AAC, mono, 44.1 kHz, 128 kbps)
    2026-11-23_14-05-33.json           its catalogue details
```

- File names are the date and time of recording and **never change**: editing
  details only rewrites the small `.json` beside the audio, so a backup mirror
  never sees renames.
- Unknown keys in either JSON file are preserved when the app saves, so files
  written by a newer version, or edited by hand, survive.
- Choosing a folder that already contains Dahdoo files (a restored backup, or
  another phone's folder) picks up its settings and recordings.
- Until a folder is chosen, or while it can't be reached, recordings wait safely
  in app-private storage, marked "On phone", and are copied in automatically
  later.

The only things kept outside the folder are a pointer to it, a copy of the
settings (so the first frame can be themed instantly), and a cache of the
recordings list. All are disposable.

### Sidecar format

```json
{
  "format": "qdvc-dahdoo-recording",
  "version": 1,
  "audioFile": "2026-11-23_14-05-33.m4a",
  "recordedAt": "2026-11-23T14:05:33+11:00",
  "durationMs": 12345,
  "childId": "c-1a2b3c4d",
  "doing": "bath time",
  "saying": "dah doo",
  "notes": "",
  "favourite": false
}
```

`recordedAt` keeps the UTC offset it was recorded with. `childId` refers to an
entry in `dahdoo-settings.json`.

## No network, ever

The app has **no network permission**. The manifest also strips `INTERNET`,
`ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` with `tools:node="remove"`, so a
library can't merge them back in. Android's own cloud backup and device
transfer are switched off for the app's private storage. To verify a built APK:

```
aapt dump permissions app-release.apk
```

The output should list only `RECORD_AUDIO`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MICROPHONE` and `POST_NOTIFICATIONS`.

## Permissions

| Permission | Why | When it's asked |
| --- | --- | --- |
| Microphone | Recording | The first time you tap the button (the recording then starts as soon as you allow it) |
| Notifications (Android 13+) | Showing the Stop button while recording in the background | Only if you tap "Allow" in Settings > Recording |

## Building

Open the project in Android Studio, or from the command line:

```
./gradlew assembleRelease     # signed with the debug key; installable as-is
./gradlew testDebugUnitTest   # pure-Kotlin tests: ages, names, codecs, suggestions
```

Judge start-up speed on a **release** build. Debug builds of Compose apps are
several times slower to reach their first frame.

### Toolchain

| Piece | Version |
| --- | --- |
| Android Studio | **Panda 4 (2025.3.4) / Quail 1 (2026.1.1) or newer** (oldest that can open AGP 9.2) |
| Gradle JDK | 17 to 26 (Studio's bundled JDK is fine) |
| Gradle | 9.4.1 |
| Android Gradle plugin | 9.2.1, with built-in Kotlin |
| Kotlin Gradle plugin and Compose compiler plugin | 2.3.10 (pinned together) |
| Compose BOM | 2024.09.02 |
| SDK | `minSdk` 26, `compileSdk`/`targetSdk` 34; Java/Kotlin bytecode 17 |

If Studio reports that the project's Gradle version is incompatible with the
Gradle JVM, or that the AGP version is too new for this Studio, compare your
Studio version (Help > About) with the first row. See
`cursed-knowledge-reports/` for why these versions fit together.

## Deviations from the specification

The specification describes folder-backed plaintext *editors*. Dahdoo shares its
foundations (Kotlin, Compose, Material 3, a single Activity, one ViewModel, SAF
tree access, haptics, insets, motion) but not its shape:

- **Two tabs, not four.** Record (slot 1, home) and Recordings. There is no
  multitasking switcher, because there are no open documents.
- **Settings live in the user's folder**, not DataStore, as the brief requires.
  SharedPreferences holds only the folder pointer and a launch-time mirror.
- **No Room index.** A small JSON cache of the folder listing gives an instant
  list at launch; reconciling needs one directory listing and re-reads only
  sidecars whose last-modified time changed. Room's start-up cost and KSP build
  step weren't worth it for this data size.
- **No JSON themes, fonts or text-size settings** (typography tier: none). One
  built-in palette with light, dark and automatic modes.
- **No shipped manual test plan yet.**
