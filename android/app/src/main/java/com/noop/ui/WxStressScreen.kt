package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import com.noop.analytics.DaytimeStress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// MARK: - Wx Stress
//
// Fast first paint: the stored daily level and the last seven days show at once, from one small
// metric read. The intraday curve is computed after that, on a background thread, with the same
// day-relative DaytimeStress read the Today card uses (hourly, no sliding re-read, no frequency-domain
// lenses), so the screen never waits on the heavy part. The curve is one smooth line over three soft
// level bands, without per-point markers.

@Composable
internal fun WxStressScreen(vm: AppViewModel, onBack: () -> Unit, onBreathe: () -> Unit) {
    val today = LocalDate.now()
    var daily by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var hours by remember { mutableStateOf<List<DaytimeStress.HourPoint>?>(null) }
    LaunchedEffect(Unit) {
        val from = today.minusDays(6).toString()
        daily = runCatching { vm.repo.metricSeries("my-whoop", "stress", from, today.toString()) }
            .getOrDefault(emptyList()).associate { it.day to it.value.coerceIn(0.0, 3.0) }
    }
    LaunchedEffect(Unit) {
        hours = withContext(Dispatchers.Default) {
            runCatching {
                val now = System.currentTimeMillis() / 1000L
                val zone = ZoneId.systemDefault()
                val from = stressLocalDayWindowContaining(now, zone).fromEpochSecond
                val tz = zone.rules.getOffset(Instant.ofEpochSecond(now)).totalSeconds.toLong()
                val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, from, now, limit = 200_000)
                if (hr.size < DaytimeStress.minHourHrSamples) emptyList()
                else {
                    val rr = vm.repo.rrIntervalsUnion(vm.activeStrapId, from, now, limit = 200_000)
                    val gravity = vm.repo.gravitySamplesUnion(vm.activeStrapId, from, now, limit = 200_000)
                    DaytimeStress.analyze(hr, rr, gravity, tz).hours
                }
            }.getOrDefault(emptyList())
        }
    }
    val scored = hours.orEmpty().mapNotNull { it.level }
    val now = hours?.lastOrNull { it.level != null }?.level ?: daily[today.toString()]
    val dayMean = if (scored.isNotEmpty()) scored.average() else daily[today.toString()]

    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.canvas)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = BottomBarStyleStore.barHeightForContent() + 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, uiString(R.string.wx_back), tint = Wx.text) }
            Text(uiString(R.string.wx_stress_monitor).uppercase(), style = WxType.title, color = Wx.text,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }

        // Gauge.
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(280.dp, 160.dp), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = 18.dp.toPx()
                    val d = size.width - s
                    val tl = Offset(s / 2, s / 2)
                    drawArc(Wx.cardRaised, 180f, 180f, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
                    now?.let { v ->
                        drawArc(Wx.stressColor(v), 180f, (180.0 * (v / 3.0)).toFloat().coerceIn(1f, 180f), false, tl, Size(d, d),
                            style = Stroke(s, cap = StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(now?.let { String.format(Locale.US, "%.1f", it) } ?: "–", style = WxType.hero, color = Wx.text)
                    WxLabel(wxStressBand(now) + " · " + uiString(R.string.wx_right_now), color = Wx.stressColor(now))
                }
            }
        }

        Column(Modifier.padding(horizontal = Wx.gutter).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(Wx.gap)) {
            // Today's curve.
            WxCard {
                WxCardHeader(uiString(R.string.nav_today), dayMean?.let { uiString(R.string.wx_avg_value, String.format(Locale.US, "%.1f", it)) })
                Spacer(Modifier.height(12.dp))
                when {
                    hours == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text(uiString(R.string.wx_reading_today), style = WxType.caption, color = Wx.textMuted)
                    }
                    scored.size < 2 -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        Text(uiString(R.string.wx_not_enough_today), style = WxType.caption, color = Wx.textMuted, textAlign = TextAlign.Center)
                    }
                    else -> WxStressCurve(hours.orEmpty(), uiString(R.string.wx_stress_curve_a11y))
                }
            }

            // Time in each level.
            if (scored.isNotEmpty()) {
                val low = scored.count { it < 1.0 }; val mid = scored.count { it in 1.0..1.999 }; val high = scored.size - low - mid
                WxCard {
                    WxLabel(uiString(R.string.wx_time_in_level))
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                        if (low > 0) Box(Modifier.weight(low.toFloat()).fillMaxHeight().background(Wx.stressLow))
                        if (mid > 0) Box(Modifier.weight(mid.toFloat()).fillMaxHeight().background(Wx.stressMid))
                        if (high > 0) Box(Modifier.weight(high.toFloat()).fillMaxHeight().background(Wx.stressHigh))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        WxStat(uiString(R.string.wx_low), uiString(R.string.wx_hours_short, low), Modifier.weight(1f))
                        WxStat(uiString(R.string.wx_medium), uiString(R.string.wx_hours_short, mid), Modifier.weight(1f))
                        WxStat(uiString(R.string.wx_high), uiString(R.string.wx_hours_short, high), Modifier.weight(1f))
                    }
                }
            }

            // Last seven days.
            val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
            WxCard {
                WxLabel(uiString(R.string.wx_last_7_days))
                Spacer(Modifier.height(12.dp))
                WxBars(
                    values = week.map { daily[it.toString()] }, labels = week.map { wxWeekdayShort(it) },
                    colorFor = { Wx.stressColor(it) }, format = { String.format(Locale.US, "%.1f", it) }, max = 3.0,
                    description = uiString(R.string.wx_stress_week_a11y), height = 130.dp,
                )
            }

            WxCard(onClick = onBreathe) {
                Text(uiString(R.string.wx_breathe_title), style = WxType.bodyStrong, color = Wx.text)
                Text(uiString(R.string.wx_breathe_body), style = WxType.caption, color = Wx.textMuted)
            }
        }
    }
}

/** One smooth line through the scored hours over three soft bands (low / medium / high). */
@Composable
private fun WxStressCurve(hours: List<DaytimeStress.HourPoint>, description: String) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = description }) {
        val plotH = size.height - 20.dp.toPx()
        val w = size.width
        val band = plotH / 3f
        drawRect(Wx.stressHigh.copy(alpha = 0.07f), Offset(0f, 0f), Size(w, band))
        drawRect(Wx.stressMid.copy(alpha = 0.06f), Offset(0f, band), Size(w, band))
        drawRect(Wx.stressLow.copy(alpha = 0.07f), Offset(0f, band * 2), Size(w, band))
        val first = hours.first().hour
        val last = hours.last().hour.coerceAtLeast(first + 1)
        fun x(h: Int) = w * (h - first).toFloat() / (last - first)
        fun y(v: Double) = (plotH - (v / 3.0) * plotH).toFloat()
        val pts = hours.filter { it.level != null }.map { Offset(x(it.hour), y(it.level!!)) }
        if (pts.size >= 2) {
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val p0 = pts[i - 1]; val p1 = pts[i]
                    val cx = (p0.x + p1.x) / 2
                    cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                }
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(pts.last().x, plotH); lineTo(pts.first().x, plotH); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(Wx.stressMid.copy(alpha = 0.35f), Wx.stressLow.copy(alpha = 0f)), 0f, plotH))
            drawPath(path, Wx.text, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        val step = if (last - first > 12) 3 else 2
        var h = first
        while (h <= last) {
            val t = measurer.measure(String.format(Locale.US, "%02d", h), TextStyle(fontSize = 10.sp, color = Wx.textDim))
            drawText(t, topLeft = Offset((x(h) - t.size.width / 2f).coerceIn(0f, w - t.size.width), size.height - t.size.height))
            h += step
        }
    }
}
