package uk.co.agentengines.toneroom

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class AudioEdit(val startUs: Long, val durationUs: Long)

private data class Box(val data: Long, val end: Long)

private fun FileChannel.bytes(pos: Long, count: Int, end: Long): ByteBuffer {
    require(pos >= 0 && count.toLong() <= end - pos)
    val buffer = ByteBuffer.allocate(count)
    while (buffer.hasRemaining()) require(read(buffer, pos + buffer.position()) > 0)
    buffer.flip()
    return buffer
}

private fun FileChannel.boxes(parent: Box, type: String): Sequence<Box> = sequence {
    var pos = parent.data
    while (pos <= parent.end - 8) {
        val header = bytes(pos, 8, parent.end)
        val size32 = header.int.toLong() and 0xffffffffL
        val tag = ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII)
        val headerLength = if (size32 == 1L) 16 else 8
        val length =
            when (size32) {
                0L -> parent.end - pos
                1L -> bytes(pos + 8, 8, parent.end).long
                else -> size32
            }
        require(length in headerLength.toLong()..(parent.end - pos))
        if (tag == type) yield(Box(pos + headerLength, pos + length))
        pos += length
    }
}

// The creation/modification timestamps preceding the ID or timescale depend on box version.
private fun FileChannel.headerField(box: Box): Long {
    val offset =
        when (bytes(box.data, 1, box.end).get().toInt()) {
            0 -> 12L
            1 -> 20L
            else -> error("Unknown MP4 header version")
        }
    return bytes(box.data + offset, 4, box.end).int.toLong() and 0xffffffffL
}

// Some Android MP4 extractors convert the edit's media time using the movie timescale.
// Only correct an exact match for that error; preserve ordinary AAC gapless metadata.
internal fun correctAacEdit(context: Context, uri: Uri, format: MediaFormat): AudioEdit? {
    if (
        format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC ||
            !format.containsKey(MediaFormat.KEY_ENCODER_DELAY) ||
            !format.containsKey(MediaFormat.KEY_TRACK_ID)
    )
        return null
    val reported = format.getInteger(MediaFormat.KEY_ENCODER_DELAY)
    if (reported <= 0) return null
    val id = format.getInteger(MediaFormat.KEY_TRACK_ID).toLong()
    val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    val ch = input.channel
                    val end =
                        if (descriptor.declaredLength >= 0)
                            descriptor.startOffset + descriptor.declaredLength
                        else ch.size()
                    val movie = ch.boxes(Box(descriptor.startOffset, end), "moov").first()
                    val movieScale = ch.headerField(ch.boxes(movie, "mvhd").first()).toDouble()
                    val track =
                        ch.boxes(movie, "trak").first {
                            ch.headerField(ch.boxes(it, "tkhd").first()) == id
                        }
                    val media = ch.boxes(track, "mdia").first()
                    val mediaScale = ch.headerField(ch.boxes(media, "mdhd").first()).toDouble()
                    require(movieScale > 0 && mediaScale > 0 && movieScale != mediaScale)
                    val edits = ch.boxes(ch.boxes(track, "edts").first(), "elst").first()
                    val head = ch.bytes(edits.data, 8, edits.end)
                    val version = head.int ushr 24
                    val count = head.int
                    require(version in 0..1 && count in 1..2)
                    val entries =
                        ch.bytes(edits.data + 8, count * if (version == 0) 12 else 20, edits.end)
                    var emptyUs = 0L
                    var actualDelay = 0L
                    var durationUs = 0L
                    repeat(count) { i ->
                        val duration =
                            if (version == 1) entries.long else entries.int.toLong() and 0xffffffffL
                        val mediaTime = if (version == 1) entries.long else entries.int.toLong()
                        require(duration >= 0 && entries.int == 0x00010000)
                        if (i < count - 1) {
                            require(mediaTime == -1L)
                            emptyUs = (duration * 1_000_000.0 / movieScale).roundToLong()
                        } else {
                            require(mediaTime > 0 && duration > 0)
                            val wrongDelay =
                                (mediaTime * rate.toDouble() / movieScale).roundToLong()
                            actualDelay = (mediaTime * rate.toDouble() / mediaScale).roundToLong()
                            require(
                                abs(reported - wrongDelay) <= 1 &&
                                    abs(reported - actualDelay) > 1 &&
                                    actualDelay in 0L..Int.MAX_VALUE.toLong()
                            )
                            durationUs = (duration * 1_000_000.0 / movieScale).roundToLong()
                        }
                    }
                    format.setInteger(MediaFormat.KEY_ENCODER_DELAY, actualDelay.toInt())
                    AudioEdit(emptyUs, durationUs)
                }
            }
        }
        .getOrNull()
}
