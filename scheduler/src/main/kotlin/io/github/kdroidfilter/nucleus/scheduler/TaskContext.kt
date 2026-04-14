package io.github.kdroidfilter.nucleus.scheduler

/**
 * Execution context passed to [DesktopTask.doWork].
 *
 * @property taskId the unique identifier of this task
 * @property inputData key-value data passed at enqueue time
 * @property runAttemptCount 1-based attempt counter (1 = first run, 2 = first retry, etc.)
 */
public data class TaskContext(
    val taskId: String,
    val inputData: Map<String, String> = emptyMap(),
    val runAttemptCount: Int = 1,
)
