package com.example.social.infrastructure

import com.example.social.application.MediaContent
import java.io.IOException
import java.io.InputStream
import java.sql.PreparedStatement
import javax.sql.DataSource

internal const val MEDIA_CHUNK_BYTES = 64 * 1024

/**
 * Streams a bytea column without ever materialising it: metadata reads only octet_length, and each chunk
 * is a separate short query that re-applies [source]'s identity and visibility predicates. No connection
 * is held between reads, so a slow client cannot pin the pool, and revoked access ends the stream.
 *
 * [source] is "FROM ... WHERE ..." selecting exactly one row; [alias] names the table owning media_type/bytes;
 * [bind] sets the source's parameters starting at the given index.
 */
internal class PostgresMediaContent(
    private val dataSource: DataSource,
    private val alias: String,
    private val source: String,
    private val bind: (PreparedStatement, Int) -> Unit,
) {
    fun load(): MediaContent? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT $alias.media_type, octet_length($alias.bytes) $source").use {
            bind(it, 1)
            it.executeQuery().use { rows ->
                if (!rows.next()) null
                else MediaContent(rows.getString(1), rows.getLong(2)) { offset, length -> ChunkStream(offset, offset + length) }
            }
        }
    }

    /** bytea caps at 1GB so offsets fit substring's int signature. Returns null when the row is gone or no longer visible. */
    private fun chunk(offset: Long, length: Int): ByteArray? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT substring($alias.bytes FROM ? FOR ?) $source").use {
            it.setInt(1, Math.toIntExact(offset + 1)); it.setInt(2, length); bind(it, 3)
            it.executeQuery().use { rows -> if (rows.next()) rows.getBytes(1) else null }
        }
    }

    private inner class ChunkStream(private var position: Long, private val end: Long) : InputStream() {
        private var buffer = ByteArray(0)
        private var index = 0
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            java.util.Objects.checkFromIndexSize(off, len, b.size)
            ensureOpen()
            if (len == 0) return 0
            if (index == buffer.size && !fill()) return -1
            val n = minOf(len, buffer.size - index)
            System.arraycopy(buffer, index, b, off, n)
            index += n
            return n
        }

        override fun skip(n: Long): Long {
            ensureOpen()
            if (n <= 0) return 0
            val fromBuffer = minOf(n, (buffer.size - index).toLong())
            index += fromBuffer.toInt()
            val fromSource = minOf(n - fromBuffer, end - position)
            position += fromSource
            return fromBuffer + fromSource
        }

        override fun available(): Int = if (closed) 0 else buffer.size - index

        override fun close() { closed = true; buffer = ByteArray(0); index = 0 }

        private fun ensureOpen() { if (closed) throw IOException("Media stream closed") }

        // A short or missing chunk means the media vanished or became invisible: fail, never retry.
        private fun fill(): Boolean {
            if (position >= end) return false
            val wanted = minOf(MEDIA_CHUNK_BYTES.toLong(), end - position).toInt()
            val bytes = try { chunk(position, wanted) } catch (e: java.sql.SQLException) { throw IOException("Media read failed", e) }
            if (bytes == null || bytes.size != wanted) throw IOException("Media no longer available")
            buffer = bytes; index = 0; position += wanted
            return true
        }
    }
}
