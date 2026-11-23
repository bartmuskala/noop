package com.noop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.StrainScorer
import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

// MARK: - Wx Workouts
//
// A list of sessions with a seven-day summary, and a detail page per session: the Effort ring, the
// headline stats, the heart rate through the session and the time in each zone. Zones come from the
// session's own heart rate against the same max HR every Effort is scored with; a session without
// raw heart rate falls back to the imported zone split. Editing, merging and manual entries stay on
// the full Workouts screen, one tap away.

/**
 * A workout to open straight away when the Workouts screen is next shown (fork): set by a day's
 * activity row just before it navigates, consumed once by [WxWorkoutsScreen].
 */
internal object WxWorkoutNav {
    @Volatile var pending: String? = null
    fun key(w: WorkoutRow): String = "${w.source}:${w.startTs}"
}

@Composable
internal fun WxWorkoutsScreen(vm: AppViewModel, onBack: (() -> Unit)?, onOpenFull: () -> Unit, onPop: () -> Unit = {}) {
    val context = LocalContext.current
    // Fork: one list, intervals.icu's when connected.
    val rows = rememberWxSessions(vm)
    val days by vm.recentDays.collectAsStateWithLifecycle()
    // intervals.icu (opt-in): synced at most hourly when this tab opens, read from the phone's cache.
    val icuStatus by com.noop.intervals.IntervalsSync.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { com.noop.intervals.IntervalsSync.syncIfStale(context) }
    val icuOn = remember(icuStatus.lastSync) { com.noop.intervals.IntervalsKeyStore.enabled(context) }
    val icuActs = remember(icuStatus.lastSync) { if (icuOn) com.noop.intervals.IntervalsStore.activities(context) else emptyList() }
    val icuEvents = remember(icuStatus.lastSync) { if (icuOn) com.noop.intervals.IntervalsStore.events(context) else emptyList() }
    val icuWellness = remember(icuStatus.lastSync) { if (icuOn) com.noop.intervals.IntervalsStore.wellness(context) else emptyList() }
    var icuSel by remember { mutableStateOf<com.noop.intervals.IntervalsParse.Activity?>(null) }
    var planSel by remember { mutableStateOf<com.noop.intervals.IntervalsParse.Event?>(null) }
    val effortScale = remember { UnitPrefs.effortScale(context) }
    val is24h = remember { android.text.format.DateFormat.is24HourFormat(context) }
    LaunchedEffect(Unit) {
        vm.loadWorkouts()
        vm.rescoreWorkoutsWithFittedCurve()
    }
    var selected by remember { mutableStateOf<WorkoutRow?>(null) }
    // Opened from a day's activity row: show that workout, and Back returns to where it was tapped.
    val direct = remember { WxWorkoutNav.pending.also { WxWorkoutNav.pending = null } }
    var directDone by remember { mutableStateOf(direct == null) }
    LaunchedEffect(rows, direct) {
        if (direct != null && !directDone && direct.startsWith("plan:")) {
            planSel = icuEvents.firstOrNull { it.id == direct.removePrefix("plan:") }
            directDone = true
        }
        if (direct != null && !directDone) {
            val hit = rows.firstOrNull { WxWorkoutNav.key(it) == direct }
            if (hit != null) { selected = hit; directDone = true }
            else if (rows.isNotEmpty()) { kotlinx.coroutines.delay(1500); directDone = true } // not found: show the list
        }
    }
    var showStart by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val sorted = remember(rows) { rows.sortedByDescending { it.startTs } }

    planSel?.let { e ->
        val back: () -> Unit = { if (direct != null) onPop() else planSel = null }
        BackHandler { back() }
        WxPlannedDetail(e, onBack = back)
        return
    }
    icuSel?.let { a ->
        BackHandler { icuSel = null }
        WxIcuActivityDetail(vm, a, null, effortScale, onBack = { icuSel = null })
        return
    }
    val sel = selected
    val leave: () -> Unit = { if (direct != null) onPop() else selected = null }
    if (sel != null && sel.source == ICU_SOURCE) {
        BackHandler { leave() }
        val act = remember(sel.startTs) { icuActivityFor(context, sel) }
        if (act != null) { WxIcuActivityDetail(vm, act, sel, effortScale, onBack = leave); return }
    }
    if (sel != null) {
        BackHandler { leave() }
        WxWorkoutDetail(vm, sel, sorted, effortScale, is24h, onBack = leave, onEdit = onOpenFull,
            onOpen = { selected = it })
        return
    }
    if (!directDone) {
        // Waiting for the list to load the tapped workout; a blank page rather than a flash of the list.
        WxPage(title = uiString(R.string.wx_workouts), onBack = onPop) {}
        return
    }

    WxPage(
        title = uiString(R.string.wx_workouts),
        onBack = onBack,
        action = {
            IconButton(onClick = onOpenFull) { Icon(Icons.Filled.Edit, uiString(R.string.wx_manage_workouts), tint = Wx.textMuted) }
        },
    ) {
        val weekAgo = System.currentTimeMillis() / 1000L - 7 * 86_400L
        val week = sorted.filter { it.startTs >= weekAgo }
        // Summary: the calendar, the week and the month, then fitness, fatigue and form.
        // Today's plan first: what it is and whether it has been ridden.
        if (icuOn) WxTodayPlanCard(icuEvents, icuActs, onOpen = { planSel = it })
        WxWorkoutCalendar(sorted, effortScale, onOpen = { selected = it },
            planned = icuEvents.filter { it.isWorkout }.groupBy { it.day }.mapValues { (_, v) -> v.sumOf { it.load ?: 0.0 } })
        WxCard {
            WxCardHeader(uiString(R.string.wx_last_7_days))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                WxStat(uiString(R.string.wx_sessions), "${week.size}", Modifier.weight(1f))
                WxStat(uiString(R.string.wx_duration), wxHoursMinutes(week.sumOf { wxDurationS(it) } / 60.0), Modifier.weight(1f))
                WxStat(
                    uiString(R.string.wx_effort),
                    week.mapNotNull { it.strain }.takeIf { it.isNotEmpty() }?.average()
                        ?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: "–",
                    Modifier.weight(1f),
                )
            }
        }
        WxWorkoutTotals(sorted, effortScale)
        if (icuOn) WxPlannedCard(icuEvents, is24h, onOpen = { planSel = it })
        // With intervals.icu connected its own fitness model (and the plan's projection) is shown;
        // otherwise NOOP's, from the daily Effort. Never both, so two fitness numbers cannot disagree.
        if (icuWellness.count { it.ctl != null } >= 2) WxIcuFitnessCard(icuWellness, icuEvents) else WxTrainingLoadCard(days)
        WxButton(uiString(R.string.wx_start_workout), onClick = { showStart = true })
        if (sorted.isEmpty()) {
            WxCard { Text(uiString(R.string.wx_no_workouts), style = WxType.body, color = Wx.textSoft) }
        } else {
            WxCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    WxLabel(uiString(R.string.wx_recent_workouts), Modifier.padding(top = 14.dp, bottom = 4.dp))
                    val shown = if (showAll) sorted else sorted.take(25)
                    shown.forEach { w ->
                        WxActivityRow(
                            accent = Wx.effort,
                            title = wxWorkoutTitle(w),
                            subtitle = wxWorkoutLine(w, is24h),
                            trailing = w.strain?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: "–",
                            onClick = { selected = w },
                        )
                    }
                    if (!showAll && sorted.size > 25) WxLinkRow(uiString(R.string.wx_show_all)) { showAll = true }
                    else Spacer(Modifier.height(8.dp))
                }
            }
        }
        WxLinkRow(uiString(R.string.wx_manage_workouts), onOpenFull)
    }
    if (showStart) StartWorkoutSheet(vm, onDismiss = { showStart = false })
}

private fun wxDurationS(w: WorkoutRow): Double = w.durationS ?: (w.endTs - w.startTs).toDouble()

private fun wxWorkoutLine(w: WorkoutRow, is24h: Boolean): String {
    val t = Instant.ofEpochSecond(w.startTs).atZone(ZoneId.systemDefault())
    val parts = mutableListOf(wxShortDate(t.toLocalDate()), wxClock(t.hour * 60 + t.minute, is24h), wxHoursMinutes(wxDurationS(w) / 60.0))
    icuRowExtra(w)?.let { parts.add(it) }
    w.avgHr?.let { parts.add("$it bpm") }
    return parts.joinToString(" · ")
}

private data class WxSessionHr(val points: List<Pair<Long, Double>>, val zoneSeconds: List<Double>?)

/** Seconds per zone (Z1..Z5 at 50/60/70/80/90 % of max HR); gaps over 5 s count as 5 s. */
internal fun wxZoneSeconds(hr: List<HrSample>, hrMax: Double): List<Double>? {
    if (hr.size < 30 || hrMax <= 0.0) return null
    val out = MutableList(5) { 0.0 }
    for (i in 1 until hr.size) {
        val dt = (hr[i].ts - hr[i - 1].ts).coerceIn(0L, 5L).toDouble()
        val pct = hr[i].bpm / hrMax
        val z = when {
            pct >= 0.9 -> 4
            pct >= 0.8 -> 3
            pct >= 0.7 -> 2
            pct >= 0.6 -> 1
            pct >= 0.5 -> 0
            else -> -1
        }
        if (z >= 0) out[z] += dt
    }
    return if (out.sum() > 0.0) out else null
}

@Composable
private fun WxWorkoutDetail(
    vm: AppViewModel,
    w: WorkoutRow,
    all: List<WorkoutRow>,
    effortScale: EffortScale,
    is24h: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (WorkoutRow) -> Unit,
) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context) }
    val hrMax = StrainScorer.effortHRmax(profile.hrMaxOverride.takeIf { it > 0 }?.toDouble(), profile.age.toDouble()).first
    var data by remember(w.startTs, w.sport) { mutableStateOf<WxSessionHr?>(null) }
    LaunchedEffect(w.startTs, w.endTs) {
        data = withContext(Dispatchers.Default) {
            val hr = runCatching { vm.repo.hrSamplesUnion(vm.activeStrapId, w.startTs, w.endTs, limit = 50_000) }.getOrDefault(emptyList())
            val buckets = hr.groupBy { (it.ts - w.startTs) / 15L }.toSortedMap()
                .map { (b, xs) -> (w.startTs + b * 15L + 7L) to xs.map { it.bpm }.average() }
            WxSessionHr(buckets, hrMax?.let { wxZoneSeconds(hr, it) })
        }
    }
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(w.startTs).atZone(zone)
    val end = Instant.ofEpochSecond(w.endTs).atZone(zone)
    val subtitle = "${wxShortDate(start.toLocalDate())} · ${wxClock(start.hour * 60 + start.minute, is24h)} – ${wxClock(end.hour * 60 + end.minute, is24h)}"

    WxPage(
        title = wxWorkoutTitle(w),
        subtitle = subtitle,
        onBack = onBack,
        action = { IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, uiString(R.string.wx_manage_workouts), tint = Wx.textMuted) } },
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val e21 = w.strain?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) }
            WxRing(fraction = e21?.let { it / 21.0 }, color = Wx.effort, size = 132.dp, stroke = 10.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(w.strain?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: "–", style = WxType.number.copy(fontSize = 40.sp), color = Wx.text)
                    WxLabel(uiString(R.string.wx_effort), color = Wx.effort)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row {
                    WxStat(uiString(R.string.wx_duration), wxHoursMinutes(wxDurationS(w) / 60.0), Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_calories), w.energyKcal?.roundToInt()?.toString() ?: "–", Modifier.weight(1f))
                }
                Row {
                    WxStat(uiString(R.string.wx_avg_hr), w.avgHr?.toString() ?: "–", Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_max_hr), w.maxHr?.toString() ?: "–", Modifier.weight(1f))
                }
            }
        }

        WxCard {
            WxCardHeader(uiString(R.string.wx_heart_rate), "bpm")
            Spacer(Modifier.height(10.dp))
            val d = data
            when {
                d == null -> Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Text(uiString(R.string.wx_reading_today), style = WxType.caption, color = Wx.textMuted)
                }
                d.points.size < 2 -> Text(uiString(R.string.wx_no_hr_session), style = WxType.caption, color = Wx.textMuted)
                else -> WxSessionChart(d.points, w.startTs, w.endTs, uiString(R.string.wx_heart_rate), hrMax)
            }
        }
        // The same session in intervals.icu, when connected: its route on the map.
        val icuSame = remember(w.startTs) {
            if (com.noop.intervals.IntervalsKeyStore.enabled(context)) icuMatch(com.noop.intervals.IntervalsStore.activities(context), w.startTs) else null
        }
        icuSame?.let { WxIcuRouteCard(it.id) }

        val zoneSeconds = data?.zoneSeconds ?: parseZonePercents(w.zonesJSON)?.map { it / 100.0 * wxDurationS(w) }
        if (zoneSeconds != null) {
            WxCard {
                WxCardHeader(uiString(R.string.wx_zones), hrMax?.let { uiString(R.string.wx_pct_of_max, it.roundToInt()) })
                Spacer(Modifier.height(14.dp))
                val total = zoneSeconds.sum().coerceAtLeast(1.0)
                val colors = listOf(Wx.zone1, Wx.zone2, Wx.zone3, Wx.zone4, Wx.zone5)
                Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                    zoneSeconds.forEachIndexed { i, s ->
                        if (s > 0.0) Box(Modifier.weight((s / total).toFloat()).fillMaxHeight().background(colors[i]))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    zoneSeconds.forEachIndexed { i, s ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Z${i + 1}", style = WxType.caption.copy(fontSize = 11.sp), color = Wx.textMuted)
                            Text(wxHoursMinutes(s / 60.0), style = WxType.numberSmall.copy(fontSize = 17.sp), color = Wx.text)
                        }
                    }
                }
            }
        }

        val same = all.filter { it.sport == w.sport && it.startTs != w.startTs }.take(5)
        if (same.isNotEmpty()) {
            WxCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    WxLabel(uiString(R.string.wx_recent_workouts), Modifier.padding(top = 14.dp, bottom = 4.dp))
                    same.forEach { o ->
                        WxActivityRow(
                            accent = Wx.effort,
                            title = wxWorkoutTitle(o),
                            subtitle = wxWorkoutLine(o, is24h),
                            trailing = o.strain?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: "–",
                            onClick = { onOpen(o) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun WxSessionChart(points: List<Pair<Long, Double>>, from: Long, to: Long, description: String, hrMax: Double?) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = description }) {
        val span = (to - from).coerceAtLeast(1L).toFloat()
        val lo = points.minOf { it.second } - 5
        val hi = (points.maxOf { it.second } + 5).coerceAtLeast(lo + 20)
        fun x(ts: Long) = ((ts - from) / span * size.width).coerceIn(0f, size.width)
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
        val path = Path()
        var prev: Long? = null
        points.forEach { (ts, bpm) ->
            if (prev == null || ts - prev!! > 120L) path.moveTo(x(ts), y(bpm)) else path.lineTo(x(ts), y(bpm))
            prev = ts
        }
        // The design: zone bands behind a light line.
        hrMax?.let { drawWxZoneBands(it, lo, hi, 0f, size.width, ::y, measurer) }
        for (k in 1..3) {
            val yy = size.height * k / 4f
            drawLine(Wx.line.copy(alpha = 0.6f), Offset(0f, yy), Offset(size.width, yy), 1f)
        }
        drawPath(path, Wx.text, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
