package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.BehaviorEffect
import com.noop.analytics.EffectRanker
import com.noop.data.DailyMetric
import com.noop.data.JournalEntry
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Wx Journal + What moves you
//
// Both screens read the same journal history: imported WHOOP answers unioned with NOOP's own
// ("noop-journal", which wins per day and question), through the same merge the classic Insights
// screen uses. Effects come from the shared EffectRanker, so the numbers match the classic screens.

internal data class WxJournalHistory(
    val native: List<JournalEntry>,
    val behaviours: Map<String, Set<String>>,
    val controls: Map<String, Set<String>>,
    val importedQuestions: List<String>,
)

internal suspend fun wxLoadJournalHistory(vm: AppViewModel): WxJournalHistory {
    val imported = vm.repo.journal("my-whoop", "0000-01-01", "9999-12-31")
    val native = vm.repo.journal(JOURNAL_DEVICE_ID, "0000-01-01", "9999-12-31")
    val merged = mergeJournalEntries(imported, native)
    val yes = HashMap<String, MutableSet<String>>()
    val no = HashMap<String, MutableSet<String>>()
    for (e in merged) (if (e.answeredYes) yes else no).getOrPut(e.question) { mutableSetOf() }.add(e.day)
    return WxJournalHistory(native, yes, no, imported.map { it.question }.distinct())
}

/** The outcomes the effects can be read against, matching the classic screen's outcome set. */
internal enum class WxMoveOutcome(val outcomeName: String, val color: Color, val pick: (DailyMetric) -> Double?) {
    Recovery("Charge", Wx.recoveryHigh, { it.recovery }),
    Sleep("Rest", Wx.sleep, { it.efficiency }),
    Hrv("HRV", Wx.effort, { it.avgHrv }),
}

internal fun wxRankEffects(history: WxJournalHistory, days: List<DailyMetric>, outcome: WxMoveOutcome): List<BehaviorEffect> {
    val byDay = days.mapNotNull { d -> outcome.pick(d)?.let { d.day to it } }.toMap()
    if (byDay.isEmpty()) return emptyList()
    return EffectRanker.rankNoLag(history.behaviours, history.controls, byDay, outcome.outcomeName)
}

/** Enough days on both sides to show an effect as a bar rather than as "still learning". */
internal const val WX_MIN_EFFECT_DAYS = 5

private fun BehaviorEffect.readable(): Boolean =
    nWith >= WX_MIN_EFFECT_DAYS && nWithout >= WX_MIN_EFFECT_DAYS && pctChange != null

private fun groupIcon(g: JournalGroup): ImageVector = when (g) {
    JournalGroup.Nutrition -> Icons.Filled.Restaurant
    JournalGroup.Supplements -> Icons.Filled.Medication
    JournalGroup.Lifestyle -> Icons.Filled.Bedtime
    JournalGroup.Health -> Icons.Filled.Favorite
    JournalGroup.Behaviour -> Icons.Filled.Psychology
    JournalGroup.Other -> Icons.Filled.MoreHoriz
}

@Composable
internal fun WxJournalScreen(vm: AppViewModel, onOpenMoves: () -> Unit, onEditQuestions: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var seq by remember { mutableStateOf(0) }
    var dayOffset by remember { mutableStateOf(0L) }
    val pending by vm.pendingJournalDayOffset.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { pending?.let { dayOffset = it.coerceIn(0L, 6L); vm.requestJournalDay(null) } }
    val catalog = remember { loadJournalCatalogItems(ctx) }
    var history by remember { mutableStateOf<WxJournalHistory?>(null) }
    var answers by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var numeric by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }

    LaunchedEffect(seq, dayOffset) {
        var h = wxLoadJournalHistory(vm)
        val key = journalDayKey(dayOffset)
        var a = h.native.filter { it.day == key }.associate { it.question to it.answeredYes }
        // Same as the classic journal: opening an empty today carries last night's answers over as real
        // rows, so a recurring pattern is one tap to confirm and the day counts as logged.
        if (a.isEmpty() && dayOffset == 0L) {
            val y = h.native.filter { it.day == journalDayKey(1L) }.associate { it.question to it.answeredYes }
            if (y.isNotEmpty()) {
                vm.repo.upsertJournal(y.map { (q, v) -> JournalEntry(JOURNAL_DEVICE_ID, key, q, v) })
                h = wxLoadJournalHistory(vm)
                a = y
            }
        }
        history = h
        answers = a
        numeric = h.native.filter { it.day == key && it.numericValue != null }.associate { it.question to it.numericValue!! }
    }

    fun answer(q: String, yes: Boolean?) {
        scope.launch {
            val key = journalDayKey(dayOffset)
            if (yes == null) vm.repo.deleteJournalEntry(JOURNAL_DEVICE_ID, key, q)
            else vm.repo.upsertJournal(listOf(JournalEntry(JOURNAL_DEVICE_ID, key, q, yes)))
            seq++
        }
    }
    fun setNumber(q: String, value: Double?) {
        scope.launch {
            val key = journalDayKey(dayOffset)
            if (value == null) vm.repo.deleteJournalEntry(JOURNAL_DEVICE_ID, key, q)
            else vm.repo.upsertJournal(listOf(JournalEntry(JOURNAL_DEVICE_ID, key, q, answeredYes = true, numericValue = value)))
            seq++
        }
    }

    val today = LocalDate.now()
    val logged = history?.native?.map { it.day }?.toSet().orEmpty()
    WxPage(
        title = uiString(R.string.wx_journal),
        onBack = null,
        action = {
            IconButton(onClick = onEditQuestions) {
                Icon(Icons.Filled.Edit, uiString(R.string.wx_edit_questions), tint = Wx.textMuted)
            }
        },
    ) {
        // This week: seven days ending today; tap a day to log it.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (back in 6 downTo 0) {
                val d = today.minusDays(back.toLong())
                val done = d.toString() in logged
                val selected = back.toLong() == dayOffset
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { dayOffset = back.toLong() }.padding(2.dp),
                ) {
                    Text(wxWeekdayShort(d), style = WxType.caption.copy(fontSize = 11.sp), color = Wx.textMuted)
                    val ring = if (selected) Wx.effort else if (done) Color.Transparent else Wx.lineStrong
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (done) Wx.recoveryHigh else Color.Transparent)
                            .border(2.dp, ring, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Icon(Icons.Filled.Check, null, tint = Wx.canvas, modifier = Modifier.size(20.dp))
                        else Text("${d.dayOfMonth}", style = WxType.caption.copy(fontSize = 13.sp), color = Wx.textSoft)
                    }
                }
            }
        }
        val streak = run {
            var n = 0
            var d = if (today.toString() in logged) today else today.minusDays(1)
            while (d.toString() in logged) { n++; d = d.minusDays(1) }
            n
        }
        Text(
            buildString {
                append(uiString(R.string.wx_streak_days, streak))
                if (today.toString() !in logged) append(" · ").append(uiString(R.string.wx_log_today_to_keep))
            },
            style = WxType.caption.copy(fontSize = 13.sp), color = Wx.textMuted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )

        val selDate = today.minusDays(dayOffset)
        WxLabel(
            (if (dayOffset == 0L) uiString(R.string.nav_today) + " · " else "") + wxShortDate(selDate),
            Modifier.padding(start = 4.dp, top = 8.dp),
        )
        val items = resolveJournalItems(history?.importedQuestions.orEmpty(), catalog, includeHidden = false)
            .sortedWith(compareBy({ JournalGroup.displayOrder.indexOf(it.group) }, { it.sortIndex }, { it.display }))
        WxCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                items.forEachIndexed { i, item ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Wx.cardRaised), contentAlignment = Alignment.Center) {
                            Icon(groupIcon(item.group), null, tint = Wx.textSoft, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(item.display, style = WxType.body.copy(fontSize = 14.sp), color = Wx.text, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        when (item.kind) {
                            is JournalKind.Numeric -> {
                                val v = numeric[item.canonical]
                                WxStepper(
                                    value = v,
                                    unit = (item.kind as JournalKind.Numeric).unit,
                                    onMinus = { setNumber(item.canonical, v?.let { if (it - 1.0 <= 0.0) null else it - 1.0 }) },
                                    onPlus = { setNumber(item.canonical, (v ?: 0.0) + 1.0) },
                                )
                            }
                            else -> {
                                val a = answers[item.canonical]
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WxAnswerPill(uiString(R.string.wx_yes), a == true, Wx.recoveryHigh) {
                                        answer(item.canonical, if (a == true) null else true)
                                    }
                                    WxAnswerPill(uiString(R.string.wx_no), a == false, Wx.text) {
                                        answer(item.canonical, if (a == false) null else false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // What the journal has shown so far, linking to the full picture.
        val h = history
        val best = remember(h, days) {
            if (h == null) null
            else wxRankEffects(h, days, WxMoveOutcome.Recovery).filter { it.readable() }
                .maxByOrNull { abs(it.pctChange ?: 0.0) }
        }
        WxCard(onClick = onOpenMoves) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        uiString(if (best != null) R.string.wx_journal_working else R.string.wx_what_moves_you),
                        style = WxType.bodyStrong, color = Wx.text,
                    )
                    Text(
                        if (best != null) uiString(
                            R.string.wx_linked_to_recovery,
                            journalDisplayName(catalog, best.behavior),
                            wxSignedPct(best.pctChange ?: 0.0),
                        ) else uiString(R.string.wx_keep_logging),
                        style = WxType.caption, color = Wx.textMuted,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Wx.textMuted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, null, tint = Wx.textMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(uiString(R.string.wx_saved_automatically), style = WxType.caption, color = Wx.textMuted)
        }
    }
}

internal fun wxSignedPct(p: Double): String {
    val r = p.roundToInt()
    return (if (r > 0) "+" else "") + "$r%"
}

@Composable
private fun WxAnswerPill(text: String, selected: Boolean, selectedColor: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp, 36.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) selectedColor else Wx.cardRaised)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = WxType.bodyStrong.copy(fontSize = 13.sp), color = if (selected) Wx.canvas else Wx.textSoft)
    }
}

@Composable
private fun WxStepper(value: Double?, unit: String?, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WxRoundButton("−", onMinus)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
            Text(value?.let { if (it % 1.0 == 0.0) "${it.toInt()}" else String.format(Locale.US, "%.1f", it) } ?: "0",
                style = WxType.numberSmall, color = Wx.text)
            if (!unit.isNullOrBlank()) Text(unit, style = WxType.caption.copy(fontSize = 10.sp), color = Wx.textMuted)
        }
        WxRoundButton("+", onPlus)
    }
}

@Composable
private fun WxRoundButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Wx.cardRaised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = WxType.bodyStrong.copy(fontSize = 18.sp), color = Wx.text) }
}

// MARK: - What moves you
//
// One card per behaviour with a centred impact bar: the change runs right in green when the outcome is
// better on "yes" days, left in orange when it is worse, both scaled to the largest effect shown. The
// figures are the shared EffectRanker's, so they match the classic analysis screen.

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WxMovesScreen(vm: AppViewModel, onBack: () -> Unit, onOpenMore: () -> Unit) {
    val ctx = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val catalog = remember { loadJournalCatalogItems(ctx) }
    var history by remember { mutableStateOf<WxJournalHistory?>(null) }
    LaunchedEffect(Unit) { history = wxLoadJournalHistory(vm) }
    var sel by remember { mutableStateOf(0) }
    val outcome = WxMoveOutcome.entries[sel]
    val labels = listOf(uiString(R.string.wx_recovery), uiString(R.string.wx_sleep), uiString(R.string.wx_hrv))
    val h = history
    val ranked = remember(h, days, outcome) { if (h == null) emptyList() else wxRankEffects(h, days, outcome) }
    val readable = ranked.filter { it.readable() }.sortedByDescending { it.pctChange ?: 0.0 }
    val learning = ranked.filterNot { it.readable() }.sortedByDescending { it.nWith }
    val loggedDays = h?.let { (it.behaviours.values.flatten() + it.controls.values.flatten()).toSet().size } ?: 0
    val scaleMax = (readable.maxOfOrNull { abs(it.pctChange ?: 0.0) } ?: 0.0).coerceAtLeast(10.0)

    WxPage(title = uiString(R.string.wx_what_moves_you), onBack = onBack) {
        Text(uiString(R.string.wx_moves_heading), style = WxType.title.copy(fontSize = 26.sp, lineHeight = 30.sp),
            color = Wx.text, modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
        Text(
            uiString(R.string.wx_moves_intro, labels[sel], loggedDays),
            style = WxType.body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Wx.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        WxSegmented(labels, sel, { sel = it }, selectedColor = outcome.color)

        Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(uiString(R.string.wx_your_behaviours).uppercase(), style = WxType.label, color = Wx.textMuted)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Wx.lineStrong))
        }

        when {
            h == null -> Text(uiString(R.string.wx_reading_today), style = WxType.caption, color = Wx.textMuted)
            readable.isEmpty() -> WxCard { Text(uiString(R.string.wx_keep_logging), style = WxType.body, color = Wx.textSoft) }
            else -> WxCard(padding = 12.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▼ " + uiString(R.string.wx_hurts).uppercase(), style = WxType.label, color = Wx.recoveryMid)
                    Text(uiString(R.string.wx_pct_impact).uppercase(), style = WxType.label, color = Wx.text,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text(uiString(R.string.wx_helps).uppercase() + " ▲", style = WxType.label, color = Wx.recoveryHigh)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    readable.forEach { e -> WxImpactCard(e, journalDisplayName(catalog, e.behavior), scaleMax) }
                }
            }
        }
        if (learning.isNotEmpty()) {
            WxCard {
                WxLabel(uiString(R.string.wx_still_learning))
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    learning.forEach { e ->
                        Text(
                            uiString(R.string.wx_n_of_m_days, journalDisplayName(catalog, e.behavior), e.nWith, e.nWith + e.nWithout),
                            style = WxType.caption.copy(fontSize = 13.sp), color = Wx.textSoft,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Wx.cardRaised)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        WxLinkRow(uiString(R.string.wx_more_analysis), onOpenMore)
    }
}

@Composable
private fun WxImpactCard(e: BehaviorEffect, name: String, scaleMax: Double) {
    val pct = e.pctChange ?: 0.0
    val color = if (pct >= 0) Wx.recoveryHigh else Wx.recoveryMid
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Wx.cardRaised).padding(14.dp),
    ) {
        Text(name, style = WxType.bodyStrong.copy(fontSize = 16.sp), color = Wx.text)
        Text(
            uiString(R.string.wx_yes_no_counts, e.nWith, e.nWithout) + if (e.significant) " · " + uiString(R.string.wx_consistent) else "",
            style = WxType.caption.copy(fontSize = 13.sp), color = Wx.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.weight(1f).height(14.dp)) {
                val h = size.height
                val trackH = 8.dp.toPx()
                val top = (h - trackH) / 2
                val r = androidx.compose.ui.geometry.CornerRadius(trackH / 2)
                // Hatched track.
                drawRoundRect(Wx.inset, androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, trackH), r)
                clipRect(top = top, bottom = top + trackH) {
                    var x = -trackH
                    while (x < size.width) {
                        drawLine(Wx.lineStrong, androidx.compose.ui.geometry.Offset(x, top + trackH),
                            androidx.compose.ui.geometry.Offset(x + trackH, top), 1.5f)
                        x += 6.dp.toPx()
                    }
                }
                val mid = size.width / 2
                val len = (abs(pct) / scaleMax).coerceIn(0.03, 1.0).toFloat() * mid
                val x0 = if (pct >= 0) mid else mid - len
                drawRoundRect(if (e.significant) color else color.copy(alpha = 0.6f),
                    androidx.compose.ui.geometry.Offset(x0, top), androidx.compose.ui.geometry.Size(len, trackH), r)
                drawCircle(Wx.canvas, h / 2, androidx.compose.ui.geometry.Offset(mid, h / 2))
                drawCircle(Wx.text, h / 2 - 2.dp.toPx(), androidx.compose.ui.geometry.Offset(mid, h / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                drawCircle(Wx.text, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(mid, h / 2))
            }
            Text(wxSignedPct(pct), style = WxType.numberSmall.copy(fontSize = 20.sp), color = color,
                textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
        }
    }
}
