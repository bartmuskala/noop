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
    ) {
        val day: String get() = startLocal.take(10)
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
    ) {
        val day: String get() = startLocal.take(10)
        val isWorkout: Boolean get() = category == null || category.equals("WORKOUT", ignoreCase = true)
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
                o.num("icu_training_load"), o.num("moving_time"))
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
