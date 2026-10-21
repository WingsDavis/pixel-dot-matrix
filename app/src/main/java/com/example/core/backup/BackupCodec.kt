package com.example.core.backup

import android.util.JsonReader
import android.util.JsonToken
import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.goal.FocusTargetType
import com.example.core.protection.DomainProfile
import com.example.core.protection.DomainProfileStore
import com.example.core.protection.DomainRules
import com.example.core.sync.WatchFaceConfig
import com.example.core.sync.WatchFacePreset
import com.example.core.timer.TimerSettings
import com.example.data.entity.FocusTaskEntity
import com.example.data.entity.IncidentStatus
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class BackupSettings(
    val timer: TimerSettings,
    val dailyGoal: DailyFocusGoalSettings,
    val watchFace: WatchFaceConfig,
    val presets: List<WatchFacePreset>,
    val recentColors: List<String>,
    val domainProfiles: List<DomainProfile>,
    val activeProfileId: String,
    val autoFocusProtection: Boolean
)

data class BackupDocument(
    val schemaVersion: Int = 1,
    val createdAtEpochMs: Long,
    val sessions: List<SessionLogEntity>,
    val incidents: List<PanicLogEntity>,
    val tasks: List<FocusTaskEntity>,
    val settings: BackupSettings
)

/** Version 1 contains local records and preferences, never media or live/synchronized authority. */
object BackupCodec {
    const val MAX_BYTES = 16 * 1024 * 1024

    private val sessionTypes = setOf("FOCUS", "SHORT_BREAK", "LONG_BREAK")
    private val sessionStatuses = setOf("COMPLETED", "INTERRUPTED", "PANIC")
    private val incidentTypes = setOf("PANIC", "DISTRACTION", "FRUSTRATION", "PROTECTION_OVERRIDE")
    private val incidentStatuses = setOf(
        IncidentStatus.OPEN, IncidentStatus.PENDING_DETAIL, IncidentStatus.CLOSED, IncidentStatus.DISMISSED
    )
    private val taskStatuses = setOf(
        FocusTaskEntity.STATUS_PENDING, FocusTaskEntity.STATUS_COMPLETED, FocusTaskEntity.STATUS_SKIPPED
    )
    private val leftSlotModes = setOf("custom_text", "phase", "heart_rate", "next_break", "streak", "daily_target", "hidden")
    private val bottomRightSlotModes = setOf("date", "phase", "timer", "battery", "daily_target", "hidden")
    private val colorPattern = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
    private val legacyIncidentIdPattern = Regex("legacy-[0-9]+")

    /** Revisions are deliberately omitted, including revisions inside saved presets. */
    fun encode(document: BackupDocument): ByteArray {
        val json = document.toJson()
        readDocument(json)
        return utf8(json.toString()).also { require(it.size <= MAX_BYTES) { "Backup exceeds MAX_BYTES" } }
    }

    /** Throws IllegalArgumentException before returning any data if the entire document is invalid. */
    fun decode(bytes: ByteArray): BackupDocument {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Invalid backup size" }
        try {
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            validateJsonText(text)
            return JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val json = reader.readValue(0)
                require(json is JSONObject) { "Backup must be an object" }
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing backup data" }
                readDocument(json)
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Invalid UTF-8 or backup JSON", error)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid backup JSON", error)
        }
    }

    /** Legacy identities include the original local ID and every persisted content field, in this order. */
    fun sessionRecordId(row: SessionLogEntity): String = row.recordId ?: run {
        val canonical = JSONArray(listOf(
            1, row.id, row.sessionType, row.startTimeMillis, row.durationSeconds,
            row.status, row.taskId, row.taskName, row.timestamp
        )).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(utf8(canonical))
        "legacy-session-v1:" + digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Migration identities hash the original local ID and all persisted fields in entity order. */
    fun incidentRecordId(row: PanicLogEntity): String {
        if (!legacyIncidentIdPattern.matches(row.incidentId)) return row.incidentId
        val canonical = JSONArray(listOf(
            1, row.id, row.timestamp, row.heartRateSpike, row.stepsTaken, row.resolvedAt,
            row.targetApp, row.triggerReason, row.forensicNotes, row.taskName, row.incidentId,
            row.incidentType, row.sourceDevice, row.incidentStatus
        )).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(utf8(canonical))
        return "legacy-incident-v1:" + digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * UTF-8, CRLF; fixed header:
     * id,sessionType,startTimeMillis,durationSeconds,status,taskId,taskName,timestamp,recordId
     * Nullable values are empty cells; text is quoted and formula-like text is apostrophe-prefixed.
     */
    fun sessionsCsv(rows: List<SessionLogEntity>): ByteArray = csv(
        "id,sessionType,startTimeMillis,durationSeconds,status,taskId,taskName,timestamp,recordId", rows
    ) { row ->
        listOf(
            row.id.toString(), csvText(row.sessionType), row.startTimeMillis.toString(),
            row.durationSeconds.toString(), csvText(row.status), csvText(row.taskId), csvText(row.taskName),
            row.timestamp.toString(), csvText(row.recordId)
        )
    }

    /**
     * Same CSV rules as sessionsCsv; fixed header:
     * id,timestamp,heartRateSpike,stepsTaken,resolvedAt,targetApp,triggerReason,forensicNotes,taskName,incidentId,incidentType,sourceDevice,incidentStatus
     */
    fun incidentsCsv(rows: List<PanicLogEntity>): ByteArray = csv(
        "id,timestamp,heartRateSpike,stepsTaken,resolvedAt,targetApp,triggerReason,forensicNotes,taskName,incidentId,incidentType,sourceDevice,incidentStatus",
        rows
    ) { row ->
        listOf(
            row.id.toString(), row.timestamp.toString(), row.heartRateSpike?.toString().orEmpty(),
            row.stepsTaken.toString(), row.resolvedAt?.toString().orEmpty(), csvText(row.targetApp),
            csvText(row.triggerReason), csvText(row.forensicNotes), csvText(row.taskName), csvText(row.incidentId),
            csvText(row.incidentType), csvText(row.sourceDevice), csvText(row.incidentStatus)
        )
    }

    private fun BackupDocument.toJson() = json(
        "schemaVersion" to schemaVersion,
        "createdAtEpochMs" to createdAtEpochMs,
        "sessions" to JSONArray(sessions.map { row ->
            json(
                "id" to row.id, "sessionType" to row.sessionType, "startTimeMillis" to row.startTimeMillis,
                "durationSeconds" to row.durationSeconds, "status" to row.status, "taskId" to row.taskId,
                "taskName" to row.taskName, "timestamp" to row.timestamp, "recordId" to row.recordId
            )
        }),
        "incidents" to JSONArray(incidents.map { row ->
            json(
                "id" to row.id, "timestamp" to row.timestamp, "heartRateSpike" to row.heartRateSpike,
                "stepsTaken" to row.stepsTaken, "resolvedAt" to row.resolvedAt, "targetApp" to row.targetApp,
                "triggerReason" to row.triggerReason, "forensicNotes" to row.forensicNotes, "taskName" to row.taskName,
                "incidentId" to row.incidentId, "incidentType" to row.incidentType,
                "sourceDevice" to row.sourceDevice, "incidentStatus" to row.incidentStatus
            )
        }),
        "tasks" to JSONArray(tasks.map { row ->
            json(
                "id" to row.id, "title" to row.title, "sortOrder" to row.sortOrder, "status" to row.status,
                "estimateSessions" to row.estimateSessions, "completedSessions" to row.completedSessions,
                "createdAt" to row.createdAt, "updatedAt" to row.updatedAt
            )
        }),
        "settings" to settings.toJson()
    )

    private fun BackupSettings.toJson() = json(
        "timer" to json(
            "focusDurationSeconds" to timer.focusDurationSeconds,
            "shortBreakDurationSeconds" to timer.shortBreakDurationSeconds,
            "longBreakDurationSeconds" to timer.longBreakDurationSeconds,
            "longBreakCadence" to timer.longBreakCadence,
            "autoStartBreaks" to timer.autoStartBreaks, "autoStartFocus" to timer.autoStartFocus,
            "transitionSoundEnabled" to timer.transitionSoundEnabled,
            "transitionNotificationEnabled" to timer.transitionNotificationEnabled,
            "transitionHapticEnabled" to timer.transitionHapticEnabled, "schemaVersion" to timer.schemaVersion
        ),
        "dailyGoal" to json(
            "enabled" to dailyGoal.enabled, "type" to dailyGoal.type.name,
            "targetMinutes" to dailyGoal.targetMinutes, "targetSessions" to dailyGoal.targetSessions,
            "timeZoneId" to dailyGoal.timeZoneId, "resetHour" to dailyGoal.resetHour
        ),
        "watchFace" to watchFace.toJson(),
        "presets" to JSONArray(presets.map { json("id" to it.id, "name" to it.name, "config" to it.config.toJson()) }),
        "recentColors" to JSONArray(recentColors),
        "domainProfiles" to JSONArray(domainProfiles.map {
            json("id" to it.id, "name" to it.name, "domains" to JSONArray(it.domains.toList()))
        }),
        "activeProfileId" to activeProfileId,
        "autoFocusProtection" to autoFocusProtection
    )

    private fun WatchFaceConfig.toJson() = json(
        "hourColor" to hourColor, "minuteColor" to minuteColor, "secondColor" to secondColor,
        "customText" to customText, "showLogo" to showLogo, "preset" to preset,
        "leftSlotMode" to leftSlotMode, "bottomRightSlotMode" to bottomRightSlotMode, "ambientStyle" to ambientStyle
    )

    private fun readDocument(json: JSONObject): BackupDocument {
        json.fields("schemaVersion", "createdAtEpochMs", "sessions", "incidents", "tasks", "settings")
        require(json.int("schemaVersion") == 1) { "Unsupported backup schemaVersion" }
        return BackupDocument(
            schemaVersion = 1,
            createdAtEpochMs = json.long("createdAtEpochMs"),
            sessions = json.objects("sessions").map(::readSession),
            incidents = json.objects("incidents").map(::readIncident),
            tasks = json.objects("tasks").map(::readTask),
            settings = readSettings(json.obj("settings"))
        ).also { document ->
            unique(document.sessions.mapNotNull { it.recordId }, "recordId")
            unique(document.sessions.map(::sessionRecordId), "session identity")
            unique(document.sessions.map { it.id }.filter { it != 0 }, "session id")
            unique(document.incidents.map { it.incidentId }, "incidentId")
            unique(document.incidents.map(::incidentRecordId), "incident identity")
            unique(document.incidents.map { it.id }.filter { it != 0 }, "incident id")
            unique(document.tasks.map { it.id }, "task id")
        }
    }

    private fun readSession(json: JSONObject): SessionLogEntity {
        json.fields("id", "sessionType", "startTimeMillis", "durationSeconds", "status", "taskId", "taskName", "timestamp", "recordId")
        return SessionLogEntity(
            id = json.int("id"), sessionType = json.choice("sessionType", sessionTypes),
            startTimeMillis = json.long("startTimeMillis"), durationSeconds = json.int("durationSeconds"),
            status = json.choice("status", sessionStatuses), taskId = json.nullableId("taskId"),
            taskName = json.nullableText("taskName"), timestamp = json.long("timestamp"), recordId = json.nullableId("recordId")
        ).also { row ->
            require(row.timestamp >= row.startTimeMillis) { "Session ends before it starts" }
            require(row.startTimeMillis <= Long.MAX_VALUE - row.durationSeconds.toLong() * 1000L) { "Session interval overflows" }
        }
    }

    private fun readIncident(json: JSONObject): PanicLogEntity {
        json.fields(
            "id", "timestamp", "heartRateSpike", "stepsTaken", "resolvedAt", "targetApp", "triggerReason",
            "forensicNotes", "taskName", "incidentId", "incidentType", "sourceDevice", "incidentStatus"
        )
        return PanicLogEntity(
            id = json.int("id"), timestamp = json.long("timestamp"), heartRateSpike = json.nullableInt("heartRateSpike"),
            stepsTaken = json.int("stepsTaken"), resolvedAt = json.nullableLong("resolvedAt"),
            targetApp = json.nullableText("targetApp"), triggerReason = json.nullableText("triggerReason"),
            forensicNotes = json.nullableText("forensicNotes"), taskName = json.nullableText("taskName"),
            incidentId = json.id("incidentId"), incidentType = json.choice("incidentType", incidentTypes),
            sourceDevice = json.choice("sourceDevice", setOf("PHONE", "WEAR")),
            incidentStatus = json.choice("incidentStatus", incidentStatuses)
        )
    }

    private fun readTask(json: JSONObject): FocusTaskEntity {
        json.fields("id", "title", "sortOrder", "status", "estimateSessions", "completedSessions", "createdAt", "updatedAt")
        return FocusTaskEntity(
            id = json.id("id"), title = json.id("title"), sortOrder = json.int("sortOrder"),
            status = json.choice("status", taskStatuses), estimateSessions = json.int("estimateSessions", 1..12),
            completedSessions = json.int("completedSessions"), createdAt = json.long("createdAt"), updatedAt = json.long("updatedAt")
        ).also { task ->
            require(task.completedSessions <= task.estimateSessions) { "Task completion exceeds its estimate" }
            require(task.status != FocusTaskEntity.STATUS_PENDING || task.completedSessions < task.estimateSessions) {
                "Pending task has reached its estimate"
            }
        }
    }

    private fun readSettings(json: JSONObject): BackupSettings {
        json.fields("timer", "dailyGoal", "watchFace", "presets", "recentColors", "domainProfiles", "activeProfileId", "autoFocusProtection")
        return BackupSettings(
            timer = readTimer(json.obj("timer")),
            dailyGoal = readGoal(json.obj("dailyGoal")),
            watchFace = readWatchFace(json.obj("watchFace")),
            presets = json.objects("presets").map {
                it.fields("id", "name", "config")
                WatchFacePreset(it.id("id"), it.id("name"), readWatchFace(it.obj("config")))
            },
            recentColors = json.strings("recentColors").onEach(::validateColor),
            domainProfiles = json.objects("domainProfiles").map {
                it.fields("id", "name", "domains")
                val domains = it.strings("domains")
                unique(domains, "domain")
                require(domains.all { domain -> DomainRules.normalize(domain) == domain }) { "Invalid or noncanonical domain" }
                DomainProfile(it.id("id"), it.id("name"), domains.toSet())
            },
            activeProfileId = json.id("activeProfileId"),
            autoFocusProtection = json.bool("autoFocusProtection")
        ).also { settings ->
            unique(settings.presets.map { it.id }, "preset id")
            unique(settings.domainProfiles.map { it.id }, "profile id")
            unique(settings.recentColors, "recent color")
            require(settings.recentColors.size <= 8) { "Too many recent colors" }
            require(settings.domainProfiles.any { it.id == DomainProfileStore.DEFAULT_PROFILE_ID }) { "Missing default protection profile" }
            require(settings.domainProfiles.any { it.id == settings.activeProfileId }) { "Unknown activeProfileId" }
        }
    }

    private fun readTimer(json: JSONObject): TimerSettings {
        json.fields(
            "focusDurationSeconds", "shortBreakDurationSeconds", "longBreakDurationSeconds", "longBreakCadence",
            "autoStartBreaks", "autoStartFocus", "transitionSoundEnabled", "transitionNotificationEnabled",
            "transitionHapticEnabled", "schemaVersion"
        )
        require(json.int("schemaVersion") == TimerSettings.CURRENT_SCHEMA_VERSION) { "Unsupported timer schemaVersion" }
        return TimerSettings(
            focusDurationSeconds = json.int("focusDurationSeconds", TimerSettings.FOCUS_RANGE),
            shortBreakDurationSeconds = json.int("shortBreakDurationSeconds", TimerSettings.SHORT_BREAK_RANGE),
            longBreakDurationSeconds = json.int("longBreakDurationSeconds", TimerSettings.LONG_BREAK_RANGE),
            longBreakCadence = json.int("longBreakCadence", TimerSettings.CADENCE_RANGE),
            autoStartBreaks = json.bool("autoStartBreaks"), autoStartFocus = json.bool("autoStartFocus"),
            transitionSoundEnabled = json.bool("transitionSoundEnabled"),
            transitionNotificationEnabled = json.bool("transitionNotificationEnabled"),
            transitionHapticEnabled = json.bool("transitionHapticEnabled"), schemaVersion = json.int("schemaVersion")
        )
    }

    private fun readGoal(json: JSONObject): DailyFocusGoalSettings {
        json.fields("enabled", "type", "targetMinutes", "targetSessions", "timeZoneId", "resetHour")
        return DailyFocusGoalSettings(
            enabled = json.bool("enabled"),
            type = FocusTargetType.valueOf(json.choice("type", FocusTargetType.entries.map { it.name }.toSet())),
            targetMinutes = json.int("targetMinutes", 1..1440), targetSessions = json.int("targetSessions", 1..100),
            timeZoneId = json.text("timeZoneId").also { require(it in TimeZone.getAvailableIDs()) { "Invalid timeZoneId" } },
            resetHour = json.int("resetHour", 0..23)
        )
    }

    private fun readWatchFace(json: JSONObject): WatchFaceConfig {
        json.fields("hourColor", "minuteColor", "secondColor", "customText", "showLogo", "preset", "leftSlotMode", "bottomRightSlotMode", "ambientStyle")
        return WatchFaceConfig(
            hourColor = json.text("hourColor").also(::validateColor),
            minuteColor = json.text("minuteColor").also(::validateColor),
            secondColor = json.text("secondColor").also(::validateColor),
            customText = json.text("customText"), showLogo = json.bool("showLogo"), preset = json.id("preset"),
            leftSlotMode = json.choice("leftSlotMode", leftSlotModes),
            bottomRightSlotMode = json.choice("bottomRightSlotMode", bottomRightSlotModes),
            ambientStyle = json.choice("ambientStyle", setOf("dim", "minimal"))
        )
    }

    private fun json(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (name, value) -> put(name, value ?: JSONObject.NULL) }
    }

    private fun JSONObject.fields(vararg names: String) {
        require(length() == names.size && names.all(::has)) { "Missing or unknown backup fields" }
    }

    private fun JSONObject.text(name: String): String =
        get(name).let { require(it is String) { "Expected string: $name" }; it }

    private fun JSONObject.id(name: String): String =
        text(name).also { require(it.isNotBlank()) { "Blank identifier or name: $name" } }

    private fun JSONObject.nullableId(name: String): String? =
        nullableText(name)?.also { require(it.isNotBlank()) { "Blank identifier: $name" } }

    private fun JSONObject.nullableText(name: String): String? = if (get(name) === JSONObject.NULL) null else text(name)

    private fun JSONObject.bool(name: String): Boolean =
        get(name).let { require(it is Boolean) { "Expected boolean: $name" }; it }

    private fun JSONObject.long(name: String): Long {
        val value = get(name)
        require(value is Int || value is Long) { "Expected integer: $name" }
        return (value as Number).toLong().also { require(it >= 0) { "Negative value: $name" } }
    }

    private fun JSONObject.int(name: String, range: IntRange = 0..Int.MAX_VALUE): Int =
        long(name).also { require(it in range.first.toLong()..range.last.toLong()) { "Out of range: $name" } }.toInt()

    private fun JSONObject.nullableLong(name: String): Long? = if (get(name) === JSONObject.NULL) null else long(name)
    private fun JSONObject.nullableInt(name: String): Int? = if (get(name) === JSONObject.NULL) null else int(name)

    private fun JSONObject.choice(name: String, choices: Set<String>): String =
        text(name).also { require(it in choices) { "Unknown value: $name" } }

    private fun JSONObject.obj(name: String): JSONObject =
        get(name).let { require(it is JSONObject) { "Expected object: $name" }; it }

    private fun JSONObject.array(name: String): JSONArray =
        get(name).let { require(it is JSONArray) { "Expected array: $name" }; it }

    private fun JSONObject.objects(name: String): List<JSONObject> = array(name).let { array ->
        (0 until array.length()).map { index ->
            array.get(index).let { require(it is JSONObject) { "Expected object in $name" }; it }
        }
    }

    private fun JSONObject.strings(name: String): List<String> = array(name).let { array ->
        (0 until array.length()).map { index ->
            array.get(index).let { require(it is String) { "Expected string in $name" }; it }
        }
    }

    private fun validateColor(color: String) {
        require(colorPattern.matches(color)) { "Invalid hex color" }
    }

    private fun <T> unique(values: List<T>, name: String) {
        require(values.size == values.toSet().size) { "Duplicate $name" }
    }

    // Android's JSONObject parser is lenient and coerces numbers; use strict tokens at the import boundary.
    private fun JsonReader.readValue(depth: Int): Any {
        require(depth <= 16) { "Backup JSON is too deeply nested" }
        return when (peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                beginObject()
                while (hasNext()) {
                    val name = nextName()
                    require(!has(name)) { "Duplicate JSON field" }
                    put(name, readValue(depth + 1))
                }
                endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                beginArray()
                while (hasNext()) put(readValue(depth + 1))
                endArray()
            }
            JsonToken.STRING -> nextString().also { utf8(it) }
            JsonToken.NUMBER -> requireNotNull(nextString().toLongOrNull()) { "Expected a 64-bit integer" }
            JsonToken.BOOLEAN -> nextBoolean()
            JsonToken.NULL -> { nextNull(); JSONObject.NULL }
            else -> throw IllegalArgumentException("Invalid backup JSON value")
        }
    }

    // JsonReader's strict mode still accepts raw controls, unknown escapes and uppercase literals.
    private fun validateJsonText(text: String) {
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                when (char) {
                    '"' -> quoted = false
                    '\\' -> {
                        require(index < text.length) { "Unterminated JSON escape" }
                        val escape = text[index++]
                        require(escape in "\"\\/bfnrtu") { "Invalid JSON escape" }
                        if (escape == 'u') {
                            require(index + 4 <= text.length && text.substring(index, index + 4).all {
                                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
                            }) { "Invalid Unicode escape" }
                            index += 4
                        }
                    }
                    else -> require(char >= ' ') { "Unescaped JSON control character" }
                }
            } else {
                if (char == '"') quoted = true
                require(char !in 'A'..'Z' && (char >= ' ' || char in "\t\r\n")) { "Invalid JSON token" }
            }
        }
        require(!quoted) { "Unterminated JSON string" }
    }

    private fun utf8(text: String): ByteArray {
        val bytes = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }

    private inline fun <T> csv(header: String, rows: List<T>, columns: (T) -> List<String>): ByteArray = utf8(buildString {
        append(header).append("\r\n")
        rows.forEach { append(columns(it).joinToString(",")).append("\r\n") }
    })

    private fun csvText(value: String?): String {
        if (value == null) return ""
        val first = value.firstOrNull { !it.isWhitespace() && !it.isISOControl() && it != '\uFEFF' }
        val safe = if (first in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
