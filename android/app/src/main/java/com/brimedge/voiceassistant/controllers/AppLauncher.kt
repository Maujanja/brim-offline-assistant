package com.brimedge.voiceassistant.controllers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

class AppLauncher(private val context: Context) {

    fun openWhatsApp(): Boolean {
        val pm = context.packageManager
        val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in candidates) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        }
        // Fallback: web
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(web); true } catch (_: Exception) { false }
    }

    fun openCamera(): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent); true
        } catch (_: Exception) {
            false
        }
    }
}
