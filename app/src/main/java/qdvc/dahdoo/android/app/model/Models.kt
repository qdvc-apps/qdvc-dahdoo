package qdvc.dahdoo.android.app.model

import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime

/** One child in the family list. [id] is stable; names and birthdays can change. */
data class Child(
    val id: String,
    val name: String,
    val birthday: LocalDate?,
)

enum class ThemeMode { AUTOMATIC, LIGHT, DARK }

/**
 * Everything the user configures. Persisted as `dahdoo-settings.json` in the
 * user's folder (the system of record) and mirrored in app-private storage so
 * the first frame can be themed without waiting for the folder.
 */
data class AppSettings(
    val children: List<Child> = emptyList(),
    /** Assigned to each new recording as it is saved. Null means "nobody yet". */
    val defaultChildId: String? = null,
    val themeMode: ThemeMode = ThemeMode.AUTOMATIC,
    val keepScreenOn: Boolean = true,
    /** Recordings stop themselves after this long. 0 means no limit. */
    val maxRecordingMinutes: Int = DEFAULT_MAX_MINUTES,
) {
    fun child(id: String?): Child? =
        if (id == null) null else children.firstOrNull { it.id == id }

    companion object {
        const val DEFAULT_MAX_MINUTES = 10
        val MAX_MINUTES_CHOICES = listOf(2, 5, 10, 30, 0)
    }
}

/**
 * The catalogue data for one recording, stored in its sidecar JSON file next to
 * the audio. [recordedAt] keeps the offset it was recorded with, so the time of
 * day shown is the one the parent lived through, even after travelling.
 */
data class RecordingMeta(
    val recordedAt: OffsetDateTime,
    /** 0 when unknown (an audio file found without its sidecar). */
    val durationMs: Long,
    val childId: String? = null,
    val doing: String = "",
    val saying: String = "",
    val notes: String = "",
    val favourite: Boolean = false,
)

/** Where a recording's bytes currently are. */
sealed interface RecordingLocation {
    /**
     * In the user's folder. Document ids are deliberately not carried here: they
     * change on rename and embed folder paths, so the repository resolves them
     * on demand and never persists them.
     */
    data object InFolder : RecordingLocation

    /**
     * Still in app-private storage, waiting to be copied into the folder (no
     * folder chosen yet, or the folder was unreachable when it was saved).
     */
    data class Pending(val audioFile: File) : RecordingLocation
}

data class Recording(
    /** The file name without extension, e.g. `2026-11-23_14-05-33`. Stable. */
    val baseName: String,
    val meta: RecordingMeta,
    val location: RecordingLocation,
) {
    val isPending: Boolean get() = location is RecordingLocation.Pending
}

/** Handed from the recorder to the repository when a recording stops. */
data class FinishedRecording(
    val baseName: String,
    val audioFile: File,
    val startedAt: OffsetDateTime,
    val durationMs: Long,
)
