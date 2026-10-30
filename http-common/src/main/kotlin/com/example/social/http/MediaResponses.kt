package com.example.social.http

import com.example.social.domain.PostImage
import org.http4k.core.Body
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.nio.ByteBuffer

private val EXTENSIONS = mapOf(
    "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "image/heic" to "heic", "image/avif" to "avif",
    "video/mp4" to "mp4", "video/webm" to "webm", "video/quicktime" to "mov",
    "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/aac" to "aac", "audio/flac" to "flac"
)
private const val FALLBACK_TYPE = "application/octet-stream"

private sealed interface RangeResult {
    data object Full : RangeResult
    data object Unsatisfiable : RangeResult
    data class Slice(val start: Int, val end: Int) : RangeResult
}

/** Serves stored original bytes: whitelisted type, generated filename (never the client's), single byte range. */
fun originalMediaResponse(request: Request, media: PostImage?): Response {
    if (media == null) return Response(Status.NOT_FOUND)
    val bytes = media.bytes
    val canonical = media.mediaType.substringBefore(';').trim().lowercase().takeIf { it in EXTENSIONS }
    val type = canonical ?: FALLBACK_TYPE
    val disposition = if (canonical == null || request.query("download") == "1") "attachment" else "inline"
    val base = { status: Status -> Response(status).header("Content-Type", type).header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, no-store").header("Accept-Ranges", "bytes")
        .header("Content-Disposition", "$disposition; filename=\"attachment.${EXTENSIONS[canonical] ?: "bin"}\"") }
    return when (val range = parseRange(request.header("Range"), bytes.size)) {
        RangeResult.Full -> base(Status.OK).header("Content-Length", bytes.size.toString()).body(Body(ByteBuffer.wrap(bytes)))
        RangeResult.Unsatisfiable -> Response(Status.REQUESTED_RANGE_NOT_SATISFIABLE).header("Content-Range", "bytes */${bytes.size}")
            .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
        is RangeResult.Slice -> base(Status.PARTIAL_CONTENT).header("Content-Range", "bytes ${range.start}-${range.end}/${bytes.size}")
            .header("Content-Length", (range.end - range.start + 1).toString())
            .body(Body(ByteBuffer.wrap(bytes.copyOfRange(range.start, range.end + 1))))
    }
}

private fun parseRange(header: String?, length: Int): RangeResult {
    if (header == null || !header.startsWith("bytes=")) return RangeResult.Full
    val spec = header.removePrefix("bytes=").trim()
    val match = Regex("(\\d*)-(\\d*)").matchEntire(spec) ?: return RangeResult.Unsatisfiable
    val first = match.groupValues[1]; val last = match.groupValues[2]
    if (first.isEmpty() && last.isEmpty()) return RangeResult.Unsatisfiable
    val a = first.toLongOrNull(); val b = last.toLongOrNull()
    if ((first.isNotEmpty() && a == null) || (last.isNotEmpty() && b == null)) return RangeResult.Unsatisfiable
    val size = length.toLong()
    val (start, end) = when {
        a == null -> { if (b!! == 0L || size == 0L) return RangeResult.Unsatisfiable; maxOf(0L, size - b) to size - 1 }
        b == null -> a to size - 1
        else -> { if (b < a) return RangeResult.Unsatisfiable; a to minOf(b, size - 1) }
    }
    if (start >= size) return RangeResult.Unsatisfiable
    return RangeResult.Slice(start.toInt(), end.toInt())
}
