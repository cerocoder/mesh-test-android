package com.cerocoder.meshtest.service

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
import com.cerocoder.meshtest.MainActivity

/**
 * Удерживает процесс живым, пока держится соединение с нодой.
 *
 * Само соединение принадлежит контейнеру уровня приложения: сервис — якорь
 * жизненного цикла, а не владелец. Без него Android усыпляет процесс, и связь
 * рвётся, как только пользователь уходит из приложения.
 */
class MeshForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mesh Test")
            .setContentText("Соединение с нодой активно")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // START_NOT_STICKY, а не START_STICKY: соединение живёт в процессе, и если
        // процесс убит, восстанавливать сервису нечего. При STICKY система подняла
        // бы его заново с пустым интентом, и в шторке висело бы уведомление о
        // соединении, которого нет.
        return START_NOT_STICKY
    }

    /** Тап по уведомлению возвращает в приложение, а не открывает пустой экран. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Соединение", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mesh_connection"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
