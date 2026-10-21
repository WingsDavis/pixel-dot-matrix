package com.example.core.backup

import com.example.core.goal.DailyFocusGoalSettings
import com.example.core.goal.FocusTargetType
import com.example.core.protection.DomainProfile
import com.example.core.sync.WatchFaceConfig
import com.example.core.sync.WatchFacePreset
import com.example.core.timer.TimerSettings
import com.example.data.entity.FocusTaskEntity
import com.example.data.entity.PanicLogEntity
import com.example.data.entity.SessionLogEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupCodecTest {
    @Test
    fun rejectsUnrestorableProfilesAndOverflowingIntervalsBeforeImport() {
        assertInvalid { json ->
            json.getJSONObject("settings").put("domainProfiles", JSONArray().put(
                JSONObject().put("id", "work").put("name", "Work").put("domains", JSONArray())))
        }
        assertInvalid { json ->
            json.getJSONArray("sessions").getJSONObject(0)
                .put("startTimeMillis", Long.MAX_VALUE).put("timestamp", Long.MAX_VALUE).put("durationSeconds", 1)
        }
        assertInvalid { json -> json.getJSONArray("sessions").getJSONObject(0).put("timestamp", 0) }
    }

    @Test
    fun fullJsonRoundTripPreservesEverySupportedFieldAndExplicitNull() {
        val expected = document()
        val bytes = BackupCodec.encode(expected)
        val actual = BackupCodec.decode(bytes)

        assertEquals(expected, actual)
        assertEquals(9_007_199_254_741_111L, actual.createdAtEpochMs)
        assertEquals("Caf\u00e9, \"notes\"\r\n\u65e5\u672c\u8a9e \uD83D\uDE80", actual.incidents.first().forensicNotes)
        assertTrue(JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("sessions").getJSONObject(1).has("recordId"))
        assertNull(actual.sessions[1].recordId)
        assertEquals("deleted-task", actual.sessions.first().taskId)
    }

    @Test
    fun stripsLiveRevisionsFromCurrentConfigAndPresetsWithoutMutatingOriginals() {
        val original = document().let { doc ->
            doc.copy(settings = doc.settings.copy(
                watchFace = doc.settings.watchFace.copy(localRevision = "local-2", appliedRevision = "watch-1"),
                presets = doc.settings.presets.map {
                    it.copy(config = it.config.copy(localRevision = "preset-local", appliedRevision = "preset-watch"))
                }
            ))
        }
        val bytes = BackupCodec.encode(original)
        val actual = BackupCodec.decode(bytes)
        val settingsJson = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("settings")

        assertEquals(document(), actual)
        assertEquals("local-2", original.settings.watchFace.localRevision)
        assertEquals("preset-watch", original.settings.presets.first().config.appliedRevision)
        listOf(settingsJson.getJSONObject("watchFace"), settingsJson.getJSONArray("presets").getJSONObject(0).getJSONObject("config"))
            .forEach {
                assertFalse(it.has("localRevision"))
                assertFalse(it.has("appliedRevision"))
            }
        listOf("localRevision", "appliedRevision").forEach { field ->
            assertInvalid { it.getJSONObject("settings").getJSONObject("watchFace").put(field, "old-revision") }
            assertInvalid { it.getJSONObject("settings").getJSONArray("presets").getJSONObject(0).getJSONObject("config").put(field, JSONObject.NULL) }
        }
    }

    @Test
    fun emptyHistoryAndOptionalEmptyCollectionsRoundTrip() {
        val expected = document().let {
            it.copy(sessions = emptyList(), incidents = emptyList(), tasks = emptyList(), settings = it.settings.copy(
                presets = emptyList(), recentColors = emptyList(),
                domainProfiles = listOf(DomainProfile("default", "Default", emptySet())), activeProfileId = "default"
            ))
        }
        assertEquals(expected, BackupCodec.decode(BackupCodec.encode(expected)))
    }

    @Test
    fun rejectsEveryMissingFieldAndWrongFieldTypeAtEveryObjectLevel() {
        objects(validJson()).forEachIndexed { index, obj ->
            obj.keys().asSequence().toList().forEach { name ->
                assertInvalid { objects(it)[index].remove(name) }
                val wrongType: Any = when (obj.get(name)) {
                    is JSONObject -> JSONArray()
                    is JSONArray -> JSONObject()
                    is String -> 123
                    else -> "not-the-required-type"
                }
                assertInvalid { objects(it)[index].put(name, wrongType) }
            }
        }
    }

    @Test
    fun rejectsNullForRequiredFieldsButAllowsOnlyDeclaredNullableFields() {
        objects(validJson()).forEachIndexed { index, obj ->
            val nullable = when (index) {
                1 -> setOf("taskId", "taskName", "recordId")
                2 -> setOf("heartRateSpike", "resolvedAt", "targetApp", "triggerReason", "forensicNotes", "taskName")
                else -> emptySet()
            }
            obj.keys().asSequence().toList().forEach { name ->
                if (name !in nullable) assertInvalid { objects(it)[index].put(name, JSONObject.NULL) }
            }
        }
        val json = validJson()
        listOf("taskId", "taskName", "recordId").forEach { json.getJSONArray("sessions").getJSONObject(0).put(it, JSONObject.NULL) }
        listOf("heartRateSpike", "resolvedAt", "targetApp", "triggerReason", "forensicNotes", "taskName").forEach {
            json.getJSONArray("incidents").getJSONObject(0).put(it, JSONObject.NULL)
        }
        val decoded = decode(json)
        assertNull(decoded.sessions.first().taskName)
        assertNull(decoded.incidents.first().resolvedAt)
    }

    @Test
    fun rejectsUnknownFieldsIncludingExcludedRuntimeData() {
        objects(validJson()).indices.forEach { index -> assertInvalid { objects(it)[index].put("unexpected", true) } }
        listOf("media", "permissions", "liveTimer", "outbox", "activeVpn", "logoPath").forEach { name ->
            assertInvalid { it.put(name, JSONObject()) }
            assertInvalid { it.getJSONObject("settings").put(name, JSONObject()) }
        }
    }

    @Test
    fun rejectsUnsupportedDocumentAndTimerSchemas() {
        listOf(-1, 0, 2, Int.MAX_VALUE).forEach { version ->
            assertInvalid { it.put("schemaVersion", version) }
            assertInvalid { it.getJSONObject("settings").getJSONObject("timer").put("schemaVersion", version) }
        }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(document().copy(schemaVersion = 2)) }
    }

    @Test
    fun rejectsMalformedNullOrWronglyTypedArrayEntries() {
        listOf("sessions", "incidents", "tasks").forEach { name ->
            listOf(JSONObject.NULL, "text", 1, true, JSONArray()).forEach { value ->
                assertInvalid { it.getJSONArray(name).put(0, value) }
            }
        }
        listOf("presets", "domainProfiles").forEach { name ->
            assertInvalid { it.getJSONObject("settings").getJSONArray(name).put(0, JSONObject.NULL) }
        }
        listOf(JSONObject.NULL, 1, true, JSONObject()).forEach { value ->
            assertInvalid { it.getJSONObject("settings").getJSONArray("recentColors").put(0, value) }
            assertInvalid { it.getJSONObject("settings").getJSONArray("domainProfiles").getJSONObject(0).getJSONArray("domains").put(0, value) }
        }
    }

    @Test
    fun rejectsUnknownSessionIncidentAndTaskEnums() {
        listOf("sessionType", "status").forEach { name ->
            assertInvalid { it.getJSONArray("sessions").getJSONObject(0).put(name, "UNKNOWN") }
        }
        listOf("incidentType", "incidentStatus", "sourceDevice").forEach { name ->
            assertInvalid { it.getJSONArray("incidents").getJSONObject(0).put(name, "UNKNOWN") }
        }
        assertInvalid { it.getJSONArray("tasks").getJSONObject(0).put("status", "UNKNOWN") }
        assertInvalid { it.getJSONObject("settings").getJSONObject("dailyGoal").put("type", "UNKNOWN") }
    }

    @Test
    fun acceptsAllSupportedSessionAndIncidentEnums() {
        listOf("FOCUS", "SHORT_BREAK", "LONG_BREAK").forEach { type ->
            listOf("COMPLETED", "INTERRUPTED", "PANIC").forEach { status ->
                val json = validJson()
                json.getJSONArray("sessions").getJSONObject(0).put("sessionType", type).put("status", status)
                assertEquals(type, decode(json).sessions.first().sessionType)
            }
        }
        listOf("PANIC", "DISTRACTION", "FRUSTRATION", "PROTECTION_OVERRIDE").forEach { type ->
            listOf("OPEN", "PENDING_DETAIL", "CLOSED", "DISMISSED").forEach { status ->
                val json = validJson()
                json.getJSONArray("incidents").getJSONObject(0).put("incidentType", type).put("incidentStatus", status)
                assertEquals(status, decode(json).incidents.first().incidentStatus)
            }
        }
        listOf("PENDING", "COMPLETED", "SKIPPED").forEach { status ->
            val json = validJson()
            json.getJSONArray("tasks").getJSONObject(0).put("status", status)
            assertEquals(status, decode(json).tasks.first().status)
        }
    }

    @Test
    fun rejectsDuplicateStableIdsAndConflictingLocalPrimaryKeys() {
        listOf("sessions", "incidents", "tasks").forEach { name ->
            assertInvalid {
                val rows = it.getJSONArray(name)
                val duplicate = JSONObject(rows.getJSONObject(0).toString())
                if (name != "tasks") duplicate.put("id", 99)
                rows.put(duplicate)
            }
        }
        listOf("sessions" to "recordId", "incidents" to "incidentId").forEach { (name, stableId) ->
            assertInvalid {
                val rows = it.getJSONArray(name)
                rows.put(JSONObject(rows.getJSONObject(0).toString()).put(stableId, "different-stable-id"))
            }
        }
        listOf("presets", "domainProfiles").forEach { name ->
            assertInvalid {
                val rows = it.getJSONObject("settings").getJSONArray(name)
                rows.put(JSONObject(rows.getJSONObject(0).toString()))
            }
        }
        assertInvalid {
            val row = document().sessions[1]
            it.getJSONArray("sessions").getJSONObject(0).put("recordId", BackupCodec.sessionRecordId(row))
        }
        assertInvalid {
            val row = document().incidents[1].copy(incidentId = "legacy-2")
            val incidents = it.getJSONArray("incidents")
            incidents.getJSONObject(1).put("incidentId", row.incidentId)
            incidents.getJSONObject(0).put("incidentId", BackupCodec.incidentRecordId(row))
        }
    }

    @Test
    fun rejectsBlankIdsAndRequiredNames() {
        listOf("", " \t").forEach { value ->
            listOf("recordId", "taskId").forEach { name ->
                assertInvalid { it.getJSONArray("sessions").getJSONObject(0).put(name, value) }
            }
            assertInvalid { it.getJSONArray("incidents").getJSONObject(0).put("incidentId", value) }
            listOf("id", "title").forEach { name -> assertInvalid { it.getJSONArray("tasks").getJSONObject(0).put(name, value) } }
            listOf("presets", "domainProfiles").forEach { array ->
                listOf("id", "name").forEach { name ->
                    assertInvalid { it.getJSONObject("settings").getJSONArray(array).getJSONObject(0).put(name, value) }
                }
            }
            assertInvalid { it.getJSONObject("settings").put("activeProfileId", value) }
        }
    }

    @Test
    fun validatesTimerAndGoalBoundsWithoutSanitizing() {
        val ranges = mapOf(
            "timer" to mapOf(
                "focusDurationSeconds" to TimerSettings.FOCUS_RANGE,
                "shortBreakDurationSeconds" to TimerSettings.SHORT_BREAK_RANGE,
                "longBreakDurationSeconds" to TimerSettings.LONG_BREAK_RANGE,
                "longBreakCadence" to TimerSettings.CADENCE_RANGE
            ),
            "dailyGoal" to mapOf("targetMinutes" to 1..1440, "targetSessions" to 1..100, "resetHour" to 0..23)
        )
        ranges.forEach { (section, fields) ->
            fields.forEach { (name, range) ->
                listOf(range.first - 1, range.last + 1).forEach { value ->
                    assertInvalid { it.getJSONObject("settings").getJSONObject(section).put(name, value) }
                }
                listOf(range.first, range.last).forEach { value ->
                    val json = validJson()
                    json.getJSONObject("settings").getJSONObject(section).put(name, value)
                    val restored = JSONObject(BackupCodec.encode(decode(json)).toString(Charsets.UTF_8))
                    assertEquals(value, restored.getJSONObject("settings").getJSONObject(section).getInt(name))
                }
            }
        }
        assertInvalid { it.getJSONObject("settings").getJSONObject("dailyGoal").put("timeZoneId", "No/Such_Zone") }
        assertThrows(IllegalArgumentException::class.java) {
            val doc = document()
            BackupCodec.encode(doc.copy(settings = doc.settings.copy(timer = TimerSettings(focusDurationSeconds = 0))))
        }
    }

    @Test
    fun validatesNonnegativeNumbersIntegerWidthsAndTaskEstimateBounds() {
        objects(validJson()).forEachIndexed { index, obj ->
            obj.keys().asSequence().filter { obj.get(it) is Number }.toList().forEach { name ->
                assertInvalid { objects(it)[index].put(name, -1) }
            }
        }
        mapOf(
            "sessions" to listOf("id", "durationSeconds"),
            "incidents" to listOf("id", "heartRateSpike", "stepsTaken"),
            "tasks" to listOf("sortOrder", "estimateSessions", "completedSessions")
        ).forEach { (array, fields) ->
            fields.forEach { name -> assertInvalid { it.getJSONArray(array).getJSONObject(0).put(name, Int.MAX_VALUE.toLong() + 1) } }
        }
        listOf(0, 13).forEach { estimate -> assertInvalid { it.getJSONArray("tasks").getJSONObject(0).put("estimateSessions", estimate) } }
        listOf(1, 12).forEach { estimate ->
            val json = validJson()
            json.getJSONArray("tasks").getJSONObject(0).put("estimateSessions", estimate).put("completedSessions", 0)
            assertEquals(estimate, decode(json).tasks.first().estimateSessions)
        }
        val json = validJson().put("createdAtEpochMs", Long.MAX_VALUE)
        json.getJSONArray("sessions").getJSONObject(0).put("durationSeconds", Int.MAX_VALUE)
        json.getJSONArray("incidents").getJSONObject(0).put("stepsTaken", Int.MAX_VALUE).put("resolvedAt", 0)
        json.getJSONArray("tasks").getJSONObject(0).put("updatedAt", 0)
        assertEquals(Long.MAX_VALUE, decode(json).createdAtEpochMs)
    }

    @Test
    fun validatesTaskCompletionCountsForPendingCompletedAndSkippedTasks() {
        listOf(1, 12).forEach { estimate ->
            listOf(FocusTaskEntity.STATUS_PENDING, FocusTaskEntity.STATUS_COMPLETED, FocusTaskEntity.STATUS_SKIPPED).forEach { status ->
                val maximum = if (status == FocusTaskEntity.STATUS_PENDING) estimate - 1 else estimate
                listOf(0, maximum).distinct().forEach { completed ->
                    val json = validJson()
                    json.getJSONArray("tasks").getJSONObject(0).put("status", status)
                        .put("estimateSessions", estimate).put("completedSessions", completed)
                    val restored = decode(json)
                    assertEquals(completed, restored.tasks.single().completedSessions)
                    assertEquals(restored, BackupCodec.decode(BackupCodec.encode(restored)))
                }
                listOf(-1, maximum + 1, Int.MAX_VALUE).forEach { completed ->
                    assertInvalid {
                        it.getJSONArray("tasks").getJSONObject(0).put("status", status)
                            .put("estimateSessions", estimate).put("completedSessions", completed)
                    }
                    val doc = document()
                    val task = doc.tasks.single().copy(status = status, estimateSessions = estimate, completedSessions = completed)
                    assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(doc.copy(tasks = listOf(task))) }
                }
            }
        }
    }

    @Test
    fun validatesWatchFaceModesHexColorsAndRecentColorLimit() {
        mapOf(
            "leftSlotMode" to listOf("custom_text", "phase", "heart_rate", "next_break", "streak", "daily_target", "hidden"),
            "bottomRightSlotMode" to listOf("date", "phase", "timer", "battery", "daily_target", "hidden"),
            "ambientStyle" to listOf("dim", "minimal")
        ).forEach { (name, choices) ->
            assertInvalid { it.getJSONObject("settings").getJSONObject("watchFace").put(name, "UNKNOWN") }
            choices.forEach { value ->
                val json = validJson()
                json.getJSONObject("settings").getJSONObject("watchFace").put(name, value)
                decode(json)
            }
        }
        listOf("red", "ffffff", "#fff", "#gg112233", " #ff112233", "#1234567").forEach { color ->
            listOf("hourColor", "minuteColor", "secondColor").forEach { name ->
                assertInvalid { it.getJSONObject("settings").getJSONObject("watchFace").put(name, color) }
                assertInvalid { it.getJSONObject("settings").getJSONArray("presets").getJSONObject(0).getJSONObject("config").put(name, color) }
            }
            assertInvalid { it.getJSONObject("settings").getJSONArray("recentColors").put(0, color) }
        }
        assertInvalid { it.getJSONObject("settings").put("recentColors", JSONArray(List(9) { index -> "#00000$index" })) }
        assertInvalid { it.getJSONObject("settings").put("recentColors", JSONArray(listOf("#ffffff", "#ffffff"))) }
        val json = validJson()
        json.getJSONObject("settings").put("recentColors", JSONArray(List(8) { "#00000$it" }))
        assertEquals(8, decode(json).settings.recentColors.size)
    }

    @Test
    fun rejectsInvalidNoncanonicalAndDuplicateDomainsAndMissingActiveProfile() {
        listOf("", "bad..domain", "-bad.com", "https://example.com/path", "Example.com", "example.com.", "b\u00fccher.de", "a".repeat(64) + ".com").forEach { domain ->
            assertInvalid { it.getJSONObject("settings").getJSONArray("domainProfiles").getJSONObject(0).put("domains", JSONArray(listOf(domain))) }
        }
        assertInvalid { it.getJSONObject("settings").getJSONArray("domainProfiles").getJSONObject(0).put("domains", JSONArray(listOf("example.com", "example.com"))) }
        assertInvalid { it.getJSONObject("settings").put("activeProfileId", "absent") }
        assertInvalid { it.getJSONObject("settings").put("domainProfiles", JSONArray()) }
    }

    @Test
    fun rejectsMalformedTrailingDataWrongIntegerSyntaxAndInvalidUnicode() {
        val raw = validJson().toString()
        val withText = validJson().also { it.getJSONObject("settings").getJSONObject("watchFace").put("customText", "sentinel") }.toString()
        listOf(
            "", "{", "[]", "null", raw.dropLast(1), raw + "{}", raw + "garbage", raw + "\u0000", raw + " //comment",
            raw.replaceFirst("{", "{\"schemaVersion\":1,"),
            raw.replaceFirst("{", "{\"schema\\u0056ersion\":1,"),
            raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1e0"),
            raw.replace("\"schemaVersion\":1", "\"schemaVersion\":01"),
            raw.replace("\"autoFocusProtection\":true", "\"autoFocusProtection\":TRUE"),
            raw.replace("\"recordId\":null", "\"recordId\":NULL"),
            withText.replace("\"sentinel\"", "\"unescaped\nnewline\""),
            withText.replace("\"sentinel\"", "\"bad\\q\""),
            withText.replace("\"sentinel\"", "\"bad\\uD800\""),
            withText.replace("\"sentinel\"", "\"bad\\uDC00\""),
            raw.replace("9007199254741111", "9223372036854775808")
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(bad.toByteArray(Charsets.UTF_8)) } }
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        val prefix = "{\"bad\":\"".toByteArray(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(prefix + invalidUtf8 + "\"}".toByteArray(Charsets.UTF_8)) }
        assertEquals(document(), BackupCodec.decode((" \r\n\t" + raw + "\t\n ").toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun rejectsTooDeepJsonBeforeStackExhaustion() {
        val raw = "{\"nested\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(raw.toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun enforcesSixteenMiBByteLimitOnImportAndExport() {
        assertEquals(16 * 1024 * 1024, BackupCodec.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(ByteArray(BackupCodec.MAX_BYTES + 1)) }
        val raw = BackupCodec.encode(document())
        val atLimit = raw + ByteArray(BackupCodec.MAX_BYTES - raw.size) { ' '.code.toByte() }
        assertEquals(document(), BackupCodec.decode(atLimit))
        val doc = document()
        val tooLarge = doc.copy(settings = doc.settings.copy(watchFace = doc.settings.watchFace.copy(customText = "x".repeat(BackupCodec.MAX_BYTES))))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(tooLarge) }
    }

    @Test
    fun legacyIdentityHasAFixedVersionedSha256VectorAndSurvivesRoundTrip() {
        val row = SessionLogEntity(
            id = 7, sessionType = "FOCUS", startTimeMillis = 1000, durationSeconds = 60,
            status = "COMPLETED", taskName = "Legacy", timestamp = 61000
        )
        val expected = "legacy-session-v1:9f65733eb5ad341ee9a567258ce72be2f22155d03d3497ccc3c0072eeb8595b9"
        assertEquals(expected, BackupCodec.sessionRecordId(row))
        assertEquals(expected, BackupCodec.sessionRecordId(row.copy()))
        val restored = BackupCodec.decode(BackupCodec.encode(document().copy(sessions = listOf(row)))).sessions.single()
        assertEquals(expected, BackupCodec.sessionRecordId(restored))
        assertNull(restored.recordId)
        assertEquals("stable-record", BackupCodec.sessionRecordId(row.copy(id = 999, recordId = "stable-record")))
    }

    @Test
    fun legacyIdentityIncludesOriginalIdEveryContentFieldAndNullMarkers() {
        val original = document().sessions[1]
        val changes = listOf(
            original.copy(id = original.id + 1), original.copy(sessionType = "LONG_BREAK"),
            original.copy(startTimeMillis = original.startTimeMillis + 1), original.copy(durationSeconds = original.durationSeconds + 1),
            original.copy(status = "PANIC"), original.copy(taskId = ""), original.copy(taskName = ""),
            original.copy(timestamp = original.timestamp + 1)
        )
        changes.forEach { assertNotEquals(BackupCodec.sessionRecordId(original), BackupCodec.sessionRecordId(it)) }
        assertEquals(changes.size, changes.map(BackupCodec::sessionRecordId).toSet().size)
        val samePkDifferentInstall = original.copy(startTimeMillis = original.startTimeMillis + 999)
        assertNotEquals(BackupCodec.sessionRecordId(original), BackupCodec.sessionRecordId(samePkDifferentInstall))
    }

    @Test
    fun legacyIncidentIdentityHasAFixedVersionedSha256VectorAndSurvivesRoundTrip() {
        val row = PanicLogEntity(
            id = 7, timestamp = 1000, heartRateSpike = null, stepsTaken = 50, resolvedAt = 61000,
            targetApp = null, triggerReason = "Stuck", forensicNotes = "Legacy", taskName = null,
            incidentId = "legacy-1", incidentType = "PANIC", sourceDevice = "PHONE", incidentStatus = "CLOSED"
        )
        val expected = "legacy-incident-v1:7423bee341860dbe788ae25ec44b0c0d7c273520314fc7f4af2e5454bf0e20f7"
        assertEquals(expected, BackupCodec.incidentRecordId(row))
        assertEquals(expected, BackupCodec.incidentRecordId(row.copy()))
        val restored = BackupCodec.decode(BackupCodec.encode(document().copy(incidents = listOf(row)))).incidents.single()
        assertEquals("legacy-1", restored.incidentId)
        assertEquals(expected, BackupCodec.incidentRecordId(restored))
    }

    @Test
    fun legacyIncidentIdentitySeparatesInstallsAndIncludesEveryPersistedField() {
        val original = document().incidents.first().copy(incidentId = "legacy-1")
        val unrelatedInstall = original.copy(timestamp = original.timestamp + 999, forensicNotes = "Unrelated incident")
        assertEquals(original.id, unrelatedInstall.id)
        assertEquals(original.incidentId, unrelatedInstall.incidentId)
        assertNotEquals(BackupCodec.incidentRecordId(original), BackupCodec.incidentRecordId(unrelatedInstall))
        val changes = listOf(
            original.copy(id = original.id + 1), original.copy(timestamp = original.timestamp + 1),
            original.copy(heartRateSpike = null), original.copy(stepsTaken = original.stepsTaken + 1),
            original.copy(resolvedAt = null), original.copy(targetApp = null), original.copy(triggerReason = null),
            original.copy(forensicNotes = null), original.copy(taskName = null), original.copy(incidentId = "legacy-2"),
            original.copy(incidentType = "FRUSTRATION"), original.copy(sourceDevice = "WEAR"), original.copy(incidentStatus = "DISMISSED")
        )
        changes.forEach { assertNotEquals(BackupCodec.incidentRecordId(original), BackupCodec.incidentRecordId(it)) }
        assertEquals(changes.size, changes.map(BackupCodec::incidentRecordId).toSet().size)
        assertNotEquals(
            BackupCodec.incidentRecordId(original.copy(forensicNotes = null)),
            BackupCodec.incidentRecordId(original.copy(forensicNotes = ""))
        )
    }

    @Test
    fun preservesModernIncidentIdsAndDoesNotRehashGeneratedLegacyIdentities() {
        val row = document().incidents.first()
        val generated = BackupCodec.incidentRecordId(row.copy(incidentId = "legacy-1"))
        listOf("a0915843-faa5-4acf-b0c7-0442b43d704e", generated, "legacy-x", "legacy-1-other", "legacy-1\n", "legacy-\u0661").forEach { id ->
            assertEquals(id, BackupCodec.incidentRecordId(row.copy(incidentId = id)))
            assertEquals(id, BackupCodec.incidentRecordId(row.copy(id = 999, incidentId = id, forensicNotes = "Changed after restore")))
        }
    }

    @Test
    fun sessionsCsvHasFixedHeadersEscapesAndUnchangedNumericColumns() {
        val row = SessionLogEntity(
            id = 7, sessionType = "FOCUS", startTimeMillis = 1000, durationSeconds = 60, status = "COMPLETED",
            taskName = "Plan, \"Caf\u00e9\"\r\n\u65e5\u672c\u8a9e", timestamp = 61000, recordId = "s-1"
        )
        val expected = sessionHeader + "\r\n" +
            "7,\"FOCUS\",1000,60,\"COMPLETED\",,\"Plan, \"\"Caf\u00e9\"\"\r\n\u65e5\u672c\u8a9e\",61000,\"s-1\"\r\n"
        assertEquals(expected, BackupCodec.sessionsCsv(listOf(row)).toString(Charsets.UTF_8))
        assertEquals(sessionHeader + "\r\n", BackupCodec.sessionsCsv(emptyList()).toString(Charsets.UTF_8))
    }

    @Test
    fun incidentsCsvHasFixedHeadersEscapesUnicodeAndNullableNumbers() {
        val row = PanicLogEntity(
            id = 8, timestamp = 123, heartRateSpike = null, stepsTaken = 50, resolvedAt = null,
            targetApp = "app,one", triggerReason = "\"stuck\"", forensicNotes = "Caf\u00e9\n\uD83D\uDE80", taskName = "",
            incidentId = "i-1", incidentType = "PANIC", sourceDevice = "PHONE", incidentStatus = "OPEN"
        )
        val expected = incidentHeader + "\r\n" +
            "8,123,,50,,\"app,one\",\"\"\"stuck\"\"\",\"Caf\u00e9\n\uD83D\uDE80\",\"\",\"i-1\",\"PANIC\",\"PHONE\",\"OPEN\"\r\n"
        assertEquals(expected, BackupCodec.incidentsCsv(listOf(row)).toString(Charsets.UTF_8))
        assertEquals(incidentHeader + "\r\n", BackupCodec.incidentsCsv(emptyList()).toString(Charsets.UTF_8))
    }

    @Test
    fun bothCsvExportsProtectEveryTextColumnFromSpreadsheetFormulas() {
        listOf("=1+1", "+SUM(1,2)", "-1+1", "@SUM(A1)", " \t=HYPERLINK(\"x\")", "\ttext", "\rtext", "\ntext", "\uFEFF =1").forEach { value ->
            val cell = "\"'${value.replace("\"", "\"\"")}\""
            val session = SessionLogEntity(1, value, 2, 3, value, value, value, 4, value)
            val incident = PanicLogEntity(1, 2, 3, 4, 5, value, value, value, value, value, value, value, value)
            assertEquals(sessionHeader + "\r\n1,$cell,2,3,$cell,$cell,$cell,4,$cell\r\n", BackupCodec.sessionsCsv(listOf(session)).toString(Charsets.UTF_8))
            assertEquals(incidentHeader + "\r\n1,2,3,4,5," + List(8) { cell }.joinToString(",") + "\r\n", BackupCodec.incidentsCsv(listOf(incident)).toString(Charsets.UTF_8))
        }
        val safe = document().sessions.first().copy(taskName = "'already text", durationSeconds = -1)
        val csv = BackupCodec.sessionsCsv(listOf(safe)).toString(Charsets.UTF_8)
        assertTrue(csv.contains(",\"'already text\","))
        assertTrue(csv.contains(",-1,"))
        assertFalse(csv.contains("\"''already text\""))
    }

    private fun assertInvalid(change: (JSONObject) -> Unit) {
        val json = validJson()
        change(json)
        assertThrows(IllegalArgumentException::class.java) { decode(json) }
    }

    private fun validJson() = JSONObject(BackupCodec.encode(document()).toString(Charsets.UTF_8))
    private fun decode(json: JSONObject) = BackupCodec.decode(json.toString().toByteArray(Charsets.UTF_8))

    private fun objects(root: JSONObject): List<JSONObject> {
        val settings = root.getJSONObject("settings")
        val preset = settings.getJSONArray("presets").getJSONObject(0)
        return listOf(
            root, root.getJSONArray("sessions").getJSONObject(0), root.getJSONArray("incidents").getJSONObject(0),
            root.getJSONArray("tasks").getJSONObject(0), settings, settings.getJSONObject("timer"),
            settings.getJSONObject("dailyGoal"), settings.getJSONObject("watchFace"), preset,
            preset.getJSONObject("config"), settings.getJSONArray("domainProfiles").getJSONObject(0)
        )
    }

    private fun document(): BackupDocument {
        val face = WatchFaceConfig(
            hourColor = "#FF12ab34", minuteColor = "#ABCDEF", secondColor = "#00112233",
            customText = "Stay, \"focused\"\n\u65e5\u672c\u8a9e", showLogo = false, preset = "My \u00e9dition",
            leftSlotMode = "next_break", bottomRightSlotMode = "daily_target", ambientStyle = "minimal"
        )
        return BackupDocument(
            createdAtEpochMs = 9_007_199_254_741_111L,
            sessions = listOf(
                SessionLogEntity(1, "FOCUS", 1000, 60, "COMPLETED", "deleted-task", "Past task", 61000, "record-1"),
                SessionLogEntity(2, "SHORT_BREAK", 62000, 0, "INTERRUPTED", null, null, 62000, null),
                SessionLogEntity(3, "LONG_BREAK", 63000, 300, "PANIC", null, "", 363000, null)
            ),
            incidents = listOf(
                PanicLogEntity(
                    1, 1000, 138, 50, 61000, "app,one", "Stuck", "Caf\u00e9, \"notes\"\r\n\u65e5\u672c\u8a9e \uD83D\uDE80",
                    "Past task", "incident-1", "PANIC", "PHONE", "CLOSED"
                ),
                PanicLogEntity(2, 62000, null, 0, null, null, null, null, null, "incident-2", "DISTRACTION", "WEAR", "PENDING_DETAIL")
            ),
            tasks = listOf(FocusTaskEntity("task-1", "Task, \u65e5\u672c\u8a9e", 0, "PENDING", 12, 1, 1000, 2000)),
            settings = BackupSettings(
                TimerSettings(1800, 120, 600, 6, true, true, false, false, false),
                DailyFocusGoalSettings(true, FocusTargetType.SESSIONS, 90, 3, "Africa/Accra", 4),
                face, listOf(WatchFacePreset("preset-1", "Caf\u00e9", face.copy(customText = ""))),
                listOf("#ABCDEF", "#00112233"),
                listOf(
                    DomainProfile("default", "Default", setOf("example.com", "xn--bcher-kva.de")),
                    DomainProfile("work", "Work \u65e5\u672c\u8a9e", emptySet())
                ),
                "work", true
            )
        )
    }

    private val sessionHeader = "id,sessionType,startTimeMillis,durationSeconds,status,taskId,taskName,timestamp,recordId"
    private val incidentHeader = "id,timestamp,heartRateSpike,stepsTaken,resolvedAt,targetApp,triggerReason,forensicNotes,taskName,incidentId,incidentType,sourceDevice,incidentStatus"
}
