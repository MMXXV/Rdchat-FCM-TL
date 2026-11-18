package com.example.rdchat

import android.app.ActivityManager
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
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStream

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
        if (type == "rdchat_wake_v1") reportDeliveredToGateway(data)
        if (type.startsWith("rdchat_ack")) return

        val senderId = data["senderId"] ?: data["fromUid"] ?: "unknown"
        val senderEmail = data["senderEmail"]?.trim()?.lowercase().orEmpty()
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

        if (type == "rdchat_contact_added_v1") {
            // O evento atualiza a lista de contatos nos dois aparelhos; não cria alerta de mensagem.
            Log.i("RDCHAT_FCM", "CONTACT_LIST_REFRESH_RECEIVED senderId=$senderId")
        } else if (shouldSuppressNotification(senderId, senderEmail)) {
            Log.i("RDCHAT_FCM", "NOTIFICATION_SUPPRESSED same_sender_chat_open senderId=$senderId")
        } else {
            showDataNotification(title, body, type, data, senderId, senderEmail)
        }
    }

    private fun reportDeliveredToGateway(data: Map<String, String>) {
        val roomId = data["roomId"].orEmpty()
        val messageId = data["messageId"].orEmpty()
        val recipientUid = data["recipientUid"].orEmpty()
        val token = getSharedPreferences("rdchat_fcm", MODE_PRIVATE).getString("token", "").orEmpty()
        if (roomId.isBlank() || messageId.isBlank() || recipientUid.isBlank() || token.isBlank()) {
            Log.w("RDCHAT_FCM", "DELIVERY_ACK_SKIPPED missing_fields")
            return
        }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://rdchat-fcm-gateway.edersonrgames.workers.dev/v1/delivered").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                val body = JSONObject()
                    .put("roomId", roomId)
                    .put("messageId", messageId)
                    .put("recipientUid", recipientUid)
                    .put("token", token)
                    .toString()
                connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status in 200..299) Log.i("RDCHAT_FCM", "DELIVERY_ACK_OK messageId=$messageId")
                else Log.w("RDCHAT_FCM", "DELIVERY_ACK_HTTP_$status messageId=$messageId")
            } catch (error: Exception) {
                Log.w("RDCHAT_FCM", "DELIVERY_ACK_FAILED messageId=$messageId", error)
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
    private fun shouldSuppressNotification(senderId: String, senderEmail: String): Boolean {
        if ((senderId.isBlank() || senderId == "unknown") && senderEmail.isBlank()) return false

        // IMPORTANCE_VISIBLE inclui a Activity visível. O chat precisa ser exatamente o remetente.
        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        val appIsVisible = processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        if (!appIsVisible) return false

        val prefs = getSharedPreferences("rdchat_active", MODE_PRIVATE)
        val activeUid = prefs.getString("active_sender_uid", "").orEmpty()
        val activeEmail = prefs.getString("active_sender_email", "").orEmpty().trim().lowercase()
        val sameUid = senderId.isNotBlank() && senderId != "unknown" &&
            activeUid.isNotBlank() && activeUid.equals(senderId, ignoreCase = true)
        val sameEmail = senderEmail.isNotBlank() && activeEmail.isNotBlank() &&
            activeEmail == senderEmail
        return sameUid || sameEmail
    }
    private fun showDataNotification(
        title: String,
        body: String,
        type: String,
        data: Map<String, String>,
        senderId: String,
        senderEmail: String
    ) {
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

        val payload = JSONObject().apply { data.forEach { (key, value) -> put(key, value) } }.toString()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("rdchat_notification_payload", payload)
            putExtra("rdchat_room_id", data["roomId"].orEmpty())
            putExtra("rdchat_message_id", data["messageId"] ?: data["id"].orEmpty())
            putExtra("rdchat_sender_id", senderId)
            putExtra("rdchat_sender_email", senderEmail)
        }
        val requestCode = (data["messageId"] ?: data["id"] ?: payload).hashCode()
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, requestCode, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.rdchat_notification)
            .setContentTitle("RdChat")
            .setContentText("Nova mensagem recebida")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                title.take(80) + "\\n" + body.take(180)
            ))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.rdchat_notification)
                .setContentTitle("RdChat")
                .setContentText("Nova mensagem recebida")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build())
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
