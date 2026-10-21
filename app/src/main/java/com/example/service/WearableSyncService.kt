package com.example.service

import com.example.core.timer.TimerRuntime
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearableSyncService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val item = event.dataItem
                val path = item.uri.path ?: continue
                val data = DataMap.fromByteArray(DataMapItem.fromDataItem(item).dataMap.toByteArray())
                TimerRuntime.get(applicationContext).receiveData(path, data)
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        TimerRuntime.get(applicationContext).receiveMessage(messageEvent.path, messageEvent.sourceNodeId, messageEvent.data.copyOf())
    }
}
