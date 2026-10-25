package com.example.wear

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class WatchFaceAmbientTest {
    private val watchFace by lazy {
        parseXml("../watchface/src/main/res/raw/watchface.xml", "watchface/src/main/res/raw/watchface.xml")
    }
    private val scene get() = watchFace.children().single { it.tagName == "Scene" }
    private val ambientClock get() = scene.children().single {
        it.tagName == "Group" && it.getAttribute("name") == "AmbientClock"
    }
    private val ambientText get() = ambientClock.children().single { it.tagName == "PartText" }

    @Test fun everyOtherRootVisualIsHiddenInAmbient() {
        assertEquals("WatchFace", watchFace.tagName)
        assertEquals("450", watchFace.getAttribute("width"))
        assertEquals("450", watchFace.getAttribute("height"))
        assertEquals("CIRCLE", watchFace.getAttribute("clipShape"))
        scene.assertAttributes("backgroundColor" to "#ff000000")
        assertEquals(1, scene.descendants().count { it.getAttribute("name") == "AmbientClock" })
        assertFalse(scene.descendants().any { it.getAttribute("name") == "InteractiveContent" })

        val interactive = scene.children().filterNot { it === ambientClock }
        assertTrue("Interactive visuals must remain present", interactive.isNotEmpty())
        for (visual in interactive) {
            val label = "${visual.tagName} ${visual.getAttribute("name")} ${visual.getAttribute("slotId")}".trim()
            assertTrue(label, visual.tagName in setOf("Group", "ComplicationSlot"))
            val variants = visual.children().filter {
                it.tagName == "Variant" && it.getAttribute("mode") == "AMBIENT" &&
                    it.getAttribute("target") == "alpha"
            }
            assertEquals("$label must have its own ambient alpha variant", 1, variants.size)
            variants.single().assertAttributes("mode" to "AMBIENT", "target" to "alpha", "value" to "0")
        }
        assertTrue("Complication slots must remain direct Scene children",
            scene.descendants().filter { it.tagName == "ComplicationSlot" }.all { it.parentNode === scene })
    }

    @Test fun ambientClockContainsOnlyOneDimMinutePrecisionClock() {
        ambientClock.assertAttributes(
            "name" to "AmbientClock", "x" to "0", "y" to "0",
            "width" to "450", "height" to "450", "alpha" to "0"
        )
        // This closed subtree excludes timers, seconds, images, complications and dynamic transforms.
        assertEquals(listOf("Variant", "PartText", "Text", "Font", "Template", "Parameter", "Parameter"),
            ambientClock.descendants().map { it.tagName })
        ambientClock.children().single { it.tagName == "Variant" }
            .assertAttributes("mode" to "AMBIENT", "target" to "alpha", "value" to "255")
        ambientText.assertAttributes("x" to "100", "y" to "180", "width" to "250", "height" to "90")
        val text = ambientText.children().single()
        assertEquals("Text", text.tagName)
        text.assertAttributes("align" to "CENTER")
        val font = text.children().single()
        assertEquals("Font", font.tagName)
        font.assertAttributes("family" to "ndot", "size" to "78", "color" to "#ff777777")
        val template = font.children().single()
        assertEquals("Template", template.tagName)
        template.assertAttributes()
        assertEquals("%s:%s", template.textContent.trim())
        val parameters = template.children()
        assertEquals(listOf("[HOUR_1_12_Z]", "[MINUTE_Z]"), parameters.map { it.getAttribute("expression") })
        parameters.forEach {
            it.assertAttributes("expression" to it.getAttribute("expression"))
        }
    }

    @Test fun ambientRectangleFitsRoundScreensWithSystemShiftAndStaysBelowLitAreaBudget() {
        val designWidth = watchFace.getAttribute("width").toDouble()
        val designHeight = watchFace.getAttribute("height").toDouble()
        for (dimension in listOf(192, 228, 450)) {
            val radius = dimension / 2.0
            val x = ambientText.getAttribute("x").toDouble() * dimension / designWidth
            val y = ambientText.getAttribute("y").toDouble() * dimension / designHeight
            val width = ambientText.getAttribute("width").toDouble() * dimension / designWidth
            val height = ambientText.getAttribute("height").toDouble() * dimension / designHeight
            // Treat the entire text rectangle as lit, not just the glyphs; this is not a rendered-pixel measurement.
            val litAreaPercent = 100 * width * height / (PI * radius * radius)
            assertTrue("${dimension}px: ambient rectangle uses $litAreaPercent% of the circle", litAreaPercent <= 15.0)
            assertEquals("${dimension}px conservative area bound", 14.15, litAreaPercent, 0.005)

            // Shifts are physical screen pixels, applied after scaling the 450px design.
            for (shiftX in listOf(-10, 0, 10)) {
                for (shiftY in listOf(-10, 0, 10)) {
                    for (cornerX in listOf(x, x + width)) {
                        for (cornerY in listOf(y, y + height)) {
                            val dx = cornerX + shiftX - radius
                            val dy = cornerY + shiftY - radius
                            assertTrue("${dimension}px: corner ($cornerX, $cornerY), shift ($shiftX, $shiftY)",
                                dx * dx + dy * dy <= radius * radius)
                        }
                    }
                }
            }
        }
    }

    @Test fun complicationProviderPeriodsAreDisabledOrAtLeastFiveMinutes() {
        val manifest = parseXml("src/main/AndroidManifest.xml", "wear/src/main/AndroidManifest.xml")
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val providers = manifest.descendants().filter {
            it.tagName == "service" && it.getAttributeNS(androidNamespace, "permission") ==
                "com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER"
        }
        assertTrue("Expected complication providers in the Wear manifest", providers.isNotEmpty())
        for (provider in providers) {
            val name = provider.getAttributeNS(androidNamespace, "name")
            val periods = provider.children().filter {
                it.tagName == "meta-data" && it.getAttributeNS(androidNamespace, "name") ==
                    "android.support.wearable.complications.UPDATE_PERIOD_SECONDS"
            }
            assertEquals("$name must declare one update period", 1, periods.size)
            val seconds = periods.single().getAttributeNS(androidNamespace, "value").toLong()
            assertTrue("$name update period must be 0 or >= 300 seconds, was $seconds",
                seconds == 0L || seconds >= 300L)
        }
    }

    private fun parseXml(wearRelativePath: String, rootRelativePath: String): Element {
        val file = File(wearRelativePath).takeIf { it.isFile } ?: File(rootRelativePath)
        assertTrue("Missing XML source: ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.descendants(): List<Element> = getElementsByTagName("*").let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.assertAttributes(vararg expected: Pair<String, String>) {
        val actual = (0 until attributes.length).associate { index ->
            val attribute = attributes.item(index)
            attribute.nodeName to attribute.nodeValue
        }
        assertEquals("$tagName attributes", expected.toMap(), actual)
    }
}
