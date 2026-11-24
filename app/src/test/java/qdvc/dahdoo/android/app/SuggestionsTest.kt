package qdvc.dahdoo.android.app

import org.junit.Assert.assertEquals
import org.junit.Test
import qdvc.dahdoo.android.app.util.Suggestions

class SuggestionsTest {
    @Test fun mostFrequentFirstThenMostRecent() {
        val newestFirst = listOf("Bath time", "playing", "", "  ", "bath time", "Reading", "Playing", "bath time")
        assertEquals(listOf("Bath time", "playing", "Reading"), Suggestions.frequent(newestFirst))
    }

    @Test fun respectsLimit() {
        assertEquals(listOf("a", "b"), Suggestions.frequent(listOf("a", "b", "c"), limit = 2))
    }

    @Test fun emptyInEmptyOut() {
        assertEquals(emptyList<String>(), Suggestions.frequent(listOf("", " ")))
    }
}
