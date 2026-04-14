package io.github.kdroidfilter.nucleus.scheduler

import java.time.DayOfWeek

/**
 * A calendar-based schedule expression that maps to systemd `OnCalendar=` syntax.
 *
 * Use the companion factory methods to create common schedules.
 *
 * @property expression the systemd OnCalendar value (e.g. `*-*-* 09:00:00`)
 */
public class CronExpression private constructor(
    public val expression: String,
) {
    override fun toString(): String = expression

    override fun equals(other: Any?): Boolean = other is CronExpression && expression == other.expression

    override fun hashCode(): Int = expression.hashCode()

    public companion object {
        private const val MAX_HOUR = 23
        private const val MAX_MINUTE = 59
        private const val DAY_ABBREV_LENGTH = 3

        private fun validateTime(
            hour: Int,
            minute: Int,
        ) {
            require(hour in 0..MAX_HOUR) { "hour must be 0..$MAX_HOUR, got $hour" }
            require(minute in 0..MAX_MINUTE) { "minute must be 0..$MAX_MINUTE, got $minute" }
        }

        /**
         * Every day at the given hour (and optional minute).
         *
         * Example: `everyDayAt(9)` → `*-*-* 09:00:00`
         */
        public fun everyDayAt(
            hour: Int,
            minute: Int = 0,
        ): CronExpression {
            validateTime(hour, minute)
            return CronExpression("*-*-* %02d:%02d:00".format(hour, minute))
        }

        /**
         * Every Monday at the given hour.
         *
         * Example: `everyMondayAt(9)` → `Mon *-*-* 09:00:00`
         */
        public fun everyMondayAt(
            hour: Int,
            minute: Int = 0,
        ): CronExpression = everyWeekdayAt(DayOfWeek.MONDAY, hour, minute)

        /**
         * Every specified day of the week at the given hour.
         */
        public fun everyWeekdayAt(
            day: DayOfWeek,
            hour: Int,
            minute: Int = 0,
        ): CronExpression {
            validateTime(hour, minute)
            val dayName =
                day.name
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
                    .take(DAY_ABBREV_LENGTH)
            return CronExpression("$dayName *-*-* %02d:%02d:00".format(hour, minute))
        }

        /**
         * Monday through Friday at the given hour.
         *
         * Example: `everyWeekdayAt(18)` → `Mon..Fri *-*-* 18:00:00`
         */
        public fun everyWeekdayAt(
            hour: Int,
            minute: Int = 0,
        ): CronExpression {
            validateTime(hour, minute)
            return CronExpression("Mon..Fri *-*-* %02d:%02d:00".format(hour, minute))
        }

        /**
         * Every hour at minute 0.
         *
         * Example: `everyHour()` → `*-*-* *:00:00`
         */
        public fun everyHour(): CronExpression = CronExpression("*-*-* *:00:00")

        /**
         * Custom systemd OnCalendar expression.
         *
         * See `systemd.time(7)` for the full syntax.
         */
        public fun custom(expression: String): CronExpression = CronExpression(expression)
    }
}
