package com.noop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MARK: - Wx — the fork's dark "score-first" design tokens
//
// One token set for the redesigned Today, score-detail, Trends and Journal surfaces: a near-black
// canvas, flat raised cards, and one colour per score (Recovery green/yellow/red bands, Effort blue,
// Sleep violet, Stress teal→amber→coral). Every Wx screen reads colour, type and spacing from here, so
// the look can be tuned in one file. Dark-only by design; the rest of the app keeps [Palette].

internal object Wx {
    val canvas = Color(0xFF0B0D10)
    val card = Color(0xFF161A1F)
    val cardRaised = Color(0xFF1E242B)
    val inset = Color(0xFF0F1216)
    val line = Color(0xFF222930)
    val lineStrong = Color(0xFF2A3139)

    val text = Color(0xFFF2F4F6)
    val textSoft = Color(0xFFC9D0D6)
    val textMuted = Color(0xFF9AA4AE)
    val textDim = Color(0xFF8C96A0)

    val recoveryHigh = Color(0xFF2FD27A)
    val recoveryMid = Color(0xFFF5C043)
    val recoveryLow = Color(0xFFFF5A5F)
    val effort = Color(0xFF3BA7FF)
    val sleep = Color(0xFF9C8CFF)
    val sleepSoft = Color(0xFFB9AEFF)
    val sleepCardTop = Color(0xFF1F1B3A)
    val sleepDivider = Color(0xFF2E2A4A)
    val stressLow = Color(0xFF4FD1C5)
    val stressMid = Color(0xFFF5C043)
    val stressHigh = Color(0xFFFF7A59)
    val heart = Color(0xFFFF5A5F)

    val stageAwake = Color(0xFFF2F4F6)
    val stageLight = Color(0xFF6F8BFF)
    val stageDeep = Color(0xFF4B3BC9)
    val stageRem = Color(0xFFC58CFF)

    val zone1 = Color(0xFF3BA7FF)
    val zone2 = Color(0xFF2FD27A)
    val zone3 = Color(0xFFF5C043)
    val zone4 = Color(0xFFFF8A4C)
    val zone5 = Color(0xFFFF5A5F)

    val cardRadius = 18.dp
    val chartRadius = 6.dp
    val gutter = 16.dp
    val gap = 12.dp
    val rowHeight = 56.dp

    /** Recovery band colour: green from 67, yellow from 34, red below. */
    fun recoveryColor(pct: Double?): Color = when {
        pct == null -> textDim
        pct >= 67.0 -> recoveryHigh
        pct >= 34.0 -> recoveryMid
        else -> recoveryLow
    }

    /** Night/sleep marker on the day charts: a muted blue-grey. */
    val nightBand = Color(0xFF6B8FB5)

    /**
     * The day-chart stress colour, continuous over 0–3: cyan-teal when calm, mint, a yellow-green at 2,
     * and amber at the top, so a line through the day flows from one into the next.
     */
    fun stressLine(level: Double): Color {
        val stops = listOf(0.0 to Color(0xFF2EC4D6), 1.0 to Color(0xFF26E0A0), 2.0 to Color(0xFFA6D84A), 3.0 to Color(0xFFF2B53A))
        val v = level.coerceIn(0.0, 3.0)
        val k = stops.indexOfFirst { it.first >= v }.coerceAtLeast(1)
        val (a, ca) = stops[k - 1]; val (b, cb) = stops[k]
        return androidx.compose.ui.graphics.lerp(ca, cb, ((v - a) / (b - a)).toFloat())
    }

    /** Stress band colour on the 0–3 scale. */
    fun stressColor(level: Double?): Color = when {
        level == null -> textDim
        level < 1.0 -> stressLow
        level < 2.0 -> stressMid
        else -> stressHigh
    }
}

/** Wx type: condensed-feeling numerals for scores, a tracked uppercase label, plain sans body. */
internal object WxType {
    private val sans = FontFamily.SansSerif
    private val condensed = FontFamily.SansSerif

    val hero = TextStyle(fontFamily = condensed, fontWeight = FontWeight.SemiBold, fontSize = 64.sp,
        letterSpacing = (-1).sp, fontFeatureSettings = "tnum")
    val dial = TextStyle(fontFamily = condensed, fontWeight = FontWeight.SemiBold, fontSize = 30.sp,
        fontFeatureSettings = "tnum")
    val number = TextStyle(fontFamily = condensed, fontWeight = FontWeight.SemiBold, fontSize = 28.sp,
        fontFeatureSettings = "tnum")
    val numberSmall = TextStyle(fontFamily = condensed, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
        fontFeatureSettings = "tnum")
    val title = TextStyle(fontFamily = condensed, fontWeight = FontWeight.Bold, fontSize = 20.sp,
        letterSpacing = 1.sp)
    val label = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        letterSpacing = 1.3.sp)
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    val caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val tab = TextStyle(fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.3.sp)
}
