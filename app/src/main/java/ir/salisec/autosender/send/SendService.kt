package ir.salisec.autosender.send

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Foreground service so the paced send loop survives app backgrounding. */
class SendService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, "ارسال کمپین", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
        startForeground(1, buildNotification("در حال آماده‌سازی صف ارسال..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as ir.salisec.autosender.App).engine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as ir.salisec.autosender.App).engine.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("SaliBussiness")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()
    }

    companion object { const val CHANNEL = "sali_send" }
}
