package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.abs
import kotlinx.coroutines.launch
import com.noop.R
import com.noop.analytics.StrainScorer
import com.noop.analytics.DaytimeStress
import com.noop.data.WorkoutRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Wx day heart-rate & stress card
//
// The selected day's heart rate as a line (5-minute means), every workout of that day as a labelled
// band behind it, and the hourly stress level as a coloured strip underneath. Works for any day: the
// window is that day's local midnight to the next (or to now for today). Loaded off the main thread.

internal data class WxDayHeart(
    val points: List<Pair<Long, Double>>,
    val stressHours: List<DaytimeStress.HourPoint>,
    val from: Long,
    val to: Long,
)

@Composable
internal fun WxDayHeartCard(
    vm: AppViewModel,
    date: LocalDate,
    workouts: List<WorkoutRow>,
    liveBpm: Int?,
    onOpenStress: () -> Unit,
) {
    // Shown at once from the last read (fork): the cached day draws immediately, the heart-rate line
    // refreshes first and the stress colours follow, so the card never sits empty while stress loads.
    var data by remember(date) { mutableStateOf(WxDayHeartCache.get(date)) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val personal = remember { NoopPrefs.stressPersonalBaseline(ctx) }
    val hrMax = remember {
        val p = ProfileStore.from(ctx)
        StrainScorer.effortHRmax(p.hrMaxOverride.takeIf { it > 0 }?.toDouble(), p.age.toDouble()).first
            ?: StrainScorer.defaultMaxHR().toDouble()
    }
    // The last 15 days are drawn once in the background and kept on the phone, so opening one is instant.
    LaunchedEffect(Unit) { WxDayHeartCache.prefill(vm, ctx.applicationContext, personal) }
    LaunchedEffect(date) {
        WxDayHeartCache.attach(ctx.applicationContext)
        // Memory, then the phone's copy; a finished day kept after it ended is final.
        val stored = WxDayHeartCache.load(date)
        if (stored != null) data = stored.first
        if (stored != null && stored.second) return@LaunchedEffect
        val fresh = wxComputeDayHeart(vm, date, personal) { partial -> data = partial } ?: return@LaunchedEffect
        data = fresh
        withContext(Dispatchers.IO) { WxDayHeartCache.put(date, fresh) }
    }
    val zone = ZoneId.systemDefault()
    val dayFrom = date.atStartOfDay(zone).toEpochSecond()
    val dayTo = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val dayWorkouts = workouts.filter { it.endTs > dayFrom && it.startTs < dayTo }.sortedBy { it.startTs }
    val scored = data?.stressHours?.mapNotNull { it.level }.orEmpty()
    val stressNow = data?.stressHours?.lastOrNull { it.level != null }?.level

    WxCard(onClick = onOpenStress) {
        WxCardHeader(
            uiString(R.string.wx_heart_and_stress),
            when {
                liveBpm != null && date == LocalDate.now() -> uiString(R.string.wx_bpm_now, liveBpm)
                data?.points?.isNotEmpty() == true ->
                    uiString(R.string.wx_bpm_avg, data!!.points.map { it.second }.average().roundToInt())
                else -> null
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            WxLegend(Wx.stressLow, uiString(R.string.wx_heart_rate_by_stress), "")
            if (dayWorkouts.isNotEmpty()) WxLegend(Wx.effort, uiString(R.string.wx_workouts), "${dayWorkouts.size}")
            if (stressNow != null || scored.isNotEmpty()) {
                WxLegend(Wx.stressColor(stressNow), uiString(R.string.wx_stress_monitor),
                    (stressNow ?: scored.average()).let { String.format(Locale.US, "%.1f", it) })
            }
        }
        Spacer(Modifier.height(10.dp))
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                Text(uiString(R.string.wx_reading_today), style = WxType.caption, color = Wx.textMuted)
            }
        } else if (d.points.size < 2) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                Text(uiString(R.string.wx_no_hr_day), style = WxType.caption, color = Wx.textMuted)
            }
        } else {
            WxDayHeartChart(d, dayWorkouts, uiString(R.string.wx_heart_chart_a11y), hrMax)
        }
    }
}

@Composable
private fun WxDayHeartChart(d: WxDayHeart, workouts: List<WorkoutRow>, description: String, hrMax: Double?) {
    val measurer = rememberTextMeasurer()
    // Touch readout: the x position under the finger, null when nothing is touched.
    var touchX by remember(d) { mutableStateOf<Float?>(null) }
    val zone = ZoneId.systemDefault()
    Canvas(
        Modifier.fillMaxWidth().height(260.dp)
            .semantics { contentDescription = description }
            .pointerInput(d) {
                detectTapGestures(onPress = { p ->
                    touchX = p.x
                    tryAwaitRelease()
                    touchX = null
                })
            }
            .pointerInput(d) {
                detectHorizontalDragGestures(
                    onDragStart = { p -> touchX = p.x },
                    onDragEnd = { touchX = null },
                    onDragCancel = { touchX = null },
                    onHorizontalDrag = { change, _ -> touchX = change.position.x },
                )
            },
    ) {
        val axisStyle = TextStyle(fontSize = 10.sp, color = Wx.textDim)
        val yAxisW = 30.dp.toPx()
        val axisH = 18.dp.toPx()
        val stripH = 10.dp.toPx()
        val gap = 6.dp.toPx()
        val topPad = 14.dp.toPx()
        val plotL = yAxisW
        val plotW = size.width - yAxisW
        val plotH = size.height - axisH - stripH - gap
        val span = (d.to - d.from).coerceAtLeast(1L).toFloat()
        fun x(ts: Long) = plotL + ((ts - d.from) / span * plotW).coerceIn(0f, plotW)
        val minBpm = d.points.minOf { it.second }
        val maxBpm = d.points.maxOf { it.second }
        // Axis in steps of 20 bpm around the day's range.
        val lo = (Math.floor((minBpm - 5) / 20.0) * 20).coerceAtLeast(20.0)
        val hi = (Math.ceil((maxBpm + 5) / 20.0) * 20).coerceAtLeast(lo + 40)
        fun y(v: Double) = (topPad + (plotH - topPad) * (1 - (v - lo) / (hi - lo))).toFloat()

        // Y axis: grid lines and bpm labels, unit on top.
        var tick = lo
        while (tick <= hi + 0.1) {
            val yy = y(tick)
            drawLine(Wx.line, Offset(plotL, yy), Offset(size.width, yy), 1f)
            val t = measurer.measure("${tick.toInt()}", axisStyle)
            drawText(t, topLeft = Offset(0f, yy - t.size.height / 2f))
            tick += 20.0
        }
        val unit = measurer.measure("bpm", axisStyle)
        drawText(unit, topLeft = Offset(0f, 0f))
        // The design: heart-rate zone bands behind the line.
        hrMax?.let { drawWxZoneBands(it, lo, hi, plotL, size.width, ::y, measurer) }

        // Workout bands with their sport name, drawn first so the line sits on top.
        workouts.forEach { w ->
            val x0 = x(w.startTs); val x1 = x(w.endTs).coerceAtLeast(x0 + 3f)
            drawRect(Wx.effort.copy(alpha = 0.22f), Offset(x0, topPad), Size(x1 - x0, plotH - topPad))
            drawLine(Wx.effort, Offset(x0, topPad), Offset(x0, plotH), 1.5f)
            val label = measurer.measure(wxSportName(w.sport), TextStyle(fontSize = 10.sp, color = Wx.effort))
            if (x1 - x0 > 8f || workouts.size < 4) {
                drawText(label, topLeft = Offset((x0 + 3f).coerceAtMost(size.width - label.size.width), topPad))
            }
        }
        // Heart rate line, each stretch coloured by that hour's stress level (grey where the hour was not
        // scored); a gap of more than 20 minutes breaks it.
        fun colorAt(ts: Long): androidx.compose.ui.graphics.Color {
            val h = d.stressHours.lastOrNull { it.startTs <= ts && ts < it.startTs + 3600L }
            return h?.level?.let { Wx.stressColor(it) } ?: Wx.textMuted
        }
        for (i in 1 until d.points.size) {
            val (t0, v0) = d.points[i - 1]; val (t1, v1) = d.points[i]
            if (t1 - t0 > 1200L) continue
            drawLine(colorAt(t1), Offset(x(t0), y(v0)), Offset(x(t1), y(v1)), 1.8.dp.toPx(), StrokeCap.Round)
        }

        // Hourly stress strip.
        val stripTop = plotH + gap
        d.stressHours.forEach { h ->
            val lvl = h.level ?: return@forEach
            val x0 = x(h.startTs); val x1 = x(h.startTs + 3600L)
            drawRoundRect(Wx.stressColor(lvl).copy(alpha = if (h.maskedForActivity) 0.45f else 1f), Offset(x0 + 1f, stripTop), Size((x1 - x0 - 2f).coerceAtLeast(2f), stripH),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
        // X axis: clock time every 6 hours.
        for (hh in listOf(0, 6, 12, 18, 24)) {
            val t = measurer.measure(String.format(Locale.US, "%02d:00", hh % 24), axisStyle)
            val xx = (plotL + plotW * hh / 24f - t.size.width / 2f).coerceIn(plotL, size.width - t.size.width)
            drawText(t, topLeft = Offset(xx, size.height - t.size.height))
        }

        // Touch readout: nearest 5-minute point, a vertical guide, a dot and "HH:mm · N bpm".
        touchX?.let { tx ->
            val ts = d.from + ((tx - plotL) / plotW * span).toLong()
            val p = d.points.minByOrNull { abs(it.first - ts) } ?: return@let
            if (abs(p.first - ts) > 1800L) return@let
            val px = x(p.first); val py = y(p.second)
            drawLine(Wx.textMuted, Offset(px, topPad), Offset(px, plotH), 1f)
            drawCircle(Wx.heart, 5.dp.toPx(), Offset(px, py))
            drawCircle(Wx.canvas, 2.5.dp.toPx(), Offset(px, py))
            val t = Instant.ofEpochSecond(p.first).atZone(zone)
            val label = measurer.measure(
                String.format(Locale.US, "%02d:%02d · %d bpm", t.hour, t.minute, p.second.roundToInt()),
                TextStyle(fontSize = 12.sp, color = Wx.text),
            )
            val padX = 8.dp.toPx(); val padY = 4.dp.toPx()
            val w = label.size.width + padX * 2; val h = label.size.height + padY * 2
            val bx = (px - w / 2).coerceIn(plotL, size.width - w)
            drawRoundRect(Wx.cardRaised, Offset(bx, 0f), Size(w, h), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawText(label, topLeft = Offset(bx + padX, padY))
        }
    }
}

/** The last drawn day per date, so the card opens with its content (fork). */
internal object WxDayHeartCache {
    private val map = object : LinkedHashMap<LocalDate, WxDayHeart>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, WxDayHeart>?) = size > 40
    }
    @Volatile private var dir: java.io.File? = null
    @Volatile private var prefilled = false

    fun attach(ctx: android.content.Context) {
        if (dir == null) dir = java.io.File(ctx.filesDir, "wx_dayheart").apply { mkdirs() }
    }

    @Synchronized fun get(d: LocalDate): WxDayHeart? = map[d]

    /**
     * The day from memory or the phone's copy, with whether it is final: a past day written at least
     * 12 hours after it ended (late strap offloads have landed by then). Today is never final.
     */
    suspend fun load(d: LocalDate): Pair<WxDayHeart, Boolean>? = withContext(Dispatchers.IO) {
        val f = dir?.let { java.io.File(it, "$d.json") }
        val text = f?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        val parsed = text?.let { runCatching { decode(it) }.getOrNull() }
        val mem = get(d)
        val v = mem ?: parsed?.first ?: return@withContext null
        if (mem == null) synchronized(this@WxDayHeartCache) { map[d] = v }
        val final = d != LocalDate.now() && parsed != null && parsed.second >= v.to + 12 * 3600L
        v to final
    }

    fun put(d: LocalDate, v: WxDayHeart) {
        synchronized(this) { map[d] = v }
        val f = dir?.let { java.io.File(it, "$d.json") } ?: return
        runCatching { f.writeText(encode(v, System.currentTimeMillis() / 1000L)) }
    }

    /** Draws the last 15 finished days that are not kept yet, once per app run, in the background. */
    fun prefill(vm: AppViewModel, ctx: android.content.Context, personal: Boolean) {
        if (prefilled) return
        prefilled = true
        attach(ctx)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default).launch {
            val today = LocalDate.now()
            for (back in 1..15) {
                val d = today.minusDays(back.toLong())
                if (load(d)?.second == true) continue
                val v = runCatching { wxComputeDayHeart(vm, d, personal) }.getOrNull() ?: continue
                put(d, v)
            }
        }
    }

    private fun encode(v: WxDayHeart, writtenAt: Long): String {
        val o = org.json.JSONObject()
        o.put("f", v.from); o.put("t", v.to); o.put("w", writtenAt)
        o.put("p", org.json.JSONArray().apply { v.points.forEach { (ts, b) -> put(org.json.JSONArray().put(ts).put(b)) } })
        o.put("s", org.json.JSONArray().apply {
            v.stressHours.forEach { h ->
                put(org.json.JSONObject().put("h", h.hour).put("ts", h.startTs)
                    .put("l", h.level ?: org.json.JSONObject.NULL).put("m", h.meanHr ?: org.json.JSONObject.NULL)
                    .put("a", h.maskedForActivity))
            }
        })
        return o.toString()
    }

    private fun decode(text: String): Pair<WxDayHeart, Long> {
        val o = org.json.JSONObject(text)
        val p = o.getJSONArray("p")
        val points = (0 until p.length()).map { i -> p.getJSONArray(i).let { it.getLong(0) to it.getDouble(1) } }
        val s = o.getJSONArray("s")
        val hours = (0 until s.length()).map { i ->
            val h = s.getJSONObject(i)
            DaytimeStress.HourPoint(h.getInt("h"), h.getLong("ts"),
                if (h.isNull("l")) null else h.getDouble("l"), if (h.isNull("m")) null else h.getDouble("m"),
                null, h.optBoolean("a", false))
        }
        return WxDayHeart(points, hours, o.getLong("f"), o.getLong("t")) to o.optLong("w", 0L)
    }
}

/** One day's 5-minute heart rate and hourly stress; [onPartial] gets the heart rate before stress is in. */
internal suspend fun wxComputeDayHeart(
    vm: AppViewModel, date: LocalDate, personal: Boolean, onPartial: (WxDayHeart) -> Unit = {},
): WxDayHeart? {
    val zone = ZoneId.systemDefault()
    val from = date.atStartOfDay(zone).toEpochSecond()
    val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val buckets = withContext(Dispatchers.Default) {
        runCatching {
            val now = System.currentTimeMillis() / 1000L
            val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, from, minOf(end, now), limit = 200_000)
            // 5-minute means keep the line readable and the draw cheap.
            hr.groupBy { (it.ts - from) / 300L }.toSortedMap()
                .map { (b, xs) -> (from + b * 300L + 150L) to xs.map { it.bpm }.average() }
        }.getOrNull()
    } ?: return null
    val prev = WxDayHeartCache.get(date)
    onPartial(WxDayHeart(buckets, prev?.stressHours.orEmpty(), from, end))
    val stress = withContext(Dispatchers.Default) {
        runCatching { wxStressHours(vm, date, personal) }.getOrDefault(emptyList())
    }
    return WxDayHeart(buckets, stress, from, end)
}
