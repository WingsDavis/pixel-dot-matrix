package com.example.wear

import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class LogoComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null
        val visible = getSharedPreferences("watchface_config_prefs", MODE_PRIVATE)
            .getBoolean("show_panic_logo", true)
        if (!visible) {
            val icon = Icon.createWithBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            val image = SmallImage.Builder(icon, SmallImageType.PHOTO).build()
            return SmallImageComplicationData.Builder(image, PlainComplicationText.Builder("").build()).build()
        }
        return logoData()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SMALL_IMAGE) logoData() else null

    private fun logoData(): ComplicationData {
        val bitmap = WearLogoStore(this).bitmapOrNull()
        val icon = bitmap?.let(Icon::createWithBitmap) ?: Icon.createWithResource(this, R.drawable.ws_logo)
        val image = SmallImage.Builder(icon, SmallImageType.PHOTO).build()
        val description = PlainComplicationText.Builder("Watch face logo").build()
        return SmallImageComplicationData.Builder(image, description)
            .setTapAction(ComplicationActions.openPanic(this)).build()
    }
}
