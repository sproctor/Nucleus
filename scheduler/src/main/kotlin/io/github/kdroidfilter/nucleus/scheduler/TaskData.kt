package io.github.kdroidfilter.nucleus.scheduler

/**
 * Typed key-value container for task input data.
 *
 * Values are stored internally as strings (backed by a `.properties` file) but
 * retrieved via typed accessors, eliminating manual parsing and silent `null`s.
 *
 * Build instances with [Builder]:
 * ```kotlin
 * TaskRequest.periodic("sync", 1.hours) {
 *     inputData {
 *         putString("endpoint", "https://api.example.com")
 *         putInt("retries", 3)
 *         putBoolean("verbose", true)
 *     }
 * }
 * ```
 *
 * Read values in [DesktopTask.doWork]:
 * ```kotlin
 * val endpoint = context.inputData.getString("endpoint") ?: return TaskResult.Failure("missing endpoint")
 * val retries  = context.inputData.getInt("retries", default = 3)
 * ```
 */
public class TaskData internal constructor(
    internal val map: Map<String, String> = emptyMap(),
) {
    // -- Typed accessors ------------------------------------------------------

    /** Returns the string value for [key], or `null` if absent. */
    public fun getString(key: String): String? = map[key]

    /** Returns the int value for [key], or [default] if absent or not parseable. */
    public fun getInt(
        key: String,
        default: Int = 0,
    ): Int = map[key]?.toIntOrNull() ?: default

    /** Returns the long value for [key], or [default] if absent or not parseable. */
    public fun getLong(
        key: String,
        default: Long = 0L,
    ): Long = map[key]?.toLongOrNull() ?: default

    /** Returns the boolean value for [key], or [default] if absent or not parseable. */
    public fun getBoolean(
        key: String,
        default: Boolean = false,
    ): Boolean = map[key]?.toBooleanStrictOrNull() ?: default

    /** Returns the double value for [key], or [default] if absent or not parseable. */
    public fun getDouble(
        key: String,
        default: Double = 0.0,
    ): Double = map[key]?.toDoubleOrNull() ?: default

    // -- Convenience ----------------------------------------------------------

    /** Returns the string value for [key], or `null` if absent. Shorthand for [getString]. */
    public operator fun get(key: String): String? = map[key]

    /** Returns `true` if no key-value pairs are present. */
    public fun isEmpty(): Boolean = map.isEmpty()

    /** Returns `true` if at least one key-value pair is present. */
    public fun isNotEmpty(): Boolean = map.isNotEmpty()

    // -- Builder --------------------------------------------------------------

    /** Builds a [TaskData] instance by accumulating typed key-value pairs. */
    public class Builder {
        private val map = mutableMapOf<String, String>()

        public fun putString(
            key: String,
            value: String,
        ): Builder = apply { map[key] = value }

        public fun putInt(
            key: String,
            value: Int,
        ): Builder = apply { map[key] = value.toString() }

        public fun putLong(
            key: String,
            value: Long,
        ): Builder = apply { map[key] = value.toString() }

        public fun putBoolean(
            key: String,
            value: Boolean,
        ): Builder = apply { map[key] = value.toString() }

        public fun putDouble(
            key: String,
            value: Double,
        ): Builder = apply { map[key] = value.toString() }

        public fun build(): TaskData = TaskData(map.toMap())
    }

    // -- Equality & companion -------------------------------------------------

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskData) return false
        return map == other.map
    }

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = "TaskData($map)"

    public companion object {
        /** Empty [TaskData] with no key-value pairs. */
        public val EMPTY: TaskData = TaskData()
    }
}
