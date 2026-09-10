#ifndef RD_INTERPOSERS_H
#define RD_INTERPOSERS_H

#include <stddef.h>

/* Write end of the report pipe (alloc-count reporter). Set by the test
 * before spawning; -1 disables reporting. */
extern int g_report_fd;

/* Allocation count observed while rd_child_armed was set (see
 * interposers.c for the honest scope statement). */
extern unsigned long rd_child_allocs;

#endif
