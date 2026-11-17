package ir.salisec.autosender.send

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Foreground service so the paced send loop survives app backgrounding and stops cleanly on completion. */
class SendService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedRunning = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "ارسال کمپین", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        startForeground(NOTIF_ID, buildNotification("در حال آماده‌سازی صف ارسال..."))

        val engine = (application as ir.salisec.autosender.App).engine
        serviceScope.launch {
            engine.state.collectLatest { st ->
                if (st.running) {
                    startedRunning = true
                    val label = when {
                        st.paused -> "ارسال متوقف شد (${st.sent}/${st.total})"
                        st.currentDomain != null -> "در حال ارسال به ${st.currentDomain} (${st.sent}/${st.total})"
                        else -> "در حال پردازش صف (${st.sent}/${st.total})"
                    }
                    nm.notify(NOTIF_ID, buildNotification(label))
                } else if (startedRunning || !st.errorNotice.isNullOrBlank()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as ir.salisec.autosender.App).engine.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
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

    companion object {
        const val CHANNEL = "sali_send"
        const val NOTIF_ID = 1
    }
}
