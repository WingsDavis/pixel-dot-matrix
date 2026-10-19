package com.example.domain

import com.example.data.entity.IncidentStatus
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import java.util.Calendar
import java.util.TimeZone

enum class InsightsPeriod(val label: String) {
    TODAY("Today"), LAST_7_DAYS("Last 7 days"), LAST_30_DAYS("Last 30 days"),
    ALL_TIME("All time"), CUSTOM("Custom dates")
}

data class InsightsFilter(
    val period: InsightsPeriod = InsightsPeriod.LAST_7_DAYS,
    val customStartUtcMs: Long? = null,
    val customEndUtcMs: Long? = null,
    val taskName: String? = null,
    val incidentCategory: String? = null,
    val weekly: Boolean = false
)

data class FocusBucket(val startEpochMs: Long, val endEpochMs: Long, val focusSeconds: Long)
data class IncidentCategoryCount(val name: String, val count: Int)

data class FocusInsights(
    val timeZoneId: String,
    val fromEpochMs: Long,
    val toEpochMs: Long,
    val focusSeconds: Long,
    val completedFocusSessions: Int,
    val endedFocusIntervals: Int,
    val averageUninterruptedSeconds: Long?,
    val rapidRecoveries: Int,
    val averageRecoverySeconds: Long?,
    val resilienceScore: Long,
    val incidentCount: Int,
    val categories: List<IncidentCategoryCount>,
    val taskNames: List<String>,
    val categoryNames: List<String>,
    val buckets: List<FocusBucket>,
    val history: List<SessionLogEntity>
) {
    val focusMinutes: Long get() = focusSeconds / 60
    val completionRatePercent: Int? get() = endedFocusIntervals.takeIf { it > 0 }
        ?.let { (completedFocusSessions.toLong() * 100 / it).toInt() }
}

object InsightsCalculator {
    fun calculate(
        sessions: List<SessionLogEntity>,
        incidents: List<PanicLogEntity>,
        filter: InsightsFilter = InsightsFilter(),
        nowEpochMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): FocusInsights {
        val today = dayStart(nowEpochMs, timeZone)
        val tomorrow = advance(today, Calendar.DAY_OF_YEAR, 1, timeZone)
        val from = when (filter.period) {
            InsightsPeriod.TODAY -> today
            InsightsPeriod.LAST_7_DAYS -> advance(today, Calendar.DAY_OF_YEAR, -6, timeZone)
            InsightsPeriod.LAST_30_DAYS -> advance(today, Calendar.DAY_OF_YEAR, -29, timeZone)
            InsightsPeriod.ALL_TIME -> Long.MIN_VALUE
            InsightsPeriod.CUSTOM -> filter.customStartUtcMs?.let { localDateFromUtc(it, timeZone) } ?: today
        }
        val to = when (filter.period) {
            InsightsPeriod.CUSTOM -> filter.customEndUtcMs?.let {
                advance(localDateFromUtc(it, timeZone), Calendar.DAY_OF_YEAR, 1, timeZone)
            } ?: tomorrow
            else -> tomorrow
        }.coerceAtLeast(from)
        val end = minOf(to, nowEpochMs)
        fun matchesTask(name: String?) = filter.taskName == null || name.orEmpty().trim() == filter.taskName
        fun endedInRange(timestamp: Long) = timestamp >= from && timestamp < to && timestamp <= nowEpochMs

        val focus = sessions.filter { it.sessionType == "FOCUS" && it.timestamp <= nowEpochMs && matchesTask(it.taskName) }
        val history = focus.filter {
            secondsInWindow(it, from, end) > 0 || endedInRange(it.timestamp)
        }.sortedWith(compareByDescending<SessionLogEntity> { it.timestamp }.thenByDescending { it.id })
        val ended = history.filter { endedInRange(it.timestamp) }
        val completed = ended.count { it.status == "COMPLETED" }
        val uninterrupted = ended.filter { it.durationSeconds > 0 }
        val relevantIncidents = incidents.filter {
            it.incidentStatus != IncidentStatus.DISMISSED && endedInRange(it.timestamp) && matchesTask(it.taskName) &&
                (filter.incidentCategory == null || category(it) == filter.incidentCategory)
        }
        val panics = relevantIncidents.filter { it.incidentType == "PANIC" }
        val recoveries = panics.mapNotNull { incident ->
            incident.resolvedAt?.takeIf { it >= incident.timestamp && it <= nowEpochMs }
                ?.let { (it - incident.timestamp) / 1000L }
        }
        val rapid = recoveries.count { it < 180 }
        val openPanics = panics.count { it.resolvedAt == null && it.incidentStatus != IncidentStatus.CLOSED }
        val categories = relevantIncidents.groupingBy(::category).eachCount()
            .map { IncidentCategoryCount(it.key, it.value) }
            .sortedWith(compareByDescending<IncidentCategoryCount> { it.count }.thenBy { it.name })

        val buckets = mutableListOf<FocusBucket>()
        if (end > from) {
            var bucketStart = dayStart(end - 1, timeZone)
            if (filter.weekly) {
                val dayOfWeek = Calendar.getInstance(timeZone).apply { timeInMillis = bucketStart }
                    .get(Calendar.DAY_OF_WEEK)
                bucketStart = advance(bucketStart, Calendar.DAY_OF_YEAR, -((dayOfWeek + 5) % 7), timeZone)
            }
            val days = if (filter.weekly) 7 else 1
            repeat(if (filter.weekly) 8 else 7) {
                val start = maxOf(from, bucketStart)
                val bucketEnd = minOf(end, advance(bucketStart, Calendar.DAY_OF_YEAR, days, timeZone))
                if (bucketEnd > start) {
                    buckets.add(FocusBucket(start, bucketEnd, focus.sumOf { secondsInWindow(it, start, bucketEnd) }))
                }
                bucketStart = advance(bucketStart, Calendar.DAY_OF_YEAR, -days, timeZone)
            }
        }

        return FocusInsights(
            timeZoneId = timeZone.id,
            fromEpochMs = from,
            toEpochMs = to,
            focusSeconds = history.sumOf { secondsInWindow(it, from, end) },
            completedFocusSessions = completed,
            endedFocusIntervals = ended.size,
            averageUninterruptedSeconds = uninterrupted.takeIf { it.isNotEmpty() }
                ?.let { it.sumOf { row -> row.durationSeconds.toLong() } / it.size },
            rapidRecoveries = rapid,
            averageRecoverySeconds = recoveries.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
            resilienceScore = (completed * 10L + rapid * 5L - openPanics * 5L).coerceAtLeast(0),
            incidentCount = relevantIncidents.size,
            categories = categories,
            taskNames = (sessions.map { it.taskName.orEmpty().trim() } + incidents.map { it.taskName.orEmpty().trim() })
                .distinct().sorted(),
            categoryNames = incidents.filter { it.incidentStatus != IncidentStatus.DISMISSED }
                .map(::category).distinct().sorted(),
            buckets = buckets.asReversed(),
            history = history
        )
    }

    // Date pickers return UTC midnight; interpret its calendar date in the user's zone.
    fun localDateFromUtc(utcEpochMs: Long, timeZone: TimeZone): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcEpochMs }
        return Calendar.getInstance(timeZone).apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun dayStart(epochMs: Long, timeZone: TimeZone): Long = Calendar.getInstance(timeZone).apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun advance(epochMs: Long, field: Int, amount: Int, timeZone: TimeZone): Long =
        Calendar.getInstance(timeZone).apply { timeInMillis = epochMs; add(field, amount) }.timeInMillis

    private fun category(incident: PanicLogEntity) = incident.triggerReason?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Uncategorized"

    private fun secondsInWindow(session: SessionLogEntity, from: Long, to: Long): Long {
        if (to <= from || session.durationSeconds <= 0) return 0
        val end = session.startTimeMillis + session.durationSeconds.toLong() * 1000L
        return (minOf(end, to) - maxOf(session.startTimeMillis, from)).coerceAtLeast(0L) / 1000L
    }
}
