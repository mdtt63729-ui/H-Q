package com.hiresstream.app.extension

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/**
 * Automatic Saavn provider bootstrap.
 *
 * The official Echo Saavn release is an .eapk package. Hi-Res Stream does not
 * execute arbitrary Echo/JVM extension bytecode; the package is downloaded,
 * stored and activated as the provider declaration while the app's small
 * native Saavn adapter handles Android playback.
 */
data class InstalledExtension(
    val id: String,
    val name: String,
    val versionName: String,
    val versionCode: String,
    val fileName: String
)

class ExtensionManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("extensions", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "extensions").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun installed(): InstalledExtension? = prefs.getString(KEY_ID, null)?.let {
        InstalledExtension(
            id = it,
            name = prefs.getString(KEY_NAME, it) ?: it,
            versionName = prefs.getString(KEY_VERSION_NAME, "unknown") ?: "unknown",
            versionCode = prefs.getString(KEY_VERSION_CODE, "") ?: "",
            fileName = prefs.getString(KEY_FILE, "") ?: ""
        )
    }

    fun isSupported(id: String): Boolean = id == SUPPORTED_SAAVN_ID

    /** Downloads and activates the latest Saavn extension release automatically. */
    suspend fun ensureLatestSaavn(): Result<InstalledExtension> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(get(LATEST_RELEASE_URL))
            val tag = root.optString("tag_name").trim().ifBlank { error("Saavn release has no version") }
            val asset = root.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".eapk", ignoreCase = true) }
            } ?: error("Saavn release has no .eapk asset")
            val downloadUrl = asset.optString("browser_download_url").trim()
            if (downloadUrl.isBlank()) error("Saavn extension download URL is missing")
            val fileName = asset.optString("name").trim().ifBlank { "saavn-$tag.eapk" }

            val current = installed()
            if (current?.versionName == tag && File(dir, current.fileName).exists()) return@runCatching current

            val target = File(dir, "saavn-$tag.eapk")
            download(downloadUrl, target)

            // The .eapk is the provider package; its metadata is known from the
            // official release and the native adapter implements saavn_music.
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            val active = InstalledExtension(
                id = SUPPORTED_SAAVN_ID,
                name = "Saavn",
                versionName = tag,
                versionCode = tag.removePrefix("v"),
                fileName = target.name
            )
            prefs.edit()
                .putString(KEY_ID, active.id)
                .putString(KEY_NAME, active.name)
                .putString(KEY_VERSION_NAME, active.versionName)
                .putString(KEY_VERSION_CODE, active.versionCode)
                .putString(KEY_FILE, active.fileName)
                .apply()
            active
        }
    }

    /** Kept for backwards compatibility with old installs. */
    suspend fun importFile(file: File): Result<InstalledExtension> = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = readManifest(file) ?: error("Not a valid Echo extension package")
            if (!isSupported(metadata.id)) error("Unsupported extension: ${metadata.id}")
            val finalFile = File(dir, file.name)
            file.copyTo(finalFile, overwrite = true)
            val active = metadata.copy(fileName = finalFile.name)
            save(active)
            active
        }
    }

    private fun save(value: InstalledExtension) {
        prefs.edit()
            .putString(KEY_ID, value.id)
            .putString(KEY_NAME, value.name)
            .putString(KEY_VERSION_NAME, value.versionName)
            .putString(KEY_VERSION_CODE, value.versionCode)
            .putString(KEY_FILE, value.fileName)
            .apply()
    }

    private fun readManifest(file: File): InstalledExtension? = try {
        JarFile(file).use { jar ->
            val attrs = jar.manifest?.mainAttributes ?: return null
            val id = attrs.getValue("Extension-Id")?.trim().orEmpty()
            if (id.isBlank()) return null
            InstalledExtension(
                id = id,
                name = attrs.getValue("Extension-Name")?.trim().takeUnless { it.isNullOrBlank() } ?: id,
                versionName = attrs.getValue("Extension-Version-Name")?.trim().orEmpty().ifBlank { "unknown" },
                versionCode = attrs.getValue("Extension-Version-Code")?.trim().orEmpty(),
                fileName = file.name
            )
        }
    } catch (_: Exception) { null }

    private fun get(url: String): String {
        client.newCall(
            Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "HiResStream")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("GitHub request failed: HTTP ${response.code}")
            return response.body?.string() ?: error("GitHub returned an empty response")
        }
    }

    private fun download(url: String, target: File) {
        val temp = File(target.parentFile, "${target.name}.part")
        if (temp.exists()) temp.delete()
        client.newCall(Request.Builder().url(url).header("User-Agent", "HiResStream").build())
            .execute().use { response ->
                if (!response.isSuccessful) error("Extension download failed: HTTP ${response.code}")
                val body = response.body ?: error("Extension download was empty")
                body.byteStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        const val SUPPORTED_SAAVN_ID = "saavn_music"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Abhishek890/Echo-Saavn-Extension/releases/latest"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_VERSION_NAME = "versionName"
        private const val KEY_VERSION_CODE = "versionCode"
        private const val KEY_FILE = "file"
    }
}
