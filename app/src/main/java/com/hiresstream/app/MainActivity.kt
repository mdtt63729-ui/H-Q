package com.hiresstream.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.hiresstream.app.audio.AudioEngine
import com.hiresstream.app.audio.RealtimeEnhancerAudioProcessor
import com.hiresstream.app.data.SaavnRepository
import com.hiresstream.app.data.Song
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val audioEngine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this, audioEngine.renderersFactory(this)).build()
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setContent { HiResTheme { HiResApp(player, audioEngine.processor) } }
    }

    override fun onDestroy() { player.release(); super.onDestroy() }
}

@Composable
private fun HiResTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(), typography = Typography(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiResApp(player: ExoPlayer, processor: RealtimeEnhancerAudioProcessor) {
    val repo = remember { SaavnRepository() }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("home") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var current by remember { mutableStateOf<Song?>(null) }
    var loading by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(320) }
    var showPlayer by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isPlaying = player.isPlaying

    fun selectStream(resolved: Song): String? =
        if (quality == 320) resolved.stream320 ?: resolved.stream160 ?: resolved.stream96
        else resolved.stream160 ?: resolved.stream96 ?: resolved.stream320

    fun startPlayback(resolved: Song, positionMs: Long = 0L, autoPlay: Boolean = true) {
        val url = selectStream(resolved)
        if (url.isNullOrBlank()) {
            error = "No playable $quality kbps stream was returned."
            return
        }
        current = resolved
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(resolved.title)
                        .setArtist(resolved.artist)
                        .setArtworkUri(android.net.Uri.parse(resolved.image))
                        .build()
                )
                .build(),
            positionMs
        )
        player.prepare()
        if (autoPlay) player.play()
        showPlayer = true
    }

    fun play(song: Song) {
        scope.launch {
            loading = true; error = null
            try {
                val resolved = repo.resolveStream(song)
                startPlayback(resolved)
            } catch (t: Throwable) {
                error = t.message ?: "Playback failed"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(quality) {
        processor.profile = if (quality == 320) RealtimeEnhancerAudioProcessor.Profile.P320
        else RealtimeEnhancerAudioProcessor.Profile.P128

        // Quality adjustment is live: change the source stream as well as DSP parameters.
        val active = current
        if (active != null && player.currentMediaItem != null) {
            val position = player.currentPosition
            val wasPlaying = player.isPlaying
            try {
                startPlayback(active, position, wasPlaying)
            } catch (_: Throwable) {
                // Keep the existing stream if the alternate quality cannot be resolved.
            }
        }
    }

    if (showPlayer && current != null) {
        FullPlayer(current!!, player, quality, onClose = { showPlayer = false })
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Hi-Res Stream", fontWeight = FontWeight.Bold) },
                actions = { IconButton({ screen = "settings" }) { Icon(Icons.Default.Settings, "Settings") } }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(screen == "home", { screen = "home" }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(screen == "search", { screen = "search" }, { Icon(Icons.Default.Search, null) }, label = { Text("Search") })
                NavigationBarItem(screen == "settings", { screen = "settings" }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                "home" -> Home(current, isPlaying) { current?.let { showPlayer = true } }
                "search" -> SearchScreen(query, results, loading, error, { query = it }, {
                    scope.launch { if (query.isNotBlank()) { loading = true; error = null; try { results = repo.searchSongs(query) } catch (t: Throwable) { error = t.message } finally { loading = false } } }
                }, ::play)
                "settings" -> SettingsScreen(quality) { quality = it }
            }
        }
    }
}

@Composable
private fun Home(song: Song?, playing: Boolean, open: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Your music, processed in real time.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Transparent restoration with a 24-bit / 192 kHz output target.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        AssistChip(onClick = {}, label = { Text("Real-time enhancement") }, leadingIcon = { Icon(Icons.Default.GraphicEq, null) })
        Spacer(Modifier.height(24.dp))
        if (song != null) {
            Card(Modifier.fillMaxWidth().clickable { open() }, shape = RoundedCornerShape(28.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(song.image, null, Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(song.title, fontWeight = FontWeight.SemiBold); Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle, null, Modifier.size(38.dp))
                }
            }
        } else {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) { Column(Modifier.padding(24.dp)) { Text("Start listening", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("Open Search and find a song from Saavn.") } }
        }
    }
}

@Composable
private fun SearchScreen(query: String, results: List<Song>, loading: Boolean, error: String?, onQuery: (String) -> Unit, search: () -> Unit, play: (Song) -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), singleLine = true, label = { Text("Search songs") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(18.dp))
            Spacer(Modifier.width(8.dp)); FilledIconButton(onClick = search) { Icon(Icons.Default.ArrowForward, "Search") }
        }
        if (loading) { Spacer(Modifier.height(18.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { song ->
                ListItem(headlineContent = { Text(song.title, fontWeight = FontWeight.SemiBold) }, supportingContent = { Text(song.artist) }, leadingContent = { AsyncImage(song.image, null, Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) }, trailingContent = { IconButton({ play(song) }) { Icon(Icons.Default.PlayArrow, "Play") } })
            }
        }
    }
}

@Composable
private fun SettingsScreen(quality: Int, onQuality: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Quality adjustment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("This changes the real-time processing profile.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                listOf(128, 320).forEach { q -> Row(Modifier.fillMaxWidth().clickable { onQuality(q) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(quality == q, { onQuality(q) }); Spacer(Modifier.width(8.dp)); Text("$q kbps profile") } }
            }
        }
        Spacer(Modifier.height(18.dp)); AssistChip(onClick = {}, label = { Text("Output target • 24-bit / 192 kHz") }, leadingIcon = { Icon(Icons.Default.HighQuality, null) })
    }
}

@Composable
private fun FullPlayer(song: Song, player: ExoPlayer, quality: Int, onClose: () -> Unit) {
    var playing by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) { val listener = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }; player.addListener(listener); onDispose { player.removeListener(listener) } }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close") }; Text("24-bit / 192 kHz", fontWeight = FontWeight.Bold); IconButton({}) { Icon(Icons.Default.MoreVert, null) } }
            Spacer(Modifier.height(22.dp))
            AsyncImage(song.image, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(34.dp)), contentScale = ContentScale.Crop)
            Spacer(Modifier.height(22.dp)); Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp)); LinearProgressIndicator(progress = { if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { IconButton({ player.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null) }; FilledIconButton({ if (player.isPlaying) player.pause() else player.play() }, Modifier.size(72.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(36.dp)) }; IconButton({ player.seekToNext() }) { Icon(Icons.Default.SkipNext, null) } }
            AssistChip(onClick = {}, label = { Text("Enhancement profile • $quality kbps") }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
        }
    }
}
