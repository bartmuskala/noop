package com.noop.intervals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IntervalsParseTest {

    @Test
    fun activitiesReadKnownFieldsAndSkipBrokenRows() {
        val json = """[
          {"id":"i1","start_date_local":"2026-11-20T09:30:00","type":"Ride","name":"Morning ride",
           "moving_time":3600,"distance":30500.5,"total_elevation_gain":210,"icu_training_load":64,
           "average_heartrate":141,"max_heartrate":172,"calories":820},
          {"start_date_local":"2026-11-21T09:30:00"},
          {"id":"i3","start_date_local":"2026-11-22T10:00:00","icu_training_load":null}
        ]"""
        val a = IntervalsParse.activities(json)
        assertEquals(2, a.size)
        assertEquals("2026-11-20", a[0].day)
        assertEquals(30500.5, a[0].distanceM!!, 1e-9)
        assertEquals(64.0, a[0].load!!, 1e-9)
        assertNull(a[1].load)
        assertTrue(IntervalsParse.activities("not json").isEmpty())
    }

    @Test
    fun latLngAcceptsParallelArraysAndPairsAndDropsNoFix() {
        val parallel = """[{"type":"latlng","data":[51.0,51.1,0.0],"data2":[4.0,4.1,0.0]}]"""
        assertEquals(listOf(51.0 to 4.0, 51.1 to 4.1), IntervalsParse.latLng(parallel))
        val pairs = """[{"type":"latlng","data":[[51.0,4.0],[51.2,4.2]]}]"""
        assertEquals(listOf(51.0 to 4.0, 51.2 to 4.2), IntervalsParse.latLng(pairs))
        assertTrue(IntervalsParse.latLng("""[{"type":"watts","data":[100,200]}]""").isEmpty())
    }

    @Test
    fun eventsAndWellness() {
        val e = IntervalsParse.events("""[{"id":7,"start_date_local":"2026-11-24T00:00:00","category":"WORKOUT","name":"Sweet spot","icu_training_load":80},
            {"id":8,"start_date_local":"2026-11-25T00:00:00","category":"NOTE","name":"Travel"}]""")
        assertEquals(listOf(true, false), e.map { it.isWorkout })
        val w = IntervalsParse.wellness("""[{"id":"2026-11-22","ctl":50.5,"atl":60.0},{"id":"2026-11-21","ctl":50.0,"atl":58.0}]""")
        assertEquals(listOf("2026-11-21", "2026-11-22"), w.map { it.day })
    }

    @Test
    fun projectionAppliesPlannedLoadAndRestDays() {
        val p = IntervalsParse.project(LocalDate.parse("2026-11-22"), 42.0, 42.0,
            mapOf("2026-11-23" to 84.0), LocalDate.parse("2026-11-24"))
        assertEquals(2, p.size)
        assertEquals(43.0, p[0].second, 1e-9)          // 42 + (84 − 42) / 42
        assertEquals(48.0, p[0].third, 1e-9)           // 42 + (84 − 42) / 7
        assertEquals(43.0 - 43.0 / 42.0, p[1].second, 1e-9) // a rest day decays
    }
}
