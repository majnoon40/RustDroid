pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RustDroid"
include(":app")

// Phase 4: vendored Termux terminal modules @ 3b66f8799635a4dba4a206563048ff0e6792c487
// (Apache-2.0 — see LICENSES/ and THIRD_PARTY.md; never termux-shared —
//  the GPL scope of that library is excluded by design, plan §3.2)
include(":terminal-emulator")
include(":terminal-view")
