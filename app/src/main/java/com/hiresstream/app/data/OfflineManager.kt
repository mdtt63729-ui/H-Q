package com.hiresstream.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Lightweight offline music library. Audio is stored in app-private storage. */
class OfflineManager(context: Context) {
    private val dir = File(context.filesDir, "offline").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun list(): List<OfflineSong> = dir.listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { read(it) }
        ?.filter { File(dir, it.audioFile).exists() }
        ?.sortedByDescending { it.savedAt }
        ?: emptyList()

    fun isDownloaded(songId: String): Boolean = list().any { it.song.id == songId }

    suspend fun download(song: Song, streamUrl: String, quality: Int): Result<OfflineSong> = withContext(Dispatchers.IO) {
        runCatching {
            require(streamUrl.isNotBlank()) { "No playable stream is available for offline download." }
            val safeId = song.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val audioName = "${safeId}_${quality}.mp4"
            val audio = File(dir, audioName)
            val part = File(dir, "$audioName.part")
            if (part.exists()) part.delete()

            client.newCall(Request.Builder().url(streamUrl).header("User-Agent", "HiResStream").build())
                .execute().use { response ->
                    if (!response.isSuccessful) error("Offline download failed: HTTP ${response.code}")
                    val body = response.body ?: error("Offline download was empty")
                    body.byteStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
                }
            if (audio.exists()) audio.delete()
            if (!part.renameTo(audio)) { part.copyTo(audio, overwrite = true); part.delete() }

            val item = OfflineSong(song, audio.name, quality, System.currentTimeMillis())
            write(item)
            item
        }
    }

    fun delete(item: OfflineSong) {
        File(dir, item.audioFile).delete()
        File(dir, "${safeId(item.song.id)}.json").delete()
        // Older entries may be keyed by quality; remove their metadata too.
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val stored = read(file)
            if (stored?.song?.id == item.song.id) file.delete()
        }
    }

    fun audioFile(item: OfflineSong): File = File(dir, item.audioFile)

    private fun safeId(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun write(item: OfflineSong) {
        val o = JSONObject()
            .put("id", item.song.id)
            .put("title", item.song.title)
            .put("artist", item.song.artist)
            .put("album", item.song.album)
            .put("image", item.song.image)
            .put("durationMs", item.song.durationMs)
            .put("audioFile", item.audioFile)
            .put("quality", item.quality)
            .put("savedAt", item.savedAt)
        File(dir, "${safeId(item.song.id)}.json").writeText(o.toString())
    }

    private fun read(file: File): OfflineSong? = runCatching {
        val o = JSONObject(file.readText())
        OfflineSong(
            song = Song(
                id = o.getString("id"), title = o.getString("title"), artist = o.optString("artist"),
                album = o.optString("album"), image = o.optString("image"), durationMs = o.optLong("durationMs")
            ),
            audioFile = o.getString("audioFile"), quality = o.optInt("quality", 320), savedAt = o.optLong("savedAt")
        )
    }.getOrNull()
}

data class OfflineSong(
    val song: Song,
    val audioFile: String,
    val quality: Int,
    val savedAt: Long
)
