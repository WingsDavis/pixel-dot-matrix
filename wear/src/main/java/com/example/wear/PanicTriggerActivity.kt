package com.example.wear

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class PanicTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Toast.makeText(this, "Triggering Panic Mode...", Toast.LENGTH_SHORT).show()

        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = Tasks.await(nodeClient.connectedNodes)
                nodes.forEach { node ->
                    Tasks.await(
                        messageClient.sendMessage(
                            node.id,
                            "/panic/trigger",
                            "TRIGGER_PANIC".toByteArray(StandardCharsets.UTF_8)
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PanicTriggerActivity, "Failed to connect to phone", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }
}
