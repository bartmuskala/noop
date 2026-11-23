package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.R
import com.noop.intervals.IntervalsParse
import com.noop.intervals.IntervalsStore
import com.noop.intervals.streams
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - intervals.icu on the Workouts tab (fork)
//
// Everything here reads the on-phone cache of the last intervals.icu sync. Loads are intervals.icu's
// own training load (its scale, not NOOP's Effort), and every card says where its numbers come from.

/** Epoch seconds of an intervals.icu local start time, on this phone's zone. */
internal fun icuEpoch(startLocal: String): Long? = runCatching {
    LocalDateTime.parse(startLocal.take(19)).atZone(ZoneId.systemDefault()).toEpochSecond()
}.getOrNull()

/** The intervals.icu activity that is the same session as a NOOP workout (starts within 30 minutes). */
internal fun icuMatch(acts: List<IntervalsParse.Activity>, startTs: Long): IntervalsParse.Activity? =
    acts.mapNotNull { a -> icuEpoch(a.startLocal)?.let { a to kotlin.math.abs(it - startTs) } }
        .filter { it.second <= 1800L }.minByOrNull { it.second }?.first

private fun km(m: Double?) = m?.let { String.format(Locale.getDefault(), "%.1f km", it / 1000.0) }

@Composable
internal fun WxPlannedCard(events: List<IntervalsParse.Event>, is24h: Boolean, onOpen: (IntervalsParse.Event) -> Unit = {}) {
    val today = LocalDate.now().toString()
    // Coming days only; today's session has its own card above.
    val upcoming = events.filter { it.isWorkout && it.day > today }.sortedBy { it.startLocal }.take(10)
    WxCard(padding = 16.dp) {
        WxCardHeader(uiString(R.string.wx_icu_planned), "intervals.icu")
        if (upcoming.isEmpty()) {
            Text(uiString(R.string.wx_icu_no_planned), style = WxType.caption, color = Wx.textMuted, modifier = Modifier.padding(top = 10.dp))
        }
        upcoming.forEach { e ->
            val d = runCatching { LocalDate.parse(e.day) }.getOrNull()
            WxActivityRow(
                accent = Wx.effort.copy(alpha = 0.5f),
                title = e.name ?: e.type ?: uiString(R.string.wx_workout),
                subtitle = listOfNotNull(d?.let { wxShortDate(it) }, e.movingS?.let { wxHoursMinutes(it / 60.0) }, e.type).joinToString(" · "),
                trailing = e.load?.let { uiString(R.string.wx_icu_load_value, it.roundToInt()) } ?: "",
                onClick = { onOpen(e) },
            )
        }
    }
}

@Composable
internal fun WxIcuActivitiesCard(acts: List<IntervalsParse.Activity>, onOpen: (IntervalsParse.Activity) -> Unit) {
    val recent = acts.sortedByDescending { it.startLocal }.take(10)
    if (recent.isEmpty()) return
    WxCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            WxLabel(uiString(R.string.wx_icu_rides), Modifier.padding(top = 14.dp, bottom = 4.dp))
            recent.forEach { a ->
                val d = runCatching { LocalDate.parse(a.day) }.getOrNull()
                WxActivityRow(
                    accent = Wx.effort,
                    title = a.name ?: a.type ?: uiString(R.string.wx_workout),
                    subtitle = listOfNotNull(d?.let { wxShortDate(it) }, km(a.distanceM), a.movingS?.let { wxHoursMinutes(it / 60.0) }).joinToString(" · "),
                    trailing = a.load?.let { uiString(R.string.wx_icu_load_value, it.roundToInt()) } ?: "",
                    onClick = { onOpen(a) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The route of [activityId] on the map, fetched once and cached; nothing when it has no GPS. */
@Composable
internal fun WxIcuRouteCard(activityId: String) {
    val ctx = LocalContext.current
    var route by remember(activityId) { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    LaunchedEffect(activityId) { route = IntervalsStore.route(ctx, activityId) }
    val r = route
    WxCard(padding = 12.dp) {
        WxCardHeader(uiString(R.string.wx_icu_route))
        Spacer(Modifier.height(8.dp))
        when {
            r == null -> Text(uiString(R.string.wx_reading_today), style = WxType.caption, color = Wx.textMuted)
            r.size < 2 -> Text(uiString(R.string.wx_icu_no_route), style = WxType.caption, color = Wx.textMuted)
            else -> WxRouteMap(r, description = uiString(R.string.wx_icu_route))
        }
    }
}

/**
 * One intervals.icu session: NOOP's Effort from the strap's heart rate in that window, the route, the
 * power and heart rate through the session (from its streams), power figures and time in Coggan's
 * power zones against the FTP intervals.icu scored it with.
 */
@Composable
internal fun WxIcuActivityDetail(vm: AppViewModel, a: IntervalsParse.Activity, row: com.noop.data.WorkoutRow?, scale: EffortScale, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d = runCatching { LocalDate.parse(a.day) }.getOrNull()
    val efforts by IcuEfforts.flow.collectAsStateWithLifecycle()
    val effort = row?.strain ?: efforts[a.id]
    var streams by remember(a.id) { mutableStateOf<Map<String, List<Double?>>?>(null) }
    LaunchedEffect(a.id) { streams = com.noop.intervals.IntervalsStore.streams(ctx, a.id) }
    WxPage(title = a.name ?: wxSportName(a.type ?: "Workout"),
        subtitle = listOfNotNull(d?.let { wxShortDate(it) }, a.type?.let { wxSportName(it) }, "intervals.icu").joinToString(" · "), onBack = onBack) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val e21 = effort?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) }
            WxRing(fraction = e21?.let { it / 21.0 }, color = Wx.effort, size = 120.dp, stroke = 10.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(effort?.let { UnitFormatter.effortDisplay(it, scale) } ?: "–", style = WxType.number.copy(fontSize = 36.sp), color = Wx.text)
                    WxLabel(uiString(R.string.wx_effort), color = Wx.effort)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    WxStat(uiString(R.string.wx_distance), km(a.distanceM) ?: "–", Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_duration), a.movingS?.let { wxHoursMinutes(it / 60.0) } ?: "–", Modifier.weight(1f))
                }
                Row {
                    WxStat(uiString(R.string.wx_icu_load), a.load?.roundToInt()?.toString() ?: "–", Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_elevation), a.elevationM?.let { "${it.roundToInt()} m" } ?: "–", Modifier.weight(1f))
                }
            }
        }
        if (effort == null) Text(uiString(R.string.wx_icu_no_strap_hr), style = WxType.caption, color = Wx.textMuted)
        WxIcuRouteCard(a.id)
        val st = streams
        val watts = st?.get("watts")?.takeIf { w -> w.count { it != null && it > 0 } > 30 }
        val hr = st?.get("heartrate")?.takeIf { h -> h.count { it != null } > 30 }
        if (watts != null || hr != null) {
            WxCard(padding = 16.dp) {
                WxCardHeader(uiString(if (watts != null) R.string.wx_icu_power_hr else R.string.wx_heart_rate))
                Spacer(Modifier.height(8.dp))
                WxIcuStreamChart(watts, hr, st["time"].orEmpty())
            }
        }
        if (a.avgWatts != null || watts != null) {
            WxCard {
                WxCardHeader(uiString(R.string.wx_icu_power), a.ftp?.let { "FTP ${it.roundToInt()} W" })
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    WxStat(uiString(R.string.wx_icu_avg_power), a.avgWatts?.let { "${it.roundToInt()} W" } ?: "–", Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_icu_np), a.normWatts?.let { "${it.roundToInt()} W" } ?: "–", Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_icu_if), a.intensityFactor?.let { String.format(Locale.US, "%.2f", it) } ?: "–", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    WxStat(uiString(R.string.wx_icu_max_power), watts?.mapNotNull { it }?.maxOrNull()?.let { "${it.roundToInt()} W" } ?: "–", Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_icu_cadence), a.avgCadence?.let { "${it.roundToInt()} rpm" } ?: "–", Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_avg_hr), a.avgHr?.roundToInt()?.toString() ?: "–", Modifier.weight(1f))
                }
                val ftp = a.ftp
                val zones = if (watts != null && ftp != null) IntervalsParse.powerZoneSeconds(watts, st["time"].orEmpty(), ftp) else null
                if (zones != null && zones.sum() > 0) {
                    Spacer(Modifier.height(14.dp))
                    WxLabel(uiString(R.string.wx_icu_power_zones))
                    Spacer(Modifier.height(8.dp))
                    val colors = listOf(Wx.textDim, Wx.zone1, Wx.zone2, Wx.zone3, Wx.zone4, Wx.zone5, Wx.heart)
                    val total = zones.sum()
                    zones.forEachIndexed { i, sec ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Z${i + 1}", style = WxType.caption, color = Wx.textMuted, modifier = Modifier.width(28.dp))
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Wx.cardRaised)) {
                                Box(Modifier.fillMaxWidth((sec / total).toFloat().coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors[i]))
                            }
                            Text(wxHoursMinutes(sec / 60.0), style = WxType.caption, color = Wx.textSoft, modifier = Modifier.width(56.dp).padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Power (and heart rate on its own scale) through the session, from its streams. */
@Composable
private fun WxIcuStreamChart(watts: List<Double?>?, hr: List<Double?>?, time: List<Double?>) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val axisH = 14.dp.toPx(); val plotH = size.height - axisH
        val n = maxOf(watts?.size ?: 0, hr?.size ?: 0)
        if (n < 2) return@Canvas
        // ~300 points: 30-second-ish means keep the line readable.
        val bucket = (n / 300).coerceAtLeast(1)
        fun series(v: List<Double?>): List<Double?> = (0 until n step bucket).map { s ->
            v.subList(s, minOf(s + bucket, v.size)).filterNotNull().takeIf { it.isNotEmpty() }?.average()
        }
        fun x(i: Int, m: Int) = size.width * i / (m - 1).coerceAtLeast(1)
        for (k in 1..3) drawLine(Wx.line, Offset(0f, plotH * k / 4f), Offset(size.width, plotH * k / 4f), 1f)
        fun draw(v: List<Double?>, c: Color, w: Float) {
            val pts = series(v); val hi = pts.filterNotNull().maxOrNull()?.coerceAtLeast(1.0) ?: return
            val p = Path(); var open = false
            pts.forEachIndexed { i, y ->
                if (y == null) { open = false; return@forEachIndexed }
                val yy = (plotH - plotH * 0.95 * (y / hi)).toFloat()
                if (!open) { p.moveTo(x(i, pts.size), yy); open = true } else p.lineTo(x(i, pts.size), yy)
            }
            drawPath(p, c, style = Stroke(w, cap = StrokeCap.Round))
        }
        hr?.let { draw(it, Wx.heart.copy(alpha = 0.75f), 1.5.dp.toPx()) }
        watts?.let { draw(it, Wx.effort, 1.8.dp.toPx()) }
        val total = time.lastOrNull { it != null } ?: n.toDouble()
        listOf(0.0, 0.5, 1.0).forEach { f ->
            val t = measurer.measure("${(total * f / 60).roundToInt()}'", TextStyle(fontSize = 10.sp, color = Wx.textDim))
            drawText(t, topLeft = Offset((size.width * f.toFloat() - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
        }
    }
}

/**
 * intervals.icu's fitness (CTL) and fatigue (ATL) for the last 90 days, continued as a dashed
 * projection over the next 14 days if the planned workouts are done (see [IntervalsParse.project]).
 */
@Composable
internal fun WxIcuFitnessCard(wellness: List<IntervalsParse.Wellness>, events: List<IntervalsParse.Event>) {
    val today = LocalDate.now()
    val past = wellness.filter { it.day <= today.toString() && it.ctl != null && it.atl != null }.takeLast(90)
    if (past.size < 2) return
    val last = past.last()
    val planned = events.filter { it.isWorkout && it.load != null }.groupBy { it.day }.mapValues { (_, v) -> v.sumOf { it.load!! } }
    val proj = remember(wellness, events) {
        IntervalsParse.project(LocalDate.parse(last.day), last.ctl!!, last.atl!!, planned, today.plusDays(14))
    }
    val endCtl = proj.lastOrNull()?.second
    val c0 = last.ctl!!; val a0 = last.atl!!
    WxCard(padding = 16.dp) {
        WxCardHeader(uiString(R.string.wx_training_load), "intervals.icu")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            WxIcuStat(uiString(R.string.wx_fitness), c0, Wx.effort, Modifier.weight(1f))
            WxIcuStat(uiString(R.string.wx_fatigue), a0, Wx.heart, Modifier.weight(1f))
            WxIcuStat(uiString(R.string.wx_form), c0 - a0, if (c0 >= a0) Wx.recoveryHigh else Wx.recoveryMid, Modifier.weight(1f), signed = true)
        }
        Spacer(Modifier.height(12.dp))
        val measurer = rememberTextMeasurer()
        val a11y = uiString(R.string.wx_training_load)
        Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = a11y }) {
            val axisH = 16.dp.toPx(); val plotH = size.height - axisH
            val n = past.size + proj.size
            fun x(i: Int) = size.width * i / (n - 1).coerceAtLeast(1)
            val hi = (past.maxOf { maxOf(it.ctl!!, it.atl!!) }.coerceAtLeast(proj.maxOfOrNull { maxOf(it.second, it.third) } ?: 0.0)).coerceAtLeast(1.0) * 1.1
            fun y(v: Double) = (plotH - plotH * (v / hi)).toFloat()
            for (k in 1..3) drawLine(Wx.line, Offset(0f, plotH * k / 4f), Offset(size.width, plotH * k / 4f), 1f)
            val todayX = x(past.size - 1)
            drawLine(Wx.lineStrong, Offset(todayX, 0f), Offset(todayX, plotH), 1f)
            fun series(hist: List<Double>, fut: List<Double>, c: Color) {
                val p = Path(); hist.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
                drawPath(p, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                if (fut.isNotEmpty()) {
                    val q = Path(); q.moveTo(x(hist.size - 1), y(hist.last()))
                    fut.forEachIndexed { i, v -> q.lineTo(x(hist.size + i), y(v)) }
                    drawPath(q, c.copy(alpha = 0.8f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                }
            }
            series(past.map { it.atl!! }, proj.map { it.third }, Wx.heart.copy(alpha = 0.85f))
            series(past.map { it.ctl!! }, proj.map { it.second }, Wx.effort)
            val style = TextStyle(fontSize = 10.sp, color = Wx.textDim)
            listOf(0 to past.first().day, past.size - 1 to today.toString(), n - 1 to (proj.lastOrNull()?.first ?: last.day)).forEach { (i, day) ->
                val t = measurer.measure(wxShortDate(LocalDate.parse(day)), style)
                drawText(t, topLeft = Offset((x(i) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
            }
        }
        if (endCtl != null) {
            Spacer(Modifier.height(8.dp))
            Text(uiString(R.string.wx_icu_projection, endCtl.roundToInt()), style = WxType.caption, color = Wx.textMuted)
        }
    }
}

@Composable
private fun WxIcuStat(label: String, v: Double, color: Color, modifier: Modifier, signed: Boolean = false) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            WxLabel(label)
        }
        Text((if (signed && v > 0) "+" else "") + v.roundToInt().toString(), style = WxType.numberSmall, color = Wx.text)
    }
}
