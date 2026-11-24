plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "qdvc.dahdoo.android.app"
    compileSdk = 34

    defaultConfig {
        // Do not add an applicationIdSuffix to any build type: the static
        // "Record now" shortcut in res/xml/shortcuts.xml names this package
        // literally, and a suffixed build would get a shortcut that opens nothing.
        applicationId = "qdvc.dahdoo.android.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // A personal app installed by hand: sign release builds with the debug
            // key so they install without a keystore. Judge start-up speed on a
            // release build -- debug Compose builds are several times slower to
            // reach the first frame. Replace with a real signing config before
            // distributing anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Installs the baseline profiles that Compose and AndroidX ship inside their
    // AARs, so a sideloaded release build gets AOT-compiled start-up paths
    // without waiting for the Play Store's cloud profiles (which this app will
    // never receive). Purely local; it does no networking.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in JVM unit tests; the real library lets
    // the codec tests exercise the same parser the app uses on device.
    testImplementation("org.json:json:20240303")
}
