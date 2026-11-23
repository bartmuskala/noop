package com.noop.intervals

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parsing of intervals.icu API responses (fork). Every field is optional and read defensively:
 * a missing or renamed field becomes null rather than an exception, so one odd activity never empties
 * the list. Kept free of Android types so JVM unit tests cover it.
 */
object IntervalsParse {

    data class Activity(
        val id: String,
        /** Local start as written by intervals.icu, e.g. "2026-11-20T09:30:00". */
        val startLocal: String,
        val type: String?,
        val name: String?,
        val movingS: Double?,
        val elapsedS: Double?,
        val distanceM: Double?,
        val elevationM: Double?,
        val load: Double?,
        val avgHr: Double?,
        val maxHr: Double?,
        val kcal: Double?,
        /** Power, when the session recorded it: average, weighted (normalised) average, the FTP it was
         *  scored against, and intensity (as intervals.icu reports it, a percentage of FTP). */
        val avgWatts: Double? = null,
        val normWatts: Double? = null,
        val ftp: Double? = null,
        val intensity: Double? = null,
        val avgCadence: Double? = null,
        val pairedEventId: String? = null,
    ) {
        val day: String get() = startLocal.take(10)
        /** Intensity factor as a fraction (0.75), whether intervals.icu sent 75 or 0.75. */
        val intensityFactor: Double? get() = intensity?.let { if (it > 3.0) it / 100.0 else it }
    }

    data class Event(
        val id: String,
        val startLocal: String,
        val category: String?,
        val name: String?,
        val type: String?,
        val description: String?,
        val load: Double?,
        val movingS: Double?,
        /** The structured plan (intervals.icu `workout_doc`), raw JSON, parsed by [steps]. */
        val workoutDoc: String? = null,
        val pairedActivityId: String? = null,
        val distanceM: Double? = null,
    ) {
        val day: String get() = startLocal.take(10)
        val isWorkout: Boolean get() = category == null || category.equals("WORKOUT", ignoreCase = true) ||
            category.startsWith("RACE", ignoreCase = true)
    }

    /** One step of a planned workout, flattened: repeats carry their count. */
    data class Step(
        val durationS: Double?,
        val distanceM: Double?,
        /** e.g. "200–240 W", "75–85% FTP", "150 bpm"; null when the step has no target. */
        val target: String?,
        val text: String?,
        /** Repeat count of the block this step belongs to (1 outside repeats), and its position. */
        val reps: Int = 1,
        val firstOfBlock: Boolean = false,
        val blockSize: Int = 1,
        /** Midpoint of a power target in watts or %FTP, to draw the plan's profile. */
        val level: Double? = null,
        val levelUnits: String? = null,
    )

    /**
     * The plan's steps from a `workout_doc`: `{"steps":[{duration, distance, power|hr|pace:{units,
     * value, max_value}, text}, {"reps": n, "steps": [...]}]}`, the shape intervals.icu stores (and the
     * TrainingPeaks bridge writes). Unknown pieces are skipped.
     */
    fun steps(workoutDoc: String?): List<Step> {
        val doc = workoutDoc?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<Step>()
        fun target(o: JSONObject): Triple<String?, Double?, String?> {
            for (key in listOf("power", "hr", "pace", "cadence")) {
                val t = o.optJSONObject(key) ?: continue
                val u = t.optString("units")
                val v = t.num("value"); val m = t.num("max_value")
                if (v == null && m == null) continue
                fun f(x: Double) = if (x % 1.0 == 0.0) x.toLong().toString() else String.format(java.util.Locale.US, "%.1f", x)
                val range = if (v != null && m != null && m != v) "${f(v)}–${f(m)}" else f(v ?: m!!)
                val unit = when (u.lowercase()) {
                    "w" -> " W"; "%ftp" -> "% FTP"; "bpm" -> " bpm"; "%lthr" -> "% LTHR"; "%hr" -> "% HRmax"
                    "%pace" -> "% pace"; "rpm" -> " rpm"; "s/km", "/km" -> " s/km"; else -> if (u.isBlank()) "" else " $u"
                }
                val mid = if (v != null && m != null) (v + m) / 2 else v ?: m
                return Triple(range + unit, if (key == "power") mid else null, if (key == "power") u.lowercase() else null)
            }
            return Triple(null, null, null)
        }
        fun one(o: JSONObject, reps: Int, first: Boolean, size: Int): Step {
            val (t, lvl, lu) = target(o)
            return Step(o.num("duration"), o.num("distance"), t, o.str("text"), reps, first, size, lvl, lu)
        }
        val steps = doc.optJSONArray("steps") ?: return emptyList()
        for (i in 0 until steps.length()) {
            val o = steps.optJSONObject(i) ?: continue
            val inner = o.optJSONArray("steps")
            if (inner != null) {
                val reps = o.optInt("reps", 1).coerceAtLeast(1)
                val kids = (0 until inner.length()).mapNotNull { inner.optJSONObject(it) }
                kids.forEachIndexed { k, c -> out += one(c, reps, k == 0, kids.size) }
            } else out += one(o, 1, true, 1)
        }
        return out
    }

    /** Named numeric streams of an activity (`[{type, data:[...]}, ...]`), e.g. watts, heartrate, time. */
    fun streams(json: String): Map<String, List<Double?>> {
        val arr = runCatching { JSONArray(json.trim()) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, List<Double?>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type").takeIf { it.isNotBlank() && it != "latlng" } ?: continue
            val d = o.optJSONArray("data") ?: continue
            out[type] = (0 until d.length()).map { k -> if (d.isNull(k)) null else d.optDouble(k).takeIf { !it.isNaN() } }
        }
        return out
    }

    /**
     * Seconds in each of Coggan's seven power zones (below 55, 55–75, 75–90, 90–105, 105–120, 120–150,
     * above 150 % of FTP) from a watts stream with one sample per entry of [times] (seconds).
     */
    fun powerZoneSeconds(watts: List<Double?>, times: List<Double?>, ftp: Double): List<Double>? {
        if (ftp <= 0 || watts.size < 2) return null
        val edges = listOf(0.55, 0.75, 0.90, 1.05, 1.20, 1.50)
        val z = DoubleArray(7)
        for (i in watts.indices) {
            val w = watts[i] ?: continue
            val dt = if (i + 1 < times.size && times[i] != null && times[i + 1] != null)
                (times[i + 1]!! - times[i]!!).coerceIn(0.0, 5.0) else 1.0
            val k = edges.indexOfFirst { w / ftp < it }.let { if (it < 0) 6 else it }
            z[k] += dt
        }
        return z.toList()
    }

    /** Seconds in five heart-rate zones (50/60/70/80/90–100 % of [hrMax]); time below 50 % is not counted. */
    fun hrZoneSeconds(hr: List<Double?>, times: List<Double?>, hrMax: Double): List<Double>? {
        if (hrMax <= 0 || hr.size < 2) return null
        val z = DoubleArray(5)
        for (i in hr.indices) {
            val b = hr[i] ?: continue
            val dt = if (i + 1 < times.size && times[i] != null && times[i + 1] != null)
                (times[i + 1]!! - times[i]!!).coerceIn(0.0, 5.0) else 1.0
            val pct = b / hrMax
            if (pct < 0.5) continue
            z[((pct - 0.5) / 0.1).toInt().coerceIn(0, 4)] += dt
        }
        return z.toList()
    }

    data class Wellness(val day: String, val ctl: Double?, val atl: Double?, val rampRate: Double?)

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null
    private fun JSONObject.num(k: String): Double? =
        if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() && !it.isInfinite() } else null

    fun activities(json: String): List<Activity> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val start = o.str("start_date_local") ?: return@mapNotNull null
            Activity(
                id = id, startLocal = start, type = o.str("type"), name = o.str("name"),
                movingS = o.num("moving_time"), elapsedS = o.num("elapsed_time"),
                distanceM = o.num("distance"), elevationM = o.num("total_elevation_gain"),
                load = o.num("icu_training_load"), avgHr = o.num("average_heartrate"),
                maxHr = o.num("max_heartrate"), kcal = o.num("calories"),
                avgWatts = o.num("icu_average_watts") ?: o.num("average_watts"),
                normWatts = o.num("icu_weighted_avg_watts") ?: o.num("weighted_average_watts"),
                ftp = o.num("icu_ftp"),
                intensity = o.num("icu_intensity"),
                avgCadence = o.num("average_cadence"),
                pairedEventId = o.str("paired_event_id"),
            )
        }
    }

    fun events(json: String): List<Event> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val start = o.str("start_date_local") ?: return@mapNotNull null
            Event(id, start, o.str("category"), o.str("name"), o.str("type"), o.str("description"),
                o.num("icu_training_load"), o.num("moving_time"),
                workoutDoc = o.optJSONObject("workout_doc")?.toString(),
                pairedActivityId = o.str("paired_activity_id"),
                distanceM = o.num("distance"))
        }
    }

    fun wellness(json: String): List<Wellness> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val day = o.str("id")?.take(10) ?: return@mapNotNull null
            Wellness(day, o.num("ctl"), o.num("atl"), o.num("rampRate"))
        }.sortedBy { it.day }
    }

    /**
     * The GPS track from an activity's streams. Accepts the latlng stream either as two parallel arrays
     * (`data` = latitudes, `data2` = longitudes) or as one array of [lat, lng] pairs, and also an object
     * keyed by stream type. Points outside valid coordinates (and the 0,0 a device writes without a fix)
     * are dropped.
     */
    fun latLng(json: String): List<Pair<Double, Double>> {
        val streams: List<JSONObject> = runCatching {
            val t = json.trim()
            if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            else JSONObject(t).let { o ->
                if (o.has("latlng")) listOf(JSONObject().put("type", "latlng").put("data", o.get("latlng"))) else listOf(o)
            }
        }.getOrNull() ?: return emptyList()
        val s = streams.firstOrNull { it.optString("type") == "latlng" } ?: return emptyList()
        val data = s.optJSONArray("data") ?: return emptyList()
        val data2 = s.optJSONArray("data2")
        val out = ArrayList<Pair<Double, Double>>(data.length())
        for (i in 0 until data.length()) {
            val p: Pair<Double, Double>? = when {
                data2 != null && i < data2.length() -> data.optDouble(i) to data2.optDouble(i)
                data.optJSONArray(i) != null -> data.optJSONArray(i)!!.let { it.optDouble(0) to it.optDouble(1) }
                else -> null
            }
            if (p != null && !p.first.isNaN() && !p.second.isNaN() && p.first in -90.0..90.0 && p.second in -180.0..180.0 &&
                !(p.first == 0.0 && p.second == 0.0)) out += p
        }
        return out
    }

    /**
     * The training-load projection if the plan is followed (fork estimate): from the latest known
     * fitness (CTL) and fatigue (ATL), each planned day's load is applied with the usual daily update
     * CTL += (load − CTL) / 42 and ATL += (load − ATL) / 7; a day with nothing planned counts as rest.
     * Returns (day, ctl, atl) for each day after [fromDay] through [toDay].
     */
    fun project(
        fromDay: java.time.LocalDate, ctl0: Double, atl0: Double,
        plannedLoadByDay: Map<String, Double>, toDay: java.time.LocalDate,
    ): List<Triple<String, Double, Double>> {
        var ctl = ctl0; var atl = atl0
        val out = ArrayList<Triple<String, Double, Double>>()
        var d = fromDay.plusDays(1)
        while (!d.isAfter(toDay)) {
            val load = plannedLoadByDay[d.toString()] ?: 0.0
            ctl += (load - ctl) / 42.0
            atl += (load - atl) / 7.0
            out += Triple(d.toString(), ctl, atl)
            d = d.plusDays(1)
        }
        return out
    }
}
