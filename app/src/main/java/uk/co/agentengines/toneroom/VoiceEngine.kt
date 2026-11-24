package uk.co.agentengines.toneroom

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.*

internal const val SCRIPT_LIMIT = 500

internal class VoiceEngine(private val assets: AssetManager) {
    fun render(
        reference: File,
        script: String,
        output: File,
        cancelled: () -> Boolean = { false },
        progress: (String) -> Unit = {},
    ) {
        val text = script.trim()
        require(text.length in 1..SCRIPT_LIMIT && text.any(Char::isLetterOrDigit)) {
            "Add a short script in English first."
        }
        val sample =
            WaveReader(reference).use { src ->
                require(src.count <= src.rate.toLong() * (SAMPLE_SECONDS + 1)) {
                    "Please record a fresh voice sample."
                }
                val pcm = ShortArray(src.count.toInt())
                val n = src.read(pcm)
                // Remove leading and trailing microphone silence, retaining a little room either
                // side.
                val edge =
                    (pcm.maxOfOrNull { abs(it.toInt()) } ?: 0)
                        .times(.024)
                        .toInt()
                        .coerceAtLeast(120)
                val first = pcm.indexOfFirst { abs(it.toInt()) > edge }
                val last = pcm.indexOfLast { abs(it.toInt()) > edge }
                require(first >= 0 && last - first >= src.rate * 2) {
                    "Please record your sample again and speak for at least four seconds."
                }
                val from = (first - src.rate / 12).coerceAtLeast(0)
                val end = (last + src.rate / 10).coerceAtMost(n)
                src.rate to FloatArray(end - from) { pcm[it + from] / 32768f }
            }
        val chunks = splitScript(text)
        progress("Opening your voice model…")
        val pocket =
            OfflineTtsPocketModelConfig(
                lmFlow = "pocket/lm_flow.int8.onnx",
                lmMain = "pocket/lm_main.int8.onnx",
                encoder = "pocket/encoder.onnx",
                decoder = "pocket/decoder.int8.onnx",
                textConditioner = "pocket/text_conditioner.onnx",
                vocabJson = "pocket/vocab.json",
                tokenScoresJson = "pocket/token_scores.json",
                voiceEmbeddingCacheCapacity = 1,
            )
        val tts =
            OfflineTts(
                assets,
                OfflineTtsConfig(model = OfflineTtsModelConfig(pocket = pocket, numThreads = 2)),
            )
        try {
            val config =
                GenerationConfig(
                    referenceAudio = sample.second,
                    referenceSampleRate = sample.first,
                    numSteps = 2,
                )
            WaveWriter(output, tts.sampleRate()).use { out ->
                chunks.forEachIndexed { index, part ->
                    if (cancelled()) throw CancellationException()
                    progress(
                        if (chunks.size == 1) "Reading your script…"
                        else "Reading passage ${index+1} of ${chunks.size}…"
                    )
                    // Complete each bounded passage in native code. Cancellation is checked
                    // at both ends, before any partial voiceover is committed.
                    val generated = tts.generateWithConfig(part, config)
                    if (cancelled()) throw CancellationException()
                    require(
                        generated.samples.size >= generated.sampleRate / 8 &&
                            generated.sampleRate == out.rate
                    ) {
                        "The voice model could not read that passage. Try a shorter sentence."
                    }
                    if (index > 0) {
                        val gap = ShortArray(out.rate / 7)
                        out.write(gap, gap.size)
                    }
                    var pos = 0
                    val buf = ShortArray(4096)
                    while (pos < generated.samples.size) {
                        val n = min(buf.size, generated.samples.size - pos)
                        for (i in 0 until n) buf[i] =
                            (generated.samples[pos + i].coerceIn(-1f, 1f) * 32767)
                                .roundToInt()
                                .toShort()
                        out.write(buf, n)
                        pos += n
                    }
                }
            }
        } finally {
            tts.release()
        }
    }

    private fun splitScript(text: String): List<String> {
        val result = mutableListOf<String>()
        var rest = text.replace(Regex("\\s+"), " ").trim()
        while (rest.length > 180) {
            val start = rest.take(180)
            val sentence = start.indexOfLast { it in ".!?;" }
            val end =
                if (sentence >= 45) sentence + 1
                else start.lastIndexOf(' ').takeIf { it > 0 } ?: 180
            result.add(rest.take(end).trim())
            rest = rest.drop(end).trimStart()
        }
        if (rest.isNotBlank()) result.add(rest)
        return result
    }
}
