package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MARK: - Wx shared building blocks
//
// Stateless pieces every Wx screen composes: a flat card, a tracked label, the score ring, a
// value tile, a two-line weekly chart and the seven-day journal dots. They take their copy and their
// data as parameters, so a screen owns its strings and its reads.

@Composable
internal fun WxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    background: Color = Wx.card,
    radius: Dp = Wx.cardRadius,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
internal fun WxLabel(text: String, modifier: Modifier = Modifier, color: Color = Wx.textMuted) {
    Text(text.uppercase(), style = WxType.label, color = color, modifier = modifier)
}

/** A label on the left, an optional caption on the right: the header every Wx card opens with. */
@Composable
internal fun WxCardHeader(label: String, trailing: String? = null, labelColor: Color = Wx.textMuted) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WxLabel(label, Modifier.weight(1f), labelColor)
        if (trailing != null) Text(trailing, style = WxType.caption, color = Wx.textMuted)
    }
}

/**
 * The score ring: a track plus a round-capped arc from 12 o'clock. [fraction] is clamped to 0..1;
 * null draws the track alone. An optional [bandFrom]..[bandTo] draws a thin arc just outside the ring (beyond its bounds, so the ring
 * keeps the same size as the others), used for Effort's target range.
 */
@Composable
internal fun WxRing(
    fraction: Double?,
    color: Color,
    size: Dp,
    stroke: Dp,
    modifier: Modifier = Modifier,
    bandFrom: Double? = null,
    bandTo: Double? = null,
    center: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            // The ring keeps its size whether or not a band is shown; the band is drawn just outside it.
            val inset = s / 2
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(Wx.cardRaised, -90f, 360f, false, topLeft, arcSize, style = Stroke(s))
            val f = fraction?.coerceIn(0.0, 1.0)
            if (f != null && f > 0.0) {
                drawArc(color, -90f, (360.0 * f).toFloat(), false, topLeft, arcSize,
                    style = Stroke(s, cap = StrokeCap.Round))
            }
            if (bandFrom != null && bandTo != null) {
                val bInset = -s * 0.75f
                val bSize = Size(this.size.width - bInset * 2, this.size.height - bInset * 2)
                drawArc(Wx.text.copy(alpha = 0.55f), -90f + (360.0 * bandFrom).toFloat(),
                    (360.0 * (bandTo - bandFrom)).toFloat(), false, Offset(bInset, bInset), bSize,
                    style = Stroke(s * 0.22f, cap = StrokeCap.Round))
            }
        }
        center()
    }
}

/** A small stat: tracked label over a number, centred. */
@Composable
internal fun WxStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        WxLabel(label)
        Spacer(Modifier.height(2.dp))
        Text(value, style = WxType.numberSmall, color = Wx.text)
    }
}

/**
 * A metric tile: label, value + unit, a change badge, and an average line under a hairline. Tapping
 * the tile opens that metric's trend.
 */
@Composable
internal fun WxMetricTile(
    label: String,
    value: String,
    unit: String?,
    delta: String?,
    deltaColor: Color,
    averageLine: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WxCard(modifier = modifier, onClick = onClick) {
        WxLabel(label)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = WxType.number, color = Wx.text)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = WxType.caption, color = Wx.textMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (delta != null) {
                Text(delta, style = WxType.caption, color = deltaColor, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
        Spacer(Modifier.height(8.dp))
        Text(averageLine, style = WxType.caption, color = Wx.textMuted)
    }
}

/** A list row inside a card: an accent bar, title + subtitle, a trailing number. */
@Composable
internal fun WxActivityRow(
    accent: Color,
    title: String,
    subtitle: String,
    trailing: String,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    Column {
        if (showDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(Wx.line))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Wx.rowHeight)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = WxType.bodyStrong, color = Wx.text)
                Text(subtitle, style = WxType.caption, color = Wx.textMuted)
            }
            Text(trailing, style = WxType.numberSmall.copy(fontSize = 22.sp), color = Wx.text)
        }
    }
}

/** One plotted line: values may be null (a missing day breaks the line). */
internal data class WxSeries(val values: List<Double?>, val color: Color, val min: Double, val max: Double,
                            val format: (Double) -> String, val pointColor: ((Double) -> Color)? = null)

/**
 * Two lines over the same days, each point a ring labelled with its value. With [axes] on, [b]'s scale
 * is printed on the left in its colour and [a]'s on the right, each tick in the colour its value
 * takes (so Recovery's 0/33/66/100 % read red, amber, green); the grid lines sit at those four ticks.
 * A series with point colours fades segment by segment from one point's colour to the next.
 * The label goes above the point when its line is the upper one at that day, below otherwise, so labels
 * of the two lines never collide. The last day is the selected one: a raised column behind it, its
 * weekday and date drawn bright. [dayNumbers], when given, prints the date under each weekday.
 */
@Composable
internal fun WxTwoLineChart(
    a: WxSeries,
    b: WxSeries,
    dayLabels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 310.dp,
    axes: Boolean = false,
    dayNumbers: List<Int>? = null,
) {
    val measurer = rememberTextMeasurer()
    val valueStyle = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    val dayStyle = TextStyle(fontSize = 11.sp, color = Wx.textMuted, textAlign = TextAlign.Center)
    val axisStyle = TextStyle(fontSize = 10.sp, fontFeatureSettings = "tnum", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    val footer = if (dayNumbers != null) 40.dp else 24.dp
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height + footer)
            .semantics { contentDescription = description },
    ) {
        val plotH = height.toPx()
        val leftW = if (axes) 26.dp.toPx() else 0f
        val rightW = if (axes) 34.dp.toPx() else 0f
        val w = size.width - leftW - rightW
        val n = dayLabels.size.coerceAtLeast(1)
        val step = w / n
        val padY = 22.dp.toPx()
        fun x(i: Int) = leftW + step * i + step / 2
        fun frac(v: Double, s: WxSeries) = ((v - s.min) / (s.max - s.min)).coerceIn(0.0, 1.0)
        fun yFrac(t: Double) = (plotH - padY - t * (plotH - padY * 2)).toFloat()
        fun y(v: Double, s: WxSeries) = yFrac(frac(v, s))
        val last = n - 1
        // The selected (last) day: a raised column behind its points and labels.
        drawRoundRect(Wx.cardRaised.copy(alpha = 0.7f), Offset(x(last) - step * 0.4f, 0f), Size(step * 0.8f, size.height),
            androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        // Grid at a third of the scale, with both scales printed when asked.
        for (k in 0..3) {
            val t = k / 3.0
            val gy = yFrac(t)
            drawLine(Wx.line, Offset(leftW, gy), Offset(leftW + w, gy), 1f)
            if (axes) {
                val bv = b.min + (b.max - b.min) * t
                val lt = measurer.measure(b.format(bv).substringBefore('.').substringBefore(','), axisStyle.copy(color = b.color))
                drawText(lt, topLeft = Offset(0f, gy - lt.size.height / 2f))
                val av = a.min + (a.max - a.min) * t
                val ac = a.pointColor?.invoke(av) ?: a.color
                val rt = measurer.measure(a.format(av) + "%", axisStyle.copy(color = ac))
                drawText(rt, topLeft = Offset(size.width - rt.size.width, gy - rt.size.height / 2f))
            }
        }
        listOf(a, b).forEach { s ->
            val pc = s.pointColor
            if (pc == null) {
                val path = Path()
                var started = false
                s.values.forEachIndexed { i, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    if (!started) path.moveTo(x(i), y(v, s)) else path.lineTo(x(i), y(v, s))
                    started = true
                }
                drawPath(path, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            } else {
                // Each segment fades from the colour of its start point to the colour of its end point.
                for (i in 1 until s.values.size) {
                    val v0 = s.values[i - 1] ?: continue
                    val v1 = s.values[i] ?: continue
                    val p0 = Offset(x(i - 1), y(v0, s)); val p1 = Offset(x(i), y(v1, s))
                    drawLine(androidx.compose.ui.graphics.Brush.linearGradient(listOf(pc(v0), pc(v1)), p0, p1), p0, p1,
                        2.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        listOf(a to b, b to a).forEach { (s, other) ->
            s.values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val px = x(i); val py = y(v, s)
                val c = s.pointColor?.invoke(v) ?: s.color
                // Ring points on the card colour.
                drawCircle(if (i == last) Wx.cardRaised else Wx.card, 5.dp.toPx(), Offset(px, py))
                drawCircle(c, 5.dp.toPx(), Offset(px, py), style = Stroke(2.2.dp.toPx()))
                val ov = other.values.getOrNull(i)
                val above = ov == null || py <= y(ov, other)
                val layout = measurer.measure(s.format(v) + (if (s === a && axes) "%" else ""), valueStyle.copy(color = c))
                val ty = if (above) py - 9.dp.toPx() - layout.size.height else py + 9.dp.toPx()
                drawText(layout, topLeft = Offset(px - layout.size.width / 2f, ty))
            }
        }
        dayLabels.forEachIndexed { i, d ->
            val styled = if (i == last) dayStyle.copy(color = Wx.text) else dayStyle
            val layout = measurer.measure(d, styled)
            drawText(layout, topLeft = Offset(x(i) - layout.size.width / 2f, plotH + 6.dp.toPx()))
            dayNumbers?.getOrNull(i)?.let { dn ->
                val l2 = measurer.measure("$dn", styled.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                drawText(l2, topLeft = Offset(x(i) - l2.size.width / 2f, plotH + 6.dp.toPx() + layout.size.height + 2.dp.toPx()))
            }
        }
    }
}

/** A legend chip: a short line in the series colour, a name and today's value. */
@Composable
internal fun WxLegend(color: Color, name: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(name, style = WxType.caption, color = Wx.textMuted)
        Spacer(Modifier.width(4.dp))
        Text(value, style = WxType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Wx.text)
    }
}

/** One day in the journal week strip. */
internal data class WxJournalDay(val weekday: String, val dayOfMonth: Int, val logged: Boolean, val isToday: Boolean)

/**
 * Seven circles, oldest first, ending today: a logged day is a filled green check, an open day an
 * outlined ring, today's ring is drawn in the Effort blue. Tapping a circle selects that day.
 */
@Composable
internal fun WxJournalWeek(
    days: List<WxJournalDay>,
    description: String,
    modifier: Modifier = Modifier,
    dot: Dp = 34.dp,
    onSelect: ((Int) -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().semantics { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        days.forEachIndexed { index, d ->
            Column(
                Modifier
                    .weight(1f)
                    .then(if (onSelect != null) Modifier.clickable { onSelect(index) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val base = Modifier.size(dot).clip(CircleShape)
                when {
                    d.logged -> Box(base.background(Wx.recoveryHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Wx.canvas, modifier = Modifier.size(dot * 0.5f))
                    }
                    else -> Box(
                        base.border(2.dp, if (d.isToday) Wx.effort else Wx.lineStrong, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${d.dayOfMonth}", style = WxType.caption, color = Wx.textSoft)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(d.weekday, style = WxType.caption.copy(fontSize = 11.sp),
                    color = if (d.isToday) Wx.text else Wx.textMuted)
            }
        }
    }
}

/** Plain segmented control: the selected option is filled. */
@Composable
internal fun WxSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, selectedColor: Color = Wx.text) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Wx.card)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) selectedColor else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = WxType.bodyStrong.copy(fontSize = 13.sp), color = if (on) Wx.canvas else Wx.textSoft)
            }
        }
    }
}

/** Vertical bars with a value on top and a label below; used by Trends' week and weekday views. */
@Composable
internal fun WxBars(
    values: List<Double?>,
    labels: List<String>,
    colorFor: (Double) -> Color,
    format: (Double) -> String,
    max: Double,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    averageLine: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val n = values.size.coerceAtLeast(1)
        val labelH = 18.dp.toPx()
        val topPad = 20.dp.toPx()
        val plotH = size.height - labelH - topPad
        val slot = size.width / n
        val barW = slot * 0.62f
        if (averageLine != null && max > 0) {
            val ay = topPad + plotH - (averageLine / max * plotH).toFloat()
            drawLine(Wx.textMuted, Offset(0f, ay), Offset(size.width, ay), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        val showValues = n <= 14
        val labelEvery = wxLabelStride(n)
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            if (v != null && max > 0) {
                val h = (v / max * plotH).toFloat().coerceAtLeast(2f)
                drawRoundRect(colorFor(v), Offset(cx - barW / 2, topPad + plotH - h), Size(barW, h),
                    androidx.compose.ui.geometry.CornerRadius(if (n > 30) 1.dp.toPx() else 6.dp.toPx()))
                if (showValues) {
                    val t = measurer.measure(format(v), TextStyle(fontSize = 14.sp, color = Wx.text, fontFeatureSettings = "tnum"))
                    drawText(t, topLeft = Offset(cx - t.size.width / 2f, topPad + plotH - h - t.size.height - 2.dp.toPx()))
                }
            }
            if (i % labelEvery == 0) {
                val l = measurer.measure(labels.getOrElse(i) { "" }, TextStyle(fontSize = 11.sp, color = Wx.textMuted))
                drawText(l, topLeft = Offset((cx - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), size.height - l.size.height))
            }
        }
    }
}


/** Keep roughly seven axis labels whatever the number of points. */
internal fun wxLabelStride(n: Int): Int = if (n <= 8) 1 else (n + 6) / 7

/**
 * One metric as a line: each segment fades between its two points' colours, a dot per point (dropped
 * above 45 points), the average as a dashed line, values printed only when there are few points, and
 * about seven axis labels. A missing day breaks the line.
 */
@Composable
internal fun WxLineChart(
    values: List<Double?>,
    labels: List<String>,
    colorFor: (Double) -> Color,
    format: (Double) -> String,
    min: Double,
    max: Double,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    averageLine: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val n = values.size.coerceAtLeast(1)
        val labelH = 18.dp.toPx()
        val padY = 18.dp.toPx()
        val plotH = size.height - labelH
        val step = if (n > 1) size.width / (n - 1) else size.width
        val inset = 6.dp.toPx()
        fun x(i: Int) = if (n > 1) inset + (size.width - inset * 2) * i / (n - 1) else size.width / 2
        fun y(v: Double): Float {
            val t = if (max > min) ((v - min) / (max - min)).coerceIn(0.0, 1.0) else 0.5
            return (plotH - padY / 2 - t * (plotH - padY * 1.5)).toFloat()
        }
        for (k in 0..2) {
            val gy = padY + (plotH - padY * 1.5f) * k / 2f
            drawLine(Wx.line, Offset(0f, gy), Offset(size.width, gy), 1f)
        }
        if (averageLine != null) {
            val ay = y(averageLine)
            drawLine(Wx.textMuted, Offset(0f, ay), Offset(size.width, ay), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        for (i in 1 until values.size) {
            val v0 = values[i - 1] ?: continue
            val v1 = values[i] ?: continue
            val p0 = Offset(x(i - 1), y(v0)); val p1 = Offset(x(i), y(v1))
            drawLine(androidx.compose.ui.graphics.Brush.linearGradient(listOf(colorFor(v0), colorFor(v1)), p0, p1), p0, p1,
                2.5.dp.toPx(), StrokeCap.Round)
        }
        val showDots = n <= 45
        val showValues = n <= 10
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val p = Offset(x(i), y(v))
            // A lone point between gaps still needs a dot to be visible.
            val lonely = (values.getOrNull(i - 1) == null) && (values.getOrNull(i + 1) == null)
            if (showDots || lonely) drawCircle(colorFor(v), (if (n > 20) 2.5 else 3.5).dp.toPx(), p)
            if (showValues) {
                val t = measurer.measure(format(v), TextStyle(fontSize = 12.sp, color = colorFor(v), fontFeatureSettings = "tnum"))
                drawText(t, topLeft = Offset((p.x - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), p.y - t.size.height - 4.dp.toPx()))
            }
        }
        val every = wxLabelStride(n)
        labels.forEachIndexed { i, l ->
            if (i % every != 0) return@forEachIndexed
            val t = measurer.measure(l, TextStyle(fontSize = 11.sp, color = Wx.textMuted))
            drawText(t, topLeft = Offset((x(i) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
        }
    }
}

/**
 * The design's heart-rate zone bands (fork): five faint horizontal bands behind an HR line, Z1 to Z5 at
 * 50/60/70/80/90–100 % of [hrMax] (the same split as the workout zone card), clipped to the plotted
 * bpm range [lo]..[hi] and the plot rectangle. [y] maps bpm to the canvas. Labels sit at the right edge.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWxZoneBands(
    hrMax: Double,
    lo: Double,
    hi: Double,
    left: Float,
    right: Float,
    y: (Double) -> Float,
    measurer: androidx.compose.ui.text.TextMeasurer? = null,
) {
    val colors = listOf(Wx.zone1, Wx.zone2, Wx.zone3, Wx.zone4, Wx.zone5)
    for (i in 0 until 5) {
        val a = (hrMax * (0.5 + 0.1 * i)).coerceIn(lo, hi)
        val b = (hrMax * (0.6 + 0.1 * i)).coerceIn(lo, hi)
        if (b - a < 0.5) continue
        val top = y(b); val bottom = y(a)
        drawRect(colors[i].copy(alpha = 0.09f), androidx.compose.ui.geometry.Offset(left, top),
            androidx.compose.ui.geometry.Size(right - left, bottom - top))
        if (measurer != null && bottom - top > 12f) {
            val t = measurer.measure("Z${i + 1}", androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = colors[i].copy(alpha = 0.8f)))
            drawText(t, topLeft = androidx.compose.ui.geometry.Offset(right - t.size.width - 4f, (top + bottom) / 2f - t.size.height / 2f))
        }
    }
}
