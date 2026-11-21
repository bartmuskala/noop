package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.R
import com.noop.analytics.RestScorer
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Score detail extras
//
// The rest of each detail page: a comparison list against a stated baseline, the night's heart rate,
// bed and wake times, and a "Weekly trends" block of seven-day charts ending on the shown day. Every
// figure is read from data NOOP already stores or computes (daily metrics, sleep sessions, raw heart
// rate with the Effort scorer's own zone function); nothing here estimates a new quantity.

/** Seven dates ending on [end], oldest first, and their axis labels. */
internal fun wxWeekDates(end: LocalDate): List<LocalDate> = (6 downTo 0).map { end.minusDays(it.toLong()) }

internal fun wxWeekLabels(dates: List<LocalDate>): List<String> = dates.map { "${wxWeekdayShort(it)} ${it.dayOfMonth}" }

/** Stored efficiency can be a 0–1 fraction (imports) or a percentage; always return a percentage. */
internal fun wxEfficiencyPct(v: Double?): Double? = v?.let { if (it <= 1.0) it * 100.0 else it }

/**
 * Minutes per Effort zone (index 0 = below zone 1, then zones 1–5) for one calendar day, from the
 * day's raw heart rate with exactly the inputs the Effort score uses: the day's resting HR (default
 * when the night had none), the one max-HR resolver and the scorer's sample durations. Null when the
 * day has too little heart rate or no usable reserve.
 */
internal suspend fun wxDayZoneMinutes(vm: AppViewModel, date: LocalDate, restingHr: Int?, hrMax: Double?): List<Double>? =
    withContext(Dispatchers.Default) {
        runCatching {
            val zone = ZoneId.systemDefault()
            val from = date.atStartOfDay(zone).toEpochSecond()
            val to = minOf(date.plusDays(1).atStartOfDay(zone).toEpochSecond(), System.currentTimeMillis() / 1000L)
            if (to <= from) return@runCatching null
            val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, from, to, limit = 200_000).sortedBy { it.ts }
            if (hr.size < 60) return@runCatching null
            val rest = restingHr?.toDouble() ?: StrainScorer.defaultRestingHR
            val max = hrMax ?: StrainScorer.defaultMaxHR().toDouble()
            val reserve = max - rest
            if (reserve <= 0) return@runCatching null
            StrainScorer.zoneMinutes(hr, rest, reserve, StrainScorer.sampleDurationsMinutes(hr))
        }.getOrNull()
    }

/** The main sleep block that ends on [day]'s morning: the longest ending between noon the day before
 *  and 14:00 on the day, the same rule the detail page uses for its stages. */
internal fun wxMainNight(sessions: List<SleepSession>, day: LocalDate): SleepSession? {
    val zone = ZoneId.systemDefault()
    val to = day.atTime(14, 0).atZone(zone).toEpochSecond()
    val from = day.minusDays(1).atTime(12, 0).atZone(zone).toEpochSecond()
    return sessions.filter { it.endTs in from..to }.maxByOrNull { it.endTs - it.effectiveStartTs }
}

// MARK: Shared pieces

@Composable
internal fun WxSectionTitle(text: String) {
    Text(text, style = WxType.title.copy(fontSize = 22.sp), color = Wx.text, modifier = Modifier.padding(top = 16.dp, start = 4.dp))
}

/** One comparison row: name, baseline caption, today's value and the change against the baseline. */
@Composable
internal fun WxCompareRow(title: String, baseline: String, value: String, pct: Double?, higherIsBetter: Boolean?) {
    val delta = when {
        pct == null -> ""
        abs(pct) < 1 -> "="
        else -> (if (pct > 0) "▲ " else "▼ ") + "${abs(pct).roundToInt()}%"
    }
    val good = if (pct == null || higherIsBetter == null || abs(pct) < 1) null else (pct > 0) == higherIsBetter
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = WxType.bodyStrong, color = Wx.text)
                Text(baseline, style = WxType.caption, color = Wx.textMuted)
            }
            Text(value, style = WxType.numberSmall.copy(fontSize = 24.sp), color = Wx.text)
            Text(delta, style = WxType.caption.copy(fontSize = 13.sp), textAlign = TextAlign.End,
                color = when (good) { true -> Wx.recoveryHigh; false -> Wx.recoveryMid; null -> Wx.textMuted },
                modifier = Modifier.width(58.dp))
        }
    }
}

internal fun wxPct(value: Double?, base: Double?): Double? =
    if (value != null && base != null && base != 0.0) (value - base) / abs(base) * 100 else null

@Composable
internal fun WxTrendCard(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    WxCard {
        WxCardHeader(title, trailing)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun WxLinkCard(title: String, body: String, onClick: () -> Unit) {
    WxCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = WxType.bodyStrong, color = Wx.text)
                Text(body, style = WxType.caption, color = Wx.textMuted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Wx.textMuted)
        }
    }
}

/** Stacked bars: each day's segments bottom-up, the total printed above. */
@Composable
internal fun WxStackBars(
    stacks: List<List<Pair<Double, Color>>?>,
    labels: List<String>,
    format: (Double) -> String,
    description: String,
    height: Dp = 170.dp,
) {
    val measurer = rememberTextMeasurer()
    val max = stacks.maxOfOrNull { s -> s?.sumOf { it.first } ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val n = stacks.size.coerceAtLeast(1)
        val labelH = 18.dp.toPx(); val topPad = 20.dp.toPx()
        val plotH = size.height - labelH - topPad
        val slot = size.width / n; val barW = slot * 0.5f
        stacks.forEachIndexed { i, s ->
            val cx = slot * i + slot / 2
            var y = topPad + plotH
            val total = s?.sumOf { it.first } ?: 0.0
            s?.forEach { (v, c) ->
                if (v <= 0) return@forEach
                val h = (v / max * plotH).toFloat()
                drawRect(c, Offset(cx - barW / 2, y - h), Size(barW, h))
                y -= h
            }
            if (s != null) {
                val t = measurer.measure(format(total), TextStyle(fontSize = 13.sp, color = Wx.text, fontFeatureSettings = "tnum"))
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, y - t.size.height - 2.dp.toPx()))
            }
            val l = measurer.measure(labels.getOrElse(i) { "" }, TextStyle(fontSize = 11.sp, color = Wx.textMuted))
            drawText(l, topLeft = Offset((cx - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), size.height - l.size.height))
        }
    }
}

/** Floating bars for bed → wake per night, on a clock axis from 18:00 to 12:00 next day. */
@Composable
internal fun WxBedWakeBars(
    nights: List<Pair<Long, Long>?>,
    labels: List<String>,
    highlightLast: Boolean,
    color: Color,
    description: String,
    is24h: Boolean,
    height: Dp = 230.dp,
) {
    val measurer = rememberTextMeasurer()
    val zone = ZoneId.systemDefault()
    // Minutes after 18:00 for a timestamp, so an evening bedtime and a morning wake sit on one scale.
    fun axisMin(ts: Long): Float {
        val t = Instant.ofEpochSecond(ts).atZone(zone)
        var m = t.hour * 60 + t.minute - 18 * 60
        if (m < 0) m += 24 * 60
        return m.toFloat()
    }
    val span = 18f * 60f
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val axisW = 40.dp.toPx(); val labelH = 18.dp.toPx(); val pad = 16.dp.toPx()
        val plotH = size.height - labelH - pad * 2
        fun y(m: Float) = pad + plotH * (m / span)
        listOf(0, 4 * 60, 8 * 60, 12 * 60, 16 * 60).forEach { m ->
            val yy = y(m.toFloat())
            drawLine(Wx.line, Offset(axisW, yy), Offset(size.width, yy), 1f)
            val t = measurer.measure(wxClock(18 * 60 + m, is24h), TextStyle(fontSize = 10.sp, color = Wx.textDim))
            drawText(t, topLeft = Offset(0f, yy - t.size.height / 2f))
        }
        val n = nights.size.coerceAtLeast(1)
        val slot = (size.width - axisW) / n
        val barW = slot * 0.4f
        nights.forEachIndexed { i, nw ->
            val cx = axisW + slot * i + slot / 2
            if (nw != null) {
                val y0 = y(axisMin(nw.first)); val y1 = y(axisMin(nw.second)).coerceAtLeast(y0 + 4f)
                val c = if (!highlightLast || i == nights.lastIndex) color else Wx.textDim
                drawRoundRect(c, Offset(cx - barW / 2, y0), Size(barW, y1 - y0), CornerRadius(4.dp.toPx()))
                if (i == nights.lastIndex) {
                    fun clock(ts: Long) = Instant.ofEpochSecond(ts).atZone(zone).let { wxClock(it.hour * 60 + it.minute, is24h) }
                    val a = measurer.measure(clock(nw.first), TextStyle(fontSize = 11.sp, color = color))
                    val b = measurer.measure(clock(nw.second), TextStyle(fontSize = 11.sp, color = color))
                    drawText(a, topLeft = Offset((cx - a.size.width / 2f).coerceIn(axisW, size.width - a.size.width), y0 - a.size.height))
                    drawText(b, topLeft = Offset((cx - b.size.width / 2f).coerceIn(axisW, size.width - b.size.width), y1 + 1f))
                }
            }
            val l = measurer.measure(labels.getOrElse(i) { "" }, TextStyle(fontSize = 11.sp, color = Wx.textMuted))
            drawText(l, topLeft = Offset((cx - l.size.width / 2f).coerceIn(axisW, size.width - l.size.width), size.height - l.size.height))
        }
    }
}

/** The night's heart rate (one-minute means) with bed and wake marked and a bpm axis. */
@Composable
internal fun WxNightHrChart(points: List<Pair<Long, Double>>, bed: Long, wake: Long, is24h: Boolean, description: String) {
    val measurer = rememberTextMeasurer()
    val zone = ZoneId.systemDefault()
    Canvas(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = description }) {
        val axisStyle = TextStyle(fontSize = 10.sp, color = Wx.textDim)
        val axisW = 28.dp.toPx(); val labelH = 18.dp.toPx(); val top = 12.dp.toPx()
        val plotH = size.height - labelH
        val from = points.first().first; val to = points.last().first.coerceAtLeast(from + 1)
        fun x(ts: Long) = axisW + (size.width - axisW) * ((ts - from).toFloat() / (to - from)).coerceIn(0f, 1f)
        val lo = (Math.floor((points.minOf { it.second } - 5) / 20.0) * 20).coerceAtLeast(20.0)
        val hi = (Math.ceil((points.maxOf { it.second } + 5) / 20.0) * 20).coerceAtLeast(lo + 40)
        fun y(v: Double) = (top + (plotH - top) * (1 - (v - lo) / (hi - lo))).toFloat()
        var tick = lo
        while (tick <= hi + 0.1) {
            val yy = y(tick)
            drawLine(Wx.line, Offset(axisW, yy), Offset(size.width, yy), 1f)
            val t = measurer.measure("${tick.toInt()}", axisStyle)
            drawText(t, topLeft = Offset(0f, yy - t.size.height / 2f))
            tick += 20.0
        }
        val path = Path()
        var prev: Long? = null
        points.forEach { (ts, v) ->
            if (prev == null || ts - prev!! > 600L) path.moveTo(x(ts), y(v)) else path.lineTo(x(ts), y(v))
            prev = ts
        }
        drawPath(path, Wx.sleepSoft, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        listOf(bed, wake).forEach { ts ->
            val xx = x(ts)
            drawLine(Wx.textMuted, Offset(xx, top), Offset(xx, plotH), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val t = Instant.ofEpochSecond(ts).atZone(zone)
            val l = measurer.measure(wxClock(t.hour * 60 + t.minute, is24h), TextStyle(fontSize = 11.sp, color = Wx.text))
            drawText(l, topLeft = Offset((xx - l.size.width / 2f).coerceIn(axisW, size.width - l.size.width), size.height - l.size.height))
        }
    }
}

/** A stage row: name, share of the night, time, and a bar with the 30-day average marked. */
@Composable
internal fun WxStageRow(name: String, color: Color, minutes: Double, totalInBed: Double, avgMinutes: Double?) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name.uppercase(), style = WxType.label, color = Wx.text)
            Spacer(Modifier.width(8.dp))
            Text("${(minutes / totalInBed.coerceAtLeast(1.0) * 100).roundToInt()}%", style = WxType.caption, color = color)
            Spacer(Modifier.weight(1f))
            Text(wxHoursMinutes(minutes), style = WxType.numberSmall, color = Wx.text)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Wx.cardRaised, cornerRadius = r)
            val f = (minutes / totalInBed.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
            avgMinutes?.let { a ->
                val ax = size.width * (a / totalInBed.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                drawLine(Wx.text, Offset(ax, -2f), Offset(ax, size.height + 2f), 2f)
            }
        }
    }
}

// MARK: Weekly trends per tab

@Composable
internal fun WxRecoveryWeek(days: List<DailyMetric>, end: LocalDate) {
    val dates = wxWeekDates(end)
    val labels = wxWeekLabels(dates)
    val by = days.associateBy { it.day }
    fun col(sel: (DailyMetric) -> Double?) = dates.map { by[it.toString()]?.let(sel) }
    WxSectionTitle(uiString(R.string.wx_weekly_trends))
    WxTrendCard(uiString(R.string.wx_recovery)) {
        WxBars(col { it.recovery }, labels, { Wx.recoveryColor(it) }, { "${it.roundToInt()}%" }, 100.0,
            uiString(R.string.wx_recovery), height = 170.dp)
    }
    WxWeekLine(uiString(R.string.wx_hrv_long), col { it.avgHrv }, labels, Wx.effort) { "${it.roundToInt()}" }
    WxWeekLine(uiString(R.string.wx_rhr_long), col { it.restingHr?.toDouble() }, labels, Wx.heart) { "${it.roundToInt()}" }
    WxWeekLine(uiString(R.string.wx_resp_long), col { it.respRateBpm }, labels, Wx.textSoft) { String.format(Locale.US, "%.1f", it) }
    WxTrendCard(uiString(R.string.wx_sleep_performance)) {
        WxBars(col { RestScorer.restFromDaily(it) }, labels, { Wx.sleep }, { "${it.roundToInt()}%" }, 100.0,
            uiString(R.string.wx_sleep_performance), height = 170.dp)
    }
}

@Composable
internal fun WxWeekLine(title: String, values: List<Double?>, labels: List<String>, color: Color, format: (Double) -> String) {
    val present = values.filterNotNull()
    WxTrendCard(title) {
        if (present.isEmpty()) {
            Text(uiString(R.string.wx_no_data), style = WxType.caption, color = Wx.textMuted)
        } else {
            val lo = present.minOrNull()!!; val hi = present.maxOrNull()!!
            val pad = ((hi - lo) * 0.3).coerceAtLeast(1.0)
            WxLineChart(values, labels, { color }, format, lo - pad, hi + pad, title, height = 160.dp)
        }
    }
}

@Composable
internal fun WxEffortWeek(
    days: List<DailyMetric>,
    end: LocalDate,
    zones: Map<String, List<Double>>,
    scale: EffortScale,
) {
    val dates = wxWeekDates(end)
    val labels = wxWeekLabels(dates)
    val by = days.associateBy { it.day }
    WxSectionTitle(uiString(R.string.wx_weekly_trends))
    WxTrendCard(uiString(R.string.wx_effort)) {
        WxBars(dates.map { d -> by[d.toString()]?.strain?.let { UnitFormatter.effortValue(it, scale) } }, labels, { Wx.effort },
            { String.format(Locale.US, "%.1f", it) }, UnitFormatter.effortValue(100.0, scale), uiString(R.string.wx_effort), height = 170.dp)
    }
    val z = dates.map { zones[it.toString()] }
    WxTrendCard(uiString(R.string.wx_hr_zones_13)) {
        WxZoneLegend(listOf(1 to Wx.zone1, 2 to Wx.zone2, 3 to Wx.zone3))
        Spacer(Modifier.height(8.dp))
        WxStackBars(z.map { it?.let { m -> listOf(m[1] to Wx.zone1, m[2] to Wx.zone2, m[3] to Wx.zone3) } }, labels,
            { wxHoursMinutes(it) }, uiString(R.string.wx_hr_zones_13))
    }
    WxTrendCard(uiString(R.string.wx_hr_zones_45)) {
        WxZoneLegend(listOf(4 to Wx.zone4, 5 to Wx.zone5))
        Spacer(Modifier.height(8.dp))
        WxStackBars(z.map { it?.let { m -> listOf(m[4] to Wx.zone4, m[5] to Wx.zone5) } }, labels,
            { wxHoursMinutes(it) }, uiString(R.string.wx_hr_zones_45))
    }
    WxTrendCard(uiString(R.string.wx_steps)) {
        val v = dates.map { by[it.toString()]?.steps?.toDouble() }
        WxBars(v, labels, { Wx.effort }, { String.format(Locale.getDefault(), "%,d", it.roundToInt()) },
            (v.filterNotNull().maxOrNull() ?: 1.0) * 1.15, uiString(R.string.wx_steps), height = 170.dp)
    }
    WxTrendCard(uiString(R.string.wx_calories)) {
        val v = dates.map { by[it.toString()]?.activeKcalEst }
        WxBars(v, labels, { Wx.effort }, { String.format(Locale.getDefault(), "%,d", it.roundToInt()) },
            (v.filterNotNull().maxOrNull() ?: 1.0) * 1.15, uiString(R.string.wx_calories), height = 170.dp)
    }
}

@Composable
internal fun WxZoneLegend(zones: List<Pair<Int, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        zones.forEach { (n, c) -> WxLegend(c, uiString(R.string.wx_zone_n, n), "") }
    }
}

@Composable
internal fun WxSleepWeek(
    days: List<DailyMetric>,
    end: LocalDate,
    sessions: List<SleepSession>,
    needMin: Double,
    debtByDay: Map<String, Double>,
    is24h: Boolean,
) {
    val dates = wxWeekDates(end)
    val labels = wxWeekLabels(dates)
    val by = days.associateBy { it.day }
    fun col(sel: (DailyMetric) -> Double?) = dates.map { by[it.toString()]?.let(sel) }
    WxSectionTitle(uiString(R.string.wx_weekly_trends))
    WxTrendCard(uiString(R.string.wx_sleep_performance)) {
        WxBars(col { RestScorer.restFromDaily(it) }, labels, { Wx.sleep }, { "${it.roundToInt()}%" }, 100.0,
            uiString(R.string.wx_sleep_performance), height = 170.dp)
    }
    WxTrendCard(uiString(R.string.wx_hours_vs_needed)) {
        val slept = col { it.totalSleepMin }
        val all = slept.filterNotNull() + needMin
        val lo = (all.minOrNull()!! - 60).coerceAtLeast(0.0); val hi = all.maxOrNull()!! + 60
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WxLegend(Wx.sleepSoft, uiString(R.string.wx_hours), "")
            WxLegend(Wx.recoveryHigh, uiString(R.string.wx_needed), wxHoursMinutes(needMin))
        }
        Spacer(Modifier.height(8.dp))
        WxTwoLineChart(
            WxSeries(slept, Wx.sleepSoft, lo, hi, { wxHoursMinutes(it) }),
            WxSeries(dates.map { needMin }, Wx.recoveryHigh, lo, hi, { "" }),
            labels, uiString(R.string.wx_hours_vs_needed), height = 200.dp,
        )
    }
    WxTrendCard(uiString(R.string.wx_restorative)) {
        WxZoneLegendNamed(listOf(uiString(R.string.wx_deep_sleep) to Wx.stageDeep, uiString(R.string.wx_rem_sleep) to Wx.stageRem))
        Spacer(Modifier.height(8.dp))
        WxStackBars(dates.map { d -> by[d.toString()]?.let { r ->
            if (r.deepMin == null && r.remMin == null) null else listOf((r.deepMin ?: 0.0) to Wx.stageDeep, (r.remMin ?: 0.0) to Wx.stageRem)
        } }, labels, { wxHoursMinutes(it) }, uiString(R.string.wx_restorative))
    }
    WxTrendCard(uiString(R.string.wx_time_in_bed)) {
        WxBedWakeBars(dates.map { d -> wxMainNight(sessions, d)?.let { it.effectiveStartTs to it.endTs } }, labels,
            highlightLast = false, color = Wx.sleepSoft, description = uiString(R.string.wx_time_in_bed), is24h = is24h)
    }
    WxWeekLine(uiString(R.string.wx_efficiency), col { wxEfficiencyPct(it.efficiency) }, labels, Wx.sleepSoft) { "${it.roundToInt()}%" }
    WxTrendCard(uiString(R.string.wx_sleep_debt)) {
        val v = dates.map { debtByDay[it.toString()] }
        WxBars(v, labels, { Wx.sleepSoft }, { wxHoursMinutes(it) }, (v.filterNotNull().maxOrNull() ?: 60.0).coerceAtLeast(60.0) * 1.2,
            uiString(R.string.wx_sleep_debt), height = 170.dp)
    }
}

@Composable
internal fun WxZoneLegendNamed(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (n, c) -> WxLegend(c, n, "") }
    }
}
