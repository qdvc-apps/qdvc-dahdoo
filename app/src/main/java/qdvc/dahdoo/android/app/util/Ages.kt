package qdvc.dahdoo.android.app.util

import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * A child's age on a given day, phrased the way parents say it: days for the
 * first fortnight, weeks until three months, months (and days) until two years,
 * then years and months.
 */
object Ages {

    /** Null when [on] is before [birthday]. */
    fun periodAt(birthday: LocalDate, on: LocalDate): Period? =
        if (on.isBefore(birthday)) null else Period.between(birthday, on)

    fun describe(birthday: LocalDate, on: LocalDate): String {
        val p = periodAt(birthday, on) ?: return "before birth"
        val days = ChronoUnit.DAYS.between(birthday, on)
        val totalMonths = p.toTotalMonths()
        return when {
            days < 14 -> plural(days, "day")
            totalMonths < 3 -> plural(days / 7, "week")
            p.years < 2 -> joinParts(plural(totalMonths, "month"), p.days.toLong(), "day")
            else -> joinParts(plural(p.years.toLong(), "year"), p.months.toLong(), "month")
        }
    }

    /** Compact form for list rows: `9d`, `5w`, `7m 12d`, `2y 4m`. */
    fun describeShort(birthday: LocalDate, on: LocalDate): String {
        val p = periodAt(birthday, on) ?: return "unborn"
        val days = ChronoUnit.DAYS.between(birthday, on)
        val totalMonths = p.toTotalMonths()
        return when {
            days < 14 -> "${days}d"
            totalMonths < 3 -> "${days / 7}w"
            p.years < 2 -> if (p.days == 0) "${totalMonths}m" else "${totalMonths}m ${p.days}d"
            else -> if (p.months == 0) "${p.years}y" else "${p.years}y ${p.months}m"
        }
    }

    private fun joinParts(first: String, secondCount: Long, secondUnit: String): String =
        if (secondCount == 0L) first else "$first, ${plural(secondCount, secondUnit)}"

    private fun plural(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"
}
