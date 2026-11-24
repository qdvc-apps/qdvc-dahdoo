package qdvc.dahdoo.android.app.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * File naming for the folder. Names are fixed at the moment of recording and
 * never change afterwards: catalogue edits go into the sidecar, so a backup
 * mirror never sees renames, and nothing has to chase the new document id a
 * SAF rename allocates.
 */
object Names {
    const val RECORDINGS_DIR = "recordings"
    const val SETTINGS_FILE = "dahdoo-settings.json"
    const val README_FILE = "dahdoo-README.txt"
    const val AUDIO_EXT = ".m4a"
    const val SIDECAR_EXT = ".json"

    // Hyphens rather than colons: colons are illegal on FAT/exFAT SD cards and on
    // the SMB shares a backup app is likely to mirror to.
    private val FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
    private const val STAMP_LENGTH = 19

    /** `2026-11-23_14-05-33`, or with `-2`, `-3`… appended if [taken]. */
    fun baseNameFor(time: LocalDateTime, taken: (String) -> Boolean): String {
        val base = FORMAT.format(time)
        if (!taken(base)) return base
        var n = 2
        while (taken("$base-$n")) n++
        return "$base-$n"
    }

    /** Recovers the recording time from a base name, ignoring any `-n` suffix. */
    fun parseBaseName(base: String): LocalDateTime? {
        if (base.length < STAMP_LENGTH) return null
        return try {
            LocalDateTime.parse(base.substring(0, STAMP_LENGTH), FORMAT)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    fun audioName(base: String) = base + AUDIO_EXT
    fun sidecarName(base: String) = base + SIDECAR_EXT

    /** The base name if [fileName] is a recording's audio file, else null. */
    fun baseOfAudio(fileName: String): String? =
        if (fileName.endsWith(AUDIO_EXT, ignoreCase = true) && fileName.length > AUDIO_EXT.length) {
            fileName.substring(0, fileName.length - AUDIO_EXT.length)
        } else {
            null
        }

    /** The base name if [fileName] is a sidecar, else null. */
    fun baseOfSidecar(fileName: String): String? =
        if (fileName.endsWith(SIDECAR_EXT, ignoreCase = true) && fileName.length > SIDECAR_EXT.length) {
            fileName.substring(0, fileName.length - SIDECAR_EXT.length)
        } else {
            null
        }
}
