package com.rdchat.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * FCM DATA-ONLY (nunca "notification"): é o que garante onMessageReceived com o app fechado.
 * O servidor deve enviar: { "data": {...}, "android": {"priority":"HIGH"} }  (sem campo "notification").
 * data: type = "msg" | "tdlib_wake" | "deleted", id, room, fromName, preview, groupName
 */
class RdFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        getSharedPreferences("rdchat", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
        MainActivity.webViewRef?.post {
            MainActivity.webViewRef?.evaluateJavascript("window.RdChatNativeFcmToken&&RdChatNativeFcmToken(${JSONObject.quote(token)})", null)
        }
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        when (d["type"]) {
            "tdlib_wake" -> RdApp.telegram(this)          // acorda a TDLib: ela baixa as mensagens pendentes
            "deleted" -> {}                              // o HTML apaga ao abrir/sincronizar
            else -> notificar(d)
        }
        // App aberto: injeta direto na WebView (mesmo caminho do Firestore)
        MainActivity.webViewRef?.post {
            MainActivity.webViewRef?.evaluateJavascript(
                "window.RdChatNativePush&&RdChatNativePush(${JSONObject(d as Map<*, *>)})", null)
        }
    }

    private fun notificar(d: Map<String, String>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("mensagens", "Mensagens", NotificationManager.IMPORTANCE_HIGH))
        val i = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP; putExtra("room", d["room"]) }
        val pi = PendingIntent.getActivity(this, (d["id"] ?: "0").hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, "mensagens")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(d["groupName"].takeUnless { it.isNullOrEmpty() } ?: d["fromName"] ?: "RdChat")
            .setContentText(d["preview"] ?: "Nova mensagem")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build()
        nm.notify((d["room"] ?: d["id"] ?: "x").hashCode(), n)
    }
}
