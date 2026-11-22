package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.DaytimeStress
import com.noop.analytics.RestScorer
import com.noop.analytics.SleepDebt
import com.noop.analytics.SleepNeedPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// MARK: - Adaptive sleep target
//
// The ONE place the evening's sleep plan is assembled from stored data, so the Today tonight card,
// the Alarms screen and the Sleep tab's "Needed" read the same figure. Every input already exists in
// NOOP: recent nights, the rolling sleep debt, the day's Effort, naps, the illness watch, efficiency,
// the usual bed and wake times from sleep sessions, and the day's stress. The arithmetic lives in the
// pure [SleepNeedPlanner].

internal object WxSleepPlanPrefs {
    private const val KEY = "wx.sleep.adaptive"
    fun enabled(ctx: android.content.Context): Boolean = NoopPrefs.of(ctx).getBoolean(KEY, true)
    fun setEnabled(ctx: android.content.Context, on: Boolean) { NoopPrefs.of(ctx).edit().putBoolean(KEY, on).apply() }
}

internal data class WxSleepPlan(val plan: SleepNeedPlanner.Plan, val personalSlope: Boolean)

/**
 * The plan for the night that follows [forDay], waking at [wakeMinute]; null while loading or when the
 * adaptive target is switched off (callers then use the fixed need).
 */
@Composable
internal fun rememberWxSleepPlan(vm: AppViewModel, forDay: LocalDate, wakeMinute: Int, refresh: Int = 0): WxSleepPlan? {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val illnessAlert by vm.healthAlert.collectAsStateWithLifecycle()
    val enabled = remember(refresh) { WxSleepPlanPrefs.enabled(context) }
    val profile = remember { ProfileStore.from(context) }
    var plan by remember { mutableStateOf<WxSleepPlan?>(null) }
    LaunchedEffect(forDay, days.size, wakeMinute, illnessAlert, enabled) {
        if (!enabled) { plan = null; return@LaunchedEffect }
        plan = withContext(Dispatchers.Default) {
            runCatching { buildWxSleepPlan(vm, days, forDay, wakeMinute, illnessAlert != null, profile.age, personalStress = NoopPrefs.stressPersonalBaseline(context)) }.getOrNull()
        }
    }
    return if (enabled) plan else null
}

private suspend fun buildWxSleepPlan(
    vm: AppViewModel,
    allDays: List<com.noop.data.DailyMetric>,
    forDay: LocalDate,
    wakeMinute: Int,
    illnessRaised: Boolean,
    age: Int,
    preloaded: List<com.noop.data.SleepSession>? = null,
    withStress: Boolean = true,
    personalStress: Boolean = false,
): WxSleepPlan {
    val zone = ZoneId.systemDefault()
    val key = forDay.toString()
    val days = allDays.filter { it.day <= key }
    val byDay = days.associateBy { it.day }
    val to = forDay.plusDays(1).atTime(14, 0).atZone(zone).toEpochSecond()
    val sessions = preloaded ?: wxSleepSessions(vm, to - 40 * 86_400L, to)

    // Usual bed and wake: medians over the main nights of the last 14 days.
    val mains = (0L until 14L).mapNotNull { wxMainNight(sessions, forDay.minusDays(it)) }
    fun minuteOf(ts: Long) = Instant.ofEpochSecond(ts).atZone(zone).let { it.hour * 60 + it.minute }
    fun median(xs: List<Int>): Int? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    val habitualBed = median(mains.map { SleepNeedPlanner.evening(minuteOf(it.effectiveStartTs)) })?.let { (it + 12 * 60) % 1440 }
    val habitualWake = median(mains.map { minuteOf(it.endTs) })

    val naps = napSleepMinutesByDay(sessions)
    val nightly = days.takeLast(30).mapNotNull { it.totalSleepMin }.filter { it > 0 }
    val debt = SleepDebt.debtSeries(
        series = days.map { it.day to SleepDebt.creditedSleepMin(it.totalSleepMin, naps[it.day] ?: 0.0) },
        needHours = RestScorer.personalizedNeedHours(nightly.map { it / 60.0 }, age),
    ).lastOrNull()?.second

    val effort21 = byDay[key]?.strain?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) }
    val isToday = forDay == logicalDayNow()

    // The day's stress: computed from today's heart rate, or the stored daily level for past days.
    // The day's stress, from the same hourly read the Stress screen shows.
    val stress = if (!withStress) null else runCatching {
        wxStressHours(vm, forDay, personalStress).mapNotNull { it.level }.takeIf { it.isNotEmpty() }?.average()
    }.getOrNull()

    // The wearer's own Effort slope, from free nights (woke at least 30 min after the usual time).
    val free = (1L until 35L).mapNotNull { back ->
        val d = forDay.minusDays(back)
        val night = wxMainNight(sessions, d.plusDays(1)) ?: return@mapNotNull null
        val e = byDay[d.toString()]?.strain?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) } ?: return@mapNotNull null
        val slept = byDay[d.plusDays(1).toString()]?.totalSleepMin ?: return@mapNotNull null
        if (habitualWake == null || minuteOf(night.endTs) < habitualWake + 30) return@mapNotNull null
        e to slept
    }
    val fitted = SleepNeedPlanner.fitEffortSlope(free)

    val plan = SleepNeedPlanner.plan(
        SleepNeedPlanner.Input(
            nightlyAsleepMin = nightly,
            debtMin = debt,
            effort21 = effort21,
            napMin = naps[key] ?: 0.0,
            illness = isToday && illnessRaised,
            efficienciesPct = days.takeLast(14).mapNotNull { wxEfficiencyPct(it.efficiency) },
            wakeMinute = wakeMinute,
            habitualBedMinute = habitualBed,
            stressLevel = stress,
            effortSlope = fitted ?: SleepNeedPlanner.DEFAULT_EFFORT_SLOPE,
            age = age,
        ),
    )
    return WxSleepPlan(plan, personalSlope = fitted != null)
}

/** The plan's parts as short lines for the tonight card and the Alarms screen. */
@Composable
internal fun wxPlanLines(p: WxSleepPlan, is24h: Boolean): List<String> {
    val pl = p.plan
    val parts = buildList {
        add(uiString(R.string.wx_plan_base, wxHoursMinutes(pl.baselineMin)))
        if (pl.effortAddMin >= 1) add(uiString(R.string.wx_plan_effort, wxHoursMinutes(pl.effortAddMin)))
        if (pl.debtAddMin >= 1) add(uiString(R.string.wx_plan_debt, wxHoursMinutes(pl.debtAddMin)))
        if (pl.illnessAddMin >= 1) add(uiString(R.string.wx_plan_illness, wxHoursMinutes(pl.illnessAddMin)))
        if (pl.napSubMin >= 1) add(uiString(R.string.wx_plan_nap, wxHoursMinutes(pl.napSubMin)))
    }
    return buildList {
        add(parts.joinToString(" · "))
        add(uiString(R.string.wx_plan_in_bed, wxHoursMinutes(pl.inBedMin), (pl.efficiency * 100).toInt()))
        if (pl.carriedMin > 0) add(uiString(R.string.wx_plan_carried, wxHoursMinutes(pl.carriedMin.toDouble())))
        if (pl.highStress) add(uiString(R.string.wx_plan_wind_down, wxClock(pl.windDownMinute, is24h)))
        if (p.personalSlope) add(uiString(R.string.wx_plan_personal))
    }
}

/**
 * The planned need (minutes) for each night ending on [dates], each planned on the evening before it,
 * with the same planner as tonight's target. Empty while loading or when the adaptive target is off.
 */
@Composable
internal fun rememberWxPlannedNeeds(vm: AppViewModel, dates: List<LocalDate>): Map<String, Double> {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val enabled = remember { WxSleepPlanPrefs.enabled(context) }
    val profile = remember { ProfileStore.from(context) }
    var needs by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    val key = dates.joinToString(",")
    LaunchedEffect(key, days.size, enabled) {
        if (!enabled || dates.isEmpty()) { needs = emptyMap(); return@LaunchedEffect }
        needs = withContext(Dispatchers.Default) {
            runCatching {
                val zone = ZoneId.systemDefault()
                val last = dates.maxOrNull()!!
                val to = last.plusDays(1).atTime(14, 0).atZone(zone).toEpochSecond()
                val sessions = wxSleepSessions(vm, to - 50 * 86_400L, to)
                dates.associate { d ->
                    d.toString() to buildWxSleepPlan(vm, days, d.minusDays(1), WX_DEFAULT_WAKE_MIN, false, profile.age,
                        preloaded = sessions, withStress = false).plan.needMin
                }
            }.getOrDefault(emptyMap())
        }
    }
    return needs
}
