package com.msaidizi.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var wakeInput: EditText
    private lateinit var langSw: RadioButton
    private lateinit var langEn: RadioButton
    private lateinit var status: TextView
    private lateinit var sttUrl: EditText
    private lateinit var sttKey: EditText
    private lateinit var sttModel: EditText
    private lateinit var sttCmd: CheckBox
    private var testInput: SpeechInput? = null

    private val prefs by lazy { getSharedPreferences(VoiceService.PREFS, MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (micGranted()) startVoiceService() else toast(getString(R.string.need_mic))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wakeInput = findViewById(R.id.wakeInput)
        langSw = findViewById(R.id.langSw)
        langEn = findViewById(R.id.langEn)
        status = findViewById(R.id.status)
        sttUrl = findViewById(R.id.sttUrl)
        sttKey = findViewById(R.id.sttKey)
        sttModel = findViewById(R.id.sttModel)
        sttCmd = findViewById(R.id.sttCmd)

        wakeInput.setText(prefs.getString(VoiceService.KEY_WAKE, VoiceService.DEFAULT_WAKE))
        if (prefs.getString(VoiceService.KEY_LANG, "sw-TZ") == "en-US") langEn.isChecked = true
        else langSw.isChecked = true
        sttUrl.setText(prefs.getString(VoiceService.KEY_STT_URL, ""))
        sttKey.setText(prefs.getString(VoiceService.KEY_STT_KEY, ""))
        sttModel.setText(prefs.getString(VoiceService.KEY_STT_MODEL, "whisper-1"))
        sttCmd.isChecked = prefs.getBoolean(VoiceService.KEY_STT_CMD, false)

        findViewById<Button>(R.id.presetOpenAi).setOnClickListener {
            sttUrl.setText("https://api.openai.com/v1/audio/transcriptions")
            sttModel.setText("whisper-1")
        }
        findViewById<Button>(R.id.presetGroq).setOnClickListener {
            sttUrl.setText("https://api.groq.com/openai/v1/audio/transcriptions")
            sttModel.setText("whisper-large-v3")
        }

        findViewById<Button>(R.id.sttTestBtn).setOnClickListener { testStt() }

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            val wake = wakeInput.text.toString().trim().lowercase()
            if (wake.isEmpty()) {
                toast(getString(R.string.need_wake))
                return@setOnClickListener
            }
            saveSettings()

            // Anzisha upya ili mipangilio mipya ichukuliwe
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            if (missingPerms().isEmpty()) startVoiceService() else requestPerms()
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            status.text = getString(R.string.status_stopped)
        }

        findViewById<Button>(R.id.a11yBtn).setOnClickListener {
            toast(getString(R.string.a11y_hint))
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.overlay_missing))
            }
        }

        findViewById<Button>(R.id.lockBtn).setOnClickListener {
            val admin = ComponentName(this, LockAdminReceiver::class.java)
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.lock_reason))
            )
        }

        findViewById<Button>(R.id.overlayBtn).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.overlay_missing))
            }
        }
    }

    private fun saveSettings() {
        prefs.edit()
            .putString(VoiceService.KEY_WAKE, wakeInput.text.toString().trim().lowercase())
            .putString(VoiceService.KEY_LANG, if (langEn.isChecked) "en-US" else "sw-TZ")
            .putString(VoiceService.KEY_STT_URL, sttUrl.text.toString().trim())
            .putString(VoiceService.KEY_STT_KEY, sttKey.text.toString().trim())
            .putString(VoiceService.KEY_STT_MODEL, sttModel.text.toString().trim().ifBlank { "whisper-1" })
            .putBoolean(VoiceService.KEY_STT_CMD, sttCmd.isChecked)
            .apply()
    }

    /** Inarekodi sekunde chache na kuzituma kwa API yako ili ujue kama mipangilio ni sahihi. */
    private fun testStt() {
        saveSettings()
        val cfg = SpeechInput.Config.load(this)
        if (!cfg.enabled) {
            toast(getString(R.string.stt_need))
            return
        }
        if (!micGranted()) {
            requestPerms()
            return
        }
        // Acha huduma ili maikrofoni iwe huru
        startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
        status.text = getString(R.string.stt_testing)
        val input = SpeechInput(this)
        testInput = input
        val lang = if (langEn.isChecked) "en" else "sw"
        status.postDelayed({
            input.listenApi(cfg, lang) { text, err ->
                status.text = if (!text.isNullOrBlank()) getString(R.string.stt_heard, text)
                else getString(R.string.stt_failed, err ?: "?")
            }
        }, 900)
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun missingPerms(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPerms() {
        permLauncher.launch(missingPerms().toTypedArray())
    }

    private fun startVoiceService() {
        // Subiri kidogo ili STOP ya awali imalize kabla ya kuanza upya
        status.postDelayed({
            ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
            status.text = getString(R.string.status_started, wakeInput.text.toString().trim())
        }, 400)
    }

    override fun onDestroy() {
        testInput?.cancel()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
