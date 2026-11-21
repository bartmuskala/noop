package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.RestScorer
import com.noop.data.DailyMetric
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Wx Trends
//
// Bars, not lines: a period headline (average + change vs the period before), the share of green /
// yellow / red days for Recovery, the average per week (or per month for long periods), the average
// per weekday, and every other metric's change over the same period. One metric at a time, chosen
// with chips; Today's tiles open this screen with their own metric selected.

internal enum class WxTrendMetric(val route: String) {
    Recovery("recovery"), Effort("effort"), Sleep("sleep"), Hrv("hrv"), RestingHr("rhr"),
    Respiratory("resp"), SkinTemp("skin"), Steps("steps"), Calories("kcal");
    companion object { fun fromRoute(r: String?) = entries.firstOrNull { it.route == r } ?: Recovery }
}

private val WX_PERIODS = listOf(7, 30, 90, 180, 365)

@Composable
internal fun WxTrendsScreen(vm: AppViewModel, initial: WxTrendMetric = WxTrendMetric.Recovery) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val scale = remember { UnitPrefs.effortScale(context) }
    var metric by remember { mutableStateOf(initial) }
    var periodIx by remember { mutableStateOf(1) }
    // 0 = per day, 1 = per week, 2 = per month. Follows the period until the user picks one.
    var grain by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(periodIx) { grain = when (periodIx) { 0, 1 -> 0; 2 -> 1; else -> 2 } }
    // 0 = line, 1 = bars; remembered across visits.
    var chartType by remember { mutableStateOf(WxTrendPrefs.readChart(context)) }
    val period = WX_PERIODS[periodIx]

    val today = LocalDate.now()
    val start = today.minusDays(period.toLong() - 1)
    val prevStart = start.minusDays(period.toLong())
    val byDay = days.associateBy { it.day }
    fun valueOf(m: WxTrendMetric, d: DailyMetric): Double? = wxMetricValue(m, d, scale)
    fun windowValues(m: WxTrendMetric, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .mapNotNull { d -> byDay[d.toString()]?.let { valueOf(m, it) }?.let { d to it } }.toList()

    val current = windowValues(metric, start, today)
    val previous = windowValues(metric, prevStart, start.minusDays(1))
    val avg = current.map { it.second }.takeIf { it.isNotEmpty() }?.average()
    val prevAvg = previous.map { it.second }.takeIf { it.isNotEmpty() }?.average()

    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.canvas)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = BottomBarStyleStore.barHeightForContent() + 24.dp),
    ) {
        Text(uiString(R.string.nav_trends).uppercase(), style = WxType.title.copy(fontSize = 28.sp), color = Wx.text,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp))
        Box(Modifier.padding(horizontal = Wx.gutter)) {
            WxSegmented(listOf("1W", "1M", "3M", "6M", "1Y"), periodIx, { periodIx = it })
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Wx.gutter, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WxTrendMetric.entries.forEach { m ->
                val on = m == metric
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .then(if (on) Modifier.background(wxMetricColor(m, avg)) else Modifier.border(1.dp, Wx.lineStrong, RoundedCornerShape(18.dp)))
                        .clickable { metric = m }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(wxMetricName(m), style = WxType.bodyStrong.copy(fontSize = 13.sp), color = if (on) Wx.canvas else Wx.textSoft)
                }
            }
        }

        Column(Modifier.padding(horizontal = Wx.gutter), verticalArrangement = Arrangement.spacedBy(Wx.gap)) {
            // Headline.
            WxCard {
                WxLabel(uiString(R.string.wx_average_over, wxMetricName(metric), wxPeriodName(period)))
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                    Text(avg?.let { wxMetricFormat(metric, it, scale) } ?: "–", style = WxType.hero.copy(fontSize = 56.sp), color = Wx.text)
                    wxMetricUnit(metric)?.let { Text(" $it", style = WxType.numberSmall, color = Wx.textMuted, modifier = Modifier.padding(bottom = 10.dp)) }
                    Spacer(Modifier.width(10.dp))
                    if (avg != null && prevAvg != null) {
                        val d = avg - prevAvg
                        val good = wxHigherIsBetter(metric)?.let { hb -> if (abs(d) < 1e-6) null else (d > 0) == hb }
                        Text(
                            (if (d >= 0) "▲ " else "▼ ") + wxMetricFormat(metric, abs(d), scale) + " " + uiString(R.string.wx_vs_previous),
                            style = WxType.bodyStrong.copy(fontSize = 14.sp),
                            color = when (good) { true -> Wx.recoveryHigh; false -> Wx.recoveryMid; null -> Wx.textMuted },
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                if (metric == WxTrendMetric.Recovery && current.isNotEmpty()) {
                    val g = current.count { it.second >= 67 }
                    val y = current.count { it.second in 34.0..66.999 }
                    val r = current.size - g - y
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                        if (g > 0) Box(Modifier.weight(g.toFloat()).fillMaxHeight().background(Wx.recoveryHigh))
                        if (y > 0) Box(Modifier.weight(y.toFloat()).fillMaxHeight().background(Wx.recoveryMid))
                        if (r > 0) Box(Modifier.weight(r.toFloat()).fillMaxHeight().background(Wx.recoveryLow))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(uiString(R.string.wx_green_days, g), style = WxType.caption, color = Wx.textMuted)
                        Text(uiString(R.string.wx_yellow_days, y), style = WxType.caption, color = Wx.textMuted)
                        Text(uiString(R.string.wx_red_days, r), style = WxType.caption, color = Wx.textMuted)
                    }
                }
            }

            // Over time: per day, week or month (chosen independently of the period), as a line or bars.
            val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            val buckets: List<Pair<String, Double?>> = when (grain) {
                0 -> generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
                    (if (period <= 7) wxWeekdayShort(d) else d.format(dayFmt)) to byDay[d.toString()]?.let { valueOf(metric, it) }
                }.toList()
                1 -> {
                    val weekStart = start.with(DayOfWeek.MONDAY)
                    val weeks = (ChronoUnit.WEEKS.between(weekStart, today) + 1).toInt()
                    (0 until weeks).map { w ->
                        val ws = weekStart.plusWeeks(w.toLong())
                        ws.format(dayFmt) to
                            current.filter { !it.first.isBefore(ws) && it.first.isBefore(ws.plusWeeks(1)) }
                                .map { it.second }.takeIf { it.isNotEmpty() }?.average()
                    }
                }
                else -> {
                    val ms = start.withDayOfMonth(1)
                    val months = (ChronoUnit.MONTHS.between(ms, today.withDayOfMonth(1)) + 1).toInt()
                    (0 until months).map { k ->
                        val m0 = ms.plusMonths(k.toLong())
                        m0.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())) to
                            current.filter { it.first.year == m0.year && it.first.month == m0.month }
                                .map { it.second }.takeIf { it.isNotEmpty() }?.average()
                    }
                }
            }
            val observed = buckets.mapNotNull { it.second } + listOfNotNull(avg)
            val maxV = wxMetricMax(metric, scale, observed.maxOrNull())
            val minV = when (metric) {
                WxTrendMetric.Recovery, WxTrendMetric.Effort -> 0.0
                else -> observed.minOrNull()?.let { lo -> lo - (maxV - lo) * 0.15 } ?: 0.0
            }
            WxCard {
                WxLabel(uiString(R.string.wx_over_time))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(3f)) {
                        WxSegmented(listOf(uiString(R.string.wx_day), uiString(R.string.wx_week), uiString(R.string.wx_month)), grain, { grain = it })
                    }
                    Box(Modifier.weight(2f)) {
                        WxSegmented(listOf(uiString(R.string.wx_line), uiString(R.string.wx_bars)), chartType, {
                            chartType = it; WxTrendPrefs.writeChart(context, it)
                        })
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (chartType == 0) {
                    WxLineChart(
                        values = buckets.map { it.second }, labels = buckets.map { it.first },
                        colorFor = { wxMetricColor(metric, it) }, format = { wxMetricFormat(metric, it, scale) },
                        min = minV, max = maxV, averageLine = avg,
                        description = uiString(R.string.wx_bars_a11y, wxMetricName(metric)), height = 180.dp,
                    )
                } else {
                    WxBars(
                        values = buckets.map { it.second }, labels = buckets.map { it.first },
                        colorFor = { wxMetricColor(metric, it) }, format = { wxMetricFormat(metric, it, scale) },
                        max = maxV, averageLine = avg,
                        description = uiString(R.string.wx_bars_a11y, wxMetricName(metric)), height = 180.dp,
                    )
                }
            }

            // By weekday.
            val weekdayAvgs = DayOfWeek.entries.map { dow ->
                current.filter { it.first.dayOfWeek == dow }.map { it.second }.takeIf { it.isNotEmpty() }?.average()
            }
            val best = weekdayAvgs.withIndex().filter { it.value != null }.let { xs ->
                val hb = wxHigherIsBetter(metric) ?: true
                if (hb) xs.maxByOrNull { it.value!! } else xs.minByOrNull { it.value!! }
            }
            WxCard {
                WxCardHeader(uiString(R.string.wx_by_weekday),
                    best?.let { uiString(R.string.wx_best_day, wxWeekdayLong(today.with(DayOfWeek.entries[it.index]))) })
                Spacer(Modifier.height(12.dp))
                WxBars(
                    values = weekdayAvgs, labels = DayOfWeek.entries.map { wxWeekdayShort(today.with(it)) },
                    colorFor = { wxMetricColor(metric, it) }, format = { wxMetricFormat(metric, it, scale) },
                    max = maxV, description = uiString(R.string.wx_weekday_a11y, wxMetricName(metric)), height = 130.dp,
                )
            }

            // Every other metric, same period, vs the period before.
            WxCard(padding = 16.dp) {
                WxLabel(uiString(R.string.wx_compared_to_previous, wxPeriodName(period)), Modifier.padding(bottom = 4.dp))
                WxTrendMetric.entries.filter { it != metric }.forEach { m ->
                    val cur = windowValues(m, start, today).map { it.second }.takeIf { it.isNotEmpty() }?.average()
                    val prev = windowValues(m, prevStart, start.minusDays(1)).map { it.second }.takeIf { it.isNotEmpty() }?.average()
                    val d = if (cur != null && prev != null) cur - prev else null
                    val good = if (d == null) null else wxHigherIsBetter(m)?.let { hb -> if (abs(d) < 1e-6) null else (d > 0) == hb }
                    Column(Modifier.clickable { metric = m }) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(wxMetricName(m), style = WxType.bodyStrong.copy(fontSize = 14.sp), color = Wx.text, modifier = Modifier.weight(1f))
                            Text((cur?.let { wxMetricFormat(m, it, scale) } ?: "–") + (wxMetricUnit(m)?.let { " $it" } ?: ""),
                                style = WxType.numberSmall, color = Wx.text)
                            Text(
                                d?.let { (if (it >= 0) "▲ " else "▼ ") + wxMetricFormat(m, abs(it), scale) } ?: "",
                                style = WxType.caption.copy(fontSize = 13.sp),
                                color = when (good) { true -> Wx.recoveryHigh; false -> Wx.recoveryMid; null -> Wx.textMuted },
                                modifier = Modifier.width(70.dp).padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun wxMetricValue(m: WxTrendMetric, d: DailyMetric, scale: EffortScale): Double? = when (m) {
    WxTrendMetric.Recovery -> d.recovery
    WxTrendMetric.Effort -> d.strain?.let { UnitFormatter.effortValue(it, scale) }
    WxTrendMetric.Sleep -> d.totalSleepMin?.div(60.0)
    WxTrendMetric.Hrv -> d.avgHrv
    WxTrendMetric.RestingHr -> d.restingHr?.toDouble()
    WxTrendMetric.Respiratory -> d.respRateBpm
    WxTrendMetric.SkinTemp -> d.skinTempDevC
    WxTrendMetric.Steps -> d.steps?.toDouble()
    WxTrendMetric.Calories -> d.activeKcalEst
}

@Composable
internal fun wxMetricName(m: WxTrendMetric): String = uiString(when (m) {
    WxTrendMetric.Recovery -> R.string.wx_recovery
    WxTrendMetric.Effort -> R.string.wx_effort
    WxTrendMetric.Sleep -> R.string.wx_hours_of_sleep
    WxTrendMetric.Hrv -> R.string.wx_hrv
    WxTrendMetric.RestingHr -> R.string.wx_resting_hr
    WxTrendMetric.Respiratory -> R.string.wx_resp_rate
    WxTrendMetric.SkinTemp -> R.string.wx_skin_temp
    WxTrendMetric.Steps -> R.string.wx_steps
    WxTrendMetric.Calories -> R.string.wx_calories
})

private fun wxMetricUnit(m: WxTrendMetric): String? = when (m) {
    WxTrendMetric.Recovery -> "%"
    WxTrendMetric.Hrv -> "ms"
    WxTrendMetric.RestingHr -> "bpm"
    WxTrendMetric.Respiratory -> "rpm"
    WxTrendMetric.SkinTemp -> "°C"
    WxTrendMetric.Calories -> "kcal"
    WxTrendMetric.Sleep -> "h"
    else -> null
}

private fun wxHigherIsBetter(m: WxTrendMetric): Boolean? = when (m) {
    WxTrendMetric.Recovery, WxTrendMetric.Hrv, WxTrendMetric.Sleep, WxTrendMetric.Steps -> true
    WxTrendMetric.RestingHr -> false
    else -> null
}

private fun wxMetricFormat(m: WxTrendMetric, v: Double, scale: EffortScale): String = when (m) {
    WxTrendMetric.Effort, WxTrendMetric.Respiratory, WxTrendMetric.SkinTemp -> String.format(Locale.US, "%.1f", v)
    WxTrendMetric.Sleep -> wxHoursMinutes(v * 60)
    WxTrendMetric.Steps, WxTrendMetric.Calories -> String.format(Locale.US, "%,d", v.roundToInt())
    else -> "${v.roundToInt()}"
}

private fun wxMetricMax(m: WxTrendMetric, scale: EffortScale, observed: Double?): Double = when (m) {
    WxTrendMetric.Recovery -> 100.0
    WxTrendMetric.Effort -> UnitFormatter.effortValue(100.0, scale)
    else -> (observed ?: 1.0) * 1.15
}

private fun wxMetricColor(m: WxTrendMetric, v: Double?): Color = when (m) {
    WxTrendMetric.Recovery -> Wx.recoveryColor(v)
    WxTrendMetric.Effort -> Wx.effort
    WxTrendMetric.Sleep -> Wx.sleep
    WxTrendMetric.Hrv, WxTrendMetric.Respiratory -> Wx.recoveryHigh
    WxTrendMetric.RestingHr -> Wx.heart
    WxTrendMetric.SkinTemp -> Wx.stressHigh
    WxTrendMetric.Steps, WxTrendMetric.Calories -> Wx.effort
}

@Composable
private fun wxPeriodName(days: Int): String = uiString(when (days) {
    7 -> R.string.wx_period_week
    30 -> R.string.wx_period_month
    90 -> R.string.wx_period_3_months
    180 -> R.string.wx_period_6_months
    else -> R.string.wx_period_year
})


/** The Trends chart style, kept per install. */
internal object WxTrendPrefs {
    private const val KEY = "wx.trends.chart"
    fun readChart(ctx: android.content.Context): Int = NoopPrefs.of(ctx).getInt(KEY, 0).coerceIn(0, 1)
    fun writeChart(ctx: android.content.Context, v: Int) { NoopPrefs.of(ctx).edit().putInt(KEY, v).apply() }
}
