package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.UserId
import com.example.social.http.AccessControl
import com.example.social.infrastructure.RasterMemberImages
import org.http4k.core.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import kotlin.test.*

/** Upload regression through the settings HTTP seam using the real raster processor. */
class RasterUploadTest {
    private val account = Account(UserId.new(), "reader@example.com", "Reader", null, true)
    private val accounts = object : Accounts {
        override fun create(account: Account) = Unit
        override fun update(account: Account) = Unit
        override fun byId(id: UserId) = account.takeIf { it.id == id }
        override fun byEmail(email: String) = account.takeIf { it.email == email }
        override fun putToken(token: AccountToken) = Unit
        override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
    }
    private val sessions = object : Sessions {
        override fun create(session: Session) = Unit
        override fun find(digest: String, now: Instant) =
            if (digest == tokenDigest("reader")) Session(digest, account.id, "csrf", now.plusSeconds(60)) else null
        override fun delete(digest: String) = Unit
        override fun deleteForAccount(id: UserId) = Unit
    }
    private val store = object : MemberCustomizations {
        val preferences = mutableMapOf<UserId, CustomizationPreferences>()
        val images = mutableMapOf<Pair<UserId, MemberImageKind>, MemberImage>()
        override fun preferences(owner: UserId) = preferences[owner] ?: CustomizationPreferences()
        override fun saveTheme(owner: UserId, theme: ThemeMode) { preferences[owner] = preferences(owner).copy(theme = theme) }
        override fun saveBio(owner: UserId, bio: String) { preferences[owner] = preferences(owner).copy(bio = bio) }
        override fun image(owner: UserId, kind: MemberImageKind) = images[owner to kind]
        override fun saveImage(owner: UserId, kind: MemberImageKind, image: MemberImage) {
            images[owner to kind] = image
            val current = preferences(owner)
            preferences[owner] = if (kind == MemberImageKind.AVATAR) current.copy(avatarVersion = image.version) else current.copy(backgroundVersion = image.version)
        }
        override fun removeImage(owner: UserId, kind: MemberImageKind) {
            images.remove(owner to kind)
            val current = preferences(owner)
            preferences[owner] = if (kind == MemberImageKind.AVATAR) current.copy(avatarVersion = null) else current.copy(backgroundVersion = null)
        }
    }
    private val access = AccessControl(sessions, false)
    private val app: HttpHandler = access.protect(settingsRoutes(
        CustomizationService(store, RasterMemberImages()), accounts,
        AccountService(accounts, object : Passwords {
            override fun hash(password: String) = password
            override fun matches(password: String, hash: String) = false
        }, { "token" }, { _, _, _ -> }),
        access))

    private fun upload(kind: String, bytes: ByteArray) =
        app(Request(Method.POST, "/settings/images/$kind").header("Cookie", "session=reader")
            .header("content-type", "image/png").header("X-CSRF-Token", "csrf").body(Body(java.nio.ByteBuffer.wrap(bytes))))
    private fun avatar() = bytesOf(app(Request(Method.GET, "/media/profiles/${account.id.value}")).also { assertEquals(Status.OK, it.status) })
    private fun background() = bytesOf(app(Request(Method.GET, "/media/background").header("Cookie", "session=reader")).also { assertEquals(Status.OK, it.status) })
    private fun bytesOf(response: Response) = response.body.payload.let { ByteArray(it.remaining()).also(it::get) }

    @Test fun `uploaded PNG and JPEG images are served as metadata-free JPEGs sized per kind`() {
        // 600x300 PNG: transparent left half, opaque red right half. Avatar centre-crops 300x300 then scales to 256.
        val png = BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().run { color = Color.RED; fillRect(300, 0, 300, 300); dispose() }
        }
        assertEquals(Status.SEE_OTHER, upload("avatar", pngWithText(png, "SECRET-PNG-MARKER")).status)
        val avatarBytes = avatar()
        val avatar = decodeJpeg(avatarBytes)
        assertEquals(256 to 256, avatar.width to avatar.height)
        assertNear(Color.WHITE, avatar.getRGB(20, 128)) // transparency flattened onto white
        assertNear(Color.RED, avatar.getRGB(236, 128))
        assertNoMarker(avatarBytes)

        // Small images: avatar upscales to 256, background is never upscaled.
        upload("avatar", jpegWithComment(solid(40, 40, Color.GREEN), "x"))
        assertEquals(256 to 256, decodeJpeg(avatar()).let { it.width to it.height })
        upload("background", jpegWithComment(solid(100, 50, Color.GREEN), "x"))
        assertEquals(100 to 50, decodeJpeg(background()).let { it.width to it.height })

        // 4000x1000 JPEG background fits longest side to 1920 -> 1920x480.
        assertEquals(Status.SEE_OTHER, upload("background", jpegWithComment(solid(4000, 1000, Color.BLUE), "SECRET-JPEG-MARKER")).status)
        val backgroundBytes = background()
        val background = decodeJpeg(backgroundBytes)
        assertEquals(1920 to 480, background.width to background.height)
        assertNear(Color.BLUE, background.getRGB(960, 240))
        assertNoMarker(backgroundBytes)
    }

    @Test fun `non-images, unsupported formats, truncated and pixel-bomb uploads are rejected with a fixed message`() {
        val validPng = pngWithText(solid(10, 10, Color.RED), "x")
        val validJpeg = jpegWithComment(solid(64, 64, Color.RED), "x")
        val gif = ByteArrayOutputStream().also { ImageIO.write(solid(10, 10, Color.RED), "gif", it) }.toByteArray()
        val rejected = mapOf(
            "svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(),
            "text" to "not an image".toByteArray(),
            "gif" to gif,
            "truncated png" to validPng.copyOf(validPng.size / 2),
            "truncated jpeg" to validJpeg.copyOf(validJpeg.size / 2),
            "too wide" to pngHeaderClaiming(4097, 10),
            "decodable but too wide" to grayPng(4097, 1),
            "decodable but too many pixels" to grayPng(4000, 4001),
            "jpeg missing its tail" to validJpeg.copyOf(validJpeg.size - 20),
            "too many pixels" to pngHeaderClaiming(4000, 4001),
            "overflowing dimensions" to pngHeaderClaiming(Int.MAX_VALUE, Int.MAX_VALUE),
        )
        for ((case, bytes) in rejected) for (kind in listOf("avatar", "background")) {
            val response = upload(kind, bytes)
            assertEquals(Status.BAD_REQUEST, response.status, "$case $kind")
            assertTrue(response.bodyString().contains("Upload a JPEG or PNG image up to 5 MB and 4096×4096 pixels."), "$case $kind")
        }
        assertTrue(store.images.isEmpty())
    }

    @Test fun `JPEG EXIF orientation is applied to the pixels and then stripped`() {
        // Stored 80x40 quadrants: top-left red, top-right green, bottom-left blue, bottom-right yellow.
        val stored = BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = Color.RED; fillRect(0, 0, 40, 20); color = Color.GREEN; fillRect(40, 0, 40, 20)
                color = Color.BLUE; fillRect(0, 20, 40, 20); color = Color.YELLOW; fillRect(40, 20, 40, 20); dispose()
            }
        }
        val (r, g, b, y) = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        // Expected displayed corners (top-left, top-right, bottom-left, bottom-right) per EXIF orientation.
        val expected = mapOf(
            1 to listOf(r, g, b, y), 2 to listOf(g, r, y, b), 3 to listOf(y, b, g, r), 4 to listOf(b, y, r, g),
            5 to listOf(r, b, g, y), 6 to listOf(b, r, y, g), 7 to listOf(y, g, b, r), 8 to listOf(g, y, r, b),
        )
        val plain = ByteArrayOutputStream().also { ImageIO.write(stored, "jpeg", it) }.toByteArray()
        for ((orientation, corners) in expected) for (bigEndian in listOf(true, false)) {
            assertEquals(Status.SEE_OTHER, upload("background", withExif(plain, exifOrientation(orientation, bigEndian))).status)
            val bytes = background()
            val image = decodeJpeg(bytes)
            val size = if (orientation <= 4) 80 to 40 else 40 to 80
            assertEquals(size, image.width to image.height, "orientation $orientation")
            val (w, h) = size
            listOf(w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4).zip(corners)
                .forEach { (point, color) -> assertNear(color, image.getRGB(point.first, point.second)) }
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Exif"))
        }
        // Malformed EXIF (IFD offset past the segment, absurd entry count) is ignored, never read out of bounds.
        val broken = exifOrientation(6, true).also { it[13] = 0x7f }
        val countBomb = exifOrientation(6, true).also { it[14] = 0xff.toByte(); it[15] = 0xff.toByte(); it[17] = 0x13 }
        for (exif in listOf(broken, countBomb, exifOrientation(6, true).copyOf(12))) {
            assertEquals(Status.SEE_OTHER, upload("background", withExif(plain, exif)).status)
            assertEquals(80 to 40, decodeJpeg(background()).let { it.width to it.height })
        }
    }

    /** "Exif\0\0" + TIFF header + one IFD with a single Orientation (0x0112, SHORT) entry. */
    private fun exifOrientation(orientation: Int, bigEndian: Boolean): ByteArray {
        val tiff = java.nio.ByteBuffer.allocate(26).order(if (bigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN)
        tiff.put(if (bigEndian) "MM".toByteArray() else "II".toByteArray()).putShort(42).putInt(8)
            .putShort(1).putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0)
        return "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.array()
    }

    /** Inserts an APP1 segment after the JFIF APP0 segment of a baseline JPEG. */
    private fun withExif(jpeg: ByteArray, exif: ByteArray): ByteArray {
        val afterApp0 = 4 + ((jpeg[4].toInt() and 0xff) shl 8 or (jpeg[5].toInt() and 0xff))
        val length = exif.size + 2
        return jpeg.copyOf(afterApp0) + byteArrayOf(-1, 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + exif +
            jpeg.copyOfRange(afterApp0, jpeg.size)
    }

    private fun grayPng(width: Int, height: Int) =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY), "png", it) }.toByteArray()

    /** Valid PNG signature and IHDR declaring the given size, with no image data. */
    private fun pngHeaderClaiming(width: Int, height: Int): ByteArray {
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        val crc = java.util.zip.CRC32().apply { update("IHDR".toByteArray()); update(ihdr) }.value.toInt()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) +
            java.nio.ByteBuffer.allocate(25).putInt(13).put("IHDR".toByteArray()).put(ihdr).putInt(crc).array()
    }

    private fun assertNoMarker(bytes: ByteArray) {
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("SECRET"))
        assertFalse(metadataText(bytes).contains("SECRET"))
    }

    private fun solid(w: Int, h: Int, c: Color) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().run { color = c; fillRect(0, 0, w, h); dispose() }
    }

    private fun pngWithText(image: BufferedImage, text: String): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
        val root = IIOMetadataNode("javax_imageio_png_1.0")
        root.appendChild(IIOMetadataNode("tEXt").apply {
            appendChild(IIOMetadataNode("tEXtEntry").apply { setAttribute("keyword", "Comment"); setAttribute("value", text) })
        })
        meta.mergeTree("javax_imageio_png_1.0", root)
        return write(writer, image, meta).also { assertTrue(String(it, Charsets.ISO_8859_1).contains(text)) }
    }

    private fun jpegWithComment(image: BufferedImage, text: String): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
        val tree = meta.getAsTree("javax_imageio_jpeg_image_1.0") as IIOMetadataNode
        (tree.getElementsByTagName("markerSequence").item(0) as IIOMetadataNode)
            .appendChild(IIOMetadataNode("com").apply { setAttribute("comment", text) })
        meta.setFromTree("javax_imageio_jpeg_image_1.0", tree)
        return write(writer, image, meta).also { assertTrue(String(it, Charsets.ISO_8859_1).contains(text)) }
    }

    private fun write(writer: ImageWriter, image: BufferedImage, meta: IIOMetadata): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { writer.output = it; writer.write(IIOImage(image, null, meta)) }
        writer.dispose()
        return out.toByteArray()
    }

    private fun decodeJpeg(bytes: ByteArray): BufferedImage =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            assertEquals("jpeg", reader.formatName.lowercase())
            reader.input = input
            reader.read(0).also { reader.dispose() }
        }

    private fun metadataText(bytes: ByteArray): String =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            reader.input = input
            val meta = reader.getImageMetadata(0)
            val text = StringBuilder()
            fun walk(node: org.w3c.dom.Node) {
                node.attributes?.let { for (i in 0 until it.length) text.append(it.item(i).nodeValue).append(' ') }
                ((node as? IIOMetadataNode)?.userObject as? ByteArray)?.let { text.append(String(it, Charsets.ISO_8859_1)) }
                var child = node.firstChild
                while (child != null) { walk(child); child = child.nextSibling }
            }
            walk(meta.getAsTree(meta.nativeMetadataFormatName))
            reader.dispose()
            text.toString()
        }

    private fun assertNear(expected: Color, rgb: Int) {
        val actual = Color(rgb)
        val delta = maxOf(kotlin.math.abs(expected.red - actual.red), kotlin.math.abs(expected.green - actual.green), kotlin.math.abs(expected.blue - actual.blue))
        assertTrue(delta <= 12, "expected $expected but was $actual")
    }
}
