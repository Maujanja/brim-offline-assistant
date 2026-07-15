package com.brimedge.voiceassistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.brimedge.voiceassistant.databinding.ActivityMainBinding
import com.brimedge.voiceassistant.service.WakeWordService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var running = false

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CAMERA,
        Manifest.permission.MODIFY_AUDIO_SETTINGS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleBtn.setOnClickListener {
            if (!hasAllPermissions()) {
                ActivityCompat.requestPermissions(this, requiredPermissions, 42)
                return@setOnClickListener
            }
            if (running) stopService() else startService()
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAllPermissions()) startService()
    }

    private fun startService() {
        val i = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        running = true
        binding.toggleBtn.text = getString(com.brimedge.voiceassistant.R.string.stop_service)
        binding.status.text = getString(com.brimedge.voiceassistant.R.string.listening)
    }

    private fun stopService() {
        stopService(Intent(this, WakeWordService::class.java))
        running = false
        binding.toggleBtn.text = getString(com.brimedge.voiceassistant.R.string.start_service)
        binding.status.text = getString(com.brimedge.voiceassistant.R.string.idle)
    }
}
