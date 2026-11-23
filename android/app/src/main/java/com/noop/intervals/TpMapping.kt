package com.noop.intervals

/*
 * Ported from Sladck/trainingpeaks-to-intervals-icu (src/tp_icu_sync/mapping, sync.py), under its licence:
 *
 * MIT License
 *
 * Copyright (c) 2026 Sladck
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * TrainingPeaks planned workout → intervals.icu calendar event (fork).
 *
 * A Kotlin port of the mapping in Sladck/trainingpeaks-to-intervals-icu (MIT, © 2026 Sladck):
 * the sport mapping, the category rules (Day Off and empty entries become NOTEs, races RACE_A), the
 * structure → `workout_doc` translation with percent-of-threshold targets made absolute from the
 * athlete's TrainingPeaks thresholds, the description assembly, and the `[tp-sync hash=…]` footer
 * that keeps re-syncs idempotent. Pure (org.json only) so it is unit-tested on the JVM.
 */
object TpMapping {

    data class Thresholds(val ftp: Double? = null, val thresholdHr: Double? = null,
                          val runVelocity: Double? = null, val swimVelocity: Double? = null)

    const val EXTERNAL_ID_PREFIX = "tp:"
    private val HASH_TAG = Regex("""\[tp-sync hash=([0-9a-f]{12})\]""")
    private const val TP_TYPE_RACE = 6
    private const val TP_TYPE_DAY_OFF = 7
    private val POWER_SPORTS = setOf("Ride", "VirtualRide", "MountainBikeRide")
    private val PACE_SPORTS = setOf("Run", "VirtualRun", "Walk", "Hike", "Swim", "Rowing")

    private val SUBTYPE = mapOf(
        1 to "Swim", 2 to "Swim", 3 to "Ride", 4 to "Ride", 5 to "Ride", 6 to "VirtualRide", 7 to "Ride",
        8 to "Ride", 9 to "Ride", 47 to "Ride", 49 to "VirtualRide", 10 to "Run", 11 to "Run", 12 to "Run",
        13 to "VirtualRun", 50 to "VirtualRun", 41 to "Hike", 42 to "Walk", 43 to "Hike", 44 to "Hike",
        14 to "MountainBikeRide", 15 to "MountainBikeRide", 16 to "MountainBikeRide", 17 to "MountainBikeRide",
        18 to "MountainBikeRide", 19 to "MountainBikeRide", 39 to "Rowing", 51 to "Rowing",
    )
    private val PARENT = mapOf(
        1 to "Swim", 2 to "Ride", 3 to "Run", 4 to "Workout", 5 to "Workout", 8 to "MountainBikeRide",
        9 to "WeightTraining", 11 to "NordicSki", 12 to "Rowing", 13 to "Walk", 29 to "WeightTraining", 100 to "Workout",
    )

    fun sportType(typeId: Int?, subTypeId: Int?): String =
        subTypeId?.let { SUBTYPE[it] } ?: typeId?.let { PARENT[it] } ?: "Workout"

    private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun JSONObject.txt(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null

    /** (category, sport type or null) for one TP workout. */
    fun classify(w: JSONObject): Pair<String, String?> {
        val type = w.int("workoutTypeValueId")
        if (type == TP_TYPE_DAY_OFF) return "NOTE" to null
        if (type == TP_TYPE_RACE) {
            val s = sportType(null, w.int("workoutSubTypeId"))
            return "RACE_A" to (if (s == "Workout") "Run" else s)
        }
        val hasContent = (w.dbl("totalTimePlanned") ?: 0.0) > 0 || (w.dbl("distancePlanned") ?: 0.0) > 0 ||
            w.optJSONObject("structure") != null || (w.dbl("tssPlanned") ?: 0.0) > 0
        if (!hasContent) return "NOTE" to null
        return "WORKOUT" to sportType(type, w.int("workoutSubTypeId"))
    }

    private fun seconds(len: JSONObject?): Int? {
        val v = len?.dbl("value") ?: return null
        return when (len.optString("unit").lowercase()) {
            "second" -> v.roundToInt(); "minute" -> (v * 60).roundToInt(); "hour" -> (v * 3600).roundToInt(); else -> null
        }
    }

    private fun metres(len: JSONObject?): Int? {
        val v = len?.dbl("value") ?: return null
        return when (len.optString("unit").lowercase()) {
            "metre", "meter" -> v.roundToInt(); "kilometre", "kilometer" -> (v * 1000).roundToInt()
            "mile" -> (v * 1609.344).roundToInt(); else -> null
        }
    }

    private fun metricToIcu(metric: String, type: String): Pair<String?, String> = when (metric) {
        "percentofftp" -> when (type) { in POWER_SPORTS -> "power" to "%ftp"; "Run", "VirtualRun" -> "pace" to "%pace"; else -> "hr" to "%lthr" }
        "watts", "power" -> "power" to "w"
        "percentofthresholdhr" -> "hr" to "%lthr"
        "percentofmaxhr" -> "hr" to "%hr"
        "heartrate", "bpm" -> "hr" to "bpm"
        "percentofthresholdpace" -> if (type in PACE_SPORTS) "pace" to "%pace" else "hr" to "%lthr"
        "pace", "speed" -> "pace" to "/km"
        else -> null to ""
    }

    private fun num(v: Double): Any = if (v % 1.0 == 0.0) v.toLong() else Math.round(v * 100) / 100.0

    /** {key: {units, value, max_value}} for a step's first target, absolute where thresholds allow. */
    private fun target(targets: JSONArray?, metric: String, type: String, th: Thresholds?): Pair<String, JSONObject>? {
        val t = targets?.optJSONObject(0) ?: return null
        val lo = t.dbl("minValue"); val hi = t.dbl("maxValue")
        if (lo == null && hi == null) return null
        val (key, units) = metricToIcu(metric.lowercase(), type)
        if (key == null) return null
        var u = units; var a: Double? = lo; var b: Double? = hi; var absolute = false
        if (th != null) {
            if (key == "power" && units == "%ftp" && th.ftp != null) {
                u = "w"; a = lo?.let { (it * th.ftp / 100).roundToLong().toDouble() }; b = hi?.let { (it * th.ftp / 100).roundToLong().toDouble() }; absolute = true
            } else if (key == "hr" && units == "%lthr" && th.thresholdHr != null) {
                u = "bpm"; a = lo?.let { (it * th.thresholdHr / 100).roundToLong().toDouble() }; b = hi?.let { (it * th.thresholdHr / 100).roundToLong().toDouble() }; absolute = true
            } else if (key == "pace" && units == "%pace") {
                val swim = type == "Swim"
                val v = if (swim) th.swimVelocity else th.runVelocity
                if (v != null && v > 0) {
                    val dist = if (swim) 100.0 else 1000.0
                    val slow = lo?.let { (dist / (v * it / 100)).roundToLong().toDouble() }
                    val fast = hi?.let { (dist / (v * it / 100)).roundToLong().toDouble() }
                    u = if (swim) "s/100m" else "s/km"
                    a = fast ?: slow; b = if (fast != null && slow != null) slow else null; absolute = true
                }
            }
        }
        val o = JSONObject().put("units", u)
        if (a != null && b != null && a != b) { o.put("value", if (absolute) a.toLong() else num(a)); o.put("max_value", if (absolute) b.toLong() else num(b)) }
        else if (a != null) { o.put("value", if (absolute) a.toLong() else num(a)); if (!absolute && b != null) o.put("max_value", num(b)) }
        else if (b != null) o.put("value", if (absolute) b.toLong() else num(b))
        return key to o
    }

    private val CLASS_LABEL = mapOf("warmup" to "Warm up", "warm_up" to "Warm up", "cooldown" to "Cool down",
        "cool_down" to "Cool down", "active" to "", "rest" to "Recovery", "recovery" to "Recovery")

    /** The intervals.icu `workout_doc` for a TP structure, or null when it has no timed or measured steps. */
    fun workoutDoc(structure: JSONObject?, type: String, th: Thresholds?): JSONObject? {
        val blocks = structure?.optJSONArray("structure") ?: return null
        val metric = structure.optString("primaryIntensityMetric")
        val steps = JSONArray(); var totalS = 0L; var totalM = 0L
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val len = block.optJSONObject("length")
            val reps = if (len?.optString("unit")?.lowercase() == "repetition") (len.dbl("value")?.toInt() ?: 1) else 1
            val rendered = ArrayList<JSONObject>()
            val src = block.optJSONArray("steps") ?: JSONArray()
            for (k in 0 until src.length()) {
                val st = src.optJSONObject(k) ?: continue
                val secs = seconds(st.optJSONObject("length")); val m = metres(st.optJSONObject("length"))
                if (secs == null && m == null) continue
                val out = JSONObject()
                secs?.let { out.put("duration", it) }; m?.let { out.put("distance", it) }
                target(st.optJSONArray("targets"), metric, type, th)?.let { (key, o) -> out.put(key, o) }
                val name = st.txt("name")
                val label = CLASS_LABEL[st.optString("intensityClass").lowercase()]
                if (name != null && name.lowercase() !in setOf("active", "rest")) out.put("text", name)
                else if (!label.isNullOrEmpty()) out.put("text", label)
                rendered += out
            }
            if (rendered.isEmpty()) continue
            totalS += reps * rendered.sumOf { it.optLong("duration", 0) }
            totalM += reps * rendered.sumOf { it.optLong("distance", 0) }
            if (reps > 1) steps.put(JSONObject().put("reps", reps).put("steps", JSONArray(rendered)))
            else rendered.forEach { steps.put(it) }
        }
        if (steps.length() == 0) return null
        return JSONObject().put("steps", steps).apply {
            if (totalS > 0) put("duration", totalS); if (totalM > 0) put("distance", totalM)
        }
    }

    /** The description body: TP text, coach comments, the comment thread and planned TSS / IF. */
    fun description(w: JSONObject, isNote: Boolean): String {
        val parts = ArrayList<String>()
        w.txt("description")?.trim()?.let { parts += it }
        w.txt("coachComments")?.trim()?.let { parts += "Coach:\n$it" }
        w.optJSONArray("workoutComments")?.let { arr ->
            val lines = (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = c.txt("comment")?.trim() ?: return@mapNotNull null
                val who = c.txt("commentByPersonName") ?: c.txt("commentByName") ?: c.txt("authorName") ?: "Comment"
                val whenS = (c.txt("dateAdded") ?: c.txt("commentDate"))?.take(10)
                "- ${if (whenS != null) "$who ($whenS)" else who}: $text"
            }
            if (lines.isNotEmpty()) parts += "Comments:\n" + lines.joinToString("\n")
        }
        if (!isNote) {
            val meta = listOfNotNull(
                w.dbl("tssPlanned")?.takeIf { it > 0 }?.let { "Planned TSS: ${it.roundToInt()}" },
                w.dbl("ifPlanned")?.takeIf { it > 0 }?.let { "Planned IF: %.2f".format(java.util.Locale.US, it) },
            )
            if (meta.isNotEmpty()) parts += meta.joinToString("\n")
        }
        return parts.joinToString("\n\n")
    }

    fun day(w: JSONObject): String = w.optString("workoutDay").take(10)

    /** The event body for POST/PUT, with the content hash in its description footer. */
    fun payload(w: JSONObject, th: Thresholds?): JSONObject {
        val (category, type) = classify(w)
        val isNote = category == "NOTE"
        val doc = if (isNote) null else workoutDoc(w.optJSONObject("structure"), type ?: "", th)
        val body = description(w, isNote)
        val durS = w.dbl("totalTimePlanned")?.let { (it * 3600).roundToInt() }?.takeIf { it > 0 }
        val start = w.txt("startTimePlanned") ?: "${day(w)}T00:00:00"
        val hash = hash(listOf(w.optLong("workoutId").toString(), day(w), start, w.txt("title") ?: "", category,
            type ?: "", durS?.toString() ?: "", w.dbl("distancePlanned")?.toString() ?: "", w.dbl("tssPlanned")?.toString() ?: "",
            w.dbl("ifPlanned")?.toString() ?: "", body, doc?.toString() ?: ""))
        val o = JSONObject()
            .put("category", category)
            .put("start_date_local", start)
            .put("name", w.txt("title") ?: if (isNote) "(note)" else "(untitled)")
            .put("description", "$body\n\n[tp-sync hash=$hash]".trimStart())
            .put("external_id", EXTERNAL_ID_PREFIX + w.optLong("workoutId"))
        if (type != null) { o.put("type", type); o.put("indoor", type.startsWith("Virtual")) }
        if (doc != null) o.put("workout_doc", doc)
        if (!isNote) {
            durS?.let { o.put("moving_time", it) }
            w.dbl("distancePlanned")?.takeIf { it > 0 }?.let { o.put("distance", it) }
            w.dbl("tssPlanned")?.takeIf { it > 0 }?.let { o.put("icu_training_load", it) }
            w.dbl("ifPlanned")?.takeIf { it > 0 }?.let { o.put("icu_intensity", it) }
        }
        return o
    }

    fun hashOf(description: String?): String? = description?.let { HASH_TAG.find(it)?.groupValues?.get(1) }

    private fun hash(fields: List<String>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(fields.joinToString("\u001F").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    /** The TP workout id an intervals.icu event was created from, or null when it is not ours. */
    fun tpIdOf(externalId: String?): Long? =
        externalId?.takeIf { it.startsWith(EXTERNAL_ID_PREFIX) }?.removePrefix(EXTERNAL_ID_PREFIX)?.toLongOrNull()
}
