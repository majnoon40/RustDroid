# RustDroid divergence from upstream @ 3b66f87 (plan §4.2):
# LOCAL_MODULE renamed libtermux -> librustdroidpty so RustDroid's
# /proc/<pid>/maps, bug reports, and crash dumps never confuse a Termux
# app's libtermux.so with ours. The Java package (com.termux.terminal)
# and JNI symbol names (Java_com_termux_terminal_*) are UNCHANGED —
# attribution-preserving vendoring; there is no runtime collision (each
# APK loads its own /data/app/<pkg>/lib/... copy).
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= librustdroidpty
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)
