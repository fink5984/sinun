package com.sinun.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.sinun.agent.data.PolicyRepository

class SinunApp : Application() {

    lateinit var policyRepository: PolicyRepository
        private set

    override fun onCreate() {
        super.onCreate()
        policyRepository = PolicyRepository(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sinun Protection",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sinun_protection"
    }
}
