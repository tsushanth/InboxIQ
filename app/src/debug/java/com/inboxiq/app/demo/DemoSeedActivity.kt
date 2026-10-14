package com.inboxiq.app.demo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only launcher for store screenshots:
 * `am start -n <pkg>/com.inboxiq.app.demo.DemoSeedActivity --ez demo_seed true [--es demo_thread <address>]`.
 * Seeds InboxIQ's own database with fake conversations, marks onboarding done, records the fake contact
 * names for [DemoHooks], then opens the normal app. Does nothing unless demo_seed is true.
 */
class DemoSeedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.getBooleanExtra(EXTRA_SEED, false)) { finish(); return }
        val thread = intent.getStringExtra(EXTRA_THREAD)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val photo = File(filesDir, "demo_photo.png").also { writePhoto(it) }
                DemoSeeder.seed(AppDatabase.get(applicationContext), System.currentTimeMillis(), photo.toUri().toString())
                val demo = getSharedPreferences(DemoHooks.PREFS, MODE_PRIVATE).edit().putBoolean(DemoHooks.KEY_ACTIVE, true)
                DemoSeeder.contacts.forEach { c -> c.name?.let { demo.putString(DemoHooks.NAME_PREFIX + c.address, it) } }
                demo.commit()
                getSharedPreferences("inboxiq_prefs", MODE_PRIVATE).edit().putBoolean("onboarding_done", true).commit()
            }
            val open = Intent(this@DemoSeedActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            thread?.let { open.putExtra(MainActivity.EXTRA_THREAD_ADDRESS, it) }
            startActivity(open)
            finish()
        }
    }

    /** A plain generated gradient, so the MMS placeholder is not anyone's real photo. */
    private fun writePhoto(file: File) {
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 600f, 600f, Color.rgb(255, 183, 94), Color.rgb(72, 99, 168), Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, 600f, 600f, paint)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    companion object {
        const val EXTRA_SEED = "demo_seed"
        const val EXTRA_THREAD = "demo_thread"
    }
}
