package com.hiresstream.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import com.hiresstream.app.audio.AudioEngine
import com.hiresstream.app.audio.RealtimeEnhancerAudioProcessor
import com.hiresstream.app.data.SaavnRepository
import com.hiresstream.app.data.Song
import com.hiresstream.app.data.OfflineManager
import com.hiresstream.app.data.OfflineSong
import com.hiresstream.app.extension.ExtensionManager
import com.hiresstream.app.extension.InstalledExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@kotlin.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var originalPlayer: ExoPlayer
    private lateinit var upgradedPlayer: ExoPlayer
    private val originalAudioStats = MutableStateFlow(SourceAudioStats())
    private val audioEngine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalPlayer = ExoPlayer.Builder(this).build()
        upgradedPlayer = ExoPlayer.Builder(this, audioEngine.renderersFactory(this)).build()
        configurePlayer(originalPlayer)
        originalPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                originalAudioStats.value = SourceAudioStats(format.sampleRate, format.channelCount, format.bitrate.takeIf { it > 0 })
            }
        })
        configurePlayer(upgradedPlayer)
        setContent { HiResTheme { HiResApp(originalPlayer, upgradedPlayer, audioEngine.processor, originalAudioStats) } }
    }

    private fun configurePlayer(player: ExoPlayer) {
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true
        )
    }

    override fun onDestroy() {
        originalPlayer.release()
        upgradedPlayer.release()
        super.onDestroy()
    }
}

@Composable
private fun HiResTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFB9A7FF),
        onPrimary = Color(0xFF21163D),
        secondary = Color(0xFF7DD3FC),
        background = Color(0xFF08090D),
        surface = Color(0xFF101218),
        surfaceVariant = Color(0xFF191C25),
        onSurface = Color(0xFFF5F5F7),
        onSurfaceVariant = Color(0xFF9EA3B0)
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

private enum class PlaybackMode { ORIGINAL, UPGRADED }
private data class StreamChoice(val url: String, val kbps: Int)
private data class SourceAudioStats(val sampleRate: Int = 0, val channels: Int = 0, val bitrate: Int? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiResApp(
    originalPlayer: ExoPlayer,
    upgradedPlayer: ExoPlayer,
    processor: RealtimeEnhancerAudioProcessor,
    originalAudioStats: MutableStateFlow<SourceAudioStats>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { SaavnRepository() }
    val offline = remember { OfflineManager(context) }
    val extensions = remember { ExtensionManager(context) }
    val scope = rememberCoroutineScope()
    val enhancedStats by processor.stats.collectAsState()
    val sourceStats by originalAudioStats.collectAsState()
    var screen by remember { mutableStateOf("home") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var homeSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var current by remember { mutableStateOf<Song?>(null) }
    var currentSource by remember { mutableStateOf<String?>(null) }
    var currentSourceQuality by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var playbackMode by remember { mutableStateOf(PlaybackMode.UPGRADED) }
    var showPlayer by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var offlineSongs by remember { mutableStateOf(offline.list()) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(extensions.installed()) }
    val activePlayer = if (playbackMode == PlaybackMode.UPGRADED) upgradedPlayer else originalPlayer

    LaunchedEffect(Unit) {
        loading = true; error = null
        extensions.ensureLatestSaavn().onSuccess { installed = it }
            .onFailure { error = it.message ?: "Unable to install the Saavn extension automatically" }
        loading = false
    }
    LaunchedEffect(installed?.id) {
        if (installed != null) {
            loading = true; error = null
            try { homeSongs = repo.homeSongs() } catch (t: Throwable) { error = t.message ?: "Home feed failed" } finally { loading = false }
        } else homeSongs = emptyList()
    }

    fun bestStream(resolved: Song): StreamChoice? = when {
        !resolved.stream320.isNullOrBlank() -> StreamChoice(resolved.stream320!!, 320)
        !resolved.stream160.isNullOrBlank() -> StreamChoice(resolved.stream160!!, 160)
        !resolved.stream96.isNullOrBlank() -> StreamChoice(resolved.stream96!!, 96)
        !resolved.stream48.isNullOrBlank() -> StreamChoice(resolved.stream48!!, 48)
        else -> null
    }
    fun playerFor(mode: PlaybackMode): ExoPlayer = if (mode == PlaybackMode.UPGRADED) upgradedPlayer else originalPlayer

    fun startPlayback(resolved: Song, localFile: java.io.File? = null, sourceQuality: Int = 0, positionMs: Long = 0L, autoPlay: Boolean = true) {
        val choice = if (localFile == null) bestStream(resolved) else null
        val url = localFile?.let { android.net.Uri.fromFile(it).toString() } ?: choice?.url
        val kbps = if (localFile != null) sourceQuality else choice?.kbps ?: 0
        if (url.isNullOrBlank()) { error = "No playable stream was returned by the provider."; return }
        current = resolved; currentSource = url; currentSourceQuality = kbps
        val mediaItem = MediaItem.Builder().setUri(url).setMediaMetadata(
            MediaMetadata.Builder().setTitle(resolved.title).setArtist(resolved.artist).setArtworkUri(android.net.Uri.parse(resolved.image)).build()
        ).build()
        originalPlayer.pause(); upgradedPlayer.pause()
        val target = playerFor(playbackMode)
        target.setMediaItem(mediaItem, positionMs); target.prepare(); if (autoPlay) target.play()
        showPlayer = true
    }

    fun play(song: Song) {
        val saved = offlineSongs.firstOrNull { it.song.id == song.id }
        if (saved != null) { startPlayback(saved.song, offline.audioFile(saved), saved.quality); return }
        scope.launch {
            loading = true; error = null
            try { startPlayback(repo.resolveStream(song)) } catch (t: Throwable) { error = t.message ?: "Playback failed" } finally { loading = false }
        }
    }

    fun saveOffline(song: Song) {
        if (offlineSongs.any { it.song.id == song.id }) return
        scope.launch {
            downloadingId = song.id; error = null
            try {
                val resolved = repo.resolveStream(song)
                val choice = bestStream(resolved) ?: error("No stream available for offline download.")
                offline.download(resolved, choice.url, choice.kbps).getOrThrow()
                offlineSongs = offline.list()
            } catch (t: Throwable) { error = t.message ?: "Offline download failed" } finally { downloadingId = null }
        }
    }
    fun playOffline(item: OfflineSong) { startPlayback(item.song, offline.audioFile(item), item.quality) }

    fun switchMode(mode: PlaybackMode) {
        if (mode == playbackMode) return
        val from = activePlayer
        val to = playerFor(mode)
        val position = from.currentPosition.coerceAtLeast(0L)
        val wasPlaying = from.isPlaying
        val item = from.currentMediaItem ?: currentSource?.let { MediaItem.fromUri(it) }
        if (item == null) { playbackMode = mode; return }
        from.pause()
        to.stop()
        to.setMediaItem(item, position)
        to.prepare()
        playbackMode = mode
        if (wasPlaying) to.play()
    }

    if (showPlayer && current != null) {
        FullPlayer(current!!, activePlayer, playbackMode, currentSourceQuality, enhancedStats, sourceStats, ::switchMode) { showPlayer = false }
        return
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Hi-Res Stream", fontWeight = FontWeight.Bold) }) },
        bottomBar = { NavigationBar {
            NavigationBarItem(screen == "home", { screen = "home" }, { Text("⌂") }, label = { Text("Home") })
            NavigationBarItem(screen == "search", { screen = "search" }, { Text("⌕") }, label = { Text("Search") })
            NavigationBarItem(screen == "offline", { screen = "offline" }, { Text("⇩") }, label = { Text("Offline") })
        } }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                "home" -> Home(installed, homeSongs, current, activePlayer.isPlaying, loading, error, offlineSongs, downloadingId, ::play, ::saveOffline)
                "search" -> SearchScreen(installed, query, results, loading, error, { query = it }, {
                    if (installed == null) error = "Saavn extension is still connecting. Please try again in a moment."
                    else if (query.isNotBlank()) scope.launch { loading = true; error = null; try { results = repo.searchSongs(query) } catch (t: Throwable) { error = t.message ?: "Search failed" } finally { loading = false } }
                }, ::play, ::saveOffline, offlineSongs, downloadingId)
                "offline" -> OfflineScreen(offlineSongs, error, ::playOffline) { item -> offline.delete(item); offlineSongs = offline.list() }
            }
        }
    }
}

@Composable
private fun Home(
    extension: InstalledExtension?, songs: List<Song>, current: Song?, playing: Boolean,
    loading: Boolean, error: String?, offlineSongs: List<OfflineSong>, downloadingId: String?,
    play: (Song) -> Unit, saveOffline: (Song) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Your music, processed in real time.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("100% local processing • no cloud enhancement. The source is decoded on-device, resampled locally to a stable 48 kHz playback path, then enhanced before AudioTrack. This does not recreate detail that was absent from the source.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (extension == null) {
            Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    Text("Connecting to Saavn", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("The latest Saavn extension is installed automatically from its GitHub release.")
                }
            }
            return
        }
        AssistChip(onClick = {}, label = { Text("${extension.name} • ${extension.versionName}") }, leadingIcon = { Text("＋") })
        Spacer(Modifier.height(14.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp))
        if (songs.isEmpty() && !loading) {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("No Home tracks returned", fontWeight = FontWeight.Bold)
                    Text("Try Search again if the provider feed is temporarily unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            items(songs, key = { it.id }) { song -> SongRow(song, if (current?.id == song.id && playing) "Playing" else "", play, saveOffline, offlineSongs.any { it.song.id == song.id }, downloadingId == song.id) }
        }
    }
}

@Composable
private fun SearchScreen(
    extension: InstalledExtension?, query: String, results: List<Song>, loading: Boolean, error: String?,
    onQuery: (String) -> Unit, search: () -> Unit, play: (Song) -> Unit,
    saveOffline: (Song) -> Unit, offlineSongs: List<OfflineSong>, downloadingId: String?
) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        if (extension == null) {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Music provider not installed", fontWeight = FontWeight.Bold)
                    Text("The Saavn extension is installed automatically when the app connects to GitHub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), singleLine = true, label = { Text("Search songs") }, leadingIcon = { Text("⌕") }, shape = RoundedCornerShape(18.dp))
            Spacer(Modifier.width(8.dp)); FilledIconButton(onClick = search) { Text("→") }
        }
        if (loading) { Spacer(Modifier.height(14.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(10.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { song -> SongRow(song, "", play, saveOffline, offlineSongs.any { it.song.id == song.id }, downloadingId == song.id) }
        }
    }
}

@Composable
private fun SongRow(song: Song, badge: String, play: (Song) -> Unit, saveOffline: (Song) -> Unit, downloaded: Boolean, downloading: Boolean) {
    ListItem(
        headlineContent = { Text(song.title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(if (badge.isBlank()) song.artist else "${song.artist} • $badge") },
        leadingContent = { NetworkImage(song.image, Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else if (downloaded) Text("✓", modifier = Modifier.padding(horizontal = 8.dp))
                else IconButton({ saveOffline(song) }) { Text("⇩") }
                IconButton({ play(song) }) { Text("▶") }
            }
        }
    )
}

@Composable
private fun OfflineScreen(items: List<OfflineSong>, error: String?, play: (OfflineSong) -> Unit, delete: (OfflineSong) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Offline music", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Downloaded tracks stay on this device and play without internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        if (items.isEmpty()) {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Nothing saved offline", fontWeight = FontWeight.Bold)
                    Text("Tap ⇩ beside a song to download it for offline playback.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.song.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.song.title, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("${item.song.artist} • ${item.quality} kbps") },
                    leadingContent = { NetworkImage(item.song.image, Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))) },
                    trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { IconButton({ delete(item) }) { Text("×") }; IconButton({ play(item) }) { Text("▶") } } }
                )
            }
        }
    }
}

@Composable
private fun FullPlayer(
    song: Song,
    player: ExoPlayer,
    mode: PlaybackMode,
    sourceQuality: Int,
    stats: RealtimeEnhancerAudioProcessor.AudioStats,
    sourceStats: SourceAudioStats,
    onMode: (PlaybackMode) -> Unit,
    onClose: () -> Unit
) {
    var playing by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, playing) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            kotlinx.coroutines.delay(250)
        }
    }

    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val accent = if (mode == PlaybackMode.UPGRADED) Color(0xFFB9A7FF) else Color(0xFF7DD3FC)

    Box(Modifier.fillMaxSize().background(Color(0xFF08090D))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF151821), modifier = Modifier.size(44.dp).clickable(onClick = onClose)) {
                    Box(contentAlignment = Alignment.Center) { Text("⌄", style = MaterialTheme.typography.titleLarge) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
                    Text(if (mode == PlaybackMode.UPGRADED) "Local enhanced playback • on-device" else "Original playback", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF151821), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("⋯", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(30.dp))) {
                NetworkImage(song.image, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xCC08090D), modifier = Modifier.align(Alignment.TopStart).padding(14.dp)) {
                    Text(if (mode == PlaybackMode.UPGRADED) "UPGRADED" else "ORIGINAL", modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF151821), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(4.dp)) {
                    PlayerModeChip("Original", mode == PlaybackMode.ORIGINAL, Color(0xFF7DD3FC), { onMode(PlaybackMode.ORIGINAL) }, Modifier.weight(1f))
                    PlayerModeChip("Upgraded", mode == PlaybackMode.UPGRADED, Color(0xFFB9A7FF), { onMode(PlaybackMode.UPGRADED) }, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)), color = accent, trackColor = Color(0xFF272B35))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ player.seekToPrevious() }, Modifier.size(50.dp)) { Text("⏮", style = MaterialTheme.typography.titleLarge) }
                IconButton({ player.seekBack() }, Modifier.size(50.dp)) { Text("↶", style = MaterialTheme.typography.titleLarge) }
                Surface(shape = RoundedCornerShape(24.dp), color = accent, modifier = Modifier.size(68.dp).clickable { if (player.isPlaying) player.pause() else player.play() }) {
                    Box(contentAlignment = Alignment.Center) { Text(if (playing) "Ⅱ" else "▶", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF111117)) }
                }
                IconButton({ player.seekForward() }, Modifier.size(50.dp)) { Text("↷", style = MaterialTheme.typography.titleLarge) }
                IconButton({ player.seekToNext() }, Modifier.size(50.dp)) { Text("⏭", style = MaterialTheme.typography.titleLarge) }
            }

            Spacer(Modifier.height(14.dp))
            QualityComparison(sourceQuality, mode, stats, sourceStats)
        }
    }
}

@Composable
private fun PlayerModeChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit, modifier: Modifier) {
    Surface(modifier = modifier.height(44.dp).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = if (selected) accent else Color.Transparent) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Color(0xFF101116) else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun QualityComparison(sourceQuality: Int, mode: PlaybackMode, stats: RealtimeEnhancerAudioProcessor.AudioStats, sourceStats: SourceAudioStats) {
    val originalLabel = sourceStats.bitrate?.let { "${it / 1000} kbps • measured" } ?: if (sourceQuality > 0) "$sourceQuality kbps • provider stream" else "Bitrate detected during playback"
    val sourceRate = if (sourceStats.sampleRate > 0) "${sourceStats.sampleRate / 1000f} kHz" else "sample rate detected"
    val sourceChannels = if (sourceStats.channels > 0) "${sourceStats.channels} ch" else "channels detected"
    val inputRate = if (stats.inputSampleRate > 0) "${stats.inputSampleRate / 1000f} kHz" else sourceRate
    val inputBits = if (stats.inputBits > 0) "${stats.inputBits}-bit decoded" else "decoded PCM"
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color(0xFF12151C)) {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QualityPill("ORIGINAL", originalLabel, "$sourceRate • $sourceChannels", Color(0xFF7DD3FC), Modifier.weight(1f))
                QualityPill("UPGRADED", "48 kHz local engine", if (mode == PlaybackMode.UPGRADED && stats.inputSampleRate > 0) "$inputRate • $inputBits → 48 kHz • live" else "48 kHz • local", Color(0xFFB9A7FF), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text("Upgraded mode is processed entirely on this device: local decode → local resampling → local DSP → PCM16 AudioTrack. No audio is sent to a cloud enhancement service.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QualityPill(title: String, value: String, detail: String, accent: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(17.dp), color = Color(0xFF191C25)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun NetworkImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text("♪", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
