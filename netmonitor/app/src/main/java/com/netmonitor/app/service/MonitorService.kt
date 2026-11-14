package com.netmonitor.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.netmonitor.app.Connection
import com.netmonitor.app.MainActivity
import com.netmonitor.app.Prefs
import com.netmonitor.app.R
import com.netmonitor.app.currentConnection
import com.netmonitor.app.data.AppDatabase
import com.netmonitor.app.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 서비스가 1초마다 내보내는 실시간 값. 화면은 이것만 읽는다. */
data class LiveState(
    val running: Boolean = false,
    val connection: Connection = Connection.NONE,
    val downBps: Double = 0.0,
    val upBps: Double = 0.0,
)

class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 부팅 이후 누적 카운터 한 번 읽은 값. 와이파이는 전체에서 모바일을 뺀 값이다. */
    private data class Counters(val wifiRx: Long, val wifiTx: Long, val mobileRx: Long, val mobileTx: Long)

    // 아직 DB에 쓰지 않은 사용량
    private var pendWifiRx = 0L
    private var pendWifiTx = 0L
    private var pendMobileRx = 0L
    private var pendMobileTx = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification(LiveState(running = true, connection = currentConnection(this)))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scope.launch { measureLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _state.value = LiveState()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun measureLoop() {
        val dao = AppDatabase.get(this).usageDao()
        val manager = getSystemService(NotificationManager::class.java)
        dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)

        var prev = readCounters()
        var prevTime = SystemClock.elapsedRealtime()
        var ticks = 0
        try {
            while (scope.isActive) {
                delay(1000)
                val now = readCounters()
                val nowTime = SystemClock.elapsedRealtime()
                // 기기가 잠들면 1초보다 오래 지나므로 실제 흐른 시간으로 나눈다
                val seconds = ((nowTime - prevTime) / 1000.0).coerceAtLeast(0.001)

                // 카운터가 줄었으면(초기화) 그 구간은 0으로 본다
                val wifiRx = (now.wifiRx - prev.wifiRx).coerceAtLeast(0)
                val wifiTx = (now.wifiTx - prev.wifiTx).coerceAtLeast(0)
                val mobileRx = (now.mobileRx - prev.mobileRx).coerceAtLeast(0)
                val mobileTx = (now.mobileTx - prev.mobileTx).coerceAtLeast(0)
                prev = now
                prevTime = nowTime

                pendWifiRx += wifiRx
                pendWifiTx += wifiTx
                pendMobileRx += mobileRx
                pendMobileTx += mobileTx

                val live = LiveState(
                    running = true,
                    connection = currentConnection(this),
                    downBps = (wifiRx + mobileRx) / seconds,
                    upBps = (wifiTx + mobileTx) / seconds,
                )
                _state.value = live
                manager.notify(NOTIFICATION_ID, buildNotification(live))

                if (++ticks >= FLUSH_EVERY_TICKS) {
                    ticks = 0
                    flush()
                }
            }
        } finally {
            // 끌 때 남은 사용량까지 저장
            withContext(NonCancellable) { flush() }
        }
    }

    private suspend fun flush() {
        if (pendWifiRx + pendWifiTx + pendMobileRx + pendMobileTx == 0L) return
        val minute = System.currentTimeMillis() / 60_000 * 60_000
        AppDatabase.get(this).usageDao().add(minute, pendWifiRx, pendWifiTx, pendMobileRx, pendMobileTx)
        pendWifiRx = 0
        pendWifiTx = 0
        pendMobileRx = 0
        pendMobileTx = 0
    }

    private fun readCounters(): Counters {
        // 지원하지 않는 기기는 -1(UNSUPPORTED)을 돌려준다
        val totalRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
        val totalTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        val mobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0)
        val mobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0)
        return Counters(
            wifiRx = (totalRx - mobileRx).coerceAtLeast(0),
            wifiTx = (totalTx - mobileTx).coerceAtLeast(0),
            mobileRx = mobileRx,
            mobileTx = mobileTx,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "실시간 속도", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(live: LiveState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle("↓ ${formatSpeed(live.downBps)}   ↑ ${formatSpeed(live.upBps)}")
            .setContentText(live.connection.label)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "live_speed"
        private const val NOTIFICATION_ID = 1
        private const val FLUSH_EVERY_TICKS = 10
        private const val RETENTION_MS = 62L * 24 * 60 * 60 * 1000 // 30일 비교에 직전 30일이 필요

        private val _state = MutableStateFlow(LiveState())
        val state: StateFlow<LiveState> = _state

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
