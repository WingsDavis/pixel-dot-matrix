package com.example.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import java.util.UUID

class WearCommandOutbox(
    context: Context,
    private val nodeClient: NodeClient,
    private val messageClient: MessageClient
) {
    private val prefs = context.getSharedPreferences("wear_command_outbox", Context.MODE_PRIVATE)

    @Synchronized
    fun sendOrQueue(path: String, payload: String, baseRevision: Long): Boolean {
        val commandId = UUID.randomUUID().toString()
        val encoded = listOf("v2", commandId, baseRevision, "wear", payload).joinToString("|")
        queue(path, encoded)
        val nodes = Tasks.await(nodeClient.connectedNodes)
        if (nodes.isEmpty()) return false
        return runCatching {
            nodes.forEach { Tasks.await(messageClient.sendMessage(it.id, path, encoded.toByteArray())) }
        }.isSuccess
    }

    @Synchronized
    fun flush() {
        val queued = prefs.getStringSet(KEY_ITEMS, emptySet()).orEmpty().toList()
        if (queued.isEmpty()) return
        val nodes = Tasks.await(nodeClient.connectedNodes)
        if (nodes.isEmpty()) return
        queued.forEach { item ->
            val separator = item.indexOf('\n')
            if (separator <= 0) return@forEach
            val path = item.substring(0, separator)
            val payload = item.substring(separator + 1).toByteArray()
            runCatching { nodes.forEach { Tasks.await(messageClient.sendMessage(it.id, path, payload)) } }
        }
    }

    @Synchronized
    fun acknowledge(id: String) {
        val remaining = prefs.getStringSet(KEY_ITEMS, emptySet()).orEmpty().filterNot {
            it.substringAfter('\n').split('|').getOrNull(1) == id
        }.toSet()
        prefs.edit().putStringSet(KEY_ITEMS, remaining).apply()
    }

    @Synchronized
    private fun queue(path: String, encoded: String) {
        val items = prefs.getStringSet(KEY_ITEMS, emptySet()).orEmpty().toMutableSet()
        val id = encoded.split('|').getOrNull(1)
        if (id != null && items.any { it.substringAfter('\n').split('|').getOrNull(1) == id }) return
        items += "$path\n$encoded"
        prefs.edit().putStringSet(KEY_ITEMS, items).apply()
    }

    companion object { private const val KEY_ITEMS = "items" }
}
