package com.example.rdchat.telegram

import android.content.Context
import android.util.Base64
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** TDLib embutida. Nunca chama ImportContacts. Número oculto logo após o login. */
class TelegramManager(private val ctx: Context, private val cb: Callbacks, private val apiHash: String) {

    interface Callbacks {
        fun onAuthState(state: String)
        fun onReady(userId: Long, username: String, phoneHidden: Boolean)
        fun onError(msg: String)
        fun onMessage(json: String)
        fun onFile(reqId: String, dataUrl: String?, err: String?)
        fun onSent(msgId: String, ok: Boolean, err: String?)
    }

    companion object {
        const val API_ID = 34968764

        private const val TAG = "RdTdlib"
        private const val MAX_DATAURL_BYTES = 25L * 1024 * 1024
    }

    private var client: Client? = null
    @Volatile var ready = false; private set
    private var codeForUsername = Random.nextInt(100000, 999999).toString()
    private val chatCache = ConcurrentHashMap<String, Long>()

    private val updates = Client.ResultHandler { obj ->
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuth(obj.authorizationState)
            is TdApi.UpdateNewMessage -> onNewMessage(obj.message)
        }
    }

    fun start() {
        if (client != null) return
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create(updates, { Log.w(TAG, "update exc", it) }, { Log.w(TAG, "exc", it) })
    }

    private fun send(f: TdApi.Function<*>, h: ((TdApi.Object) -> Unit)? = null) {
        client?.send(f) { r -> h?.invoke(r) } ?: cb.onError("TDLib não iniciada")
    }

    // ---------------- Autenticação ----------------
    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val p = TdApi.SetTdlibParameters()
                p.databaseDirectory = File(ctx.filesDir, "tdlib").absolutePath
                p.filesDirectory = File(ctx.filesDir, "tdlib_files").absolutePath   // privado: fora da galeria
                p.useFileDatabase = true; p.useChatInfoDatabase = true; p.useMessageDatabase = true
                p.useSecretChats = false
                p.apiId = API_ID; p.apiHash = apiHash
                p.systemLanguageCode = "pt-BR"; p.deviceModel = android.os.Build.MODEL
                p.systemVersion = android.os.Build.VERSION.RELEASE; p.applicationVersion = "1.0.6"
                send(p)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> cb.onAuthState("idle")
            is TdApi.AuthorizationStateWaitCode -> cb.onAuthState("aguardando_codigo")
            is TdApi.AuthorizationStateWaitPassword -> cb.onAuthState("aguardando_senha")
            is TdApi.AuthorizationStateReady -> { ready = true; afterLogin() }
            is TdApi.AuthorizationStateClosed -> { ready = false; client = null }
            else -> {}
        }
    }

    fun iniciarLogin(phone: String) = send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { e(it) }
    fun enviarCodigo(code: String) = send(TdApi.CheckAuthenticationCode(code)) { e(it) }
    fun enviarSenha(pwd: String) = send(TdApi.CheckAuthenticationPassword(pwd)) { e(it) }
    private fun e(r: TdApi.Object) { if (r is TdApi.Error) cb.onError(r.message) }

    /** Regra de privacidade obrigatória + usuário técnico + sem sincronizar agenda. */
    private fun afterLogin() {
        val restrictAll = TdApi.UserPrivacySettingRules(arrayOf<TdApi.UserPrivacySettingRule>(TdApi.UserPrivacySettingRuleRestrictAll()))
        var hidden = true
        send(TdApi.SetUserPrivacySettingRules(TdApi.UserPrivacySettingShowPhoneNumber(), restrictAll)) {
            if (it is TdApi.Error) { hidden = false; Log.w(TAG, "ShowPhoneNumber: ${it.message}") }
        }
        send(TdApi.SetUserPrivacySettingRules(TdApi.UserPrivacySettingAllowFindingByPhoneNumber(), restrictAll)) {
            if (it is TdApi.Error) {   // Telegram só aceita "Todos" ou "Meus contatos" aqui
                val contactsOnly = TdApi.UserPrivacySettingRules(arrayOf<TdApi.UserPrivacySettingRule>(TdApi.UserPrivacySettingRuleAllowContacts()))
                send(TdApi.SetUserPrivacySettingRules(TdApi.UserPrivacySettingAllowFindingByPhoneNumber(), contactsOnly))
            }
        }
        // NUNCA: TdApi.ImportContacts / sincronização da agenda.
        garantirUsername { username ->
            send(TdApi.GetMe()) { me ->
                if (me is TdApi.User) cb.onReady(me.id, username, hidden)
            }
        }
    }

    private fun garantirUsername(done: (String) -> Unit, tentativa: Int = 0) {
        send(TdApi.GetMe()) { me ->
            val atual = (me as? TdApi.User)?.usernames?.editableUsername.orEmpty()
            if (atual.startsWith("rdc_")) { done(atual); return@send }
            if (tentativa > 5) { cb.onError("Não foi possível criar o usuário técnico"); done(""); return@send }
            val nome = "rdc_${codeForUsername}_${Random.nextInt(1000, 9999).toString(36)}"
            send(TdApi.SetUsername(nome)) { r -> if (r is TdApi.Error) garantirUsername(done, tentativa + 1) else done(nome) }
        }
    }
    fun definirCodigo(code6: String) { codeForUsername = code6.filter { it.isDigit() }.take(6).ifEmpty { "000000" } }

    // ---------------- Envio ----------------
    /** envelope = "RDC1{json}". Texto vai como mensagem; mídia vai como documento com o envelope na legenda. */
    fun enviar(msgId: String, usernames: List<String>, envelope: String, arquivo: File?, mime: String) {
        if (!ready) { cb.onSent(msgId, false, "TDLib não está logada"); return }
        var restantes = usernames.size; var falhou: String? = null
        fun fim(err: String?) { if (err != null) falhou = err; if (--restantes == 0) { arquivo?.delete(); cb.onSent(msgId, falhou == null, falhou) } }
        if (usernames.isEmpty()) { cb.onSent(msgId, false, "sem destinatários"); return }
        usernames.forEach { u ->
            chatId(u) { id ->
                if (id == null) { fim("usuário não encontrado: $u"); return@chatId }
                val m = TdApi.SendMessage()
                m.chatId = id
                val legenda = TdApi.FormattedText(envelope, arrayOf())
                if (arquivo == null) {
                    val c = TdApi.InputMessageText(); c.text = legenda; m.inputMessageContent = c
                } else {
                    val c = TdApi.InputMessageDocument()
                    c.document = TdApi.InputFileLocal(arquivo.absolutePath); c.caption = legenda
                    m.inputMessageContent = c
                }
                send(m) { r -> fim(if (r is TdApi.Error) r.message else null) }
            }
        }
    }

    private fun chatId(username: String, r: (Long?) -> Unit) {
        chatCache[username]?.let { r(it); return }
        send(TdApi.SearchPublicChat(username)) { c ->
            if (c is TdApi.Chat) { chatCache[username] = c.id; r(c.id) } else r(null)
        }
    }

    // ---------------- Recebimento ----------------
    private fun onNewMessage(m: TdApi.Message) {
        if (m.isOutgoing) return
        val j = JSONObject().put("chatId", m.chatId).put("tgMessageId", m.id)
        when (val c = m.content) {
            is TdApi.MessageText -> j.put("text", c.text.text)
            is TdApi.MessageDocument -> {
                j.put("text", c.caption.text).put("fileId", c.document.document.id)
                    .put("fileName", c.document.fileName).put("mime", c.document.mimeType)
                    .put("size", c.document.document.size)
            }
            else -> return
        }
        cb.onMessage(j.toString())
    }

    fun baixar(fileId: Int, reqId: String) {
        send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { r ->
            if (r !is TdApi.File || !r.local.isDownloadingCompleted) { cb.onFile(reqId, null, (r as? TdApi.Error)?.message ?: "download incompleto"); return@send }
            val f = File(r.local.path)
            if (f.length() > MAX_DATAURL_BYTES) { cb.onFile(reqId, null, "arquivo grande: use o visualizador nativo"); return@send }
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            cb.onFile(reqId, "data:application/octet-stream;base64,$b64", null)
        }
    }

    /** Fila temporária: após gravar localmente, apaga da nuvem. */
    fun confirmarRecebimento(chatId: Long, msgId: Long) {
        send(TdApi.DeleteMessages(chatId, longArrayOf(msgId), true))
    }
}
