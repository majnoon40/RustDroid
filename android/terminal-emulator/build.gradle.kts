// RustDroid vendored terminal-emulator (upstream: termux/termux-app,
// pinned commit 3b66f8799635a4dba4a206563048ff0e6792c487; origin:
// jackpal/Android-Terminal-Emulator). Build script is OURS — the source
// tree (src/, proguard-rules.pro, Android.mk) is vendored verbatim.
//
// Carried obligations from upstream's build.gradle (plan §4.1, review P1-5):
//   - testOptions unitTests.isReturnDefaultValues = true  (tests touch
//     android.* stubs; without this the suite throws "not mocked")
//   - externalNativeBuild cFlags verbatim (incl. -Werror; if NDK r27c's
//     clang warns, we drop -Werror / add -Wno- HERE, never in upstream C)
//   - dependencies exactly androidx.annotation + JUnit
// Deliberate divergences (recorded in THIRD_PARTY.md):
//   - abiFilters stripped to arm64-v8a only (upstream ships 4 ABIs)
//   - maven-publish + sourceJar blocks dropped (we are not a Maven repo)
//   - compileOptions raised 1.8 -> 17; compileSdk 35 (upstream 36),
//     minSdk 24 (upstream 21), ndkVersion r27c (upstream default r29)
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.emulator" // upstream's namespace, preserved

    compileSdk = 35
    ndkVersion = "27.2.12479018" // NDK r27c — matches the toolchain build pin

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            ndkBuild {
                // Upstream cFlags, verbatim (terminal-emulator/build.gradle @ 3b66f87)
                cFlags += listOf(
                    "-std=c11", "-Wall", "-Wextra", "-Werror", "-Os",
                    "-fno-stack-protector", "-Wl,--gc-sections"
                )
            }
        }

        ndk {
            // RustDroid is arm64-v8a only, everywhere (plan §4.1)
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

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // REQUIRED for the vendored upstream test suite (review P1-5):
        // the tests touch android.* framework stubs.
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    // Upstream's testLogging block, carried.
    testLogging {
        events("started", "passed", "skipped", "failed")
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
