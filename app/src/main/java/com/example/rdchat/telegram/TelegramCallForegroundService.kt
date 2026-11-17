package com.example.rdchat.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.rdchat.MainActivity
import com.example.rdchat.R

/**
 * Keeps microphone/camera capture alive while an accepted Telegram call is active.
 * Call signaling and media remain owned by TelegramManager/NTgCalls in this process.
 */
class TelegramCallForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "RdChatTelegramCalls"
        private const val NOTIFICATION_ID = 7301
        private const val EXTRA_VIDEO = "rdchat_call_video"

        fun start(context: Context, video: Boolean) {
            val intent = Intent(context, TelegramCallForegroundService::class.java)
                .putExtra(EXTRA_VIDEO, video)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramCallForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Chamadas RdChat",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Mantém chamadas de voz e vídeo ativas." }
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        val openApp = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.rdchat_notification)
            .setContentTitle(if (video) "Videochamada em andamento" else "Chamada de voz em andamento")
            .setContentText("O RdChat está usando o microfone" + if (video) " e a câmera." else ".")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()

        val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
