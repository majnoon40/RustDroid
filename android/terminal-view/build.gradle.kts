// RustDroid vendored terminal-view (upstream: termux/termux-app, pinned
// commit 3b66f8799635a4dba4a206563048ff0e6792c487; origin:
// jackpal/Android-Terminal-Emulator). Build script is OURS — the source
// tree (src/, proguard-rules.pro) is vendored verbatim.
//
// Carried from upstream's build.gradle: dependencies (androidx.annotation
// + api :terminal-emulator), release proguardFiles boilerplate.
// The unitTests.isReturnDefaultValues flag is carried here too — the
// module has no upstream tests, but the flag is harmless and keeps the
// two vendored modules symmetric (plan §4.6).
// Deliberate divergences (recorded in THIRD_PARTY.md):
//   - maven-publish + sourceJar blocks dropped
//   - testInstrumentationRunner (ancient android.support.test boilerplate,
//     no instrumented tests exist) dropped
//   - compileOptions raised 1.8 -> 17; compileSdk 35, minSdk 24,
//     ndkVersion r27c (upstream: 36 / 21 / r29)
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view" // upstream's namespace, preserved

    compileSdk = 35
    ndkVersion = "27.2.12479018" // NDK r27c — matches the toolchain build pin

    defaultConfig {
        minSdk = 24

        // arm64-v8a only, everywhere (plan §4.1) — inert today (this
        // module has no native code) but kept symmetric with
        // :terminal-emulator and future-proof.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro" // upstream boilerplate, vendored as-is
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Carried for symmetry with :terminal-emulator (plan §4.6).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    api(project(":terminal-emulator"))
    testImplementation(libs.junit)
}
