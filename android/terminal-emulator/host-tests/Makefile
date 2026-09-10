# Host-side JNI test harness (plan §6.2) — mock JNIEnv + ASan/LSan +
# allocation-interposer builds for the vendored termux.c.
#
# `make check` runs everything (compile + run + whitelist grep).
# CC defaults to clang (ubuntu-latest runners ship it); gcc works too.
#
# TERMUX_SRC can be pointed at a different copy of the C (e.g. a
# pristine upstream file, for regression-pinning runs — see
# DEVIATIONS.md's implementation log).

CC ?= clang

CFLAGS = -std=c11 -D_GNU_SOURCE -Wall -Wextra -Werror -g -O1 -Iinclude

TERMUX_SRC ?= ../src/main/jni/termux.c

HARNESS_OBJS = mock_jni.c seams.c

.PHONY: all check clean

all: tests_asan tests_alloc sigprobe

tests_asan: tests.c $(HARNESS_OBJS) $(TERMUX_SRC) include/jni.h seam_overrides.h
	$(CC) $(CFLAGS) -fsanitize=address \
	    -include seam_overrides.h \
	    tests.c $(HARNESS_OBJS) $(TERMUX_SRC) -o $@

# Link-time --wrap=malloc cannot coexist with ASan's malloc
# interception — this build carries no sanitizers on purpose.
tests_alloc: alloc_tests.c interposers.c $(HARNESS_OBJS) $(TERMUX_SRC) include/jni.h seam_overrides.h
	$(CC) $(CFLAGS) \
	    -include seam_overrides.h \
	    alloc_tests.c interposers.c $(HARNESS_OBJS) $(TERMUX_SRC) -o $@ \
	    -Wl,--wrap=malloc -Wl,--wrap=calloc -Wl,--wrap=realloc \
	    -Wl,--wrap=strdup -Wl,--wrap=close -Wl,--wrap=execve -Wl,--wrap=_exit

sigprobe: sigprobe.c
	$(CC) $(CFLAGS) sigprobe.c -o $@

check: all
	@echo '== whitelist grep (child path) =='
	./check_child_path.sh ../src/main/jni/termux.c
	@echo '== ASan + LeakSanitizer suite =='
	./tests_asan
	@echo '== fork-window allocation discipline =='
	./tests_alloc
	@echo '== ALL HOST JNI HARNESS CHECKS PASSED =='

clean:
	rm -f tests_asan tests_alloc sigprobe
