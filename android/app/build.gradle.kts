plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rustdroid.ide"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.rustdroid.ide"
        // Pinned to 28: executing binaries from app data (/data/data/<pkg>/files)
        // is blocked by SELinux for targetSdk >= 29. This is the same strategy
        // Termux uses; binaries run fine on Android 5-15 at targetSdk 28.
        // F-Droid distribution only — not Play-eligible at this target.
        minSdk = 24
        targetSdk = 28
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ""
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

    packaging {
        // META-INF duplicate licenses from commons-compress et al.
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/LICENSE*"
        resources.excludes += "/META-INF/NOTICE*"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // opt-in real-bundle integration test: -Drd.bundle=/path/to/bundle.zip
        unitTests.all { test ->
            test.systemProperty("rd.bundle", System.getProperty("rd.bundle") ?: "")
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines + serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Networking (toolchain download, crates.io search)
    implementation(libs.okhttp)

    // Code editor (LGPL-2.1 — F-Droid compatible)
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)

    // Archive handling for the toolchain bundle (zip + tar.xz with unix modes)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // Cargo.toml parsing
    implementation(libs.tomlj)

    // Unit tests (pure JVM)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
}
