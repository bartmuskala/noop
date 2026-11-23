package com.noop.intervals

import android.content.Context
import com.noop.data.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

// MARK: - TrainingPeaks plan → intervals.icu (fork, opt-in)
//
// Mirrors the wearer's PLANNED TrainingPeaks workouts for the next 14 days into their intervals.icu
// calendar, the way Sladck/trainingpeaks-to-intervals-icu (MIT) does: TrainingPeaks has no public
// API, so it signs in with the wearer's own `Production_tpAuth` browser cookie, exchanged for a short
// bearer token. Events it creates carry `external_id = "tp:<workoutId>"`; only those are ever updated
// or deleted. Runs when the app opens, at most every three hours, and on demand. The cookie is stored
// encrypted on the phone and stops working when the wearer logs out of TrainingPeaks in that browser.

object TpKeyStore {
    private const val FILE = "noop_tp_secure_prefs"
    private fun prefs(ctx: Context) = SecurePrefs.of(ctx, FILE)
    fun cookie(ctx: Context): String? = runCatching { prefs(ctx).getString("cookie", null) }.getOrNull()?.takeIf { it.isNotBlank() }
    fun save(ctx: Context, cookie: String) { prefs(ctx).edit().putString("cookie", cookie.trim()).apply() }
    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
    fun lastRun(ctx: Context): Long = runCatching { prefs(ctx).getLong("lastRun", 0L) }.getOrDefault(0L)
    fun lastLine(ctx: Context): String? = runCatching { prefs(ctx).getString("lastLine", null) }.getOrNull()
    fun record(ctx: Context, line: String) { prefs(ctx).edit().putLong("lastRun", System.currentTimeMillis()).putString("lastLine", line).apply() }
}

class TpException(message: String) : Exception(message)

/** The unofficial TrainingPeaks API calls the bridge needs (read-only on the TP side). */
class TpClient(private val cookie: String) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val base = "https://tpapi.trainingpeaks.com"
    private var token: String? = null

    private fun ensureToken(): String {
        token?.let { return it }
        val req = Request.Builder().url("$base/users/v3/token")
            .header("Cookie", "Production_tpAuth=$cookie").header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw TpException("The TrainingPeaks cookie has expired. Copy a fresh one.")
            if (!r.isSuccessful) throw TpException("TrainingPeaks sign-in failed (HTTP ${r.code}).")
            val body = JSONObject(r.body?.string().orEmpty())
            val t = body.optJSONObject("token")?.optString("access_token")?.takeIf { it.isNotBlank() }
                ?: throw TpException("TrainingPeaks sign-in returned no token.")
            token = t
            return t
        }
    }

    private fun get(path: String): String? {
        val req = Request.Builder().url(base + path)
            .header("Authorization", "Bearer ${ensureToken()}").header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (r.code == 401) throw TpException("TrainingPeaks refused the session. Copy a fresh cookie.")
            if (!r.isSuccessful) throw TpException("TrainingPeaks HTTP ${r.code} on $path")
            return r.body?.string()
        }
    }

    /** The wearer's own athlete id: the entry coached by themselves with their last name, else the first. */
    fun athleteId(): Long {
        val data = JSONObject(get("/users/v3/user") ?: throw TpException("No TrainingPeaks user."))
        val user = data.optJSONObject("user") ?: data
        val person = user.optLong("personId")
        val last = user.optString("lastName").lowercase()
        val athletes = user.optJSONArray("athletes") ?: JSONArray()
        var chosen: Long? = null
        for (i in 0 until athletes.length()) {
            val a = athletes.optJSONObject(i) ?: continue
            if (a.optString("lastName").lowercase() == last && a.optLong("coachedBy") == person) { chosen = a.optLong("athleteId"); break }
        }
        if (chosen == null && athletes.length() > 0) chosen = athletes.optJSONObject(0)?.optLong("athleteId")
        return chosen?.takeIf { it > 0 } ?: person.takeIf { it > 0 } ?: throw TpException("No TrainingPeaks athlete id.")
    }

    fun thresholds(athlete: Long): TpMapping.Thresholds {
        val s = get("/fitness/v1/athletes/$athlete/settings")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return TpMapping.Thresholds()
        fun first(key: String, wt: (Int?) -> Boolean): Double? {
            val arr = s.optJSONArray(key) ?: return null
            for (i in 0 until arr.length()) {
                val z = arr.optJSONObject(i) ?: continue
                val w = if (z.has("workoutTypeId") && !z.isNull("workoutTypeId")) z.optInt("workoutTypeId") else null
                val t = z.optDouble("threshold")
                if (wt(w) && !t.isNaN() && t > 0) return t
            }
            return null
        }
        return TpMapping.Thresholds(
            ftp = first("powerZones") { it == null || it == 0 },
            thresholdHr = first("heartRateZones") { it == null || it == 0 },
            runVelocity = first("speedZones") { it == null || it == 0 },
            swimVelocity = first("speedZones") { it == 1 },
        )
    }

    /** Planned (not completed) workouts from [from] to [to]. */
    fun plannedWorkouts(athlete: Long, from: LocalDate, to: LocalDate): List<JSONObject> {
        val raw = get("/fitness/v6/athletes/$athlete/workouts/$from/$to") ?: return emptyList()
        val t = raw.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("workouts") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { !it.optBoolean("completed", false) }
    }
}

object TpSync {
    data class Status(val running: Boolean = false, val line: String? = null, val error: Boolean = false)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun enabled(ctx: Context) = TpKeyStore.cookie(ctx) != null && IntervalsKeyStore.enabled(ctx) && IntervalsKeyStore.hasKey(ctx)

    /** A run when connected and the last one is older than three hours. */
    suspend fun runIfStale(ctx: Context, maxAgeMs: Long = 3 * 3600_000L) {
        if (!enabled(ctx)) return
        if (System.currentTimeMillis() - TpKeyStore.lastRun(ctx) < maxAgeMs) {
            _status.value = Status(line = TpKeyStore.lastLine(ctx)); return
        }
        run(ctx)
    }

    /** One mirror of the next 14 days. Returns true when it finished without errors. */
    suspend fun run(ctx: Context, days: Long = 14): Boolean = withContext(Dispatchers.IO) {
        val cookie = TpKeyStore.cookie(ctx) ?: return@withContext false
        val key = IntervalsKeyStore.apiKey(ctx) ?: return@withContext false
        if (_status.value.running) return@withContext false
        _status.value = Status(running = true)
        var created = 0; var updated = 0; var deleted = 0; var unchanged = 0; val errors = ArrayList<String>()
        val result = runCatching {
            val tp = TpClient(cookie)
            val athlete = tp.athleteId()
            val th = tp.thresholds(athlete)
            val today = LocalDate.now(); val end = today.plusDays(days)
            val planned = tp.plannedWorkouts(athlete, today, end).associateBy { it.optLong("workoutId") }
            val icu = IntervalsClient(IntervalsKeyStore.athleteId(ctx), key)
            val events = JSONArray(icu.events(today, end))
            val ours = HashMap<Long, JSONObject>()
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val id = TpMapping.tpIdOf(e.optString("external_id")) ?: continue
                if (e.optString("start_date_local").take(10) in today.toString()..end.toString()) ours[id] = e
            }
            val path = "/athlete/${icu.athleteId()}/events"
            for ((wid, w) in planned) {
                val p = TpMapping.payload(w, th)
                val existing = ours[wid]
                runCatching {
                    if (existing == null) { icu.send("POST", path, p.toString()); created++ }
                    else if (TpMapping.hashOf(existing.optString("description")) == TpMapping.hashOf(p.optString("description"))) unchanged++
                    else { icu.send("PUT", "$path/${existing.optLong("id")}", p.toString()); updated++ }
                }.onFailure { errors += "${TpMapping.day(w)} ${w.optString("title")}: ${it.message}" }
            }
            // Ours, still in the window, no longer in TrainingPeaks: removed there, so removed here.
            for ((wid, e) in ours) if (wid !in planned) {
                runCatching { icu.send("DELETE", "$path/${e.optLong("id")}", null); deleted++ }
                    .onFailure { errors += "delete ${e.optString("name")}: ${it.message}" }
            }
        }
        val line = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            ?: "$created new, $updated updated, $deleted removed, $unchanged unchanged" +
                if (errors.isNotEmpty()) " · ${errors.size} failed: ${errors.first()}" else ""
        val ok = result.isSuccess && errors.isEmpty()
        TpKeyStore.record(ctx, line)
        _status.value = Status(running = false, line = line, error = !ok)
        // Pull the updated calendar into the phone's cache.
        if (result.isSuccess && (created + updated + deleted) > 0) IntervalsSync.sync(ctx)
        ok
    }
}
