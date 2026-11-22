package com.noop.ui

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.StrainScorer
import kotlin.math.roundToInt

// MARK: - Wx Live
//
// The strap's live heart rate as one ring: the bpm, its share of max HR and the zone it sits in (zones at
// 50/60/70/80/90 % of the same max HR every Effort is scored with), a five-minute trace coloured by zone,
// the rolling RMSSD and last R-R interval from the live stream, and the strap's status. The signal console
// and session controls stay on the full Live screen, one tap away.

private val WX_ZONE_COLORS get() = listOf(Wx.textMuted, Wx.zone1, Wx.zone2, Wx.zone3, Wx.zone4, Wx.zone5)

/** 0 = below zone 1, then zones 1–5 at 50/60/70/80/90 % of max HR. */
internal fun wxHrZone(bpm: Int, hrMax: Double): Int {
    val p = bpm / hrMax
    return when { p >= 0.9 -> 5; p >= 0.8 -> 4; p >= 0.7 -> 3; p >= 0.6 -> 2; p >= 0.5 -> 1; else -> 0 }
}

@Composable
internal fun WxLiveScreen(vm: AppViewModel, onBack: () -> Unit, onOpenConsole: () -> Unit, onOpenDevices: () -> Unit) {
    val context = LocalContext.current
    val live = vm.live.collectAsStateWithLifecycle().value
    val profile = remember { ProfileStore.from(context) }
    val hrMax = StrainScorer.effortHRmax(profile.hrMaxOverride.takeIf { it > 0 }?.toDouble(), profile.age.toDouble()).first
        ?: StrainScorer.defaultMaxHR().toDouble()
    val requestScan = rememberRequestScan { vm.connect() }
    var showStart by remember { mutableStateOf(false) }
    // The last five minutes of live readings, kept while the screen is open.
    var trace by remember { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) }
    val bpm = live.heartRate?.takeIf { live.connected }
    LaunchedEffect(bpm, live.rrSeq) {
        val now = System.currentTimeMillis() / 1000L
        if (bpm != null) trace = (trace + (now to bpm)).filter { it.first >= now - 300 }
    }
    val zone = bpm?.let { wxHrZone(it, hrMax) }
    val zoneColor = zone?.let { WX_ZONE_COLORS[it] } ?: Wx.textMuted

    WxPage(title = uiString(R.string.wx_live), onBack = onBack) {
        // Status.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WxChip(if (live.connected) uiString(R.string.wx_connected) else uiString(R.string.wx_not_connected),
                if (live.connected) Wx.recoveryHigh else Wx.recoveryMid)
            live.batteryPct?.let { WxChip("${it.roundToInt()}%", if (it <= 20) Wx.recoveryLow else Wx.textSoft) }
            if (live.connected) WxChip(uiString(if (live.worn) R.string.wx_worn else R.string.wx_not_worn), Wx.textSoft)
        }

        // The ring.
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            WxRing(fraction = bpm?.let { (it / hrMax).coerceIn(0.0, 1.0) }, color = zoneColor, size = 250.dp, stroke = 16.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(bpm?.toString() ?: "–", style = WxType.hero.copy(fontSize = 80.sp), color = Wx.text)
                    WxLabel("bpm", color = Wx.textMuted)
                    if (zone != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (zone == 0) uiString(R.string.wx_below_zones) else uiString(R.string.wx_zone_n, zone),
                            style = WxType.bodyStrong, color = zoneColor,
                        )
                        Text(uiString(R.string.wx_pct_of_max_short, (bpm / hrMax * 100).roundToInt()), style = WxType.caption, color = Wx.textMuted)
                    }
                }
            }
        }
        if (!live.connected) {
            WxButton(uiString(R.string.wx_connect), onClick = requestScan, color = Wx.recoveryHigh)
        }

        // Zone scale with the current position.
        WxCard {
            WxCardHeader(uiString(R.string.wx_zones), uiString(R.string.wx_max_hr_value, hrMax.roundToInt()))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                (1..5).forEach { z -> Box(Modifier.weight(1f).fillMaxHeight().background(WX_ZONE_COLORS[z].copy(alpha = if (z == zone) 1f else 0.35f))) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                (1..5).forEach { z ->
                    Text("${(hrMax * (0.4 + z * 0.1)).roundToInt()}", style = WxType.caption.copy(fontSize = 11.sp),
                        color = if (z == zone) Wx.text else Wx.textDim, modifier = Modifier.weight(1f))
                }
            }
        }

        // Last five minutes.
        WxCard {
            WxCardHeader(uiString(R.string.wx_last_5_min), trace.takeIf { it.isNotEmpty() }?.let { t ->
                "${t.minOf { it.second }}–${t.maxOf { it.second }} bpm"
            })
            Spacer(Modifier.height(10.dp))
            if (trace.size < 2) {
                Text(uiString(R.string.wx_waiting_for_live), style = WxType.caption, color = Wx.textMuted)
            } else {
                val desc = uiString(R.string.wx_heart_rate)
                Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = desc }) {
                    val now = trace.last().first
                    val lo = (trace.minOf { it.second } - 5).toFloat(); val hi = (trace.maxOf { it.second } + 5).toFloat()
                    fun x(ts: Long) = size.width * (1f - (now - ts) / 300f)
                    fun y(v: Int) = size.height * (1f - (v - lo) / (hi - lo))
                    for (i in 1 until trace.size) {
                        val (t0, v0) = trace[i - 1]; val (t1, v1) = trace[i]
                        drawLine(WX_ZONE_COLORS[wxHrZone(v1, hrMax)], Offset(x(t0), y(v0)), Offset(x(t1), y(v1)),
                            2.5.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }

        // Live variability.
        Row(horizontalArrangement = Arrangement.spacedBy(Wx.gap)) {
            WxCard(Modifier.weight(1f)) {
                WxLabel("RMSSD")
                Text(rollingRMSSD(live.rrRecent)?.let { "${it.roundToInt()}" } ?: "–", style = WxType.number, color = Wx.text)
                Text("ms", style = WxType.caption, color = Wx.textMuted)
            }
            WxCard(Modifier.weight(1f)) {
                WxLabel("R-R")
                Text(live.rrRecent.lastOrNull()?.toString() ?: "–", style = WxType.number, color = Wx.text)
                Text("ms", style = WxType.caption, color = Wx.textMuted)
            }
        }
        WxButton(uiString(R.string.wx_start_workout), onClick = { showStart = true })
        WxLinkRow(uiString(R.string.wx_live_console), onOpenConsole)
        WxLinkRow(uiString(R.string.wx_manage_devices), onOpenDevices)
    }
    if (showStart) StartWorkoutSheet(vm, onDismiss = { showStart = false })
}

@Composable
private fun WxChip(text: String, color: Color) {
    Text(text, style = WxType.bodyStrong.copy(fontSize = 13.sp), color = color,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Wx.card).padding(horizontal = 12.dp, vertical = 6.dp))
}
