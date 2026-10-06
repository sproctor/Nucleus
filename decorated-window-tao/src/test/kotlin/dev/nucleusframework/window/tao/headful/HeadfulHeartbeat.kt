package dev.nucleusframework.window.tao.headful

import java.lang.management.ManagementFactory
import kotlin.concurrent.thread

/**
 * DEBUG (Windows runner freeze): with `-Dnucleus.tao.headful.heartbeatMillis=N`
 * prints the process's and the system's resources every N ms from a thread of
 * its own, so a log that stops mid-case shows whether memory or CPU ran away
 * before it did — or whether everything went silent at once.
 */
internal object HeadfulHeartbeat {
    private const val MB = 1024L * 1024L
    private const val PERCENT = 100

    fun startIfRequested(currentCase: () -> String) {
        val period =
            System
                .getProperty("nucleus.tao.headful.heartbeatMillis")
                ?.toLongOrNull()
                ?.takeIf { it > 0 } ?: return
        thread(isDaemon = true, name = "tao-headful-heartbeat") {
            val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val threads = ManagementFactory.getThreadMXBean()
            val runtime = Runtime.getRuntime()
            while (true) {
                System.err.println(
                    "[heartbeat] committedVirt=${os.committedVirtualMemorySize / MB}MB " +
                        "heap=${(runtime.totalMemory() - runtime.freeMemory()) / MB}MB " +
                        "sysFree=${os.freeMemorySize / MB}/${os.totalMemorySize / MB}MB " +
                        "procCpu=${(os.processCpuLoad * PERCENT).toInt()}% " +
                        "sysCpu=${(os.cpuLoad * PERCENT).toInt()}% " +
                        "javaThreads=${threads.threadCount} case=${currentCase()}",
                )
                System.err.flush()
                Thread.sleep(period)
            }
        }
    }
}

/**
 * Ends this process with `TerminateProcess` (via `taskkill /F`) on Windows,
 * before the watchdog's `halt()`. `halt()` exits through `ExitProcess`, which
 * runs every DLL's process-detach under the loader lock — and on the Windows
 * runner, with the event-loop thread stuck in a native modal loop (an embed's
 * context menu), that never finished: the step hung past the job's timeout and
 * GitHub kept no log at all. `TerminateProcess` skips detach. Does nothing
 * elsewhere, or if `taskkill` cannot be started; `halt()` follows either way.
 */
internal fun terminateWithoutDetachOnWindows() {
    if (!System.getProperty("os.name", "").lowercase().contains("win")) return
    runCatching {
        ProcessBuilder("taskkill", "/F", "/PID", ProcessHandle.current().pid().toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor(TASKKILL_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }
}

private const val TASKKILL_WAIT_SECONDS = 10L
