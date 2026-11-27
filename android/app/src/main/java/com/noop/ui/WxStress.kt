package com.noop.ui

import com.noop.analytics.DaytimeStress
import com.noop.analytics.WxStressScorer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One local day's hourly stress for the Wx screens, from [WxStressScorer]: every hour with heart rate
 * scores, day and night. The reference is the personal daytime calm floor when the personal-baseline
 * setting is on and enough history exists, else the day's own. Returned as [DaytimeStress.HourPoint]
 * so the charts draw it unchanged; `maskedForActivity` carries the low-confidence flag (moving, or
 * little heart rate in the hour), which the charts draw dashed.
 */
internal suspend fun wxStressHours(vm: AppViewModel, date: LocalDate, personalBaseline: Boolean): List<DaytimeStress.HourPoint> {
    val key = "$date|$personalBaseline|${vm.activeStrapId}"
    val now = System.currentTimeMillis()
    // A day is final once read at least 12 h after it ended (late strap offloads have landed by then);
    // until then (today, and a yesterday first read while it was still today) it is reused for a few
    // minutes only. Before this a day read in the afternoon kept its half-scored hours after midnight.
    val dayEndMs = date.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond() * 1000L
    WxStressCache.get(key)?.let { (at, hours) ->
        val final = at >= dayEndMs + 12 * 3_600_000L
        if (final || now - at < WxStressCache.TODAY_TTL_MS) return hours
    }
    return computeWxStressHours(vm, date, personalBaseline).also { WxStressCache.put(key, now, it) }
}

/** The last read stress hours per day, so reopening Today or the Stress screen is instant (fork). */
internal object WxStressCache {
    const val TODAY_TTL_MS = 5 * 60_000L
    private val map = object : LinkedHashMap<String, Pair<Long, List<DaytimeStress.HourPoint>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<DaytimeStress.HourPoint>>>?) = size > 40
    }
    // The personal calm reference changes once a day at most.
    private val refs = HashMap<String, Double?>()
    @Synchronized fun get(key: String) = map[key]
    @Synchronized fun put(key: String, at: Long, hours: List<DaytimeStress.HourPoint>) { map[key] = at to hours }
    @Synchronized fun ref(key: String): Pair<Boolean, Double?> = if (refs.containsKey(key)) true to refs[key] else false to null
    @Synchronized fun putRef(key: String, v: Double?) { refs[key] = v }
}

private suspend fun computeWxStressHours(vm: AppViewModel, date: LocalDate, personalBaseline: Boolean): List<DaytimeStress.HourPoint> {
    val zone = ZoneId.systemDefault()
    val from = date.atStartOfDay(zone).toEpochSecond()
    val to = minOf(date.plusDays(1).atStartOfDay(zone).toEpochSecond(), System.currentTimeMillis() / 1000L)
    if (to <= from) return emptyList()
    val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, from, to, limit = 200_000)
    if (hr.isEmpty()) return emptyList()
    val gravity = vm.repo.gravitySamplesUnion(vm.activeStrapId, from, to, limit = 200_000)
    val refKey = "$date|${vm.activeStrapId}"
    val reference = if (!personalBaseline) null else WxStressCache.ref(refKey).let { (known, v) ->
        if (known) v else runCatching {
            (selectedDaytimeStressMode(vm.repo, vm.activeStrapId, date, zone, true) as? DaytimeStress.ScoringMode.BaselineRelative)
                ?.hr?.baseline
        }.getOrNull().also { WxStressCache.putRef(refKey, it) }
    }
    val tz = zone.rules.getOffset(Instant.ofEpochSecond(from)).totalSeconds.toLong()
    return WxStressScorer.score(hr, gravity, tz, reference).hours.map { h ->
        val hourOfDay = Instant.ofEpochSecond(h.startTs).atZone(zone).hour
        DaytimeStress.HourPoint(hourOfDay, h.startTs, h.level, h.meanHr, null, h.lowConfidence)
    }
}
