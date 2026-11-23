package com.noop.ui

import com.noop.data.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalSynonymTest {
    @Test
    fun whoopPhrasingsJoinTheirStarterQuestion() {
        assertEquals("Did you drink any alcohol?", canonicalJournalQuestion("Have any alcoholic drinks?"))
        assertEquals("Did you eat close to bedtime?", canonicalJournalQuestion("Ate food close to bedtime?"))
        assertEquals("Did you view a screen in bed?", canonicalJournalQuestion("Viewed a screen device in bed?"))
        assertEquals("Did you feel stressed?", canonicalJournalQuestion("Feeling stressed?"))
        assertEquals("Did you drink any alcohol?", canonicalJournalQuestion("Did you drink any alcohol?"))
    }

    @Test
    fun partialOverlapsStaySeparate() {
        assertEquals("Have any caffeine?", canonicalJournalQuestion("Have any caffeine?"))
        assertEquals("Practiced stress management?", canonicalJournalQuestion("Practiced stress management?"))
        assertEquals("Hydrated sufficiently?", canonicalJournalQuestion("Hydrated sufficiently?"))
    }

    @Test
    fun mergeEvaluatesBothPhrasingsAsOneAndTheInAppAnswerWins() {
        val imported = listOf(
            JournalEntry(deviceId = "my-whoop", day = "2026-05-01", question = "Have any alcoholic drinks?", answeredYes = true),
            JournalEntry(deviceId = "my-whoop", day = "2026-05-02", question = "Have any alcoholic drinks?", answeredYes = false),
        )
        val native = listOf(JournalEntry(deviceId = JOURNAL_DEVICE_ID, day = "2026-05-02", question = "Did you drink any alcohol?", answeredYes = true))
        val merged = mergeJournalEntries(imported, native)
        assertEquals(listOf("Did you drink any alcohol?", "Did you drink any alcohol?"), merged.map { it.question })
        assertEquals(listOf(true, true), merged.map { it.answeredYes })
    }
}
