package com.hiresstream.app.data

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Native adapter based on the public Saavn API flow used by the supplied Echo extension. */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val image: String,
    val durationMs: Long,
    val stream320: String? = null,
    val stream160: String? = null,
    val stream96: String? = null,
    val stream48: String? = null
)

class SaavnRepository {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val base = "https://www.jiosaavn.com/api.php"
    private val desKey = "38346591"

    suspend fun searchSongs(query: String): List<Song> {
        val url = "$base?__call=search.getResults&p=1&q=${URLEncoder.encode(query, StandardCharsets.UTF_8.name())}&n=30&__jsong=true"
        val body = get(url)
        val root = json.parseToJsonElement(body).jsonObject
        return root["results"]?.jsonArray?.mapNotNull { parseSong(it.jsonObject) } ?: emptyList()
    }

    suspend fun resolveStream(song: Song): Song {
        if (song.stream320 != null) return song
        val url = "$base?__call=song.getDetails&pids=${URLEncoder.encode(song.id, StandardCharsets.UTF_8.name())}&__jsong=true"
        val body = get(url)
        val root = json.parseToJsonElement(body).jsonObject
        val detail = root[song.id]?.jsonObject ?: root.values.firstOrNull()?.jsonObject ?: return song
        val encrypted = detail["encrypted_media_url"]?.jsonPrimitive?.contentOrNull ?: return song
        val decrypted = decrypt(encrypted) ?: return song
        return song.copy(
            stream320 = decrypted.replace("_96.mp4", "_320.mp4"),
            stream160 = decrypted.replace("_96.mp4", "_160.mp4"),
            stream96 = decrypted,
            stream48 = decrypted.replace("_96.mp4", "_48.mp4")
        )
    }

    private fun parseSong(o: JsonObject): Song? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = htmlDecode(o["title"]?.jsonPrimitive?.contentOrNull ?: "")
        val more = o["more_info"]?.jsonObject
        val artistMap = more?.get("artistMap")?.jsonObject
        val artists = artistMap?.get("primary_artists")?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ") ?: o["subtitle"]?.jsonPrimitive?.contentOrNull ?: "Unknown artist"
        val image = (o["image"]?.jsonPrimitive?.contentOrNull ?: "").replace("150x150", "500x500")
        val duration = more?.get("duration")?.jsonPrimitive?.longOrNull ?: 0L
        return Song(id, title, htmlDecode(artists), htmlDecode(more?.get("album")?.jsonPrimitive?.contentOrNull ?: ""), image, duration * 1000)
    }

    private fun decrypt(encrypted: String): String? = try {
        val key = SecretKeySpec(desKey.toByteArray(StandardCharsets.UTF_8), "DES")
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        String(cipher.doFinal(Base64.getDecoder().decode(encrypted.trim())), StandardCharsets.UTF_8)
    } catch (_: Exception) { null }

    private fun htmlDecode(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Saavn request failed: ${response.code}")
            return response.body?.string() ?: error("Empty Saavn response")
        }
    }
}
