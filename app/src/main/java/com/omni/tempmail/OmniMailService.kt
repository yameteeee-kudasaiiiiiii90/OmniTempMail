package com.omni.tempmail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class OmniMailService : Service() {

    companion object {
        const val CHANNEL_ID = "omni_fg_channel"
        const val NOTIF_ID = 1
    }

    inner class LocalBinder : Binder() {
        fun getService(): OmniMailService = this@OmniMailService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newCachedThreadPool()
    private var scheduler: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateListener: (() -> Unit)? = null

    val apiClient = MultiApiManager {
        updateNotification()
        mainHandler.post { stateListener?.invoke() }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        bgExecutor.execute { apiClient.generateNewAccount() }
        startPolling()
    }

    override fun onBind(intent: Intent?): IBinder = binder
    fun setStateListener(listener: (() -> Unit)?) { stateListener = listener }

    private fun startPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay({
            try {
                apiClient.pollInbox()
            } catch (_: Exception) {}
        }, 3, 3, TimeUnit.SECONDS)
    }

    fun triggerRefresh(onDone: ((Int) -> Unit)? = null) {
        bgExecutor.execute {
            val count = apiClient.pollInbox()
            mainHandler.post { onDone?.invoke(count) }
        }
    }

    fun switchProvider(provider: String) {
        bgExecutor.execute {
            apiClient.switchProvider(provider)
            copyToClipboard(apiClient.currentEmail, "Switched to $provider & copied!")
        }
    }

    fun copyToClipboard(text: String, toastMsg: String?) {
        mainHandler.post {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OmniMail", text))
            if (!toastMsg.isNullOrBlank()) {
                Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "OmniMail Active Service", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_omni)
            .setColor(0xFF0A84FF.toInt())
            .setContentTitle("⚡ OmniMail [${apiClient.currentProvider}]")
            .setContentText(apiClient.currentEmail)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniMail::WakeLock").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
