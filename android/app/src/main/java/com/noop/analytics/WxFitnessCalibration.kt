package com.noop.analytics

import java.time.LocalDate

/**
 * Fork: two corrections to the weekly Fitness Age / VO2max.
 *
 * 1. The activity term from real training sessions. NOOP's PA-index counts days whose Effort clears a
 *    threshold, so it moves whenever the Effort scale moves (WHOOP's strain vs NOOP's fitted curve),
 *    and a change of source steps the series. HUNT's index is a questionnaire about exercise sessions:
 *    how often, how long, how hard. [paIndex] answers it from the sessions themselves (intervals.icu's),
 *    over the 28 days ending on the week's Saturday, through [FitnessAgeEngine.physicalActivityIndex].
 *
 * 2. A level calibration to the wearer's own WHOOP history. WHOOP's Age and VO2max come from WHOOP's
 *    own models; NOOP's from published formulas. [offsets] takes the weeks for which the wearer typed
 *    WHOOP's values and returns the median difference to NOOP's value for the same week, added to every
 *    week afterwards: NOOP then starts from the wearer's WHOOP level and moves with their own data. Only
 *    the level is calibrated, never the slope.
 */
object WxFitnessCalibration {

    /** One training session: its local day, minutes, and whether it was hard. */
    data class Session(val day: String, val minutes: Double, val hard: Boolean)

    /** One WHOOP week the wearer entered: its ending Saturday and WHOOP's values. */
    data class Ref(val saturday: String, val age: Double?, val vo2: Double?)

    /**
     * PA-index (0–15) for the 28 days ending [saturday] from [sessions]: active days per week (a day
     * with at least 15 minutes), mean minutes per active day, and the share of sessions that were hard.
     * Null when [coverageFrom] (the first day the session history covers) is after the window starts,
     * so a week the history does not reach is not read as inactive.
     */
    fun paIndex(sessions: List<Session>, saturday: String, coverageFrom: String?): Double? {
        val end = LocalDate.parse(saturday)
        val start = end.minusDays(27)
        if (coverageFrom == null || coverageFrom > start.toString()) return null
        val inWindow = sessions.filter { it.day >= start.toString() && it.day <= saturday }
        val byDay = inWindow.groupBy { it.day }.mapValues { (_, v) -> v.sumOf { it.minutes } }.filterValues { it >= 15.0 }
        val activePerWeek = Math.round(byDay.size / 4.0).toInt()
        val avgMin = if (byDay.isEmpty()) 0.0 else byDay.values.average()
        val hardFrac = if (inWindow.isEmpty()) 0.0 else inWindow.count { it.hard }.toDouble() / inWindow.size
        return FitnessAgeEngine.physicalActivityIndex(activePerWeek, avgMin, hardFrac)
    }

    /**
     * Parse lines like "05/7 - 11/7: 38,5 en 53" (start - end: then the first two numbers are Fitness
     * Age and VO2max; comma or dot decimals; any words around them). The week's end date takes the latest year in which
     * it is not after [today]. Lines that do not parse are skipped.
     */
    fun parseRefs(text: String, today: LocalDate): List<Ref> {
        val re = Regex("""(\d{1,2})/(\d{1,2})\s*[-–]\s*(\d{1,2})/(\d{1,2})\s*:(.*)""")
        // Not a digit inside a word ("vo2max").
        val num = Regex("""(?<![A-Za-z\d])\d+(?:[.,]\d+)?""")
        return text.lines().mapNotNull { line ->
            val m = re.find(line) ?: return@mapNotNull null
            val (_, _, d2, m2, rest) = m.destructured
            // The first two numbers after the colon: Fitness Age, then VO2max.
            val nums = num.findAll(rest).map { it.value }.toList()
            val a = nums.getOrNull(0) ?: return@mapNotNull null
            val v = nums.getOrNull(1) ?: ""
            val day = d2.toInt(); val month = m2.toInt()
            if (month !in 1..12 || day !in 1..31) return@mapNotNull null
            var date = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return@mapNotNull null
            if (date.isAfter(today)) date = date.minusYears(1)
            val back = (date.dayOfWeek.value + 1) % 7 // to the Saturday on or before
            Ref(date.minusDays(back.toLong()).toString(), a.replace(',', '.').toDoubleOrNull(), v.replace(',', '.').toDoubleOrNull())
        }.distinctBy { it.saturday }
    }

    private fun median(xs: List<Double>): Double? = xs.sorted().let {
        if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
    }

    /** (age offset, VO2max offset, weeks matched) — WHOOP minus NOOP, median over the weeks both have. */
    fun offsets(refs: List<Ref>, noopAge: Map<String, Double>, noopVo2: Map<String, Double>): Triple<Double?, Double?, Int> {
        val ageD = refs.mapNotNull { r -> r.age?.let { a -> noopAge[r.saturday]?.let { a - it } } }
        val vo2D = refs.mapNotNull { r -> r.vo2?.let { v -> noopVo2[r.saturday]?.let { v - it } } }
        return Triple(median(ageD), median(vo2D), maxOf(ageD.size, vo2D.size))
    }
}
