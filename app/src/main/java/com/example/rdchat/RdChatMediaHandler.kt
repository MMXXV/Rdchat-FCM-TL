package com.example.rdchat

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

class RdChatMediaHandler(private val activity: Activity) {

    fun open(sourceValue: String, requestedMime: String, requestedName: String) {
        val source = sourceValue.trim()
        if (source.isEmpty()) {
            showMessage("Arquivo vazio.")
            return
        }

        Thread({
            try {
                val contentUri = if (source.startsWith("content://", true)) Uri.parse(source) else null
                if (contentUri != null) {
                    val mime = normalizeMime(requestedMime, "application/octet-stream")
                    val kind = kindFor(mime)
                    activity.runOnUiThread { openWithPreference(contentUri, mime, kind) }
                    return@Thread
                }

                val (bytes, detectedMime) = readSource(source, requestedMime)
                if (bytes.isEmpty()) throw IllegalArgumentException("O arquivo está vazio.")
                if (bytes.size > MAX_MEDIA_BYTES) throw IllegalArgumentException("O arquivo ultrapassa o limite de 20 MB.")

                val directory = File(activity.cacheDir, "midia_privada")
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException("Não foi possível preparar a pasta temporária.")
                }
                File(directory, ".nomedia").apply { if (!exists()) createNewFile() }

                val safeName = requestedName
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(80)
                    .ifBlank { "arquivo" }
                val existingExtension = safeName.substringAfterLast('.', "")
                    .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                val extension = existingExtension
                    ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(detectedMime)
                    ?: "bin"
                val baseName = if (existingExtension != null) safeName.substringBeforeLast('.') else safeName
                val file = File(directory, "rdchat_${System.currentTimeMillis()}_${baseName}.$extension")
                file.writeBytes(bytes)

                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file
                )
                val finalMime = normalizeMime(detectedMime, "application/octet-stream")
                val kind = kindFor(finalMime)
                activity.runOnUiThread { openWithPreference(uri, finalMime, kind) }
            } catch (error: Exception) {
                showMessage("Não foi possível abrir o arquivo: ${error.message ?: "erro desconhecido"}")
            }
        }, "RdChat-Media-Open").start()
    }
    // rdchat-media-picker-api-v1: APIs de seleção de aplicativo padrão usadas pelo painel HTML.
    fun getMediaDefaultsJson(): String {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = org.json.JSONObject()
        for (kind in MEDIA_KINDS) {
            val selectedPackage = prefs.getString("default_$kind", null) ?: continue
            val selectedApp = availableMediaApps(kind).firstOrNull { it.first == selectedPackage }
            if (selectedApp == null) {
                prefs.edit().remove("default_$kind").apply()
            } else {
                result.put(kind, org.json.JSONObject()
                    .put("package", selectedApp.first)
                    .put("label", selectedApp.second))
            }
        }
        return result.toString()
    }

    fun getAvailableMediaAppsJson(kindValue: String): String {
        val kind = normalizeMediaKind(kindValue) ?: return "[]"
        val result = org.json.JSONArray()
        for ((packageName, label) in availableMediaApps(kind)) {
            result.put(org.json.JSONObject().put("package", packageName).put("label", label))
        }
        return result.toString()
    }

    fun setDefaultMediaApp(kindValue: String, packageName: String): Boolean {
        val kind = normalizeMediaKind(kindValue) ?: return false
        val selected = packageName.trim()
        if (selected.isBlank() || selected == activity.packageName) return false
        val match = availableMediaApps(kind).firstOrNull { it.first == selected } ?: return false
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("default_$kind", match.first).apply()
        return true
    }

    fun clearDefaultMediaApp(kindValue: String): Boolean {
        val kind = normalizeMediaKind(kindValue) ?: return false
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("default_$kind").apply()
        return true
    }

    private fun normalizeMediaKind(value: String): String? {
        val kind = value.trim().lowercase(java.util.Locale.ROOT)
        return kind.takeIf { it in MEDIA_KINDS }
    }

    private fun availableMediaApps(kind: String): List<Pair<String, String>> {
        val mimeTypes = when (kind) {
            "image" -> listOf("image/*")
            "video" -> listOf("video/*")
            "audio" -> listOf("audio/*")
            "document" -> listOf(
                "application/pdf", "application/*", "text/plain", "text/*",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )
            else -> emptyList()
        }
        val pm = activity.packageManager
        val found = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for (mime in mimeTypes) {
            val probe = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://rdchat.invalid/app-picker"), mime)
            }
            val handlers = pm.queryIntentActivities(
                probe,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in handlers) {
                val packageName = resolveInfo.activityInfo?.packageName ?: continue
                if (packageName == activity.packageName) continue
                val label = resolveInfo.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { packageName }
                if (!found.containsKey(packageName)) found[packageName] = label
            }
        }
        return found.entries
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value })
            .map { it.key to it.value }
    }
    fun resetDefaultMediaApp() {
        activity.runOnUiThread {
            val labels = arrayOf("Fotos", "Vídeos", "Áudios", "Documentos", "Todas as preferências")
            AlertDialog.Builder(activity)
                .setTitle("Qual aplicativo padrão deseja alterar?")
                .setItems(labels) { _, selected ->
                    val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val key = when (selected) {
                        0 -> "default_image"
                        1 -> "default_video"
                        2 -> "default_audio"
                        3 -> "default_document"
                        else -> null
                    }
                    if (key == null) prefs.edit().clear().apply()
                    else prefs.edit().remove(key).apply()
                    Toast.makeText(activity, "Na próxima abertura, o Android perguntará qual aplicativo usar.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun readSource(source: String, requestedMime: String): Pair<ByteArray, String> {
        if (source.startsWith("data:", true)) {
            val comma = source.indexOf(',')
            if (comma <= 4) throw IllegalArgumentException("Formato de arquivo inválido.")
            val header = source.substring(0, comma)
            val payload = source.substring(comma + 1)
            if (payload.length > 28_000_000) throw IllegalArgumentException("O arquivo ultrapassa o limite de 20 MB.")
            val mime = Regex("^data:([^;,]+)", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.getOrNull(1)
                ?: normalizeMime(requestedMime, "application/octet-stream")
            val bytes = if (header.contains(";base64", true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            return bytes to normalizeMime(mime, requestedMime)
        }

        if (source.startsWith("https://", true) || source.startsWith("http://", true)) {
            val connection = URL(source).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "*/*")
            try {
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) throw IllegalStateException("Servidor respondeu HTTP $responseCode.")
                val responseMime = connection.contentType?.substringBefore(';')
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_MEDIA_BYTES) throw IllegalArgumentException("O arquivo ultrapassa o limite de 20 MB.")
                        output.write(buffer, 0, count)
                    }
                }
                val mime = normalizeMime(responseMime ?: requestedMime, requestedMime)
                return output.toByteArray() to mime
            } finally {
                connection.disconnect()
            }
        }

        throw IllegalArgumentException("Origem do arquivo não suportada pelo aplicativo.")
    }

    private fun openWithPreference(uri: Uri, mimeValue: String, kind: String) {
        try {
            val mime = normalizeMime(mimeValue, "application/octet-stream")
            val intentMime = if (mime == "application/octet-stream" || mime.contains('*')) "*/*" else mime
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, intentMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedPackage = prefs.getString("default_$kind", null)

            if (!savedPackage.isNullOrBlank()) {
                try {
                    activity.startActivity(Intent(viewIntent).setPackage(savedPackage))
                    return
                } catch (_: Exception) {
                    prefs.edit().remove("default_$kind").apply()
                }
            }

            val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callbackIntent = Intent(activity, MediaPickReceiver::class.java).putExtra("kind", kind)
            val callback = PendingIntent.getBroadcast(
                activity,
                kind.hashCode(),
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
            )
            activity.startActivity(Intent.createChooser(viewIntent, "Abrir com", callback.intentSender))
        } catch (error: Exception) {
            showMessage("Nenhum aplicativo conseguiu abrir este arquivo: ${error.message ?: "erro desconhecido"}")
        }
    }

    private fun normalizeMime(value: String?, fallback: String): String {
        val candidate = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            candidate.isBlank() || candidate == "*/*" -> fallback
            candidate == "image/*" -> "image/jpeg"
            candidate == "video/*" -> "video/mp4"
            candidate == "audio/*" -> "audio/webm"
            else -> candidate
        }
    }

    private fun kindFor(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> "document"
    }

    private fun showMessage(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private val MEDIA_KINDS = setOf("image", "video", "audio", "document")
        private const val PREFS_NAME = "rdchat_media"
        private const val MAX_MEDIA_BYTES = 20 * 1024 * 1024
    }
}

class MediaPickReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        if (kind !in setOf("image", "video", "audio", "document")) return
        val chosen: ComponentName? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        val packageName = chosen?.packageName ?: return
        context.getSharedPreferences("rdchat_media", Context.MODE_PRIVATE)
            .edit()
            .putString("default_$kind", packageName)
            .apply()
    }
}