package qdvc.dahdoo.android.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import qdvc.dahdoo.android.app.data.CacheCodec
import qdvc.dahdoo.android.app.data.RecordingCodec
import qdvc.dahdoo.android.app.data.SettingsCodec
import qdvc.dahdoo.android.app.model.AppSettings
import qdvc.dahdoo.android.app.model.Child
import qdvc.dahdoo.android.app.model.RecordingMeta
import qdvc.dahdoo.android.app.model.ThemeMode
import java.time.LocalDate
import java.time.OffsetDateTime

class CodecsTest {
    private val at = OffsetDateTime.parse("2026-11-23T14:05:33+11:00")
    private val fallback = OffsetDateTime.parse("2000-01-01T00:00:00Z")

    @Test fun settingsRoundTrip() {
        val s = AppSettings(
            children = listOf(
                Child("c1", "Mei", LocalDate.of(2025, 3, 10)),
                Child("c2", "Kai", null),
            ),
            defaultChildId = "c1",
            themeMode = ThemeMode.DARK,
            keepScreenOn = false,
            maxRecordingMinutes = 30,
        )
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
    }

    @Test fun nullBirthdayIsNullNotTheStringNull() {
        val text = SettingsCodec.encode(AppSettings(children = listOf(Child("c2", "Kai", null))))
        assertNull(SettingsCodec.decode(text)!!.children.single().birthday)
        assertTrue(JSONObject(text).getJSONArray("children").getJSONObject(0).isNull("birthday"))
    }

    @Test fun settingsKeepUnknownKeys() {
        val previous = """{"format":"qdvc-dahdoo-settings","future":42,
            "children":[{"id":"c1","name":"Mei","birthday":null,"colour":"pink"}]}"""
        val text = SettingsCodec.encode(
            AppSettings(children = listOf(Child("c1", "Mei Mei", null))), previous
        )
        val root = JSONObject(text)
        assertEquals(42, root.getInt("future"))
        val child = root.getJSONArray("children").getJSONObject(0)
        assertEquals("pink", child.getString("colour"))
        assertEquals("Mei Mei", child.getString("name"))
    }

    @Test fun danglingDefaultChildIsDropped() {
        val text = """{"children":[],"defaultChildId":"gone"}"""
        assertNull(SettingsCodec.decode(text)!!.defaultChildId)
    }

    @Test fun foreignOrBrokenFilesAreRejected() {
        assertNull(SettingsCodec.decode("""{"format":"something-else"}"""))
        assertNull(SettingsCodec.decode("not json"))
        assertNull(SettingsCodec.decode(""))
        assertNull(RecordingCodec.decode("{", fallback))
    }

    @Test fun recordingRoundTripKeepsOffset() {
        val m = RecordingMeta(at, 12_345, "c1", "bath time", "dah doo", "first time!", true)
        val decoded = RecordingCodec.decode(RecordingCodec.encode(m, "a.m4a"), fallback)
        assertEquals(m, decoded)
        assertEquals("+11:00", decoded!!.recordedAt.offset.id)
    }

    @Test fun recordingFallsBackForMissingFields() {
        val m = RecordingCodec.decode("""{"childId":null}""", fallback)!!
        assertEquals(fallback, m.recordedAt)
        assertNull(m.childId)
        assertEquals("", m.saying)
        assertEquals(0L, m.durationMs)
    }

    @Test fun recordingKeepsUnknownKeys() {
        val text = RecordingCodec.encode(
            RecordingMeta(at, 1000), "a.m4a", """{"tags":["milestone"]}"""
        )
        assertEquals("milestone", JSONObject(text).getJSONArray("tags").getString(0))
    }

    @Test fun cacheRoundTrip() {
        val e = CacheCodec.Entry("2026-11-23_14-05-33", 99L, RecordingMeta(at, 500, saying = "ba"))
        assertEquals(listOf(e), CacheCodec.decode(CacheCodec.encode(listOf(e)), fallback))
        assertTrue(CacheCodec.decode("garbage", fallback).isEmpty())
    }
}
