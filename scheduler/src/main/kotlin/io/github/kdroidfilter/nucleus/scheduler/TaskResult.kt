package io.github.kdroidfilter.nucleus.scheduler

/**
 * The outcome of a [DesktopTask.doWork] invocation.
 */
public sealed class TaskResult {
    /** The task completed successfully. */
    public data object Success : TaskResult()

    /**
     * The task failed permanently and should not be retried.
     *
     * @property message optional description of the failure
     */
    public data class Failure(
        val message: String? = null,
    ) : TaskResult()

    /**
     * The task failed temporarily and should be retried according to its [RetryPolicy].
     *
     * @property message optional description of why a retry is needed
     */
    public data class Retry(
        val message: String? = null,
    ) : TaskResult()
}
