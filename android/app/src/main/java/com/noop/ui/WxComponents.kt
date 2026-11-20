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
 * null draws the track alone. An optional [bandFrom]..[bandTo] draws a thin outer arc, used for
 * Effort's target range.
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
            val outer = if (bandFrom != null) s * 1.2f else 0f
            val inset = s / 2 + outer
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(Wx.cardRaised, -90f, 360f, false, topLeft, arcSize, style = Stroke(s))
            val f = fraction?.coerceIn(0.0, 1.0)
            if (f != null && f > 0.0) {
                drawArc(color, -90f, (360.0 * f).toFloat(), false, topLeft, arcSize,
                    style = Stroke(s, cap = StrokeCap.Round))
            }
            if (bandFrom != null && bandTo != null) {
                val bInset = s * 0.25f
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
                            val format: (Double) -> String)

/**
 * Two lines over the same days in a squared, gridded box, each point labelled with its value. The
 * label goes above the point when its line is the upper one at that day, below otherwise, so labels of
 * the two lines never collide. The last point is drawn hollow (today).
 */
@Composable
internal fun WxTwoLineChart(
    a: WxSeries,
    b: WxSeries,
    dayLabels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 310.dp,
) {
    val measurer = rememberTextMeasurer()
    val valueStyle = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum")
    val dayStyle = TextStyle(fontSize = 11.sp, color = Wx.textMuted, textAlign = TextAlign.Center)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height + 24.dp)
            .semantics { contentDescription = description },
    ) {
        val plotH = height.toPx()
        val w = size.width
        val n = dayLabels.size.coerceAtLeast(1)
        val step = w / n
        val padY = 22.dp.toPx()
        fun x(i: Int) = step * i + step / 2
        fun y(v: Double, s: WxSeries): Float {
            val t = ((v - s.min) / (s.max - s.min)).coerceIn(0.0, 1.0)
            return (plotH - padY - t * (plotH - padY * 2)).toFloat()
        }
        drawRoundRect(Wx.inset, size = Size(w, plotH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        for (k in 1..2) {
            val gy = plotH * k / 3f
            drawLine(Wx.line, Offset(0f, gy), Offset(w, gy), 1f)
        }
        for (i in 1 until n) drawLine(Wx.line.copy(alpha = 0.5f), Offset(step * i, 0f), Offset(step * i, plotH), 1f)
        listOf(a, b).forEach { s ->
            val path = Path()
            var started = false
            s.values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                if (!started) path.moveTo(x(i), y(v, s)) else path.lineTo(x(i), y(v, s))
                started = true
            }
            drawPath(path, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        val last = n - 1
        listOf(a to b, b to a).forEach { (s, other) ->
            s.values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val px = x(i); val py = y(v, s)
                if (i == last) {
                    drawCircle(Wx.inset, 5.dp.toPx(), Offset(px, py))
                    drawCircle(s.color, 5.dp.toPx(), Offset(px, py), style = Stroke(2.5.dp.toPx()))
                } else {
                    drawCircle(s.color, 3.5.dp.toPx(), Offset(px, py))
                }
                val ov = other.values.getOrNull(i)
                val above = ov == null || py <= y(ov, other)
                val layout = measurer.measure(s.format(v), valueStyle.copy(color = s.color))
                val ty = if (above) py - 8.dp.toPx() - layout.size.height else py + 8.dp.toPx()
                drawText(layout, topLeft = Offset(px - layout.size.width / 2f, ty))
            }
        }
        dayLabels.forEachIndexed { i, d ->
            val styled = if (i == last) dayStyle.copy(color = Wx.text) else dayStyle
            val layout = measurer.measure(d, styled)
            drawText(layout, topLeft = Offset(x(i) - layout.size.width / 2f, plotH + 6.dp.toPx()))
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
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            if (v != null && max > 0) {
                val h = (v / max * plotH).toFloat().coerceAtLeast(2f)
                drawRoundRect(colorFor(v), Offset(cx - barW / 2, topPad + plotH - h), Size(barW, h),
                    androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                val t = measurer.measure(format(v), TextStyle(fontSize = 14.sp, color = Wx.text, fontFeatureSettings = "tnum"))
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, topPad + plotH - h - t.size.height - 2.dp.toPx()))
            }
            val l = measurer.measure(labels.getOrElse(i) { "" }, TextStyle(fontSize = 11.sp, color = Wx.textMuted))
            drawText(l, topLeft = Offset(cx - l.size.width / 2f, size.height - l.size.height))
        }
    }
}
