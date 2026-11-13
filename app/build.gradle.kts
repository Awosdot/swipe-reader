plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.awoo.swipereader"
    compileSdk = 34

    defaultConfig {
        // New id, so this installs as its own app next to the original Swipe Reader
        applicationId = "com.awoo.swipeshelf"
        minSdk = 29
        // Android 10 target keeps direct access to /sdcard/Telegram via requestLegacyExternalStorage
        targetSdk = 29
        versionCode = 1
        versionName = "2.0"
    }

    // Fixed signing key so every CI build can update the previous install
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
}
