package qdvc.dahdoo.android.app.data

import org.json.JSONArray
import org.json.JSONObject
import qdvc.dahdoo.android.app.model.AppSettings
import qdvc.dahdoo.android.app.model.Child
import qdvc.dahdoo.android.app.model.RecordingMeta
import qdvc.dahdoo.android.app.model.ThemeMode
import qdvc.dahdoo.android.app.util.Names
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/*
 * JSON for the files in the user's folder, written by hand with the platform's
 * org.json (no serialisation library, nothing reflective for R8 to break).
 *
 * Both encoders take the file's previous contents and overlay their own keys on
 * it, so a key this version does not know about -- written by a newer version,
 * or added by hand -- survives a save instead of being silently dropped.
 *
 * org.json trap: optString() on a JSON null returns the four-letter string
 * "null". Every nullable read goes through [optStringOrNull], which checks
 * isNull() first.
 */

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name)

// Catches everything, not just JSONException: these files live in a folder other
// apps (and backup mirrors) can touch, and a corrupt one must read as "absent",
// never crash the app.
private fun parseObject(text: String?): JSONObject? =
    if (text.isNullOrBlank()) null else try {
        JSONObject(text)
    } catch (e: Exception) {
        null
    }

private fun parseDate(text: String?): LocalDate? =
    if (text.isNullOrBlank()) null else try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }

object SettingsCodec {
    const val FORMAT = "qdvc-dahdoo-settings"
    const val VERSION = 1

    fun encode(settings: AppSettings, previous: String? = null): String {
        val root = parseObject(previous) ?: JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        val previousChildren = root.optJSONArray("children")
        val children = JSONArray()
        settings.children.forEach { child ->
            // Keep any unknown keys a child object had before.
            val obj = findById(previousChildren, child.id) ?: JSONObject()
            obj.put("id", child.id)
            obj.put("name", child.name)
            obj.put("birthday", child.birthday?.toString() ?: JSONObject.NULL)
            children.put(obj)
        }
        root.put("children", children)
        root.put("defaultChildId", settings.defaultChildId ?: JSONObject.NULL)
        root.put("themeMode", settings.themeMode.name.lowercase())
        root.put("keepScreenOn", settings.keepScreenOn)
        root.put("maxRecordingMinutes", settings.maxRecordingMinutes)
        return root.toString(2) + "\n"
    }

    /** Null if [text] is not a settings file this app can read. */
    fun decode(text: String?): AppSettings? {
        val root = parseObject(text) ?: return null
        val fmt = root.optStringOrNull("format")
        if (fmt != null && fmt != FORMAT) return null
        val defaults = AppSettings()
        val children = mutableListOf<Child>()
        val arr = root.optJSONArray("children")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optStringOrNull("id")?.takeIf { it.isNotBlank() } ?: continue
                val name = obj.optStringOrNull("name") ?: ""
                children += Child(id, name, parseDate(obj.optStringOrNull("birthday")))
            }
        }
        val mode = when (root.optStringOrNull("themeMode")?.lowercase()) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.AUTOMATIC
        }
        val defaultChild = root.optStringOrNull("defaultChildId")
            ?.takeIf { wanted -> children.any { it.id == wanted } }
        return AppSettings(
            children = children,
            defaultChildId = defaultChild,
            themeMode = mode,
            keepScreenOn = root.optBoolean("keepScreenOn", defaults.keepScreenOn),
            maxRecordingMinutes = root.optInt("maxRecordingMinutes", defaults.maxRecordingMinutes)
                .coerceAtLeast(0),
        )
    }

    private fun findById(arr: JSONArray?, id: String): JSONObject? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optStringOrNull("id") == id) return obj
        }
        return null
    }
}

object RecordingCodec {
    const val FORMAT = "qdvc-dahdoo-recording"
    const val VERSION = 1

    fun encodeObject(meta: RecordingMeta, audioFileName: String, previous: String? = null): JSONObject {
        val root = parseObject(previous) ?: JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("audioFile", audioFileName)
        root.put("recordedAt", meta.recordedAt.toString())
        root.put("durationMs", meta.durationMs)
        root.put("childId", meta.childId ?: JSONObject.NULL)
        root.put("doing", meta.doing)
        root.put("saying", meta.saying)
        root.put("notes", meta.notes)
        root.put("favourite", meta.favourite)
        return root
    }

    fun encode(meta: RecordingMeta, audioFileName: String, previous: String? = null): String =
        encodeObject(meta, audioFileName, previous).toString(2) + "\n"

    /**
     * Null if [text] is unreadable. [fallbackRecordedAt] is used when the file
     * lacks a usable timestamp (e.g. edited by hand).
     */
    fun decode(text: String?, fallbackRecordedAt: OffsetDateTime): RecordingMeta? {
        val root = parseObject(text) ?: return null
        return decodeObject(root, fallbackRecordedAt)
    }

    fun decodeObject(root: JSONObject, fallbackRecordedAt: OffsetDateTime): RecordingMeta? {
        val fmt = root.optStringOrNull("format")
        if (fmt != null && fmt != FORMAT) return null
        val recordedAt = root.optStringOrNull("recordedAt")?.let {
            try {
                OffsetDateTime.parse(it)
            } catch (e: DateTimeParseException) {
                null
            }
        } ?: fallbackRecordedAt
        return RecordingMeta(
            recordedAt = recordedAt,
            durationMs = root.optLong("durationMs", 0L).coerceAtLeast(0L),
            childId = root.optStringOrNull("childId")?.takeIf { it.isNotBlank() },
            doing = root.optStringOrNull("doing") ?: "",
            saying = root.optStringOrNull("saying") ?: "",
            notes = root.optStringOrNull("notes") ?: "",
            favourite = root.optBoolean("favourite", false),
        )
    }
}

/** App-private cache of what the folder held last time, for an instant list. */
object CacheCodec {
    data class Entry(val baseName: String, val sidecarModified: Long, val meta: RecordingMeta)

    fun encode(entries: Collection<Entry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("base", e.baseName)
                    .put("sidecarModified", e.sidecarModified)
                    .put("meta", RecordingCodec.encodeObject(e.meta, Names.audioName(e.baseName)))
            )
        }
        return JSONObject().put("version", 1).put("entries", arr).toString()
    }

    fun decode(text: String?, fallbackRecordedAt: OffsetDateTime): List<Entry> {
        val root = parseObject(text) ?: return emptyList()
        val arr = root.optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val base = obj.optStringOrNull("base") ?: continue
            val metaObj = obj.optJSONObject("meta") ?: continue
            val meta = RecordingCodec.decodeObject(metaObj, fallbackRecordedAt) ?: continue
            out += Entry(base, obj.optLong("sidecarModified", 0L), meta)
        }
        return out
    }
}
