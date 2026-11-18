package com.example.rdchat.telegram

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Ponte TDLib complementar; MainActivity delega para ela sem substituir AndroidBridge. */
class WebAppInterface(
    context: Context,
    private val web: WebView,
    apiId: Int,
    apiHash: String
) : TelegramManager.Callbacks {

    private val ctx = context.applicationContext
    private val tg = TelegramManager(ctx, this, apiId, apiHash)
    private val closing = AtomicBoolean(false)
    private val tdlibExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RdChat-TDLib").apply { isDaemon = true }
    }

    private fun enqueueTdlib(action: () -> Unit) {
        if (closing.get()) return
        try {
            tdlibExecutor.execute {
                try { action() }
                catch (error: Throwable) {
                    Log.e("RDCHAT_TDLIB", "Falha em operação TDLib.", error)
                    onError(error.message ?: "Falha ao executar operação Telegram.")
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!closing.get()) Log.w("RDCHAT_TDLIB", "Executor TDLib indisponível.", error)
        }
    }

    fun restaurarSeModoTelefone() {
        enqueueTdlib { tg.start() }
    }

    fun registrarTokenFcm(token: String) {
        enqueueTdlib { tg.registrarTokenFcm(token) }
    }

    fun processarPushTelegram(payload: String) {
        enqueueTdlib { tg.processarPushNotification(payload) }
    }

    fun encerrar() {
        if (!closing.compareAndSet(false, true)) return
        try {
            tdlibExecutor.execute {
                try { tg.close() }
                finally { tdlibExecutor.shutdown() }
            }
        } catch (_: RejectedExecutionException) {
            tdlibExecutor.shutdownNow()
        }
    }

    private fun js(code: String) {
        web.post {
            try {
                web.evaluateJavascript(code, null)
            } catch (_: Exception) {
                // A página pode ter sido destruída durante o callback.
            }
        }
    }

    private fun quote(value: String?): String = JSONObject.quote(value ?: "")

    private fun fileSuffix(displayName: String, mime: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (displayName.lastIndexOf('.') > 0 && extension.matches(Regex("[a-z0-9]{1,8}"))) return ".$extension"
        return when (mime.substringBefore(';').trim().lowercase()) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            "audio/mp4" -> ".m4a"
            "audio/mpeg" -> ".mp3"
            "audio/ogg" -> ".ogg"
            "audio/aac" -> ".aac"
            "audio/wav", "audio/x-wav" -> ".wav"
            "application/pdf" -> ".pdf"
            else -> ".bin"
        }
    }

    @JavascriptInterface
    fun tdlibIniciarLogin(phone: String) = enqueueTdlib { tg.iniciarLogin(phone) }

    @JavascriptInterface
    fun tdlibEnviarCodigo(code: String) = enqueueTdlib { tg.enviarCodigo(code) }

    @JavascriptInterface
    fun tdlibEnviarSenha(password: String) = enqueueTdlib { tg.enviarSenha(password) }

    @JavascriptInterface
    fun tdlibDefinirCodigo(code6: String) = enqueueTdlib { tg.definirCodigo(code6) }

    @JavascriptInterface
    fun tdlibIniciarSeLogado() = restaurarSeModoTelefone()

    @JavascriptInterface
    fun enviarViaTelegram(json: String) {
        Thread({
            var temporaryFile: File? = null
            try {
                val data = JSONObject(json)
                val messageId = data.getString("msgId")
                val destinationsArray = data.getJSONArray("destinos")
                val destinations = List(destinationsArray.length()) { index ->
                    destinationsArray.getString(index)
                }
                val directory = File(ctx.cacheDir, "rdchat_out").apply {
                    if (!exists() && !mkdirs()) throw IllegalStateException("Não foi possível preparar o cache de envio.")
                    File(this, ".nomedia").createNewFile()
                }

                val localUri = data.optString("uriLocal")
                val dataUrl = data.optString("dataUrl")
                val mime = data.optString("mime")
                val displayName = data.optString("nome")
                val suffix = fileSuffix(displayName, mime)
                if (localUri.isNotBlank()) {
                    val uri = Uri.parse(localUri)
                    temporaryFile = File.createTempFile("out_", suffix, directory)
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("Não foi possível abrir o arquivo selecionado.")
                    input.use { source ->
                        temporaryFile!!.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > 2L * 1024L * 1024L * 1024L) {
                                    throw IllegalArgumentException("O arquivo ultrapassa o limite de 2 GB.")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } else if (dataUrl.isNotBlank()) {
                    if (dataUrl.length > 28_000_000) {
                        throw IllegalArgumentException("Arquivo Base64 acima do limite de 20 MB; selecione o arquivo por URI.")
                    }
                    val encoded = dataUrl.substringAfter("base64,", "")
                    if (encoded.isBlank()) throw IllegalArgumentException("Formato de arquivo Base64 inválido.")
                    temporaryFile = File.createTempFile("out_", suffix, directory)
                    temporaryFile!!.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
                }

                tg.enviar(
                    messageId,
                    destinations,
                    data.getString("envelope"),
                    temporaryFile,
                    data.optString("mime")
                )
                temporaryFile = null
            } catch (error: Exception) {
                temporaryFile?.delete()
                val messageId = try { JSONObject(json).optString("msgId") } catch (_: Exception) { "" }
                onSent(messageId, false, error.message)
            }
        }, "RdChat-Telegram-Send").start()
    }

    @JavascriptInterface
    fun tdlibBaixarArquivo(fileId: String, requestId: String) {
        val id = fileId.toIntOrNull()
        if (id == null) {
            onFile(requestId, null, "Identificador de arquivo Telegram inválido.")
            return
        }
        enqueueTdlib { tg.baixar(id, requestId) }
    }

    @JavascriptInterface
    fun tdlibIniciarChamada(username: String, video: Boolean) =
        enqueueTdlib { tg.iniciarChamada(username, video) }

    @JavascriptInterface
    fun tdlibAtenderChamada(callId: Int) = enqueueTdlib { tg.atenderChamada(callId) }

    @JavascriptInterface
    fun tdlibRecusarChamada(callId: Int) = enqueueTdlib { tg.recusarChamada(callId) }

    @JavascriptInterface
    fun tdlibEncerrarChamada(callId: Int) = enqueueTdlib { tg.encerrarChamada(callId) }

    @JavascriptInterface
    fun tdlibMutarChamada(callId: Int, muted: Boolean) =
        enqueueTdlib { tg.mutarChamada(callId, muted) }

    @JavascriptInterface
    fun tdlibCameraChamada(callId: Int, enabled: Boolean) =
        enqueueTdlib { tg.cameraChamada(callId, enabled) }

    @JavascriptInterface
    fun tdlibConfirmarRecebimento(chatId: String, messageId: String) {
        val chat = chatId.toLongOrNull()
        val message = messageId.toLongOrNull()
        if (chat != null && message != null) enqueueTdlib { tg.confirmarRecebimento(chat, message) }
    }

    override fun onAuthState(state: String) =
        js("window.RdChatTdlib&&window.RdChatTdlib.onAuthState(" + quote(state) + ")")

    override fun onReady(userId: Long, username: String, phoneHidden: Boolean) {
        val payload = JSONObject()
            .put("userId", userId.toString())
            .put("username", username)
            .put("phoneHidden", phoneHidden)
        js("window.RdChatTdlib&&window.RdChatTdlib.onReady(" + payload.toString() + ")")
    }

    override fun onError(message: String) =
        js("window.RdChatTdlib&&window.RdChatTdlib.onError(" + quote(message) + ")")

    override fun onMessage(json: String) =
        js("window.RdChatTdlib&&window.RdChatTdlib.onMessage(" + quote(json) + ")")

    override fun onFile(requestId: String, dataUrl: String?, error: String?) {
        js(
            "window.RdChatTdlib&&window.RdChatTdlib.onArquivo(" +
                quote(requestId) + "," + quote(dataUrl) + "," + (dataUrl != null) + "," + quote(error) + ")"
        )
    }

    override fun onSent(messageId: String, ok: Boolean, error: String?) =
        js("window.RdChatTdlib&&window.RdChatTdlib.onEnviado(" +
            quote(messageId) + "," + ok + "," + quote(error) + ")")

    override fun onCallEvent(json: String) =
        js("window.RdChatTdlib&&window.RdChatTdlib.onCallEvent(" + quote(json) + ")")

    override fun onCallFrame(json: String) =
        js("window.RdChatTdlib&&window.RdChatTdlib.onCallFrame(" + quote(json) + ")")
}
