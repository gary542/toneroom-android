package uk.co.agentengines.toneroom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModelProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.delay

private val Paper = Color(0xfff8f6ef)
private val Ink = Color(0xff172e27)
private val Pine = Color(0xff193d35)
private val Moss = Color(0xff53695f)
private val Line = Color(0xffd5dcd0)
private val Lime = Color(0xffe3f6ab)
private val Pale = Color(0xffe9eee2)
private val Fog = Color(0xffbfd0c2)
private val Coral = Color(0xffffb195)
private val Serif = FontFamily(Font(R.font.dm_serif))
private val Sans = FontFamily(Font(R.font.dm_sans))
private val scheme =
    lightColorScheme(
        primary = Pine,
        onPrimary = Color.White,
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        outline = Line,
        secondary = Moss,
    )

class MainActivity : ComponentActivity() {
    private lateinit var studio: Studio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        studio = ViewModelProvider(this)[Studio::class.java]
        setContent { MaterialTheme(colorScheme = scheme) { StudioScreen(studio, this) } }
        studio.ensureMix()
    }

    override fun onStop() {
        if (!isChangingConfigurations) studio.background()
        super.onStop()
    }
}

@Composable
private fun Label(
    s: String,
    size: Int = 15,
    color: Color = Ink,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    Text(
        s,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        lineHeight = (size * 1.35).sp,
        fontFamily = Sans,
        fontWeight = weight,
    )
}

@Composable
private fun Heading(s: String, size: Int = 27, color: Color = Ink) {
    Text(
        s,
        color = color,
        fontFamily = Serif,
        fontSize = size.sp,
        lineHeight = (size * 1.15).sp,
        letterSpacing = (-.5).sp,
    )
}

@Composable
private fun PillButton(
    s: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fill: Color = Pine,
    ink: Color = Color.White,
    outline: Boolean = false,
    mark: String = "",
) {
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier
            .heightIn(min = 49.dp)
            .clip(shape)
            .background(if (enabled) fill else if (fill == Lime || fill == Coral) Fog else Pale)
            .then(if (outline) Modifier.border(1.dp, Line, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mark.isNotEmpty()) Mark(mark, if (enabled) ink else Moss)
            Label(s, 15, if (enabled) ink else Moss, FontWeight.Bold)
        }
    }
}

@Composable
private fun Mark(kind: String, color: Color) {
    Canvas(Modifier.size(17.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * .095f
        fun line(x: Float, y: Float, a: Float, b: Float) {
            drawLine(color, Offset(w * x, h * y), Offset(w * a, h * b), stroke, StrokeCap.Round)
        }
        when (kind) {
            "record" -> drawCircle(color, w * .32f)
            "stop" ->
                drawRoundRect(
                    color,
                    Offset(w * .18f, h * .18f),
                    androidx.compose.ui.geometry.Size(w * .64f, h * .64f),
                    androidx.compose.ui.geometry.CornerRadius(w * .1f),
                )
            "play" -> {
                val p =
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(.25f * w, .14f * h)
                        lineTo(.84f * w, .5f * h)
                        lineTo(.25f * w, .86f * h)
                        close()
                    }
                drawPath(p, color)
            }
            "import" -> {
                line(.5f, .13f, .5f, .65f)
                line(.28f, .45f, .5f, .67f)
                line(.5f, .67f, .72f, .45f)
                line(.14f, .72f, .14f, .9f)
                line(.14f, .9f, .86f, .9f)
                line(.86f, .9f, .86f, .72f)
            }
            "takes" -> {
                line(.17f, .25f, .82f, .25f)
                line(.17f, .5f, .82f, .5f)
                line(.17f, .75f, .62f, .75f)
            }
            else ->
                listOf(.23f, .48f, .8f, .51f, .30f).forEachIndexed { i, v ->
                    val x = .12f + i * .19f
                    line(x, .5f - v / 2, x, .5f + v / 2)
                }
        }
    }
}

@Composable
private fun Wave(bars: List<Float>, color: Color, modifier: Modifier, progress: Float = 1f) {
    Canvas(modifier) {
        val dx = size.width / bars.size
        val half = size.height * .48f
        bars.forEachIndexed { i, p ->
            val v = max(1.6.dp.toPx(), half * p.coerceIn(0f, 1f))
            val x = (i + .5f) * dx
            drawLine(
                if (i.toFloat() / bars.size <= progress) color else color.copy(alpha = .32f),
                Offset(x, half - v),
                Offset(x, half + v),
                min(dx * .52f, 3.3.dp.toPx()),
                StrokeCap.Round,
            )
        }
    }
}

private fun dateText(ts: Long) = SimpleDateFormat("d MMM · HH:mm", Locale.UK).format(Date(ts))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioScreen(vm: Studio, activity: MainActivity) {
    var pending by rememberSaveable { mutableStateOf(Session.TAKE.name) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var library by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var removeVoice by rememberSaveable { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) {
                settings = false
                vm.record(Session.valueOf(pending))
            } else {
                settings =
                    !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                vm.inform(
                    "Microphone access is needed to record your voice. You can still import a clip."
                )
            }
        }
    val record: (Session) -> Unit = { type ->
        if (
            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            settings = false
            vm.record(type)
        } else {
            pending = type.name
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::import)
        }
    val pickReference =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::importReference)
        }
    val saveSound =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
            it?.let { u -> vm.save(u, false) }
        }
    val saveVideo =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) {
            it?.let { u -> vm.save(u, true) }
        }
    val scroll = rememberScrollState()
    val take = vm.take
    val reviewPosition = remember { BringIntoViewRequester() }
    var lastTake by rememberSaveable { mutableStateOf(take?.folder?.name) }
    LaunchedEffect(take?.folder?.name, vm.busy) {
        val id = take?.folder?.name
        if (id != null && id != lastTake && vm.busy == null) {
            lastTake = id
            delay(100)
            reviewPosition.bringIntoView()
        }
    }
    LaunchedEffect(vm.playerMode) {
        while (vm.playerMode != null) {
            vm.tick()
            delay(130)
        }
    }
    Box(
        Modifier.fillMaxSize().background(Paper).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 620.dp)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Pine),
                        contentAlignment = Alignment.Center,
                    ) {
                        Mark("wave", Lime)
                    }
                    Heading("ToneRoom", 30)
                }
                PillButton(
                    "Takes",
                    { library = true },
                    enabled = vm.idle,
                    fill = Paper,
                    ink = Pine,
                    outline = true,
                    mark = "takes",
                )
            }
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Pale)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("Record" to false, "From script" to true).forEach { (name, scriptMode) ->
                    val selected = scriptMode == vm.scriptMode
                    Box(
                        Modifier.weight(1f)
                            .heightIn(min = 43.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Pine else Color.Transparent)
                            .semantics { this.selected = selected }
                            .clickable(enabled = vm.idle, role = Role.Tab) { vm.mode(scriptMode) }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(name, 15, if (selected) Color.White else Moss, FontWeight.Bold)
                    }
                }
            }
            if (!vm.scriptMode) {
                RecorderPanel(vm) {
                    if (vm.session == Session.TAKE) vm.stopRecording() else record(Session.TAKE)
                }
                PillButton(
                    "Import audio or video",
                    { pick.launch(arrayOf("audio/*", "video/*")) },
                    Modifier.fillMaxWidth(),
                    vm.idle,
                    Paper,
                    Pine,
                    true,
                    "import",
                )
            } else {
                VoicePanel(
                    vm,
                    {
                        if (vm.session == Session.REFERENCE) vm.stopRecording()
                        else record(Session.REFERENCE)
                    },
                    { pickReference.launch(arrayOf("audio/*")) },
                    { removeVoice = true },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Heading("Your script", 25)
                        Label("${vm.script.length} / $SCRIPT_LIMIT", 12, Moss)
                    }
                    OutlinedTextField(
                        value = vm.script,
                        onValueChange = vm::draft,
                        modifier =
                            Modifier.fillMaxWidth().semantics {
                                contentDescription = "English voiceover script"
                            },
                        enabled = vm.idle,
                        minLines = 3,
                        maxLines = 7,
                        placeholder = { Label("Write what you’d like to say…", 15, Moss) },
                        shape = RoundedCornerShape(14.dp),
                        textStyle =
                            androidx.compose.ui.text.TextStyle(
                                fontFamily = Sans,
                                fontSize = 16.sp,
                                color = Ink,
                            ),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Pine,
                                unfocusedBorderColor = Line,
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                            ),
                    )
                    PillButton(
                        "Generate voiceover",
                        vm::generate,
                        Modifier.fillMaxWidth(),
                        vm.idle && vm.hasVoice && vm.script.any(Char::isLetterOrDigit),
                        mark = "wave",
                    )
                }
            }
            if (vm.busy != null) {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(Pale)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Label(
                        vm.busy ?: "",
                        14,
                        Pine,
                        FontWeight.Bold,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Pine,
                        trackColor = Line,
                    )
                    if (vm.canCancel)
                        TextButton(onClick = vm::cancelGeneration) {
                            Label("Cancel generation", 14, Pine)
                        }
                }
            }
            vm.notice?.let { msg ->
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(Pale)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(
                            msg,
                            14,
                            Ink,
                            modifier =
                                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        TextButton(onClick = { vm.inform(null) }) { Label("OK", 14, Pine) }
                    }
                    if (
                        settings &&
                            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                                PackageManager.PERMISSION_GRANTED
                    )
                        TextButton(
                            onClick = {
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${activity.packageName}"),
                                    )
                                )
                            }
                        ) {
                            Label("Microphone settings", 14, Pine)
                        }
                }
            }
            FinishPanel(vm)
            if (take != null) {
                val base =
                    "ToneRoom-${SimpleDateFormat("yyyyMMdd-HHmmss",Locale.UK).format(Date(take.created))}-${vm.finish.label}"
                Box(Modifier.bringIntoViewRequester(reviewPosition)) {
                    ReviewPanel(
                        vm,
                        take,
                        { saveSound.launch("$base.wav") },
                        { saveVideo.launch("$base.mp4") },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Label("Audio stays on this device", 12, Moss)
                TextButton(onClick = { about = true }) { Label("About ToneRoom", 13, Pine) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (library) TakesDialog(vm) { library = false }
    if (about) Credits { about = false }
    if (removeVoice)
        AlertDialog(
            onDismissRequest = { removeVoice = false },
            title = { Heading("Remove voice sample?", 24) },
            text = {
                Label(
                    "Your saved takes will still be available. Record a new sample to generate another script."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.forgetVoice()
                        removeVoice = false
                    }
                ) {
                    Label("Remove sample", 14, Pine)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeVoice = false }) { Label("Keep", 14, Moss) }
            },
        )
}

@Composable
private fun RecorderPanel(vm: Studio, onRecord: () -> Unit) {
    val live = vm.session == Session.TAKE
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Pine).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Heading("Recording desk", 29, Color.White)
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (live) Coral else Lime))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label(if (live) "Recording" else "Microphone ready", 13, Fog)
            Label(durationLabel(vm.recSeconds.toDouble()), 15, Lime, FontWeight.Bold)
        }
        Wave(
            if (live) vm.meter else List(48) { 0f },
            if (live) Coral else Fog,
            Modifier.fillMaxWidth().height(42.dp),
        )
        PillButton(
            if (live) "Stop recording" else "Record a take",
            onRecord,
            Modifier.fillMaxWidth(),
            live || vm.idle,
            if (live) Coral else Lime,
            Pine,
            mark = if (live) "stop" else "record",
        )
        Label("Record up to 5 minutes per take.", 12, Fog)
    }
}

@Composable
private fun VoicePanel(vm: Studio, record: () -> Unit, pick: () -> Unit, remove: () -> Unit) {
    val live = vm.session == Session.REFERENCE
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pine).padding(19.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Heading("Your voice sample", 26, Color.White)
            Mark("wave", Lime)
        }
        if (!vm.hasVoice || live) {
            Label("Speak naturally for 6–12 seconds. Try this line in your own voice.", 14, Fog)
            Label(
                "“I’m getting ready to record a short video. Here’s one thing I’d like to share with you today.”",
                16,
                Color.White,
            )
        } else {
            Label("This sample gives new scripts the sound of your voice.", 14, Fog)
        }
        if (live) {
            Wave(vm.meter, Coral, Modifier.fillMaxWidth().height(28.dp))
            Label("${durationLabel(vm.recSeconds.toDouble())} / 0:12", 13, Fog)
        }
        PillButton(
            if (live) "Stop sample"
            else if (vm.hasVoice) "Record a new sample" else "Record my voice",
            record,
            Modifier.fillMaxWidth(),
            live || vm.idle,
            if (live) Coral else Lime,
            Pine,
            mark = if (live) "stop" else "record",
        )
        if (!live)
            TextButton(enabled = vm.idle, onClick = pick) {
                Label("Use a 4–12s audio sample", 14, Lime)
            }
        if (vm.hasVoice && !live) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = vm.idle, onClick = { vm.listen(Listen.SAMPLE) }) {
                    Label(
                        if (vm.playerMode == Listen.SAMPLE) "Stop sample playback"
                        else "Hear sample",
                        14,
                        Lime,
                    )
                }
                TextButton(enabled = vm.idle, onClick = remove) { Label("Remove", 14, Fog) }
            }
        }
    }
}

@Composable
private fun FinishPanel(vm: Studio) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Heading("Voice finish", 25)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Finish.entries.forEach { f ->
                val selected = vm.finish == f
                val shape = RoundedCornerShape(13.dp)
                Box(
                    Modifier.weight(1f)
                        .heightIn(min = 45.dp)
                        .clip(shape)
                        .background(if (selected) Pale else Paper)
                        .border(if (selected) 1.5.dp else 1.dp, if (selected) Pine else Line, shape)
                        .semantics { this.selected = selected }
                        .clickable(enabled = vm.idle, role = Role.RadioButton) {
                            vm.chooseFinish(f)
                        }
                        .padding(7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(
                        f.label,
                        14,
                        if (selected) Pine else Moss,
                        if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Label(vm.finish.detail, 14, Moss)
    }
}

@Composable
private fun ReviewPanel(vm: Studio, t: Take, save: () -> Unit, video: () -> Unit) {
    val listen = vm.playerMode == Listen.ORIGINAL || vm.playerMode == Listen.STUDIO
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Line, RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("${t.source.label}  ·  ${dateText(t.created)}", 12, Moss, FontWeight.Bold)
        Text(
            t.title,
            color = Ink,
            fontFamily = Serif,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Wave(
                t.bars,
                Moss,
                Modifier.weight(1f).height(27.dp),
                if (listen) (vm.playSeconds / t.duration).toFloat() else 1f,
            )
            Label(
                if (listen) durationLabel(vm.playSeconds.toDouble()) else durationLabel(t.duration),
                13,
                Pine,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PillButton(
                if (vm.playerMode == Listen.ORIGINAL) "Stop"
                else if (t.source == Source.SCRIPT) "Generated" else "Original",
                { vm.listen(Listen.ORIGINAL) },
                Modifier.weight(1f),
                vm.idle,
                Paper,
                Pine,
                true,
                if (vm.playerMode == Listen.ORIGINAL) "stop" else "play",
            )
            PillButton(
                if (vm.playerMode == Listen.STUDIO) "Stop" else "Finished",
                { vm.listen(Listen.STUDIO) },
                Modifier.weight(1f),
                vm.idle && vm.mixReady,
                Pine,
                Color.White,
                false,
                if (vm.playerMode == Listen.STUDIO) "stop" else "play",
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (t.video != null)
                PillButton(
                    "Save video",
                    video,
                    Modifier.weight(1f),
                    vm.idle && vm.mixReady,
                    Lime,
                    Pine,
                )
            PillButton(
                "Save WAV",
                save,
                Modifier.weight(1f),
                vm.idle && vm.mixReady,
                if (t.video == null) Lime else Paper,
                Pine,
                t.video != null,
            )
        }
    }
}

@Composable
private fun TakesDialog(vm: Studio, close: () -> Unit) {
    var remove by remember { mutableStateOf<Take?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Heading("Saved takes", 27) },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (vm.takes.isEmpty())
                    Label("Your recordings and generated voiceovers will appear here.", 15, Moss)
                vm.takes.forEach { t ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f)
                                .clickable(role = Role.Button) {
                                    vm.open(t)
                                    close()
                                }
                                .padding(vertical = 9.dp)
                        ) {
                            Label(t.title, 16, Ink, FontWeight.Bold)
                            Label(
                                "${dateText(t.created)}  ·  ${durationLabel(t.duration)}",
                                12,
                                Moss,
                            )
                        }
                        TextButton(onClick = { remove = t }) { Label("Delete", 13, Moss) }
                    }
                    HorizontalDivider(color = Line)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Label("Done", 15, Pine) } },
    )
    remove?.let { t ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Heading("Delete this take?", 24) },
            text = { Label(t.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteTake(t)
                        remove = null
                    }
                ) {
                    Label("Delete take", 15, Pine)
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Label("Keep", 15, Moss) } },
        )
    }
}

@Composable
private fun Credits(close: () -> Unit) {
    val ctx = LocalContext.current
    var license by remember { mutableStateOf<String?>(null) }
    val entries = remember {
        ctx.assets.list("licenses").orEmpty().filterNot { it == "Credits.txt" }.sorted()
    }
    val content =
        remember(license) {
            ctx.assets.open("licenses/${license ?: "Credits.txt"}").bufferedReader().use {
                it.readText()
            }
        }
    val pages = remember(content) { content.chunked(5000) }
    var page by remember(license) { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = close,
        title = { Heading(if (license == null) "About ToneRoom" else "Licence", 25) },
        text = {
            key(license, page) {
                Column(
                    Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (license == null)
                        Label(
                            "Record a take, import a clip, or read an English script using your own sample. Each saved take stays on this device until you delete it. Use Save WAV or Save video to keep a copy outside ToneRoom.",
                            14,
                        )
                    if (pages.size > 1)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(enabled = page > 0, onClick = { page-- }) {
                                Label("Previous", 13, Pine)
                            }
                            Label("${page+1} / ${pages.size}", 12, Moss)
                            TextButton(enabled = page < pages.lastIndex, onClick = { page++ }) {
                                Label("Next", 13, Pine)
                            }
                        }
                    Label(pages[page], 13, Moss)
                    if (license == null)
                        entries.forEach { name ->
                            TextButton(onClick = { license = name }) {
                                Label(name.removeSuffix(".txt"), 14, Pine)
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (license == null) close() else license = null }) {
                Label(if (license == null) "Done" else "Back", 15, Pine)
            }
        },
    )
}
