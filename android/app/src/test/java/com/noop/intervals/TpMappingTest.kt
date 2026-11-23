package com.noop.intervals

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpMappingTest {
    private val th = TpMapping.Thresholds(ftp = 250.0, thresholdHr = 170.0, runVelocity = 4.0)

    @Test
    fun sportAndCategory() {
        assertEquals("VirtualRide", TpMapping.sportType(2, 6))
        assertEquals("Ride", TpMapping.sportType(2, null))
        assertEquals("Workout", TpMapping.sportType(77, null))
        assertEquals("NOTE" to null, TpMapping.classify(JSONObject("""{"workoutTypeValueId":7}""")))
        assertEquals("RACE_A" to "Run", TpMapping.classify(JSONObject("""{"workoutTypeValueId":6}""")))
        assertEquals("NOTE" to null, TpMapping.classify(JSONObject("""{"workoutTypeValueId":2}""")))
        assertEquals("WORKOUT" to "Ride", TpMapping.classify(JSONObject("""{"workoutTypeValueId":2,"totalTimePlanned":1.5}""")))
    }

    @Test
    fun structureBecomesAbsoluteWorkoutDoc() {
        val structure = JSONObject("""{"primaryIntensityMetric":"percentOfFtp","structure":[
            {"type":"step","length":{"unit":"repetition","value":1},"steps":[
              {"name":"Warm up","length":{"unit":"minute","value":10},"targets":[{"minValue":50,"maxValue":60}],"intensityClass":"warmUp"}]},
            {"type":"repetition","length":{"unit":"repetition","value":4},"steps":[
              {"name":"Active","length":{"unit":"second","value":300},"targets":[{"minValue":100,"maxValue":105}],"intensityClass":"active"},
              {"name":"Rest","length":{"unit":"second","value":180},"targets":[{"minValue":55}],"intensityClass":"rest"}]}]}""")
        val doc = TpMapping.workoutDoc(structure, "Ride", th)!!
        val steps = doc.getJSONArray("steps")
        assertEquals(2, steps.length())
        val wu = steps.getJSONObject(0)
        assertEquals(600, wu.getInt("duration"))
        assertEquals("w", wu.getJSONObject("power").getString("units"))
        assertEquals(125, wu.getJSONObject("power").getInt("value"))
        assertEquals(150, wu.getJSONObject("power").getInt("max_value"))
        val rep = steps.getJSONObject(1)
        assertEquals(4, rep.getInt("reps"))
        assertEquals("Recovery", rep.getJSONArray("steps").getJSONObject(1).getString("text"))
        assertFalse(rep.getJSONArray("steps").getJSONObject(0).has("text")) // "Active" is not a label
        assertEquals(600 + 4 * 480, doc.getInt("duration"))
    }

    @Test
    fun paceTargetsBecomeSecondsPerKilometre() {
        val structure = JSONObject("""{"primaryIntensityMetric":"percentOfThresholdPace","structure":[
            {"type":"step","length":{"unit":"repetition","value":1},"steps":[
              {"length":{"unit":"minute","value":20},"targets":[{"minValue":80,"maxValue":100}]}]}]}""")
        val pace = TpMapping.workoutDoc(structure, "Run", th)!!.getJSONArray("steps").getJSONObject(0).getJSONObject("pace")
        assertEquals("s/km", pace.getString("units"))
        assertEquals(250, pace.getInt("value"))      // 100 % of 4 m/s: 1000 / 4
        assertEquals(313, pace.getInt("max_value"))  // 80 %: 1000 / 3.2 = 312.5
    }

    @Test
    fun payloadCarriesIdHashAndPlan() {
        val w = JSONObject("""{"workoutId":42,"workoutDay":"2026-11-24T00:00:00","title":"Sweet spot","workoutTypeValueId":2,
            "totalTimePlanned":1.0,"tssPlanned":70,"ifPlanned":0.84,"description":"Steady","coachComments":"Keep it smooth"}""")
        val p = TpMapping.payload(w, th)
        assertEquals("tp:42", p.getString("external_id"))
        assertEquals("WORKOUT", p.getString("category"))
        assertEquals(3600, p.getInt("moving_time"))
        assertTrue(p.getString("description").contains("Coach:\nKeep it smooth"))
        val h = TpMapping.hashOf(p.getString("description"))!!
        assertEquals(h, TpMapping.hashOf(TpMapping.payload(w, th).getString("description"))) // stable
        assertEquals(42L, TpMapping.tpIdOf("tp:42"))
        assertNull(TpMapping.tpIdOf("other:1"))
    }
}
