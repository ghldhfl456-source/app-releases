package com.netmonitor.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.Locale

enum class Connection(val label: String) {
    WIFI("Wi-Fi"),
    MOBILE("모바일 데이터"),
    OTHER("기타 연결"),
    NONE("연결 없음"),
}

fun currentConnection(context: Context): Connection {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Connection.NONE
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return Connection.NONE) ?: return Connection.NONE
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Connection.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Connection.MOBILE
        else -> Connection.OTHER
    }
}

/** 기록 On/Off 상태. 재부팅 뒤 다시 켤지 정할 때도 쓴다. */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

fun formatBytes(bytes: Double): String {
    val abs = kotlin.math.abs(bytes)
    return when {
        abs >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        abs >= 1024.0 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        abs >= 1024.0 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.0f B", bytes)
    }
}

fun formatSpeed(bytesPerSec: Double): String = when {
    bytesPerSec >= 1024.0 * 1024 -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
}
