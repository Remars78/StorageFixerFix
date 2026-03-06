plugins {
    id("com.android.application")
}

android {
    namespace = "com.omersusin.storagefixer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omersusin.storagefixer"
        minSdk = 34
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")

    // Xposed API (compile only - provided by LSPosed at runtime)
    compileOnly("com.github.rovo89:XposedBridge:82")
}
