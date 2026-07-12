package com.sinun.agent.data

import android.content.Context
import androidx.core.content.edit
import com.sinun.agent.net.ApiClient
import com.sinun.agent.data.local.CachedPolicy
import com.sinun.agent.data.local.PolicyDb
import com.sinun.agent.monitor.AppInventory
import org.json.JSONObject

/**
 * מקור האמת ל-policy במכשיר:
 * 1. מנסה למשוך מהשרת ולעדכן את ה-cache.
 * 2. אם השרת לא זמין — עובד לפי ה-cache.
 * 3. אין cache → fail closed (המתקשר מחליט מה לחסום).
 */
class PolicyRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("sinun", Context.MODE_PRIVATE)
    private val db = PolicyDb.get(context)
    private val api = ApiClient()

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        private set(value) = prefs.edit { putString("device_id", value) }

    val isEnrolled: Boolean get() = deviceId != null

    /** הצטרפות עם הקוד החד-פעמי שהמנהל מסר ללקוח. */
    suspend fun enroll(code: String, agentVersion: String): String {
        deviceId?.let { return it }
        val id = api.enrollDevice(code, agentVersion)
        deviceId = id
        return id
    }

    /** מרענן policy מהשרת; מחזיר את ה-policy הפעיל (מהשרת או מה-cache). */
    suspend fun refreshPolicy(): PolicyState {
        val id = deviceId ?: return PolicyState.NoPolicy
        return try {
            val raw = api.fetchPolicy(id)
            db.policyDao().save(
                CachedPolicy(
                    policyId = raw.getString("policy_id"),
                    version = raw.getLong("version"),
                    rawJson = raw.toString(),
                    fetchedAt = System.currentTimeMillis(),
                )
            )
            PolicyState.Active(raw, fromCache = false)
        } catch (e: Exception) {
            cachedPolicy() // שרת לא זמין — נופלים ל-cache
        }
    }

    suspend fun cachedPolicy(): PolicyState {
        val cached = db.policyDao().get() ?: return PolicyState.NoPolicy
        return PolicyState.Active(JSONObject(cached.rawJson), fromCache = true)
    }

    suspend fun heartbeat(protectionStatus: String) {
        val id = deviceId ?: return
        runCatching { api.heartbeat(id, protectionStatus) }
    }

    suspend fun reportEvent(eventType: String, details: JSONObject? = null) {
        val id = deviceId ?: return
        runCatching { api.reportEvent(id, eventType, details) }
    }

    /** אוסף את רשימת האפליקציות המותקנות ושולח לשרת (למלאי הפאנל). */
    suspend fun reportInstalledApps() {
        val id = deviceId ?: return
        runCatching { api.reportApps(id, AppInventory(appContext).collect()) }
    }

    suspend fun requestOpening(type: String, target: String, reason: String) {
        val id = deviceId ?: error("device not registered")
        api.createOpeningRequest(id, type, target, reason)
    }

    /** שולח בקשת הסרת הסינון למנהל. */
    suspend fun requestRemoval(reason: String) {
        val id = deviceId ?: error("device not registered")
        api.requestRemoval(id, reason)
    }

    /**
     * מאמת קוד הסרה חד-פעמי מול השרת.
     * @return true אם הקוד תקין (הסרה מאושרת), false אם לא.
     */
    suspend fun verifyUninstallCode(code: String): Boolean {
        val id = deviceId ?: return false
        return runCatching { api.verifyUninstallCode(id, code) }.getOrDefault(false)
    }
}

sealed interface PolicyState {
    /** יש policy פעיל (טרי מהשרת או מה-cache המקומי). */
    data class Active(val policy: JSONObject, val fromCache: Boolean) : PolicyState

    /** אין policy בכלל → fail closed: חסימת גלישה, ערוץ תמיכה בלבד. */
    data object NoPolicy : PolicyState
}
