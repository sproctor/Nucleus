package io.github.kdroidfilter.nucleus.scheduler

/**
 * Controls what happens when [DesktopTaskScheduler.enqueue] is called
 * for a task that is already scheduled.
 */
public enum class ExistingTaskPolicy {
    /** Keep the existing task unchanged. This is the default. */
    KEEP,

    /** Replace the existing task with the new configuration. */
    REPLACE,
}
