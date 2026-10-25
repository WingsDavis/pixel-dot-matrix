package com.example.wear

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.example.core.sync.SyncAppliedAck
import com.example.core.sync.TimerCommand
import com.example.core.sync.WearCommandProtocol
import com.google.android.gms.wearable.DataMap
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WearCommandOutboxTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var transport: RecordingTransport
    private var time = 1_000L
    private val journalFile get() = File(context.filesDir, "wear_command_outbox.json")

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = folder.root
            override fun getSharedPreferences(name: String, mode: Int) =
                application.getSharedPreferences("${folder.root.name}_$name", mode)
        }
        transport = RecordingTransport()
    }

    @Test fun migratesLegacySetWithoutChangingIdsPayloadsOrInventingCreationOrder() {
        val first = TimerCommand("legacy-one", "START", 7)
        val second = TimerCommand("legacy-two", "DISTRACTION", 8)
        val source = setOf(
            "/pomodoro/control\n${String(first.encode(), Charsets.UTF_8)}",
            "/incident/mark\n${String(second.encode(), Charsets.UTF_8)}"
        )
        val prefs = context.getSharedPreferences("wear_command_outbox", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putStringSet("items", source).commit())
        val outbox = outbox()
        assertEquals(setOf(first.id, second.id), outbox.records.value.map { it.id }.toSet())
        assertTrue(outbox.records.value.all { it.createdAt == 0L && it.status == WearCommandStatus.QUEUED })
        assertEquals(source, prefs.getStringSet("items", emptySet()))
        assertTrue(transport.publications.isEmpty())
        outbox.flush()
        assertEquals(setOf(first, second), transport.publications.map { it.command }.toSet())
        outbox.acknowledge(first.id)
        assertEquals(WearCommandStatus.APPLIED, outbox().records.value.single { it.id == first.id }.status)
        assertEquals(2, outbox().records.value.size)
    }

    @Test fun malformedLegacyEntriesRemainVisibleAndSourceIsRetained() {
        val source = setOf("broken", "/unsupported\nv2|original-id|4|wear|START")
        val prefs = context.getSharedPreferences("wear_command_outbox", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putStringSet("items", source).commit())
        val outbox = outbox()
        assertEquals(2, outbox.records.value.size)
        assertTrue(outbox.records.value.all { it.status == WearCommandStatus.FAILED && it.error != null })
        assertTrue(outbox.records.value.any { it.id == "original-id" })
        assertEquals(source, prefs.getStringSet("items", emptySet()))
        outbox.flush()
        assertTrue(transport.publications.isEmpty())
    }

    @Test fun offlinePublishIsDurableButIsNotAnAppliedAckOrImmediateMessageDelivery() {
        val outbox = outbox()
        assertFalse(outbox.sendOrQueue("/pomodoro/control", "START", 7))
        val record = outbox.records.value.single()
        assertEquals(WearCommandStatus.QUEUED, record.status)
        assertNull(record.error)
        assertEquals(record.id, transport.publications.single().id)
        assertEquals(TimerCommand(record.id, "START", 7), transport.publications.single().command)
        assertEquals(listOf(record), outbox().records.value)
    }

    @Test fun recreationRetriesSameIdAndRevisionWithChangedDataItemMetadata() {
        val first = outbox()
        first.sendOrQueue("/panic/trigger", "TRIGGER_PANIC", 7)
        val original = transport.publications.single()
        time += 30_000
        outbox().flush()
        val retried = transport.publications.last()
        assertEquals(original.command, retried.command)
        assertEquals(original.createdAt, retried.createdAt)
        assertEquals(2, retried.attempt)
        assertTrue(retried.updatedAt > original.updatedAt)
    }

    @Test fun appliedRemovesPendingButRetainsReceiptAcrossRecreation() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7)
        val record = outbox.records.value.single()
        outbox.acknowledge(SyncAppliedAck(record.id, true).encode())
        assertEquals(record.copy(status = WearCommandStatus.APPLIED), outbox.records.value.single())
        time += 900_000
        outbox().flush()
        assertEquals(1, transport.publications.size)
        assertEquals(WearCommandStatus.APPLIED, outbox().records.value.single().status)
    }

    @Test fun failedAckRetainsReasonAndOnlyManualRetryRefreshesBaseRevision() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "PAUSE", 7)
        val original = outbox.records.value.single()
        outbox.acknowledge(SyncAppliedAck(original.id, false, "Stale timer revision").encode())
        time += 3_600_000
        outbox().flush()
        assertEquals(1, transport.publications.size)
        assertEquals(WearCommandStatus.FAILED, outbox().records.value.single().status)
        assertEquals("Stale timer revision", outbox.records.value.single().error)
        transport.online = true
        assertTrue(outbox.retry(original.id, 42))
        val retry = transport.publications.last()
        assertEquals(TimerCommand(original.id, "PAUSE", 42), retry.command)
        assertEquals(original.createdAt, retry.createdAt)
        assertEquals(1, retry.attempt)
        assertEquals(WearCommandStatus.DELIVERED, outbox.records.value.single().status)
        assertNull(outbox.records.value.single().error)
        assertFalse(outbox.retry(original.id, 99))
    }

    @Test fun duplicateAndMalformedAcksDoNotRegressAppliedOrDropFailures() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 1)
        val id = outbox.records.value.single().id
        val failed = SyncAppliedAck(id, false, "Rejected").encode()
        outbox.acknowledge(failed)
        val failedBytes = journalFile.readBytes()
        outbox.acknowledge(failed)
        assertArrayEquals(failedBytes, journalFile.readBytes())
        assertEquals(WearCommandStatus.FAILED, outbox.records.value.single().status)
        outbox.acknowledge(SyncAppliedAck(id, true).encode())
        val appliedBytes = journalFile.readBytes()
        outbox.acknowledge(failed)
        outbox.acknowledge(id)
        outbox.acknowledge("$id|UNKNOWN|".toByteArray())
        outbox.acknowledge(SyncAppliedAck("unknown", true).encode())
        assertArrayEquals(appliedBytes, journalFile.readBytes())
        assertEquals(WearCommandStatus.APPLIED, outbox.records.value.single().status)
    }

    @Test fun keepsOnlyBoundedRecentAppliedReceiptsWithoutPruningPendingOrFailed() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 1)
        val failedId = outbox.records.value.last().id
        outbox.acknowledge(SyncAppliedAck(failedId, false, "Rejected").encode())
        outbox.sendOrQueue("/panic/resolve", "RESOLVE_PANIC", 1)
        val pendingId = outbox.records.value.last().id
        val appliedIds = mutableListOf<String>()
        repeat(WearCommandOutbox.MAX_APPLIED_RECEIPTS + 3) {
            outbox.sendOrQueue("/pomodoro/control", "PAUSE", 1)
            val id = outbox.records.value.last().id
            appliedIds += id
            outbox.acknowledge(id)
        }
        val records = outbox().records.value
        assertEquals(appliedIds.takeLast(WearCommandOutbox.MAX_APPLIED_RECEIPTS).toSet(),
            records.filter { it.status == WearCommandStatus.APPLIED }.map { it.id }.toSet())
        assertEquals(WearCommandStatus.FAILED, records.single { it.id == failedId }.status)
        assertEquals(WearCommandStatus.QUEUED, records.single { it.id == pendingId }.status)
    }

    @Test fun multipleInstancesShareFlowAndConcurrentQueuesDoNotLoseCommands() {
        val first = outbox()
        val second = outbox()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 16).map { index ->
                executor.submit<Boolean> {
                    (if (index % 2 == 0) first else second).sendOrQueue("/pomodoro/control", "START", index.toLong())
                }
            }
            futures.forEach { assertFalse(it.get(10, TimeUnit.SECONDS)) }
            assertEquals(16, first.records.value.size)
            assertEquals(16, first.records.value.map { it.id }.toSet().size)
            assertEquals(first.records.value, second.records.value)
            assertEquals(first.records.value, outbox().records.value)
            assertEquals(16, transport.publications.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun concurrentFlushCannotOverlapAnAttemptOrOverwriteItsAck() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.onPublish = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val first = outbox()
        val second = outbox()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Boolean> { first.sendOrQueue("/pomodoro/control", "START", 1) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            time += 3_600_000
            second.flush()
            val id = second.records.value.single().id
            second.acknowledge(SyncAppliedAck(id, true).encode())
            release.countDown()
            assertFalse(future.get(10, TimeUnit.SECONDS))
            assertEquals(1, transport.publications.size)
            assertTrue(transport.messages.isEmpty())
            assertEquals(WearCommandStatus.APPLIED, first.records.value.single().status)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun storageFailureThrowsBeforeTransportAndDoesNotPublishPhantomState() {
        val events = mutableListOf<String>()
        val broken = WearCommandOutbox(context, transport, { events += "schedule" }, { time },
            persist = { _, _ -> events += "persist"; throw IOException("Disk full") })
        assertThrows(IOException::class.java) { broken.sendOrQueue("/pomodoro/control", "START", 1) }
        assertEquals(listOf("schedule", "persist"), events)
        assertTrue(transport.publications.isEmpty())
        assertTrue(transport.messages.isEmpty())
        assertTrue(broken.records.value.isEmpty())
        assertTrue(outbox().records.value.isEmpty())
    }

    @Test fun ackStorageFailureKeepsLastCommittedPendingState() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 1)
        val record = outbox.records.value.single()
        val broken = WearCommandOutbox(context, transport, {}, { time },
            persist = { _, _ -> throw IOException("Disk full") })
        assertThrows(IOException::class.java) { broken.acknowledge(SyncAppliedAck(record.id, true).encode()) }
        assertEquals(listOf(record), outbox.records.value)
        assertEquals(listOf(record), outbox().records.value)
    }

    @Test fun attemptStorageFailureDoesNotSendAndLeavesTheCommittedRetryDue() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7)
        val original = transport.publications.single()
        time += 30_000
        val broken = WearCommandOutbox(context, transport, {}, { time },
            persist = { _, _ -> throw IOException("Disk full") })
        assertThrows(IOException::class.java) { broken.flush() }
        assertEquals(1, transport.publications.size)
        assertEquals(1, transport.messages.size)
        outbox().flush()
        assertEquals(original.command, transport.publications.last().command)
        assertEquals(2, transport.publications.last().attempt)
    }

    @Test fun manualRetryStorageFailureCannotClearFailureOrSendARebasedCommand() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7)
        val id = outbox.records.value.single().id
        outbox.acknowledge(SyncAppliedAck(id, false, "Rejected").encode())
        val failed = outbox.records.value.single()
        val broken = WearCommandOutbox(context, transport, {}, { time },
            persist = { _, _ -> throw IOException("Disk full") })
        assertThrows(IOException::class.java) { broken.retry(id, 42) }
        assertEquals(listOf(failed), outbox.records.value)
        assertEquals(listOf(failed), outbox().records.value)
        assertEquals(1, transport.publications.size)
        assertEquals(7L, transport.publications.single().command.baseRevision)
    }

    @Test fun migrationStorageFailureLeavesEveryLegacyItemRecoverable() {
        val source = setOf("/pomodoro/control\nv2|legacy-one|7|wear|START")
        val prefs = context.getSharedPreferences("wear_command_outbox", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putStringSet("items", source).commit())
        assertThrows(IOException::class.java) {
            WearCommandOutbox(context, transport, {}, { time },
                persist = { _, _ -> throw IOException("Disk full") })
        }
        assertEquals(source, prefs.getStringSet("items", emptySet()))
        assertFalse(journalFile.exists())
        assertTrue(transport.publications.isEmpty())
        assertEquals("legacy-one", outbox().records.value.single().id)
    }

    @Test fun schedulerFailureDoesNotLeaveAnUnscheduledQueueOrSend() {
        val outbox = WearCommandOutbox(context, transport, { throw IOException("Job unavailable") }, { time })
        assertThrows(IOException::class.java) { outbox.sendOrQueue("/pomodoro/control", "START", 1) }
        assertFalse(journalFile.exists())
        assertTrue(outbox.records.value.isEmpty())
        assertTrue(transport.publications.isEmpty())
    }

    @Test fun transportFailuresArePersistedAndRetriedWithSameCommandAfterBackoff() {
        transport.publishFailure = IOException("Data Layer unavailable")
        transport.messageFailure = IOException("Message Layer unavailable")
        val outbox = outbox()
        assertFalse(outbox.sendOrQueue("/pomodoro/control", "START", 7))
        val record = outbox.records.value.single()
        assertEquals("Data Layer unavailable", record.error)
        assertEquals(listOf(record), outbox().records.value)
        outbox.flush()
        assertEquals(1, transport.publications.size)
        transport.publishFailure = null
        transport.messageFailure = null
        transport.online = true
        time += 30_000
        outbox().flush()
        assertEquals(TimerCommand(record.id, "START", 7), transport.publications.last().command)
        assertEquals(WearCommandStatus.DELIVERED, outbox.records.value.single().status)
        assertNull(outbox.records.value.single().error)
    }

    @Test fun fallbackMessageCanDeliverWhenDataPublishFails() {
        transport.publishFailure = IOException("Data Layer unavailable")
        transport.online = true
        val outbox = outbox()
        assertTrue(outbox.sendOrQueue("/panic/resolve", "RESOLVE_PANIC", 7))
        assertEquals(WearCommandStatus.DELIVERED, outbox.records.value.single().status)
        assertEquals("/panic/resolve", transport.messages.single().first)
        assertArrayEquals(transport.publications.single().payload, transport.messages.single().second)
    }

    @Test fun retryBackoffIsPersistedCappedAndAutomaticAttemptsAreBounded() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7)
        val id = outbox.records.value.single().id
        val delays = listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L, 900_000L)
        delays.forEachIndexed { index, delay ->
            time += delay - 1
            outbox().flush()
            assertEquals(index + 1, transport.publications.size)
            time += 1
            outbox().flush()
            assertEquals((index + 2).coerceAtMost(WearCommandOutbox.MAX_ATTEMPTS), transport.publications.size)
        }
        assertEquals(WearCommandStatus.FAILED, outbox.records.value.single().status)
        assertNotNull(outbox.records.value.single().error)
        time += 86_400_000
        outbox.flush()
        assertEquals(WearCommandOutbox.MAX_ATTEMPTS, transport.publications.size)
        assertTrue(transport.publications.all { it.id == id && it.command.baseRevision == 7L })
    }

    @Test fun schedulingPrecedesDurabilityAndTerminalRowsCancelOnlyWithoutPendingWork() {
        val events = mutableListOf<String>()
        val outbox = WearCommandOutbox(context, transport, {
            events += "schedule"
            if (events.size == 1) assertFalse(journalFile.exists())
        }, { time }, cancelRetry = { events += "cancel" })
        transport.onPublish = { assertTrue(journalFile.exists()) }
        outbox.sendOrQueue("/pomodoro/control", "START", 1)
        val firstId = outbox.records.value.last().id
        outbox.sendOrQueue("/pomodoro/control", "PAUSE", 1)
        val secondId = outbox.records.value.last().id
        outbox.acknowledge(firstId)
        assertEquals(listOf("schedule", "schedule"), events)
        outbox.acknowledge(SyncAppliedAck(secondId, false, "Rejected").encode())
        assertEquals(listOf("schedule", "schedule", "cancel"), events)
    }

    @Test fun incidentDataItemHasExactWireFieldsAndNonnegativeCreationTime() {
        time = -1
        val outbox = outbox()
        outbox.sendOrQueue("/incident/mark", "DISTRACTION", 7)
        val publication = transport.publications.single()
        val request = GoogleWearCommandTransport.request(
            publication.id, publication.path, publication.payload,
            publication.createdAt, publication.attempt, publication.updatedAt
        )
        val data = DataMap.fromByteArray(request.data!!)
        assertEquals(WearCommandProtocol.COMMAND_PREFIX + publication.id, request.uri.path)
        assertEquals(1, data.getInt("schema_version"))
        assertEquals(publication.id, data.getString("command_id"))
        assertEquals("/incident/mark", data.getString("path"))
        assertArrayEquals(publication.payload, data.getByteArray("payload"))
        assertEquals(0L, data.getLong("created_at"))
        assertEquals(1, data.getInt("attempt"))
        assertEquals(publication.updatedAt, data.getLong("updated_at"))
        assertEquals("DISTRACTION", TimerCommand.decode(data.getByteArray("payload")!!)!!.action)
    }

    @Test fun invalidInputsCannotBePersistedOrSent() {
        val outbox = outbox()
        assertThrows(IllegalArgumentException::class.java) { outbox.sendOrQueue("/unsupported", "START", 1) }
        assertThrows(IllegalArgumentException::class.java) { outbox.sendOrQueue("/incident/mark", "MARK", 1) }
        assertThrows(IllegalArgumentException::class.java) { outbox.sendOrQueue("/pomodoro/control", "", 1) }
        assertThrows(IllegalArgumentException::class.java) {
            outbox.sendOrQueue("/pomodoro/control", "X".repeat(16_384), 1)
        }
        assertTrue(outbox.records.value.isEmpty())
        assertTrue(transport.publications.isEmpty())
    }

    @Test fun suppliedIdIsIdempotentAndDuplicateEnqueueCannotRebase() {
        val outbox = outbox()
        assertFalse(outbox.sendOrQueue("/pomodoro/control", "START", 7, "tap-one"))
        val record = outbox.records.value.single()
        val committed = journalFile.readBytes()
        time += 60_000
        assertFalse(outbox.sendOrQueue("/pomodoro/control", "START", 99, "tap-one"))
        assertEquals(listOf(record), outbox().records.value)
        assertArrayEquals(committed, journalFile.readBytes())
        assertEquals(1, transport.publications.size)
        assertEquals(TimerCommand("tap-one", "START", 7), transport.publications.single().command)
        outbox.acknowledge("tap-one")
        assertTrue(outbox.sendOrQueue("/pomodoro/control", "START", 100, "tap-one"))
        assertEquals(1, transport.publications.size)
    }

    @Test fun suppliedIdWithConflictingCommandOrInvalidCharactersIsRejectedWithoutMutation() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "tap-one")
        val committed = journalFile.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            outbox.sendOrQueue("/pomodoro/control", "PAUSE", 7, "tap-one")
        }
        assertThrows(IllegalArgumentException::class.java) {
            outbox.sendOrQueue("/panic/trigger", "START", 7, "tap-one")
        }
        assertThrows(IllegalArgumentException::class.java) {
            outbox.sendOrQueue("/pomodoro/control", "START", 7, "invalid/id")
        }
        assertArrayEquals(committed, journalFile.readBytes())
        assertEquals(1, transport.publications.size)
    }

    @Test fun concurrentEnqueueOfTheSameSuppliedIdCreatesOnlyOneRecordAndAttempt() {
        val first = outbox()
        val second = outbox()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 8).map { index ->
                executor.submit<Boolean> {
                    (if (index % 2 == 0) first else second)
                        .sendOrQueue("/pomodoro/control", "START", 7, "shared-tap")
                }
            }
            futures.forEach { assertFalse(it.get(10, TimeUnit.SECONDS)) }
            assertEquals("shared-tap", first.records.value.single().id)
            assertEquals(first.records.value, second.records.value)
            assertEquals(1, transport.publications.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun fullPendingQueueRejectsNewWorkButAllowsIdempotentEnqueueWithoutDroppingAnything() {
        seedLegacy(WearCommandOutbox.MAX_PENDING_COMMANDS)
        val outbox = outbox()
        val records = outbox.records.value
        val committed = journalFile.readBytes()
        val error = assertThrows(IOException::class.java) {
            outbox.sendOrQueue("/pomodoro/control", "START", 7, "one-too-many")
        }
        assertTrue(error.message!!.contains("full"))
        assertFalse(outbox.sendOrQueue("/pomodoro/control", "START", 99, "legacy-0"))
        assertEquals(records, outbox().records.value)
        assertArrayEquals(committed, journalFile.readBytes())
        assertTrue(transport.publications.isEmpty())
    }

    @Test fun manualRetryCannotExceedPendingLimitOrLoseItsFailedReason() {
        seedLegacy(WearCommandOutbox.MAX_PENDING_COMMANDS)
        val outbox = outbox()
        outbox.acknowledge(SyncAppliedAck("legacy-0", false, "Rejected").encode())
        outbox.sendOrQueue("/pomodoro/control", "PAUSE", 7, "replacement-slot")
        val records = outbox.records.value
        val committed = journalFile.readBytes()
        assertThrows(IOException::class.java) { outbox.retry("legacy-0", 42) }
        assertEquals(records, outbox().records.value)
        assertEquals("Rejected", outbox.records.value.single { it.id == "legacy-0" }.error)
        assertArrayEquals(committed, journalFile.readBytes())
        assertEquals(1, transport.publications.size)
    }

    @Test fun journalByteLimitRejectsNewWorkWithoutDroppingOldRecords() {
        seedLegacy(31, "\\".repeat(8_000))
        val outbox = outbox()
        val committed = journalFile.readBytes()
        assertTrue(committed.size <= WearCommandOutbox.MAX_JOURNAL_BYTES)
        val error = assertThrows(IOException::class.java) {
            outbox.sendOrQueue("/pomodoro/control", "\\".repeat(16_000), 7, "byte-overflow")
        }
        assertTrue(error.message!!.contains("512 KiB"))
        assertArrayEquals(committed, journalFile.readBytes())
        assertEquals(31, outbox().records.value.size)
        assertTrue(transport.publications.isEmpty())
    }

    @Test fun corruptExistingJournalIsNeverResetOrSent() {
        val corrupt = "{not-a-valid-journal".toByteArray()
        journalFile.writeBytes(corrupt)
        assertThrows(Exception::class.java) { outbox() }
        assertArrayEquals(corrupt, journalFile.readBytes())
        assertTrue(transport.publications.isEmpty())
        assertTrue(transport.removals.isEmpty())
    }

    @Test fun oversizedExistingJournalIsRejectedWithoutTruncationOrReset() {
        val oversized = ByteArray(WearCommandOutbox.MAX_JOURNAL_BYTES + 1) { 'x'.code.toByte() }
        journalFile.writeBytes(oversized)
        assertThrows(IOException::class.java) { outbox() }
        assertArrayEquals(oversized, journalFile.readBytes())
        assertTrue(transport.publications.isEmpty())
    }

    @Test fun oversizedLegacySourceFailsWithoutDeletingPreferencesOrCreatingJournal() {
        val source = setOf("/pomodoro/control\n" + "x".repeat(WearCommandOutbox.MAX_JOURNAL_BYTES))
        val prefs = context.getSharedPreferences("wear_command_outbox", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putStringSet("items", source).commit())
        assertThrows(IOException::class.java) { outbox() }
        assertEquals(source, prefs.getStringSet("items", emptySet()))
        assertFalse(journalFile.exists())
        assertTrue(transport.publications.isEmpty())
    }

    @Test fun actualAtomicFinishThenFailureRestoresPreviousJournalAndNeverSendsNewWork() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "committed")
        val record = outbox.records.value.single()
        val committed = journalFile.readBytes()
        val broken = WearCommandOutbox(context, transport, {}, { time }, persist = ::finishThenFail)
        assertThrows(IOException::class.java) {
            broken.sendOrQueue("/pomodoro/control", "PAUSE", 7, "uncommitted")
        }
        assertArrayEquals(committed, journalFile.readBytes())
        assertEquals(listOf(record), outbox().records.value)
        assertEquals(1, transport.publications.size)
        assertEquals(1, transport.messages.size)
    }

    @Test fun actualInitialAtomicFinishThenFailureRestoresEmptyStoreBeforeAnySend() {
        val broken = WearCommandOutbox(context, transport, {}, { time }, persist = ::finishThenFail)
        assertThrows(IOException::class.java) {
            broken.sendOrQueue("/pomodoro/control", "START", 7, "uncommitted")
        }
        assertFalse(journalFile.exists())
        assertTrue(outbox().records.value.isEmpty())
        assertTrue(transport.publications.isEmpty())
    }

    @Test fun actualAckAtomicFinishThenFailureRestoresPendingAndCannotCleanUpDataItems() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "pending")
        val committed = journalFile.readBytes()
        val broken = WearCommandOutbox(context, transport, {}, { time }, persist = ::finishThenFail)
        assertThrows(IOException::class.java) { broken.acknowledge(SyncAppliedAck("pending", true).encode()) }
        assertArrayEquals(committed, journalFile.readBytes())
        assertEquals(WearCommandStatus.QUEUED, outbox().records.value.single().status)
        assertTrue(transport.removals.isEmpty())
    }

    @Test fun removesDataItemsOnlyAfterAppliedReceiptIsCommittedNeverOnFailedAck() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "tap-one")
        outbox.acknowledge(SyncAppliedAck("tap-one", false, "Rejected").encode())
        assertTrue(transport.removals.isEmpty())
        transport.onRemove = {
            val entry = JSONObject(journalFile.readText()).getJSONArray("entries").getJSONObject(0)
            assertEquals("APPLIED", entry.getString("status"))
        }
        outbox.acknowledge(SyncAppliedAck("tap-one", true).encode())
        assertEquals(listOf("tap-one"), transport.removals)
        outbox.acknowledge(SyncAppliedAck("tap-one", false, "Late failure").encode())
        assertEquals(listOf("tap-one"), transport.removals)
    }

    @Test fun cleanupFailureKeepsAppliedReceiptAndRetriesOnDuplicateAckAndFlush() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "tap-one")
        transport.removeFailure = IOException("Data Layer unavailable")
        outbox.acknowledge(SyncAppliedAck("tap-one", true).encode())
        assertEquals(WearCommandStatus.APPLIED, outbox().records.value.single().status)
        transport.removeFailure = null
        outbox.acknowledge(SyncAppliedAck("tap-one", true).encode())
        outbox.flush()
        assertEquals(listOf("tap-one", "tap-one", "tap-one"), transport.removals)
        assertEquals(1, transport.publications.size)
    }

    @Test fun cleanupUrisUseWildcardHostsAndExactCommandAndAckPathsForOnlyOneId() {
        val uris = GoogleWearCommandTransport.removalUris("tap-one")
        assertEquals(2, uris.size)
        assertTrue(uris.all { it.scheme == "wear" && it.host == "*" })
        assertEquals(setOf(WearCommandProtocol.COMMAND_PREFIX + "tap-one", WearCommandProtocol.ACK_PREFIX + "tap-one"),
            uris.map { it.path }.toSet())
        assertThrows(IllegalArgumentException::class.java) { GoogleWearCommandTransport.removalUris("bad/id") }
    }

    @Test fun recordsAreStablySortedByCreationTimeAcrossAcksAndRecreation() {
        val outbox = outbox()
        time = 100
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "first")
        time = 50
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "earlier")
        time = 100
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "same-time-one")
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "same-time-two")
        val order = listOf("earlier", "first", "same-time-one", "same-time-two")
        assertEquals(order, outbox.records.value.map { it.id })
        outbox.acknowledge("first")
        outbox.acknowledge(SyncAppliedAck("same-time-one", false, "Rejected").encode())
        assertEquals(order, outbox.records.value.map { it.id })
        assertEquals(order, outbox().records.value.map { it.id })
    }

    @Test fun receiptRetentionTracksAckRecencyRatherThanCreationTime() {
        val outbox = outbox()
        val ids = (0..WearCommandOutbox.MAX_APPLIED_RECEIPTS).map { index ->
            time += 1
            "tap-$index".also { outbox.sendOrQueue("/pomodoro/control", "START", 7, it) }
        }
        ids.reversed().forEach { outbox.acknowledge(it) }
        assertEquals(ids.dropLast(1), outbox().records.value.map { it.id })
        assertTrue(outbox.records.value.all { it.status == WearCommandStatus.APPLIED })
    }

    @Test fun legacyIncidentPayloadAddsCaptureTimeWithoutChangingIdentityRevisionOrSource() {
        listOf("wear", "phone").forEach { source ->
            val command = TimerCommand("incident-id", "DISTRACTION", 42, source)
            val payload = command.encode()
            val legacy = GoogleWearCommandTransport.legacyPayload("/incident/mark", payload, 123_456L)
            assertEquals(command.copy(action = "DISTRACTION|123456"), TimerCommand.decode(legacy))
            assertEquals(command, TimerCommand.decode(payload))
            assertArrayEquals(command.encode(), payload)
        }
    }

    @Test fun legacyPayloadLeavesUnknownCaptureTimesAndNonIncidentRoutesUnchanged() {
        val incident = TimerCommand("incident-id", "DISTRACTION", 7).encode()
        listOf(0L, -1L).forEach { capturedAt ->
            assertArrayEquals(incident, GoogleWearCommandTransport.legacyPayload("/incident/mark", incident, capturedAt))
        }
        val control = TimerCommand("control-id", "START", 7).encode()
        (WearCommandProtocol.paths - "/incident/mark").forEach { path ->
            assertArrayEquals(control, GoogleWearCommandTransport.legacyPayload(path, control, 123_456L))
        }
    }

    @Test fun incidentRetriesKeepOriginalCaptureTimeAndPlainDurableAction() {
        val outbox = outbox()
        val capturedAt = time
        outbox.sendOrQueue("/incident/mark", "DISTRACTION", 7, "incident-id")
        time += 60_000
        outbox.flush()
        outbox.acknowledge(SyncAppliedAck("incident-id", false, "Try again").encode())
        time += 60_000
        outbox.retry("incident-id", 42)
        assertEquals(3, transport.publications.size)
        assertTrue(transport.publications.all {
            it.id == "incident-id" && it.createdAt == capturedAt && it.command.action == "DISTRACTION"
        })
        val messages = transport.messages.map { requireNotNull(TimerCommand.decode(it.second)) }
        assertEquals(3, messages.size)
        assertTrue(messages.all {
            it.id == "incident-id" && it.sourceDevice == "wear" && it.action == "DISTRACTION|$capturedAt"
        })
        assertEquals(listOf(7L, 7L, 42L), messages.map { it.baseRevision })
        assertEquals(capturedAt, outbox.records.value.single().createdAt)
        assertEquals("DISTRACTION", outbox.records.value.single().action)
    }

    @Test fun stoppedFlushDoesNotReserveAttemptsOrTouchTransport() {
        seedLegacy(2)
        val outbox = outbox()
        val committed = journalFile.readBytes()
        outbox.flush { false }
        assertArrayEquals(committed, journalFile.readBytes())
        assertTrue(transport.publications.isEmpty())
        assertTrue(transport.messages.isEmpty())
        assertTrue(transport.removals.isEmpty())
    }

    @Test fun stoppingDuringFirstBlockedPublicationSkipsLegacyFallbackAndSubsequentCommands() {
        seedLegacy(2)
        val outbox = outbox()
        val records = outbox.records.value
        val active = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.onPublish = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Unit> { outbox.flush { active.get() } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            active.set(false)
            release.countDown()
            future.get(10, TimeUnit.SECONDS)
            assertEquals(1, transport.publications.size)
            assertTrue(transport.messages.isEmpty())
            assertEquals(records, outbox().records.value)
            assertTrue(outbox.records.value.all { it.status == WearCommandStatus.QUEUED })
            transport.onPublish = null
            active.set(true)
            time += 30_000
            outbox.flush { active.get() }
            assertEquals(3, transport.publications.size)
            assertEquals(records.map { it.id }.toSet(), transport.publications.map { it.id }.toSet())
            assertEquals(2, transport.messages.size)
        } finally {
            active.set(false)
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun stoppingAfterAttemptCommitPreventsFirstPublishAndReleasesReservation() {
        seedLegacy(2)
        val records = outbox().records.value
        val active = AtomicBoolean(true)
        val outbox = WearCommandOutbox(context, transport, {}, { time }, persist = { file, bytes ->
            finishWrite(file, bytes)
            active.set(false)
        })
        outbox.flush { active.get() }
        assertTrue(transport.publications.isEmpty())
        assertTrue(transport.messages.isEmpty())
        assertEquals(records, outbox.records.value)
        time += 30_000
        outbox().flush()
        assertEquals(2, transport.publications.size)
        assertEquals(setOf(1, 2), transport.publications.map { it.attempt }.toSet())
    }

    @Test fun stoppingCleanupDoesNotStartAnotherRemoval() {
        val outbox = outbox()
        outbox.sendOrQueue("/pomodoro/control", "START", 7, "first")
        outbox.sendOrQueue("/pomodoro/control", "PAUSE", 7, "second")
        outbox.acknowledge("first")
        outbox.acknowledge("second")
        transport.removals.clear()
        val active = AtomicBoolean(true)
        transport.onRemove = { active.set(false) }
        outbox.flush { active.get() }
        assertEquals(1, transport.removals.size)
        assertEquals(2, transport.publications.size)
    }

    private fun seedLegacy(count: Int, action: String = "START") {
        val source = (0 until count).map { index ->
            "/pomodoro/control\n" + TimerCommand("legacy-$index", action, 7).encode().toString(Charsets.UTF_8)
        }.toSet()
        assertTrue(context.getSharedPreferences("wear_command_outbox", Context.MODE_PRIVATE)
            .edit().putStringSet("items", source).commit())
    }

    private fun finishThenFail(file: AtomicFile, bytes: ByteArray) {
        finishWrite(file, bytes)
        assertArrayEquals(bytes, file.readFully())
        throw IOException("Failure after actual AtomicFile.finishWrite")
    }

    private fun finishWrite(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        stream.write(bytes)
        stream.fd.sync()
        file.finishWrite(stream)
    }

    private fun outbox() = WearCommandOutbox(context, transport, {}, { time })

    private data class Publication(
        val id: String,
        val path: String,
        val payload: ByteArray,
        val createdAt: Long,
        val attempt: Int,
        val updatedAt: Long
    ) {
        val command get() = requireNotNull(TimerCommand.decode(payload))
    }

    private class RecordingTransport : WearCommandTransport {
        val publications: MutableList<Publication> = Collections.synchronizedList(mutableListOf())
        val messages: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(mutableListOf())
        val removals: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var online = false
        var publishFailure: Exception? = null
        var messageFailure: Exception? = null
        var onPublish: (() -> Unit)? = null
        var removeFailure: Exception? = null
        var onRemove: (() -> Unit)? = null

        override fun publish(id: String, path: String, payload: ByteArray, createdAt: Long, attempt: Int, updatedAt: Long) {
            publications += Publication(id, path, payload.copyOf(), createdAt, attempt, updatedAt)
            onPublish?.invoke()
            publishFailure?.let { throw it }
        }

        override fun sendLegacy(path: String, payload: ByteArray, createdAt: Long): Boolean {
            messages += path to GoogleWearCommandTransport.legacyPayload(path, payload, createdAt).copyOf()
            messageFailure?.let { throw it }
            return online
        }

        override fun remove(id: String) {
            removals += id
            onRemove?.invoke()
            removeFailure?.let { throw it }
        }
    }
}
