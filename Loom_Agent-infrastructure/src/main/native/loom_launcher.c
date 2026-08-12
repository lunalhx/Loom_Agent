#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static const char READY[] = "LOOM_LAUNCHER_READY\n";

int main(int argc, char **argv) {
    if (argc < 2) return 127;
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGUSR1);
    if (sigprocmask(SIG_BLOCK, &signals, NULL) != 0) return 126;
    if (setpgid(0, 0) != 0) return 126;
    if (write(STDOUT_FILENO, READY, sizeof(READY) - 1) != (ssize_t)(sizeof(READY) - 1)) return 126;
    int signal_number = 0;
    if (sigwait(&signals, &signal_number) != 0 || signal_number != SIGUSR1) return 126;
    execvp(argv[1], &argv[1]);
    fprintf(stderr, "loom launcher exec failed: %s\n", strerror(errno));
    return 127;
}
