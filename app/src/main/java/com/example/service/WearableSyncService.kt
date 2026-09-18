package com.example.service

import android.content.Intent
import android.util.Log
import com.example.core.sync.WearableSyncManager
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.SupervisorJob

class WearableSyncService : WearableListenerService() {

    private val job = SupervisorJob()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val item = event.dataItem
                if (item.uri.path == WearableSyncManager.PATH_POMODORO_STATE) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val stateStr = dataMap.getString(WearableSyncManager.KEY_STATE) ?: continue

                    Log.d(TAG, "Sync data changed received in background Service: $stateStr")
                } else if (item.uri.path == WearableSyncManager.PATH_WATCHFACE_STATUS_V2) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    Log.d(
                        TAG,
                        "Watch face config acknowledgement: revision=${dataMap.getString(WearableSyncManager.KEY_REVISION)}, success=${dataMap.getBoolean(WearableSyncManager.KEY_SUCCESS, false)}"
                    )
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        val path = messageEvent.path
        val payload = String(messageEvent.data)
        Log.d(TAG, "Message received via Service: path=$path, payload=$payload")

        // Broadcast the message locally for MainActivity and PomodoroViewModel to handle in background
        val controlIntent = Intent("com.example.wear.CONTROL_MESSAGE").apply {
            putExtra("path", path)
            putExtra("payload", payload)
        }
        sendBroadcast(controlIntent)

        when (path) {
            WearableSyncManager.PATH_PANIC_TRIGGER -> {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("TRIGGER_PANIC", true)
                }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        private const val TAG = "WearableSyncService"
    }
}
