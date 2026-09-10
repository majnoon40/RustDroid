/*
 * seam_overrides.h — injected via -include BEFORE termux.c's own
 * defaults, so the RD_FORK/RD_GRANTPT/RD_MALLOC macros resolve to the
 * harness implementations in seams.c. Function-like macro definitions
 * on the command line are quoting-hostile; a header is not.
 *
 * Only harness builds include this. Production (ndkBuild) builds never
 * see it and use the libc defaults from termux.c's #ifndef blocks.
 */
#ifndef RD_SEAM_OVERRIDES_H
#define RD_SEAM_OVERRIDES_H

#include "seams.h"

#define RD_FORK() rd_fork()
#define RD_GRANTPT(fd) rd_grantpt(fd)
#define RD_MALLOC(n) rd_malloc(n)

#endif
