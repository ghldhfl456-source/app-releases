package com.netmonitor.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.netmonitor.app.Prefs

/** 재부팅이나 앱 업데이트 뒤, 꺼 두지 않았다면 기록을 다시 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.isEnabled(context)) MonitorService.start(context)
    }
}
