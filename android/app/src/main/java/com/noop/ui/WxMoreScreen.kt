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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R

// MARK: - Wx More
//
// The trimmed More list: only the screens this fork keeps, in one flat card, plus the strap status
// row on top. Rows push their destination, so Back returns here.

/** The destinations the More page lists, in order, with the accent each row's icon sits on. */
internal val wxMoreItems: List<Pair<Destination, Color>> = listOf(
    Destination.Live to Wx.heart,
    Destination.Workouts to Wx.effort,
    Destination.Stress to Wx.stressLow,
    Destination.InsightsHub to Wx.recoveryHigh,
    Destination.FitnessAge to Wx.heart,
    Destination.Devices to Wx.effort,
    Destination.DataSources to Wx.recoveryHigh,
    Destination.BackupSync to Wx.sleep,
    Destination.SmartAlarm to Wx.recoveryMid,
    Destination.Notifications to Wx.zone4,
    Destination.Settings to Wx.textSoft,
)

@Composable
internal fun WxMoreScreen(vm: AppViewModel, onNavigate: (String) -> Unit) {
    val deviceName by vm.activeDeviceName.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(Wx.canvas)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = BottomBarStyleStore.barHeightForContent() + 24.dp),
    ) {
        Text(uiString(R.string.nav_more).uppercase(), style = WxType.title.copy(fontSize = 28.sp), color = Wx.text,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp))
        Column(Modifier.padding(horizontal = Wx.gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WxCard(onClick = { onNavigate(Destination.Devices.route) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Wx.cardRaised), contentAlignment = Alignment.Center) {
                        Icon(Destination.Devices.icon, null, tint = Wx.text, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(deviceName ?: uiString(R.string.nav_devices), style = WxType.bodyStrong, color = Wx.text)
                        Text(uiString(R.string.wx_strap_status_hint), style = WxType.caption, color = Wx.textMuted)
                    }
                }
            }
            WxCard(padding = 0.dp) {
                wxMoreItems.forEachIndexed { i, (dest, accent) ->
                    WxMoreRow(dest.icon, stringResource(dest.titleRes), accent, showDivider = i > 0) { onNavigate(dest.route) }
                }
            }
            Text(uiString(R.string.wx_on_device_note), style = WxType.caption, color = Wx.textDim,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun WxMoreRow(icon: ImageVector, title: String, accent: Color, showDivider: Boolean, onClick: () -> Unit) {
    Column {
        if (showDivider) Box(Modifier.fillMaxWidth().padding(start = 62.dp).height(1.dp).background(Wx.line))
        Row(
            Modifier.fillMaxWidth().height(Wx.rowHeight).clickable(onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(accent), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Wx.canvas, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(title, style = WxType.body, color = Wx.text, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Wx.textDim, modifier = Modifier.size(18.dp))
        }
    }
}

/** The Fitness Age page: the existing weekly Fitness Age section, on its own page. */
@Composable
internal fun WxFitnessAgeScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val profile = remember { ProfileStore.from(context) }
    ScreenScaffold(title = uiString(R.string.today_card_fitness_age), subtitle = uiString(R.string.wx_fitness_age_subtitle)) {
        FitnessAgeSection(vm = vm, days = days, profile = profile, onOpenSettings = onOpenSettings)
    }
}

/** Icon for the Fitness Age destination. */
internal val WxFitnessAgeIcon: ImageVector get() = Icons.Filled.Cake
