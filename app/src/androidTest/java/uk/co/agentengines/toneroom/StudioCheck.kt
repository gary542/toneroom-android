package uk.co.agentengines.toneroom

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioCheck {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun fixture(name: String): File {
        val file = File(context.cacheDir, "test-$name")
        instrumentation.context.assets.open(name).use { src ->
            file.outputStream().use { src.copyTo(it) }
        }
        return file
    }

    @Test
    fun tonePresetsKeepSpeechTimingAndPeakHeadroom() {
        val original = fixture("reference.wav")
        val bytes = original.readBytes()
        val signatures = mutableSetOf<Int>()
        for (tone in Finish.entries) {
            val out = File(context.cacheDir, "${tone.name}.wav")
            polish(original, out, tone)
            assertEquals(bytes.size.toLong(), out.length())
            assertTrue(signatures.add(out.readBytes().contentHashCode()))
            val result = inspectWave(out)
            assertTrue(result.activeSeconds > 2)
            assertTrue(result.rms > .03)
            assertTrue(result.bars.max() <= .99f)
        }
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test
    fun scriptUsesBundledModelAndRecordedVoice() {
        val ref = fixture("reference.wav")
        val out = File(context.cacheDir, "instrumented-script.wav")
        VoiceEngine(context.assets).render(ref, "Welcome to the studio.", out)
        val result = inspectWave(out)
        assertTrue("Speech lasted ${result.seconds}", result.seconds in .55..12.0)
        assertTrue(result.activeSeconds > .35)
        assertTrue(result.rms > .015)
        context.getExternalFilesDir(null)?.let { out.copyTo(File(it, "test-script.wav"), true) }
    }

    @Test
    fun silentReferenceIsRejected() {
        val ref = File(context.cacheDir, "quiet.wav")
        WaveWriter(ref, 24000).use { it.write(ShortArray(120000), 120000) }
        val failed =
            runCatching {
                    VoiceEngine(context.assets)
                        .render(ref, "Read these words.", File(context.cacheDir, "unused.wav"))
                }
                .exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
    }

    private data class Video(
        val frames: List<Pair<Long, String>>,
        val rotation: Int,
        val audioDuration: Long,
    )

    private fun tracks(file: File): Video {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val v =
                (0 until ex.trackCount).first {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
                }
            val a =
                (0 until ex.trackCount).first {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
                }
            val f = ex.getTrackFormat(v)
            val rotation =
                if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION)
                else 0
            val audioDuration = ex.getTrackFormat(a).getLong(MediaFormat.KEY_DURATION)
            ex.selectTrack(v)
            val photos = mutableListOf<Pair<Long, String>>()
            val buf = ByteBuffer.allocate(200000)
            while (ex.sampleTime >= 0) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                buf.position(0)
                buf.limit(n)
                val md = MessageDigest.getInstance("SHA-256")
                md.update(buf)
                photos.add(ex.sampleTime to md.digest().joinToString("") { "%02x".format(it) })
                ex.advance()
            }
            return Video(photos, rotation, audioDuration)
        } finally {
            ex.release()
        }
    }

    @Test
    fun importedVideoKeepsPictureAndSyncWithNewAudio() {
        val source = fixture("clip.mp4")
        val wav = File(context.cacheDir, "source.wav")
        val mix = File(context.cacheDir, "mix.wav")
        val result = File(context.cacheDir, "result.mp4")
        val info = importMedia(context, Uri.fromFile(source), wav)
        assertTrue(info.video)
        assertTrue("Audio starts at ${info.offsetUs} us", info.offsetUs in 0L..60000L)
        assertTrue(inspectWave(wav).seconds in 2.85..3.15)
        polish(wav, mix, Finish.CLEAR)
        exportVideo(context, Uri.fromFile(source), mix, info.offsetUs, result)
        val before = tracks(source)
        val after = tracks(result)
        assertTrue(after.frames.size > 35)
        assertEquals(before.frames, after.frames)
        assertTrue(
            "The fixture must carry a real display rotation",
            before.rotation in setOf(90, 270),
        )
        assertEquals(before.rotation, after.rotation)
        assertTrue(abs(before.audioDuration - after.audioDuration) < 90000)
        val rendered = File(context.cacheDir, "result-audio.wav")
        importMedia(context, Uri.fromFile(result), rendered)
        assertTrue(inspectWave(rendered).rms > .03)
    }
}
