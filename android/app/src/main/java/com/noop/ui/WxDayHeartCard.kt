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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.abs
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
            WxLegend(Wx.stressLow, uiString(R.string.wx_heart_rate_by_stress), "")
            if (dayWorkouts.isNotEmpty()) WxLegend(Wx.effort, uiString(R.string.wx_workouts), "${dayWorkouts.size}")
            if (stressNow != null || scored.isNotEmpty()) {
                WxLegend(Wx.stressColor(stressNow), uiString(R.string.wx_stress_monitor),
                    (stressNow ?: scored.average()).let { String.format(Locale.US, "%.1f", it) })
            }
        }
        Spacer(Modifier.height(10.dp))
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
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
    // Touch readout: the x position under the finger, null when nothing is touched.
    var touchX by remember(d) { mutableStateOf<Float?>(null) }
    val zone = ZoneId.systemDefault()
    Canvas(
        Modifier.fillMaxWidth().height(260.dp)
            .semantics { contentDescription = description }
            .pointerInput(d) {
                detectTapGestures(onPress = { p ->
                    touchX = p.x
                    tryAwaitRelease()
                    touchX = null
                })
            }
            .pointerInput(d) {
                detectHorizontalDragGestures(
                    onDragStart = { p -> touchX = p.x },
                    onDragEnd = { touchX = null },
                    onDragCancel = { touchX = null },
                    onHorizontalDrag = { change, _ -> touchX = change.position.x },
                )
            },
    ) {
        val axisStyle = TextStyle(fontSize = 10.sp, color = Wx.textDim)
        val yAxisW = 30.dp.toPx()
        val axisH = 18.dp.toPx()
        val stripH = 10.dp.toPx()
        val gap = 6.dp.toPx()
        val topPad = 14.dp.toPx()
        val plotL = yAxisW
        val plotW = size.width - yAxisW
        val plotH = size.height - axisH - stripH - gap
        val span = (d.to - d.from).coerceAtLeast(1L).toFloat()
        fun x(ts: Long) = plotL + ((ts - d.from) / span * plotW).coerceIn(0f, plotW)
        val minBpm = d.points.minOf { it.second }
        val maxBpm = d.points.maxOf { it.second }
        // Axis in steps of 20 bpm around the day's range.
        val lo = (Math.floor((minBpm - 5) / 20.0) * 20).coerceAtLeast(20.0)
        val hi = (Math.ceil((maxBpm + 5) / 20.0) * 20).coerceAtLeast(lo + 40)
        fun y(v: Double) = (topPad + (plotH - topPad) * (1 - (v - lo) / (hi - lo))).toFloat()

        // Y axis: grid lines and bpm labels, unit on top.
        var tick = lo
        while (tick <= hi + 0.1) {
            val yy = y(tick)
            drawLine(Wx.line, Offset(plotL, yy), Offset(size.width, yy), 1f)
            val t = measurer.measure("${tick.toInt()}", axisStyle)
            drawText(t, topLeft = Offset(0f, yy - t.size.height / 2f))
            tick += 20.0
        }
        val unit = measurer.measure("bpm", axisStyle)
        drawText(unit, topLeft = Offset(0f, 0f))

        // Workout bands with their sport name, drawn first so the line sits on top.
        workouts.forEach { w ->
            val x0 = x(w.startTs); val x1 = x(w.endTs).coerceAtLeast(x0 + 3f)
            drawRect(Wx.effort.copy(alpha = 0.22f), Offset(x0, topPad), Size(x1 - x0, plotH - topPad))
            drawLine(Wx.effort, Offset(x0, topPad), Offset(x0, plotH), 1.5f)
            val label = measurer.measure(wxSportName(w.sport), TextStyle(fontSize = 10.sp, color = Wx.effort))
            if (x1 - x0 > 8f || workouts.size < 4) {
                drawText(label, topLeft = Offset((x0 + 3f).coerceAtMost(size.width - label.size.width), topPad))
            }
        }
        // Heart rate line, each stretch coloured by that hour's stress level (grey where the hour was not
        // scored); a gap of more than 20 minutes breaks it.
        fun colorAt(ts: Long): androidx.compose.ui.graphics.Color {
            val h = d.stressHours.lastOrNull { it.startTs <= ts && ts < it.startTs + 3600L }
            return h?.level?.let { Wx.stressColor(it) } ?: Wx.textMuted
        }
        for (i in 1 until d.points.size) {
            val (t0, v0) = d.points[i - 1]; val (t1, v1) = d.points[i]
            if (t1 - t0 > 1200L) continue
            drawLine(colorAt(t1), Offset(x(t0), y(v0)), Offset(x(t1), y(v1)), 1.8.dp.toPx(), StrokeCap.Round)
        }

        // Hourly stress strip.
        val stripTop = plotH + gap
        d.stressHours.forEach { h ->
            val lvl = h.level ?: return@forEach
            val x0 = x(h.startTs); val x1 = x(h.startTs + 3600L)
            drawRoundRect(Wx.stressColor(lvl), Offset(x0 + 1f, stripTop), Size((x1 - x0 - 2f).coerceAtLeast(2f), stripH),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
        // X axis: clock time every 6 hours.
        for (hh in listOf(0, 6, 12, 18, 24)) {
            val t = measurer.measure(String.format(Locale.US, "%02d:00", hh % 24), axisStyle)
            val xx = (plotL + plotW * hh / 24f - t.size.width / 2f).coerceIn(plotL, size.width - t.size.width)
            drawText(t, topLeft = Offset(xx, size.height - t.size.height))
        }

        // Touch readout: nearest 5-minute point, a vertical guide, a dot and "HH:mm · N bpm".
        touchX?.let { tx ->
            val ts = d.from + ((tx - plotL) / plotW * span).toLong()
            val p = d.points.minByOrNull { abs(it.first - ts) } ?: return@let
            if (abs(p.first - ts) > 1800L) return@let
            val px = x(p.first); val py = y(p.second)
            drawLine(Wx.textMuted, Offset(px, topPad), Offset(px, plotH), 1f)
            drawCircle(Wx.heart, 5.dp.toPx(), Offset(px, py))
            drawCircle(Wx.canvas, 2.5.dp.toPx(), Offset(px, py))
            val t = Instant.ofEpochSecond(p.first).atZone(zone)
            val label = measurer.measure(
                String.format(Locale.US, "%02d:%02d · %d bpm", t.hour, t.minute, p.second.roundToInt()),
                TextStyle(fontSize = 12.sp, color = Wx.text),
            )
            val padX = 8.dp.toPx(); val padY = 4.dp.toPx()
            val w = label.size.width + padX * 2; val h = label.size.height + padY * 2
            val bx = (px - w / 2).coerceIn(plotL, size.width - w)
            drawRoundRect(Wx.cardRaised, Offset(bx, 0f), Size(w, h), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawText(label, topLeft = Offset(bx + padX, padY))
        }
    }
}
