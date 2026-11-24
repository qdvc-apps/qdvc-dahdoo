package qdvc.dahdoo.android.app.util

object Suggestions {
    /**
     * The most-used answers to "what was the child doing?", most frequent first
     * (ties broken by most recent), compared case-insensitively and shown in the
     * spelling used most recently. [recentFirst] is newest-first.
     */
    fun frequent(recentFirst: List<String>, limit: Int = 8): List<String> {
        data class Tally(val display: String, val firstSeen: Int, var count: Int)
        val tallies = LinkedHashMap<String, Tally>()
        recentFirst.forEachIndexed { index, raw ->
            val text = raw.trim()
            if (text.isEmpty()) return@forEachIndexed
            val key = text.lowercase()
            val t = tallies[key]
            if (t == null) tallies[key] = Tally(text, index, 1) else t.count++
        }
        return tallies.values
            .sortedWith(compareByDescending<Tally> { it.count }.thenBy { it.firstSeen })
            .take(limit)
            .map { it.display }
    }
}
