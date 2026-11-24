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

    @Test
    fun stepsFlattenRepeatsAndFormatTargets() {
        val doc = """{"steps":[
            {"duration":600,"power":{"units":"w","value":150,"max_value":180},"text":"Warm up"},
            {"reps":3,"steps":[{"duration":300,"power":{"units":"%ftp","value":105}},{"duration":180,"hr":{"units":"bpm","value":120}}]},
            {"duration":300}
        ],"duration":2340}"""
        val s = IntervalsParse.steps(doc)
        assertEquals(4, s.size)
        assertEquals("150–180 W", s[0].target)
        assertEquals(165.0, s[0].level!!, 1e-9)
        assertEquals(3, s[1].reps); assertTrue(s[1].firstOfBlock); assertEquals(2, s[1].blockSize)
        assertEquals("105% FTP", s[1].target)
        assertEquals("120 bpm", s[2].target); assertNull(s[2].level)
        assertNull(s[3].target)
        assertTrue(IntervalsParse.steps(null).isEmpty())
    }

    @Test
    fun streamsAndPowerZones() {
        val st = IntervalsParse.streams("""[{"type":"time","data":[0,1,2,3]},{"type":"watts","data":[100,null,250,400]},{"type":"latlng","data":[1],"data2":[2]}]""")
        assertEquals(setOf("time", "watts"), st.keys)
        val z = IntervalsParse.powerZoneSeconds(st.getValue("watts"), st.getValue("time"), 250.0)!!
        // 100 W = 40 % (Z1), 250 W = 100 % (Z4), 400 W = 160 % (Z7); the null sample is skipped.
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0), z)
    }

    @Test
    fun activityPowerFieldsAndIntensityFactor() {
        val a = IntervalsParse.activities("""[{"id":"i9","start_date_local":"2026-11-20T09:30:00","icu_average_watts":210,
            "icu_weighted_avg_watts":225,"icu_ftp":260,"icu_intensity":86.5}]""").single()
        assertEquals(225.0, a.normWatts!!, 1e-9)
        assertEquals(0.865, a.intensityFactor!!, 1e-9)
    }

    @Test
    fun hrZonesSplitAtTenPercentOfMax() {
        // Max 200: 90 bpm (45 %) is below the zones, 110 Z1, 150 Z3, 195 Z5.
        val z = IntervalsParse.hrZoneSeconds(listOf(90.0, 110.0, 150.0, 195.0), listOf(0.0, 1.0, 2.0, 3.0), 200.0)!!
        assertEquals(listOf(1.0, 0.0, 1.0, 0.0, 1.0), z)
    }

    @Test
    fun stravaSourceAndNoteAreKept() {
        val a = IntervalsParse.activities("""[{"id":"i9","start_date_local":"2026-11-20T09:30:00","source":"STRAVA","_note":"STRAVA activities are not available via the API"},
            {"id":"i10","start_date_local":"2026-11-21T09:30:00","source":"GARMIN_CONNECT"}]""")
        assertEquals(true, a[0].stravaOnly)
        assertEquals("STRAVA activities are not available via the API", a[0].note)
        assertEquals(false, a[1].stravaOnly)
    }
}
