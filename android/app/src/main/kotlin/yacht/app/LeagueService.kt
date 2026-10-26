package yacht.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the AI league playing while the app is hidden (리그 설정 ‘나가도 계속’): a foreground
 * service with a notification (tap: open the app; ‘정지’: stop the league) and a partial wake
 * lock, so the phone keeps computing with the screen off. The league itself runs in the app's
 * controller; this only keeps the process awake and visible. It runs while the league runs in
 * that mode (see GameViewModel).
 */
class LeagueService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // At once: a service started as a foreground one must say so within seconds.
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLeague?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yacht:league")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    private fun goForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI 리그", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags,
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, LeagueService::class.java).setAction(ACTION_STOP), flags)
        val icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle("AI 리그 진행 중")
            .setContentText("앱을 나가도 계속 돌아요 · 눌러서 열기")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, "정지", stop).build())
            .build()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "league"
        private const val ACTION_STOP = "yacht.app.LEAGUE_STOP"

        /** Stops the league (set by the app while it lives). */
        @Volatile
        var stopLeague: (() -> Unit)? = null

        /** Starts the service (only from the foreground; refused otherwise, which is ignored). */
        fun start(c: Context) {
            runCatching { c.startForegroundService(Intent(c, LeagueService::class.java)) }
        }

        fun stop(c: Context) {
            runCatching { c.stopService(Intent(c, LeagueService::class.java)) }
        }
    }
}
