package com.noop.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.intervals.IntervalsKeyStore
import com.noop.intervals.IntervalsSync
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Connect the opt-in intervals.icu link (fork): athlete ID and personal API key (from intervals.icu,
 * Settings → Developer settings), a test-and-sync button that reports the real outcome, and a
 * disconnect that deletes the key and everything synced.
 */
@Composable
internal fun WxIntervalsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by IntervalsSync.status.collectAsStateWithLifecycle()
    var connected by remember { mutableStateOf(IntervalsKeyStore.enabled(ctx) && IntervalsKeyStore.hasKey(ctx)) }
    var athlete by remember { mutableStateOf(IntervalsKeyStore.athleteId(ctx).takeIf { it != "0" }.orEmpty()) }
    var key by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { IntervalsSync.refreshCounts(ctx) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Wx.text, unfocusedTextColor = Wx.text,
        focusedBorderColor = Wx.effort, unfocusedBorderColor = Wx.lineStrong,
        focusedLabelColor = Wx.effort, unfocusedLabelColor = Wx.textMuted, cursorColor = Wx.effort,
    )
    WxPage(title = uiString(R.string.wx_intervals_icu), onBack = onBack) {
        WxCard {
            Text(uiString(R.string.wx_icu_intro), style = WxType.body, color = Wx.textSoft)
            Spacer(Modifier.height(8.dp))
            Text(uiString(R.string.wx_icu_privacy), style = WxType.caption, color = Wx.textMuted)
        }
        WxCard {
            if (!connected) {
                OutlinedTextField(athlete, { athlete = it }, label = { Text(uiString(R.string.wx_icu_athlete)) },
                    singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(key, { key = it }, label = { Text(uiString(R.string.wx_icu_key)) },
                    singleLine = true, colors = fieldColors, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(uiString(R.string.wx_icu_where), style = WxType.caption, color = Wx.textMuted)
                Spacer(Modifier.height(12.dp))
                WxButton(uiString(R.string.wx_icu_connect), onClick = {
                    if (key.isBlank()) return@WxButton
                    IntervalsKeyStore.save(ctx, athlete.ifBlank { "0" }, key)
                    scope.launch {
                        if (IntervalsSync.sync(ctx)) { connected = true; key = "" }
                        else IntervalsKeyStore.disconnect(ctx) // a refused key is not kept
                    }
                })
            } else {
                WxCardHeader(uiString(R.string.wx_icu_connected), IntervalsKeyStore.athleteId(ctx))
                Spacer(Modifier.height(10.dp))
                val last = status.lastSync.takeIf { it > 0 }?.let {
                    val t = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                    "${wxShortDate(t.toLocalDate())} %02d:%02d".format(t.hour, t.minute)
                } ?: "–"
                Text(uiString(R.string.wx_icu_status, last, status.activities, status.planned), style = WxType.caption, color = Wx.textSoft)
                Spacer(Modifier.height(12.dp))
                WxButton(uiString(if (status.running) R.string.wx_icu_syncing else R.string.wx_icu_sync_now),
                    onClick = { scope.launch { IntervalsSync.sync(ctx) } })
                Spacer(Modifier.height(4.dp))
                WxLinkRow(uiString(R.string.wx_icu_disconnect)) {
                    IntervalsKeyStore.disconnect(ctx); connected = false; IntervalsSync.refreshCounts(ctx)
                }
            }
            status.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = WxType.caption, color = Wx.recoveryLow)
            }
        }
    }
}
