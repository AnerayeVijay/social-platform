package com.example.social.application

import kotlin.test.*

class MediaAttachmentsTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun validate(name: String, bytes: ByteArray) = AttachmentPolicy.validate(AttachmentUpload(name, bytes))

    @Test fun `jpeg keeps original bytes and canonical type`() {
        val bytes = b(0xFF, 0xD8, 0xFF, 0xE1, 1, 2, 3)
        val image = validate("Photo.JPG", bytes)
        assertEquals("image/jpeg", image.mediaType)
        assertContentEquals(bytes, image.bytes)
    }

    @Test fun `markup renamed as image is rejected`() {
        assertFailsWith<IllegalArgumentException> { validate("x.jpg", "<html><script>".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { validate("x.jpg", "<svg xmlns=".toByteArray()) }
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun riff(form: String, chunk: String) = ascii("RIFF") + b(20, 0, 0, 0) + ascii(form + chunk) + ByteArray(8)
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val body = ascii(major) + b(0, 0, 0, 0) + compatible.fold(ByteArray(0)) { acc, c -> acc + ascii(c) }
        val size = 8 + body.size
        return b(0, 0, 0, size) + ascii("ftyp") + body + b(0, 0, 0, 8) + ascii("mdat")
    }
    private fun webm(docType: String) = b(0x1A, 0x45, 0xDF, 0xA3, 0x80 + 3 + docType.length) +
        b(0x42, 0x82, 0x80 + docType.length) + ascii(docType) + b(0x18, 0x53, 0x80, 0x67)
    private val mp3Frame = b(0xFF, 0xFB, 0x90, 0x64, 0, 0)
    private val adtsFrame = b(0xFF, 0xF1, 0x50, 0x80, 0x02, 0x1F, 0xFC)
    private fun ogg(codec: ByteArray) = ascii("OggS") + b(0, 2) + ByteArray(20) + b(1, codec.size) + codec

    private val accepted = listOf(
        Triple("a.jpeg", b(0xFF, 0xD8, 0xFF, 0xDB), "image/jpeg"),
        Triple("a.png", b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ascii("IHDR"), "image/png"),
        Triple("a.APNG", b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ascii("acTL"), "image/png"),
        Triple("a.webp", riff("WEBP", "VP8L"), "image/webp"),
        Triple("a.gif", ascii("GIF89a") + b(1, 0), "image/gif"),
        Triple("a.gif", ascii("GIF87a") + b(1, 0), "image/gif"),
        Triple("a.heic", ftyp("heic", "mif1", "heic"), "image/heic"),
        Triple("a.heic", ftyp("mif1", "mif1", "heix"), "image/heic"),
        Triple("a.avif", ftyp("avif", "mif1", "avif"), "image/avif"),
        Triple("a.avif", ftyp("mif1", "avis"), "image/avif"),
        Triple("a.mp4", ftyp("isom", "isom", "avc1"), "video/mp4"),
        Triple("a.m4v", ftyp("M4V ", "M4V ", "mp42"), "video/mp4"),
        Triple("a.mov", ftyp("qt  ", "qt  "), "video/quicktime"),
        Triple("a.webm", webm("webm"), "video/webm"),
        Triple("a.mp3", mp3Frame, "audio/mpeg"),
        Triple("a.mp3", ascii("ID3") + b(4, 0, 0, 0, 0, 0, 2) + b(0, 0) + mp3Frame, "audio/mpeg"),
        Triple("a.aac", adtsFrame, "audio/aac"),
        Triple("a.m4a", ftyp("M4A ", "M4A ", "mp42"), "audio/mp4"),
        Triple("a.wav", riff("WAVE", "fmt "), "audio/wav"),
        Triple("a.ogg", ogg(b(1) + ascii("vorbis")), "audio/ogg"),
        Triple("a.ogg", ogg(ascii("OpusHead")), "audio/ogg"),
        Triple("a.flac", ascii("fLaC") + b(0, 0, 0, 34), "audio/flac"),
    )

    @Test fun `every supported format is recognised by extension and signature`() {
        accepted.forEach { (name, bytes, type) ->
            val media = validate(name, bytes)
            assertEquals(type, media.mediaType, name)
            assertContentEquals(bytes, media.bytes, name)
        }
    }

    @Test fun `content that belongs to another format or is spoofed is rejected`() {
        val rejected = listOf(
            "a.webp" to riff("WAVE", "fmt "), "a.wav" to riff("WEBP", "VP8 "),
            "a.mp3" to adtsFrame, "a.aac" to mp3Frame, "a.mp3" to ascii("ID3") + b(4, 0, 0, 0, 0, 0x7F) + mp3Frame,
            "a.webm" to webm("matroska"), "a.webm" to b(0x1A, 0x45, 0xDF, 0xA3, 0x80),
            "a.mp4" to ftyp("qt  ", "qt  "), "a.mov" to ftyp("isom", "isom"), "a.m4a" to ftyp("qt  ", "qt  "),
            "a.heic" to ftyp("avif", "mif1", "avif"), "a.avif" to ftyp("heic", "mif1", "heic"),
            "a.mp4" to b(0, 0, 0x10, 0) + ascii("ftypisom") + ByteArray(8),
            "a.png" to b(0x89) + ascii("PNG"), "a.gif" to ascii("GIF90a"), "a.flac" to ascii("<svg"),
            "a.ogg" to ogg(b(0x80) + ascii("theora")), "a.gif" to ascii("<?xml version=\"1.0\"?><svg>"),
        )
        rejected.forEach { (name, bytes) -> assertFailsWith<IllegalArgumentException>(name) { validate(name, bytes) } }
    }

    @Test fun `empty, extensionless and unknown files are rejected`() {
        assertFailsWith<IllegalArgumentException> { validate("a.jpg", ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { validate("jpg", b(0xFF, 0xD8, 0xFF)) }
        assertFailsWith<IllegalArgumentException> { validate("a.svg", ascii("<svg>")) }
        assertFailsWith<IllegalArgumentException> { validate("a.html", ascii("<html>")) }
        assertFailsWith<IllegalArgumentException> { validate("a.jpg.html", b(0xFF, 0xD8, 0xFF)) }
    }

    @Test fun `size limits depend on media category`() {
        assertEquals(20 * 1024 * 1024, AttachmentPolicy.MAX_IMAGE_BYTES)
        assertEquals(100 * 1024 * 1024, AttachmentPolicy.MAX_VIDEO_BYTES)
        assertEquals(25 * 1024 * 1024, AttachmentPolicy.MAX_AUDIO_BYTES)
        val image = ByteArray(AttachmentPolicy.MAX_IMAGE_BYTES + 1).also { b(0xFF, 0xD8, 0xFF).copyInto(it) }
        assertFailsWith<AttachmentTooLarge> { validate("a.jpg", image) }
        assertEquals("image/jpeg", validate("a.jpg", image.copyOf(AttachmentPolicy.MAX_IMAGE_BYTES)).mediaType)
        assertEquals("audio/flac", validate("a.flac", image.copyOf(AttachmentPolicy.MAX_IMAGE_BYTES + 1).also { ascii("fLaC").copyInto(it) }).mediaType)
        val audio = ByteArray(AttachmentPolicy.MAX_AUDIO_BYTES + 1).also { ascii("fLaC").copyInto(it) }
        assertFailsWith<AttachmentTooLarge> { validate("a.flac", audio) }
        assertEquals("video/webm", validate("a.webm", audio.also { webm("webm").copyInto(it) }).mediaType)
    }

    @Test fun `upload bytes are a defensive copy`() {
        val source = b(0xFF, 0xD8, 0xFF)
        val upload = AttachmentUpload("a.jpg", source)
        source[0] = 0
        upload.bytes[1] = 0
        assertContentEquals(b(0xFF, 0xD8, 0xFF), upload.bytes)
    }

    @Test fun `m4a accepts generic iso brands`() {
        assertEquals("audio/mp4", validate("a.m4a", ftyp("isom", "isom", "mp42")).mediaType)
        assertEquals("audio/mp4", validate("a.M4A", ftyp("mp42", "mp41")).mediaType)
    }

    @Test fun `still images carrying generic iso brands are not accepted as mp4 or m4a`() {
        val heic = ftyp("heic", "mif1", "heic", "isom")
        val avif = ftyp("avif", "mif1", "avif", "miaf", "iso8", "mp42")
        listOf("a.mp4", "a.m4v", "a.m4a").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { validate(name, heic) }
            assertFailsWith<IllegalArgumentException>(name) { validate(name, avif) }
        }
        assertEquals("image/heic", validate("a.heic", heic).mediaType)
        assertEquals("image/avif", validate("a.avif", avif).mediaType)
    }

    private fun atom(type: String, payload: Int = 0, declared: Int = 8 + payload) =
        b(declared ushr 24, (declared ushr 16) and 0xFF, (declared ushr 8) and 0xFF, declared and 0xFF) + ascii(type) + ByteArray(payload)

    @Test fun `legacy quicktime without ftyp is recognised by a sane top-level atom chain`() {
        listOf(atom("moov", 4), atom("wide") + atom("mdat", 16) + atom("moov", 8), atom("free", 2) + atom("mdat"))
            .forEach { assertEquals("video/quicktime", validate("a.MOV", it).mediaType) }
    }

    @Test fun `legacy quicktime atoms with unsafe bounds or no movie data are rejected`() {
        listOf(
            atom("moov", 4, declared = 100), atom("wide", declared = 4) + atom("moov"), atom("moov", declared = 0),
            atom("wide") + atom("free"), atom("free", 2), atom("skip") + atom("moov"), atom("mdat").copyOf(6),
            ascii("<html>moov"), atom("wide") + ascii("<html><script>"),
        ).forEachIndexed { i, bytes -> assertFailsWith<IllegalArgumentException>("case $i") { validate("a.mov", bytes) } }
    }

    private fun ebml(vararg children: ByteArray, declared: Int? = null): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, c -> acc + c }
        val size = declared ?: body.size
        return b(0x1A, 0x45, 0xDF, 0xA3, 0x40 or (size ushr 8), size and 0xFF) + body + b(0x18, 0x53, 0x80, 0x67)
    }
    private fun element(id: ByteArray, payload: ByteArray) =
        id + b(0x40 or (payload.size ushr 8), payload.size and 0xFF) + payload
    private val void = b(0xEC)
    private val docType = b(0x42, 0x82)

    @Test fun `webm doc type is found anywhere inside the bounded ebml header`() {
        val bytes = ebml(element(b(0x42, 0x86), b(1)), element(void, ByteArray(200)), element(docType, ascii("webm")))
        assertEquals("video/webm", validate("a.webm", bytes).mediaType)
    }

    @Test fun `webm header that only hides or overstates a webm doc type is rejected`() {
        val webmType = element(docType, ascii("webm"))
        listOf(
            ebml(element(docType, ascii("matroska")), element(void, ascii("webm") + b(0x42, 0x82, 0x84) + ascii("webm"))),
            ebml(element(void, b(0x42, 0x82, 0x84) + ascii("webm"))),
            ebml(webmType, declared = webmType.size + 20),
            ebml(webmType, element(void, ByteArray(5000))),
            ebml(webmType, webmType),
            ebml(element(docType, ascii("webmx"))),
            ebml(element(docType, ascii("web"))),
            ebml(docType + b(0xFF) + ascii("webm") + ByteArray(123)),
            ebml(b(0x08, 1, 2, 3, 4, 0x80) + webmType),
            b(0x1A, 0x45, 0xDF, 0xA3, 0x40, webmType.size + 8) + webmType + b(0x42, 0x86, 0x40),
            ebml(docType + b(0x40, 0x10) + ascii("webm")),
            ebml(b(0) + webmType),
        ).forEachIndexed { i, bytes -> assertFailsWith<IllegalArgumentException>("case $i") { validate("a.webm", bytes) } }
        assertEquals("video/webm", validate("a.webm", ebml(element(docType, ascii("webm") + b(0)))).mediaType)
    }

    @Test fun `max bytes follows the filename extension and rejects unsupported names`() {
        assertEquals(AttachmentPolicy.MAX_IMAGE_BYTES, AttachmentPolicy.maxBytes("a.HEIC"))
        assertEquals(AttachmentPolicy.MAX_VIDEO_BYTES, AttachmentPolicy.maxBytes("clip.mov"))
        assertEquals(AttachmentPolicy.MAX_AUDIO_BYTES, AttachmentPolicy.maxBytes("song.m4a"))
        listOf("a.svg", "jpg", "a.jpg.html", "").forEach { assertFailsWith<IllegalArgumentException>(it) { AttachmentPolicy.maxBytes(it) } }
    }

    @Test fun `validated original cannot be changed through the input array or any returned array`() {
        val source = b(0xFF, 0xD8, 0xFF, 0xE0, 7)
        val upload = AttachmentUpload("a.jpg", source)
        assertEquals(5, upload.size)
        val media = AttachmentPolicy.validate(upload)
        source.fill(0)
        upload.bytes.fill(0)
        media.bytes.fill(0)
        assertContentEquals(b(0xFF, 0xD8, 0xFF, 0xE0, 7), media.bytes)
        assertContentEquals(b(0xFF, 0xD8, 0xFF, 0xE0, 7), upload.bytes)
        assertEquals(5, media.byteCount)
        assertTrue(media.readOnlyBytes().isReadOnly)
    }
}
