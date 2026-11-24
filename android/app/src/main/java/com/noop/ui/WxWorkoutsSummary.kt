package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.R
import com.noop.analytics.TrainingLoadEngine
import com.noop.data.DailyMetric
import com.noop.data.WorkoutRow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Workouts summary (fork)
//
// The top of the Workouts tab: a five-week calendar whose dots grow with the day's hardest session,
// thirty-day totals, and the training-load picture (fitness, fatigue, form) from NOOP's own
// TrainingLoadEngine over the daily Effort. Nothing here is a new score: every number is read from
// stored workouts and days.

/** Five weeks, Monday first, ending with this week; a dot per day sized by its hardest workout. */
@Composable
internal fun WxWorkoutCalendar(
    rows: List<WorkoutRow>,
    scale: EffortScale,
    onOpen: (WorkoutRow) -> Unit,
    /** Planned load per day from intervals.icu, drawn as an open ring on days still to come. */
    planned: Map<String, Double> = emptyMap(),
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    // How many five-week pages back in time; 0 is the page ending with this week.
    var page by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(4L + 5L * page)
    // Next week as an extra row only when something is planned in it (current page only).
    val nextMonday = firstMonday.plusWeeks(5)
    val weeks = if (page == 0 && planned.keys.any { it >= nextMonday.toString() && it < nextMonday.plusWeeks(1).toString() }) 6 else 5
    val byDay = androidx.compose.runtime.remember(rows) { rows.groupBy { Instant.ofEpochSecond(it.startTs).atZone(zone).toLocalDate() } }
    val oldest = androidx.compose.runtime.remember(byDay) { byDay.keys.minOrNull() }
    val canBack = oldest != null && oldest < firstMonday
    WxCard(padding = 16.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                WxLabel(if (page == 0) uiString(R.string.wx_last_5_weeks) else {
                    val f = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                    val end = firstMonday.plusWeeks(5).minusDays(1)
                    "${firstMonday.format(f)} – ${end.format(f)} ${end.year}"
                })
                Text(uiString(R.string.wx_dot_effort), style = WxType.caption.copy(fontSize = 11.sp), color = Wx.textMuted)
            }
            androidx.compose.material3.IconButton(onClick = { page++ }, enabled = canBack) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    uiString(R.string.wx_cal_earlier), tint = if (canBack) Wx.text else Wx.lineStrong)
            }
            androidx.compose.material3.IconButton(onClick = { if (page > 0) page-- }, enabled = page > 0) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    uiString(R.string.wx_cal_later), tint = if (page > 0) Wx.text else Wx.lineStrong)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Text(DayOfWeek.of(i + 1).getDisplayName(JTextStyle.NARROW, Locale.getDefault()),
                    style = WxType.caption.copy(fontSize = 11.sp), color = Wx.textDim,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = firstMonday.plusDays((w * 7 + i).toLong())
                    val sessions = byDay[d].orEmpty()
                    val top = sessions.maxByOrNull { it.strain ?: 0.0 }
                    val e21 = top?.strain?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) }
                    val future = d.isAfter(today)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .then(if (top != null) Modifier.clip(CircleShape).clickable { onOpen(top) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Today: a ring around the cell, whatever it holds.
                        if (d == today) Box(Modifier.matchParentSize().padding(2.dp).border(1.5.dp, Wx.text, CircleShape))
                        when {
                            top != null -> {
                                // Area grows with Effort: 10 dp at the bottom of the scale, 34 dp at 21.
                                val sizeDp = 10 + 24 * kotlin.math.sqrt(((e21 ?: 4.0) / 21.0).coerceIn(0.0, 1.0))
                                Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(
                                    Wx.effort.copy(alpha = if (d == today) 1f else 0.85f)))
                                if (sizeDp > 24) Text(e21?.let { UnitFormatter.effortDisplay(top.strain!!, scale) } ?: "",
                                    style = WxType.caption.copy(fontSize = 10.sp), color = Wx.canvas)
                            }
                            !d.isBefore(today) && planned.containsKey(d.toString()) -> {
                                // An open ring for a planned session; bigger for a heavier plan (load 150 = full).
                                val sizeDp = 12 + 20 * kotlin.math.sqrt(((planned[d.toString()] ?: 0.0) / 150.0).coerceIn(0.05, 1.0))
                                // Dashed: planned, not ridden yet.
                                Canvas(Modifier.size(sizeDp.dp)) {
                                    drawCircle(Wx.effort.copy(alpha = 0.8f), radius = size.minDimension / 2 - 1.dp.toPx(),
                                        style = Stroke(1.8.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
                                }
                            }
                            else -> Box(Modifier.size(5.dp).clip(CircleShape).background(
                                if (future) Wx.cardRaised else if (d == today) Wx.textSoft else Wx.lineStrong))
                        }
                    }
                }
            }
        }
        if (planned.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(uiString(R.string.wx_cal_legend), style = WxType.caption.copy(fontSize = 11.sp), color = Wx.textMuted)
        }
    }
}

/** Thirty-day totals across every workout. */
@Composable
internal fun WxWorkoutTotals(rows: List<WorkoutRow>, scale: EffortScale) {
    val since = System.currentTimeMillis() / 1000L - 30 * 86_400L
    val month = rows.filter { it.startTs >= since }
    val secs = month.sumOf { it.durationS ?: (it.endTs - it.startTs).toDouble() }
    val kcal = month.mapNotNull { it.energyKcal }.sum()
    val hardest = month.maxByOrNull { it.strain ?: 0.0 }
    WxCard {
        WxCardHeader(uiString(R.string.wx_last_30_days))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            WxStat(uiString(R.string.wx_sessions), "${month.size}", Modifier.weight(1f))
            WxStat(uiString(R.string.wx_duration), wxHoursMinutes(secs / 60.0), Modifier.weight(1f))
            WxStat(uiString(R.string.wx_calories), if (kcal > 0) String.format(Locale.getDefault(), "%,d", kcal.roundToInt()) else "–", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            WxStat(uiString(R.string.wx_per_week), String.format(Locale.US, "%.1f", month.size / (30.0 / 7.0)), Modifier.weight(1f))
            WxStat(uiString(R.string.wx_hardest), hardest?.strain?.let { UnitFormatter.effortDisplay(it, scale) } ?: "–", Modifier.weight(1f))
            WxStat(uiString(R.string.wx_top_sport), month.groupingBy { it.sport }.eachCount().maxByOrNull { it.value }?.key?.let { wxSportName(it) } ?: "–", Modifier.weight(1f))
        }
    }
}

/**
 * Fitness, fatigue and form over the last 90 days: NOOP's TrainingLoadEngine (42- and 7-day
 * exponential averages of the daily Effort, and their difference) drawn as two lines over form bars.
 * The engine models only the unbroken run of days ending today; a gap shows the reason instead.
 */
@Composable
internal fun WxTrainingLoadCard(days: List<DailyMetric>) {
    val result = androidx.compose.runtime.remember(days) {
        val loads = days.map { TrainingLoadEngine.DailyLoad(it.day, it.strain) }
        // Through today when today is scored; otherwise through the latest scored day.
        TrainingLoadEngine.evaluate(loads, through = LocalDate.now().toString()).takeIf { it.isAvailable }
            ?: TrainingLoadEngine.evaluate(loads.filter { it.load != null })
    }
    WxCard(padding = 16.dp) {
        WxCardHeader(uiString(R.string.wx_training_load))
        Spacer(Modifier.height(10.dp))
        if (!result.isAvailable || result.points.size < 2) {
            Text(uiString(R.string.wx_training_load_unavailable), style = WxType.caption, color = Wx.textMuted)
            return@WxCard
        }
        val pts = result.points.takeLast(90)
        val last = pts.last()
        Row(Modifier.fillMaxWidth()) {
            WxLoadStat(uiString(R.string.wx_fitness), last.ctl, Wx.effort, Modifier.weight(1f))
            WxLoadStat(uiString(R.string.wx_fatigue), last.atl, Wx.heart, Modifier.weight(1f))
            WxLoadStat(uiString(R.string.wx_form), last.tsb, if (last.tsb >= 0) Wx.recoveryHigh else Wx.recoveryMid, Modifier.weight(1f), signed = true)
        }
        Spacer(Modifier.height(12.dp))
        val measurer = rememberTextMeasurer()
        val a11y = uiString(R.string.wx_training_load)
        Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = a11y }) {
            val axisH = 16.dp.toPx()
            val lineH = (size.height - axisH) * 0.68f
            val barTop = lineH + 6.dp.toPx(); val barH = size.height - axisH - barTop
            val n = pts.size
            fun x(i: Int) = size.width * i / (n - 1).coerceAtLeast(1)
            val hi = pts.maxOf { maxOf(it.ctl, it.atl) }.coerceAtLeast(1.0) * 1.1
            fun y(v: Double) = (lineH - lineH * (v / hi)).toFloat()
            for (k in 1..3) drawLine(Wx.line, Offset(0f, lineH * k / 4f), Offset(size.width, lineH * k / 4f), 1f)
            fun line(get: (TrainingLoadEngine.Point) -> Double, c: androidx.compose.ui.graphics.Color) {
                val p = Path()
                pts.forEachIndexed { i, pt -> if (i == 0) p.moveTo(x(i), y(get(pt))) else p.lineTo(x(i), y(get(pt))) }
                drawPath(p, c, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            line({ it.atl }, Wx.heart.copy(alpha = 0.85f))
            line({ it.ctl }, Wx.effort)
            // Form bars around a zero line.
            val tsbMax = pts.maxOf { kotlin.math.abs(it.tsb) }.coerceAtLeast(1.0)
            val mid = barTop + barH / 2
            drawLine(Wx.lineStrong, Offset(0f, mid), Offset(size.width, mid), 1f)
            val bw = (size.width / n * 0.7f).coerceAtLeast(1f)
            pts.forEachIndexed { i, pt ->
                val h = (barH / 2 * (pt.tsb / tsbMax)).toFloat()
                val c = if (pt.tsb >= 0) Wx.recoveryHigh else Wx.recoveryMid
                drawRect(c.copy(alpha = 0.8f), Offset(x(i) - bw / 2, if (h >= 0) mid - h else mid), Size(bw, kotlin.math.abs(h)))
            }
            val style = TextStyle(fontSize = 10.sp, color = Wx.textDim)
            listOf(0, n / 2, n - 1).forEach { i ->
                val d = LocalDate.parse(pts[i].day)
                val t = measurer.measure(wxShortDate(d), style)
                drawText(t, topLeft = Offset((x(i) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(uiString(R.string.wx_training_load_note), style = WxType.caption, color = Wx.textMuted)
    }
}

@Composable
private fun WxLoadStat(label: String, v: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier, signed: Boolean = false) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            WxLabel(label)
        }
        Text((if (signed && v > 0) "+" else "") + v.roundToInt().toString(), style = WxType.numberSmall, color = Wx.text)
    }
}
