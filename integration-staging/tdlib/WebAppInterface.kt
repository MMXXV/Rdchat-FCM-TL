package com.example.rdchat.telegram

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ponte HTML <-> Android. addJavascriptInterface(WebAppInterface(...), "AndroidBridge") */
class WebAppInterface(private val ctx: Context, private val web: WebView, apiHash: String) : TelegramManager.Callbacks {

    private val tg = TelegramManager(ctx, this, apiHash)
    private fun js(code: String) = web.post { web.evaluateJavascript(code, null) }
    private fun q(s: String?) = JSONObject.quote(s ?: "")

    // ----- Login Modo B (TDLib) -----
    @JavascriptInterface fun tdlibIniciarLogin(phone: String) { tg.start(); tg.iniciarLogin(phone) }
    @JavascriptInterface fun tdlibEnviarCodigo(code: String) = tg.enviarCodigo(code)
    @JavascriptInterface fun tdlibEnviarSenha(pwd: String) = tg.enviarSenha(pwd)
    @JavascriptInterface fun tdlibDefinirCodigo(code6: String) = tg.definirCodigo(code6)
    @JavascriptInterface fun tdlibIniciarSeLogado() { tg.start() }            // chame ao abrir o app

    // ----- Cenário 2: envio pela TDLib (texto, áudio, foto, doc, vídeo até 2 GB) -----
    // json: {msgId, destinos:[username], envelope:"RDC1{...}", dataUrl:"data:..;base64,..", uriLocal:"content://..", mime, nome}
    @JavascriptInterface fun enviarViaTelegram(json: String) {
        Thread {
            val j = JSONObject(json); val msgId = j.getString("msgId")
            try {
                val dests = JSONArray(j.getJSONArray("destinos").toString()).let { a -> List(a.length()) { a.getString(it) } }
                var arquivo: File? = null
                val dir = File(ctx.cacheDir, "rdchat_out").apply { mkdirs(); File(this, ".nomedia").createNewFile() }   // privado, sem galeria
                val uri = j.optString("uriLocal"); val data = j.optString("dataUrl")
                if (uri.isNotEmpty()) {                                    // arquivo grande: copia em stream, sem base64
                    arquivo = File.createTempFile("out_", "_" + j.optString("nome", "arq").replace(Regex("[^A-Za-z0-9._-]"), "_"), dir)
                    ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { i -> arquivo.outputStream().use { o -> i.copyTo(o) } }
                } else if (data.isNotEmpty()) {
                    arquivo = File.createTempFile("out_", "_" + j.optString("nome", "arq").replace(Regex("[^A-Za-z0-9._-]"), "_"), dir)
                    arquivo.writeBytes(Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT))
                }
                tg.enviar(msgId, dests, j.getString("envelope"), arquivo, j.optString("mime"))
            } catch (e: Exception) { onSent(msgId, false, e.message) }
        }.start()
    }
    @JavascriptInterface fun tdlibBaixarArquivo(fileId: String, reqId: String) = tg.baixar(fileId.toInt(), reqId)
    @JavascriptInterface fun tdlibConfirmarRecebimento(chatId: String, msgId: String) = tg.confirmarRecebimento(chatId.toLong(), msgId.toLong())

    // ----- Callbacks nativo -> JS -----
    override fun onAuthState(state: String) = js("window.RdChatTdlib&&RdChatTdlib.onAuthState(${q(state)})")
    override fun onReady(userId: Long, username: String, phoneHidden: Boolean) =
        js("window.RdChatTdlib&&RdChatTdlib.onReady(${JSONObject().put("userId", userId.toString()).put("username", username).put("phoneHidden", phoneHidden)})")
    override fun onError(msg: String) = js("window.RdChatTdlib&&RdChatTdlib.onError(${q(msg)})")
    override fun onMessage(json: String) = js("window.RdChatTdlib&&RdChatTdlib.onMessage(${q(json)})")
    override fun onFile(reqId: String, dataUrl: String?, err: String?) =
        js("window.RdChatTdlib&&RdChatTdlib.onArquivo(${q(reqId)},${q(dataUrl)},${dataUrl != null},${q(err)})")
    override fun onSent(msgId: String, ok: Boolean, err: String?) =
        js("window.RdChatTdlib&&RdChatTdlib.onEnviado(${q(msgId)},$ok,${q(err)})")
}
