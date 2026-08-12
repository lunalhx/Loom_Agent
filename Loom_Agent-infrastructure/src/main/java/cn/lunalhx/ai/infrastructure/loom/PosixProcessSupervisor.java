package cn.lunalhx.ai.infrastructure.loom;

import com.sun.jna.Library;
import com.sun.jna.Native;

import java.util.concurrent.TimeUnit;

/** Terminates the group established by {@link NativeLauncher}. */
final class PosixProcessSupervisor {
    private static final int SIGTERM = 15;
    private static final int SIGKILL = 9;
    private static final int SIGUSR1 = System.getProperty("os.name", "").toLowerCase().contains("mac") ? 30 : 10;
    private static final PosixLib POSIX = Native.load("c", PosixLib.class);

    private interface PosixLib extends Library { int kill(int pid, int signal); }

    boolean release(Process process) {
        return process.pid() > 0 && process.pid() <= Integer.MAX_VALUE
                && POSIX.kill((int) process.pid(), SIGUSR1) == 0;
    }

    boolean terminate(Process process) {
        long pid = process.pid();
        if (pid <= 0 || pid > Integer.MAX_VALUE) { process.destroyForcibly(); return true; }
        int group = -(int) pid;
        POSIX.kill(group, SIGTERM);
        try { if (process.waitFor(500, TimeUnit.MILLISECONDS)) return false; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        POSIX.kill(group, SIGKILL);
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return true;
    }

    /** Cleans ordinary background descendants that remain in the launcher's group. */
    boolean terminateRemainingGroup(Process process) {
        long pid = process.pid();
        if (pid <= 0 || pid > Integer.MAX_VALUE) return false;
        int group = -(int) pid;
        if (POSIX.kill(group, 0) != 0) return false;
        POSIX.kill(group, SIGTERM);
        try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (POSIX.kill(group, 0) == 0) POSIX.kill(group, SIGKILL);
        return true;
    }
}
