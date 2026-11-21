package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.RestScorer
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Wx score detail
//
// One screen, three tabs (Recovery · Effort · Sleep), so a tap on any Today dial lands here and the
// other two scores are one tab away. The hero is identical on every tab — a 240dp ring, a row of three
// stats, one sentence of fixed height — so the first card starts at the same place on all three.

internal enum class WxScoreTab(val route: String) {
    Recovery("recovery"), Effort("effort"), Sleep("sleep");
    companion object { fun fromRoute(r: String?) = entries.firstOrNull { it.route == r } ?: Recovery }
}

@Composable
internal fun WxScoreScreen(vm: AppViewModel, initial: WxScoreTab, initialOffset: Long = 0L, onBack: () -> Unit, onOpenWorkouts: () -> Unit,
                           onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val effortScale = remember { UnitPrefs.effortScale(context) }
    val profile = remember { ProfileStore.from(context) }
    var tab by remember { mutableStateOf(initial) }
    // The day shown: 0 = today, 1 = yesterday, ... Starts on the day Today had selected.
    var dayOffset by remember { mutableStateOf(initialOffset.coerceAtLeast(0L)) }
    val date = LocalDate.now().minusDays(dayOffset)
    LaunchedEffect(Unit) { vm.loadWorkouts() }
    // The night that belongs to the shown day: the longest session ending between noon the day before
    // and 14:00 on the day itself.
    var stages by remember { mutableStateOf<List<com.noop.analytics.StageSegment>>(emptyList()) }
    LaunchedEffect(date) {
        val zone = ZoneId.systemDefault()
        val to = date.atTime(14, 0).atZone(zone).toEpochSecond()
        val from = date.minusDays(1).atTime(12, 0).atZone(zone).toEpochSecond()
        val sessions = runCatching { vm.repo.sleepSessionsUnion(vm.activeStrapId, from - 24 * 3600L, to) }.getOrDefault(emptyList())
        val night = sessions.filter { it.endTs in from..to }.maxByOrNull { it.endTs - it.startTs }
        stages = com.noop.analytics.AnalyticsEngine.decodeStages(night?.stagesJSON)
    }

    val dateKey = date.toString()
    val row = days.lastOrNull { it.day == dateKey }
    val upToDate = days.filter { it.day <= dateKey }
    val baseline = days.filter { it.day < dateKey }.takeLast(30)

    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.canvas)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = BottomBarStyleStore.barHeightForContent() + 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, uiString(R.string.wx_back), tint = Wx.text)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { dayOffset += 1 }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, uiString(R.string.wx_prev_day), tint = Wx.textMuted)
            }
            Text(
                if (dayOffset == 0L) uiString(R.string.nav_today) else wxShortDate(date),
                style = WxType.bodyStrong.copy(fontSize = 14.sp), color = Wx.text, textAlign = TextAlign.Center,
                modifier = Modifier.width(120.dp),
            )
            IconButton(onClick = { if (dayOffset > 0) dayOffset -= 1 }, enabled = dayOffset > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, uiString(R.string.wx_next_day),
                    tint = if (dayOffset > 0) Wx.textMuted else Wx.lineStrong)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        // Tabs.
        Row(Modifier.fillMaxWidth().padding(horizontal = Wx.gutter)) {
            WxScoreTab.entries.forEach { t ->
                val on = t == tab
                val accent = when (t) {
                    WxScoreTab.Recovery -> Wx.recoveryColor(row?.recovery)
                    WxScoreTab.Effort -> Wx.effort
                    WxScoreTab.Sleep -> Wx.sleep
                }
                Column(
                    Modifier.weight(1f).clickable { tab = t }.padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(wxTabLabel(t).uppercase(), style = WxType.tab, color = if (on) Wx.text else Wx.textDim)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) accent else Wx.line))
                }
            }
        }

        when (tab) {
            WxScoreTab.Recovery -> WxRecoveryTab(row, baseline, upToDate)
            WxScoreTab.Effort -> WxEffortTab(row, date, workouts, effortScale, profile, onOpenWorkouts, onOpenProfile)
            WxScoreTab.Sleep -> WxSleepTab(row, upToDate, stages)
        }
    }
}

@Composable
private fun wxTabLabel(t: WxScoreTab): String = when (t) {
    WxScoreTab.Recovery -> uiString(R.string.wx_recovery)
    WxScoreTab.Effort -> uiString(R.string.wx_effort)
    WxScoreTab.Sleep -> uiString(R.string.wx_sleep)
}

/** The shared hero: ring, value, band label, three stats, one sentence of fixed height. */
@Composable
private fun WxScoreHero(
    fraction: Double?,
    color: Color,
    value: String,
    unit: String?,
    band: String,
    stats: List<Pair<String, String>>,
    sentence: String,
    bandFrom: Double? = null,
    bandTo: Double? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.radialGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent)))
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WxRing(fraction, color, 240.dp, 14.dp, bandFrom = bandFrom, bandTo = bandTo) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, style = WxType.hero.copy(fontSize = 72.sp), color = Wx.text)
                    if (unit != null) Text(unit, style = WxType.hero.copy(fontSize = 32.sp), color = Wx.text,
                        modifier = Modifier.padding(bottom = 10.dp))
                }
                WxLabel(band, color = color)
            }
        }
        Row(Modifier.fillMaxWidth().height(52.dp).padding(top = 12.dp)) {
            stats.forEach { (l, v) -> WxStat(l, v, Modifier.weight(1f)) }
        }
        Text(
            sentence, style = WxType.body.copy(fontSize = 14.sp), color = Wx.textSoft, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 12.dp),
        )
    }
}

@Composable
private fun WxCards(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Wx.gutter).padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Wx.gap),
    ) { content() }
}

// MARK: Recovery

@Composable
private fun WxRecoveryTab(row: DailyMetric?, baseline: List<DailyMetric>, days: List<DailyMetric>) {
    val rec = row?.recovery
    val color = Wx.recoveryColor(rec)
    fun mean(sel: (DailyMetric) -> Double?): Double? = baseline.mapNotNull(sel).takeIf { it.isNotEmpty() }?.average()
    val hrvAvg = mean { it.avgHrv }
    val rhrAvg = mean { it.restingHr?.toDouble() }
    val respAvg = mean { it.respRateBpm }
    val sleepPerf = row?.let { RestScorer.restFromDaily(it) }
    val sleepAvg = mean { RestScorer.restFromDaily(it) }
    val range = optimalStrainRange(rec)
    WxScoreHero(
        fraction = rec?.div(100.0), color = color,
        value = rec?.roundToInt()?.toString() ?: "–", unit = if (rec != null) "%" else null,
        band = when {
            rec == null -> uiString(R.string.wx_no_data)
            rec >= 67 -> uiString(R.string.wx_recovered)
            rec >= 34 -> uiString(R.string.wx_maintaining)
            else -> uiString(R.string.wx_rest_up)
        },
        stats = listOf(
            uiString(R.string.wx_hrv) to (row?.avgHrv?.let { "${it.roundToInt()} ms" } ?: "–"),
            uiString(R.string.wx_rhr) to (row?.restingHr?.toString() ?: "–"),
            uiString(R.string.wx_resp_short) to (row?.respRateBpm?.let { String.format(Locale.US, "%.1f", it) } ?: "–"),
        ),
        sentence = if (range != null) uiString(R.string.wx_recovery_sentence, range.low, range.high)
        else uiString(R.string.wx_recovery_sentence_empty),
    )
    WxCards {
        WxCard(padding = 16.dp) {
            WxLabel(uiString(R.string.wx_what_drove_it), Modifier.padding(bottom = 4.dp))
            WxContributor(uiString(R.string.wx_hrv_long), avgLine(hrvAvg, "ms", 0), row?.avgHrv, hrvAvg, "ms", 0, true)
            WxContributor(uiString(R.string.wx_rhr_long), avgLine(rhrAvg, "bpm", 0), row?.restingHr?.toDouble(), rhrAvg, "bpm", 0, false)
            WxContributor(uiString(R.string.wx_resp_long), avgLine(respAvg, "rpm", 1), row?.respRateBpm, respAvg, null, 1, null)
            WxContributor(uiString(R.string.wx_sleep_performance), avgLine(sleepAvg, "%", 0), sleepPerf, sleepAvg, "%", 0, true)
            WxContributor(uiString(R.string.wx_skin_temp), uiString(R.string.wx_vs_baseline), row?.skinTempDevC, 0.0, "°C", 1, null, signed = true)
        }
        WxCard {
            val last30 = days.takeLast(30)
            WxCardHeader(uiString(R.string.wx_last_30_days),
                last30.mapNotNull { it.recovery }.takeIf { it.isNotEmpty() }?.average()?.let { uiString(R.string.wx_avg_pct, it.roundToInt()) })
            Spacer(Modifier.height(12.dp))
            WxBars(
                values = last30.map { it.recovery }, labels = last30.map { "" },
                colorFor = { Wx.recoveryColor(it) }, format = { "" }, max = 100.0,
                description = uiString(R.string.wx_recovery_30_a11y), height = 130.dp,
            )
        }
    }
}

@Composable
private fun avgLine(v: Double?, unit: String, decimals: Int): String =
    uiString(R.string.wx_30_day_avg_value, v?.let { wxFmt(it, decimals) + " " + unit } ?: "–")

private fun wxFmt(v: Double, decimals: Int): String =
    if (decimals == 0) "${v.roundToInt()}" else String.format(Locale.US, "%.${decimals}f", v)

@Composable
private fun WxContributor(
    title: String, subtitle: String, value: Double?, mean: Double?, unit: String?, decimals: Int,
    higherIsBetter: Boolean?, signed: Boolean = false,
) {
    val pct = if (value != null && mean != null && mean != 0.0 && !signed) (value - mean) / abs(mean) * 100 else null
    val deltaText = when {
        signed && value != null -> if (abs(value) <= 0.3) uiString(R.string.wx_ok) else (if (value > 0) "▲" else "▼")
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
                Text(subtitle, style = WxType.caption, color = Wx.textMuted)
            }
            val valueText = value?.let { (if (signed && it >= 0) "+" else "") + wxFmt(it, decimals) } ?: "–"
            Text(valueText, style = WxType.numberSmall.copy(fontSize = 24.sp), color = Wx.text)
            if (unit != null) Text(" $unit", style = WxType.caption, color = Wx.textMuted)
            Text(deltaText, style = WxType.caption.copy(fontSize = 13.sp), textAlign = TextAlign.End,
                color = when (good) { true -> Wx.recoveryHigh; false -> Wx.recoveryMid; null -> Wx.textMuted },
                modifier = Modifier.width(58.dp))
        }
    }
}

// MARK: Effort

@Composable
private fun WxEffortTab(
    row: DailyMetric?,
    dayDate: LocalDate,
    workouts: List<com.noop.data.WorkoutRow>,
    scale: EffortScale,
    profile: ProfileStore,
    onOpenWorkouts: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val effort = row?.strain
    val range = optimalStrainRange(row?.recovery)
    val dayWorkouts = workouts.filter {
        Instant.ofEpochSecond(it.startTs).atZone(ZoneId.systemDefault()).toLocalDate() == dayDate
    }.sortedBy { it.startTs }
    val bandWord = when {
        effort == null -> uiString(R.string.wx_no_data)
        effort < 10.0 / 21 * 100 -> uiString(R.string.wx_light)
        effort < 14.0 / 21 * 100 -> uiString(R.string.wx_moderate)
        effort < 18.0 / 21 * 100 -> uiString(R.string.wx_strenuous)
        else -> uiString(R.string.wx_all_out)
    }
    fun onScale(v21: Int): String = UnitFormatter.effortDisplay(StrainScorer.effortValueFromWhoopStrain(v21.toDouble()), scale)
        .removeSuffix(".0")
    val sentence = when {
        effort == null || range == null -> uiString(R.string.wx_effort_sentence_empty)
        effort < StrainScorer.effortValueFromWhoopStrain(range.low.toDouble()) -> uiString(R.string.wx_effort_below, onScale(range.low))
        effort > StrainScorer.effortValueFromWhoopStrain(range.high.toDouble()) -> uiString(R.string.wx_effort_above)
        else -> uiString(R.string.wx_effort_in_range)
    }
    WxScoreHero(
        fraction = effort?.div(100.0), color = Wx.effort,
        value = effort?.let { UnitFormatter.effortDisplay(it, scale) } ?: "–", unit = null, band = bandWord,
        stats = listOf(
            uiString(R.string.wx_target) to (range?.let { "${onScale(it.low)} – ${onScale(it.high)}" } ?: "–"),
            uiString(R.string.wx_calories) to (row?.activeKcalEst?.let { String.format(Locale.US, "%,d", it.roundToInt()) } ?: "–"),
            uiString(R.string.wx_workouts) to "${dayWorkouts.size}",
        ),
        sentence = sentence,
        bandFrom = range?.let { it.low / 21.0 }, bandTo = range?.let { it.high / 21.0 },
    )
    WxCards {
        WxCard(padding = 16.dp) {
            WxLabel(uiString(R.string.wx_activities), Modifier.padding(bottom = 4.dp))
            if (dayWorkouts.isEmpty()) {
                Text(uiString(R.string.wx_no_workouts), style = WxType.caption, color = Wx.textMuted, modifier = Modifier.padding(vertical = 12.dp))
            }
            dayWorkouts.forEach { w ->
                val mins = ((w.durationS ?: (w.endTs - w.startTs).toDouble()) / 60.0)
                WxActivityRow(
                    accent = Wx.effort, title = wxSportName(w.sport),
                    subtitle = listOfNotNull(wxHoursMinutes(mins), w.avgHr?.let { "$it avg" }, w.maxHr?.let { "$it max" }).joinToString(" · "),
                    trailing = w.strain?.let { UnitFormatter.effortDisplay(it, scale) } ?: "–",
                    onClick = onOpenWorkouts,
                )
            }
        }
        // Max HR: what every Effort on this phone is scored against, from the one resolver.
        val (hrMax, source) = StrainScorer.effortHRmax(profile.hrMaxOverride.takeIf { it > 0 }?.toDouble(), profile.age.toDouble())
        WxCard(onClick = onOpenProfile) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Wx.cardRaised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Favorite, null, tint = Wx.heart, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(uiString(R.string.wx_max_hr_value, hrMax?.roundToInt() ?: StrainScorer.defaultMaxHR()),
                        style = WxType.bodyStrong, color = Wx.text)
                    Text(
                        when (source) {
                            "override" -> uiString(R.string.wx_max_hr_override)
                            "tanaka" -> uiString(R.string.wx_max_hr_age, profile.age)
                            else -> uiString(R.string.wx_max_hr_default)
                        },
                        style = WxType.caption, color = Wx.textMuted,
                    )
                }
                Text(uiString(R.string.wx_edit), style = WxType.bodyStrong.copy(fontSize = 13.sp), color = Wx.effort)
            }
        }
    }
}

// MARK: Sleep

@Composable
private fun WxSleepTab(row: DailyMetric?, days: List<DailyMetric>, stages: List<com.noop.analytics.StageSegment>) {
    val perf = row?.let { RestScorer.restFromDaily(it) }
    val needMin = sleepNeedForDay(row, days.takeLast(30), emptyMap())
    val slept = row?.totalSleepMin
    val short = if (slept != null) needMin - slept else null
    WxScoreHero(
        fraction = perf?.div(100.0), color = Wx.sleep,
        value = perf?.roundToInt()?.toString() ?: "–", unit = if (perf != null) "%" else null,
        band = uiString(R.string.wx_sleep_performance),
        stats = listOf(
            uiString(R.string.wx_hours) to (slept?.let { wxHoursMinutes(it) } ?: "–"),
            uiString(R.string.wx_needed) to wxHoursMinutes(needMin),
            uiString(R.string.wx_efficiency) to (row?.efficiency?.let { "${it.roundToInt()}%" } ?: "–"),
        ),
        sentence = when {
            short == null -> uiString(R.string.wx_sleep_sentence_empty)
            short > 10 -> uiString(R.string.wx_sleep_short, wxHoursMinutes(short))
            else -> uiString(R.string.wx_sleep_met)
        },
    )
    WxCards {
        WxCard {
            WxCardHeader(uiString(R.string.wx_stages), stages.takeIf { it.isNotEmpty() }?.let {
                val z = ZoneId.systemDefault()
                fun c(ts: Long) = Instant.ofEpochSecond(ts).atZone(z).let { t -> String.format(Locale.US, "%02d:%02d", t.hour, t.minute) }
                "${c(it.first().start)} – ${c(it.last().end)}"
            })
            Spacer(Modifier.height(14.dp))
            if (stages.isNotEmpty()) {
                WxHypnogram(stages, uiString(R.string.wx_hypnogram_a11y))
                Spacer(Modifier.height(16.dp))
            }
            val light = row?.lightMin ?: 0.0
            val deep = row?.deepMin ?: 0.0
            val rem = row?.remMin ?: 0.0
            val total = (light + deep + rem).coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                if (light > 0) Box(Modifier.weight(light.toFloat()).fillMaxSize().background(Wx.stageLight))
                if (deep > 0) Box(Modifier.weight(deep.toFloat()).fillMaxSize().background(Wx.stageDeep))
                if (rem > 0) Box(Modifier.weight(rem.toFloat()).fillMaxSize().background(Wx.stageRem))
                if (light + deep + rem <= 0) Box(Modifier.weight(1f).fillMaxSize().background(Wx.cardRaised))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                WxStage(uiString(R.string.wx_light_sleep), Wx.stageLight, light, total, Modifier.weight(1f))
                WxStage(uiString(R.string.wx_deep_sleep), Wx.stageDeep, deep, total, Modifier.weight(1f))
                WxStage(uiString(R.string.wx_rem_sleep), Wx.stageRem, rem, total, Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Wx.gap)) {
            WxSmallStat(uiString(R.string.wx_restorative), row?.let { wxHoursMinutes((it.deepMin ?: 0.0) + (it.remMin ?: 0.0)) } ?: "–",
                uiString(R.string.wx_deep_plus_rem), Modifier.weight(1f))
            WxSmallStat(uiString(R.string.wx_disturbances), row?.disturbances?.toString() ?: "–",
                uiString(R.string.wx_last_night), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Wx.gap)) {
            WxSmallStat(uiString(R.string.wx_hrv), row?.avgHrv?.let { "${it.roundToInt()}" } ?: "–", "ms", Modifier.weight(1f))
            WxSmallStat(uiString(R.string.wx_resting_hr), row?.restingHr?.toString() ?: "–", "bpm", Modifier.weight(1f))
        }
    }
}

@Composable
private fun WxStage(name: String, color: Color, minutes: Double, total: Double, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Spacer(Modifier.width(6.dp))
            Text(name, style = WxType.caption, color = Wx.textMuted)
        }
        Text(wxHoursMinutes(minutes), style = WxType.numberSmall, color = Wx.text)
        Text("${(minutes / total * 100).roundToInt()}%", style = WxType.caption.copy(fontSize = 11.sp), color = Wx.textDim)
    }
}

@Composable
private fun WxSmallStat(label: String, value: String, caption: String, modifier: Modifier) {
    WxCard(modifier = modifier) {
        WxLabel(label)
        Text(value, style = WxType.number, color = Wx.text, modifier = Modifier.padding(top = 6.dp))
        Text(caption, style = WxType.caption, color = Wx.textMuted)
    }
}


/** Rows top to bottom: awake, REM, light, deep. Each segment is a rounded bar on its row. */
@Composable
private fun WxHypnogram(stages: List<com.noop.analytics.StageSegment>, description: String) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val rows = listOf("wake" to Wx.stageAwake, "rem" to Wx.stageRem, "light" to Wx.stageLight, "deep" to Wx.stageDeep)
    val labels = listOf(uiString(R.string.wx_awake), uiString(R.string.wx_rem_sleep), uiString(R.string.wx_light_sleep), uiString(R.string.wx_deep_sleep))
    val start = stages.first().start
    val end = stages.maxOf { it.end }.coerceAtLeast(start + 1)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            .semantics { contentDescription = description },
    ) {
        val labelW = 46.dp.toPx()
        val rowH = 26.dp.toPx()
        val barH = 14.dp.toPx()
        val plotW = size.width - labelW
        labels.forEachIndexed { i, l ->
            val t = measurer.measure(l, androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Wx.textDim))
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(0f, i * rowH + (barH - t.size.height) / 2 + 4))
        }
        stages.forEach { seg ->
            val r = rows.indexOfFirst { it.first == seg.stage }.takeIf { it >= 0 } ?: return@forEach
            val x0 = labelW + plotW * ((seg.start - start).toFloat() / (end - start))
            val x1 = labelW + plotW * ((seg.end - start).toFloat() / (end - start))
            drawRoundRect(rows[r].second, androidx.compose.ui.geometry.Offset(x0, r * rowH + 4),
                androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(2f), barH),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
        val z = ZoneId.systemDefault()
        val ticks = 4
        for (k in 0..ticks) {
            val ts = start + (end - start) * k / ticks
            val t = Instant.ofEpochSecond(ts).atZone(z)
            val lay = measurer.measure(String.format(Locale.US, "%02d:%02d", t.hour, t.minute),
                androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = Wx.textDim))
            val x = (labelW + plotW * k / ticks - lay.size.width / 2f).coerceIn(labelW, size.width - lay.size.width)
            drawText(lay, topLeft = androidx.compose.ui.geometry.Offset(x, size.height - lay.size.height))
        }
    }
}

