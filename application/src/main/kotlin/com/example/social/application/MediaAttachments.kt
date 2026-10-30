package com.example.social.application

import com.example.social.domain.PostImage

class AttachmentUpload(val filename: String, bytes: ByteArray) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
}

class AttachmentTooLarge(limit: Int) : IllegalArgumentException("Attachment exceeds $limit bytes")

/**
 * Accepts an original media file when its extension and its leading bytes agree on a supported format.
 *
 * Only bounded header/signature checks are made: nothing is decoded, so a file whose header is genuine but whose
 * body is corrupt is still accepted. The user-supplied Content-Type is never consulted. Accepted bytes are kept
 * verbatim — animation, transparency and metadata (including EXIF/location) are NOT stripped.
 */
object AttachmentPolicy {
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    const val MAX_VIDEO_BYTES = 100 * 1024 * 1024
    const val MAX_AUDIO_BYTES = 25 * 1024 * 1024

    private class Format(val mediaType: String, val limit: Int, val matches: (ByteArray) -> Boolean)

    private val jpeg = Format("image/jpeg", MAX_IMAGE_BYTES) { it.startsWith(0xFF, 0xD8, 0xFF) }
    private val png = Format("image/png", MAX_IMAGE_BYTES) { it.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) }
    private val formats = mapOf(
        "jpg" to jpeg, "jpeg" to jpeg, "png" to png, "apng" to png,
        "webp" to Format("image/webp", MAX_IMAGE_BYTES) { it.isRiff("WEBP") },
        "gif" to Format("image/gif", MAX_IMAGE_BYTES) { it.asciiAt(0, "GIF87a") || it.asciiAt(0, "GIF89a") },
        "heic" to Format("image/heic", MAX_IMAGE_BYTES) { it.hasBrand("heic", "heix", "heim", "heis", "hevc", "hevx") },
        "avif" to Format("image/avif", MAX_IMAGE_BYTES) { it.hasBrand("avif", "avis") },
        "mp4" to Format("video/mp4", MAX_VIDEO_BYTES, ::isMp4Video),
        "m4v" to Format("video/mp4", MAX_VIDEO_BYTES, ::isMp4Video),
        "mov" to Format("video/quicktime", MAX_VIDEO_BYTES) { it.hasBrand("qt  ") },
        "webm" to Format("video/webm", MAX_VIDEO_BYTES, ::isWebm),
        "mp3" to Format("audio/mpeg", MAX_AUDIO_BYTES) { isMpegAudioFrame(it, afterId3(it)) },
        "aac" to Format("audio/aac", MAX_AUDIO_BYTES) { isAdtsFrame(it, afterId3(it)) },
        "m4a" to Format("audio/mp4", MAX_AUDIO_BYTES) { it.hasBrand("M4A ", "M4B ") },
        "wav" to Format("audio/wav", MAX_AUDIO_BYTES) { it.isRiff("WAVE") },
        "ogg" to Format("audio/ogg", MAX_AUDIO_BYTES, ::isOggAudio),
        "flac" to Format("audio/flac", MAX_AUDIO_BYTES) { it.asciiAt(0, "fLaC") },
    )

    fun validate(upload: AttachmentUpload): PostImage {
        val format = formats[upload.filename.substringAfterLast('.', "").lowercase()]
            ?: throw IllegalArgumentException("Unsupported attachment type")
        val bytes = upload.bytes
        require(bytes.isNotEmpty()) { "Attachment is empty" }
        if (bytes.size > format.limit) throw AttachmentTooLarge(format.limit)
        require(format.matches(bytes)) { "Attachment content does not match its extension" }
        return PostImage(format.mediaType, bytes)
    }

    private fun isMp4Video(bytes: ByteArray) =
        bytes.hasBrand("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "M4VH", "M4VP", "dash")

    // Only the EBML header is inspected; DocType must be exactly "webm" (generic Matroska is refused).
    private fun isWebm(bytes: ByteArray): Boolean {
        if (!bytes.startsWith(0x1A, 0x45, 0xDF, 0xA3)) return false
        val end = minOf(bytes.size, 64)
        return (4 until end - 6).any { i ->
            bytes.u(i) == 0x42 && bytes.u(i + 1) == 0x82 && bytes.u(i + 2) == 0x84 && bytes.asciiAt(i + 3, "webm")
        }
    }

    // Skips one ID3v2 tag using its syncsafe size; the tag must end inside the upload.
    private fun afterId3(bytes: ByteArray): Int {
        if (bytes.size < 10 || !bytes.asciiAt(0, "ID3")) return 0
        if ((6..9).any { bytes.u(it) > 0x7F }) return -1
        val size = (6..9).fold(0) { acc, i -> (acc shl 7) or bytes.u(i) }
        val footer = if (bytes.u(5) and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    // MPEG audio frame header: 11-bit sync, layer != reserved(00, which is ADTS), sane bitrate and sample rate.
    private fun isMpegAudioFrame(bytes: ByteArray, at: Int): Boolean {
        if (at < 0 || at + 4 > bytes.size) return false
        val b1 = bytes.u(at + 1); val b2 = bytes.u(at + 2)
        return bytes.u(at) == 0xFF && b1 and 0xE0 == 0xE0 && (b1 shr 3) and 3 != 1 && (b1 shr 1) and 3 != 0 &&
            (b2 shr 4) != 0xF && (b2 shr 2) and 3 != 3
    }

    // ADTS header: 12-bit sync, layer 00, valid sampling index, frame length covering at least the header.
    private fun isAdtsFrame(bytes: ByteArray, at: Int): Boolean {
        if (at < 0 || at + 7 > bytes.size) return false
        val b1 = bytes.u(at + 1)
        val frameLength = ((bytes.u(at + 3) and 3) shl 11) or (bytes.u(at + 4) shl 3) or (bytes.u(at + 5) shr 5)
        return bytes.u(at) == 0xFF && b1 and 0xF6 == 0xF0 && (bytes.u(at + 2) shr 2) and 0xF < 13 && frameLength >= 7
    }

    // First Ogg page must be version 0, beginning-of-stream, and carry a known audio codec id packet.
    private fun isOggAudio(bytes: ByteArray): Boolean {
        if (bytes.size < 28 || !bytes.asciiAt(0, "OggS") || bytes.u(4) != 0 || bytes.u(5) and 2 == 0) return false
        val payload = 27 + bytes.u(26)
        return bytes.asciiAt(payload, "\u0001vorbis") || bytes.asciiAt(payload, "OpusHead") ||
            bytes.asciiAt(payload, "\u007FFLAC") || bytes.asciiAt(payload, "Speex   ")
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.startsWith(vararg v: Int) = size >= v.size && v.indices.all { u(it) == v[it] }
    private fun ByteArray.asciiAt(at: Int, s: String) =
        at >= 0 && at + s.length <= size && s.indices.all { u(at + it) == s[it].code }
    private fun ByteArray.isRiff(form: String) = size >= 12 && asciiAt(0, "RIFF") && asciiAt(8, form)

    // Leading ISO-BMFF ftyp box: brands are read only within the declared box, itself capped at 4 KiB.
    private fun ByteArray.hasBrand(vararg wanted: String): Boolean {
        if (size < 16 || !asciiAt(4, "ftyp")) return false
        val boxSize = (u(0) shl 24) or (u(1) shl 16) or (u(2) shl 8) or u(3)
        if (boxSize < 16 || boxSize > minOf(size, 4096) || boxSize % 4 != 0) return false
        val brandOffsets = listOf(8) + (16 until boxSize step 4)
        return brandOffsets.any { at -> wanted.any { asciiAt(at, it) } }
    }
}
