package io.github.kdroidfilter.nucleus.scheduler.internal

import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import io.github.kdroidfilter.nucleus.scheduler.ExistingTaskPolicy
import io.github.kdroidfilter.nucleus.scheduler.TaskInfo
import io.github.kdroidfilter.nucleus.scheduler.TaskRequest
import io.github.kdroidfilter.nucleus.scheduler.TaskState
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Windows implementation using the Task Scheduler via `schtasks.exe`.
 *
 * Tasks are created under a `Nucleus\{appId}` folder in the Windows Task Scheduler.
 * Each task invokes the application executable with `--nucleus-scheduler-run {taskId}`.
 */
@Suppress("TooManyFunctions")
internal object WindowsTaskScheduler : PlatformScheduler {
    private val logger = Logger.getLogger(WindowsTaskScheduler::class.java.name)
    private const val SCHEDULER_ARG = "--nucleus-scheduler-run"
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val TASK_FOLDER = "Nucleus"

    private val appId: String
        get() = NucleusApp.appId

    private val executablePath: String?
        get() =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)

    // -- Task naming ----------------------------------------------------------

    private fun taskPath(taskId: String): String = "\\$TASK_FOLDER\\$appId\\$taskId"

    private fun retryTaskPath(taskId: String): String = "\\$TASK_FOLDER\\$appId\\$taskId-retry"

    // -- PlatformScheduler implementation -------------------------------------

    override fun enqueue(request: TaskRequest): Boolean {
        if (request.existingTaskPolicy == ExistingTaskPolicy.KEEP && isScheduled(request.taskId)) {
            return true
        }

        val execPath = executablePath
        if (execPath == null) {
            logger.warning("Cannot resolve executable path — task '${request.taskId}' not scheduled")
            return false
        }

        if (request.inputData.isNotEmpty()) {
            TaskMetadataStore.save(appId, request.taskId, request.inputData)
        }

        // If replacing, delete the existing task first
        if (request.existingTaskPolicy == ExistingTaskPolicy.REPLACE && isScheduled(request.taskId)) {
            deleteSchtask(taskPath(request.taskId))
        }

        val args = buildCreateArgs(request, execPath)
        if (args == null) {
            logger.warning("Cannot build schtasks arguments for task '${request.taskId}'")
            return false
        }

        return schtasks(*args.toTypedArray())
    }

    override fun cancel(taskId: String): Boolean {
        if (!isScheduled(taskId)) return false
        val result = deleteSchtask(taskPath(taskId))
        // Also clean up any pending retry task
        deleteSchtask(retryTaskPath(taskId))
        if (result) TaskMetadataStore.delete(appId, taskId)
        return result
    }

    override fun cancelAll() {
        getAllTaskIds().forEach { taskId ->
            deleteSchtask(taskPath(taskId))
            deleteSchtask(retryTaskPath(taskId))
        }
        // Delete the app folder itself (will fail silently if not empty or missing)
        deleteSchtask("\\$TASK_FOLDER\\$appId")
        TaskMetadataStore.deleteAll(appId)
    }

    override fun isScheduled(taskId: String): Boolean =
        schtasks("/Query", "/TN", taskPath(taskId))

    override fun getTaskInfo(taskId: String): TaskInfo? {
        if (!isScheduled(taskId)) return null

        val state = resolveState(taskId)
        val nextRunMs = parseNextRun(taskId)

        return TaskInfo(
            taskId = taskId,
            state = state,
            lastRunMs = TaskMetadataStore.getLastRunMs(appId, taskId),
            nextRunMs = nextRunMs,
            runCount = TaskMetadataStore.getRunCount(appId, taskId),
            lastResult = TaskMetadataStore.getLastResult(appId, taskId),
        )
    }

    override fun getAllTasks(): List<TaskInfo> =
        getAllTaskIds().mapNotNull { getTaskInfo(it) }

    // -- Retry support --------------------------------------------------------

    /**
     * Schedules a one-shot retry task that fires after [delaySeconds].
     */
    fun scheduleRetry(
        taskId: String,
        delaySeconds: Long,
    ): Boolean {
        val execPath = executablePath ?: return false
        val startTime = LocalDateTime.now().plusSeconds(delaySeconds)
        val dateStr = "%02d/%02d/%04d".format(startTime.monthValue, startTime.dayOfMonth, startTime.year)
        val timeStr = "%02d:%02d".format(startTime.hour, startTime.minute)

        // Delete any previous retry task for this ID
        deleteSchtask(retryTaskPath(taskId))

        return schtasks(
            "/Create",
            "/TN", retryTaskPath(taskId),
            "/TR", "\"$execPath\" $SCHEDULER_ARG $taskId",
            "/SC", "ONCE",
            "/SD", dateStr,
            "/ST", timeStr,
            "/RL", "LIMITED",
            "/F",
        )
    }

    // -- schtasks argument building -------------------------------------------

    private fun buildCreateArgs(request: TaskRequest, execPath: String): List<String>? {
        val args = mutableListOf(
            "/Create",
            "/TN", taskPath(request.taskId),
            "/TR", "\"$execPath\" $SCHEDULER_ARG ${request.taskId}",
            "/RL", "LIMITED",
            "/F",
        )

        when (request.type) {
            TaskRequest.Type.PERIODIC -> {
                val minutes = request.interval!!.inWholeMinutes
                args.addAll(listOf("/SC", "MINUTE", "/MO", minutes.toString()))
            }

            TaskRequest.Type.CALENDAR -> {
                val scheduleArgs = convertCronToSchtasks(request.cronExpression!!.expression)
                if (scheduleArgs == null) {
                    logger.warning(
                        "Unsupported cron expression '${request.cronExpression}' — " +
                            "cannot map to schtasks schedule",
                    )
                    return null
                }
                args.addAll(scheduleArgs)
            }

            TaskRequest.Type.ON_BOOT -> {
                args.addAll(listOf("/SC", "ONLOGON"))
            }
        }

        return args
    }

    // -- Cron-to-schtasks conversion ------------------------------------------

    /**
     * Converts a systemd OnCalendar expression to schtasks /SC arguments.
     *
     * Supported patterns:
     * - `*-*-* HH:MM:00`            → /SC DAILY /ST HH:MM
     * - `*-*-* *:00:00`             → /SC HOURLY
     * - `Mon *-*-* HH:MM:00`        → /SC WEEKLY /D MON /ST HH:MM
     * - `Mon..Fri *-*-* HH:MM:00`   → /SC WEEKLY /D MON,TUE,WED,THU,FRI /ST HH:MM
     *
     * Returns null for unsupported expressions.
     */
    @Suppress("CyclomaticComplexity")
    private fun convertCronToSchtasks(expression: String): List<String>? {
        val trimmed = expression.trim()

        // Pattern: every hour — *-*-* *:00:00
        if (trimmed == "*-*-* *:00:00") {
            return listOf("/SC", "HOURLY")
        }

        // Pattern: day range (Mon..Fri) with time
        val rangeMatch = Regex("""^(\w{3})\.\.(\w{3})\s+\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""").matchEntire(trimmed)
        if (rangeMatch != null) {
            val (startDay, endDay, hour, minute) = rangeMatch.destructured
            val days = expandDayRange(startDay, endDay) ?: return null
            return listOf("/SC", "WEEKLY", "/D", days, "/ST", "$hour:$minute")
        }

        // Pattern: specific weekday with time — Mon *-*-* HH:MM:00
        val weekdayMatch = Regex("""^(\w{3})\s+\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""").matchEntire(trimmed)
        if (weekdayMatch != null) {
            val (day, hour, minute) = weekdayMatch.destructured
            val schtasksDay = day.uppercase()
            return listOf("/SC", "WEEKLY", "/D", schtasksDay, "/ST", "$hour:$minute")
        }

        // Pattern: daily at specific time — *-*-* HH:MM:00
        val dailyMatch = Regex("""^\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""").matchEntire(trimmed)
        if (dailyMatch != null) {
            val (hour, minute) = dailyMatch.destructured
            return listOf("/SC", "DAILY", "/ST", "$hour:$minute")
        }

        return null
    }

    private val orderedDays = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    private fun expandDayRange(start: String, end: String): String? {
        val startIdx = orderedDays.indexOf(start.uppercase())
        val endIdx = orderedDays.indexOf(end.uppercase())
        if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return null
        return orderedDays.subList(startIdx, endIdx + 1).joinToString(",")
    }

    // -- schtasks helpers -----------------------------------------------------

    private fun deleteSchtask(path: String): Boolean =
        schtasks("/Delete", "/TN", path, "/F")

    @Suppress("TooGenericExceptionCaught")
    private fun schtasks(vararg args: String): Boolean =
        try {
            val process =
                ProcessBuilder("schtasks.exe", *args)
                    .redirectErrorStream(true)
                    .start()
            // Drain output to prevent blocking
            process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                logger.warning("schtasks ${args.joinToString(" ")} timed out")
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "schtasks ${args.joinToString(" ")} failed", e)
            false
        }

    @Suppress("TooGenericExceptionCaught")
    private fun schtasksQuery(vararg args: String): String? =
        try {
            val process =
                ProcessBuilder("schtasks.exe", *args)
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                output
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    // -- Introspection --------------------------------------------------------

    private fun getAllTaskIds(): List<String> {
        val folderPath = "\\$TASK_FOLDER\\$appId"
        val output = schtasksQuery("/Query", "/TN", folderPath, "/FO", "LIST") ?: return emptyList()

        val prefix = "\\$TASK_FOLDER\\$appId\\"
        return output.lines()
            .filter { it.startsWith("TaskName:") }
            .map { it.substringAfter("TaskName:").trim() }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { !it.endsWith("-retry") && !it.contains("\\") }
    }

    private fun resolveState(taskId: String): TaskState {
        val output = schtasksQuery(
            "/Query", "/TN", taskPath(taskId), "/FO", "LIST", "/V",
        ) ?: return TaskState.SCHEDULED

        val statusLine = output.lines().firstOrNull { it.startsWith("Status:") }
        val status = statusLine?.substringAfter("Status:")?.trim()

        return when (status) {
            "Running" -> TaskState.RUNNING
            "Disabled" -> TaskState.INACTIVE
            else -> TaskState.SCHEDULED
        }
    }

    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    private fun parseNextRun(taskId: String): Long? {
        val output = schtasksQuery(
            "/Query", "/TN", taskPath(taskId), "/FO", "LIST", "/V",
        ) ?: return null

        val nextRunLine = output.lines().firstOrNull { it.startsWith("Next Run Time:") }
        val nextRunStr = nextRunLine?.substringAfter("Next Run Time:")?.trim() ?: return null

        if (nextRunStr == "N/A" || nextRunStr == "Disabled") return null

        return try {
            // schtasks outputs date/time in locale format — try common patterns
            val formatters = listOf(
                DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("M/d/yyyy HH:mm:ss"),
            )
            var parsed: LocalDateTime? = null
            for (fmt in formatters) {
                try {
                    parsed = LocalDateTime.parse(nextRunStr, fmt)
                    break
                } catch (_: Exception) {
                    // Try next format
                }
            }
            parsed?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
