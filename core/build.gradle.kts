plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)

    // Linux hub.
    jvm()

    // Android app. minSdk 31 (Android 12) is where the modern BLUETOOTH_SCAN/CONNECT permissions start.
    android {
        namespace = "app.batterycommander.core"
        compileSdk = 37
        minSdk = 31
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
