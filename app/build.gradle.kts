import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    // Path comes from -PNEKOSHELF_KEYSTORE_PROPERTIES or the env var of the same name.
    val path = (findProperty("NEKOSHELF_KEYSTORE_PROPERTIES") as String?)
        ?: System.getenv("NEKOSHELF_KEYSTORE_PROPERTIES")
    val f = path?.let { file(it) }
    if (f != null && f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.nekoshelf.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.nekoshelf.launcher"
        minSdk = 28
        targetSdk = 28
        versionCode = 5
        versionName = "1.2.1"
    }

    signingConfigs {
        create("nekoshelf") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Without a keystore the default debug key is used, so anyone can build.
        debug { if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("nekoshelf") }
        release {
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("nekoshelf")
            else signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions { unitTests.isIncludeAndroidResources = true }

    // Target 28 is deliberate (WifiManager.setWifiEnabled; sideloaded, never on Google Play), so
    // the release build's lint-vital check must not fail on it.
    lint { disable += "ExpiredTargetSdkVersion" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-svg:3.5.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Compose UI tests under Robolectric (gesture tests); the manifest artifact supplies the host activity.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
