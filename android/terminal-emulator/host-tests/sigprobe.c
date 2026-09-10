/*
 * sigprobe.c — tiny helper for the SIGPIPE-disposition test (plan §6.2,
 * review P2-7). Spawned through create_subprocess while the parent
 * holds SIGPIPE == SIG_IGN (mimicking ART). Writes the disposition it
 * observes after exec to stdout (the pts), for the parent to read from
 * the master side.
 *
 * Built WITHOUT sanitizers — it is an independent exec target.
 */
#include <signal.h>
#include <string.h>
#include <unistd.h>

int main(void)
{
    struct sigaction old;
    memset(&old, 0, sizeof old);
    if (sigaction(SIGPIPE, NULL, &old) != 0) {
        static const char e[] = "SIGPIPE=PROBE-ERROR\n";
        write(1, e, sizeof e - 1);
        return 1;
    }
    static const char ign[] = "SIGPIPE=SIG_IGN\n";
    static const char dfl[] = "SIGPIPE=SIG_DFL\n";
    if (old.sa_handler == SIG_IGN) {
        write(1, ign, sizeof ign - 1);
    } else {
        write(1, dfl, sizeof dfl - 1);
    }
    return 0;
}
