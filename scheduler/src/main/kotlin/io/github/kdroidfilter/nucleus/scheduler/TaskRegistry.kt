package io.github.kdroidfilter.nucleus.scheduler

/**
 * Maps task IDs to factory lambdas that create [DesktopTask] instances.
 *
 * Build with [Builder]:
 * ```kotlin
 * TaskRegistry.Builder()
 *     .register("sync") { SyncTask() }
 *     .register("backup") { BackupTask() }
 *     .build()
 * ```
 */
public class TaskRegistry internal constructor(
    private val factories: Map<String, () -> DesktopTask>,
) {
    /**
     * Creates a [DesktopTask] for the given [taskId].
     *
     * @throws TaskNotFoundException if no factory is registered for [taskId]
     */
    public fun create(taskId: String): DesktopTask {
        val factory =
            factories[taskId]
                ?: throw TaskNotFoundException("No task registered for id '$taskId'")
        return factory()
    }

    /** Returns all registered task IDs. */
    public fun registeredIds(): Set<String> = factories.keys

    public class Builder {
        private val factories = mutableMapOf<String, () -> DesktopTask>()

        /**
         * Registers a factory for the given [taskId].
         *
         * @param taskId unique identifier — must match the ID used in [TaskRequest]
         * @param factory lambda that creates a fresh [DesktopTask] instance
         */
        public fun register(
            taskId: String,
            factory: () -> DesktopTask,
        ): Builder =
            apply {
                factories[taskId] = factory
            }

        public fun build(): TaskRegistry = TaskRegistry(factories.toMap())
    }
}

/**
 * Thrown when [TaskRegistry.create] is called with an unregistered task ID.
 */
public class TaskNotFoundException(
    message: String,
) : RuntimeException(message)
