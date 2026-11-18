import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from a properties file kept outside the repo
// (storeFile, storePassword, keyAlias, keyPassword). Default location is
// ~/.android/sikw00f-release.properties; override with SIKW00F_SIGNING_PROPS.
// Without it, release builds are simply left unsigned.
val signingProps = (System.getenv("SIKW00F_SIGNING_PROPS")
    ?: "${System.getProperty("user.home")}/.android/sikw00f-release.properties")
    .let(::file)
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.sikw00f.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sikw00f.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // USB CDC/FTDI serial support (covers the FT231X UART on SiK radios)
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")

    // MAVLink message framing/decoding (HEARTBEAT, ATTITUDE, VFR_HUD, GPS_RAW_INT, etc.)
    implementation("io.dronefleet.mavlink:mavlink:1.1.11")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OpenStreetMap map view (no API key) for the Scan tab's device-location map
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
