package com.hiresstream.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Native Android adapter for the Saavn Echo extension provider. */
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val base = "https://www.jiosaavn.com/api.php"
    private val desKey = "38346591"

    suspend fun homeSongs(language: String = "hindi"): List<Song> = withContext(Dispatchers.IO) {
        val url = "$base?__call=webapi.getLaunchData&api_version=4&_format=json&_marker=0&ctx=wap6dot0"
        val body = get(url, mapOf("Cookie" to "L=$language;"))
        extractSongs(JSONObject(body)).take(30)
    }

    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = buildUrl(
            "search.getResults",
            mapOf(
                "q" to query,
                "p" to "1",
                "n" to "30",
                "_format" to "json",
                "_marker" to "0",
                "api_version" to "4",
                "ctx" to "web6dot0"
            )
        )
        extractSongs(JSONObject(get(url))).take(30)
    }

    suspend fun resolveStream(song: Song): Song = withContext(Dispatchers.IO) {
        if (!song.stream320.isNullOrBlank()) return@withContext song
        val url = buildUrl(
            "song.getDetails",
            mapOf("pids" to song.id, "cc" to "in", "_format" to "json", "_marker" to "0")
        )
        val root = JSONObject(get(url))
        val detail = findObjectWithKey(root, "encrypted_media_url") ?: root.optJSONObject(song.id)
            ?: return@withContext song
        val encrypted = detail.optString("encrypted_media_url").takeIf { it.isNotBlank() }
            ?: return@withContext song
        val decrypted = decrypt(encrypted) ?: return@withContext song
        val normalized = decrypted.replace("_96.mp4", "_96.mp4")
        song.copy(
            stream320 = replaceQuality(normalized, "320"),
            stream160 = replaceQuality(normalized, "160"),
            stream96 = replaceQuality(normalized, "96"),
            stream48 = replaceQuality(normalized, "48")
        )
    }

    private fun replaceQuality(url: String, quality: String): String = when {
        "_96.mp4" in url -> url.replace("_96.mp4", "_${quality}.mp4")
        "_160.mp4" in url -> url.replace("_160.mp4", "_${quality}.mp4")
        "_320.mp4" in url -> url.replace("_320.mp4", "_${quality}.mp4")
        "_48.mp4" in url -> url.replace("_48.mp4", "_${quality}.mp4")
        else -> url
    }

    private fun extractSongs(root: JSONObject): List<Song> {
        val out = LinkedHashMap<String, Song>()
        walk(root, out)
        return out.values.toList()
    }

    private fun walk(value: Any?, out: LinkedHashMap<String, Song>) {
        when (value) {
            is JSONObject -> {
                val type = value.optString("type")
                val more = value.optJSONObject("more_info")
                val looksLikeSong = type.equals("song", true) ||
                    more?.has("duration") == true ||
                    value.has("encrypted_media_url")
                if (looksLikeSong) parseSong(value)?.let { out.putIfAbsent(it.id, it) }
                val keys = value.keys()
                while (keys.hasNext()) walk(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), out)
        }
    }

    private fun parseSong(o: JSONObject): Song? {
        val id = o.optString("id").trim().takeIf { it.isNotBlank() } ?: return null
        val title = htmlDecode(o.optString("title").ifBlank { o.optString("song") })
        if (title.isBlank()) return null
        val more = o.optJSONObject("more_info")
        val artistMap = more?.optJSONObject("artistMap")
        val primary = artistMap?.optJSONArray("primary_artists")
        val artists = buildString {
            if (primary != null) {
                for (i in 0 until primary.length()) {
                    if (i > 0) append(", ")
                    append(primary.optJSONObject(i)?.optString("name").orEmpty())
                }
            }
        }.ifBlank { o.optString("subtitle").ifBlank { more?.optString("singers").orEmpty() } }
            .ifBlank { "Unknown artist" }
        val image = o.optString("image").replace("150x150", "500x500")
        val duration = more?.optLong("duration", 0L) ?: 0L
        val direct = more?.optString("encrypted_media_url").takeUnless { it.isNullOrBlank() }
        return Song(id, title, htmlDecode(artists), htmlDecode(more?.optString("album").orEmpty()), image, duration * 1000L,
            stream320 = direct?.let { decrypt(it) }?.let { replaceQuality(it, "320") },
            stream160 = direct?.let { decrypt(it) }?.let { replaceQuality(it, "160") },
            stream96 = direct?.let { decrypt(it) }?.let { replaceQuality(it, "96") },
            stream48 = direct?.let { decrypt(it) }?.let { replaceQuality(it, "48") }
        )
    }

    private fun findObjectWithKey(value: JSONObject, key: String): JSONObject? {
        if (value.has(key)) return value
        val keys = value.keys()
        while (keys.hasNext()) {
            when (val child = value.opt(keys.next())) {
                is JSONObject -> findObjectWithKey(child, key)?.let { return it }
                is JSONArray -> for (i in 0 until child.length()) {
                    val item = child.optJSONObject(i) ?: continue
                    findObjectWithKey(item, key)?.let { return it }
                }
            }
        }
        return null
    }

    private fun decrypt(encrypted: String): String? = try {
        val key = SecretKeySpec(desKey.toByteArray(StandardCharsets.UTF_8), "DES")
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        String(cipher.doFinal(Base64.getDecoder().decode(encrypted.trim())), StandardCharsets.UTF_8)
    } catch (_: Exception) { null }

    private fun buildUrl(call: String, params: Map<String, String>): String =
        "$base?__call=$call&" + params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("Provider request failed: HTTP ${response.code}")
            return response.body?.string() ?: error("Provider returned an empty response")
        }
    }

    private fun htmlDecode(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
