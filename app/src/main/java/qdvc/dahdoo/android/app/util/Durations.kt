package qdvc.dahdoo.android.app.util

import java.util.Locale

object Durations {
    /** `0:07`, `12:34`, `1:02:03`. Negative values are treated as zero. */
    fun format(ms: Long): String {
        val totalSeconds = (if (ms < 0) 0 else ms) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        // Locale.ROOT keeps the digits ASCII whatever the phone's language.
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
