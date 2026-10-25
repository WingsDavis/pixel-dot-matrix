package com.example.wear

import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.io.File

class LogoComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null
        val visible = getSharedPreferences("watchface_config_prefs", MODE_PRIVATE)
            .getBoolean("show_panic_logo", true)
        return if (visible) logoData() else NoDataComplicationData()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SMALL_IMAGE) logoData() else null

    private fun logoData(): ComplicationData {
        val customFile = File(filesDir, WearSyncService.LOGO_FILENAME)
        val bitmap = if (customFile.isFile) runCatching { BitmapFactory.decodeFile(customFile.absolutePath) }.getOrNull() else null
        val icon = bitmap?.let(Icon::createWithBitmap) ?: Icon.createWithResource(this, R.drawable.ws_logo)
        val image = SmallImage.Builder(icon, SmallImageType.PHOTO).build()
        val description = PlainComplicationText.Builder("Watch face logo").build()
        return SmallImageComplicationData.Builder(image, description)
            .setTapAction(ComplicationActions.openPanic(this)).build()
    }
}
