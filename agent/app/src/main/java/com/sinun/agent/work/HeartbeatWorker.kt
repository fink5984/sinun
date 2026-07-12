package com.sinun.agent.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sinun.agent.SinunApp
import com.sinun.agent.vpn.FilterVpnService
import java.util.concurrent.TimeUnit

/** heartbeat תקופתי לשרת + רענון policy. */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as SinunApp).policyRepository
        val status = if (FilterVpnService.running) "protected" else "unprotected"
        repo.heartbeat(status)
        repo.refreshPolicy()
        repo.reportInstalledApps()  // מלאי אפליקציות מעודכן לפאנל
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sinun_heartbeat",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
