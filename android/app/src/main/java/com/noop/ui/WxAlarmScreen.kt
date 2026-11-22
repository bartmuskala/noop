package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.PuffinExperiment
import java.time.Instant
import java.time.ZoneId

// MARK: - Wx Alarms
//
// The next wake time up top, from the same resolver the Today tonight card uses, with tonight's
// recommended bedtime beside it. Below: the strap buzz alarm (on/off, wake time, days) and the state of
// the phone alarm. The phone alarm's wake window, per-day overrides and permissions stay on the full
// Alarms screen, which also carries the strap's per-day overrides.

@Composable
internal fun WxAlarmScreen(vm: AppViewModel, onBack: () -> Unit, onOpenFull: () -> Unit) {
    val context = LocalContext.current
    val is24h = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val strapOn by vm.smartAlarmEnabled.collectAsStateWithLifecycle()
    val strapMin by vm.smartAlarmMinutes.collectAsStateWithLifecycle()
    val strapDays by vm.smartAlarmWeekdays.collectAsStateWithLifecycle()
    val phoneOn by vm.phoneAlarmEnabled.collectAsStateWithLifecycle()
    val phoneMin by vm.phoneAlarmTargetMinutes.collectAsStateWithLifecycle()
    val phoneDays by vm.phoneAlarmWeekdays.collectAsStateWithLifecycle()
    val phoneOverrides by vm.phoneAlarmDayOverrides.collectAsStateWithLifecycle()
    val phoneWindow by vm.phoneAlarmWindowMinutes.collectAsStateWithLifecycle()
    val strapOverrides by vm.smartAlarmDayOverrides.collectAsStateWithLifecycle()
    var canSchedule by remember { mutableStateOf(vm.canScheduleExactAlarms()) }
    val live = vm.live.collectAsStateWithLifecycle().value
    val probesOn = remember { PuffinExperiment.from(context).isEnabled }
    val next = rememberWxNextWake(vm)
    var planRefresh by remember { mutableStateOf(0) }
    var adaptive by remember { mutableStateOf(WxSleepPlanPrefs.enabled(context)) }
    val sleepPlan = rememberWxSleepPlan(vm, logicalDayNow(), next?.minuteOfDay ?: WX_DEFAULT_WAKE_MIN, planRefresh)

    WxPage(title = uiString(R.string.wx_alarm), onBack = onBack) {
        // Next wake + tonight's bedtime.
        WxCard {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    WxLabel(uiString(R.string.wx_next_alarm))
                    Spacer(Modifier.height(6.dp))
                    Text(next?.let { wxClock(it.minuteOfDay, is24h) } ?: uiString(R.string.wx_off),
                        style = WxType.number.copy(fontSize = 40.sp), color = Wx.text)
                    Text(
                        next?.let {
                            val d = Instant.ofEpochSecond(it.epochSec).atZone(ZoneId.systemDefault()).toLocalDate()
                            uiString(if (it.kind == WxWakeKind.Strap) R.string.wx_strap_buzz else R.string.wx_phone_alarm) +
                                " · " + wxShortDate(d)
                        } ?: uiString(R.string.wx_no_alarm_set),
                        style = WxType.caption, color = Wx.textMuted,
                    )
                }
                val key = today?.day
                val history = days.filter { key == null || it.day <= key }
                val need = sleepPlan?.plan?.needMin ?: sleepNeedForDay(today, history.takeLast(30), emptyMap())
                val bed = sleepPlan?.plan?.bedMinute ?: wxBedtimeMinutes(need, next?.minuteOfDay ?: WX_DEFAULT_WAKE_MIN)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    WxLabel(uiString(R.string.wx_recommended_bedtime), color = Wx.sleepSoft)
                    Spacer(Modifier.height(6.dp))
                    Text(wxClock(bed, is24h),
                        style = WxType.number.copy(fontSize = 40.sp), color = Wx.text)
                    Text(uiString(R.string.wx_sleep_need_value, wxHoursMinutes(need)), style = WxType.caption,
                        color = Wx.textMuted, textAlign = TextAlign.End)
                }
            }
        }

        // The adaptive sleep target: how tonight's need is built, and the switch back to a fixed need.
        WxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiString(R.string.wx_adaptive_target), style = WxType.bodyStrong, color = Wx.text)
                    Text(uiString(R.string.wx_adaptive_target_body), style = WxType.caption, color = Wx.textMuted)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = adaptive,
                    onCheckedChange = { adaptive = it; WxSleepPlanPrefs.setEnabled(context, it); planRefresh++ },
                    colors = noopSwitchColors(),
                )
            }
            sleepPlan?.let { p ->
                Spacer(Modifier.height(10.dp))
                wxPlanLines(p, is24h).forEach { Text(it, style = WxType.caption, color = Wx.textSoft) }
            }
        }

        // Strap buzz alarm: on/off, then the week as it will actually ring.
        WxCard {
            WxAlarmHeader(uiString(R.string.wx_strap_buzz), uiString(R.string.wx_strap_buzz_body), strapOn) { vm.setSmartAlarmEnabled(it) }
            if (strapOn) {
                WxWeekSchedule(
                    defaultMinutes = strapMin, weekdays = strapDays, overrides = strapOverrides, is24h = is24h,
                    onDefault = { vm.setSmartAlarmMinutes(it) },
                    onToggleDay = { dow -> vm.setSmartAlarmWeekdays(toggledSmartAlarmWeekday(dow, strapDays)) },
                    onOverride = { dow, m -> vm.setSmartAlarmDayOverride(dow, m) },
                )
                if (live.whoop5Detected && !probesOn) {
                    Spacer(Modifier.height(10.dp))
                    Text(uiString(R.string.l10n_smart_alarm_screen_your_whoop_5_mg_won_t_75029bae),
                        style = WxType.caption, color = Wx.recoveryMid)
                } else if (!live.bonded) {
                    Spacer(Modifier.height(10.dp))
                    Text(uiString(R.string.wx_connect_to_arm), style = WxType.caption, color = Wx.textMuted)
                }
            }
        }

        // Phone alarm: the same week, plus the light-sleep window that can wake you earlier.
        WxCard {
            WxAlarmHeader(uiString(R.string.wx_phone_alarm), uiString(R.string.wx_phone_alarm_body), phoneOn) { want ->
                if (want && !vm.canScheduleExactAlarms()) {
                    requestExactAlarmAccess(context)
                } else if (!vm.setPhoneAlarmEnabled(want)) {
                    requestExactAlarmAccess(context)
                }
                canSchedule = vm.canScheduleExactAlarms()
            }
            if (phoneOn && !canSchedule) {
                Spacer(Modifier.height(8.dp))
                Text(uiString(R.string.wx_allow_exact_alarms), style = WxType.caption, color = Wx.recoveryMid,
                    modifier = Modifier.clickable { requestExactAlarmAccess(context); canSchedule = vm.canScheduleExactAlarms() })
            }
            if (phoneOn) {
                WxWeekSchedule(
                    defaultMinutes = phoneMin, weekdays = phoneDays, overrides = phoneOverrides, is24h = is24h,
                    onDefault = { vm.setPhoneAlarmTargetMinutes(it) },
                    onToggleDay = { dow -> vm.setPhoneAlarmWeekdays(toggledSmartAlarmWeekday(dow, phoneDays)) },
                    onOverride = { dow, m -> vm.setPhoneAlarmDayOverride(dow, m) },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiString(R.string.wx_wake_window), style = WxType.body, color = Wx.text)
                        Text(uiString(R.string.wx_wake_window_body, phoneWindow), style = WxType.caption, color = Wx.textMuted)
                    }
                    WxMiniStepper("${phoneWindow} min",
                        onMinus = { vm.setPhoneAlarmWindowMinutes((phoneWindow - 5).coerceAtLeast(5)) },
                        onPlus = { vm.setPhoneAlarmWindowMinutes((phoneWindow + 5).coerceAtMost(60)) })
                }
            }
        }
        Text(uiString(R.string.wx_backup_alarm_note), style = WxType.caption, color = Wx.textDim,
            modifier = Modifier.padding(horizontal = 4.dp))
        WxLinkRow(uiString(R.string.wx_more_alarm_options), onOpenFull)
    }
}

@Composable
private fun WxAlarmHeader(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = WxType.bodyStrong, color = Wx.text)
            Text(body, style = WxType.caption, color = Wx.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = noopSwitchColors())
    }
}

/** Calendar.DAY_OF_WEEK, Monday first. */
private val WX_WEEK = intArrayOf(2, 3, 4, 5, 6, 7, 1)

private fun wxDayName(dow: Int): String =
    java.time.DayOfWeek.of(if (dow == 1) 7 else dow - 1)
        .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }

/**
 * The alarm's week exactly as it will ring: the usual time, then one row per day with its own on/off
 * and the time that day really uses (a per-day time is marked and can be reset to the usual one).
 * Same stores as the full Alarms screen, so the two can never show a different week.
 */
@Composable
private fun WxWeekSchedule(
    defaultMinutes: Int,
    weekdays: Set<Int>,
    overrides: Map<Int, Int>,
    is24h: Boolean,
    onDefault: (Int) -> Unit,
    onToggleDay: (Int) -> Unit,
    onOverride: (Int, Int?) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(uiString(R.string.wx_usual_time), style = WxType.body, color = Wx.text)
            Text(uiString(R.string.wx_usual_time_body), style = WxType.caption, color = Wx.textMuted)
        }
        TimeChip(minutes = defaultMinutes, accessibilityLabel = uiString(R.string.wx_usual_time), onPicked = onDefault)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
    WX_WEEK.forEach { dow ->
        val on = smartAlarmWeekdayIsSelected(dow, weekdays)
        val custom = overrides[dow]
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (on) Wx.recoveryHigh else Wx.cardRaised)
                    .clickable { onToggleDay(dow) },
                contentAlignment = Alignment.Center,
            ) {
                if (on) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, null,
                    tint = Wx.canvas, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(wxDayName(dow), style = WxType.body, color = if (on) Wx.text else Wx.textDim, modifier = Modifier.weight(1f))
            if (!on) {
                Text(uiString(R.string.wx_off), style = WxType.caption, color = Wx.textDim)
            } else {
                if (custom != null) {
                    Text(uiString(R.string.wx_reset), style = WxType.caption, color = Wx.textMuted,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOverride(dow, null) }
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                    Spacer(Modifier.width(4.dp))
                }
                TimeChip(
                    minutes = custom ?: defaultMinutes,
                    accessibilityLabel = wxDayName(dow),
                    onPicked = { m -> onOverride(dow, if (m == defaultMinutes) null else m) },
                )
            }
        }
    }
    Text(uiString(R.string.wx_week_schedule_note), style = WxType.caption, color = Wx.textDim,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun WxMiniStepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Wx.cardRaised).clickable(onClick = onMinus),
            contentAlignment = Alignment.Center) { Text("−", style = WxType.bodyStrong, color = Wx.text) }
        Text(value, style = WxType.numberSmall.copy(fontSize = 16.sp), color = Wx.text)
        Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Wx.cardRaised).clickable(onClick = onPlus),
            contentAlignment = Alignment.Center) { Text("+", style = WxType.bodyStrong, color = Wx.text) }
    }
}
