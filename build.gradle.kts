// Versions match qdvc-csv-android, the QDVC sibling already known to build on
// the Kotlin 2.0 Compose-compiler plugin path. Kotlin 2.0+ ships the Compose
// compiler itself, so there is no composeOptions/kotlinCompilerExtensionVersion.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
