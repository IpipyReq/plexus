import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// app's version no. Bump it for every release: the updater only offers a higher version.
val plexusVersion = "1.0.0"
// Where the in-app updater looks for new versions: a public GitHub repo whose releases hold the APKs.
val updateRepo = "IpipyReq/plexus"

// signing key, from keystore.properties next to settings.gradle.kts (never committed).
// Every release must be signed with the same key, or Android refuses to update the app.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.plex_tv_app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.plex_tv_app"
        minSdk = 29
        targetSdk = 37
        // 1.2.3 → 10203, so each release's code is higher than the last.
        versionCode = plexusVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }
        versionName = plexusVersion
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("plexus") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Your "plexus" key if keystore.properties is set up, else the debug key (this PC only).
            signingConfig = signingConfigs.findByName("plexus") ?: signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    // Video playback (Media3 / ExoPlayer), with HLS for when Plex converts a file on the fly
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // Voice and remote control of playback ("Hey Google, pause"), via Android's media session
    implementation(libs.androidx.media3.session)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}