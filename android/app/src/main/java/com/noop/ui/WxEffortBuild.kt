package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.data.WorkoutRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

// MARK: - Effort built through the day (fork)
//
// The day's Effort as it accumulated: the fitted curve applied to the running load, from the same
// heart rate, resting HR and max HR the day was scored with. The curve's shape comes from the data and
// its end is pinned to the stored Effort, so the chart and the headline number can never disagree.
// "Rest of the day" is what the hours outside workouts added on top of the workouts' combined load.

internal data class WxEffortBuild(
    /** (timestamp, Effort on the stored 0–100 axis) every 5 minutes. */
    val points: List<Pair<Long, Double>>,
    val from: Long,
    val to: Long,
    /** Effort the day would have from its workouts alone, and what the rest of the day added. */
    val workoutsOnly: Double?,
    val restOfDay: Double?,
)

internal suspend fun wxEffortBuild(
    vm: AppViewModel,
    date: LocalDate,
    row: DailyMetric,
    workouts: List<WorkoutRow>,
    hrMax: Double,
): WxEffortBuild? = withContext(Dispatchers.Default) {
    runCatching {
        val stored = row.strain ?: return@runCatching null
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toEpochSecond()
        val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, from, minOf(end, System.currentTimeMillis() / 1000L), 200_000)
            .sortedBy { it.ts }
        if (hr.size < StrainScorer.minReadings) return@runCatching null
        val rest = row.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR
        val reserve = hrMax - rest
        if (reserve <= 0) return@runCatching null
        val dur = StrainScorer.sampleDurationsMinutes(hr)
        val weights = hr.indices.map { StrainScorer.fittedWeight(StrainScorer.pctHRR(hr[it].bpm.toDouble(), rest, reserve)) * dur[it] }
        val total = weights.sum()
        val raw = StrainScorer.fittedEffort(total)
        if (raw <= 0.0) return@runCatching null
        // Pin the end of the curve to the stored Effort.
        val k = stored / raw
        val points = ArrayList<Pair<Long, Double>>()
        var acc = 0.0
        var nextMark = from
        for (i in hr.indices) {
            acc += weights[i]
            if (hr[i].ts >= nextMark) {
                points += hr[i].ts to StrainScorer.fittedEffort(acc) * k
                nextMark = hr[i].ts + 300L
            }
        }
        points += hr.last().ts to stored
        val inWorkout = workouts.takeIf { it.isNotEmpty() }?.let { ws ->
            hr.indices.filter { i -> ws.any { hr[i].ts >= it.startTs && hr[i].ts < it.endTs } }.sumOf { weights[it] }
        }
        val workoutsOnly = inWorkout?.let { StrainScorer.fittedEffort(it) * k }
        WxEffortBuild(points, from, end, workoutsOnly, workoutsOnly?.let { (stored - it).coerceAtLeast(0.0) })
    }.getOrNull()
}

@Composable
internal fun WxEffortBuildCard(vm: AppViewModel, date: LocalDate, row: DailyMetric?, workouts: List<WorkoutRow>, scale: EffortScale,
                               onBuild: (WxEffortBuild?) -> Unit = {}) {
    val context = LocalContext.current
    val hrMax = remember {
        val p = ProfileStore.from(context)
        StrainScorer.effortHRmax(p.hrMaxOverride.takeIf { it > 0 }?.toDouble(), p.age.toDouble()).first
            ?: StrainScorer.defaultMaxHR().toDouble()
    }
    val fitted = remember { NoopPrefs.fittedEffort(context) }
    var build by remember(date, row?.strain) { mutableStateOf<WxEffortBuild?>(null) }
    LaunchedEffect(date, row?.strain, workouts.size) {
        build = if (row == null || !fitted) null else wxEffortBuild(vm, date, row, workouts, hrMax)
        onBuild(build)
    }
    val b = build ?: return
    val measurer = rememberTextMeasurer()
    WxCard(padding = 16.dp) {
        WxCardHeader(uiString(R.string.wx_effort_built), uiString(R.string.wx_effort_built_span))
        Spacer(Modifier.height(12.dp))
        val color = Wx.effort
        val a11y = uiString(R.string.wx_effort_built)
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = a11y }) {
            val axisH = 16.dp.toPx(); val top = 14.dp.toPx()
            val plotB = size.height - axisH
            val span = (b.to - b.from).toFloat()
            val maxV = (b.points.maxOf { it.second }).coerceAtLeast(1.0) * 1.1
            fun x(ts: Long) = ((ts - b.from) / span * size.width).coerceIn(0f, size.width)
            fun y(v: Double) = (plotB - (plotB - top) * (v / maxV)).toFloat()
            // Workouts shaded behind, with their sport name.
            workouts.forEach { w ->
                val x0 = x(w.startTs); val x1 = x(w.endTs).coerceAtLeast(x0 + 3f)
                drawRect(color.copy(alpha = 0.10f), Offset(x0, 0f), Size(x1 - x0, plotB))
                val t = measurer.measure(wxSportName(w.sport), TextStyle(fontSize = 10.sp, color = Wx.textMuted))
                drawText(t, topLeft = Offset(((x0 + x1) / 2 - t.size.width / 2).coerceIn(0f, size.width - t.size.width), 0f))
            }
            val line = Path(); val fill = Path()
            b.points.forEachIndexed { i, (ts, v) ->
                if (i == 0) { line.moveTo(x(ts), y(v)); fill.moveTo(x(ts), plotB); fill.lineTo(x(ts), y(v)) }
                else { line.lineTo(x(ts), y(v)); fill.lineTo(x(ts), y(v)) }
            }
            val last = b.points.last()
            fill.lineTo(x(last.first), plotB); fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), top, plotB))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(color, 4.dp.toPx(), Offset(x(last.first), y(last.second)))
            val v = measurer.measure(UnitFormatter.effortDisplay(last.second, scale), TextStyle(fontSize = 12.sp, color = Wx.text))
            drawText(v, topLeft = Offset((x(last.first) - v.size.width - 6.dp.toPx()).coerceAtLeast(0f), (y(last.second) - v.size.height - 4.dp.toPx()).coerceAtLeast(0f)))
            for (hh in listOf(0, 6, 12, 18)) {
                val t = measurer.measure("${hh}h", TextStyle(fontSize = 10.sp, color = Wx.textDim))
                drawText(t, topLeft = Offset(size.width * hh / 24f, size.height - t.size.height))
            }
        }
    }
}
