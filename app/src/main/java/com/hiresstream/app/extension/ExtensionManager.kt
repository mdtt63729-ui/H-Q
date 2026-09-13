package com.hiresstream.app.extension

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.jar.JarFile

/**
 * Lightweight extension installer.
 *
 * Echo extensions are JVM shadow JARs. Android does not execute arbitrary JVM
 * extension bytecode directly. We therefore use the downloaded extension as
 * the provider declaration/activation package and run a small native Android
 * adapter for supported extension ids. This keeps the APK small and avoids a
 * JVM/D8 runtime inside the app.
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

    suspend fun import(uri: Uri): Result<InstalledExtension> = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(dir, "incoming-${System.currentTimeMillis()}.jar")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to read extension file")

            val metadata = readManifest(temp) ?: run {
                temp.delete()
                error("Not a valid Echo extension JAR")
            }
            if (!isSupported(metadata.id)) {
                temp.delete()
                error("Unsupported extension: ${metadata.id}")
            }

            dir.listFiles()?.filter { it != temp }?.forEach { it.delete() }
            val finalFile = File(dir, "${metadata.id}.jar")
            if (finalFile.exists()) finalFile.delete()
            if (!temp.renameTo(finalFile)) {
                temp.copyTo(finalFile, overwrite = true)
                temp.delete()
            }

            prefs.edit()
                .putString(KEY_ID, metadata.id)
                .putString(KEY_NAME, metadata.name)
                .putString(KEY_VERSION_NAME, metadata.versionName)
                .putString(KEY_VERSION_CODE, metadata.versionCode)
                .putString(KEY_FILE, finalFile.name)
                .apply()

            metadata.copy(fileName = finalFile.name)
        }
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
    } catch (_: Exception) {
        null
    }

    companion object {
        const val SUPPORTED_SAAVN_ID = "saavn_music"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_VERSION_NAME = "versionName"
        private const val KEY_VERSION_CODE = "versionCode"
        private const val KEY_FILE = "file"
    }
}
