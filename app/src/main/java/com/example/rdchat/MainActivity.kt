package com.example.rdchat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import com.google.firebase.messaging.FirebaseMessaging
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var nativeFcmPageReady = false
    private var pendingNativeFcmToken: String? = null
    private val pendingNativeFcmPayloads = mutableListOf<String>()

    companion object {
        private const val WORKER_DOMAIN = "rdchat.edersonrgames.workers.dev"
        private const val APP_URL = "https://rdchat.edersonrgames.workers.dev/assets/index.html"
        private const val ASSET_PREFIX = "https://rdchat.edersonrgames.workers.dev/assets/"
        private const val MAX_MEDIA_BYTES = 12L * 1024L * 1024L
        private const val MAX_VIDEO_BYTES = 20L * 1024L * 1024L
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var cameraTempFile: File? = null
    private var cameraCaptureType = "image"

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingCameraAction: String? = null

    private var permissionDialogInFlight = false
    private var activePermissionSet: Set<String> = emptySet()
    private val queuedPermissions = linkedSetOf<String>()

    private val mediaExecutor = Executors.newSingleThreadExecutor()

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            val callback = filePathCallback
            filePathCallback = null

            if (callback == null) {
                return@registerForActivityResult
            }

            if (result.resultCode != RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val intent = result.data

            val uris: Array<Uri>? = try {
                when {
                    intent?.clipData != null -> {
                        val clip = intent.clipData!!
                        Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                    }

                    intent?.data != null -> arrayOf(intent.data!!)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }

            callback.onReceiveValue(uris?.takeIf { it.isNotEmpty() })
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            val temp = cameraTempFile
            val outputUri = cameraImageUri
            val type = cameraCaptureType

            cameraTempFile = null
            cameraImageUri = null

            if (result.resultCode != RESULT_OK) {
                temp?.delete()
                return@registerForActivityResult
            }

            val finalUri =
                if (temp != null && temp.exists() && temp.length() > 0L) {
                    outputUri
                } else {
                    result.data?.data ?: outputUri
                }

            if (finalUri == null) {
                temp?.delete()
                showMessage("A câmera não retornou uma mídia válida.")
                return@registerForActivityResult
            }

            mediaExecutor.execute {
                val data = try {
                    getBase64FromUri(finalUri, type)
                } catch (_: Exception) {
                    null
                }

                temp?.delete()

                runOnUiThread {
                    if (data.isNullOrBlank()) {
                        showMessage(
                            if (type == "video") {
                                "Não foi possível preparar o vídeo. Verifique se ele não ultrapassou 12 MB."
                            } else {
                                "Não foi possível preparar a fotografia."
                            }
                        )
                    } else {
                        val safeData = JSONObject.quote(data)
                        val safeType = JSONObject.quote(type)

                        webView.evaluateJavascript(
                            "if (typeof window.onCameraResult === 'function') window.onCameraResult($safeData, $safeType);",
                            null
                        )
                    }
                }
            }
        }

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {

            permissionDialogInFlight = false
            activePermissionSet = emptySet()

            resolvePendingPermissionActions()
            launchNextPermissionRequest()
        }

    private fun deliverNativeFcmToken(token: String) {
        val value = token.trim()
        if (value.isEmpty()) return
        runOnUiThread {
            if (!::webView.isInitialized || !nativeFcmPageReady || !isTrustedAppPage()) {
                pendingNativeFcmToken = value
                return@runOnUiThread
            }
            val js = "if (typeof window.RdChatFirebaseNativeFcmToken === 'function') window.RdChatFirebaseNativeFcmToken(${JSONObject.quote(value)});"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun deliverNativeFcmPayload(payloadJson: String) {
        val safePayload = try { JSONObject(payloadJson).toString() } catch (error: Exception) {
            android.util.Log.w("RDCHAT_FCM", "Payload FCM invÃ¡lido.", error)
            return
        }
        runOnUiThread {
            if (!::webView.isInitialized || !nativeFcmPageReady || !isTrustedAppPage()) {
                pendingNativeFcmPayloads.add(safePayload)
                if (pendingNativeFcmPayloads.size > 50) pendingNativeFcmPayloads.removeAt(0)
                return@runOnUiThread
            }
            val js = "if (typeof window.onFCMMessageReceived === 'function') window.onFCMMessageReceived(JSON.parse(${JSONObject.quote(safePayload)}));"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun flushPendingNativeFcm() {
        if (!::webView.isInitialized || !isTrustedAppPage()) return
        nativeFcmPageReady = true
        val stored = try { MyFirebaseMessagingService.consumePendingPayloads(this) } catch (error: Exception) {
            android.util.Log.w("RDCHAT_FCM", "Falha ao consumir fila FCM local.", error)
            emptyList()
        }
        val pending = (stored + pendingNativeFcmPayloads.toList()).takeLast(50)
        pendingNativeFcmPayloads.clear()
        pending.forEach { deliverNativeFcmPayload(it) }

        val prefs = getSharedPreferences("rdchat_fcm", Context.MODE_PRIVATE)
        val waitingToken = pendingNativeFcmToken
        pendingNativeFcmToken = null
        val cached = waitingToken?.takeIf { it.isNotBlank() }
            ?: prefs.getString("token", "").orEmpty()
        if (cached.isNotBlank()) deliverNativeFcmToken(cached)
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        prefs.edit().putString("token", token).apply()
                        deliverNativeFcmToken(token)
                    }
                }
                .addOnFailureListener { error -> android.util.Log.e("RDCHAT_FCM", "Falha ao atualizar token.", error) }
        } catch (error: Exception) { android.util.Log.e("RDCHAT_FCM", "Falha ao consultar token.", error) }
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(WORKER_DOMAIN)
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView = WebView(this).apply {

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true

                allowFileAccess = false
                allowContentAccess = true

                mediaPlaybackRequiresUserGesture = false

                mixedContentMode =
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW

                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false

                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }

            webViewClient = object : WebViewClientCompat() {

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    if (!request.isForMainFrame) return false
                    val target = request.url
                    val isAppAsset = target.scheme.equals("https", true) &&
                        target.host.equals(WORKER_DOMAIN, true) &&
                        target.path.orEmpty().startsWith("/assets/")
                    if (isAppAsset || target.scheme.equals("about", true)) return false
                    if (target.scheme.equals("https", true) || target.scheme.equals("http", true)) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, target)) }
                        catch (_: Exception) { showMessage("Não foi possível abrir este link.") }
                    }
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {

                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (isTrustedAppPage()) {
                        flushPendingNativeFcm()
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {

                override fun onPermissionRequest(request: PermissionRequest) {

                    runOnUiThread {

                        if (!isTrustedOrigin(request.origin)) {
                            safeDeny(request)
                            return@runOnUiThread
                        }

                        val permissions = permissionsForWebRequest(request.resources)

                        if (permissions == null) {
                            safeDeny(request)
                            return@runOnUiThread
                        }

                        pendingWebPermissionRequest?.let {
                            if (it !== request) safeDeny(it)
                        }

                        pendingWebPermissionRequest = request
                        requestRuntimePermissions(permissions)
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if (pendingWebPermissionRequest === request) {
                        pendingWebPermissionRequest = null
                    }
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {

                    if (!isTrustedAppPage() || callback == null) {
                        callback?.onReceiveValue(null)
                        return false
                    }

                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback

                    return try {

                        val chooserIntent = params?.createIntent()
                            ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }

                        chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                        fileChooserLauncher.launch(chooserIntent)
                        true

                    } catch (_: Exception) {

                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null

                        showMessage("Não foi possível abrir a galeria ou o seletor de arquivos.")
                        true
                    }
                }
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, false)

        webView.addJavascriptInterface(
            object {

                @JavascriptInterface
                fun openCamera() {
                    runOnUiThread {
                        openCameraWithPermission("image")
                    }
                }

                @JavascriptInterface
                fun openVideoCamera() {
                    runOnUiThread {
                        openCameraWithPermission("video")
                    }
                }

                @JavascriptInterface
                fun requestMicrophonePermission() {
                    runOnUiThread {
                        requestRuntimePermissions(listOf(Manifest.permission.RECORD_AUDIO))
                    }
                }

                @JavascriptInterface
                fun requestNotificationPermission() {
                    runOnUiThread {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestRuntimePermissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }
                }

                @JavascriptInterface
                fun getClipboardText(): String {
                    return try {
                        val clipboard =
                            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                        val clip = clipboard.primaryClip

                        if (clip != null && clip.itemCount > 0) {
                            clip.getItemAt(0).coerceToText(this@MainActivity)
                                .toString().take(2048)
                        } else {
                            ""
                        }
                    } catch (_: Exception) {
                        ""
                    }
                }

                            @JavascriptInterface
                fun openFileWithChooser(dataUrl: String, mime: String, name: String) {
                    RdChatMediaHandler(this@MainActivity).open(dataUrl, mime, name)
                }

                @JavascriptInterface
                fun openMediaInGallery(type: String, url: String, name: String) {
                    val mime = when (type.lowercase()) {
                        "video" -> "video/mp4"
                        "audio" -> "audio/webm"
                        "gif" -> "image/gif"
                        else -> "image/jpeg"
                    }
                    RdChatMediaHandler(this@MainActivity).open(url, mime, name)
                }

                @JavascriptInterface
                fun openMediaViewer(url: String, mimeType: String, name: String) {
                    RdChatMediaHandler(this@MainActivity).open(url, mimeType, name)
                }

                @JavascriptInterface
                fun openImageViewer(url: String, name: String) {
                    RdChatMediaHandler(this@MainActivity).open(url, "image/*", name)
                }

                @JavascriptInterface
                fun openVideoViewer(url: String, name: String) {
                    RdChatMediaHandler(this@MainActivity).open(url, "video/mp4", name)
                }

                @JavascriptInterface
                fun resetDefaultMediaApp() {
                    RdChatMediaHandler(this@MainActivity).resetDefaultMediaApp()
                }
                @JavascriptInterface
                fun getMediaDefaultsJson(): String {
                    return RdChatMediaHandler(this@MainActivity).getMediaDefaultsJson()
                }

                @JavascriptInterface
                fun getAvailableMediaAppsJson(kind: String): String {
                    return RdChatMediaHandler(this@MainActivity).getAvailableMediaAppsJson(kind)
                }

                @JavascriptInterface
                fun setDefaultMediaApp(kind: String, packageName: String): Boolean {
                    return RdChatMediaHandler(this@MainActivity).setDefaultMediaApp(kind, packageName)
                }

                @JavascriptInterface
                fun clearDefaultMediaApp(kind: String): Boolean {
                    return RdChatMediaHandler(this@MainActivity).clearDefaultMediaApp(kind)
                }
                @JavascriptInterface
                fun setActiveChatUid(uid: String) {
                    val value = uid.trim()
                    val prefs = this@MainActivity.getSharedPreferences(
                        "rdchat_active",
                        Context.MODE_PRIVATE
                    )

                    if (value.isBlank() || value.contains("@")) {
                        prefs.edit().remove("active_sender_uid").apply()
                    } else {
                        prefs.edit().putString("active_sender_uid", value).apply()
                    }
                }

                @JavascriptInterface
                fun clearActiveChat() {
                    this@MainActivity.getSharedPreferences(
                        "rdchat_active",
                        Context.MODE_PRIVATE
                    ).edit().remove("active_sender_uid").apply()
                }
                @JavascriptInterface
                fun clearWebCache() {
                    this@MainActivity.runOnUiThread {
                        this@MainActivity.webView.clearCache(true)
                    }
                }

                @JavascriptInterface
                fun getFcmToken(): String {
                    val prefs = this@MainActivity.getSharedPreferences("rdchat_fcm", Context.MODE_PRIVATE)
                    val cached = prefs.getString("token", "").orEmpty()

                    try {
                        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                            .addOnCompleteListener { task ->
                                if (!task.isSuccessful) {
                                    android.util.Log.e("RDCHAT_FCM", "Falha ao obter token FCM", task.exception)
                                    return@addOnCompleteListener
                                }

                                val token = task.result ?: return@addOnCompleteListener
                                prefs.edit().putString("token", token).apply()

                                this@MainActivity.deliverNativeFcmToken(token)
                            }
                    } catch (error: Exception) {
                        android.util.Log.e("RDCHAT_FCM", "Erro ao solicitar token FCM", error)
                    }

                    return cached
                }
                @JavascriptInterface
                fun authenticate() = this@MainActivity.authenticateNative()

                @JavascriptInterface
                fun getSafetyEventsJson(): String = this@MainActivity.getSafetyEventsJsonNative()

                @JavascriptInterface
                fun getStorageUsageJson(): String = this@MainActivity.getStorageUsageJsonNative()

                @JavascriptInterface
                fun recordSafetyEvent(type: String, content: String, reason: String) =
                    this@MainActivity.recordSafetyEventNative(type, content, reason)

                @JavascriptInterface
                fun resolveGifLink(url: String, requestId: String) =
                    this@MainActivity.resolveGifLinkNative(url, requestId)

                @JavascriptInterface
                fun resolveXGif(url: String, requestId: String) =
                    this@MainActivity.resolveXGifNative(url, requestId)

                @JavascriptInterface
                fun reviewMediaBase64(value: String, mime: String, requestId: String) =
                    this@MainActivity.rejectNativeMediaReview(requestId, "A análise local de mídia não está configurada neste build.")

                @JavascriptInterface
                fun reviewRemoteMedia(url: String, mime: String, requestId: String) =
                    this@MainActivity.rejectNativeMediaReview(requestId, "A análise local de mídia remota não está configurada neste build.")

                @JavascriptInterface
                fun setSafetyProfile(adult: Boolean, restricted: Boolean) =
                    this@MainActivity.setSafetyProfileNative(adult, restricted)

                @JavascriptInterface
                fun setSpeakerphoneOn(enabled: Boolean) =
                    this@MainActivity.setSpeakerphoneNative(enabled)

                @JavascriptInterface
                fun showNotification(title: String, body: String) =
                    this@MainActivity.showNativeNotification(title, body)

            }, "AndroidBridge")

        MyFirebaseMessagingService.tokenListener = { token -> deliverNativeFcmToken(token) }
        MyFirebaseMessagingService.messageListener = { _, _, payloadJson -> deliverNativeFcmPayload(payloadJson) }

        setContentView(webView)

        val restored = savedInstanceState?.let {
            webView.restoreState(it)
        }

        if (
            restored == null ||
            !webView.url.orEmpty().startsWith(ASSET_PREFIX)
        ) {
            webView.loadUrl(APP_URL)
        }

        var backRequestPending = false
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {
                    if (backRequestPending) return
                    backRequestPending = true

                    try {
                        this@MainActivity.webView.evaluateJavascript(
                            "(function(){try{return window.onAndroidBack?!!window.onAndroidBack():false}catch(e){return false}})()"
                        ) { handled ->
                            backRequestPending = false
                            if (handled != "true" && !this@MainActivity.isFinishing) {
                                this@MainActivity.finish()
                            }
                        }
                    } catch (_: Exception) {
                        backRequestPending = false
                        if (!this@MainActivity.isFinishing) {
                            this@MainActivity.finish()
                        }
                    }
                }
            }
        )
    }


    private fun sendJavascriptCallback(script: String) {
        if (!::webView.isInitialized) return
        webView.post {
            if (isTrustedAppPage()) {
                try { webView.evaluateJavascript(script, null) } catch (_: Exception) {}
            }
        }
    }

    private fun authenticateNative() {
        runOnUiThread {
            if (!isTrustedAppPage()) return@runOnUiThread
            try {
                val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                } else {
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
                }
                if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                    sendJavascriptCallback("if (typeof window.onBiometricError === 'function') window.onBiometricError();")
                    return@runOnUiThread
                }
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirmar identidade")
                    .setSubtitle("Confirme para continuar no RdChat")
                    .setAllowedAuthenticators(authenticators)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) info.setNegativeButtonText("Cancelar")
                val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            sendJavascriptCallback("if (typeof window.onBiometricSuccess === 'function') window.onBiometricSuccess();")
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            sendJavascriptCallback("if (typeof window.onBiometricError === 'function') window.onBiometricError();")
                        }
                    })
                prompt.authenticate(info.build())
            } catch (error: Exception) {
                android.util.Log.w("RDCHAT_BIOMETRIC", "Autenticação biométrica indisponível.", error)
                sendJavascriptCallback("if (typeof window.onBiometricError === 'function') window.onBiometricError();")
            }
        }
    }

    private fun recordSafetyEventNative(type: String, content: String, reason: String) {
        try {
            val prefs = getSharedPreferences("rdchat_safety", MODE_PRIVATE)
            val current = try { org.json.JSONArray(prefs.getString("events", "[]") ?: "[]") } catch (_: Exception) { org.json.JSONArray() }
            val next = org.json.JSONArray()
            val start = (current.length() - 98).coerceAtLeast(0)
            for (index in start until current.length()) next.put(current.optJSONObject(index) ?: continue)
            next.put(org.json.JSONObject()
                .put("type", type.trim().take(40).ifBlank { "Ocorrência" })
                .put("reason", reason.trim().take(180))
                .put("contentLength", content.length.coerceAtMost(320))
                .put("timestamp", System.currentTimeMillis()))
            prefs.edit().putString("events", next.toString()).apply()
        } catch (error: Exception) {
            android.util.Log.w("RDCHAT_SAFETY", "Não foi possível salvar o evento local.", error)
        }
    }

    private fun getSafetyEventsJsonNative(): String =
        getSharedPreferences("rdchat_safety", MODE_PRIVATE).getString("events", "[]") ?: "[]"

    private fun setSafetyProfileNative(adult: Boolean, restricted: Boolean) {
        getSharedPreferences("rdchat_safety", MODE_PRIVATE).edit()
            .putBoolean("adult_profile", adult)
            .putBoolean("restricted_profile", restricted)
            .putLong("profile_updated_at", System.currentTimeMillis())
            .apply()
    }

    private fun directorySize(directory: File): Long {
        if (!directory.exists()) return 0L
        return try { directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        catch (_: Exception) { 0L }
    }

    private fun getStorageUsageJsonNative(): String {
        val modelBytes = directorySize(File(filesDir, "tdlib")) + directorySize(File(filesDir, "tdlib_files"))
        val cacheBytes = directorySize(cacheDir)
        return org.json.JSONObject()
            .put("totalBytes", directorySize(filesDir) + cacheBytes)
            .put("modelBytes", modelBytes)
            .put("cacheBytes", cacheBytes)
            .toString()
    }

    private fun resolveGifLinkNative(rawUrl: String, requestId: String) {
        val uri = try { Uri.parse(rawUrl.trim()) } catch (_: Exception) { null }
        val path = uri?.path.orEmpty().lowercase(java.util.Locale.ROOT)
        val direct = uri != null && uri.scheme.equals("https", true) &&
            uri.userInfo.isNullOrBlank() && (path.endsWith(".gif") || path.endsWith(".mp4"))
        val payload = org.json.JSONObject().put("ok", direct)
        if (direct) payload.put("url", uri.toString()).put("prev", uri.toString()).put("gifVideo", path.endsWith(".mp4"))
        else payload.put("reason", "Use um link HTTPS direto terminado em .gif ou .mp4; conversão de páginas não está configurada.")
        sendJavascriptCallback("if (typeof window.rdOnGifLinkResolved === 'function') window.rdOnGifLinkResolved(${JSONObject.quote(requestId)}, $payload);")
    }

    private fun resolveXGifNative(rawUrl: String, requestId: String) {
        val payload = org.json.JSONObject().put("ok", false)
            .put("reason", "A conversão de publicações do X exige um resolvedor de mídia autorizado, ainda não configurado.")
        sendJavascriptCallback("if (typeof window.rdOnGifLinkResolved === 'function') window.rdOnGifLinkResolved(${JSONObject.quote(requestId)}, $payload);")
    }

    private fun rejectNativeMediaReview(requestId: String, reason: String) {
        recordSafetyEventNative("media_review_unavailable", "", reason)
        val payload = org.json.JSONObject().put("allowed", false).put("reason", reason)
        sendJavascriptCallback("if (typeof window.rdOnNativeMediaReview === 'function') window.rdOnNativeMediaReview(${JSONObject.quote(requestId)}, $payload);")
    }

    private fun setSpeakerphoneNative(enabled: Boolean) {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = enabled
        } catch (error: Exception) {
            android.util.Log.w("RDCHAT_AUDIO", "Não foi possível alterar o alto-falante.", error)
        }
    }

    private fun showNativeNotification(title: String, body: String) {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return@runOnUiThread
            try {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.createNotificationChannel(NotificationChannel("rdchat_messages", "Mensagens", NotificationManager.IMPORTANCE_HIGH))
                }
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pending = PendingIntent.getActivity(this, 7301, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(this, "rdchat_messages")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title.trim().take(80).ifBlank { "RdChat" })
                    .setContentText(body.trim().take(180).ifBlank { "Nova mensagem" })
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build()
                manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
            } catch (error: Exception) {
                android.util.Log.w("RDCHAT_NOTIFICATION", "Não foi possível exibir a notificação.", error)
            }
        }
    }

    private fun isTrustedAppPage(): Boolean {
        if (!::webView.isInitialized) return false

        return try {
            val uri = Uri.parse(webView.url ?: return false)

            uri.scheme.equals("https", true) &&
                uri.host.equals(WORKER_DOMAIN, true) &&
                uri.path.orEmpty().startsWith("/assets/")
        } catch (_: Exception) {
            false
        }
    }

    private fun isTrustedOrigin(origin: Uri): Boolean {
        return origin.scheme.equals("https", true) &&
            origin.host.equals(WORKER_DOMAIN, true)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionsForWebRequest(resources: Array<String>): List<String>? {
        if (resources.isEmpty()) return null

        val permissions = linkedSetOf<String>()

        for (resource in resources) {
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    permissions.add(Manifest.permission.CAMERA)
                }

                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    permissions.add(Manifest.permission.RECORD_AUDIO)
                }

                else -> return null
            }
        }

        return permissions.toList()
    }

    private fun requestRuntimePermissions(requested: Collection<String>) {

        for (permission in requested.distinct()) {

            if (hasPermission(permission)) continue

            if (
                permissionDialogInFlight &&
                permission in activePermissionSet
            ) {
                continue
            }

            queuedPermissions.add(permission)
        }

        launchNextPermissionRequest()
    }

    private fun launchNextPermissionRequest() {

        if (permissionDialogInFlight) return

        val toAsk = queuedPermissions
            .filterNot { hasPermission(it) }
            .toTypedArray()

        queuedPermissions.clear()

        if (toAsk.isEmpty()) {
            resolvePendingPermissionActions()
            return
        }

        permissionDialogInFlight = true
        activePermissionSet = toAsk.toSet()

        try {
            runtimePermissionLauncher.launch(toAsk)
        } catch (error: Exception) {
            permissionDialogInFlight = false
            activePermissionSet = emptySet()

            showMessage("Não foi possível solicitar as permissões do Android.")
            resolvePendingPermissionActions()
        }
    }

    private fun resolvePendingPermissionActions() {

        val webRequest = pendingWebPermissionRequest

        if (webRequest != null) {

            val needed = permissionsForWebRequest(webRequest.resources)

            if (needed == null) {

                pendingWebPermissionRequest = null
                safeDeny(webRequest)

            } else {

                val missing = needed.filterNot { hasPermission(it) }

                if (missing.isEmpty()) {

                    pendingWebPermissionRequest = null

                    try {
                        webRequest.grant(webRequest.resources)
                    } catch (_: Exception) {
                    }

                } else if (
                    missing.none {
                        it in queuedPermissions || it in activePermissionSet
                    }
                ) {

                    pendingWebPermissionRequest = null
                    safeDeny(webRequest)
                }
            }
        }

        val cameraAction = pendingCameraAction

        if (cameraAction != null) {

            val needed = cameraPermissionsFor(cameraAction)
            val missing = needed.filterNot { hasPermission(it) }

            if (missing.isEmpty()) {

                pendingCameraAction = null
                launchCameraIntent(cameraAction)

            } else if (
                missing.none {
                    it in queuedPermissions || it in activePermissionSet
                }
            ) {

                pendingCameraAction = null
                showMessage("Permita o acesso à câmera e ao microfone nas configurações do Android.")
            }
        }
    }

    private fun safeDeny(request: PermissionRequest) {
        try {
            request.deny()
        } catch (_: Exception) {
        }
    }

    private fun cameraPermissionsFor(type: String): List<String> {

        val permissions = mutableListOf(Manifest.permission.CAMERA)

        if (type == "video") {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        return permissions
    }

    private fun openCameraWithPermission(type: String) {

        if (!isTrustedAppPage()) return

        pendingCameraAction = type
        requestRuntimePermissions(cameraPermissionsFor(type))
    }

    private fun launchCameraIntent(type: String) {

        try {

            cameraCaptureType = type

            val privateFolder = File(cacheDir, "midia_privada").apply {
                mkdirs()
            }

            File(privateFolder, ".nomedia").apply {
                if (!exists()) createNewFile()
            }

            val extension = if (type == "video") ".mp4" else ".jpg"
            val prefix = if (type == "video") "rdchat_video_" else "rdchat_foto_"

            val file = File.createTempFile(prefix, extension, privateFolder)

            cameraTempFile = file

            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            cameraImageUri = uri

            val action = if (type == "video") {
                MediaStore.ACTION_VIDEO_CAPTURE
            } else {
                MediaStore.ACTION_IMAGE_CAPTURE
            }

            val intent = Intent(action).apply {

                putExtra(MediaStore.EXTRA_OUTPUT, uri)

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                clipData = ClipData.newUri(
                    contentResolver,
                    "RdChat mídia",
                    uri
                )

                if (type == "video") {
                    putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                    putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30)
                    putExtra(MediaStore.EXTRA_SIZE_LIMIT, MAX_VIDEO_BYTES)
                }
            }

            cameraLauncher.launch(intent)

        } catch (_: Exception) {

            cameraImageUri = null
            cameraTempFile?.delete()
            cameraTempFile = null

            showMessage(
                if (type == "video") {
                    "Não foi possível abrir a câmera de vídeo."
                } else {
                    "Não foi possível abrir a câmera."
                }
            )
        }
    }

    private fun getBase64FromUri(uri: Uri, type: String): String? {

        if (type == "video") {

            val output = ByteArrayOutputStream()

            val input = contentResolver.openInputStream(uri) ?: return null

            input.use {

                val buffer = ByteArray(64 * 1024)
                var total = 0L

                while (true) {

                    val count = it.read(buffer)

                    if (count < 0) break

                    total += count

                    if (total > MAX_VIDEO_BYTES) {
                        throw IOException("Vídeo maior que 20 MB.")
                    }

                    output.write(buffer, 0, count)
                }
            }

            val bytes = output.toByteArray()

            if (bytes.isEmpty()) return null

            return "data:video/mp4;base64," +
                Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1

        while (
            bounds.outWidth / (sample * 2) > 2048 ||
            bounds.outHeight / (sample * 2) > 2048
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val output = ByteArrayOutputStream()

        try {

            var quality = 82

            do {

                output.reset()

                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    return null
                }

                quality -= 8

            } while (
                output.size().toLong() > MAX_MEDIA_BYTES &&
                quality >= 50
            )

            if (output.size().toLong() > MAX_MEDIA_BYTES) return null

            val bytes = output.toByteArray()

            if (bytes.isEmpty()) return null

            return "data:image/jpeg;base64," +
                Base64.encodeToString(bytes, Base64.NO_WRAP)

        } finally {
            bitmap.recycle()
            output.close()
        }
    }

    private fun showMessage(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {

        try {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null

            pendingWebPermissionRequest?.let { safeDeny(it) }
            pendingWebPermissionRequest = null

            cameraTempFile?.delete()
            cameraTempFile = null
            cameraImageUri = null

            mediaExecutor.shutdownNow()

            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }

        } catch (_: Exception) {
        }

        nativeFcmPageReady = false
        MyFirebaseMessagingService.messageListener = null
        MyFirebaseMessagingService.tokenListener = null

        super.onDestroy()
    }
}