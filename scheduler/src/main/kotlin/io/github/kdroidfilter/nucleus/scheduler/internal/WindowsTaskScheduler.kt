package io.github.kdroidfilter.nucleus.scheduler.internal

import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
import io.github.kdroidfilter.nucleus.scheduler.ExistingTaskPolicy
import io.github.kdroidfilter.nucleus.scheduler.TaskInfo
import io.github.kdroidfilter.nucleus.scheduler.TaskRequest
import io.github.kdroidfilter.nucleus.scheduler.TaskState
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Windows implementation using the Task Scheduler via `schtasks.exe`.
 *
 * Tasks are created under a `\Nucleus\{appId}\` folder in the Windows Task Scheduler.
 * Each task invokes the application executable with `--nucleus-scheduler-run {taskId}`.
 *
 * Task listing uses [TaskMetadataStore] as the source of truth for registered task IDs
 * (cross-referenced with `schtasks /Query` for existence), because `schtasks.exe` output
 * field names are locale-dependent and unreliable to parse across languages.
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
            // Task already exists — ensure metadata is registered so getAllTaskIds() finds it
            TaskMetadataStore.save(appId, request.taskId, request.inputData)
            return true
        }

        val execPath = executablePath
        if (execPath == null) {
            logger.warning("Cannot resolve executable path — task '${request.taskId}' not scheduled")
            return false
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

        val created = schtasks(*args.toTypedArray())
        if (created) {
            // Register in metadata store so getAllTaskIds() can find this task.
            // On Linux, systemd unit files serve as the registry; on Windows we use metadata.
            TaskMetadataStore.save(appId, request.taskId, request.inputData)
        }
        return created
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
            val deleted = deleteSchtask(taskPath(taskId))
            deleteSchtask(retryTaskPath(taskId))
            if (deleted) TaskMetadataStore.delete(appId, taskId)
        }
        // Delete the app folder itself (will fail silently if not empty or missing)
        deleteSchtask("\\$TASK_FOLDER\\$appId")
    }

    override fun isScheduled(taskId: String): Boolean =
        schtasks("/Query", "/TN", taskPath(taskId))

    override fun getTaskInfo(taskId: String): TaskInfo? {
        if (!isScheduled(taskId)) return null

        val xmlInfo = queryTaskXml(taskId)

        return TaskInfo(
            taskId = taskId,
            state = xmlInfo?.state ?: TaskState.SCHEDULED,
            lastRunMs = TaskMetadataStore.getLastRunMs(appId, taskId),
            nextRunMs = xmlInfo?.nextRunMs,
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
        val instant = startTime.atZone(ZoneId.systemDefault()).toInstant()

        // Use the system's short date format so schtasks accepts it regardless of locale
        val dateStr = (DateFormat.getDateInstance(DateFormat.SHORT) as SimpleDateFormat)
            .format(Date.from(instant))
        val timeStr = "%02d:%02d".format(startTime.hour, startTime.minute)

        // Delete any previous retry task for this ID
        deleteSchtask(retryTaskPath(taskId))

        return schtasks(
            "/Create",
            "/TN", retryTaskPath(taskId),
            "/TR", "\"$execPath\" $SCHEDULER_ARG \"$taskId\"",
            "/SC", "ONCE",
            "/SD", dateStr,
            "/ST", timeStr,
            "/RL", "LIMITED",
            "/IT",
            "/Z", // Auto-delete after execution
            "/F",
        )
    }

    // -- schtasks argument building -------------------------------------------

    private fun buildCreateArgs(request: TaskRequest, execPath: String): List<String>? {
        val args = mutableListOf(
            "/Create",
            "/TN", taskPath(request.taskId),
            "/TR", "\"$execPath\" $SCHEDULER_ARG \"${request.taskId}\"",
            "/RL", "LIMITED",
            "/IT", // Only run when user is logged on interactively
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
    internal fun convertCronToSchtasks(expression: String): List<String>? {
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
            // Drain output for logging
            val output = process.inputStream.bufferedReader().readText().trim()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                logger.warning("schtasks ${args.joinToString(" ")} timed out")
                false
            } else {
                val success = process.exitValue() == 0
                if (!success) {
                    logger.warning("schtasks ${args.joinToString(" ")} exit=${process.exitValue()}: $output")
                }
                success
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

    /**
     * Lists all registered task IDs by cross-referencing the metadata store
     * (locale-independent) with actual Task Scheduler presence.
     */
    private fun getAllTaskIds(): List<String> =
        TaskMetadataStore.listTaskIds(appId).filter { isScheduled(it) }

    private data class SchtasksXmlInfo(
        val state: TaskState,
        val nextRunMs: Long?,
    )

    /**
     * Queries task info via `schtasks /Query /FO XML` which produces
     * locale-independent ISO 8601 dates and English status strings.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun queryTaskXml(taskId: String): SchtasksXmlInfo? {
        val output = schtasksQuery(
            "/Query", "/TN", taskPath(taskId), "/FO", "XML",
        ) ?: return null

        return try {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(output.byteInputStream())

            val nextRunStr = doc.getElementsByTagName("NextRunTime")
                .let { if (it.length > 0) it.item(0).textContent else null }

            val statusStr = doc.getElementsByTagName("Status")
                .let { if (it.length > 0) it.item(0).textContent else null }

            val state = parseTaskState(statusStr)
            val nextRunMs = parseXmlDateTime(nextRunStr)

            SchtasksXmlInfo(state = state, nextRunMs = nextRunMs)
        } catch (e: Exception) {
            logger.log(Level.FINE, "Failed to parse schtasks XML for '$taskId'", e)
            null
        }
    }

    /**
     * Maps the schtasks XML `<Status>` element to [TaskState].
     * XML output always uses English status strings regardless of locale.
     */
    private fun parseTaskState(status: String?): TaskState =
        when (status) {
            "Running" -> TaskState.RUNNING
            "Disabled" -> TaskState.INACTIVE
            "Ready", "Queued" -> TaskState.SCHEDULED
            else -> TaskState.SCHEDULED
        }

    /**
     * Parses an ISO 8601 date/time from schtasks XML output (e.g. `2026-04-15T14:30:00`).
     */
    private fun parseXmlDateTime(text: String?): Long? {
        if (text.isNullOrBlank() || text == "N/A") return null
        return try {
            ZonedDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                // schtasks sometimes omits timezone — treat as local
                LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
