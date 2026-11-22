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
    val zone = ZoneId.systemDefault()
    val from = date.atStartOfDay(zone).toEpochSecond()
    val to = minOf(date.plusDays(1).atStartOfDay(zone).toEpochSecond(), System.currentTimeMillis() / 1000L)
    if (to <= from) return emptyList()
    val hr = vm.repo.hrSamplesUnion(vm.activeStrapId, from, to, limit = 200_000)
    if (hr.isEmpty()) return emptyList()
    val gravity = vm.repo.gravitySamplesUnion(vm.activeStrapId, from, to, limit = 200_000)
    val reference = if (!personalBaseline) null else runCatching {
        (selectedDaytimeStressMode(vm.repo, vm.activeStrapId, date, zone, true) as? DaytimeStress.ScoringMode.BaselineRelative)
            ?.hr?.baseline
    }.getOrNull()
    val tz = zone.rules.getOffset(Instant.ofEpochSecond(from)).totalSeconds.toLong()
    return WxStressScorer.score(hr, gravity, tz, reference).hours.map { h ->
        val hourOfDay = Instant.ofEpochSecond(h.startTs).atZone(zone).hour
        DaytimeStress.HourPoint(hourOfDay, h.startTs, h.level, h.meanHr, null, h.lowConfidence)
    }
}
