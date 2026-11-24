# A new Android Studio runs Gradle on JDK 25, which Gradle 8.x can't run on, and the fix means AGP 9

- **Date encountered:** 2026-11-24
- **Reported by:** Claude Opus 5.5, with the project owner
- **Project visibility:** Public
- **Project:** `qdvc-dahdoo-android`
- **Platform:** Android build tooling: Gradle and Kotlin
- **Versions:** A current Android Studio whose Gradle JDK was JDK 25 (exact Studio version not recorded). Project as generated: Gradle 8.9, AGP 8.5.2, Kotlin 2.0.21 with the Compose compiler plugin, Compose BOM 2024.09.02, compileSdk/targetSdk 34. After the fix: Gradle 9.4.1, AGP 9.2.1, Kotlin Gradle plugin and Compose compiler plugin 2.3.10.
- **Log link:** Pending: qdvc-dahdoo-android, vibe-coding/2026-11-23-claude.md, UR.2
- **Status:** Fix not yet confirmed
- **Related entry:** The Compose compiler plugin needs Kotlin 2.0

## Symptom

The first Gradle sync in Android Studio failed before any build script ran:

> The project's Gradle version Gradle 8.9 is incompatible with the Gradle JVM version 25. To fix this, select a JVM version that is at least 8 and at most 22.

The project's toolchain had been copied from a sibling QDVC app known to build (`qdvc-csv-android`: Gradle 8.9, AGP 8.5.2, Kotlin 2.0.21), as the related entry advises. That configuration was correct when the sibling was built, but too old for the Android Studio this project was opened in.

## Cause

Confirmed from the Gradle and Android Developers compatibility tables, not by bisecting Studio versions.

- Android Studio runs Gradle on its **Gradle JDK** setting, which by default is the JDK bundled with Studio. The user's Studio used JDK 25.
- Each Gradle release can only *run* on JVMs up to a certain version. Gradle 8.9 tops out at 22 (as the error says). Daemon support for JDK 24 arrived in 8.14, JDK 25 in 9.1.0, and JDK 26 in 9.4.0.
- So the wrapper has to move to Gradle 9.1 or later, which pulls in the rest of the toolchain. AGP 8.x isn't built for Gradle 9. AGP 9.0 is the first version that requires it (minimum 9.1.0), and newer AGP raises the floor: 9.1 needs Gradle 9.3.1, 9.2 needs 9.4.1, and 9.4/9.5 need 9.6.0.
- AGP 9 switches on "built-in Kotlin" and its new DSL by default. The `org.jetbrains.kotlin.android` plugin is incompatible with the new DSL, and `android { kotlinOptions { } }` no longer exists. AGP 9 has a runtime dependency on the Kotlin Gradle plugin (2.2.10 in 9.0; the AGP 9.2 notes mention updating that dependency to 2.3.10), and the Compose compiler plugin must match whatever Kotlin version ends up on the build classpath.
- Studio also caps the AGP version it can open. Panda 4 and Quail 1 (2026.1.1) accept AGP up to 9.2; Quail 2 and 3 up to 9.3; Quail 4 and Rabbit 1 up to 9.4; Rabbit 2 up to 9.5. Choosing the newest AGP can therefore produce the mirror-image error on a slightly older Studio.

## Fix

Not yet confirmed by a successful sync:

- `gradle/wrapper/gradle-wrapper.properties`: `gradle-9.4.1-bin.zip`.
- Top-level `build.gradle.kts`: AGP `9.2.1`. Remove `org.jetbrains.kotlin.android`. Pin `org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10` on the `buildscript` classpath, and declare `org.jetbrains.kotlin.plugin.compose` at the same `2.3.10`, so the Kotlin compiler and the Compose compiler plugin can't disagree.
- App `build.gradle.kts`: remove the `org.jetbrains.kotlin.android` plugin and the `kotlinOptions { jvmTarget = "17" }` block. Built-in Kotlin defaults its `jvmTarget` to `compileOptions.targetCompatibility` (17 here). Nothing else in the `android { }` block needed changing.
- The libraries (Compose BOM 2024.09.02) and compileSdk 34 were left alone. Newer AndroidX releases require a newer compileSdk, so upgrading them is a separate step.

A quicker alternative that keeps the old toolchain: in Studio, **Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK**, pick a JDK 17 or 21. That changes `.idea/gradle.xml` on one machine only, so it doesn't fix the project for anyone else. It also leaves the project on an AGP that newer Studios will eventually stop opening.

## Watch for

- **Ask for the Android Studio version (Help > About) before choosing toolchain versions**, then read across: Studio version → maximum AGP (Studio/AGP table) → minimum Gradle (AGP/Gradle table) → check that Gradle version runs on Studio's JDK (Gradle's Java compatibility table). Pick an AGP the user's Studio supports, not merely the newest one.
- Copying a sibling app's "known-good" versions, as the related entry advises, only works if the user's Studio is about as old as the one the sibling was built with. Sibling configurations age out silently.
- After moving to AGP 9, look for anything else that assumed AGP 8 defaults (see the AGP 9.0 release notes table), for example `android.r8.strictFullModeForKeepRules` (`-keep class A` no longer keeps its constructor) and `android.enableAppCompileTimeRClass`.
- Gradle 9.6 deprecates running on JDKs below 21, and Gradle 10 will require 21.

## Minimal example

```kotlin
// Top-level build.gradle.kts for AGP 9.2 with built-in Kotlin and Compose
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10") }
}
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    // removed: id("org.jetbrains.kotlin.android") version "…" apply false
}

// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // removed: kotlinOptions { jvmTarget = "17" }
}
```

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.4.1-bin.zip
```
