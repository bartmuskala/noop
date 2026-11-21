package com.noop.ui

import androidx.compose.foundation.background
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
    val live = vm.live.collectAsStateWithLifecycle().value
    val probesOn = remember { PuffinExperiment.from(context).isEnabled }
    val next = rememberWxNextWake(vm)

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
                val need = sleepNeedForDay(today, history.takeLast(30), emptyMap())
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    WxLabel(uiString(R.string.wx_recommended_bedtime), color = Wx.sleepSoft)
                    Spacer(Modifier.height(6.dp))
                    Text(wxClock(wxBedtimeMinutes(need, next?.minuteOfDay ?: WX_DEFAULT_WAKE_MIN), is24h),
                        style = WxType.number.copy(fontSize = 40.sp), color = Wx.text)
                    Text(uiString(R.string.wx_sleep_need_value, wxHoursMinutes(need)), style = WxType.caption,
                        color = Wx.textMuted, textAlign = TextAlign.End)
                }
            }
        }

        // Strap buzz alarm.
        WxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiString(R.string.wx_strap_buzz), style = WxType.bodyStrong, color = Wx.text)
                    Text(uiString(R.string.wx_strap_buzz_body), style = WxType.caption, color = Wx.textMuted)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = strapOn,
                    onCheckedChange = { vm.setSmartAlarmEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Wx.effort, checkedThumbColor = Wx.text),
                )
            }
            if (strapOn) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(uiString(R.string.wx_wake_at), style = WxType.body, color = Wx.text, modifier = Modifier.weight(1f))
                    TimeChip(minutes = strapMin, accessibilityLabel = uiString(R.string.wx_wake_at), onPicked = { vm.setSmartAlarmMinutes(it) })
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
                Spacer(Modifier.height(12.dp))
                AlarmWeekdayPicker(
                    selected = strapDays,
                    onToggle = { dow -> vm.setSmartAlarmWeekdays(toggledSmartAlarmWeekday(dow, strapDays)) },
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

        // Phone alarm: state only; its window and permissions live on the full screen.
        WxCard(onClick = onOpenFull) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiString(R.string.wx_phone_alarm), style = WxType.bodyStrong, color = Wx.text)
                    Text(uiString(R.string.wx_phone_alarm_body), style = WxType.caption, color = Wx.textMuted)
                }
                Text(if (phoneOn) wxClock(phoneMin, is24h) else uiString(R.string.wx_off),
                    style = WxType.numberSmall, color = if (phoneOn) Wx.text else Wx.textMuted)
            }
        }
        Text(uiString(R.string.wx_backup_alarm_note), style = WxType.caption, color = Wx.textDim,
            modifier = Modifier.padding(horizontal = 4.dp))
        WxLinkRow(uiString(R.string.wx_all_alarm_options), onOpenFull)
    }
}
