package uk.co.agentengines.toneroom

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

internal const val MAX_SECONDS = 300
internal const val SAMPLE_SECONDS = 12

internal enum class Finish(val label: String, val detail: String) {
    BALANCED("Balanced", "Steady volume and a little definition."),
    WARM("Warm", "Fuller low notes and softer edges."),
    CLEAR("Clear", "A brighter sound that brings words forward."),
}

internal class WaveWriter(file: File, val rate: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw")
    private var bytes = ByteArray(16384)
    var count = 0L
        private set

    init {
        require(rate in 8000..192000)
        out.setLength(0)
        out.write(ByteArray(44))
    }

    fun write(data: ShortArray, size: Int) {
        if (bytes.size < size * 2) bytes = ByteArray(size * 2)
        for (i in 0 until size) {
            val s = data[i].toInt()
            bytes[i * 2] = s.toByte()
            bytes[i * 2 + 1] = (s shr 8).toByte()
        }
        out.write(bytes, 0, size * 2)
        count += size
    }

    override fun close() {
        try {
            val n = (count * 2).toInt()
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + n).put("WAVEfmt ".toByteArray()).putInt(16)
            h.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(n)
            out.seek(0)
            out.write(h.array())
        } finally {
            out.close()
        }
    }
}

// All incoming media is decoded once into this app's mono PCM16 WAV format.
internal class WaveReader(file: File) : Closeable {
    private val stream = RandomAccessFile(file, "r")
    val rate: Int
    val count: Long
    private var bytes = ByteArray(16384)

    init {
        try {
            val header = ByteArray(44)
            stream.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(
                String(header, 0, 4) == "RIFF" &&
                    String(header, 36, 4) == "data" &&
                    h.getShort(22).toInt() == 1 &&
                    h.getShort(34).toInt() == 16
            ) {
                "This take could not be opened."
            }
            rate = h.getInt(24)
            count = h.getInt(40).toLong() / 2
            require(rate in 8000..192000 && count >= 0 && count * 2 + 44 <= stream.length()) {
                "This take is incomplete."
            }
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
    }

    fun read(to: ShortArray): Int {
        if (bytes.size < to.size * 2) bytes = ByteArray(to.size * 2)
        val n = min(to.size.toLong() * 2, 44 + count * 2 - stream.filePointer).toInt()
        if (n <= 0) return 0
        stream.readFully(bytes, 0, n)
        for (i in 0 until n / 2) to[i] =
            ((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        return n / 2
    }

    override fun close() = stream.close()
}

internal data class Levels(
    val seconds: Double,
    val activeSeconds: Double,
    val rms: Double,
    val bars: List<Float>,
)

internal fun inspectWave(file: File): Levels =
    WaveReader(file).use { src ->
        val frame = ShortArray(max(1, src.rate / 50))
        val bars = FloatArray(48)
        var pos = 0L
        var active = 0L
        var squares = 0.0
        while (true) {
            val n = src.read(frame)
            if (n == 0) break
            var energy = 0.0
            var peak = 0
            for (i in 0 until n) {
                val s = frame[i].toInt()
                peak = max(peak, abs(s))
                energy += s.toDouble() * s
            }
            val b = (pos * bars.size / max(1, src.count)).toInt().coerceIn(bars.indices)
            bars[b] = max(bars[b], (peak / 32768f).pow(0.5f))
            if (sqrt(energy / n) > 190.0) {
                squares += energy
                active += n
            }
            pos += n
        }
        Levels(
            src.count.toDouble() / src.rate,
            active.toDouble() / src.rate,
            sqrt(squares / max(1L, active)) / 32768.0,
            bars.toList(),
        )
    }

internal class Microphone {
    @Volatile var stopped = false
    @Volatile var seconds = 0f
    @Volatile var peak = 0f

    @SuppressLint("MissingPermission")
    fun record(file: File, limit: Int) {
        val rate =
            listOf(48000, 44100, 24000, 16000).firstOrNull {
                AudioRecord.getMinBufferSize(
                    it,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ) > 0
            } ?: error("No compatible microphone input was found.")
        val min =
            AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val input =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(min * 2, rate / 3),
            )
        try {
            check(input.state == AudioRecord.STATE_INITIALIZED) {
                "The microphone is unavailable. Close any app using it and try again."
            }
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Your phone could not start the microphone."
            }
            WaveWriter(file, rate).use { out ->
                val buf = ShortArray(rate / 25)
                val end = limit.toLong() * rate
                while (!stopped && out.count < end) {
                    val n = input.read(buf, 0, min(buf.size.toLong(), end - out.count).toInt())
                    check(n >= 0) { "Microphone input was interrupted." }
                    if (n == 0) continue
                    var p = 0
                    for (i in 0 until n) p = max(p, abs(buf[i].toInt()))
                    peak = (p / 32768f).pow(.5f)
                    out.write(buf, n)
                    seconds = out.count.toFloat() / rate
                }
            }
        } finally {
            try {
                if (input.recordingState == AudioRecord.RECORDSTATE_RECORDING) input.stop()
            } finally {
                input.release()
            }
        }
    }
}

private class Filter(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var s1 = 0.0
    private var s2 = 0.0

    fun run(x: Double): Double {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    companion object {
        fun peak(rate: Int, hz: Double, gain: Double, q: Double): Filter {
            val w = 2 * PI * min(hz, rate * .40) / rate
            val a = 10.0.pow(gain / 40.0)
            val al = sin(w) / (2 * q)
            val d = 1 + al / a
            return Filter(
                (1 + al * a) / d,
                -2 * cos(w) / d,
                (1 - al * a) / d,
                -2 * cos(w) / d,
                (1 - al / a) / d,
            )
        }

        fun highPass(rate: Int): Filter {
            val w = 2 * PI * 70 / rate
            val c = cos(w)
            val al = sin(w) / (2 * .7071)
            val d = 1 + al
            return Filter(
                (1 + c) / (2 * d),
                -(1 + c) / d,
                (1 + c) / (2 * d),
                -2 * c / d,
                (1 - al) / d,
            )
        }
    }
}

internal fun polish(source: File, target: File, finish: Finish) {
    val stats = inspectWave(source)
    // Gentle levelling, peak control and broad EQ preserve speech timing exactly.
    val gain =
        if (stats.activeSeconds < .16) 1.0
        else (.132 / stats.rms.coerceAtLeast(.0001)).coerceIn(.45, 3.0)
    WaveReader(source).use { input ->
        WaveWriter(target, input.rate).use { out ->
            val highPass = Filter.highPass(input.rate)
            val body =
                Filter.peak(
                    input.rate,
                    195.0,
                    when (finish) {
                        Finish.WARM -> 2.7
                        Finish.CLEAR -> -2.0
                        else -> .4
                    },
                    .8,
                )
            val mud = Filter.peak(input.rate, 410.0, -1.5, .9)
            val presence =
                Filter.peak(
                    input.rate,
                    2950.0,
                    when (finish) {
                        Finish.WARM -> .2
                        Finish.CLEAR -> 3.2
                        else -> 1.6
                    },
                    .85,
                )
            var envelope = 0.0
            var comp = 1.0
            val attack = exp(-1.0 / (input.rate * .006))
            val release = exp(-1.0 / (input.rate * .14))
            val threshold = .24
            val buf = ShortArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n == 0) break
                for (i in 0 until n) {
                    val x = presence.run(mud.run(body.run(highPass.run(buf[i] / 32768.0)))) * gain
                    val e = abs(x)
                    val coeff = if (e > envelope) attack else release
                    envelope = e + coeff * (envelope - e)
                    val desired = if (envelope > threshold) (threshold / envelope).pow(.48) else 1.0
                    comp += (desired - comp) * (if (desired < comp) 1 - attack else 1 - release)
                    val y = x * comp * 1.20
                    val m = abs(y)
                    val limited = if (m <= .8) y else sign(y) * (.8 + .16 * tanh((m - .8) / .16))
                    buf[i] = (limited * 32767).roundToInt().coerceIn(-32767, 32767).toShort()
                }
                out.write(buf, n)
            }
        }
    }
}
