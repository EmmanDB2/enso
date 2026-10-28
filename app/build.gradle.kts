plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// GeckoView's omni build bundles Glean's native library; application-services (Places) asks
// for the standalone one too. Take GeckoView's, as Mozilla's own apps do.
configurations.configureEach {
    resolutionStrategy.capabilitiesResolution.withCapability("org.mozilla.telemetry:glean-native") {
        val geckoView = candidates.firstOrNull {
            (it.id as? ModuleComponentIdentifier)?.module?.contains("geckoview") == true
        }
        if (geckoView != null) select(geckoView)
        because("GeckoView already ships Glean's native library")
    }
}

dependencies {
    implementation(libs.mozilla.browser.engine.gecko)
    implementation(libs.mozilla.browser.icons)
    implementation(libs.mozilla.browser.state)
    implementation(libs.mozilla.browser.thumbnails)
    implementation(libs.mozilla.browser.storage.sync)
    implementation(libs.mozilla.concept.base)
    implementation(libs.mozilla.concept.engine)
    implementation(libs.mozilla.concept.fetch)
    implementation(libs.mozilla.concept.storage)
    implementation(libs.mozilla.concept.sync)
    implementation(libs.mozilla.concept.toolbar)
    implementation(libs.mozilla.feature.accounts)
    implementation(libs.mozilla.feature.addons)
    implementation(libs.mozilla.feature.session)
    implementation(libs.mozilla.feature.tabs)
    implementation(libs.mozilla.lib.state)
    implementation(libs.mozilla.service.firefox.accounts)
    implementation(libs.mozilla.support.appservices)
    implementation(libs.mozilla.support.base)
    implementation(libs.mozilla.support.utils)
    implementation(libs.mozilla.support.webextensions)
    implementation(libs.mozilla.ui.widgets)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.coordinatorlayout)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    implementation(libs.kotlinx.serialization.json)
}
