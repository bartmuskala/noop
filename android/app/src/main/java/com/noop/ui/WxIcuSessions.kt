package com.noop.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.StrainScorer
import com.noop.data.WorkoutRow
import com.noop.intervals.IntervalsKeyStore
import com.noop.intervals.IntervalsParse
import com.noop.intervals.IntervalsStore
import com.noop.intervals.IntervalsSync
import com.noop.intervals.streams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

// MARK: - intervals.icu as the workout list (fork)
//
// With the intervals.icu link on, every Wx surface that lists workouts (Today's activities, the
// Effort tab, the Workouts tab and its calendar) reads ONE list: the intervals.icu activities, shown
// as WorkoutRows with source "intervals-icu" (never stored in the database). Each carries a NOOP
// Effort scored from the strap's heart rate inside the session, exactly like a NOOP workout (same
// curve, max HR and that day's resting HR); a ride the strap did not record has none, and shows
// intervals.icu's own load instead.

internal const val ICU_SOURCE = "intervals-icu"

/** NOOP Effort per intervals.icu activity id, cached in the intervals folder. */
internal object IcuEfforts {
    val flow = MutableStateFlow<Map<String, Double>>(emptyMap())
    private var loaded = false
    private const val FILE = "efforts.json"

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val o = IntervalsStore.read(ctx, FILE)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        flow.value = o.keys().asSequence().associateWith { o.getDouble(it) }
    }

    private val lock = kotlinx.coroutines.sync.Mutex()
    private const val NO_HR_FILE = "efforts_nohr.json"

    /**
     * Score every activity that has no Effort yet, the whole synced history. Heart rate comes from the
     * strap when it covers the session, otherwise from the activity's own heart-rate stream on
     * intervals.icu (a chest strap or watch); the fuller of the two is used. Activities with neither are
     * remembered so their stream is not asked for again on every open.
     */
    suspend fun compute(vm: AppViewModel, ctx: Context, acts: List<IntervalsParse.Activity>) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        try { computeLocked(vm, ctx, acts) } finally { lock.unlock() }
    }

    private suspend fun computeLocked(vm: AppViewModel, ctx: Context, acts: List<IntervalsParse.Activity>) {
        load(ctx)
        val have = flow.value.toMutableMap()
        val noHr = IntervalsStore.read(ctx, NO_HR_FILE)
            ?.let { t -> runCatching { org.json.JSONArray(t) }.getOrNull() }
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toMutableSet() } ?: mutableSetOf()
        val todo = acts.filter { it.id !in have }
        if (todo.isEmpty()) return
        val profile = ProfileStore.from(ctx)
        val hrMax = StrainScorer.effortHRmax(profile.hrMaxOverride.takeIf { it > 0 }?.toDouble(), profile.age.toDouble()).first
        // That day's resting HR across the whole history: WHOOP's imported days, NOOP's own on top.
        val restByDay = HashMap<String, Int>()
        runCatching {
            vm.repo.dailyMetrics(vm.deviceId, "2000-01-01", "2100-01-01").forEach { d -> d.restingHr?.let { restByDay[d.day] = it } }
            vm.repo.dailyMetrics("${vm.deviceId}-noop", "2000-01-01", "2100-01-01").forEach { d -> d.restingHr?.let { restByDay[d.day] = it } }
        }
        val fallbackRest = vm.recentDays.value.lastOrNull { it.restingHr != null }?.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR
        val now = System.currentTimeMillis() / 1000
        var changed = 0
        for (a in todo.sortedByDescending { it.startLocal }) {
            val start = icuEpoch(a.startLocal) ?: continue
            val dur = (a.elapsedS ?: a.movingS ?: continue).toLong()
            val end = start + dur
            if (end > now - 1800) continue // let the strap's heart rate arrive first
            val strap = runCatching { vm.repo.hrSamplesUnion(vm.activeStrapId, start, end, 50_000) }.getOrDefault(emptyList())
            var hr = strap
            // The strap missed (most of) it: the session's own heart-rate stream, when it has one.
            if (strap.size < dur / 2 && a.id !in noHr) {
                val st = runCatching { IntervalsStore.streams(ctx, a.id) }.getOrDefault(emptyMap())
                val bpm = st["heartrate"].orEmpty()
                val t = st["time"].orEmpty()
                val fromStream = bpm.indices.mapNotNull { i ->
                    val b = bpm[i]?.takeIf { it in 30.0..240.0 } ?: return@mapNotNull null
                    val sec = t.getOrNull(i) ?: i.toDouble()
                    com.noop.data.HrSample(deviceId = ICU_SOURCE, ts = start + sec.toLong(), bpm = b.toInt())
                }
                if (fromStream.size > strap.size) hr = fromStream
                if (fromStream.isEmpty() && strap.isEmpty()) noHr += a.id
            }
            if (hr.isEmpty()) continue
            val rest = restByDay[a.day]?.toDouble() ?: fallbackRest
            val e = StrainScorer.strain(hr, maxHR = hrMax, restingHR = rest, method = StrainScorer.Method.FITTED, sex = profile.sex) ?: continue
            have[a.id] = e; changed++
            // Show progress while a long history scores.
            if (changed % 10 == 0) flow.value = have.toMap()
        }
        if (changed > 0) {
            flow.value = have.toMap()
            IntervalsStore.write(ctx, FILE, JSONObject(have as Map<*, *>).toString())
        }
        IntervalsStore.write(ctx, NO_HR_FILE, org.json.JSONArray(noHr.toList()).toString())
    }
}

/** The intervals.icu activities as display-only WorkoutRows. */
internal fun icuRows(acts: List<IntervalsParse.Activity>, efforts: Map<String, Double>): List<WorkoutRow> =
    acts.mapNotNull { a ->
        val start = icuEpoch(a.startLocal) ?: return@mapNotNull null
        val dur = a.elapsedS ?: a.movingS ?: 0.0
        WorkoutRow(
            deviceId = ICU_SOURCE, startTs = start, endTs = start + dur.toLong(), sport = a.type ?: "Workout",
            source = ICU_SOURCE, durationS = a.movingS ?: dur, energyKcal = a.kcal,
            avgHr = a.avgHr?.toInt(), maxHr = a.maxHr?.toInt(), strain = efforts[a.id], distanceM = a.distanceM,
            notes = a.name,
        )
    }

/** The intervals.icu activity a display row came from. */
internal fun icuActivityFor(ctx: Context, row: WorkoutRow): IntervalsParse.Activity? =
    if (row.source != ICU_SOURCE) null
    else IntervalsStore.activities(ctx).firstOrNull { icuEpoch(it.startLocal) == row.startTs }

/** Whether the intervals.icu list replaces NOOP's own workout list. */
internal fun icuIsWorkoutSource(ctx: Context): Boolean = IntervalsKeyStore.enabled(ctx) && IntervalsKeyStore.hasKey(ctx)

/** Process-wide scope for the open-triggered syncs. */
private val IcuSyncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/** Minimum gap between two open-triggered syncs. */
private const val OPEN_SYNC_GAP_MS = 2 * 60_000L

/**
 * The workout list every Wx surface shows: intervals.icu's when connected, NOOP's otherwise. Starts the
 * sync on every app open and the Effort scoring of new rides in the background.
 */
@Composable
internal fun rememberWxSessions(vm: AppViewModel): List<WorkoutRow> {
    val ctx = LocalContext.current
    val noop by vm.workouts.collectAsStateWithLifecycle()
    val status by IntervalsSync.status.collectAsStateWithLifecycle()
    val efforts by IcuEfforts.flow.collectAsStateWithLifecycle()
    val on = remember(status.lastSync) { icuIsWorkoutSource(ctx) }
    val acts = remember(status.lastSync, on) { if (on) IntervalsStore.activities(ctx) else emptyList() }
    // Every return to the app counts as an open: a resume from the background recomposes nothing,
    // so without this the sync only ran on a cold start.
    var resumes by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(on, status.lastSync, resumes) {
        if (!on) return@LaunchedEffect
        IcuEfforts.load(ctx)
        // On the app's own scope: leaving this screen mid-sync must not cancel the sync halfway.
        IcuSyncScope.launch {
            // TrainingPeaks plan → intervals.icu first (when set up), so the sync below picks it up.
            // Short gates: each open syncs, a quick tab switch or screen rotation does not.
            com.noop.intervals.TpSync.runIfStale(ctx.applicationContext, OPEN_SYNC_GAP_MS)
            IntervalsSync.syncIfStale(ctx.applicationContext, OPEN_SYNC_GAP_MS)
            IcuEfforts.compute(vm, ctx.applicationContext, IntervalsStore.activities(ctx.applicationContext))
        }
    }
    return remember(on, acts, efforts, noop) { if (on) icuRows(acts, efforts) else noop }
}

/** Short subtitle for an intervals.icu row: distance, moving time, average power. */
internal fun icuRowExtra(row: WorkoutRow): String? = if (row.source != ICU_SOURCE) null else listOfNotNull(
    row.distanceM?.takeIf { it > 0 }?.let { String.format(java.util.Locale.getDefault(), "%.1f km", it / 1000.0) },
).joinToString(" · ").ifBlank { null }

internal fun icuLocalDay(ts: Long): String = Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()
