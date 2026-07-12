package com.sinun.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sinun.agent.work.HeartbeatWorker

/** אחרי reboot — מוודאים שה-heartbeat מתוזמן. (שבוע 5: גם החזרת VPN אוטומטית.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            HeartbeatWorker.schedule(context)
        }
    }
}
