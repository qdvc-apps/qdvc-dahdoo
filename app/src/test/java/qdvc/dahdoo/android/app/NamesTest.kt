package qdvc.dahdoo.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import qdvc.dahdoo.android.app.util.Durations
import qdvc.dahdoo.android.app.util.Names
import java.time.LocalDateTime

class NamesTest {
    private val t = LocalDateTime.of(2026, 11, 23, 14, 5, 33)

    @Test fun baseNameIsSortableAndColonFree() {
        assertEquals("2026-11-23_14-05-33", Names.baseNameFor(t) { false })
    }

    @Test fun collisionsGetNumberedSuffixes() {
        val taken = setOf("2026-11-23_14-05-33", "2026-11-23_14-05-33-2")
        assertEquals("2026-11-23_14-05-33-3", Names.baseNameFor(t) { it in taken })
    }

    @Test fun parseRoundTripsAndIgnoresSuffix() {
        assertEquals(t, Names.parseBaseName("2026-11-23_14-05-33"))
        assertEquals(t, Names.parseBaseName("2026-11-23_14-05-33-2"))
        assertNull(Names.parseBaseName("holiday"))
        assertNull(Names.parseBaseName("2026-13-23_14-05-33"))
    }

    @Test fun recognisesOwnFiles() {
        assertEquals("2026-11-23_14-05-33", Names.baseOfAudio("2026-11-23_14-05-33.m4a"))
        assertEquals("x", Names.baseOfAudio("x.M4A"))
        assertNull(Names.baseOfAudio(".m4a"))
        assertNull(Names.baseOfAudio("notes.txt"))
        assertEquals("a", Names.baseOfSidecar("a.json"))
        assertNull(Names.baseOfSidecar("a.m4a"))
    }

    @Test fun durations() {
        assertEquals("0:00", Durations.format(0))
        assertEquals("0:00", Durations.format(-5))
        assertEquals("0:07", Durations.format(7_999))
        assertEquals("12:34", Durations.format((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", Durations.format((3600 + 2 * 60 + 3) * 1000L))
    }
}
