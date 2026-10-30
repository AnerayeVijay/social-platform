package com.example.social.infrastructure

import com.example.social.application.MemberImageKind
import com.example.social.application.MemberImageProcessor
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.event.IIOReadWarningListener
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * Decodes an untrusted JPEG/PNG upload and re-encodes it as a fresh, metadata-free JPEG.
 * The format is decided by magic bytes only, dimensions are checked from the header before
 * any pixel buffer is allocated, and any decoder warning (e.g. a truncated JPEG) is a rejection.
 */
class RasterMemberImages(
    private val maxBytes: Int = 5 * 1024 * 1024,
    private val maxSide: Int = 4096,
    private val maxPixels: Long = 16_000_000,
) : MemberImageProcessor {
    override fun process(bytes: ByteArray, kind: MemberImageKind): ByteArray {
        val source = try {
            decode(bytes)
        } catch (error: IOException) {
            throw invalid()
        } catch (error: RuntimeException) { // decoders signal malformed input with assorted runtime exceptions; Errors propagate
            throw invalid()
        }
        val upright = orient(source, exifOrientation(bytes))
        val normalized = when (kind) {
            MemberImageKind.AVATAR -> avatar(upright)
            MemberImageKind.BACKGROUND, MemberImageKind.HEADER -> background(upright)
        }
        return encodeJpeg(normalized)
    }

    private fun decode(bytes: ByteArray): BufferedImage {
        // Backstop: CustomizationService already enforces this before calling us, so it is unreachable over HTTP.
        if (bytes.size > maxBytes) throw invalid()
        val format = formatOf(bytes) ?: throw invalid()
        val reader = ImageIO.getImageReadersByFormatName(format).next()
        try {
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                reader.setInput(input, true, true) // seek-forward-only, ignore metadata: no text/ICC/EXIF parsing
                val warnings = mutableListOf<String>()
                reader.addIIOReadWarningListener(IIOReadWarningListener { _, warning -> warnings += warning })
                checkDimensions(reader)
                val image = reader.read(0)
                if (warnings.isNotEmpty()) throw invalid() // the JPEG decoder pads truncated data with grey instead of failing
                return image
            }
        } finally {
            reader.dispose()
        }
    }

    private fun checkDimensions(reader: ImageReader) {
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        if (width !in 1..maxSide || height !in 1..maxSide || width.toLong() * height > maxPixels) throw invalid()
    }

    /**
     * Reads only the EXIF Orientation tag from the first JPEG APP1 "Exif" segment, bounds-checking every
     * offset against that segment. Anything malformed, missing or out of range means "no rotation" (1).
     */
    private fun exifOrientation(bytes: ByteArray): Int {
        if (!bytes.startsWith(JPEG_SIGNATURE)) return 1
        var pos = 2
        while (pos + 4 <= bytes.size && bytes[pos] == 0xFF.toByte()) {
            val marker = bytes[pos + 1].toInt() and 0xff
            if (marker == SOS || marker == EOI) return 1
            val length = bytes.u16(pos + 2, bigEndian = true)
            val end = pos + 2 + length
            if (length < 2 || end > bytes.size) return 1
            if (marker == APP1 && bytes.regionMatches(pos + 4, EXIF_HEADER)) return tiffOrientation(bytes, pos + 4 + EXIF_HEADER.size, end)
            pos = end
        }
        return 1
    }

    private fun tiffOrientation(bytes: ByteArray, tiff: Int, end: Int): Int {
        if (tiff + 8 > end) return 1
        val bigEndian = when {
            bytes.regionMatches(tiff, "MM".toByteArray()) -> true
            bytes.regionMatches(tiff, "II".toByteArray()) -> false
            else -> return 1
        }
        if (bytes.u16(tiff + 2, bigEndian) != 42) return 1
        val ifd = tiff + bytes.u32(tiff + 4, bigEndian)
        if (ifd < tiff + 8 || ifd + 2 > end) return 1 // Long arithmetic: a huge offset cannot wrap
        val count = bytes.u16(ifd.toInt(), bigEndian)
        for (i in 0 until count) {
            val entry = ifd.toInt() + 2 + 12 * i
            if (entry + 12 > end) return 1
            if (bytes.u16(entry, bigEndian) == ORIENTATION_TAG) {
                val valid = bytes.u16(entry + 2, bigEndian) == SHORT_TYPE && bytes.u32(entry + 4, bigEndian) == 1L
                val orientation = bytes.u16(entry + 8, bigEndian)
                return if (valid && orientation in 1..8) orientation else 1
            }
        }
        return 1
    }

    private fun ByteArray.u16(at: Int, bigEndian: Boolean): Int {
        val a = this[at].toInt() and 0xff
        val b = this[at + 1].toInt() and 0xff
        return if (bigEndian) a shl 8 or b else b shl 8 or a
    }

    private fun ByteArray.u32(at: Int, bigEndian: Boolean): Long {
        val high = u16(if (bigEndian) at else at + 2, bigEndian).toLong()
        val low = u16(if (bigEndian) at + 2 else at, bigEndian).toLong()
        return high shl 16 or low
    }

    private fun ByteArray.regionMatches(at: Int, expected: ByteArray) =
        at + expected.size <= size && expected.indices.all { this[at + it] == expected[it] }

    /** Re-maps pixels so the image displays upright; orientations 5..8 swap width and height. */
    private fun orient(source: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == 1) return source
        val w = source.width
        val h = source.height
        val transposed = orientation >= 5
        val target = BufferedImage(if (transposed) h else w, if (transposed) w else h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until target.height) for (x in 0 until target.width) {
            val (sx, sy) = when (orientation) {
                2 -> w - 1 - x to y
                3 -> w - 1 - x to h - 1 - y
                4 -> x to h - 1 - y
                5 -> y to x
                6 -> y to h - 1 - x
                7 -> w - 1 - y to h - 1 - x
                else -> w - 1 - y to x // 8
            }
            target.setRGB(x, y, source.getRGB(sx, sy))
        }
        return target
    }

    private fun formatOf(bytes: ByteArray): String? = when {
        bytes.startsWith(PNG_SIGNATURE) -> "png"
        bytes.startsWith(JPEG_SIGNATURE) -> "jpeg"
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun avatar(source: BufferedImage): BufferedImage {
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        return render(source, x, y, side, side, AVATAR_SIDE, AVATAR_SIDE)
    }

    private fun background(source: BufferedImage): BufferedImage {
        val scale = minOf(1.0, BACKGROUND_LONGEST_SIDE.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, Math.round(source.width * scale).toInt())
        val height = maxOf(1, Math.round(source.height * scale).toInt())
        return render(source, 0, 0, source.width, source.height, width, height)
    }

    /** Draws a source region onto an opaque white RGB canvas, flattening any transparency. */
    private fun render(source: BufferedImage, x: Int, y: Int, w: Int, h: Int, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.drawImage(source, 0, 0, width, height, x, y, x + w, y + h, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val output = ByteArrayOutputStream()
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JPEG_QUALITY
                }
                writer.write(null, IIOImage(image, null, null), param) // null metadata: only the default JFIF header
            }
            return output.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    private fun invalid() = IllegalArgumentException(INVALID_IMAGE)

    companion object {
        const val INVALID_IMAGE = "Upload a JPEG or PNG image up to 5 MB and 4096×4096 pixels."
        private const val AVATAR_SIDE = 256
        private const val BACKGROUND_LONGEST_SIDE = 1920
        private const val JPEG_QUALITY = 0.85f
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private const val SOS = 0xDA
        private const val EOI = 0xD9
        private const val APP1 = 0xE1
        private const val ORIENTATION_TAG = 0x0112
        private const val SHORT_TYPE = 3
        private val EXIF_HEADER = byteArrayOf(69, 120, 105, 102, 0, 0)
        private val JPEG_SIGNATURE = byteArrayOf(-1, -40, -1)
    }
}
