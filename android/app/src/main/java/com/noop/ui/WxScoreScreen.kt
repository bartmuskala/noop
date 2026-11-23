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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                           onOpenProfile: () -> Unit, onOpenMoves: () -> Unit = {}) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val workouts = rememberWxSessions(vm)
    val effortScale = remember { UnitPrefs.effortScale(context) }
    val profile = remember { ProfileStore.from(context) }
    var tab by remember { mutableStateOf(initial) }
    // The day shown: 0 = today, 1 = yesterday, ... Starts on the day Today had selected.
    var dayOffset by remember { mutableStateOf(initialOffset.coerceAtLeast(0L)) }
    val date = logicalDayNow().minusDays(dayOffset)
    val todayResolved by vm.today.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadWorkouts() }
    // The night that belongs to the shown day: the longest session ending between noon the day before
    // and 14:00 on the day itself.
    var stages by remember { mutableStateOf<List<com.noop.analytics.StageSegment>>(emptyList()) }
    // Sleep sessions of the last six weeks up to the shown day: the night itself, bed/wake times,
    // consistency and time in bed all read from these.
    var sessions by remember { mutableStateOf<List<com.noop.data.SleepSession>>(emptyList()) }
    var nightHr by remember { mutableStateOf<List<Pair<Long, Double>>>(emptyList()) }
    LaunchedEffect(date) {
        val zone = ZoneId.systemDefault()
        val to = date.atTime(14, 0).atZone(zone).toEpochSecond()
        val all = runCatching { wxSleepSessions(vm, to - 42 * 86_400L, to) }.getOrDefault(emptyList())
        sessions = all
        val night = wxMainNight(all, date)
        stages = com.noop.analytics.AnalyticsEngine.decodeStages(night?.stagesJSON)
        nightHr = if (night == null) emptyList() else withContext(Dispatchers.Default) {
            val hr = runCatching {
                vm.repo.hrSamplesUnion(vm.activeStrapId, night.effectiveStartTs - 1800L, night.endTs + 1800L, limit = 100_000)
            }.getOrDefault(emptyList())
            hr.groupBy { it.ts / 60L }.toSortedMap().map { (m, xs) -> (m * 60L + 30L) to xs.map { it.bpm }.average() }
        }
    }
    // Effort zone minutes for the seven days ending on the shown day, from the scorer's own zone function.
    var zones by remember { mutableStateOf<Map<String, List<Double>>>(emptyMap()) }
    val zoneHrMax = StrainScorer.effortHRmax(profile.hrMaxOverride.takeIf { it > 0 }?.toDouble(), profile.age.toDouble()).first
    LaunchedEffect(date, days.size) {
        val byDay = days.associateBy { it.day }
        val out = HashMap<String, List<Double>>()
        for (d in wxWeekDates(date)) {
            wxDayZoneMinutes(vm, d, byDay[d.toString()]?.restingHr, zoneHrMax)?.let { out[d.toString()] = it }
            zones = HashMap(out)
        }
    }

    val dateKey = if (dayOffset == 0L) todayResolved?.day ?: date.toString() else date.toString()
    val row = if (dayOffset == 0L) todayResolved ?: days.lastOrNull { it.day == dateKey }
              else days.lastOrNull { it.day == dateKey }
    val upToDate = days.filter { it.day <= dateKey }
    // Recovery and Sleep describe the night, which can be keyed to the previous day: carry it on Today,
    // exactly as the old Today does. Effort stays on the shown day.
    val carried = if (dayOffset == 0L) lastScoredRecoveryDay(
        days = days, selectedDayKey = dateKey, isToday = true,
        todayScored = row?.recovery != null, isCalibrating = false,
        today = maxOf(date.toString(), LocalDate.now().toString()),
    ) else null
    val night = if (row?.recovery == null && carried != null) carried else row
    // The need for the night shown, from the same adaptive target as Today's tonight card, planned on
    // the evening before it (the wake time does not change the need).
    val planDay = (night?.day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: date).minusDays(1)
    val sleepPlan = rememberWxSleepPlan(vm, planDay, WX_DEFAULT_WAKE_MIN)
    val nightBaseline = days.filter { it.day < (night?.day ?: dateKey) }.takeLast(30)
    val baseline = days.filter { it.day < dateKey }.takeLast(30)

    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.canvas)
            .verticalScroll(rememberScrollState())
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
            WxScoreTab.Recovery -> WxRecoveryTab(night, nightBaseline, upToDate, date, onOpenMoves)
            WxScoreTab.Effort -> WxEffortTab(vm, row, date, workouts, effortScale, profile, onOpenWorkouts, onOpenProfile, upToDate, baseline, zones)
            WxScoreTab.Sleep -> WxSleepTab(vm, night, upToDate, stages, sessions, nightHr, date, sleepPlan?.plan?.needMin)
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
private fun WxRecoveryTab(row: DailyMetric?, baseline: List<DailyMetric>, days: List<DailyMetric>, date: LocalDate, onOpenMoves: () -> Unit) {
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
        WxLinkCard(uiString(R.string.wx_behaviour_insights), uiString(R.string.wx_behaviour_insights_body), onOpenMoves)
        WxRecoveryWeek(days, date)
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
    vm: AppViewModel,
    row: DailyMetric?,
    dayDate: LocalDate,
    workouts: List<com.noop.data.WorkoutRow>,
    scale: EffortScale,
    profile: ProfileStore,
    onOpenWorkouts: () -> Unit,
    onOpenProfile: () -> Unit,
    days: List<DailyMetric>,
    baseline: List<DailyMetric>,
    zones: Map<String, List<Double>>,
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
    var build by remember(dayDate) { mutableStateOf<WxEffortBuild?>(null) }
    WxCards {
        // How the day's Effort accumulated, with its workouts marked.
        WxEffortBuildCard(vm, dayDate, row, dayWorkouts, scale, onBuild = { build = it })
        // Today against a stated baseline: zones against the seven days ending today, steps and calories
        // against the last 30 days.
        WxCard(padding = 16.dp) {
            val dayZ = zones[dayDate.toString()]
            val weekZ = wxWeekDates(dayDate).mapNotNull { zones[it.toString()] }
            fun z13(z: List<Double>) = z[1] + z[2] + z[3]
            fun z45(z: List<Double>) = z[4] + z[5]
            val avg13 = weekZ.takeIf { it.isNotEmpty() }?.map(::z13)?.average()
            val avg45 = weekZ.takeIf { it.isNotEmpty() }?.map(::z45)?.average()
            val stepsAvg = baseline.mapNotNull { it.steps?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
            val kcalAvg = baseline.mapNotNull { it.activeKcalEst }.takeIf { it.isNotEmpty() }?.average()
            WxLabel(uiString(R.string.wx_vs_baseline_title), Modifier.padding(bottom = 4.dp))
            WxCompareRow(uiString(R.string.wx_hr_zones_13), uiString(R.string.wx_7_day_avg_value, avg13?.let { wxHoursMinutes(it) } ?: "–"),
                dayZ?.let { wxHoursMinutes(z13(it)) } ?: "–", wxPct(dayZ?.let(::z13), avg13), null)
            WxCompareRow(uiString(R.string.wx_hr_zones_45), uiString(R.string.wx_7_day_avg_value, avg45?.let { wxHoursMinutes(it) } ?: "–"),
                dayZ?.let { wxHoursMinutes(z45(it)) } ?: "–", wxPct(dayZ?.let(::z45), avg45), null)
            WxCompareRow(uiString(R.string.wx_steps), uiString(R.string.wx_30_day_avg_value, stepsAvg?.let { String.format(Locale.getDefault(), "%,d", it.roundToInt()) } ?: "–"),
                row?.steps?.let { String.format(Locale.getDefault(), "%,d", it) } ?: "–", wxPct(row?.steps?.toDouble(), stepsAvg), true)
            WxCompareRow(uiString(R.string.wx_calories), uiString(R.string.wx_30_day_avg_value, kcalAvg?.let { String.format(Locale.getDefault(), "%,d", it.roundToInt()) } ?: "–"),
                row?.activeKcalEst?.let { String.format(Locale.getDefault(), "%,d", it.roundToInt()) } ?: "–", wxPct(row?.activeKcalEst, kcalAvg), null)
        }
        WxCard(padding = 16.dp) {
            WxLabel(uiString(R.string.wx_activities), Modifier.padding(bottom = 4.dp))
            if (dayWorkouts.isEmpty()) {
                Text(uiString(R.string.wx_no_workouts), style = WxType.caption, color = Wx.textMuted, modifier = Modifier.padding(vertical = 12.dp))
            }
            dayWorkouts.forEach { w ->
                val mins = ((w.durationS ?: (w.endTs - w.startTs).toDouble()) / 60.0)
                WxActivityRow(
                    accent = Wx.effort, title = wxWorkoutTitle(w),
                    subtitle = listOfNotNull(wxHoursMinutes(mins), w.avgHr?.let { "$it avg" }, w.maxHr?.let { "$it max" }).joinToString(" · "),
                    trailing = w.strain?.let { UnitFormatter.effortDisplay(it, scale) } ?: "–",
                    onClick = { WxWorkoutNav.pending = WxWorkoutNav.key(w); onOpenWorkouts() },
                )
            }
            // What the hours outside workouts added to the day's Effort.
            build?.restOfDay?.let { rest ->
                WxActivityRow(
                    accent = Wx.lineStrong, title = uiString(R.string.wx_rest_of_day),
                    subtitle = uiString(R.string.wx_rest_of_day_sub),
                    trailing = "+" + UnitFormatter.effortDisplay(rest, scale),
                    onClick = null,
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
        WxEffortWeek(days, dayDate, zones, scale)
    }
}

// MARK: Sleep

@Composable
private fun WxSleepTab(
    vm: AppViewModel,
    row: DailyMetric?,
    days: List<DailyMetric>,
    stages: List<com.noop.analytics.StageSegment>,
    sessions: List<com.noop.data.SleepSession>,
    nightHr: List<Pair<Long, Double>>,
    date: LocalDate,
    plannedNeedMin: Double? = null,
) {
    val context = LocalContext.current
    val is24h = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val perf = row?.let { RestScorer.restFromDaily(it) }
    val needMin = plannedNeedMin ?: sleepNeedForDay(row, days.takeLast(30), emptyMap())
    val slept = row?.totalSleepMin
    val short = if (slept != null) needMin - slept else null
    val eff = wxEfficiencyPct(row?.efficiency)
    WxScoreHero(
        fraction = perf?.div(100.0), color = Wx.sleep,
        value = perf?.roundToInt()?.toString() ?: "–", unit = if (perf != null) "%" else null,
        band = uiString(R.string.wx_sleep_performance),
        stats = listOf(
            uiString(R.string.wx_hours) to (slept?.let { wxHoursMinutes(it) } ?: "–"),
            uiString(R.string.wx_needed) to wxHoursMinutes(needMin),
            uiString(R.string.wx_efficiency) to (eff?.let { "${it.roundToInt()}%" } ?: "–"),
        ),
        sentence = when {
            short == null -> uiString(R.string.wx_sleep_sentence_empty)
            short > 10 -> uiString(R.string.wx_sleep_short, wxHoursMinutes(short))
            else -> uiString(R.string.wx_sleep_met)
        },
    )
    // The night this row describes (it can be carried from the day before on Today).
    val nightDate = row?.day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: date
    val night = wxMainNight(sessions, nightDate)
    val base = days.filter { it.day < nightDate.toString() }.takeLast(30)
    fun avg(sel: (DailyMetric) -> Double?): Double? = base.mapNotNull(sel).takeIf { it.isNotEmpty() }?.average()
    val awakeMin = stages.filter { it.stage == "wake" || it.stage == "awake" }.sumOf { (it.end - it.start) / 60.0 }
    val light = row?.lightMin ?: 0.0
    val deep = row?.deepMin ?: 0.0
    val rem = row?.remMin ?: 0.0
    WxCards {
        // Last night against the 30 nights before it.
        WxCard(padding = 16.dp) {
            WxLabel(uiString(R.string.wx_vs_baseline_title), Modifier.padding(bottom = 4.dp))
            val hvn = slept?.let { it / needMin * 100 }
            val hvnAvg = avg { it.totalSleepMin }?.let { it / needMin * 100 }
            WxCompareRow(uiString(R.string.wx_hours_vs_needed), avgLine(hvnAvg, "%", 0),
                hvn?.let { "${it.roundToInt()}%" } ?: "–", wxPct(hvn, hvnAvg), true)
            val cons = consistencySeries(sessions.filter { night == null || it.endTs <= night.endTs })
            WxCompareRow(uiString(R.string.wx_sleep_consistency), avgLine(cons.typical, "%", 0),
                cons.latest?.let { "${it.roundToInt()}%" } ?: "–", wxPct(cons.latest, cons.typical), true)
            val effAvg = avg { wxEfficiencyPct(it.efficiency) }
            WxCompareRow(uiString(R.string.wx_efficiency), avgLine(effAvg, "%", 0),
                eff?.let { "${it.roundToInt()}%" } ?: "–", wxPct(eff, effAvg), true)
            val restor = row?.let { (it.deepMin ?: 0.0) + (it.remMin ?: 0.0) }
            val restorAvg = avg { r -> if (r.deepMin == null && r.remMin == null) null else (r.deepMin ?: 0.0) + (r.remMin ?: 0.0) }
            WxCompareRow(uiString(R.string.wx_restorative), uiString(R.string.wx_30_day_avg_value, restorAvg?.let { wxHoursMinutes(it) } ?: "–"),
                restor?.let { wxHoursMinutes(it) } ?: "–", wxPct(restor, restorAvg), true)
        }

        // Hours of sleep: the night's heart rate between bed and wake.
        WxCard {
            WxCardHeader(uiString(R.string.wx_hours_of_sleep), slept?.let { wxHoursMinutes(it) })
            Spacer(Modifier.height(10.dp))
            if (night != null && nightHr.size >= 2) {
                WxNightHrChart(nightHr, night.effectiveStartTs, night.endTs, is24h, uiString(R.string.wx_hours_of_sleep))
            } else {
                Text(uiString(R.string.wx_no_hr_night), style = WxType.caption, color = Wx.textMuted)
            }
        }

        // Stages with the hypnogram, each against its 30-night average (the white mark).
        WxCard {
            WxCardHeader(uiString(R.string.wx_stages), night?.let {
                val z = ZoneId.systemDefault()
                fun c(ts: Long) = Instant.ofEpochSecond(ts).atZone(z).let { t -> wxClock(t.hour * 60 + t.minute, is24h) }
                "${c(it.effectiveStartTs)} – ${c(it.endTs)}"
            })
            Spacer(Modifier.height(14.dp))
            if (stages.isNotEmpty()) {
                WxHypnogram(stages, uiString(R.string.wx_hypnogram_a11y))
                Spacer(Modifier.height(12.dp))
            }
            val inBed = (awakeMin + light + deep + rem).coerceAtLeast(1.0)
            WxStageRow(uiString(R.string.wx_awake), Wx.stageAwake, awakeMin, inBed, null)
            WxStageRow(uiString(R.string.wx_light_sleep), Wx.stageLight, light, inBed, avg { it.lightMin })
            WxStageRow(uiString(R.string.wx_deep_sleep), Wx.stageDeep, deep, inBed, avg { it.deepMin })
            WxStageRow(uiString(R.string.wx_rem_sleep), Wx.stageRem, rem, inBed, avg { it.remMin })
            Text(uiString(R.string.wx_stage_avg_note), style = WxType.caption, color = Wx.textDim, modifier = Modifier.padding(top = 4.dp))
        }

        // Efficiency: asleep against awake, and the wake events.
        WxCard {
            WxCardHeader(uiString(R.string.wx_efficiency), eff?.let { "${it.roundToInt()}%" })
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                WxStat(uiString(R.string.wx_asleep), slept?.let { wxHoursMinutes(it) } ?: "–", Modifier.weight(1f))
                WxStat(uiString(R.string.wx_awake), if (stages.isNotEmpty()) wxHoursMinutes(awakeMin) else "–", Modifier.weight(1f))
                WxStat(uiString(R.string.wx_wake_events), row?.disturbances?.toString() ?: "–", Modifier.weight(1f))
            }
        }

        // Consistency: bed and wake times of the last five nights.
        WxCard {
            val cons = consistencySeries(sessions.filter { night == null || it.endTs <= night.endTs })
            WxCardHeader(uiString(R.string.wx_sleep_consistency), cons.latest?.let { "${it.roundToInt()}%" })
            Spacer(Modifier.height(12.dp))
            val five = (4 downTo 0).map { nightDate.minusDays(it.toLong()) }
            WxBedWakeBars(five.map { d -> wxMainNight(sessions, d)?.let { it.effectiveStartTs to it.endTs } },
                five.map { wxWeekdayShort(it) }, highlightLast = true, color = Wx.sleepSoft,
                description = uiString(R.string.wx_sleep_consistency), is24h = is24h)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Wx.gap)) {
            WxSmallStat(uiString(R.string.wx_hrv), row?.avgHrv?.let { "${it.roundToInt()}" } ?: "–", "ms", Modifier.weight(1f))
            WxSmallStat(uiString(R.string.wx_resting_hr), row?.restingHr?.toString() ?: "–", "bpm", Modifier.weight(1f))
        }

        val debtByDay = remember(days) {
            com.noop.analytics.SleepDebt.debtSeries(
                series = days.map { it.day to com.noop.analytics.SleepDebt.creditedSleepMin(it.totalSleepMin) },
                needHours = RestScorer.personalizedNeedHours(days.mapNotNull { it.totalSleepMin?.let { m -> m / 60.0 } }, null),
            ).toMap()
        }
        val plannedNeeds = rememberWxPlannedNeeds(vm, wxWeekDates(nightDate))
        WxSleepWeek(days, nightDate, sessions, needMin, debtByDay, is24h, plannedNeeds)
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
            val r = rows.indexOfFirst { it.first == (if (seg.stage == "awake") "wake" else seg.stage) }.takeIf { it >= 0 } ?: return@forEach
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

