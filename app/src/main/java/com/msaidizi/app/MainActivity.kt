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
    private lateinit var msgAlerts: CheckBox
    private lateinit var checkinOn: CheckBox
    private lateinit var grokKey: EditText
    private var typedHandler: CommandHandler? = null

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
        msgAlerts = findViewById(R.id.msgAlerts)
        checkinOn = findViewById(R.id.checkinOn)
        grokKey = findViewById(R.id.grokKey)

        wakeInput.setText(prefs.getString(VoiceService.KEY_WAKE, VoiceService.DEFAULT_WAKE))
        if (prefs.getString(VoiceService.KEY_LANG, "sw-TZ") == "en-US") langEn.isChecked = true
        else langSw.isChecked = true
        msgAlerts.isChecked = prefs.getBoolean(VoiceService.KEY_MSG_ALERTS, true)

        checkinOn.isChecked = prefs.getBoolean(Assistant.KEY_CHECKIN, true)
        findViewById<Button>(R.id.checkinNowBtn).setOnClickListener {
            saveSettings()
            val svc = VoiceService.instance
            if (svc == null) toast(getString(R.string.checkin_need_service))
            else {
                toast(getString(R.string.checkin_asking))
                svc.askCheckin(manual = true)
            }
        }
        findViewById<Button>(R.id.clearMemBtn).setOnClickListener {
            Memory.clear(this)
            MessageLog.clear(this)
            toast(getString(R.string.memory_cleared))
        }
        grokKey.setText(prefs.getString(AiClient.KEY_FREE_KEY, ""))
        findViewById<Button>(R.id.aiTestBtn).setOnClickListener { testAi() }

        findViewById<Button>(R.id.runCmdBtn).setOnClickListener {
            val text = findViewById<EditText>(R.id.cmdInput).text.toString().trim()
            if (text.isNotEmpty()) runTyped(text)
        }

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

        findViewById<Button>(R.id.notifAccessBtn).setOnClickListener {
            toast(getString(R.string.notif_access_hint))
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.overlay_missing))
            }
        }

        findViewById<Button>(R.id.batteryBtn).setOnClickListener {
            toast(getString(R.string.battery_hint))
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    toast(getString(R.string.overlay_missing))
                }
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
            .putString(VoiceService.KEY_STT_URL, "")
            .putString(VoiceService.KEY_STT_KEY, "")
            .putBoolean(VoiceService.KEY_STT_CMD, false)
            .putBoolean(Assistant.KEY_CHECKIN, checkinOn.isChecked)
            .putString(AiClient.KEY_FREE_KEY, grokKey.text.toString().trim())
            .putString(AiClient.KEY_FREE_URL, "")
            .putString(AiClient.KEY_FREE_MODEL, "")
            .putString(Speaker.Config.KEY_URL, "")
            .putString(Speaker.Config.KEY_KEY, "")
            .putString(Speaker.Config.KEY_VOICE, "")
            .putBoolean(VoiceService.KEY_MSG_ALERTS, msgAlerts.isChecked)
            .apply()
    }

    /** Inatekeleza amri iliyoandikwa (bila kutumia sauti) na kuonyesha jibu, kutenganisha tatizo la sauti na la amri. */
    private fun runTyped(text: String) {
        saveSettings()
        typedHandler?.shutdown()
        val ch = CommandHandler(this)
        typedHandler = ch
        status.text = getString(R.string.cmd_running)
        ch.execute(text) {
            status.text = getString(R.string.cmd_result, ch.lastReply.ifBlank { "(imefanyika)" })
        }
    }

    /** Inajaribu AI: "mpigie baba" inapaswa kurudi kama "piga baba". */
    private fun testAi() {
        saveSettings()
        if (!AiClient.available(this)) {
            toast(getString(R.string.ai_need))
            return
        }
        status.text = getString(R.string.ai_testing)
        Thread {
            val msg = try {
                val r = AiClient.clean(AiClient.ask(this, AiClient.COMMAND_CONTEXT, "mpigie baba")) ?: "NONE"
                val note = AiClient.lastSautiError?.let { " | Sauti yangu imeshindwa: $it" } ?: ""
                getString(R.string.ai_ok, r) + " [" + AiClient.lastProvider + "]" + note
            } catch (e: Exception) {
                getString(R.string.ai_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { status.text = msg }
        }.start()
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

    override fun onResume() {
        super.onResume()
        val trace = Trace.get(this)
        if (trace.isNotBlank()) status.text = getString(R.string.trace_last, trace)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
