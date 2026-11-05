plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vanzetta.gipbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vanzetta.gipbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.12"
        ndk {
            // Cover both 64-bit Android TV devices and 32-bit SaluteTV userlands.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
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
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Talks to the TV's own adbd on 127.0.0.1 to restart the Shizuku server (see ShizukuStarter).
    implementation("dev.mobile:dadb:1.2.10")

    testImplementation("junit:junit:4.13.2")
}
