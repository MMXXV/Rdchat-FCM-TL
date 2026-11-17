package com.example.rdchat.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.rdchat.BuildConfig
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

class TelegramManager(
    context: Context,
    private val cb: Callbacks,
    private val apiId: Int,
    private val apiHash: String
) {
    interface Callbacks {
        fun onAuthState(state: String)
        fun onReady(userId: Long, username: String, phoneHidden: Boolean)
        fun onError(msg: String)
        fun onMessage(json: String)
        fun onFile(reqId: String, dataUrl: String?, err: String?)
        fun onSent(msgId: String, ok: Boolean, err: String?)
        fun onCallEvent(json: String)
        fun onCallFrame(json: String)
    }

    companion object {
        private const val TAG = "RdTdlib"
        private const val DB_KEY_ALIAS = "rdchat_tdlib_database_key_v1"
        private const val MAX_DATAURL_BYTES = 25L * 1024L * 1024L

        @Volatile private var nativeLibrariesLoaded = false
        private val nativeLibrariesLock = Any()
    }

    private val ctx = context.applicationContext
    private val initializationLock = Any()
    private val phoneLock = Any()
    @Volatile private var client: Client? = null
    @Volatile private var authState: TdApi.AuthorizationState? = null
    @Volatile private var pendingPhone: String? = null
    @Volatile var ready = false
        private set

    private var codeForUsername = Random.nextInt(100000, 999999).toString()
    private val chatCache = ConcurrentHashMap<String, Long>()
    private val fileMimeTypes = ConcurrentHashMap<Int, String>()
    @Volatile private var pendingTelegramFcmToken: String? = null
    private val loginFinalized = AtomicBoolean(false)
    @Volatile private var callEngine: TelegramCallEngine? = null

    private val updates = Client.ResultHandler { obj ->
        // Client.create() starts its receiver thread before returning. This lock ensures
        // the Client reference is assigned before the first authorization callback runs.
        synchronized(initializationLock) { }
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuth(obj.authorizationState)
            is TdApi.UpdateNewMessage -> onNewMessage(obj.message)
            is TdApi.UpdateCall -> callEngine?.handleCall(obj.call)
            is TdApi.UpdateNewCallSignalingData -> callEngine?.handleSignaling(obj)
        }
    }

    private fun configured(): Boolean =
        apiId > 0 && Regex("^[0-9a-fA-F]{32}$").matches(apiHash.trim())

    fun start(): Boolean {
        if (!configured()) return false
        if (client != null) return true

        synchronized(initializationLock) {
            if (client != null) return true
            try {
                loadNativeLibraries()
                Client.execute(TdApi.SetLogVerbosityLevel(1))
                client = Client.create(
                    updates,
                    { error -> Log.w(TAG, "Exceção no callback TDLib.", error) },
                    { error -> Log.w(TAG, "Exceção interna TDLib.", error) }
                )
                return true
            } catch (error: Throwable) {
                Log.e(TAG, "Não foi possível iniciar a biblioteca TDLib.", error)
                cb.onError(
                    if (error is UnsatisfiedLinkError) {
                        "Biblioteca TDLib ausente ou incompatível com a arquitetura deste aparelho."
                    } else {
                        "Não foi possível iniciar o Telegram: " + (error.message ?: "erro interno")
                    }
                )
                return false
            }
        }
    }

    private fun loadNativeLibraries() {
        if (nativeLibrariesLoaded) return
        synchronized(nativeLibrariesLock) {
            if (nativeLibrariesLoaded) return
            System.loadLibrary("cryptox")
            System.loadLibrary("sslx")
            System.loadLibrary("tdjni")
            nativeLibrariesLoaded = true
        }
    }

    /** Fecha o cliente local, preservando a autorização gravada no banco TDLib. */
    fun close() {
        ready = false
        callEngine?.close()
        callEngine = null
        synchronized(phoneLock) { pendingPhone = null }
        if (client != null) send(TdApi.Close())
    }

    private fun send(function: TdApi.Function<*>, handler: ((TdApi.Object) -> Unit)? = null) {
        val current = client
        if (current == null) {
            cb.onError("A sessão Telegram ainda não foi inicializada.")
            return
        }
        try {
            current.send(function) { result ->
                if (result is TdApi.Error) {
                    if (handler != null) handler(result) else cb.onError(result.message)
                } else {
                    handler?.invoke(result)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Falha ao enviar operação TDLib.", error)
            cb.onError(error.message ?: "Falha na operação Telegram.")
        }
    }

    private fun onAuth(state: TdApi.AuthorizationState) {
        authState = state
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                try {
                    val parameters = TdApi.SetTdlibParameters()
                    parameters.databaseDirectory = File(ctx.filesDir, "tdlib").absolutePath
                    parameters.filesDirectory = File(ctx.filesDir, "tdlib_files").absolutePath
                    parameters.useFileDatabase = true
                    parameters.useChatInfoDatabase = true
                    parameters.useMessageDatabase = true
                    parameters.useSecretChats = false
                    parameters.apiId = apiId
                    parameters.apiHash = apiHash.trim()
                    parameters.systemLanguageCode = "pt-BR"
                    parameters.deviceModel = android.os.Build.MODEL
                    parameters.systemVersion = android.os.Build.VERSION.RELEASE
                    parameters.applicationVersion = "1.0.7"
                    parameters.databaseEncryptionKey = loadOrCreateDatabaseKey()
                    send(parameters) { result ->
                        if (result is TdApi.Error) cb.onError("Falha ao configurar o banco Telegram: " + result.message)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Falha ao preparar banco local TDLib.", error)
                    cb.onError(error.message ?: "Não foi possível preparar o banco local do Telegram.")
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                cb.onAuthState("idle")
                requestPendingPhoneIfReady()
            }
            is TdApi.AuthorizationStateWaitCode -> cb.onAuthState("aguardando_codigo")
            is TdApi.AuthorizationStateWaitPassword -> cb.onAuthState("aguardando_senha")
            is TdApi.AuthorizationStateReady -> {
                ready = true
                if (callEngine == null) {
                    try {
                        callEngine = TelegramCallEngine(
                            ctx,
                            { function, handler -> send(function, handler) },
                            { payload -> cb.onCallEvent(payload) },
                            { payload -> cb.onCallFrame(payload) }
                        )
                    } catch (error: Throwable) {
                        Log.e(TAG, "Motor de chamadas Telegram não pôde ser carregado.", error)
                        cb.onError("Chamadas Telegram não disponíveis neste aparelho: " + (error.message ?: "falha ao carregar o motor de mídia."))
                    }
                }
                pendingTelegramFcmToken?.takeIf { it.isNotBlank() }?.let(::registerFcmDevice)
                if (loginFinalized.compareAndSet(false, true)) afterLogin()
            }
            is TdApi.AuthorizationStateClosed -> {
                ready = false
                authState = null
                synchronized(initializationLock) { client = null }
                cb.onAuthState("fechado")
            }
            else -> cb.onAuthState("inicializando")
        }
    }

    fun iniciarLogin(phone: String) {
        if (!configured()) {
            cb.onError("Login com número ainda não configurado. Configure TELEGRAM_API_ID e TELEGRAM_API_HASH no build do APK.")
            return
        }
        val normalized = phone.trim().filter { it.isDigit() || it == '+' }
        if (!Regex("^\\+[1-9][0-9]{7,14}$").matches(normalized)) {
            cb.onError("Digite o número no formato internacional, incluindo o DDI. Exemplo: +55 11 99999-9999.")
            return
        }
        loginFinalized.set(false)
        synchronized(phoneLock) { pendingPhone = normalized }
        if (!start()) return
        requestPendingPhoneIfReady()
    }

    private fun requestPendingPhoneIfReady() {
        val phone = synchronized(phoneLock) {
            if (authState is TdApi.AuthorizationStateWaitPhoneNumber) {
                pendingPhone.also { pendingPhone = null }
            } else null
        }
        if (!phone.isNullOrBlank()) {
            send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { result ->
                if (result is TdApi.Error) cb.onError(result.message)
            }
        }
    }

    fun enviarCodigo(code: String) {
        if (!configured()) {
            cb.onError("Integração Telegram não configurada neste APK.")
            return
        }
        if (start()) {
            send(TdApi.CheckAuthenticationCode(code.trim())) { result ->
                if (result is TdApi.Error) cb.onError(result.message)
            }
        }
    }

    fun enviarSenha(password: String) {
        if (!configured()) {
            cb.onError("Integração Telegram não configurada neste APK.")
            return
        }
        if (start()) {
            send(TdApi.CheckAuthenticationPassword(password)) { result ->
                if (result is TdApi.Error) cb.onError(result.message)
            }
        }
    }

    private fun loadOrCreateDatabaseKey(): ByteArray {
        val prefs = ctx.getSharedPreferences("rdchat_tdlib_secure", Context.MODE_PRIVATE)
        val encrypted = prefs.getString("database_key_cipher", null)
        val ivStored = prefs.getString("database_key_iv", null)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var masterKey = keyStore.getKey(DB_KEY_ALIAS, null) as? SecretKey
        if (masterKey == null) {
            if (!encrypted.isNullOrBlank() || !ivStored.isNullOrBlank()) {
                throw IllegalStateException("A chave protegida do banco Telegram não está disponível neste aparelho.")
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    DB_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            masterKey = generator.generateKey()
        }

        if (!encrypted.isNullOrBlank() && !ivStored.isNullOrBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey,
                GCMParameterSpec(128, Base64.decode(ivStored, Base64.DEFAULT))
            )
            return cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT))
        }

        val databaseKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val ciphertext = cipher.doFinal(databaseKey)
        val saved = prefs.edit()
            .putString("database_key_cipher", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("database_key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        if (!saved) throw IllegalStateException("Não foi possível salvar a chave protegida do Telegram.")
        return databaseKey
    }

    private fun afterLogin() {
        val completed = AtomicInteger(0)
        val phoneHidden = AtomicBoolean(false)
        val phoneDiscoveryBlocked = AtomicBoolean(false)

        fun finishPrivacyStep() {
            if (completed.incrementAndGet() == 2) {
                garantirUsername({ username ->
                    send(TdApi.GetMe()) { me ->
                        if (me is TdApi.User) {
                            // Só informa privacidade confirmada quando as duas regras foram aceitas.
                            cb.onReady(me.id, username, phoneHidden.get() && phoneDiscoveryBlocked.get())
                        } else if (me is TdApi.Error) {
                            cb.onError(me.message)
                        }
                    }
                })
            }
        }

        val restrictAll = TdApi.UserPrivacySettingRules(
            arrayOf<TdApi.UserPrivacySettingRule>(TdApi.UserPrivacySettingRuleRestrictAll())
        )
        send(TdApi.SetUserPrivacySettingRules(TdApi.UserPrivacySettingShowPhoneNumber(), restrictAll)) { result ->
            if (result is TdApi.Error) {
                Log.w(TAG, "O Telegram não confirmou ocultação do número: " + result.message)
            } else {
                phoneHidden.set(true)
            }
            finishPrivacyStep()
        }

        val findingByPhone = TdApi.UserPrivacySettingAllowFindingByPhoneNumber()
        // Não relaxar para "somente contatos": a promessa do RdChat é não ser localizável pelo número.
        send(TdApi.SetUserPrivacySettingRules(findingByPhone, restrictAll)) { result ->
            if (result is TdApi.Error) {
                Log.w(TAG, "O Telegram não confirmou bloqueio de descoberta por telefone: " + result.message)
            } else {
                phoneDiscoveryBlocked.set(true)
            }
            finishPrivacyStep()
        }
    }

    private fun garantirUsername(done: (String) -> Unit, tentativa: Int = 0) {
        send(TdApi.GetMe()) { me ->
            val current = (me as? TdApi.User)?.usernames?.editableUsername.orEmpty()
            // Preserve an existing username; create a technical one only when the user has none.
            if (current.isNotBlank()) {
                done(current)
                return@send
            }
            if (tentativa >= 5) {
                cb.onError("Não foi possível criar o usuário técnico do Telegram.")
                done("")
                return@send
            }
            val generated = "rdc_" + codeForUsername + "_" + Random.nextInt(1000, 9999).toString(36)
            send(TdApi.SetUsername(generated)) { result ->
                if (result is TdApi.Error) garantirUsername(done, tentativa + 1) else done(generated)
            }
        }
    }

    fun definirCodigo(code6: String) {
        codeForUsername = code6.filter { it.isDigit() }.take(6).ifEmpty { "000000" }
    }

    /** Mantém o token FCM também registrado no serviço de notificações do Telegram para contas do Modo B. */
    fun iniciarChamada(username: String, video: Boolean) {
        if (!ready) {
            cb.onError("Entre na conta Telegram antes de iniciar uma chamada.")
            return
        }
        val engine = callEngine
        if (engine == null) {
            cb.onError("O motor nativo de chamadas não está disponível neste aparelho.")
            return
        }
        try { engine.startOutgoing(username, video) }
        catch (error: Throwable) {
            Log.e(TAG, "Falha ao iniciar chamada Telegram.", error)
            cb.onError(error.message ?: "Não foi possível iniciar a chamada.")
        }
    }

    fun atenderChamada(callId: Int) = callEngine?.accept(callId)
    fun recusarChamada(callId: Int) = callEngine?.decline(callId)
    fun encerrarChamada(callId: Int) = callEngine?.hangup(callId)
    fun mutarChamada(callId: Int, muted: Boolean) = callEngine?.setMuted(callId, muted)
    fun cameraChamada(callId: Int, enabled: Boolean) = callEngine?.setCameraEnabled(callId, enabled)

    fun registrarTokenFcm(token: String) {
        val value = token.trim()
        if (value.isBlank()) return
        pendingTelegramFcmToken = value
        if (ready) registerFcmDevice(value)
    }

    private fun registerFcmDevice(token: String) {
        if (!ready || token.isBlank()) return
        val prefs = ctx.getSharedPreferences("rdchat_tdlib_push", Context.MODE_PRIVATE)
        if (prefs.getString("registered_token", null) == token) return
        val deviceToken = TdApi.DeviceTokenFirebaseCloudMessaging(token, false)
        send(TdApi.RegisterDevice(deviceToken, longArrayOf())) { result ->
            if (result is TdApi.PushReceiverId) {
                prefs.edit()
                    .putString("registered_token", token)
                    .putLong("push_receiver_id", result.id)
                    .apply()
                Log.i(TAG, "Dispositivo registrado para notificações Telegram/FCM.")
            } else if (result is TdApi.Error) {
                Log.w(TAG, "Não foi possível registrar o token FCM na conta Telegram: ${result.message}")
            }
        }
    }

    /** Entrega ao TDLib o payload Telegram recebido pelo FirebaseMessagingService. */
    fun processarPushNotification(payload: String) {
        val value = payload.trim()
        if (value.isBlank()) return
        if (client == null && !start()) return
        send(TdApi.ProcessPushNotification(value)) { result ->
            if (result is TdApi.Error) {
                Log.w(TAG, "TDLib não conseguiu processar o push Telegram (${result.code}): ${result.message}")
            }
        }
    }

    private fun inputMessageFor(envelope: String, mime: String, file: File?): TdApi.InputMessageContent {
        val caption = TdApi.FormattedText(envelope, emptyArray())
        if (file == null) return TdApi.InputMessageText().apply { text = caption }

        val kind = try {
            JSONObject(envelope.removePrefix("RDC1")).optString("kind").lowercase()
        } catch (_: Exception) { "file" }
        val normalizedMime = mime.trim().lowercase()
        val inputFile = TdApi.InputFileLocal(file.absolutePath)

        return when {
            (kind == "image" || normalizedMime.startsWith("image/")) &&
                normalizedMime != "image/gif" && file.length() <= 10L * 1024L * 1024L -> {
                val photo = TdApi.InputPhoto().apply { this.photo = inputFile }
                TdApi.InputMessagePhoto().apply { this.photo = photo; this.caption = caption }
            }
            kind == "video" || normalizedMime.startsWith("video/") -> {
                val video = TdApi.InputVideo().apply {
                    this.video = inputFile
                    supportsStreaming = true
                }
                TdApi.InputMessageVideo().apply { this.video = video; this.caption = caption }
            }
            kind == "audio" || normalizedMime.startsWith("audio/") -> {
                val audio = TdApi.InputAudio().apply {
                    this.audio = inputFile
                    title = file.name.take(64)
                }
                TdApi.InputMessageAudio().apply { this.audio = audio; this.caption = caption }
            }
            else -> {
                val document = TdApi.InputDocument().apply {
                    this.document = inputFile
                    disableContentTypeDetection = false
                }
                TdApi.InputMessageDocument().apply { this.document = document; this.caption = caption }
            }
        }
    }

    fun enviar(msgId: String, usernames: List<String>, envelope: String, arquivo: File?, mime: String) {
        if (!ready) {
            arquivo?.delete()
            cb.onSent(msgId, false, "Telegram ainda não está autenticado.")
            return
        }
        if (usernames.isEmpty()) {
            arquivo?.delete()
            cb.onSent(msgId, false, "Nenhum destinatário Telegram válido.")
            return
        }
        var remaining = usernames.size
        var failure: String? = null
        fun finished(error: String?) {
            if (error != null) failure = error
            if (--remaining == 0) {
                arquivo?.delete()
                cb.onSent(msgId, failure == null, failure)
            }
        }
        usernames.forEach { username ->
            chatId(username) { id ->
                if (id == null) {
                    finished("Usuário Telegram não encontrado: " + username)
                    return@chatId
                }
                val message = TdApi.SendMessage()
                message.chatId = id
                message.inputMessageContent = inputMessageFor(envelope, mime, arquivo)
                send(message) { result ->
                    finished((result as? TdApi.Error)?.message)
                }
            }
        }
    }

    private fun chatId(username: String, callback: (Long?) -> Unit) {
        chatCache[username]?.let {
            callback(it)
            return
        }
        send(TdApi.SearchPublicChat(username)) { chat ->
            if (chat is TdApi.Chat) {
                chatCache[username] = chat.id
                callback(chat.id)
            } else {
                callback(null)
            }
        }
    }

    private fun onNewMessage(message: TdApi.Message) {
        if (message.isOutgoing) return
        val payload = JSONObject()
            .put("chatId", message.chatId)
            .put("tgMessageId", message.id)
        var fileId = 0
        var mime = ""
        var fileName = ""
        var size = 0L
        when (val content = message.content) {
            is TdApi.MessageText -> payload.put("text", content.text.text)
            is TdApi.MessageDocument -> {
                payload.put("text", content.caption.text)
                fileId = content.document.document.id
                mime = content.document.mimeType
                fileName = content.document.fileName
                size = content.document.document.size
            }
            is TdApi.MessagePhoto -> {
                payload.put("text", content.caption.text)
                val file = content.photo.sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }?.photo
                if (file != null) { fileId = file.id; size = file.size.toLong() }
                mime = "image/jpeg"
                fileName = "foto.jpg"
            }
            is TdApi.MessageVideo -> {
                payload.put("text", content.caption.text)
                fileId = content.video.video.id
                mime = content.video.mimeType
                fileName = content.video.fileName
                size = content.video.video.size.toLong()
            }
            is TdApi.MessageAudio -> {
                payload.put("text", content.caption.text)
                fileId = content.audio.audio.id
                mime = content.audio.mimeType
                fileName = content.audio.fileName
                size = content.audio.audio.size.toLong()
            }
            is TdApi.MessageVoiceNote -> {
                payload.put("text", content.caption.text)
                fileId = content.voiceNote.voice.id
                mime = "audio/ogg"
                fileName = "mensagem-de-voz.ogg"
                size = content.voiceNote.voice.size.toLong()
            }
            is TdApi.MessageAnimation -> {
                payload.put("text", content.caption.text)
                fileId = content.animation.animation.id
                mime = content.animation.mimeType
                fileName = "animacao"
                size = content.animation.animation.size.toLong()
            }
            else -> return
        }
        if (fileId != 0) {
            if (mime.isNotBlank()) fileMimeTypes[fileId] = mime
            payload.put("fileId", fileId)
                .put("fileName", fileName)
                .put("mime", mime)
                .put("size", size)
        }
        cb.onMessage(payload.toString())
    }

    fun baixar(fileId: Int, requestId: String) {
        if (!ready) {
            cb.onFile(requestId, null, "Telegram não está autenticado.")
            return
        }
        send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { result ->
            if (result !is TdApi.File || !result.local.isDownloadingCompleted) {
                cb.onFile(requestId, null, (result as? TdApi.Error)?.message ?: "Download Telegram incompleto.")
                return@send
            }
            try {
                val file = File(result.local.path)
                if (!file.isFile || file.length() > MAX_DATAURL_BYTES) {
                    cb.onFile(requestId, null, "Arquivo maior que o limite do visualizador interno.")
                    return@send
                }
                val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val mime = fileMimeTypes[fileId]?.takeIf { it.matches(Regex("^[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+$")) }
                    ?: "application/octet-stream"
                cb.onFile(requestId, "data:$mime;base64," + encoded, null)
            } catch (error: Exception) {
                cb.onFile(requestId, null, error.message ?: "Falha ao ler arquivo Telegram.")
            }
        }
    }

    /** Called only after HTML accepts and persists a valid RDC1 envelope. */
    fun confirmarRecebimento(chatId: Long, messageId: Long) {
        if (!ready) return
        send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true))
    }
}
