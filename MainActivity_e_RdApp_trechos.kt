// ===== RdApp.kt =====
package com.rdchat.app
import android.app.Application
import android.content.Context
class RdApp : Application() {
    companion object {
        @Volatile var tg: TelegramManager? = null
        /** usado pelo FCM "tdlib_wake" (sem WebView): só mantém a TDLib viva para receber/armazenar */
        fun telegram(ctx: Context): TelegramManager? = tg
    }
}

// ===== MainActivity.kt (trechos) =====
class MainActivity : AppCompatActivity() {
    companion object { @Volatile var webViewRef: WebView? = null }
    private lateinit var webView: WebView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        webView = WebView(this).also { setContentView(it) }
        webViewRef = webView
        webView.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; mediaPlaybackRequiresUserGesture = false }
        val bridge = WebAppInterface(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        bridge.tdlibIniciarSeLogado()                                   // retoma sessão TDLib salva
        webView.loadUrl("file:///android_asset/www/index.html")         // ou o caminho do seu Capacitor/WebView

        // Token FCM -> HTML (que grava em users/{uid} e contactCodes)
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
            webView.post { webView.evaluateJavascript("window.RdChatNativeFcmToken&&RdChatNativeFcmToken(${org.json.JSONObject.quote(t)})", null) }
        }
    }
    override fun onDestroy() { if (webViewRef === webView) webViewRef = null; super.onDestroy() }
}
