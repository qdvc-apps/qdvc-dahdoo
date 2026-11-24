// Toolchain chosen for Android Studio's bundled JDK 25 (see README "Toolchain"
// and cursed-knowledge-reports/). In short:
// - Gradle runs on whatever JDK Android Studio is set to use, and recent Studio
//   releases ship JDK 25. Gradle only runs on JDK 25 from 9.1 onwards.
// - AGP 8.x doesn't support Gradle 9, so the build needs AGP 9. AGP 9.2 is the
//   newest version that Android Studio Panda 4 and Quail 1 (2026.1.1) can open,
//   and it needs Gradle 9.4.1 (which also runs on JDK 26).
// - AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the
//   org.jetbrains.kotlin.android plugin is gone. AGP 9.2 depends on Kotlin
//   Gradle plugin 2.3.10. KGP is pinned to that version here, and the Compose
//   compiler plugin uses the same version, so the two can never drift apart.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
