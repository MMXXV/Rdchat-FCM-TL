package com.example.rdchat.telegram

import android.content.Context
import android.util.Base64
import android.util.Log
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TDLib call signaling + NTgCalls native WebRTC media adapter.
 * NTgCalls uses reflection because its Android Java bindings are generated.
 */
internal class TelegramCallEngine(
    context: Context,
    private val sendTd: (TdApi.Function<*>, ((TdApi.Object) -> Unit)?) -> Unit,
    private val event: (String) -> Unit,
    private val frame: (String) -> Unit
) {
    companion object { private const val TAG = "RdTelegramCalls" }

    private data class ActiveCall(
        val callId: Int,
        val userId: Long,
        val isVideo: Boolean,
        val isOutgoing: Boolean,
        var username: String = "",
        var connected: Boolean = false
    )

    private val appContext = context.applicationContext
    private val callsById = ConcurrentHashMap<Int, ActiveCall>()
    private val callsByUser = ConcurrentHashMap<Long, ActiveCall>()
    private val frameTimes = ConcurrentHashMap<Long, Long>()
    private val closed = AtomicBoolean(false)
    private val nativeClass = Class.forName("io.github.pytgcalls.NTgCalls")
    private val native: Any = nativeClass.getDeclaredConstructor().newInstance()
    @Volatile private var foregroundServiceStarted = false

    init { installNativeCallbacks() }

    fun startOutgoing(username: String, video: Boolean) {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) {
            emit("error", message = "O contato não tem um usuário Telegram válido.")
            return
        }
        sendTd(TdApi.SearchPublicChat(clean)) { result ->
            val chat = result as? TdApi.Chat
            val privateType = chat?.type as? TdApi.ChatTypePrivate
            if (privateType == null) {
                emit("error", message = "Não foi possível localizar o contato no Telegram.")
                return@sendTd
            }
            sendTd(TdApi.CreateCall(privateType.userId, localTdProtocol(), video)) { created ->
                val id = (created as? TdApi.CallId)?.id
                if (id == null) {
                    emit("error", message = (created as? TdApi.Error)?.message ?: "O Telegram não criou a chamada.")
                    return@sendTd
                }
                val active = ActiveCall(id, privateType.userId, video, true, clean)
                callsById[id] = active
                callsByUser[privateType.userId] = active
                startForegroundCall(active)
                emit("outgoing", active)
            }
        }
    }

    fun accept(callId: Int) {
        val active = callsById[callId]
        if (active == null) {
            emit("error", message = "A chamada recebida não está mais disponível.")
            return
        }
        startForegroundCall(active)
        sendTd(TdApi.AcceptCall(callId, localTdProtocol())) { result ->
            if (result is TdApi.Error) emit("error", active, result.message)
        }
    }

    fun decline(callId: Int) = discard(callId, true)
    fun hangup(callId: Int) = discard(callId, false)

    private fun discard(callId: Int, disconnected: Boolean) {
        val active = callsById[callId] ?: return
        sendTd(TdApi.DiscardCall(callId, disconnected, "", 0, active.isVideo, 0L)) { result ->
            if (result is TdApi.Error) emit("error", active, result.message)
            stopNative(active.userId)
        }
    }

    fun setMuted(callId: Int, muted: Boolean) {
        val active = callsById[callId] ?: return
        invokeNative(if (muted) "mute" else "unmute", active.userId)
    }

    fun setCameraEnabled(callId: Int, enabled: Boolean) {
        val active = callsById[callId] ?: return
        if (!active.isVideo && enabled) {
            emit("error", active, "A chamada foi iniciada sem vídeo. Inicie uma chamada de vídeo para usar a câmera.")
            return
        }
        try { setMediaSources(active, includeVideo = enabled) }
        catch (error: Throwable) {
            Log.e(TAG, "Não foi possível alterar a câmera.", error)
            emit("error", active, error.message ?: "Não foi possível alterar a câmera.")
        }
    }

    fun handleCall(call: TdApi.Call) {
        val active = callsById[call.id] ?: ActiveCall(
            call.id, call.userId, call.isVideo, call.isOutgoing
        ).also {
            callsById[call.id] = it
            callsByUser[call.userId] = it
        }
        when (val state = call.state) {
            is TdApi.CallStatePending -> {
                if (!call.isOutgoing && state.isReceived) {
                    sendTd(TdApi.GetUser(call.userId)) { result ->
                        val user = result as? TdApi.User
                        val username = user?.usernames?.editableUsername.orEmpty()
                        active.username = username
                        emit("incoming", active)
                    }
                } else emit("ringing", active)
            }
            is TdApi.CallStateReady -> {
                try {
                    connectNative(active, state)
                    emit("connecting", active)
                } catch (error: Throwable) {
                    Log.e(TAG, "Falha ao preparar mídia da chamada.", error)
                    emit("error", active, error.message ?: "Não foi possível iniciar o áudio/vídeo.")
                    discard(call.id, true)
                }
            }
            is TdApi.CallStateDiscarded -> {
                stopNative(call.userId)
                callsById.remove(call.id)
                callsByUser.remove(call.userId)
                frameTimes.remove(call.userId)
                emit("ended", active)
            }
            else -> emit("state", active, state.javaClass.simpleName)
        }
    }

    fun handleSignaling(update: TdApi.UpdateNewCallSignalingData) {
        val active = callsById[update.callId] ?: return
        try { invokeNative("sendSignalingData", active.userId, update.data) }
        catch (error: Throwable) { Log.e(TAG, "Falha ao entregar sinalização ao motor de chamadas.", error) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        callsByUser.keys.toList().forEach(::stopNative)
        callsById.clear()
        callsByUser.clear()
        frameTimes.clear()
        stopForegroundCall()
    }

    private fun startForegroundCall(active: ActiveCall) {
        try {
            TelegramCallForegroundService.start(appContext, active.isVideo)
            foregroundServiceStarted = true
        } catch (error: Throwable) {
            Log.e(TAG, "Não foi possível manter a chamada em primeiro plano.", error)
            emit("error", active, error.message ?: "O Android não permitiu iniciar o serviço de chamada.")
        }
    }

    private fun stopForegroundCall() {
        if (!foregroundServiceStarted) return
        try { TelegramCallForegroundService.stop(appContext) }
        catch (error: Throwable) { Log.w(TAG, "Falha ao finalizar o serviço de chamada.", error) }
        foregroundServiceStarted = false
    }

    private fun localTdProtocol(): TdApi.CallProtocol {
        val protocol = nativeClass.getMethod("getProtocol").invoke(null)
        return TdApi.CallProtocol(
            booleanField(protocol, "udp_p2p", "udpP2p"),
            booleanField(protocol, "udp_reflector", "udpReflector"),
            intField(protocol, "min_layer", "minLayer"),
            intField(protocol, "max_layer", "maxLayer"),
            stringArrayField(protocol, "library_versions", "libraryVersions")
        )
    }

    private fun connectNative(active: ActiveCall, state: TdApi.CallStateReady) {
        startForegroundCall(active)
        invokeNative("createP2pCall", active.userId)
        setMediaSources(active, includeVideo = active.isVideo)
        invokeNative("skipExchange", active.userId, state.encryptionKey, active.isOutgoing)
        val rtcServers = state.servers.mapNotNull(::toRtcServer)
        invokeNative(
            "connectP2p", active.userId, rtcServers,
            state.protocol.libraryVersions.toList(), state.allowP2p, state.customParameters
        )
    }

    private fun setMediaSources(active: ActiveCall, includeVideo: Boolean) {
        val devices = nativeClass.getMethod("getMediaDevices").invoke(null)
        val mic = firstMetadata(devices, "microphone")
        val speaker = firstMetadata(devices, "speaker")
        val camera = firstMetadata(devices, "camera")

        val audioType = Class.forName("io.github.pytgcalls.media.AudioDescription")
        val audioCtor = audioType.constructors.first { it.parameterCount == 5 }
        val mediaSourceType = audioCtor.parameterTypes[0]
        val deviceSource = enumValue(mediaSourceType, "DEVICE")
        val micDesc = audioCtor.newInstance(deviceSource, 48000, 1, mic, true)
        val speakerDesc = audioCtor.newInstance(deviceSource, 48000, 2, speaker, true)

        val videoDesc = if (includeVideo && active.isVideo && camera.isNotBlank()) {
            val videoType = Class.forName("io.github.pytgcalls.media.VideoDescription")
            val videoCtor = videoType.constructors.first { it.parameterCount == 6 }
            videoCtor.newInstance(enumValue(videoCtor.parameterTypes[0], "DEVICE"), 640, 480, 24, camera, true)
        } else null

        val descriptionType = Class.forName("io.github.pytgcalls.media.MediaDescription")
        val descriptionCtor = descriptionType.constructors.first { it.parameterCount == 4 }
        val capture = descriptionCtor.newInstance(micDesc, null, videoDesc, null)
        val playback = descriptionCtor.newInstance(null, speakerDesc, null, null)

        val setSources = nativeClass.methods.first { it.name == "setStreamSources" && it.parameterCount == 3 }
        val modeType = setSources.parameterTypes[1]
        setSources.invoke(native, active.userId, enumValue(modeType, "CAPTURE"), capture)
        setSources.invoke(native, active.userId, enumValue(modeType, "PLAYBACK"), playback)
    }

    private fun toRtcServer(server: TdApi.CallServer): Any? {
        val type = server.type
        val webRtc = type as? TdApi.CallServerTypeWebrtc
        val reflector = type as? TdApi.CallServerTypeTelegramReflector
        if (webRtc == null && reflector == null) return null
        val rtcType = Class.forName("io.github.pytgcalls.p2p.RTCServer")
        val ctor = rtcType.constructors.first { it.parameterCount == 10 }
        return ctor.newInstance(
            server.id, server.ipAddress, server.ipv6Address, server.port,
            webRtc?.username, webRtc?.password,
            webRtc?.supportsTurn ?: (reflector != null),
            webRtc?.supportsStun ?: false,
            reflector?.isTcp ?: false,
            reflector?.peerTag
        )
    }

    private fun installNativeCallbacks() {
        installCallback("onSignalingData") { args ->
            val userId = (args.getOrNull(0) as? Number)?.toLong() ?: return@installCallback
            val data = args.getOrNull(1) as? ByteArray ?: return@installCallback
            val active = callsByUser[userId] ?: return@installCallback
            sendTd(TdApi.SendCallSignalingData(active.callId, data), null)
        }
        installCallback("onConnectionChange") { args ->
            val userId = (args.getOrNull(0) as? Number)?.toLong() ?: return@installCallback
            val info = args.getOrNull(1) ?: return@installCallback
            val state = readField(info, "state")?.toString().orEmpty().uppercase()
            val active = callsByUser[userId] ?: return@installCallback
            if (state.contains("CONNECTED")) {
                active.connected = true
                emit("connected", active)
            } else if (state.contains("FAILED") || state.contains("TIMEOUT")) {
                emit("error", active, "A conexão da chamada falhou ($state).")
            } else if (state.contains("CLOSED")) emit("ended", active)
        }
        installCallback("onFrames") { args ->
            val userId = (args.getOrNull(0) as? Number)?.toLong() ?: return@installCallback
            val mode = args.getOrNull(1)?.toString()?.uppercase().orEmpty()
            val device = args.getOrNull(2)?.toString()?.uppercase().orEmpty()
            if (!mode.contains("PLAYBACK") || !(device.contains("CAMERA") || device.contains("SCREEN"))) return@installCallback
            val now = System.currentTimeMillis()
            if (now - (frameTimes[userId] ?: 0L) < 100L) return@installCallback
            frameTimes[userId] = now
            val frames = args.getOrNull(3) as? List<*> ?: return@installCallback
            val sample = frames.lastOrNull() ?: return@installCallback
            val bytes = readField(sample, "data") as? ByteArray ?: return@installCallback
            val metadata = readField(sample, "frame_data") ?: return@installCallback
            val width = intField(metadata, "width")
            val height = intField(metadata, "height")
            if (width <= 0 || height <= 0 || bytes.isEmpty()) return@installCallback
            val payload = JSONObject()
                .put("userId", userId.toString())
                .put("width", width)
                .put("height", height)
                .put("rotation", readField(metadata, "rotation")?.toString().orEmpty())
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            frame(payload.toString())
        }
    }

    private fun installCallback(name: String, action: (Array<out Any?>) -> Unit) {
        val callbackMethod = nativeClass.methods.firstOrNull { it.name == name && it.parameterCount == 1 } ?: return
        val callbackType = callbackMethod.parameterTypes[0]
        val proxy = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, called, args ->
            if (called.declaringClass == Any::class.java) {
                when (called.name) {
                    "toString" -> "RdChat$name"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> null
                }
            } else {
                action(args ?: emptyArray())
                null
            }
        }
        callbackMethod.invoke(native, proxy)
    }

    private fun invokeNative(name: String, vararg args: Any?): Any? {
        val method = nativeClass.methods.firstOrNull {
            it.name == name && it.parameterCount == args.size &&
                it.parameterTypes.indices.all { index -> compatible(it.parameterTypes[index], args[index]) }
        } ?: throw NoSuchMethodException("NTgCalls.$name(${args.size})")
        return method.invoke(native, *args)
    }

    private fun compatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        val boxed = when (type) {
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> type
        }
        return boxed.isAssignableFrom(value.javaClass) || (Number::class.java.isAssignableFrom(boxed) && value is Number)
    }

    private fun stopNative(userId: Long) {
        try { invokeNative("stop", userId) }
        catch (error: Throwable) { Log.w(TAG, "Falha ao fechar mídia nativa.", error) }
        stopForegroundCall()
    }

    private fun firstMetadata(container: Any, name: String): String {
        val entries = readField(container, name) as? List<*> ?: return ""
        return entries.firstOrNull()?.let { readField(it, "metadata")?.toString().orEmpty() }.orEmpty()
    }

    private fun enumValue(type: Class<*>, token: String): Any {
        val values = type.enumConstants ?: throw IllegalStateException("${type.name} não é enumeração.")
        return values.firstOrNull { it.toString().uppercase().contains(token) }
            ?: throw IllegalStateException("Valor $token ausente em ${type.name}.")
    }

    private fun readField(value: Any?, vararg names: String): Any? {
        if (value == null) return null
        for (name in names) {
            try { return value.javaClass.getField(name).get(value) } catch (_: Throwable) { }
            try {
                val field = value.javaClass.declaredFields.firstOrNull { it.name.equals(name, true) }
                if (field != null) { field.isAccessible = true; return field.get(value) }
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun booleanField(value: Any?, vararg names: String): Boolean =
        (if (value == null) null else readField(value, *names) as? Boolean) ?: false

    private fun intField(value: Any?, vararg names: String): Int =
        (if (value == null) null else readField(value, *names) as? Number)?.toInt() ?: 0

    private fun stringArrayField(value: Any?, vararg names: String): Array<String> {
        val values = if (value == null) null else readField(value, *names) as? Array<*>
        return values?.mapNotNull { it?.toString() }?.toTypedArray() ?: emptyArray()
    }

    private fun emit(type: String, active: ActiveCall? = null, message: String = "") {
        event(
            JSONObject()
                .put("event", type)
                .put("callId", active?.callId ?: 0)
                .put("userId", active?.userId?.toString().orEmpty())
                .put("username", active?.username.orEmpty())
                .put("video", active?.isVideo ?: false)
                .put("message", message)
                .toString()
        )
    }
}
