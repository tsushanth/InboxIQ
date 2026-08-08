package com.inboxiq.app.sms

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log

/** Wraps the two ways Android lets an app become the default SMS handler. */
object DefaultSmsRole {

    fun isDefault(context: Context): Boolean {
        val legacy = Telephony.Sms.getDefaultSmsPackage(context)
        val roleHeld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            false
        }
        Log.d("DefaultSmsRole", "legacy=$legacy pkg=${context.packageName} roleHeld=$roleHeld")
        return roleHeld || legacy == context.packageName
    }

    /** Launches the system's "make this app your SMS app" prompt. Result handled in onActivityResult / ActivityResultContract. */
    fun requestIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            @Suppress("DEPRECATION")
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }
    }

    fun handledResult(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
