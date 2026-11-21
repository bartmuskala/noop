package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R

// MARK: - Wx page frame
//
// The frame every Wx sub-page shares: a centred condensed title with an optional back arrow on the
// left and one optional action on the right, then a scrolling column on the canvas with the standard
// gutter and card gap.

@Composable
internal fun WxPage(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.canvas)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = BottomBarStyleStore.barHeightForContent() + 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, uiString(R.string.wx_back), tint = Wx.text)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title.uppercase(), style = WxType.title, color = Wx.text, textAlign = TextAlign.Center)
                if (subtitle != null) Text(subtitle, style = WxType.caption, color = Wx.textMuted, textAlign = TextAlign.Center)
            }
            if (action != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { action() }
            else Spacer(Modifier.size(48.dp))
        }
        Column(
            Modifier.padding(horizontal = Wx.gutter).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Wx.gap),
            content = content,
        )
    }
}

/** A full-width filled button in the Wx style. */
@Composable
internal fun WxButton(text: String, onClick: () -> Unit, filled: Boolean = true, color: Color = Wx.effort) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) color else Wx.cardRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = WxType.bodyStrong.copy(fontSize = 15.sp), color = if (filled) Wx.canvas else Wx.text)
    }
}

/** A quiet text link row used to reach a screen's full set of options. */
@Composable
internal fun WxLinkRow(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = WxType.bodyStrong.copy(fontSize = 14.sp), color = Wx.effort)
    }
}

// MARK: - Next wake
//
// The ONE resolver for "when will an alarm wake me next", used by the Today tonight card and the Wx
// Alarms screen so the two cannot disagree. Each alarm is resolved with its own weekday set and per-day
// overrides; the earliest armed one wins. Null when neither alarm is on.

internal enum class WxWakeKind { Phone, Strap }

internal data class WxNextWake(val epochSec: Long, val minuteOfDay: Int, val kind: WxWakeKind)

internal fun wxNextWake(
    phoneOn: Boolean,
    phoneMinutes: Int,
    phoneWeekdays: Set<Int>,
    phoneOverrides: Map<Int, Int>,
    phoneWindowMinutes: Int,
    strapOn: Boolean,
    strapMinutes: Int,
    strapWeekdays: Set<Int>,
    strapOverrides: Map<Int, Int>,
    nowMs: Long = System.currentTimeMillis(),
): WxNextWake? {
    val candidates = ArrayList<WxNextWake>(2)
    if (phoneOn) {
        val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        com.noop.alarm.SmartAlarmScheduler.nextDeadline(
            now = now,
            weekdays = phoneWeekdays,
            windowMinutes = phoneWindowMinutes,
            targetForDay = { dow -> phoneOverrides[dow] ?: phoneMinutes },
        )?.let { c ->
            val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
            candidates.add(WxNextWake(c.timeInMillis / 1000L, m, WxWakeKind.Phone))
        }
    }
    if (strapOn) {
        nextSmartAlarmEpochSec(strapMinutes, strapWeekdays, nowMs, dayOverrides = strapOverrides)?.let { s ->
            val c = java.util.Calendar.getInstance().apply { timeInMillis = s * 1000L }
            val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
            candidates.add(WxNextWake(s, m, WxWakeKind.Strap))
        }
    }
    return candidates.minByOrNull { it.epochSec }
}

/** [wxNextWake] read from the view model's alarm state. */
@Composable
internal fun rememberWxNextWake(vm: AppViewModel): WxNextWake? {
    val phoneOn = vm.phoneAlarmEnabled.collectAsStateWithLifecycleValue()
    val phoneMin = vm.phoneAlarmTargetMinutes.collectAsStateWithLifecycleValue()
    val phoneDays = vm.phoneAlarmWeekdays.collectAsStateWithLifecycleValue()
    val phoneOv = vm.phoneAlarmDayOverrides.collectAsStateWithLifecycleValue()
    val phoneWin = vm.phoneAlarmWindowMinutes.collectAsStateWithLifecycleValue()
    val strapOn = vm.smartAlarmEnabled.collectAsStateWithLifecycleValue()
    val strapMin = vm.smartAlarmMinutes.collectAsStateWithLifecycleValue()
    val strapDays = vm.smartAlarmWeekdays.collectAsStateWithLifecycleValue()
    val strapOv = vm.smartAlarmDayOverrides.collectAsStateWithLifecycleValue()
    return wxNextWake(phoneOn, phoneMin, phoneDays, phoneOv, phoneWin, strapOn, strapMin, strapDays, strapOv)
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleValue(): T =
    collectAsStateWithLifecycle().value
