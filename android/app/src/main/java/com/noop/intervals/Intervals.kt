package com.noop.intervals

import android.content.Context
import com.noop.data.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

// MARK: - intervals.icu (fork, opt-in)
//
// A default-off, read-only link to the wearer's own intervals.icu account, with their personal API key.
// NOOP sends nothing to intervals.icu except the key (HTTP Basic auth, user "API_KEY") on requests that
// READ the wearer's activities, GPS tracks, planned workouts and fitness. Responses are cached on the
// phone (filesDir/intervals) so the screens work offline after a sync. Turning it off deletes the key
// and the cache.

/** The wearer's credentials and switch, encrypted at rest like the Coach key. */
object IntervalsKeyStore {
    private const val FILE = "noop_intervals_secure_prefs"
    private fun prefs(ctx: Context) = SecurePrefs.of(ctx, FILE)

    fun enabled(ctx: Context): Boolean = runCatching { prefs(ctx).getBoolean("enabled", false) }.getOrDefault(false)
    fun athleteId(ctx: Context): String = runCatching { prefs(ctx).getString("athlete", null) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0"
    fun apiKey(ctx: Context): String? = runCatching { prefs(ctx).getString("key", null) }.getOrNull()?.takeIf { it.isNotBlank() }
    fun hasKey(ctx: Context): Boolean = apiKey(ctx) != null

    fun save(ctx: Context, athleteId: String, apiKey: String) {
        prefs(ctx).edit().putString("athlete", athleteId.trim()).putString("key", apiKey.trim()).putBoolean("enabled", true).apply()
    }

    fun disconnect(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        IntervalsStore.dir(ctx).deleteRecursively()
    }
}

class IntervalsException(val code: Int, message: String) : Exception(message)

/** The few read-only calls NOOP makes. */
class IntervalsClient(private val athleteId: String, private val apiKey: String) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val base = "https://intervals.icu/api/v1"

    /** A write (POST / PUT / DELETE) of a JSON body; used only by the TrainingPeaks bridge, for events it owns. */
    fun send(method: String, path: String, body: String?): String {
        val rb = body?.toRequestBody("application/json".toMediaTypeOrNull())
        val req = Request.Builder().url(base + path)
            .header("Authorization", Credentials.basic("API_KEY", apiKey))
            .method(method, rb)
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IntervalsException(r.code, "HTTP ${r.code} on $method $path")
            return text
        }
    }

    fun athleteId(): String = athleteId

    private fun get(path: String): String {
        val req = Request.Builder().url(base + path)
            .header("Authorization", Credentials.basic("API_KEY", apiKey))
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IntervalsException(r.code, "HTTP ${r.code} on $path")
            return body
        }
    }

    fun activities(oldest: LocalDate, newest: LocalDate) = get("/athlete/$athleteId/activities?oldest=$oldest&newest=$newest")
    fun events(oldest: LocalDate, newest: LocalDate) = get("/athlete/$athleteId/events?oldest=$oldest&newest=$newest")
    fun wellness(oldest: LocalDate, newest: LocalDate) = get("/athlete/$athleteId/wellness?oldest=$oldest&newest=$newest")

    /** Power, heart rate, cadence, altitude and time streams of an activity. */
    fun streams(activityId: String): String = try {
        get("/activity/$activityId/streams?types=time,watts,heartrate,cadence,altitude,distance")
    } catch (e: IntervalsException) {
        if (e.code == 404) get("/activity/$activityId/streams.json?types=time,watts,heartrate,cadence,altitude,distance") else throw e
    }

    /** The activity's GPS stream; tries the two spellings of the streams path. */
    fun latLng(activityId: String): String = try {
        get("/activity/$activityId/streams?types=latlng")
    } catch (e: IntervalsException) {
        if (e.code == 404) get("/activity/$activityId/streams.json?types=latlng") else throw e
    }
}

/** The on-phone cache of the last sync. */
object IntervalsStore {
    fun dir(ctx: Context) = File(ctx.filesDir, "intervals").apply { mkdirs() }
    private fun file(ctx: Context, name: String) = File(dir(ctx), name)
    fun read(ctx: Context, name: String): String? = file(ctx, name).takeIf { it.exists() }?.readText()
    fun write(ctx: Context, name: String, text: String) = file(ctx, name).writeText(text)

    fun activities(ctx: Context) = read(ctx, "activities.json")?.let(IntervalsParse::activities).orEmpty()
    fun events(ctx: Context) = read(ctx, "events.json")?.let(IntervalsParse::events).orEmpty()
    fun wellness(ctx: Context) = read(ctx, "wellness.json")?.let(IntervalsParse::wellness).orEmpty()
    fun lastSync(ctx: Context): Long = file(ctx, "activities.json").takeIf { it.exists() }?.lastModified() ?: 0L

    /** The cached GPS track, fetching it once when missing. Empty when the activity has none. */
    suspend fun route(ctx: Context, activityId: String): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val name = "route_${activityId.filter { it.isLetterOrDigit() }}.json"
        val cached = read(ctx, name)
        if (cached != null) return@withContext IntervalsParse.latLng(cached)
        val key = IntervalsKeyStore.apiKey(ctx) ?: return@withContext emptyList()
        val json = runCatching { IntervalsClient(IntervalsKeyStore.athleteId(ctx), key).latLng(activityId) }.getOrNull()
            ?: return@withContext emptyList()
        write(ctx, name, json)
        IntervalsParse.latLng(json)
    }
}

/** The cached power / HR / cadence / altitude streams, fetched once. Empty when there are none. */
suspend fun IntervalsStore.streams(ctx: Context, activityId: String): Map<String, List<Double?>> = withContext(Dispatchers.IO) {
    // v2: also carries the distance stream (for the chart readout).
    val name = "streams2_${activityId.filter { it.isLetterOrDigit() }}.json"
    read(ctx, name)?.let { return@withContext IntervalsParse.streams(it) }
    val key = IntervalsKeyStore.apiKey(ctx) ?: return@withContext emptyMap()
    val json = runCatching { IntervalsClient(IntervalsKeyStore.athleteId(ctx), key).streams(activityId) }.getOrNull()
        ?: return@withContext emptyMap()
    write(ctx, name, json)
    IntervalsParse.streams(json)
}

/** How far back activities are synced: a year and a bit, so Fitness Age's activity term covers the history. */
const val ACTIVITY_DAYS = 400L

/** One sync: activities of the last 400 days, planned workouts two weeks back and four ahead, fitness. */
object IntervalsSync {
    data class Status(val running: Boolean = false, val error: String? = null, val lastSync: Long = 0L,
                      val activities: Int = 0, val planned: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** A sync when connected and the cache is older than [maxAgeMs]; no-op otherwise. */
    suspend fun syncIfStale(ctx: Context, maxAgeMs: Long = 60 * 60_000L) {
        if (!IntervalsKeyStore.enabled(ctx) || !IntervalsKeyStore.hasKey(ctx)) return
        if (System.currentTimeMillis() - IntervalsStore.lastSync(ctx) < maxAgeMs) {
            refreshCounts(ctx); return
        }
        sync(ctx)
    }

    fun refreshCounts(ctx: Context) {
        _status.value = _status.value.copy(lastSync = IntervalsStore.lastSync(ctx),
            activities = IntervalsStore.activities(ctx).size, planned = IntervalsStore.events(ctx).count { it.isWorkout })
    }

    suspend fun sync(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val key = IntervalsKeyStore.apiKey(ctx) ?: return@withContext false
        // One sync at a time; the lock is released however this ends, so a cancelled sync can't
        // leave "running" set and silently skip every later one.
        if (!lock.tryLock()) return@withContext false
        try { syncLocked(ctx, key) } finally {
            if (_status.value.running) _status.value = _status.value.copy(running = false)
            lock.unlock()
        }
    }

    private val lock = kotlinx.coroutines.sync.Mutex()

    private fun syncLocked(ctx: Context, key: String): Boolean {
        _status.value = _status.value.copy(running = true, error = null)
        val client = IntervalsClient(IntervalsKeyStore.athleteId(ctx), key)
        val today = LocalDate.now()
        val result = runCatching {
            val acts = client.activities(today.minusDays(ACTIVITY_DAYS), today)
            val events = client.events(today.minusDays(14), today.plusDays(28))
            val well = client.wellness(today.minusDays(120), today.plusDays(28))
            IntervalsStore.write(ctx, "events.json", events)
            IntervalsStore.write(ctx, "wellness.json", well)
            IntervalsStore.write(ctx, "activities_from", today.minusDays(ACTIVITY_DAYS).toString())
            IntervalsStore.write(ctx, "activities.json", acts) // last: its time stamps the sync
        }
        val err = result.exceptionOrNull()?.let { e ->
            when {
                e is IntervalsException && (e.code == 401 || e.code == 403) -> "The key or athlete ID was refused (HTTP ${e.code})."
                e is IntervalsException -> e.message
                else -> "Could not reach intervals.icu (${e.javaClass.simpleName})."
            }
        }
        _status.value = Status(running = false, error = err, lastSync = IntervalsStore.lastSync(ctx),
            activities = IntervalsStore.activities(ctx).size, planned = IntervalsStore.events(ctx).count { it.isWorkout })
        return err == null
    }
}
