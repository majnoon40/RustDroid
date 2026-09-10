# RustDroid keeps default rules; no reflection-heavy code paths that
# minification would break. Minification is disabled in release for v1
# (F-Droid builds from source and can enable it themselves).

# --- Phase 4: vendored terminal modules (com.termux.*) -------------------
# The JNI bridge class must survive shrinking with its native methods
# bound, or the library load fails at runtime with UnsatisfiedLinkError
# in minified builds (plan §4.2; review P3-11 — inert today while
# isMinifyEnabled = false, landed in implementation step 1).
-keepclasseswithmembernames class com.termux.terminal.JNI {
    native <methods>;
}

# termux.c (the only vendored native code) never calls back into Java;
# there are no native-invoked Java callbacks to keep today. If a patch
# ever adds a JNIEnv callback, its keep rule belongs HERE — review this
# block when touching terminal-emulator/src/main/jni/.
