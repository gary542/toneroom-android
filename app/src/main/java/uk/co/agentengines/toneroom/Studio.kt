package uk.co.agentengines.toneroom

import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal enum class Source(val label: String) {
    MIC("Recording"),
    FILE("Imported clip"),
    SCRIPT("Script voiceover"),
}

internal enum class Listen {
    ORIGINAL,
    STUDIO,
    SAMPLE,
}

internal enum class Session {
    TAKE,
    REFERENCE,
}

internal data class Take(
    val folder: File,
    val title: String,
    val source: Source,
    val created: Long,
    val duration: Double,
    val bars: List<Float>,
    val video: String?,
    val offsetUs: Long,
) {
    val original
        get() = File(folder, "original.wav")

    fun mix(tone: Finish) = File(folder, "${tone.name.lowercase()}.wav")

    companion object {
        fun read(dir: File): Take {
            val j = JSONObject(File(dir, "take.json").readText())
            val w = j.getJSONArray("wave")
            return Take(
                dir,
                j.getString("title"),
                Source.valueOf(j.getString("source")),
                j.getLong("created"),
                j.getDouble("duration"),
                List(w.length()) { w.getDouble(it).toFloat() },
                j.optString("video").takeIf(String::isNotEmpty),
                j.optLong("offset"),
            )
        }
    }
}

internal fun replaceFile(from: File, to: File) {
    Files.move(
        from.toPath(),
        to.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

internal fun durationLabel(seconds: Double): String =
    "%d:%02d".format(java.util.Locale.UK, seconds.toInt() / 60, seconds.toInt() % 60)

internal class Studio(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val prefs = context.getSharedPreferences("studio", 0)
    private val root = File(context.filesDir, "takes").apply { mkdirs() }
    private val reference = File(context.filesDir, "my-voice.wav")
    var takes by mutableStateOf<List<Take>>(emptyList())
        private set

    var take by mutableStateOf<Take?>(null)
        private set

    var finish by
        mutableStateOf(
            runCatching { Finish.valueOf(prefs.getString("finish", null) ?: "BALANCED") }
                .getOrDefault(Finish.BALANCED)
        )
        private set

    var script by mutableStateOf(prefs.getString("script", "") ?: "")
        private set

    var scriptMode by mutableStateOf(prefs.getBoolean("scriptMode", false))
        private set

    var hasVoice by mutableStateOf(reference.exists())
        private set

    var busy by mutableStateOf<String?>(null)
        private set

    var session by mutableStateOf<Session?>(null)
        private set

    var recSeconds by mutableFloatStateOf(0f)
        private set

    var meter by mutableStateOf(List(48) { 0f })
        private set

    var playerMode by mutableStateOf<Listen?>(null)
        private set

    var playSeconds by mutableFloatStateOf(0f)
        private set

    var canCancel by mutableStateOf(false)
        private set

    var mixReady by mutableStateOf(false)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    val idle
        get() = session == null && busy == null

    @Volatile private var cancelRequested = false
    private var capture: Microphone? = null
    private var player: MediaPlayer? = null
    private var meterJob: Job? = null

    init {
        takes =
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .mapNotNull { runCatching { Take.read(it) }.getOrNull() }
                .sortedByDescending { it.created }
        take =
            takes.firstOrNull { it.folder.name == prefs.getString("current", null) }
                ?: takes.firstOrNull()
        mixReady = take?.mix(finish)?.isFile == true
    }

    fun mode(value: Boolean) {
        if (idle) {
            stopPlayback()
            scriptMode = value
            prefs.edit().putBoolean("scriptMode", value).apply()
            notice = null
        }
    }

    fun draft(value: String) {
        if (value.length <= SCRIPT_LIMIT) {
            script = value
            prefs.edit().putString("script", value).apply()
        }
    }

    fun inform(message: String?) {
        notice = message
    }

    private fun select(t: Take?) {
        stopPlayback()
        take = t
        mixReady = t?.mix(finish)?.isFile == true
        prefs.edit().putString("current", t?.folder?.name).apply()
    }

    fun open(t: Take) {
        if (idle) {
            select(t)
            ensureMix()
        }
    }

    fun chooseFinish(f: Finish) {
        if (idle) {
            stopPlayback()
            finish = f
            prefs.edit().putString("finish", f.name).apply()
            ensureMix()
        }
    }

    fun deleteTake(t: Take) {
        if (!idle) return
        val hidden = File(root, ".delete-${t.folder.name}")
        if (!t.folder.renameTo(hidden)) {
            notice = "This take could not be deleted."
            return
        }
        takes = takes.filter { it.folder != t.folder }
        if (take?.folder == t.folder) select(takes.firstOrNull())
        val releaseVideo = t.video?.takeIf { uri -> takes.none { it.video == uri } }
        viewModelScope.launch(Dispatchers.IO) {
            hidden.deleteRecursively()
            releaseVideo?.let {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(it),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
        ensureMix()
    }

    fun forgetVoice() {
        if (idle) {
            stopPlayback()
            if (reference.delete() || !reference.exists()) {
                hasVoice = false
                notice = "Voice sample removed."
            } else notice = "Your voice sample could not be removed."
        }
    }

    private fun message(e: Throwable) =
        when (e) {
            is SecurityException ->
                "Access was interrupted. Choose the file again or allow microphone access in Settings."
            is java.io.IOException ->
                "Could not read or save the file. Check available space and try again."
            is UnsatisfiedLinkError -> "This phone could not load the speech engine."
            is OutOfMemoryError ->
                "The phone ran low on memory. Close other apps and try a shorter clip."
            else ->
                e.message?.takeIf { it.length in 1..155 }
                    ?: "That take could not be completed. Try again with a shorter clip."
        }

    private fun task(label: String, work: suspend () -> Unit) {
        if (!idle) return
        stopPlayback()
        busy = label
        notice = null
        viewModelScope.launch {
            try {
                work()
            } catch (e: java.util.concurrent.CancellationException) {
                if (isActive) notice = "Generation cancelled."
            } catch (e: Exception) {
                notice = message(e)
            } catch (e: LinkageError) {
                notice = message(e)
            } catch (e: OutOfMemoryError) {
                notice = message(e)
            } finally {
                busy = null
                canCancel = false
            }
        }
    }

    private fun tempWav() = File.createTempFile("take-", ".wav", context.cacheDir)

    private suspend fun storeTake(
        file: File,
        title: String,
        source: Source,
        video: Uri? = null,
        offset: Long = 0,
    ) {
        val new =
            withContext(Dispatchers.IO) {
                val level = inspectWave(file)
                require(level.seconds >= .25) {
                    "That recording was very short. Try recording a full line."
                }
                val id = UUID.randomUUID().toString()
                val staging = File(root, ".new-$id")
                val final = File(root, id)
                check(staging.mkdir())
                try {
                    replaceFile(file, File(staging, "original.wav"))
                    val j =
                        JSONObject()
                            .put("title", title)
                            .put("source", source.name)
                            .put("created", System.currentTimeMillis())
                            .put("duration", level.seconds)
                            .put("wave", JSONArray(level.bars))
                            .put("video", video?.toString() ?: "")
                            .put("offset", offset)
                    File(staging, "take.json").writeText(j.toString())
                    replaceFile(staging, final)
                    Take.read(final)
                } finally {
                    staging.deleteRecursively()
                }
            }
        takes = listOf(new) + takes
        select(new)
        renderMix(new, finish)
    }

    private suspend fun renderMix(t: Take, f: Finish) {
        val file = t.mix(f)
        mixReady = file.isFile
        if (!file.isFile) {
            busy = "Finishing your take…"
            withContext(Dispatchers.Default) {
                val tmp = File.createTempFile("tone-", ".part", t.folder)
                try {
                    polish(t.original, tmp, f)
                    replaceFile(tmp, file)
                } finally {
                    tmp.delete()
                }
            }
        }
        mixReady = take?.folder == t.folder && finish == f && file.isFile
    }

    fun ensureMix() {
        val t = take ?: return
        mixReady = t.mix(finish).isFile
        if (!mixReady && idle) task("Finishing your take…") { renderMix(t, finish) }
    }

    fun record(which: Session) {
        if (!idle) return
        stopPlayback()
        notice = null
        val mic = Microphone()
        capture = mic
        session = which
        recSeconds = 0f
        meter = List(48) { 0f }
        meterJob =
            viewModelScope.launch {
                while (isActive) {
                    recSeconds = mic.seconds
                    meter = meter.drop(1) + mic.peak
                    delay(90)
                }
            }
        viewModelScope.launch {
            var tmp: File? = null
            try {
                val file = tempWav()
                tmp = file
                withContext(Dispatchers.IO) {
                    mic.record(
                        file,
                        if (which == Session.REFERENCE) SAMPLE_SECONDS else MAX_SECONDS,
                    )
                }
                session = null
                busy = "Saving your recording…"
                if (which == Session.REFERENCE) {
                    keepReference(file)
                } else storeTake(file, "Mic take", Source.MIC)
            } catch (e: Exception) {
                notice = message(e)
            } finally {
                tmp?.delete()
                meterJob?.cancel()
                capture = null
                session = null
                busy = null
                recSeconds = 0f
            }
        }
    }

    private suspend fun keepReference(file: File) {
        withContext(Dispatchers.IO) {
            val levels = inspectWave(file)
            require(levels.seconds in 4.0..12.12 && levels.activeSeconds >= 2.0) {
                "Use 4–12 seconds of your own clear speech. Try again with a quiet background."
            }
            replaceFile(file, reference)
        }
        hasVoice = true
        notice = "Your voice sample is ready."
    }

    fun importReference(uri: Uri) {
        task("Opening your voice sample…") {
            val file = tempWav()
            try {
                withContext(Dispatchers.IO) { importMedia(context, uri, file) }
                keepReference(file)
            } finally {
                file.delete()
            }
        }
    }

    fun stopRecording() {
        capture?.stopped = true
    }

    fun import(uri: Uri) {
        task("Opening your clip…") {
            val file = tempWav()
            try {
                val result = withContext(Dispatchers.IO) { importMedia(context, uri, file) }
                val name =
                    runCatching {
                            context.contentResolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        }
                        .getOrNull()
                        ?.substringBeforeLast('.')
                        ?.take(70)
                        ?.ifBlank { null } ?: "Imported clip"
                if (result.video)
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                storeTake(file, name, Source.FILE, uri.takeIf { result.video }, result.offsetUs)
            } finally {
                file.delete()
            }
        }
    }

    fun generate() {
        if (!hasVoice || script.isBlank()) return
        val text = script.trim()
        cancelRequested = false
        task("Opening your voice model…") {
            canCancel = true
            val file = tempWav()
            try {
                withContext(Dispatchers.Default) {
                    VoiceEngine(context.assets).render(
                        reference,
                        text,
                        file,
                        { cancelRequested },
                    ) { status ->
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            if (!cancelRequested && canCancel) busy = status
                        }
                    }
                }
                if (cancelRequested) throw java.util.concurrent.CancellationException()
                canCancel = false
                storeTake(
                    file,
                    text.take(43).trimEnd() + if (text.length > 43) "…" else "",
                    Source.SCRIPT,
                )
            } finally {
                file.delete()
            }
        }
    }

    fun cancelGeneration() {
        cancelRequested = true
        if (canCancel) busy = "Stopping after this passage…"
    }

    fun listen(which: Listen) {
        if (!idle) return
        val t = take
        val file =
            when (which) {
                Listen.ORIGINAL -> t?.original
                Listen.STUDIO -> t?.mix(finish)
                Listen.SAMPLE -> reference
            } ?: return
        val old = playerMode
        val seek =
            if (old != null && which != Listen.SAMPLE && old != Listen.SAMPLE)
                runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            else 0
        stopPlayback()
        if (old == which) return
        runCatching {
                check(file.isFile) { "This take is unavailable." }
                val p = MediaPlayer()
                player = p
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                p.setDataSource(file.path)
                p.setOnCompletionListener { if (player === it) stopPlayback() }
                p.setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    notice = "Playback stopped. Try opening the take again."
                    true
                }
                p.prepare()
                if (seek > 0 && seek < p.duration) p.seekTo(seek.toLong(), MediaPlayer.SEEK_CLOSEST)
                p.start()
                playerMode = which
                notice = null
            }
            .onFailure {
                stopPlayback()
                notice = message(it)
            }
    }

    fun tick() {
        playSeconds = runCatching { (player?.currentPosition ?: 0) / 1000f }.getOrDefault(0f)
    }

    fun stopPlayback() {
        player?.release()
        player = null
        playerMode = null
        playSeconds = 0f
    }

    fun save(uri: Uri, video: Boolean) {
        val t = take ?: return
        val wav = t.mix(finish)
        if (!wav.isFile) return
        task(if (video) "Saving your video…" else "Saving your voiceover…") {
            withContext(Dispatchers.IO) {
                var temp: File? = null
                try {
                    val source =
                        if (video) {
                            val original = t.video ?: error("The original video is unavailable.")
                            val mp4 = File.createTempFile("finished-", ".mp4", context.cacheDir)
                            temp = mp4
                            exportVideo(context, Uri.parse(original), wav, t.offsetUs, mp4)
                            mp4
                        } else wav
                    val out =
                        context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("The selected folder could not be opened.")
                    out.use { dest -> source.inputStream().use { it.copyTo(dest, 65536) } }
                } finally {
                    temp?.delete()
                }
            }
            notice =
                if (video) "Video saved with your finished voice track."
                else "WAV saved. It’s ready for your video editor."
        }
    }

    fun background() {
        stopPlayback()
        stopRecording()
    }

    override fun onCleared() {
        background()
        cancelRequested = true
        super.onCleared()
    }
}
