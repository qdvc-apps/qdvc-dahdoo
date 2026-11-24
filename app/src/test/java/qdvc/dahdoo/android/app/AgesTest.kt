package qdvc.dahdoo.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import qdvc.dahdoo.android.app.util.Ages
import java.time.LocalDate

class AgesTest {
    private val born = LocalDate.of(2025, 3, 10)

    private fun long(on: String) = Ages.describe(born, LocalDate.parse(on))
    private fun short(on: String) = Ages.describeShort(born, LocalDate.parse(on))

    @Test fun beforeBirth() {
        assertEquals("before birth", long("2025-03-09"))
        assertEquals("unborn", short("2025-03-09"))
        assertNull(Ages.periodAt(born, LocalDate.parse("2025-03-09")))
    }

    @Test fun firstFortnightInDays() {
        assertEquals("0 days", long("2025-03-10"))
        assertEquals("1 day", long("2025-03-11"))
        assertEquals("13 days", long("2025-03-23"))
        assertEquals("13d", short("2025-03-23"))
    }

    @Test fun weeksUntilThreeMonths() {
        assertEquals("2 weeks", long("2025-03-24"))
        assertEquals("2 weeks", long("2025-03-30"))
        assertEquals("3 weeks", long("2025-03-31"))
        // 91 days, but Period says 2 months 30 days, so still weeks.
        assertEquals("13 weeks", long("2025-06-09"))
        assertEquals("13w", short("2025-06-09"))
    }

    @Test fun monthsAndDaysUntilTwo() {
        assertEquals("3 months", long("2025-06-10"))
        assertEquals("3m", short("2025-06-10"))
        assertEquals("7 months, 12 days", long("2025-10-22"))
        assertEquals("7m 12d", short("2025-10-22"))
        assertEquals("15 months, 1 day", long("2026-06-11"))
        assertEquals("23 months, 27 days", long("2027-03-09"))
    }

    @Test fun yearsAndMonthsFromTwo() {
        assertEquals("2 years", long("2027-03-10"))
        assertEquals("2y", short("2027-03-10"))
        assertEquals("2 years, 1 month", long("2027-04-10"))
        assertEquals("3 years, 4 months", long("2028-07-31"))
        assertEquals("3y 4m", short("2028-07-31"))
    }

    @Test fun leapDayBirthday() {
        val leap = LocalDate.of(2024, 2, 29)
        // java.time counts 28 Feb as not yet the anniversary of 29 Feb.
        assertEquals("11 months, 30 days", Ages.describe(leap, LocalDate.of(2025, 2, 28)))
        assertEquals("23 months, 30 days", Ages.describe(leap, LocalDate.of(2026, 2, 28)))
        assertEquals("2 years", Ages.describe(leap, LocalDate.of(2026, 3, 1)))
    }
}
