package com.example.rdchat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        @Volatile var messageListener: ((String, String, String) -> Unit)? = null
        @Volatile var tokenListener: ((String) -> Unit)? = null
        private const val PREFS = "rdchat_fcm"
        private const val PENDING_KEY = "pending_data_messages"
        private val pendingLock = Any()

        private fun persistPendingPayload(context: Context, payloadJson: String) {
            synchronized(pendingLock) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val current = try { JSONArray(prefs.getString(PENDING_KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
                val next = JSONArray()
                // Limita a fila local para não crescer sem controle caso o usuário não abra o app.
                val start = (current.length() - 49).coerceAtLeast(0)
                for (index in start until current.length()) next.put(current.optString(index))
                next.put(payloadJson)
                prefs.edit().putString(PENDING_KEY, next.toString()).apply()
            }
        }

        fun consumePendingPayloads(context: Context): List<String> {
            synchronized(pendingLock) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val current = try { JSONArray(prefs.getString(PENDING_KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
                val values = (0 until current.length()).mapNotNull { index -> current.optString(index).takeIf { it.isNotBlank() } }
                prefs.edit().remove(PENDING_KEY).apply()
                return values
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences("rdchat_fcm", MODE_PRIVATE)
            .edit()
            .putString("token", token)
            .apply()
        tokenListener?.invoke(token)
        Log.i("RDCHAT_FCM", "Token FCM atualizado e armazenado localmente.")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // RdChat usa exclusivamente mensagens de dados. Notification payloads genéricos
        // são ignorados para manter o processamento e a notificação sob controle do app.
        val data = message.data
        if (data.isEmpty()) {
            Log.w("RDCHAT_FCM", "Mensagem FCM sem data payload ignorada.")
            return
        }

        val type = data["type"].orEmpty()
        if (type.startsWith("rdchat_ack")) return

        val senderId = data["senderId"] ?: data["fromUid"] ?: "unknown"
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: "RdChat"
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: when {
            type == "rdchat_wake_v1" -> "Há uma mensagem ou mídia aguardando."
            type.startsWith("rdchat_text_sealed") -> "Nova mensagem recebida. Abra o RdChat para sincronizar."
            else -> "Há uma nova atividade no RdChat."
        }

        val payloadJson = try {
            JSONObject().apply {
                data.forEach { (key, value) -> put(key, value) }
                put("title", title)
                put("body", body)
                put("senderId", senderId)
            }.toString()
        } catch (error: Exception) {
            Log.e("RDCHAT_FCM", "Não foi possível montar o payload de dados.", error)
            return
        }

        val listener = messageListener
        if (listener != null) {
            try {
                listener.invoke(senderId, body, payloadJson)
            } catch (error: Exception) {
                Log.e("RDCHAT_FCM", "Erro ao repassar evento ao WebView; evento guardado localmente.", error)
                persistPendingPayload(this, payloadJson)
            }
        } else {
            // O processo pode ter sido iniciado apenas pelo serviço FCM, sem Activity/WebView.
            persistPendingPayload(this, payloadJson)
        }

        showDataNotification(title, body, type)
    }

    private fun showDataNotification(title: String, body: String, type: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val channelId = "RdChatMsgs"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(channelId, "Mensagens RdChat", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Notificações de mensagens e atividade recebidas por dados FCM" }
                )
            }
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.rdchat_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        try {
            val id = (System.currentTimeMillis() and 0x7fffffffL).toInt()
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (error: Exception) {
            Log.e("RDCHAT_FCM", "Falha ao exibir notificação de dados ($type).", error)
        }
    }
}
