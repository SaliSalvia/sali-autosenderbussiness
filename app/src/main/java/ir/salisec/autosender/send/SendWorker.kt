package ir.salisec.autosender.send

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import ir.salisec.autosender.App

/**
 * WorkManager worker that runs the approved send queue in the background with:
 *  - NetworkType.CONNECTED constraint
 *  - Foreground dataSync notification (survives process backgrounding)
 *  - Automatic business-hours rescheduling when outside the configured work window
 */
class SendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        ensureChannel()
        try {
            setForeground(foregroundInfo("در حال آماده‌سازی صف ارسال..."))
        } catch (_: Exception) {
            // Foreground promotion may be deferred on some OEMs; continue safely
        }

        return when (val outcome = app.engine.runQueue { label ->
            try {
                setForeground(foregroundInfo(label))
            } catch (_: Exception) { }
        }) {
            is QueueRunOutcome.Completed -> Result.success()
            is QueueRunOutcome.RescheduleOutsideHours -> {
                app.engine.enqueueWork(initialDelayMs = outcome.delayMs)
                Result.success()
            }
            is QueueRunOutcome.FailedConfig -> Result.failure()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                SendService.CHANNEL,
                "ارسال کمپین",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, SendService.CHANNEL)
            .setContentTitle("SaliBussiness")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SendService.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SendService.NOTIF_ID, notification)
        }
    }
}
