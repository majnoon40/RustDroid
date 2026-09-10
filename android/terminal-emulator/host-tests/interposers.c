/*
 * interposers.c — link-time allocation/exec reporting (plan §6.2).
 *
 * Linked ONLY into tests_alloc (no sanitizers in that build — ASan
 * intercepts malloc itself and cannot be combined with --wrap=malloc).
 *
 * --wrap=malloc/-Wl,--wrap=calloc/-Wl,--wrap=realloc/-Wl,--wrap=strdup
 * count allocations that occur while rd_child_armed is set — i.e. in
 * the forked child between fork() and execve. --wrap=execve and
 * --wrap=_exit report the count ("RD_ALLOCS=n") to the report pipe
 * right before the real call, so the parent can assert zero.
 * --wrap=close keeps the report pipe's write end alive in the child
 * (the parent's fd close-list would otherwise close it before execve).
 *
 * SCOPE, stated honestly (review Part 2 / plan §4.4): link-time
 * wrapping sees only calls from OUR compilation units. glibc/bionic
 * internals (opendir's buffer, an asprintf inside libc, stdio) are
 * invisible to it. The interposer therefore proves OUR child path does
 * not allocate — necessary, not sufficient; the on-device soak under
 * memory pressure (plan §6.6) is the real test for platform surprises.
 */
#include "interposers.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int g_report_fd = -1;
unsigned long rd_child_allocs = 0;

extern void* __real_malloc(size_t n);
extern void* __real_calloc(size_t n, size_t s);
extern void* __real_realloc(void* p, size_t n);
extern char* __real_strdup(const char* s);
extern int __real_close(int fd);
extern int __real_execve(const char* path, char* const argv[], char* const envp[]);
extern void __real__exit(int code);

void* __wrap_malloc(size_t n)
{
    if (rd_child_armed) rd_child_allocs++;
    return __real_malloc(n);
}

void* __wrap_calloc(size_t n, size_t s)
{
    if (rd_child_armed) rd_child_allocs++;
    return __real_calloc(n, s);
}

void* __wrap_realloc(void* p, size_t n)
{
    if (rd_child_armed) rd_child_allocs++;
    return __real_realloc(p, n);
}

char* __wrap_strdup(const char* s)
{
    if (rd_child_armed) rd_child_allocs++;
    return __real_strdup(s);
}

int __wrap_close(int fd)
{
    /* The report pipe's write end must survive the child's close-list
     * loop so the execve/_exit wrapper can write the count. */
    if (rd_child_armed && fd == g_report_fd) return 0;
    return __real_close(fd);
}

static void report_allocs(const char* what)
{
    if (!rd_child_armed || g_report_fd < 0) return;
    char buf[64];
    size_t n = 0;
    const char* tag = "RD_ALLOCS(";
    while (tag[n] != '\0') { buf[n] = tag[n]; n++; }
    const char* w = what;
    while (*w && n < 40) buf[n++] = *w++;
    buf[n++] = ')'; buf[n++] = '=';
    unsigned long v = rd_child_allocs;
    char digits[24];
    size_t d = 0;
    do { digits[d++] = (char) ('0' + (v % 10)); v /= 10; } while (v > 0);
    while (d > 0) buf[n++] = digits[--d];
    buf[n++] = '\n';
    (void) write(g_report_fd, buf, n);
}

int __wrap_execve(const char* path, char* const argv[], char* const envp[])
{
    report_allocs("execve");
    return __real_execve(path, argv, envp);
}

void __wrap__exit(int code)
{
    report_allocs("exit");
    __real__exit(code);
}
