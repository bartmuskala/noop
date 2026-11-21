package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.R
import com.noop.analytics.DaytimeStress
import com.noop.data.WorkoutRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Wx day heart-rate & stress card
//
// The selected day's heart rate as a line (5-minute means), every workout of that day as a labelled
// band behind it, and the hourly stress level as a coloured strip underneath. Works for any day: the
// window is that day's local midnight to the next (or to now for today). Loaded off the main thread.

private data class WxDayHeart(
    val points: List<Pair<Long, Double>>,
    val stressHours: List<DaytimeStress.HourPoint>,
    val from: Long,
    val to: Long,
)

@Composable
internal fun WxDayHeartCard(
    vm: AppViewModel,
    date: LocalDate,
    workouts: List<WorkoutRow>,
    liveBpm: Int?,
    onOpenStress: () -> Unit,
) {
    var data by remember(date) { mutableStateOf<WxDayHeart?>(null) }
    LaunchedEffect(date) {
        data = withContext(Dispatchers.Default) {
            runCatching {
                val zone = ZoneId.systemDefault()
                val from = date.atStartOfDay(zone).toEpochSecond()
                val now = System.currentTimeMillis() / 1000L
                val to = minOf(date.plusDays(1).atStartOfDay(zone).toEpochSecond(), now)
                val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, from, to, limit = 200_000)
                // 5-minute means keep the line readable and the draw cheap.
                val buckets = hr.groupBy { (it.ts - from) / 300L }.toSortedMap()
                    .map { (b, xs) -> (from + b * 300L + 150L) to xs.map { it.bpm }.average() }
                val stress = if (hr.size < DaytimeStress.minHourHrSamples) emptyList() else {
                    val tz = zone.rules.getOffset(Instant.ofEpochSecond(to)).totalSeconds.toLong()
                    val rr = vm.repo.rrIntervalsUnion(vm.activeStrapId, from, to, limit = 200_000)
                    val gravity = vm.repo.gravitySamplesUnion(vm.activeStrapId, from, to, limit = 200_000)
                    DaytimeStress.analyze(hr, rr, gravity, tz).hours
                }
                WxDayHeart(buckets, stress, from, date.plusDays(1).atStartOfDay(zone).toEpochSecond())
            }.getOrNull()
        }
    }
    val zone = ZoneId.systemDefault()
    val dayFrom = date.atStartOfDay(zone).toEpochSecond()
    val dayTo = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val dayWorkouts = workouts.filter { it.endTs > dayFrom && it.startTs < dayTo }.sortedBy { it.startTs }
    val scored = data?.stressHours?.mapNotNull { it.level }.orEmpty()
    val stressNow = data?.stressHours?.lastOrNull { it.level != null }?.level

    WxCard(onClick = onOpenStress) {
        WxCardHeader(
            uiString(R.string.wx_heart_and_stress),
            when {
                liveBpm != null && date == LocalDate.now() -> uiString(R.string.wx_bpm_now, liveBpm)
                data?.points?.isNotEmpty() == true ->
                    uiString(R.string.wx_bpm_avg, data!!.points.map { it.second }.average().roundToInt())
                else -> null
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            WxLegend(Wx.heart, uiString(R.string.wx_heart_rate), "")
            if (dayWorkouts.isNotEmpty()) WxLegend(Wx.effort, uiString(R.string.wx_workouts), "${dayWorkouts.size}")
            if (stressNow != null || scored.isNotEmpty()) {
                WxLegend(Wx.stressColor(stressNow), uiString(R.string.wx_stress_monitor),
                    (stressNow ?: scored.average()).let { String.format(Locale.US, "%.1f", it) })
            }
        }
        Spacer(Modifier.height(10.dp))
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                Text(uiString(R.string.wx_reading_today), style = WxType.caption, color = Wx.textMuted)
            }
        } else if (d.points.size < 2) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                Text(uiString(R.string.wx_no_hr_day), style = WxType.caption, color = Wx.textMuted)
            }
        } else {
            WxDayHeartChart(d, dayWorkouts, uiString(R.string.wx_heart_chart_a11y))
        }
    }
}

@Composable
private fun WxDayHeartChart(d: WxDayHeart, workouts: List<WorkoutRow>, description: String) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = description }) {
        val axisH = 16.dp.toPx()
        val stripH = 10.dp.toPx()
        val gap = 6.dp.toPx()
        val plotH = size.height - axisH - stripH - gap
        val span = (d.to - d.from).coerceAtLeast(1L).toFloat()
        fun x(ts: Long) = ((ts - d.from) / span * size.width).coerceIn(0f, size.width)
        val lo = (d.points.minOf { it.second } - 5).coerceAtLeast(30.0)
        val hi = (d.points.maxOf { it.second } + 5).coerceAtLeast(lo + 20)
        fun y(v: Double) = (plotH - (v - lo) / (hi - lo) * (plotH - 14.dp.toPx())).toFloat()

        // Workout bands with their sport name, drawn first so the line sits on top.
        workouts.forEach { w ->
            val x0 = x(w.startTs); val x1 = x(w.endTs).coerceAtLeast(x0 + 3f)
            drawRect(Wx.effort.copy(alpha = 0.22f), Offset(x0, 0f), Size(x1 - x0, plotH))
            drawLine(Wx.effort, Offset(x0, 0f), Offset(x0, plotH), 1.5f)
            val label = measurer.measure(wxSportName(w.sport), TextStyle(fontSize = 10.sp, color = Wx.effort))
            if (x1 - x0 > 8f || workouts.size < 4) {
                drawText(label, topLeft = Offset((x0 + 3f).coerceAtMost(size.width - label.size.width), 0f))
            }
        }
        // Heart rate line; a gap of more than 20 minutes breaks it.
        val path = Path()
        var prev: Long? = null
        d.points.forEach { (ts, bpm) ->
            if (prev == null || ts - prev!! > 1200L) path.moveTo(x(ts), y(bpm)) else path.lineTo(x(ts), y(bpm))
            prev = ts
        }
        drawPath(path, Wx.heart, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))

        // Hourly stress strip.
        val stripTop = plotH + gap
        d.stressHours.forEach { h ->
            val lvl = h.level ?: return@forEach
            val x0 = x(h.startTs); val x1 = x(h.startTs + 3600L)
            drawRoundRect(Wx.stressColor(lvl), Offset(x0 + 1f, stripTop), Size((x1 - x0 - 2f).coerceAtLeast(2f), stripH),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
        // Time axis every 6 hours.
        for (hh in listOf(0, 6, 12, 18)) {
            val t = measurer.measure(String.format(Locale.US, "%02d", hh), TextStyle(fontSize = 10.sp, color = Wx.textDim))
            val xx = (size.width * hh / 24f).coerceAtMost(size.width - t.size.width)
            drawText(t, topLeft = Offset(xx, size.height - t.size.height))
        }
    }
}
