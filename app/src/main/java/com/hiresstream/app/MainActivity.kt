package com.hiresstream.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hiresstream.app.audio.AudioEngine
import com.hiresstream.app.audio.RealtimeEnhancerAudioProcessor
import com.hiresstream.app.data.SaavnRepository
import com.hiresstream.app.data.Song
import com.hiresstream.app.extension.ExtensionManager
import com.hiresstream.app.extension.InstalledExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private val audioEngine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this, audioEngine.renderersFactory(this)).build()
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true
        )
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { SaavnRepository() }
    val extensions = remember { ExtensionManager(context) }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("home") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var homeSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var current by remember { mutableStateOf<Song?>(null) }
    var loading by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(320) }
    var showPlayer by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(extensions.installed()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true; error = null
            val result = extensions.import(uri)
            result.onSuccess {
                installed = it
                screen = "home"
                homeSongs = emptyList()
            }.onFailure { error = it.message ?: "Extension import failed" }
            loading = false
        }
    }

    fun addExtension() {
        picker.launch(arrayOf("application/java-archive", "application/zip", "application/octet-stream", "*/*"))
    }

    LaunchedEffect(installed?.id) {
        if (installed != null) {
            loading = true; error = null
            try { homeSongs = repo.homeSongs() }
            catch (t: Throwable) { error = t.message ?: "Home feed failed" }
            finally { loading = false }
        } else homeSongs = emptyList()
    }

    fun selectStream(resolved: Song): String? = if (quality == 320) {
        resolved.stream320 ?: resolved.stream160 ?: resolved.stream96 ?: resolved.stream48
    } else {
        resolved.stream160 ?: resolved.stream96 ?: resolved.stream48 ?: resolved.stream320
    }

    fun startPlayback(resolved: Song, positionMs: Long = 0L, autoPlay: Boolean = true) {
        val url = selectStream(resolved)
        if (url.isNullOrBlank()) { error = "No playable stream was returned by the extension."; return }
        current = resolved
        player.setMediaItem(
            MediaItem.Builder().setUri(url).setMediaMetadata(
                MediaMetadata.Builder().setTitle(resolved.title).setArtist(resolved.artist)
                    .setArtworkUri(android.net.Uri.parse(resolved.image)).build()
            ).build(), positionMs
        )
        player.prepare()
        if (autoPlay) player.play()
        showPlayer = true
    }

    fun play(song: Song) {
        scope.launch {
            loading = true; error = null
            try { startPlayback(repo.resolveStream(song)) }
            catch (t: Throwable) { error = t.message ?: "Playback failed" }
            finally { loading = false }
        }
    }

    LaunchedEffect(quality) {
        processor.profile = if (quality == 320) RealtimeEnhancerAudioProcessor.Profile.P320 else RealtimeEnhancerAudioProcessor.Profile.P128
        val active = current
        if (active != null && player.currentMediaItem != null) {
            val position = player.currentPosition; val wasPlaying = player.isPlaying
            runCatching { startPlayback(repo.resolveStream(active), position, wasPlaying) }
        }
    }

    if (showPlayer && current != null) {
        FullPlayer(current!!, player, quality) { showPlayer = false }
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
                "home" -> Home(installed, homeSongs, current, player.isPlaying, loading, error, ::addExtension) { play(it) }
                "search" -> SearchScreen(installed, query, results, loading, error, { query = it }, {
                    if (installed == null) {
                        error = "Add the Saavn extension first."
                    } else if (query.isNotBlank()) {
                        scope.launch {
                            loading = true; error = null
                            try { results = repo.searchSongs(query) }
                            catch (t: Throwable) { error = t.message ?: "Search failed" }
                            finally { loading = false }
                        }
                    }
                }, ::addExtension, ::play)
                "settings" -> SettingsScreen(quality) { quality = it }
            }
        }
    }
}

@Composable
private fun Home(
    extension: InstalledExtension?, songs: List<Song>, current: Song?, playing: Boolean,
    loading: Boolean, error: String?, addExtension: () -> Unit, play: (Song) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Your music, processed in real time.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Conservative real-time enhancement. Lossy sources are not magically converted into the original master.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (extension == null) {
            Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    Text("Add a music extension", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Download the Echo Saavn extension JAR and add it here. Once activated, Home, Search and playback use the provider.")
                    Spacer(Modifier.height(14.dp))
                    Button(addExtension) { Icon(Icons.Default.Extension, null); Spacer(Modifier.width(8.dp)); Text("Add Extension") }
                }
            }
            return
        }
        AssistChip(onClick = {}, label = { Text("${extension.name} • ${extension.versionName}") }, leadingIcon = { Icon(Icons.Default.Extension, null) })
        Spacer(Modifier.height(14.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp))
        if (songs.isEmpty() && !loading) {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("No Home tracks returned", fontWeight = FontWeight.Bold)
                    Text("Try Search, or re-add the extension if its provider package is outdated.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            items(songs, key = { it.id }) { song -> SongRow(song, if (current?.id == song.id && playing) "Playing" else "", play) }
        }
    }
}

@Composable
private fun SearchScreen(
    extension: InstalledExtension?, query: String, results: List<Song>, loading: Boolean, error: String?,
    onQuery: (String) -> Unit, search: () -> Unit, addExtension: () -> Unit, play: (Song) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        if (extension == null) {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Music provider not installed", fontWeight = FontWeight.Bold)
                    Text("Add the downloaded Saavn extension to enable Search and playback.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp)); Button(addExtension) { Icon(Icons.Default.Extension, null); Spacer(Modifier.width(8.dp)); Text("Add Extension") }
                }
            }
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), singleLine = true, label = { Text("Search songs") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(18.dp))
            Spacer(Modifier.width(8.dp)); FilledIconButton(onClick = search) { Icon(Icons.Default.ArrowForward, "Search") }
        }
        if (loading) { Spacer(Modifier.height(14.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(10.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { song -> SongRow(song, "", play) }
        }
    }
}

@Composable
private fun SongRow(song: Song, badge: String, play: (Song) -> Unit) {
    ListItem(
        headlineContent = { Text(song.title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(if (badge.isBlank()) song.artist else "${song.artist} • $badge") },
        leadingContent = { NetworkImage(song.image, Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))) },
        trailingContent = { IconButton({ play(song) }) { Icon(Icons.Default.PlayArrow, "Play") } }
    )
}

@Composable
private fun SettingsScreen(quality: Int, onQuality: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Quality adjustment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Changes the selected source quality and the real-time processing profile.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                listOf(128, 320).forEach { q ->
                    Row(Modifier.fillMaxWidth().clickable { onQuality(q) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(quality == q, { onQuality(q) }); Spacer(Modifier.width(8.dp)); Text("$q kbps profile")
                    }
                }
            }
        }
    }
}

@Composable
private fun FullPlayer(song: Song, player: ExoPlayer, quality: Int, onClose: () -> Unit) {
    var playing by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(listener); onDispose { player.removeListener(listener) }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
                Text("Hi-Res", fontWeight = FontWeight.Bold)
                IconButton({}) { Icon(Icons.Default.MoreVert, null) }
            }
            Spacer(Modifier.height(22.dp))
            NetworkImage(song.image, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(34.dp)))
            Spacer(Modifier.height(22.dp)); Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp)); LinearProgressIndicator(progress = { if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ player.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, null) }
                FilledIconButton({ if (player.isPlaying) player.pause() else player.play() }, Modifier.size(72.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(36.dp)) }
                IconButton({ player.seekToNext() }) { Icon(Icons.Default.SkipNext, null) }
            }
            AssistChip(onClick = {}, label = { Text("Enhancement profile • $quality kbps") }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
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
            ?: Icon(Icons.Default.MusicNote, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
