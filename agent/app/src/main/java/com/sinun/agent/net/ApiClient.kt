package com.sinun.agent.net

import android.os.Build
import com.sinun.agent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** לקוח ה-API מול שרת הניהול. כל הקריאות סינכרוניות-ברקע (IO dispatcher). */
class ApiClient(private val baseUrl: String = BuildConfig.API_BASE_URL) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /** הצטרפות עם קוד חד-פעמי שהמנהל מסר ללקוח; מחזיר device_id. */
    suspend fun enrollDevice(code: String, agentVersion: String): String = withContext(Dispatchers.IO) {
        val body = deviceInfo(agentVersion).put("code", code.trim().uppercase())
        post("/api/devices/enroll", body).getString("id")
    }

    /** רישום ישיר ללא קוד — לפיתוח/בדיקות בלבד. */
    suspend fun registerDevice(agentVersion: String): String = withContext(Dispatchers.IO) {
        post("/api/devices/register", deviceInfo(agentVersion)).getString("id")
    }

    private fun deviceInfo(agentVersion: String) = JSONObject()
        .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("android_version", Build.VERSION.RELEASE)
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("agent_version", agentVersion)

    suspend fun heartbeat(deviceId: String, protectionStatus: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("protection_status", protectionStatus)
        post("/api/devices/heartbeat", body)
    }

    /** מושך את ה-policy העדכני; מחזיר את ה-JSON הגולמי לשמירה ב-cache. */
    suspend fun fetchPolicy(deviceId: String): JSONObject = withContext(Dispatchers.IO) {
        get("/api/devices/$deviceId/policy")
    }

    /** מדווח את רשימת האפליקציות המותקנות לשרת (למלאי הפאנל). */
    suspend fun reportApps(deviceId: String, apps: JSONArray) = withContext(Dispatchers.IO) {
        post("/api/devices/$deviceId/apps", JSONObject().put("apps", apps))
    }

    suspend fun reportEvent(deviceId: String, eventType: String, details: JSONObject? = null) =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("event_type", eventType)
            if (details != null) body.put("details", details)
            post("/api/devices/$deviceId/events", body)
        }

    suspend fun createOpeningRequest(deviceId: String, type: String, target: String, reason: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("request_type", type)
                .put("target", target)
                .put("reason", reason)
            post("/api/requests", body)
        }

    /** מבקש הסרת אפליקציית הסינון — שולח בקשת removal לשרת. */
    suspend fun requestRemoval(deviceId: String, reason: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("request_type", "removal")
                .put("target", "sinun_agent")
                .put("reason", reason)
            post("/api/requests", body)
        }

    /** מאמת קוד הסרה חד-פעמי מול השרת. זורק ApiException אם לא תקין. */
    suspend fun verifyUninstallCode(deviceId: String, code: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("code", code.trim())
            val res = post("/api/devices/$deviceId/verify-uninstall", body)
            res.optBoolean("authorized", false)
        }

    private fun post(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toString().toRequestBody(json))
            .build()
        return execute(request)
    }

    private fun get(path: String): JSONObject {
        val request = Request.Builder().url(baseUrl + path).get().build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, text)
            }
            return JSONObject(text)
        }
    }
}

class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")
