package com.noop.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.R
import com.noop.intervals.IntervalsParse
import com.noop.intervals.IntervalsStore
import java.time.LocalDate
import kotlin.math.roundToInt

// MARK: - Planned workouts from intervals.icu (fork)
//
// The calendar's WORKOUT (and race) events, as intervals.icu holds them, including the structured
// plan (`workout_doc`) a coach or the TrainingPeaks bridge wrote. A planned session counts as done once
// intervals.icu paired it with an activity, or an activity of the same day exists; until then it is
// shown as planned, never as ridden.

/** Whether a planned [e] has been done: paired by intervals.icu, or ridden that day. */
internal fun icuPlanDone(e: IntervalsParse.Event, acts: List<IntervalsParse.Activity>): Boolean =
    e.pairedActivityId != null || acts.any { it.day == e.day }

/** Today's planned sessions that have not been done yet. */
internal fun icuOpenPlansToday(ctx: Context): List<IntervalsParse.Event> {
    if (!icuIsWorkoutSource(ctx)) return emptyList()
    val today = LocalDate.now().toString()
    val acts = IntervalsStore.activities(ctx)
    return IntervalsStore.events(ctx).filter { it.isWorkout && it.day == today && !icuPlanDone(it, acts) }
}

internal fun icuPlanSubtitle(e: IntervalsParse.Event): String = listOfNotNull(
    e.type?.let { wxSportName(it) },
    e.movingS?.let { wxHoursMinutes(it / 60.0) },
    e.distanceM?.takeIf { it > 0 }?.let { String.format(java.util.Locale.getDefault(), "%.1f km", it / 1000.0) },
).joinToString(" · ")

/** The phone's JOIN app (the training-plan app), found by its launcher name; null when not installed. */
internal fun joinLaunchIntent(ctx: Context): Intent? {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val hit = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList())
        .firstOrNull { it.loadLabel(pm).toString().trim().equals("JOIN", ignoreCase = true) }
        ?: return null
    return pm.getLaunchIntentForPackage(hit.activityInfo.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Today's plan at the top of the Workouts tab: what it is, and that it has not been ridden yet. */
@Composable
internal fun WxTodayPlanCard(events: List<IntervalsParse.Event>, acts: List<IntervalsParse.Activity>, onOpen: (IntervalsParse.Event) -> Unit) {
    val today = LocalDate.now().toString()
    val plans = events.filter { it.isWorkout && it.day == today }
    if (plans.isEmpty()) return
    plans.forEach { e ->
        val done = icuPlanDone(e, acts)
        WxCard(onClick = { onOpen(e) }, modifier = Modifier.border(1.dp,
            (if (done) Wx.recoveryHigh else Wx.effort).copy(alpha = 0.5f), RoundedCornerShape(Wx.cardRadius))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WxLabel(uiString(R.string.wx_icu_today_plan), Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background((if (done) Wx.recoveryHigh else Wx.cardRaised).copy(alpha = if (done) 0.2f else 1f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(uiString(if (done) R.string.wx_icu_done else R.string.wx_icu_not_ridden), style = WxType.caption,
                        color = if (done) Wx.recoveryHigh else Wx.textSoft)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(e.name ?: uiString(R.string.wx_workout), style = WxType.title, color = Wx.text)
            Text(icuPlanSubtitle(e), style = WxType.caption, color = Wx.textMuted)
            val steps = remember(e.id) { IntervalsParse.steps(e.workoutDoc) }
            if (steps.isNotEmpty()) { Spacer(Modifier.height(10.dp)); WxPlanProfile(steps, 56) }
            e.load?.let {
                Spacer(Modifier.height(6.dp))
                Text(uiString(R.string.wx_icu_load_value, it.roundToInt()), style = WxType.caption, color = Wx.textSoft)
            }
        }
    }
}

/** The plan's shape: one bar per step, width by duration, height by power target. */
@Composable
internal fun WxPlanProfile(steps: List<IntervalsParse.Step>, heightDp: Int = 80) {
    // Repeats expanded so the profile shows every interval.
    val flat = remember(steps) {
        val out = ArrayList<IntervalsParse.Step>(); var i = 0
        while (i < steps.size) {
            val s = steps[i]
            if (s.reps > 1 && s.firstOfBlock) {
                val block = steps.subList(i, minOf(i + s.blockSize, steps.size))
                repeat(s.reps) { out.addAll(block) }; i += s.blockSize
            } else { out += s; i++ }
        }
        out
    }
    val total = flat.sumOf { it.durationS ?: 0.0 }
    if (total <= 0) return
    val maxLevel = flat.mapNotNull { it.level }.maxOrNull()
    val a11y = uiString(R.string.wx_icu_plan_profile)
    Canvas(Modifier.fillMaxWidth().height(heightDp.dp).semantics { contentDescription = a11y }) {
        var x = 0f
        flat.forEach { s ->
            val w = (size.width * ((s.durationS ?: 0.0) / total)).toFloat()
            val f = if (maxLevel != null && s.level != null) (s.level / maxLevel).coerceIn(0.15, 1.0) else 0.35
            val h = (size.height * f).toFloat()
            val c = when {
                s.level == null || maxLevel == null -> Wx.lineStrong
                s.level / maxLevel > 0.9 -> Wx.zone5
                s.level / maxLevel > 0.75 -> Wx.zone4
                s.level / maxLevel > 0.6 -> Wx.zone3
                else -> Wx.zone1
            }
            drawRoundRect(c, Offset(x + 0.5f, size.height - h), Size((w - 1f).coerceAtLeast(1f), h), CornerRadius(2f))
            x += w
        }
    }
}

@Composable
internal fun WxPlannedDetail(e: IntervalsParse.Event, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val acts = remember { IntervalsStore.activities(ctx) }
    val done = icuPlanDone(e, acts)
    val d = runCatching { LocalDate.parse(e.day) }.getOrNull()
    val steps = remember(e.id) { IntervalsParse.steps(e.workoutDoc) }
    WxPage(title = e.name ?: uiString(R.string.wx_workout),
        subtitle = listOfNotNull(d?.let { wxShortDate(it) }, uiString(if (done) R.string.wx_icu_done else R.string.wx_icu_not_ridden)).joinToString(" · "),
        onBack = onBack) {
        WxCard {
            Row(Modifier.fillMaxWidth()) {
                WxStat(uiString(R.string.wx_duration), e.movingS?.let { wxHoursMinutes(it / 60.0) } ?: "–", Modifier.weight(1f))
                WxStat(uiString(R.string.wx_icu_load), e.load?.roundToInt()?.toString() ?: "–", Modifier.weight(1f))
                WxStat(uiString(R.string.wx_distance), e.distanceM?.takeIf { it > 0 }?.let { String.format(java.util.Locale.getDefault(), "%.1f km", it / 1000.0) } ?: "–", Modifier.weight(1f))
            }
        }
        if (steps.isNotEmpty()) {
            WxCard(padding = 16.dp) {
                WxCardHeader(uiString(R.string.wx_icu_plan))
                Spacer(Modifier.height(10.dp))
                WxPlanProfile(steps, 90)
                Spacer(Modifier.height(12.dp))
                steps.forEach { s ->
                    if (s.reps > 1 && s.firstOfBlock) {
                        Text(uiString(R.string.wx_icu_repeat, s.reps), style = WxType.bodyStrong.copy(fontSize = 13.sp), color = Wx.effort,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(start = if (s.reps > 1) 12.dp else 0.dp, top = 3.dp, bottom = 3.dp)) {
                        Text(s.durationS?.let { wxStepDuration(it) } ?: s.distanceM?.let { "${it.roundToInt()} m" } ?: "",
                            style = WxType.bodyStrong.copy(fontSize = 13.sp), color = Wx.text, modifier = Modifier.width(72.dp))
                        Column(Modifier.weight(1f)) {
                            s.target?.let { Text(it, style = WxType.body.copy(fontSize = 13.sp), color = Wx.textSoft) }
                            s.text?.let { Text(it, style = WxType.caption, color = Wx.textMuted) }
                        }
                    }
                }
            }
        }
        e.description?.takeIf { it.isNotBlank() }?.let {
            WxCard {
                WxCardHeader(uiString(R.string.wx_icu_notes))
                Spacer(Modifier.height(8.dp))
                Text(it, style = WxType.body.copy(fontSize = 14.sp), color = Wx.textSoft)
            }
        }
        val join = remember { joinLaunchIntent(ctx) }
        if (join != null) WxButton(uiString(R.string.wx_icu_open_join), onClick = { runCatching { ctx.startActivity(join) } })
        else Text(uiString(R.string.wx_icu_join_missing), style = WxType.caption, color = Wx.textMuted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

private fun wxStepDuration(s: Double): String {
    val t = s.roundToInt()
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
    else if (t % 60 == 0) "${t / 60} min" else "%d:%02d".format(t / 60, t % 60)
}
