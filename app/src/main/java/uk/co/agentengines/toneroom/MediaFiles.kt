package uk.co.agentengines.toneroom

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal data class ImportInfo(val video: Boolean, val offsetUs: Long)

private fun MediaExtractor.track(prefix: String) =
    (0 until trackCount).firstOrNull {
        getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
    }

private fun MediaFormat.integer(key: String, fallback: Int) =
    if (containsKey(key)) getInteger(key) else fallback

internal fun importMedia(context: Context, uri: Uri, wav: File): ImportInfo {
    val tracks = MediaExtractor()
    var decoder: MediaCodec? = null
    var output: WaveWriter? = null
    try {
        tracks.setDataSource(context, uri, null)
        val audio = tracks.track("audio/") ?: error("This file has no audio track.")
        val video = tracks.track("video/")
        val format = tracks.getTrackFormat(audio)
        for (f in listOfNotNull(format, video?.let(tracks::getTrackFormat))) {
            if (f.containsKey(MediaFormat.KEY_DURATION))
                require(f.getLong(MediaFormat.KEY_DURATION) <= MAX_SECONDS * 1_000_000L + 150000) {
                    "Choose a clip of five minutes or less."
                }
        }
        tracks.selectTrack(audio)
        val firstPacketUs = tracks.sampleTime.coerceAtLeast(0L)
        val edit = correctAacEdit(context, uri, format)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format.")
        val codec = MediaCodec.createDecoderByType(mime)
        decoder = codec
        codec.configure(format, null, null, 0)
        codec.start()
        var ended = false
        var eof = false
        var current = format
        var startUs = Long.MIN_VALUE
        val info = MediaCodec.BufferInfo()
        val buffer = ShortArray(4096)
        val zero = ShortArray(4096)
        while (!ended) {
            if (!eof) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val input = requireNotNull(codec.getInputBuffer(i))
                    input.clear()
                    val n = tracks.readSampleData(input, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eof = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, tracks.sampleTime.coerceAtLeast(0), 0)
                        tracks.advance()
                    }
                }
            }
            val i = codec.dequeueOutputBuffer(info, 10000)
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) current = codec.outputFormat
            if (i < 0) continue
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val rate = current.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = current.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding =
                    current.integer(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                val bytes =
                    when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> 2
                        AudioFormat.ENCODING_PCM_FLOAT,
                        AudioFormat.ENCODING_PCM_32BIT -> 4
                        AudioFormat.ENCODING_PCM_8BIT -> 1
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                        else -> error("This audio format is unavailable on this phone.")
                    }
                val dest =
                    output
                        ?: WaveWriter(wav, rate).also {
                            output = it
                            startUs = info.presentationTimeUs.coerceAtLeast(0)
                        }
                require(dest.rate == rate && channels in 1..32) {
                    "This track changes audio format part way through."
                }
                val pcm =
                    requireNotNull(codec.getOutputBuffer(i))
                        .duplicate()
                        .order(ByteOrder.LITTLE_ENDIAN)
                pcm.position(info.offset)
                pcm.limit(info.offset + info.size)
                val frames = pcm.remaining() / (channels * bytes)
                val expected =
                    ((info.presentationTimeUs - startUs) * rate / 1_000_000L).coerceAtLeast(0)
                require(expected + frames <= rate.toLong() * MAX_SECONDS + rate / 5) {
                    "Choose a clip of five minutes or less."
                }
                if (expected > dest.count + rate / 100)
                    while (dest.count < expected) dest.write(
                        zero,
                        min(zero.size.toLong(), expected - dest.count).toInt(),
                    )
                val overlap =
                    if (dest.count - expected > rate / 100)
                        min(frames.toLong(), dest.count - expected).toInt()
                    else 0
                pcm.position(pcm.position() + overlap * channels * bytes)
                var left = frames - overlap
                if (edit != null) {
                    val remaining = edit.durationUs * rate / 1_000_000L - dest.count
                    left = min(left.toLong(), remaining.coerceAtLeast(0)).toInt()
                }
                while (left > 0) {
                    val take = min(left, buffer.size)
                    for (k in 0 until take) {
                        var sum = 0L
                        repeat(channels) {
                            sum +=
                                when (encoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT ->
                                        (pcm.float.coerceIn(-1f, 1f) * 32767).toInt()
                                    AudioFormat.ENCODING_PCM_32BIT -> pcm.int shr 16
                                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                        pcm.get()
                                        (pcm.get().toInt() and 255) or (pcm.get().toInt() shl 8)
                                    }
                                    AudioFormat.ENCODING_PCM_8BIT ->
                                        ((pcm.get().toInt() and 255) - 128) shl 8
                                    else -> pcm.short.toInt()
                                }
                        }
                        buffer[k] = (sum / channels).toShort()
                    }
                    dest.write(buffer, take)
                    left -= take
                }
            }
            ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(i, false)
        }
        require((output?.count ?: 0L) > 0) { "No readable sound was found in this clip." }
        return ImportInfo(video != null, edit?.let { max(firstPacketUs, it.startUs) } ?: startUs)
    } finally {
        try {
            output?.close()
        } finally {
            try {
                decoder?.release()
            } finally {
                tracks.release()
            }
        }
    }
}

private fun encodeAudio(wav: File, m4a: File) {
    WaveReader(wav).use { input ->
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var mux: MediaMuxer? = null
        var started = false
        try {
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, input.rate, 1)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val writer = MediaMuxer(m4a.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = writer
            var track = -1
            var sent = false
            var done = false
            var samples = 0L
            var block = ShortArray(1024)
            val info = MediaCodec.BufferInfo()
            while (!done) {
                if (!sent) {
                    val i = codec.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val buf =
                            requireNotNull(codec.getInputBuffer(i)).order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        if (buf.remaining() / 2 < block.size)
                            block = ShortArray(buf.remaining() / 2)
                        val n = input.read(block)
                        buf.asShortBuffer().put(block, 0, n)
                        codec.queueInputBuffer(
                            i,
                            0,
                            n * 2,
                            samples * 1_000_000L / input.rate,
                            if (n == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                        samples += n
                        sent = n == 0
                    }
                }
                val i = codec.dequeueOutputBuffer(info, 10000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = writer.addTrack(codec.outputFormat)
                    writer.start()
                    started = true
                }
                if (i < 0) continue
                if (
                    info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        info.presentationTimeUs >= 0L
                )
                    writer.writeSampleData(track, requireNotNull(codec.getOutputBuffer(i)), info)
                done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(i, false)
            }
            check(started)
            writer.stop()
        } finally {
            try {
                mux?.release()
            } finally {
                codec.release()
            }
        }
    }
}

// Remux camera frames, including their timestamps and display rotation, without video encoding.
internal fun exportVideo(context: Context, source: Uri, wav: File, offsetUs: Long, result: File) {
    val scratch = File.createTempFile("voice-track", ".m4a", context.cacheDir)
    val picture = MediaExtractor()
    val voice = MediaExtractor()
    var mux: MediaMuxer? = null
    try {
        encodeAudio(wav, scratch)
        picture.setDataSource(context, source, null)
        voice.setDataSource(scratch.path)
        val v =
            picture.track("video/")
                ?: error("The original video is unavailable. Import it again or save the WAV.")
        val a = voice.track("audio/") ?: error("The voice track could not be encoded.")
        val vf = picture.getTrackFormat(v)
        val af = voice.getTrackFormat(a)
        picture.selectTrack(v)
        voice.selectTrack(a)
        val writer = MediaMuxer(result.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mux = writer
        val vo = writer.addTrack(vf)
        val ao = writer.addTrack(af)
        writer.setOrientationHint(
            vf.integer(MediaFormat.KEY_ROTATION, 0).let { ((it % 360) + 360) % 360 }
        )
        writer.start()
        var data =
            ByteBuffer.allocateDirect(
                vf.integer(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
                    .coerceIn(65536, 32 * 1024 * 1024)
            )
        val info = MediaCodec.BufferInfo()
        while (picture.sampleTime >= 0 || voice.sampleTime >= 0) {
            val useVideo =
                picture.sampleTime >= 0 &&
                    (voice.sampleTime < 0 || picture.sampleTime <= voice.sampleTime + offsetUs)
            val src = if (useVideo) picture else voice
            if (Build.VERSION.SDK_INT >= 28 && src.sampleSize > data.capacity()) {
                require(src.sampleSize <= 32 * 1024 * 1024) {
                    "This video frame is too large to copy on this phone."
                }
                data = ByteBuffer.allocateDirect(src.sampleSize.toInt())
            }
            data.clear()
            val n = src.readSampleData(data, 0)
            check(n in 0..data.capacity()) { "A video frame could not be read." }
            val flags =
                if (src.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                else 0
            info.set(0, n, src.sampleTime + if (useVideo) 0L else offsetUs, flags)
            writer.writeSampleData(if (useVideo) vo else ao, data, info)
            src.advance()
        }
        writer.stop()
    } finally {
        try {
            mux?.release()
        } finally {
            picture.release()
            voice.release()
            scratch.delete()
        }
    }
}
