package com.noop.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.noop.data.WorkoutRow
import com.noop.ingest.HealthConnectImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Wx Today
//
// The score-first home screen: three dials (Recovery, Effort, Sleep) that open their own detail,
// tonight's bedtime + alarm, a two-line Effort-vs-Recovery week, today's activities, a stress glance,
// metric tiles with an N-day average that open Trends, and the seven-day journal strip. Reads only what
// the rest of the app already computes: the merged daily rows, workouts, the alarm stores and the
// journal table. Nothing here scores anything.

/** Where a Wx Today tap goes; the shell maps each to a route. */
internal sealed interface WxTodayTarget {
    data class Score(val tab: WxScoreTab) : WxTodayTarget
    data class Trend(val metric: WxTrendMetric) : WxTodayTarget
    data object Alarms : WxTodayTarget
    data object Journal : WxTodayTarget
    data object Workouts : WxTodayTarget
    data object Stress : WxTodayTarget
    data object DataSources : WxTodayTarget
    data object Settings : WxTodayTarget
    data object Live : WxTodayTarget
    data object Devices : WxTodayTarget
}

/** The day window every Today average uses, persisted per install. */
internal object WxAveragePrefs {
    private const val KEY = "wx.today.averageDays"
    val choices = listOf(7, 14, 30, 90)
    fun read(ctx: android.content.Context): Int =
        NoopPrefs.of(ctx).getInt(KEY, 30).let { if (it in choices) it else 30 }
    fun write(ctx: android.content.Context, days: Int) {
        NoopPrefs.of(ctx).edit().putInt(KEY, days).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WxTodayScreen(vm: AppViewModel, onOpen: (WxTodayTarget) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val phoneAlarmOn by vm.phoneAlarmEnabled.collectAsStateWithLifecycle()
    val phoneAlarmMin by vm.phoneAlarmTargetMinutes.collectAsStateWithLifecycle()
    val strapAlarmOn by vm.smartAlarmEnabled.collectAsStateWithLifecycle()
    val strapAlarmMin by vm.smartAlarmMinutes.collectAsStateWithLifecycle()
    val effortScale = remember { UnitPrefs.effortScale(context) }
    val avgDays = remember { WxAveragePrefs.read(context) }
    val is24h = remember { android.text.format.DateFormat.is24HourFormat(context) }
    LaunchedEffect(Unit) { vm.loadWorkouts() }

    // Day navigation: 0 = today, 1 = yesterday, and so on. Every card reads the row for that day.
    var dayOffset by remember { mutableStateOf(0L) }
    val selectedDate = LocalDate.now().minusDays(dayOffset)
    val selectedKey = selectedDate.toString()
    val row = days.lastOrNull { it.day == selectedKey }
    val history = days.filter { it.day <= selectedKey }

    // Journal: which of the seven days ending on the selected day have at least one native answer.
    var loggedDays by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(selectedKey) {
        val from = selectedDate.minusDays(6).toString()
        loggedDays = runCatching { vm.repo.journal(JOURNAL_DEVICE_ID, from, selectedKey) }
            .getOrDefault(emptyList()).map { it.day }.toSet()
    }
    // Stress: the stored daily 0–3 value for the selected day.
    var stressToday by remember { mutableStateOf<Double?>(null) }
    var stressWeek by remember { mutableStateOf<List<Double?>>(emptyList()) }
    LaunchedEffect(selectedKey) {
        val from = selectedDate.minusDays(10).toString()
        val rows = runCatching { vm.repo.metricSeries("my-whoop", "stress", from, selectedKey) }.getOrDefault(emptyList())
        val byDay = rows.associate { it.day to it.value.coerceIn(0.0, 3.0) }
        stressToday = byDay[selectedKey]
        stressWeek = (10 downTo 0).map { byDay[selectedDate.minusDays(it.toLong()).toString()] }
    }

    val recovery = row?.recovery
    val effortStored = row?.strain
    val sleepPerf = row?.let { RestScorer.restFromDaily(it) }

    // Strap link: battery, connection, live HR and history sync, from the one LiveState the BLE client
    // publishes. Pull down (or tap the sync badge) to ask for a sync; the same gate the old Today used.
    val live by vm.live.collectAsStateWithLifecycle()
    val canSync = todayPullToSyncEnabled(live.connected, live.bonded, live.backfilling, live.historyReady)
    val requestScan = rememberRequestScan { vm.connect() }
    val pullState = rememberPullToRefreshState(enabled = {
        todayPullToSyncEnabled(vm.live.value.connected, vm.live.value.bonded, vm.live.value.backfilling, vm.live.value.historyReady)
    })
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            if (canSync) vm.syncNow()
            pullState.endRefresh()
        }
    }
    val onSyncTap: () -> Unit = {
        when {
            !live.connected -> requestScan()
            canSync -> vm.syncNow()
            else -> Unit
        }
    }
    Box(Modifier.fillMaxSize().background(Wx.canvas).nestedScroll(pullState.nestedScrollConnection)) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.canvas)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = BottomBarStyleStore.barHeightForContent() + 24.dp),
    ) {
        // Header: avatar → Settings on the left; live HR, sync and battery on the right.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Wx.cardRaised)
                    .clickable { onOpen(WxTodayTarget.Settings) },
                contentAlignment = Alignment.Center,
            ) {
                ProfileAvatarOrInitials()
            }
            Spacer(Modifier.weight(1f))
            WxStrapStatus(
                live = live,
                is24h = is24h,
                onLive = { onOpen(WxTodayTarget.Live) },
                onSync = onSyncTap,
                onBattery = { onOpen(WxTodayTarget.Devices) },
            )
        }
        // Day navigation.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { dayOffset += 1 }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, uiString(R.string.wx_prev_day), tint = Wx.textMuted)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    (if (dayOffset == 0L) uiString(R.string.nav_today) else wxWeekdayLong(selectedDate)).uppercase(),
                    style = WxType.title, color = Wx.text,
                )
                Text(wxShortDate(selectedDate), style = WxType.caption, color = Wx.textMuted)
            }
            IconButton(onClick = { if (dayOffset > 0) dayOffset -= 1 }, enabled = dayOffset > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, uiString(R.string.wx_next_day),
                    tint = if (dayOffset > 0) Wx.textMuted else Wx.lineStrong)
            }
        }

        // The three dials.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            WxDial(
                label = uiString(R.string.wx_recovery),
                fraction = recovery?.div(100.0),
                color = Wx.recoveryColor(recovery),
                value = recovery?.let { "${it.roundToInt()}" } ?: "–",
                unit = if (recovery != null) "%" else null,
                modifier = Modifier.weight(1f),
                onClick = { onOpen(WxTodayTarget.Score(WxScoreTab.Recovery)) },
            )
            WxDial(
                label = uiString(R.string.wx_effort),
                fraction = effortStored?.div(100.0),
                color = Wx.effort,
                value = effortStored?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: "–",
                unit = null,
                modifier = Modifier.weight(1f),
                onClick = { onOpen(WxTodayTarget.Score(WxScoreTab.Effort)) },
            )
            WxDial(
                label = uiString(R.string.wx_sleep),
                fraction = sleepPerf?.div(100.0),
                color = Wx.sleep,
                value = sleepPerf?.let { "${it.roundToInt()}" } ?: "–",
                unit = if (sleepPerf != null) "%" else null,
                modifier = Modifier.weight(1f),
                onClick = { onOpen(WxTodayTarget.Score(WxScoreTab.Sleep)) },
            )
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = Wx.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Wx.gap),
        ) {
            // Tonight's sleep: recommended bedtime on the left, the alarm on the right.
            val needMin = sleepNeedForDay(row, history.takeLast(30), emptyMap())
            val alarmMin: Int? = when {
                phoneAlarmOn -> phoneAlarmMin
                strapAlarmOn -> strapAlarmMin
                else -> null
            }
            val wakeMin = alarmMin ?: WX_DEFAULT_WAKE_MIN
            val bedMin = ((wakeMin - needMin.roundToInt() - WX_SLEEP_LATENCY_MIN) % 1440 + 1440) % 1440
            WxTonightCard(
                bedtime = wxClock(bedMin, is24h),
                need = wxHoursMinutes(needMin),
                alarm = alarmMin?.let { wxClock(it, is24h) },
                onAlarm = { onOpen(WxTodayTarget.Alarms) },
            )

            // Effort & recovery, last seven days ending on the selected day.
            val week = (6 downTo 0).map { selectedDate.minusDays(it.toLong()) }
            val byKey = history.associateBy { it.day }
            val recSeries = week.map { byKey[it.toString()]?.recovery }
            val effSeries = week.map { d -> byKey[d.toString()]?.strain?.let { UnitFormatter.effortValue(it, effortScale) } }
            val effMax = UnitFormatter.effortValue(100.0, effortScale)
            WxCard(radius = Wx.chartRadius) {
                WxCardHeader(uiString(R.string.wx_effort_and_recovery), uiString(R.string.wx_last_7_days))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WxLegend(Wx.recoveryHigh, uiString(R.string.wx_recovery), recovery?.let { "${it.roundToInt()}%" } ?: "–")
                    WxLegend(Wx.effort, uiString(R.string.wx_effort),
                        effortStored?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: "–")
                }
                Spacer(Modifier.height(12.dp))
                WxTwoLineChart(
                    a = WxSeries(recSeries, Wx.recoveryHigh, 0.0, 100.0) { "${it.roundToInt()}" },
                    b = WxSeries(effSeries, Wx.effort, 0.0, effMax) { String.format(Locale.US, "%.1f", it) },
                    dayLabels = week.map { wxWeekdayShort(it) },
                    description = uiString(R.string.wx_effort_and_recovery_a11y),
                )
            }

            // Activities: the night, then the day's workouts, then the Health Connect sync.
            val dayWorkouts = workouts.filter {
                Instant.ofEpochSecond(it.startTs).atZone(ZoneId.systemDefault()).toLocalDate() == selectedDate
            }.sortedBy { it.startTs }
            WxCard(padding = 16.dp) {
                WxLabel(uiString(R.string.wx_todays_activities), Modifier.padding(bottom = 6.dp))
                row?.totalSleepMin?.let { mins ->
                    WxActivityRow(
                        accent = Wx.sleep,
                        title = uiString(R.string.wx_sleep),
                        subtitle = uiString(R.string.wx_sleep_efficiency, row.efficiency?.roundToInt() ?: 0),
                        trailing = wxHoursMinutes(mins),
                        onClick = { onOpen(WxTodayTarget.Score(WxScoreTab.Sleep)) },
                    )
                }
                dayWorkouts.forEach { w ->
                    WxActivityRow(
                        accent = Wx.effort,
                        title = wxSportName(w.sport),
                        subtitle = wxWorkoutSubtitle(w, is24h),
                        trailing = w.strain?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: "–",
                        onClick = { onOpen(WxTodayTarget.Workouts) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                var syncing by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Wx.cardRaised)
                        .clickable(enabled = !syncing) {
                            syncing = true
                            scope.launch {
                                val outcome = wxSyncHealthConnect(context, vm)
                                syncing = false
                                when (outcome) {
                                    null -> onOpen(WxTodayTarget.DataSources)
                                    else -> Toast.makeText(context, outcome, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null, tint = Wx.recoveryHigh, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        uiString(if (syncing) R.string.wx_syncing else R.string.wx_sync_health_connect),
                        style = WxType.bodyStrong.copy(fontSize = 14.sp), color = Wx.text,
                    )
                }
            }

            // Stress glance.
            WxCard(onClick = { onOpen(WxTodayTarget.Stress) }) {
                WxCardHeader(uiString(R.string.wx_stress_monitor), uiString(R.string.wx_daily_level))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(stressToday?.let { String.format(Locale.US, "%.1f", it) } ?: "–", style = WxType.number, color = Wx.text)
                    Spacer(Modifier.width(8.dp))
                    Text(wxStressBand(stressToday), style = WxType.bodyStrong.copy(fontSize = 14.sp),
                        color = Wx.stressColor(stressToday), modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text(uiString(R.string.wx_of_three), style = WxType.caption, color = Wx.textMuted,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom) {
                    stressWeek.forEachIndexed { i, v ->
                        val f = ((v ?: 0.0) / 3.0).toFloat().coerceIn(0.08f, 1f)
                        val c = Wx.stressColor(v).copy(alpha = if (i == stressWeek.lastIndex) 1f else 0.45f)
                        Box(Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(2.dp)).background(c))
                    }
                }
            }

            // Metric tiles: today's value, change vs the N-day average, and the average itself.
            val window = history.takeLast(avgDays)
            val avgLabel = uiString(R.string.wx_n_day_avg, avgDays)
            val tiles = wxTiles(row, window, avgLabel)
            tiles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Wx.gap)) {
                    pair.forEach { t ->
                        WxMetricTile(
                            label = t.label, value = t.value, unit = t.unit,
                            delta = t.delta, deltaColor = t.deltaColor, averageLine = t.averageLine,
                            onClick = { onOpen(WxTodayTarget.Trend(t.metric)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // Journal week.
            val journalWeek = (6 downTo 0).map { selectedDate.minusDays(it.toLong()) }.map {
                WxJournalDay(wxWeekdayShort(it), it.dayOfMonth, it.toString() in loggedDays, it == selectedDate)
            }
            val loggedCount = journalWeek.count { it.logged }
            WxCard(onClick = { onOpen(WxTodayTarget.Journal) }) {
                WxCardHeader(uiString(R.string.today_section_journal), uiString(R.string.wx_days_logged, loggedCount))
                Spacer(Modifier.height(14.dp))
                WxJournalWeek(journalWeek, uiString(R.string.wx_journal_week_a11y, loggedCount))
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(Wx.cardRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(uiString(R.string.wx_log_today), style = WxType.bodyStrong.copy(fontSize = 14.sp), color = Wx.text)
                }
            }
        }
    }
        if (pullState.progress > 0f || pullState.isRefreshing) {
            PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

/**
 * The strap at a glance: a live heart-rate pill (opens Live), the history-sync badge (spinner while
 * offloading, a check with the last sync time otherwise, a warning on an error, a crossed-out link when
 * disconnected; tap to sync or reconnect) and the battery (opens Devices).
 */
@Composable
private fun WxStrapStatus(
    live: com.noop.ble.LiveState,
    is24h: Boolean,
    onLive: () -> Unit,
    onSync: () -> Unit,
    onBattery: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Live HR.
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Wx.cardRaised).clickable(onClick = onLive)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = uiString(R.string.wx_live), tint = Wx.heart, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(live.heartRate?.takeIf { live.connected }?.toString() ?: uiString(R.string.wx_live),
                style = WxType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Wx.text)
        }
        // Sync.
        val syncing = live.backfilling
        val calculating = !live.backfilling && live.analyzingHistory
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Wx.cardRaised).clickable(onClick = onSync)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                !live.connected -> {
                    Icon(Icons.Filled.BluetoothDisabled, contentDescription = null, tint = Wx.textMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(uiString(R.string.wx_connect), style = WxType.caption, color = Wx.textSoft)
                }
                syncing -> {
                    CircularProgressIndicator(Modifier.size(12.dp), color = Wx.effort, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(uiString(R.string.wx_syncing), style = WxType.caption, color = Wx.textSoft)
                }
                calculating -> {
                    // The download is done; only scoring is running. Show when the strap last synced.
                    CircularProgressIndicator(Modifier.size(12.dp), color = Wx.recoveryHigh, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    val at = live.lastSyncAt?.let {
                        val t = Instant.ofEpochSecond(if (it > 10_000_000_000L) it / 1000 else it).atZone(ZoneId.systemDefault())
                        wxClock(t.hour * 60 + t.minute, is24h)
                    }
                    Text(at ?: uiString(R.string.wx_calculating), style = WxType.caption, color = Wx.textSoft)
                }
                live.lastSyncError != null -> {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Wx.recoveryMid, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(uiString(R.string.wx_sync_retry), style = WxType.caption, color = Wx.textSoft)
                }
                else -> {
                    Icon(Icons.Filled.CloudDone, contentDescription = null, tint = Wx.recoveryHigh, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    val at = live.lastSyncAt?.let {
                        val t = Instant.ofEpochSecond(if (it > 10_000_000_000L) it / 1000 else it).atZone(ZoneId.systemDefault())
                        wxClock(t.hour * 60 + t.minute, is24h)
                    }
                    Text(at ?: uiString(R.string.wx_sync_now), style = WxType.caption, color = Wx.textSoft)
                }
            }
        }
        // Battery.
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Wx.cardRaised).clickable(onClick = onBattery)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pct = live.batteryPct?.takeIf { live.connected }
            if (live.charging == true) {
                Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, tint = Wx.recoveryHigh, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
            } else {
                WxBatteryGlyph(pct)
                Spacer(Modifier.width(5.dp))
            }
            Text(pct?.let { "${it.roundToInt()}%" } ?: "–", style = WxType.caption, color = Wx.textSoft)
        }
    }
}

@Composable
private fun WxBatteryGlyph(pct: Double?) {
    val fill = ((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val color = when {
        pct == null -> Wx.textDim
        pct < 20 -> Wx.recoveryLow
        pct < 40 -> Wx.recoveryMid
        else -> Wx.recoveryHigh
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp, 10.dp).clip(RoundedCornerShape(2.dp)).background(Wx.lineStrong).padding(1.5.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(1.dp)).background(color))
        }
        Box(Modifier.size(2.dp, 4.dp).background(Wx.lineStrong))
    }
}

@Composable
private fun WxDial(
    label: String,
    fraction: Double?,
    color: Color,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WxRing(fraction = fraction, color = color, size = 104.dp, stroke = 8.dp) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = WxType.dial.copy(fontSize = 34.sp), color = Wx.text)
                if (unit != null) Text(unit, style = WxType.dial.copy(fontSize = 18.sp), color = Wx.text,
                    modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        WxLabel(label)
    }
}

@Composable
private fun WxTonightCard(bedtime: String, need: String, alarm: String?, onAlarm: () -> Unit) {
    WxCard(background = Wx.sleepCardTop) {
        WxCardHeader(uiString(R.string.wx_tonights_sleep), labelColor = Wx.sleepSoft)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bedtime, null, tint = Wx.sleepSoft, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiString(R.string.wx_recommended_bedtime), style = WxType.caption, color = Wx.textSoft)
                }
                Text(bedtime, style = WxType.number.copy(fontSize = 36.sp), color = Wx.text)
                Text(uiString(R.string.wx_for_full_sleep, need), style = WxType.caption, color = Wx.textMuted)
            }
            Box(Modifier.width(1.dp).height(72.dp).background(Wx.sleepDivider))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Alarm, null, tint = Wx.recoveryMid, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiString(R.string.wx_alarm), style = WxType.caption, color = Wx.textSoft)
                }
                Text(alarm ?: uiString(R.string.wx_off), style = WxType.number.copy(fontSize = 36.sp),
                    color = if (alarm != null) Wx.text else Wx.textDim)
                Text(
                    uiString(if (alarm != null) R.string.wx_alarm_set else R.string.wx_no_alarm),
                    style = WxType.caption, color = if (alarm != null) Wx.textMuted else Wx.recoveryMid,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(Wx.sleep).clickable(onClick = onAlarm),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Alarm, null, tint = Wx.canvas, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(uiString(if (alarm != null) R.string.wx_change_alarm else R.string.wx_set_alarm),
                style = WxType.bodyStrong.copy(fontSize = 14.sp), color = Wx.canvas)
        }
    }
}

/** A small round avatar slot; the initials are not known to this screen, so it shows a person glyph. */
@Composable
private fun ProfileAvatarOrInitials() {
    Icon(Icons.Filled.Person, contentDescription = uiString(R.string.nav_settings),
        tint = Wx.textSoft, modifier = Modifier.size(22.dp))
}

// MARK: - Tiles

internal data class WxTile(
    val metric: WxTrendMetric,
    val label: String,
    val value: String,
    val unit: String?,
    val delta: String?,
    val deltaColor: Color,
    val averageLine: String,
)

/**
 * Builds the six tiles. [higherIsBetter] decides the delta colour: green when today moved the good
 * way, muted when it did not move, amber otherwise.
 */
@Composable
private fun wxTiles(row: DailyMetric?, window: List<DailyMetric>, avgLabel: String): List<WxTile> {
    fun avg(sel: (DailyMetric) -> Double?): Double? {
        val xs = window.mapNotNull(sel)
        return if (xs.isEmpty()) null else xs.sum() / xs.size
    }
    fun tile(metric: WxTrendMetric, label: String, unit: String?, today: Double?, mean: Double?,
             higherIsBetter: Boolean?, fmt: (Double) -> String): WxTile {
        val d = if (today != null && mean != null) today - mean else null
        val good = when {
            d == null || higherIsBetter == null -> null
            abs(d) < 0.005 * (abs(mean ?: 1.0) + 1.0) -> null
            else -> (d > 0) == higherIsBetter
        }
        val deltaText = d?.let { (if (it >= 0) "▲ " else "▼ ") + fmt(abs(it)) }
        return WxTile(
            metric = metric, label = label, value = today?.let(fmt) ?: "–", unit = unit,
            delta = deltaText,
            deltaColor = when (good) { true -> Wx.recoveryHigh; false -> Wx.recoveryMid; null -> Wx.textMuted },
            averageLine = "$avgLabel " + (mean?.let { fmt(it) + (unit?.let { u -> " $u" } ?: "") } ?: "–"),
        )
    }
    val int0: (Double) -> String = { "${it.roundToInt()}" }
    val one: (Double) -> String = { String.format(Locale.US, "%.1f", it) }
    val thousands: (Double) -> String = { String.format(Locale.US, "%,d", it.roundToInt()) }
    return listOf(
        tile(WxTrendMetric.Hrv, uiString(R.string.wx_hrv), "ms", row?.avgHrv, avg { it.avgHrv }, true, int0),
        tile(WxTrendMetric.RestingHr, uiString(R.string.wx_resting_hr), "bpm", row?.restingHr?.toDouble(),
            avg { it.restingHr?.toDouble() }, false, int0),
        tile(WxTrendMetric.Respiratory, uiString(R.string.wx_resp_rate), "rpm", row?.respRateBpm, avg { it.respRateBpm }, null, one),
        tile(WxTrendMetric.SkinTemp, uiString(R.string.wx_skin_temp), "°C", row?.skinTempDevC, avg { it.skinTempDevC }, null) {
            (if (it >= 0) "+" else "") + String.format(Locale.US, "%.1f", it)
        },
        tile(WxTrendMetric.Steps, uiString(R.string.wx_steps), null, row?.steps?.toDouble(), avg { it.steps?.toDouble() }, true, thousands),
        tile(WxTrendMetric.Calories, uiString(R.string.wx_calories), "kcal", row?.activeKcalEst, avg { it.activeKcalEst }, null, thousands),
    )
}

// MARK: - Health Connect

/**
 * Runs the same Health Connect import the Data Sources screen runs, when access is already granted
 * and nothing new needs asking. Returns the result message to show, or null when the permission flow
 * is needed, in which case the caller opens Data Sources, which owns that flow.
 */
private suspend fun wxSyncHealthConnect(context: android.content.Context, vm: AppViewModel): String? {
    if (HealthConnectImporter.sdkStatus(context) != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) return null
    val granted = runCatching {
        HealthConnectImporter.client(context).permissionController.getGrantedPermissions()
    }.getOrDefault(emptySet())
    HealthConnectImporter.migrateSelectionFromGrants(context, granted)
    val categories = HealthConnectImporter.selectedCategories(context)
    val selected = HealthConnectImporter.permissionsFor(categories)
    if (granted.none { it in selected } || HealthConnectImporter.hasUnaskedPermissions(context, categories)) return null
    val summary = withContext(Dispatchers.IO) {
        runCatching { HealthConnectImporter.import(context, vm.repo, ProfileStore.from(context).heightCm) }.getOrNull()
    }
    vm.loadWorkouts()
    return summary?.message ?: context.getString(R.string.wx_sync_failed)
}

// MARK: - Formatting

internal const val WX_DEFAULT_WAKE_MIN = 7 * 60
internal const val WX_SLEEP_LATENCY_MIN = 15

internal fun wxClock(minutesOfDay: Int, is24h: Boolean): String {
    val m = ((minutesOfDay % 1440) + 1440) % 1440
    val h = m / 60; val mm = m % 60
    return if (is24h) String.format(Locale.US, "%02d:%02d", h, mm)
    else String.format(Locale.US, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, mm, if (h < 12) "AM" else "PM")
}

internal fun wxHoursMinutes(minutes: Double): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

internal fun wxWeekdayShort(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2).replaceFirstChar { it.titlecase(Locale.getDefault()) }

internal fun wxWeekdayLong(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

internal fun wxShortDate(d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

internal fun wxSportName(sport: String): String =
    sport.replace('_', ' ').lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun wxWorkoutSubtitle(w: WorkoutRow, is24h: Boolean): String {
    val zone = ZoneId.systemDefault()
    fun clock(ts: Long): String {
        val t = Instant.ofEpochSecond(ts).atZone(zone)
        return wxClock(t.hour * 60 + t.minute, is24h)
    }
    val hr = w.avgHr?.let { " · $it bpm" } ?: ""
    return "${clock(w.startTs)} – ${clock(w.endTs)}$hr"
}

@Composable
internal fun wxStressBand(level: Double?): String = when {
    level == null -> uiString(R.string.wx_no_data)
    level < 1.0 -> uiString(R.string.wx_low)
    level < 2.0 -> uiString(R.string.wx_medium)
    else -> uiString(R.string.wx_high)
}
