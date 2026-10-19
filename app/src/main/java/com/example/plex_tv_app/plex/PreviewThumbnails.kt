package com.example.plex_tv_app.plex

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Video preview thumbnails (Plex makes these when a library's "Generate video preview thumbnails"
 * option is on): one small picture every few seconds, all in a single BIF file. Used to show the
 * picture at the spot you're seeking to.
 */
class PreviewThumbnails private constructor(
    private val data: ByteArray,
    private val timesMs: LongArray, // when each picture is from
    private val offsets: IntArray, // where each picture starts in [data]; one extra entry marks the end
) {
    /** The JPEG for the picture at (or just before) [positionMs], or null. */
    fun frameAt(positionMs: Long): ByteArray? {
        if (timesMs.isEmpty()) return null
        var lo = 0
        var hi = timesMs.lastIndex
        while (lo < hi) { // last picture at or before the position
            val mid = (lo + hi + 1) / 2
            if (timesMs[mid] <= positionMs) lo = mid else hi = mid - 1
        }
        val start = offsets[lo]
        val end = offsets[lo + 1]
        if (start < 0 || end > data.size || end <= start) return null
        return data.copyOfRange(start, end)
    }

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)

        /** Reads a BIF file; null if it isn't one. */
        fun parse(bytes: ByteArray): PreviewThumbnails? {
            if (bytes.size < 64 || !bytes.copyOfRange(0, 8).contentEquals(MAGIC)) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = buf.getInt(12)
            val interval = buf.getInt(16).toLong().takeIf { it > 0 } ?: 1000L // ms per timestamp unit
            if (count <= 0 || 64 + (count + 1) * 8 > bytes.size) return null
            val times = LongArray(count)
            val offsets = IntArray(count + 1)
            for (i in 0..count) {
                val at = 64 + i * 8
                if (i < count) times[i] = (buf.getInt(at).toLong() and 0xffffffffL) * interval
                offsets[i] = buf.getInt(at + 4)
            }
            return PreviewThumbnails(bytes, times, offsets)
        }
    }
}
