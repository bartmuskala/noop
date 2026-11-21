package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** One period average drawn as a flat segment over the points [from]..[to], with its change. */
internal data class WxTrendSegment(val from: Int, val to: Int, val value: Double, val changePct: Double?)

/** The middle half (25th to 75th percentile) of [values], or null with fewer than five values. */
internal fun wxTypicalRange(values: List<Double>): Pair<Double, Double>? {
    if (values.size < 5) return null
    val s = values.sorted()
    fun q(p: Double): Double {
        val pos = p * (s.size - 1)
        val lo = floor(pos).toInt(); val hi = ceil(pos).toInt()
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }
    return q(0.25) to q(0.75)
}

/**
 * A metric over time in the WHOOP style: a y axis with four labelled steps, the typical range as a
 * shaded band, the line with a soft fill below it, values printed on the points when there are few,
 * and optional period-average segments (a flat line per month with its change) over a faint daily line.
 */
@Composable
internal fun WxTrendChart(
    values: List<Double?>,
    labels: List<String>,
    color: Color,
    format: (Double) -> String,
    typical: Pair<Double, Double>?,
    description: String,
    segments: List<WxTrendSegment> = emptyList(),
    higherIsBetter: Boolean? = null,
    height: Dp = 260.dp,
    fixedMin: Double? = null,
    fixedMax: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    val present = values.filterNotNull()
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val axisStyle = TextStyle(fontSize = 11.sp, color = Wx.textDim)
        val axisW = 34.dp.toPx(); val labelH = 22.dp.toPx(); val top = 24.dp.toPx()
        val plotL = axisW; val plotR = size.width - 8.dp.toPx()
        val plotB = size.height - labelH
        val n = values.size.coerceAtLeast(1)
        val all = present + listOfNotNull(typical?.first, typical?.second) + segments.map { it.value }
        if (all.isEmpty()) return@Canvas
        var lo = fixedMin ?: all.minOrNull()!!
        var hi = fixedMax ?: all.maxOrNull()!!
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val padV = (hi - lo) * 0.25
        if (fixedMin == null) lo -= padV
        if (fixedMax == null) hi += padV
        fun x(i: Int) = if (n > 1) plotL + (plotR - plotL) * i / (n - 1) else (plotL + plotR) / 2
        fun y(v: Double) = (top + (plotB - top) * (1 - (v - lo) / (hi - lo))).toFloat()

        // Y axis: four steps.
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            val yy = y(v)
            drawLine(Wx.line, Offset(plotL, yy), Offset(size.width, yy), 1f)
            val t = measurer.measure(format(v), axisStyle)
            drawText(t, topLeft = Offset(0f, yy - t.size.height / 2f))
        }
        // Typical range band.
        typical?.let { (a, b) ->
            drawRect(Wx.cardRaised.copy(alpha = 0.85f), Offset(plotL - 8f, y(b)), Size(size.width - plotL + 8f, y(a) - y(b)))
        }
        // Soft fill under the line, then the line.
        val faint = segments.isNotEmpty()
        val line = Path(); val fill = Path()
        var started = false; var lastX = 0f; var firstX = 0f
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) { line.moveTo(x(i), y(v)); fill.moveTo(x(i), plotB); fill.lineTo(x(i), y(v)); firstX = x(i); started = true }
            else { line.lineTo(x(i), y(v)); fill.lineTo(x(i), y(v)) }
            lastX = x(i)
        }
        if (started) {
            fill.lineTo(lastX, plotB); fill.lineTo(firstX, plotB); fill.close()
            if (!faint) drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent), top, plotB))
            drawPath(line, if (faint) color.copy(alpha = 0.35f) else color,
                style = Stroke((if (faint) 1.2 else 2.5).dp.toPx(), cap = StrokeCap.Round))
        }
        // Points and values when the series is short.
        val showValues = !faint && n <= 10
        if (showValues) {
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val p = Offset(x(i), y(v))
                drawCircle(Wx.canvas, 5.dp.toPx(), p)
                drawCircle(color, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                val t = measurer.measure(format(v), TextStyle(fontSize = 14.sp, color = color, fontFeatureSettings = "tnum"))
                val bx = (p.x - t.size.width / 2f - 4.dp.toPx()).coerceIn(0f, size.width - t.size.width - 8.dp.toPx())
                drawRoundRect(Wx.cardRaised, Offset(bx, p.y - t.size.height - 12.dp.toPx()),
                    Size(t.size.width + 8.dp.toPx(), t.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawText(t, topLeft = Offset(bx + 4.dp.toPx(), p.y - t.size.height - 11.dp.toPx()))
            }
        } else if (started && !faint) {
            // Long series: mark only the latest point with its value.
            val i = values.indexOfLast { it != null }
            val v = values[i]!!
            val p = Offset(x(i), y(v))
            drawCircle(Wx.canvas, 5.dp.toPx(), p)
            drawCircle(color, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            val t = measurer.measure(format(v), TextStyle(fontSize = 14.sp, color = color))
            drawText(t, topLeft = Offset((p.x - t.size.width / 2f).coerceAtMost(size.width - t.size.width), p.y - t.size.height - 8.dp.toPx()))
        }
        // Period-average segments with their change.
        segments.forEach { s ->
            val good = s.changePct?.let { c -> if (abs(c) < 1 || higherIsBetter == null) null else (c > 0) == higherIsBetter }
            val c = when (good) { true -> Wx.recoveryHigh; false -> Wx.recoveryMid; null -> Wx.text }
            val x0 = x(s.from) + 2f; val x1 = (x(s.to) - 2f).coerceAtLeast(x0 + 6f)
            val yy = y(s.value)
            drawLine(c, Offset(x0, yy), Offset(x1, yy), 3.dp.toPx(), StrokeCap.Round)
            val v = measurer.measure(format(s.value), TextStyle(fontSize = 13.sp, color = Wx.text, fontFeatureSettings = "tnum"))
            drawText(v, topLeft = Offset(x0, yy - v.size.height - 2.dp.toPx()))
            s.changePct?.takeIf { abs(it) >= 1 }?.let { ch ->
                val t = measurer.measure((if (ch > 0) "+" else "") + "${ch.toInt()}%", TextStyle(fontSize = 12.sp, color = c))
                drawText(t, topLeft = Offset(x0, yy + 3.dp.toPx()))
            }
        }
        // X labels: about six.
        val every = if (n <= 8) 1 else (n + 5) / 6
        labels.forEachIndexed { i, l ->
            if (i % every != 0 && i != n - 1) return@forEachIndexed
            if (i != n - 1 && n - 1 - i < every / 2) return@forEachIndexed
            val t = measurer.measure(l, TextStyle(fontSize = 11.sp, color = Wx.textMuted))
            drawText(t, topLeft = Offset((x(i) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
        }
    }
}
