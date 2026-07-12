package com.sinun.agent.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * מלאי האפליקציות המותקנות. סורק את כל החבילות במכשיר ומכין רשימה לדיווח לשרת,
 * כדי שהמנהל יראה בפאנל אילו אפליקציות קיימות ויוכל לחסום/לפתוח כל אחת בנפרד.
 *
 * דורש QUERY_ALL_PACKAGES (מוצהר ב-Manifest) כדי לראות את כל האפליקציות ב-Android 11+.
 */
class AppInventory(private val context: Context) {

    private val pm = context.packageManager

    /** מחזיר JSONArray של {package_name, app_name, is_system, installer} לכל אפליקציה. */
    fun collect(): JSONArray {
        val arr = JSONArray()
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        for (ai in apps) {
            val pkg = ai.packageName
            if (pkg == context.packageName) continue  // לא מדווחים על עצמנו
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            arr.put(
                JSONObject()
                    .put("package_name", pkg)
                    .put("app_name", label)
                    .put("is_system", isSystem)
                    .put("installer", installerOf(pkg)),
            )
        }
        return arr
    }

    private fun installerOf(pkg: String): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    }.getOrNull()
}
