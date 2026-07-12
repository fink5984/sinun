package com.sinun.agent.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device Admin — הגנת ביניים נגד הסרה (בלי Device Owner).
 *
 * כשההרשאה פעילה, לא ניתן להסיר את האפליקציה לפני ביטול ההרשאה ידנית
 * (המערכת חוסמת uninstall ומפנה להגדרות). זה מרתיע, אך לא מונע לחלוטין —
 * מניעה מלאה (חסימת ההסרה עצמה, force-stop, Safe Mode) דורשת Device Owner.
 */
class SinunDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "ביטול ההגנה יחשוף את המכשיר. הפעולה תדווח למנהל."

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, SinunDeviceAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            return dpm.isAdminActive(component(context))
        }

        /** Intent להפעלת ההרשאה (מציג מסך אישור מערכת). */
        fun activationIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "הפעלת ההגנה מונעת הסרה לא מורשית של אפליקציית הסינון.",
                )
            }

        /**
         * מבטל את הרשאת Device Admin — נקרא לאחר אימות קוד הסרה תקין מהמנהל.
         * ביטול ה-Admin הוא תנאי מקדים להסרת האפליקציה.
         */
        fun deactivate(context: Context) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            dpm.removeActiveAdmin(component(context))
        }
    }
}
