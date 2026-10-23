plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.enso.browser"
    // Android Components 158 requires compiling against API 37.2.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.enso.browser"
        // Android Components' floor.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // GeckoView ships native code for every ABI; the Fold only needs arm64.
        ndk { abiFilters += "arm64-v8a" }
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
}

dependencies {
    implementation(libs.mozilla.browser.engine.gecko)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
}
