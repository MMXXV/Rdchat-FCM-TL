package com.example.rdchat.telegram

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ponte TDLib complementar; MainActivity delega para ela sem substituir AndroidBridge. */
class WebAppInterface(
    context: Context,
    private val web: WebView,
    apiId: Int,
    apiHash: String
) : TelegramManager.Callbacks {

    private val ctx = context.applicationContext
    private val tg = TelegramManager(ctx, this, apiId, apiHash)

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

    @JavascriptInterface
    fun tdlibIniciarLogin(phone: String) = tg.iniciarLogin(phone)

    @JavascriptInterface
    fun tdlibEnviarCodigo(code: String) = tg.enviarCodigo(code)

    @JavascriptInterface
    fun tdlibEnviarSenha(password: String) = tg.enviarSenha(password)

    @JavascriptInterface
    fun tdlibDefinirCodigo(code6: String) = tg.definirCodigo(code6)

    @JavascriptInterface
    fun tdlibIniciarSeLogado() = tg.start()

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
                if (localUri.isNotBlank()) {
                    val uri = Uri.parse(localUri)
                    temporaryFile = File.createTempFile("out_", ".bin", directory)
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
                    temporaryFile = File.createTempFile("out_", ".bin", directory)
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
        tg.baixar(id, requestId)
    }

    @JavascriptInterface
    fun tdlibConfirmarRecebimento(chatId: String, messageId: String) {
        val chat = chatId.toLongOrNull()
        val message = messageId.toLongOrNull()
        if (chat != null && message != null) tg.confirmarRecebimento(chat, message)
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
}
