package com.brimedge.voiceassistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.brimedge.voiceassistant.R
import com.brimedge.voiceassistant.controllers.AppLauncher
import com.brimedge.voiceassistant.controllers.FlashlightController
import com.brimedge.voiceassistant.controllers.TimeController
import com.brimedge.voiceassistant.databinding.ActivityMainBinding
import com.brimedge.voiceassistant.tts.SpeechManager
import com.brimedge.voiceassistant.voice.Command
import com.brimedge.voiceassistant.voice.CommandParser
import com.brimedge.voiceassistant.voice.VoskSpeechRecognizer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recognizer: VoskSpeechRecognizer
    private lateinit var tts: SpeechManager
    private lateinit var torch: FlashlightController
    private lateinit var launcher: AppLauncher

    private var modelReady = false
    private var listening = false

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        torch = FlashlightController(this)
        launcher = AppLauncher(this)

        tts = SpeechManager(this)
        tts.initialize(this, object : SpeechManager.Listener {
            override fun onReady(available: Boolean) {}
            override fun onMissingVoiceData() {
                setStatus(getString(R.string.status_tts_missing))
                val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                try { startActivity(installIntent) } catch (_: Exception) {}
            }
            override fun onDone() {
                if (modelReady) setStatus(getString(R.string.status_ready))
            }
        })

        recognizer = VoskSpeechRecognizer(this)

        binding.micButton.setOnClickListener {
            if (!hasPermissions()) {
                ActivityCompat.requestPermissions(this, requiredPermissions, 100)
                return@setOnClickListener
            }
            if (!modelReady) {
                setStatus(getString(R.string.status_loading_model))
                initRecognizer()
                return@setOnClickListener
            }
            if (listening) stopListening() else startListening()
        }

        if (hasPermissions()) initRecognizer()
        else ActivityCompat.requestPermissions(this, requiredPermissions, 100)
    }

    private fun initRecognizer() {
        setStatus(getString(R.string.status_loading_model))
        recognizer.initialize(object : VoskSpeechRecognizer.Callback {
            override fun onReady() {
                modelReady = true
                setStatus(getString(R.string.status_ready))
            }
            override fun onPartial(text: String) {
                binding.transcript.text = text
            }
            override fun onFinal(text: String) {
                binding.transcript.text = text
                handleCommand(text)
            }
            override fun onError(message: String) {
                setStatus(message)
            }
            override fun onTimeout() {
                if (listening) { stopListening(); tts.speak(getString(R.string.reply_not_understood)) }
            }
        })
    }

    private fun startListening() {
        listening = true
        binding.transcript.text = ""
        setStatus(getString(R.string.status_listening))
        recognizer.startListening()
    }

    private fun stopListening() {
        listening = false
        recognizer.stopListening()
        setStatus(getString(R.string.status_ready))
    }

    private fun handleCommand(text: String) {
        setStatus(getString(R.string.status_recognizing))
        val command = CommandParser.parse(text)
        setStatus(getString(R.string.status_executing))
        val reply = when (command) {
            Command.TorchOn -> {
                if (torch.setEnabled(true)) getString(R.string.reply_torch_on)
                else getString(R.string.reply_torch_missing)
            }
            Command.TorchOff -> {
                if (torch.setEnabled(false)) getString(R.string.reply_torch_off)
                else getString(R.string.reply_torch_missing)
            }
            Command.OpenWhatsApp -> {
                if (launcher.openWhatsApp()) getString(R.string.reply_open_whatsapp)
                else getString(R.string.reply_whatsapp_missing)
            }
            Command.OpenCamera -> {
                if (launcher.openCamera()) getString(R.string.reply_open_camera)
                else getString(R.string.reply_camera_missing)
            }
            Command.CurrentTime -> TimeController.currentTimeSpoken()
            Command.Unsupported -> getString(R.string.reply_unsupported)
            Command.Unknown -> getString(R.string.reply_not_understood)
        }
        setStatus(getString(R.string.status_speaking))
        tts.speak(reply)
        stopListening()
    }

    private fun setStatus(text: String) { binding.status.text = text }

    private fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) initRecognizer()
        else setStatus(getString(R.string.status_permissions_needed))
    }

    override fun onDestroy() {
        recognizer.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
