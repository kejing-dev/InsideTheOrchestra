package io.embodit.insideorchestra.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Transparent helper for the eye-tracking permission `com.picovr.permission.EYE_TRACKING` (SDK doc "Eye tracking":
 * a runtime permission; taken from Rehearsal Hall). Asked once on the first start; refused or no dialog: pointing
 * falls back to the head direction (design `container.permissions`).
 */
class EyePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (granted(this)) {
            finish()
            return
        }
        requestPermissions(arrayOf(PERMISSION), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        OrchLog.i("EYE permission granted=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
        finish()
    }

    companion object {
        const val PERMISSION = "com.picovr.permission.EYE_TRACKING"
        private const val REQUEST = 53

        fun granted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        /** Asks once per install (SharedPreferences flag); a no-op afterwards or when already granted. */
        fun askOnce(context: Context) {
            if (granted(context)) return
            val prefs = context.getSharedPreferences("orchestra", Context.MODE_PRIVATE)
            if (prefs.getBoolean("eyeAsked", false)) return
            prefs.edit().putBoolean("eyeAsked", true).apply()
            runCatching { context.startActivity(Intent(context, EyePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { OrchLog.w("EYE permission request failed: $it") }
        }
    }
}
