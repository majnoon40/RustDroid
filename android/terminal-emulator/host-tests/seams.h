/*
 * seams.h/.c — RustDroid test-seam implementations (plan §6.2).
 *
 * termux.c routes fork/grantpt/malloc-for-marshalling through
 * RD_FORK()/RD_GRANTPT(fd)/RD_MALLOC(n) macros. Production builds use
 * the libc defaults. Harness builds compile termux.c with
 * -include seam_overrides.h so those macros resolve to the functions
 * below, whose failure behavior is controlled by these globals at
 * runtime (no per-test recompilation needed).
 */
#ifndef RD_SEAMS_H
#define RD_SEAMS_H

#include <sys/types.h>

/* Failure injection flags (set by tests; 0 = behave like libc). */
extern int g_fail_fork;      /* 1: RD_FORK returns -1 (fork failure) */
extern int g_fail_grantpt;   /* 1: RD_GRANTPT returns -1 */
extern size_t g_fail_malloc_size; /* nonzero: RD_MALLOC of exactly this size fails */

/* Set to 1 in the forked child right after fork() — used by the
 * allocation interposer to know it is observing the child window. */
extern int rd_child_armed;

pid_t rd_fork(void);
int rd_grantpt(int fd);
void* rd_malloc(size_t n);

#endif /* RD_SEAMS_H */
