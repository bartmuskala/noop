package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.MetricSeriesRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// MARK: - Wx Fitness Age
//
// The weekly Fitness Age NOOP already computes, shown the WHOOP way: the value inside a glowing shape,
// how many years it sits under or over the calendar age, the change since the previous week, and its
// history. VO2max (the estimate the same engine stores) sits below. When there is no value yet, the
// page says so and links to the full section that explains what is still missing.

@Composable
internal fun WxFitnessAgeScreen(vm: AppViewModel, onBack: () -> Unit, onOpenDetails: () -> Unit) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val profile = remember { ProfileStore.from(context) }
    var series by remember { mutableStateOf<List<MetricSeriesRow>?>(null) }
    var vo2 by remember { mutableStateOf<List<MetricSeriesRow>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(days, reload) {
        series = runCatching {
            vm.repo.metricSeriesComputedUnion(vm.activeStrapId, "fitness_age", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList()).sortedBy { it.day }
        vo2 = runCatching {
            vm.repo.metricSeriesComputedUnion(vm.activeStrapId, "vo2max_est", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList()).sortedBy { it.day }
    }
    val latest = series?.lastOrNull()
    val prev = series?.dropLast(1)?.lastOrNull()
    // Calendar age to the day, from the date of birth the profile keeps.
    val calendarAge = (System.currentTimeMillis() - profile.dateOfBirthMillis) / (365.2425 * 86_400_000.0)
    val diff = latest?.let { calendarAge - it.value }

    Box(Modifier.fillMaxSize().background(Wx.canvas)) {
        // A green glow across the top, as behind the shape.
        Box(
            Modifier.fillMaxWidth().height(420.dp)
                .background(Brush.verticalGradient(listOf(Wx.recoveryHigh.copy(alpha = 0.22f), Color.Transparent))),
        )
        WxPage(title = uiString(R.string.today_card_fitness_age), onBack = onBack, background = Color.Transparent) {
            Box(Modifier.fillMaxWidth().height(330.dp), contentAlignment = Alignment.Center) {
                WxAgeBlob(Modifier.size(300.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(latest?.let { String.format(Locale.getDefault(), "%.1f", it.value) } ?: "–",
                        style = WxType.hero.copy(fontSize = 64.sp), color = Wx.text)
                    Text(uiString(R.string.today_card_fitness_age).uppercase(), style = WxType.title.copy(fontSize = 18.sp),
                        color = Wx.textSoft)
                    if (diff != null) {
                        Text(
                            uiString(if (diff >= 0) R.string.wx_years_younger else R.string.wx_years_older,
                                String.format(Locale.getDefault(), "%.1f", abs(diff))),
                            style = WxType.bodyStrong.copy(fontSize = 17.sp),
                            color = if (diff >= 0) Wx.recoveryHigh else Wx.recoveryMid,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            if (series != null && latest == null) {
                WxCard {
                    Text(uiString(R.string.wx_fitness_age_not_ready), style = WxType.body, color = Wx.textSoft)
                }
            }

            // This week against the last: the same weekly values, one step apart.
            WxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WxLabel(uiString(R.string.wx_vs_last_week), Modifier.weight(1f), color = Wx.text)
                    val change = if (latest != null && prev != null) latest.value - prev.value else null
                    if (change != null) {
                        val better = change < 0
                        Text(
                            (if (change > 0) "▲ +" else if (change < 0) "▼ " else "") + String.format(Locale.getDefault(), "%.1f", change),
                            style = WxType.bodyStrong.copy(fontSize = 14.sp),
                            color = if (abs(change) < 0.05) Wx.textMuted else if (better) Wx.recoveryHigh else Wx.recoveryMid,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(Wx.cardRaised).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    WxStat(uiString(R.string.wx_calendar_age), String.format(Locale.getDefault(), "%.1f", calendarAge), Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_this_week), latest?.let { String.format(Locale.getDefault(), "%.1f", it.value) } ?: "–", Modifier.weight(1f))
                    WxStat(uiString(R.string.wx_last_week), prev?.let { String.format(Locale.getDefault(), "%.1f", it.value) } ?: "–", Modifier.weight(1f))
                }
            }

            val hist = series.orEmpty().takeLast(26)
            if (hist.size >= 2) {
                val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                WxCard {
                    WxCardHeader(uiString(R.string.wx_history), uiString(R.string.wx_weekly))
                    Spacer(Modifier.height(10.dp))
                    WxTrendChart(
                        values = hist.map { it.value },
                        labels = hist.map { runCatching { LocalDate.parse(it.day).format(fmt) }.getOrDefault(it.day) },
                        color = Wx.recoveryHigh,
                        format = { String.format(Locale.getDefault(), "%.1f", it) },
                        typical = null,
                        description = uiString(R.string.today_card_fitness_age),
                        height = 220.dp,
                    )
                }
            }

            val v = vo2.lastOrNull()
            if (v != null) {
                WxCard {
                    WxCardHeader(uiString(R.string.wx_vo2max), "ml/kg/min")
                    Text(String.format(Locale.getDefault(), "%.0f", v.value), style = WxType.hero.copy(fontSize = 44.sp), color = Wx.text,
                        modifier = Modifier.padding(top = 4.dp))
                    val vh = vo2.takeLast(26)
                    if (vh.size >= 2) {
                        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                        WxTrendChart(
                            values = vh.map { it.value },
                            labels = vh.map { runCatching { LocalDate.parse(it.day).format(fmt) }.getOrDefault(it.day) },
                            color = Wx.effort,
                            format = { String.format(Locale.getDefault(), "%.0f", it) },
                            typical = null,
                            description = uiString(R.string.wx_vo2max),
                            height = 180.dp,
                        )
                    }
                }
            }
            WxButton(uiString(R.string.wx_how_its_calculated), onClick = onOpenDetails, filled = false)
            WxWhoopCalibrationCard(vm, onDone = { reload++ })
        }
    }
}

/** A soft, irregular green shape with a dark centre and a scatter of points, drawn the same every time. */
@Composable
private fun WxAgeBlob(modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 * 0.86f
        val path = Path()
        val steps = 120
        for (i in 0..steps) {
            val t = 2 * PI * i / steps
            val rr = r * (1 + 0.06 * sin(3 * t + 0.6) + 0.04 * cos(5 * t) + 0.02 * sin(7 * t + 1.1))
            val p = Offset((c.x + rr * cos(t)).toFloat(), (c.y + rr * sin(t)).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        // Outer glow.
        drawCircle(Brush.radialGradient(listOf(Wx.recoveryHigh.copy(alpha = 0.25f), Color.Transparent), c, r * 1.3f), r * 1.3f, c)
        drawPath(path, Brush.radialGradient(
            0.0f to Color(0xFF050807), 0.55f to Color(0xFF07140D), 1.0f to Wx.recoveryHigh.copy(alpha = 0.75f),
            center = c, radius = r * 1.08f,
        ))
        drawPath(path, Wx.recoveryHigh.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
        // Deterministic scatter of small points.
        for (i in 0 until 70) {
            val a = (i * 137.508) * PI / 180
            val d = r * 0.85 * Math.sqrt(((i * 53) % 97) / 97.0)
            val p = Offset((c.x + d * cos(a)).toFloat(), (c.y + d * sin(a)).toFloat())
            drawCircle(Wx.recoveryHigh.copy(alpha = 0.35f + (i % 5) * 0.1f), (1 + i % 3).dp.toPx() * 0.8f, p)
        }
    }
}

/**
 * Fork: calibrate the level of Fitness Age and VO2max to the wearer's WHOOP weeks. One line per week,
 * as WHOOP shows it ("05/7 - 11/7: 38,5 en 53"). Kept on the phone only.
 */
@Composable
private fun WxWhoopCalibrationCard(vm: AppViewModel, onDone: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by remember { mutableStateOf(vm.fitnessWhoopRefs()) }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    WxCard {
        WxCardHeader(uiString(R.string.wx_fa_calibrate))
        Spacer(Modifier.height(6.dp))
        Text(uiString(R.string.wx_fa_calibrate_intro), style = WxType.caption, color = Wx.textMuted)
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.OutlinedTextField(
            value = text, onValueChange = { text = it }, minLines = 3,
            placeholder = { Text("05/7 - 11/7: 38,5 en 53", color = Wx.textDim) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Wx.text, unfocusedTextColor = Wx.text,
                focusedBorderColor = Wx.effort, unfocusedBorderColor = Wx.lineStrong, cursorColor = Wx.effort),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        WxButton(uiString(if (running) R.string.wx_icu_syncing else R.string.wx_fa_calibrate_run), onClick = {
            if (running || text.isBlank()) return@WxButton
            running = true
            scope.launch {
                result = runCatching { vm.calibrateFitnessToWhoop(text) }.getOrElse { it.message ?: it.javaClass.simpleName }
                running = false
                onDone()
            }
        })
        result?.let { Spacer(Modifier.height(8.dp)); Text(it, style = WxType.caption, color = Wx.textSoft) }
    }
}
