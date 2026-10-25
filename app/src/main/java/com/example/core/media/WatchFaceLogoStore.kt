package com.example.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.os.Build
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONObject

internal fun validateLogoImageBounds(width: Int, height: Int) {
    if (width <= 0 || height <= 0) throw IOException("Logo image is corrupt or its format is unsupported")
    if (maxOf(width, height) > 100_000 || width.toLong() * height.toLong() > 256_000_000L) {
        throw IOException("Logo image header exceeds 100000 pixels per edge or 256 million pixels")
    }
}

class WatchFaceLogoStore(context: Context) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "watch_face_logo")
    private val manifest = AtomicFile(File(directory, "manifest.json"))
    private var cachedSourceName: String? = null
    private var cachedDocument: WatchFaceLogoDocument? = null

    @Synchronized
    fun load(): WatchFaceLogoDocument? {
        if (directory.exists() && !directory.isDirectory) throw IOException("Watch-face logo storage is not a directory")
        val bytes = try {
            readManifest()
        } catch (error: FileNotFoundException) {
            if (manifestFiles().any(File::exists) || ownedSources().isNotEmpty()) {
                throw IOException("Saved watch-face logo metadata is missing or unreadable", error)
            }
            cachedSourceName = null
            cachedDocument = null
            return null
        }
        val (sourceName, transform) = try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.get("schema") == 1) { "Unsupported logo metadata schema" }
            val name = json.get("source") as? String ?: error("Logo source filename is missing")
            require(SOURCE_NAME.matches(name)) { "Unsafe logo source filename" }
            val pose = json.getJSONObject("pose")
            name to LogoTransform(
                fit = LogoFit.valueOf(pose.get("fit") as? String ?: error("Logo fit is missing")),
                zoom = poseNumber(pose, "zoom", 1.0, 4.0),
                panX = poseNumber(pose, "panX", -1.0, 1.0),
                panY = poseNumber(pose, "panY", -1.0, 1.0),
                transparent = pose.get("transparent") as? Boolean ?: error("Logo transparency is missing")
            )
        } catch (error: Exception) {
            throw IOException("Saved watch-face logo metadata is corrupt: ${error.message}", error)
        }
        val sourceFile = File(directory, sourceName)
        if (!sourceFile.isFile || sourceFile.length() !in 1L..MAX_INPUT_BYTES.toLong()) {
            throw IOException("Saved watch-face logo source is missing, empty, or larger than 32 MiB")
        }
        val cached = cachedDocument.takeIf { cachedSourceName == sourceName }
        if (cached != null && cached.transform == transform) return cached
        val source = cached?.source ?: decode(sourceFile, allowSampling = false)
        return WatchFaceLogoDocument(source, transform, WatchFaceLogoRenderer.render(source, transform)).also {
            cachedSourceName = sourceName
            cachedDocument = it
        }
    }

    @Synchronized
    fun importImage(uri: Uri): WatchFaceLogoDocument {
        val temporary = File.createTempFile("watch-face-logo-", ".input", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Selected image could not be opened")
            input.use { source -> temporary.outputStream().use { copyBounded(source, it, MAX_INPUT_BYTES, "Selected image") } }
            val source = decode(temporary, allowSampling = true)
            val transform = LogoTransform()
            val document = WatchFaceLogoDocument(source, transform, WatchFaceLogoRenderer.render(source, transform))
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create watch-face logo storage")
            val sourceName = "source-${UUID.randomUUID()}.png"
            val sourceFile = File(directory, sourceName)
            try {
                FileOutputStream(sourceFile).use { output ->
                    if (!source.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Could not encode logo source PNG")
                    output.fd.sync()
                }
            } catch (error: Exception) {
                sourceFile.delete()
                throw error
            }
            // Keep the immutable source if a commit's outcome is uncertain; the next commit or clear removes orphans.
            writeManifest(sourceName, transform)
            cachedSourceName = sourceName
            cachedDocument = document
            cleanupSources(sourceName)
            return document
        } catch (error: Exception) {
            throw IOException("Could not import watch-face logo: ${error.message}", error)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun update(transform: LogoTransform): WatchFaceLogoDocument {
        val current = load() ?: throw IllegalStateException("Import a watch-face logo before changing its framing")
        val document = WatchFaceLogoDocument(current.source, transform, WatchFaceLogoRenderer.render(current.source, transform))
        val sourceName = checkNotNull(cachedSourceName)
        writeManifest(sourceName, transform)
        cachedDocument = document
        cleanupSources(sourceName)
        return document
    }

    @Synchronized
    fun clear() {
        manifest.delete()
        for (file in manifestFiles()) {
            if (file.exists() && !file.delete()) throw IOException("Could not remove watch-face logo metadata")
        }
        cachedSourceName = null
        cachedDocument = null
        cleanupSources(null)
        if (ownedSources().isNotEmpty()) throw IOException("Could not remove watch-face logo source files")
    }

    private fun decode(file: File, allowSampling: Boolean): Bitmap {
        try {
            if (!file.isFile || file.length() !in 1L..MAX_INPUT_BYTES.toLong()) throw IOException("Logo image is missing, empty, or larger than 32 MiB")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            validateLogoImageBounds(bounds.outWidth, bounds.outHeight)
            if (!allowSampling && maxOf(bounds.outWidth, bounds.outHeight) > MAX_SOURCE_EDGE) {
                throw IOException("Saved logo source exceeds the 2048-pixel limit")
            }
            var sample = 1
            while ((maxOf(bounds.outWidth, bounds.outHeight).toLong() + sample - 1) / sample > MAX_SOURCE_EDGE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = if (Build.VERSION.SDK_INT >= 28) {
                val encoded = ByteArray(file.length().toInt())
                file.inputStream().use { input ->
                    DataInputStream(input).readFully(encoded)
                    if (input.read() != -1) throw IOException("Logo source changed while reading")
                }
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sample)
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.setOnPartialImageListener { false }
                }
            } else BitmapFactory.decodeFile(file.absolutePath, options)
                ?: throw IOException("Logo image is corrupt or its format is unsupported")
            try {
                checkSourceSize(decoded)
                val orientation = if (Build.VERSION.SDK_INT >= 28) ExifInterface.ORIENTATION_NORMAL else try {
                    ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (_: IOException) {
                    // Older platform EXIF readers can reject otherwise valid PNG/WebP images.
                    ExifInterface.ORIENTATION_NORMAL
                }
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> Unit
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                    else -> throw IOException("Logo image has an invalid EXIF orientation")
                }
                val normalized = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (normalized !== decoded) decoded.recycle()
                return normalized.also(::checkSourceSize)
            } catch (error: Exception) {
                if (!decoded.isRecycled) decoded.recycle()
                throw error
            }
        } catch (error: Exception) {
            throw IOException("Could not read watch-face logo source: ${error.message}", error)
        }
    }

    private fun checkSourceSize(bitmap: Bitmap) {
        if (maxOf(bitmap.width, bitmap.height) > MAX_SOURCE_EDGE || bitmap.allocationByteCount > MAX_SOURCE_BYTES) {
            throw IOException("Decoded logo source exceeds 2048 pixels or 16 MiB")
        }
    }

    private fun readManifest(): ByteArray = manifest.openRead().use { input ->
        ByteArrayOutputStream().use { output ->
            copyBounded(input, output, MAX_MANIFEST_BYTES, "Saved logo metadata")
            output.toByteArray()
        }
    }

    private fun writeManifest(sourceName: String, transform: LogoTransform) {
        val bytes = JSONObject().put("schema", 1).put("source", sourceName).put("pose", JSONObject()
            .put("fit", transform.fit.name).put("zoom", transform.zoom.toDouble())
            .put("panX", transform.panX.toDouble()).put("panY", transform.panY.toDouble())
            .put("transparent", transform.transparent)).toString().toByteArray(Charsets.UTF_8)
        var output: FileOutputStream? = null
        try {
            output = manifest.startWrite()
            output.write(bytes)
            output.fd.sync()
            manifest.finishWrite(output)
            output = null
            // AtomicFile logs some commit failures instead of throwing, so verify before retiring the old source.
            if (!readManifest().contentEquals(bytes)) throw IOException("Logo metadata commit could not be confirmed")
        } catch (error: Exception) {
            output?.let(manifest::failWrite)
            throw IOException("Could not save watch-face logo metadata: ${error.message}", error)
        }
    }

    private fun poseNumber(pose: JSONObject, name: String, minimum: Double, maximum: Double): Float {
        val value = (pose.get(name) as? Number)?.toDouble() ?: error("Logo $name must be a number")
        require(value.isFinite() && value in minimum..maximum) { "Logo $name must be finite and between $minimum and $maximum" }
        return value.toFloat()
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Int, label: String) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return
            total += count
            if (total > limit) throw IOException("$label exceeds the ${limit / 1024}-KiB limit")
            output.write(buffer, 0, count)
        }
    }

    private fun manifestFiles() = listOf(manifest.baseFile, File(directory, "manifest.json.bak"), File(directory, "manifest.json.new"))

    private fun ownedSources(): List<File> = directory.listFiles()?.filter { it.isFile && SOURCE_NAME.matches(it.name) }.orEmpty()

    private fun cleanupSources(keep: String?) {
        ownedSources().filter { it.name != keep }.forEach { it.delete() }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 32 * 1024 * 1024
        const val MAX_SOURCE_EDGE = 2048
        const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 4096
        val SOURCE_NAME = Regex("source-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png")
    }
}
