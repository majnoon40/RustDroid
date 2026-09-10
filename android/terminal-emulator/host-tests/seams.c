#include "seams.h"

#include <errno.h>
#include <stdlib.h>
#include <unistd.h>

int g_fail_fork = 0;
int g_fail_grantpt = 0;
size_t g_fail_malloc_size = 0;
int rd_child_armed = 0;

pid_t rd_fork(void)
{
    if (g_fail_fork) {
        errno = EAGAIN;
        return -1;
    }
    pid_t pid = fork();
    if (pid == 0) {
        rd_child_armed = 1; /* allocation interposer counts from here */
    }
    return pid;
}

int rd_grantpt(int fd)
{
    if (g_fail_grantpt) {
        errno = EACCES;
        return -1;
    }
    return grantpt(fd);
}

void* rd_malloc(size_t n)
{
    if (g_fail_malloc_size != 0 && n == g_fail_malloc_size) {
        return NULL;
    }
    return malloc(n);
}
