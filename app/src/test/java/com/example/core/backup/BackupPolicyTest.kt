package com.example.core.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupPolicyTest {
    @Test
    fun excludesAllAppDataFromAutomaticBackupAndDeviceTransfer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val expected = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        listOf(R.xml.backup_rules, R.xml.data_extraction_rules).forEach { resource ->
            val groups = mutableMapOf<String, MutableSet<String>>()
            context.resources.getXml(resource).use { parser ->
                var section = ""
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        if (parser.name in setOf("full-backup-content", "cloud-backup", "device-transfer")) section = parser.name
                        if (parser.name == "exclude") {
                            assertEquals(".", parser.getAttributeValue(null, "path"))
                            groups.getOrPut(section) { mutableSetOf() }.add(parser.getAttributeValue(null, "domain"))
                        }
                    }
                    parser.next()
                }
            }
            if (resource == R.xml.backup_rules) assertEquals(setOf("full-backup-content"), groups.keys)
            else assertEquals(setOf("cloud-backup", "device-transfer"), groups.keys)
            groups.values.forEach { assertEquals(expected, it) }
        }
    }
}
