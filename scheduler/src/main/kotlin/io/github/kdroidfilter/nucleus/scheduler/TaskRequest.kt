package io.github.kdroidfilter.nucleus.scheduler

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Describes a task to be scheduled with the OS.
 *
 * Create instances via the companion factory methods: [periodic], [calendar], [onBoot].
 */
public class TaskRequest private constructor(
    /** Unique identifier — must match a [TaskRegistry] entry. */
    public val taskId: String,
    internal val type: Type,
    internal val interval: Duration?,
    internal val cronExpression: CronExpression?,
    internal val runOnce: Boolean,
    internal val inputData: Map<String, String>,
    internal val retryPolicy: RetryPolicy?,
    internal val existingTaskPolicy: ExistingTaskPolicy,
) {
    internal enum class Type { PERIODIC, CALENDAR, ON_BOOT }

    /**
     * DSL builder for configuring a [TaskRequest].
     */
    public class Builder internal constructor() {
        internal val data = mutableMapOf<String, String>()
        internal var retryPolicy: RetryPolicy? = null
        internal var existingTaskPolicy: ExistingTaskPolicy = ExistingTaskPolicy.KEEP

        /** Attach a key-value pair to the task, retrievable via [TaskContext.inputData]. */
        public fun inputData(
            key: String,
            value: String,
        ) {
            data[key] = value
        }

        /** Set the retry policy for this task. */
        public fun retryPolicy(policy: RetryPolicy) {
            retryPolicy = policy
        }

        /** Control behavior when a task with the same ID is already scheduled. */
        public fun existingTaskPolicy(policy: ExistingTaskPolicy) {
            existingTaskPolicy = policy
        }
    }

    public companion object {
        private val MIN_INTERVAL = 15.minutes

        /**
         * A task that repeats at a fixed interval.
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param interval minimum 15 minutes
         * @param configure optional DSL block for inputData, retryPolicy, etc.
         */
        public fun periodic(
            taskId: String,
            interval: Duration,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            require(interval >= MIN_INTERVAL) {
                "Interval must be at least $MIN_INTERVAL, got $interval"
            }
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.PERIODIC,
                interval = interval,
                cronExpression = null,
                runOnce = false,
                inputData = builder.data.toMap(),
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
            )
        }

        /**
         * A task triggered on a calendar schedule (cron-like).
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param expression the schedule (use [CronExpression] factory methods)
         * @param configure optional DSL block
         */
        public fun calendar(
            taskId: String,
            expression: CronExpression,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.CALENDAR,
                interval = null,
                cronExpression = expression,
                runOnce = false,
                inputData = builder.data.toMap(),
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
            )
        }

        /**
         * A task that runs at system/user login.
         *
         * @param taskId unique identifier matching a [TaskRegistry] entry
         * @param runOnce if true, the task auto-disables after its first successful run
         * @param configure optional DSL block
         */
        public fun onBoot(
            taskId: String,
            runOnce: Boolean = false,
            configure: Builder.() -> Unit = {},
        ): TaskRequest {
            val builder = Builder().apply(configure)
            return TaskRequest(
                taskId = taskId,
                type = Type.ON_BOOT,
                interval = null,
                cronExpression = null,
                runOnce = runOnce,
                inputData = builder.data.toMap(),
                retryPolicy = builder.retryPolicy,
                existingTaskPolicy = builder.existingTaskPolicy,
            )
        }
    }
}
