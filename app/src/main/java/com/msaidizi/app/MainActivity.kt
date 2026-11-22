package com.msaidizi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.Porcupine
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var accessKeyInput: EditText
    private lateinit var keywordSpinner: Spinner
    private lateinit var ppnStatus: TextView
    private lateinit var langEn: RadioButton
    private lateinit var langSw: RadioButton
    private lateinit var status: TextView

    private val prefs by lazy { getSharedPreferences(VoiceService.PREFS, MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (micGranted()) startVoiceService() else toast(getString(R.string.need_mic))
        }

    private val ppnPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPpn(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        accessKeyInput = findViewById(R.id.accessKeyInput)
        keywordSpinner = findViewById(R.id.keywordSpinner)
        ppnStatus = findViewById(R.id.ppnStatus)
        langSw = findViewById(R.id.langSw)
        langEn = findViewById(R.id.langEn)
        status = findViewById(R.id.status)

        val names = Porcupine.BuiltInKeyword.values().map { it.name }
        keywordSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        accessKeyInput.setText(prefs.getString(VoiceService.KEY_ACCESS, ""))
        keywordSpinner.setSelection(
            names.indexOf(prefs.getString(VoiceService.KEY_KEYWORD, "JARVIS")).coerceAtLeast(0)
        )
        if (prefs.getString(VoiceService.KEY_LANG, "sw-TZ") == "en-US") langEn.isChecked = true
        else langSw.isChecked = true
        refreshPpnStatus()

        findViewById<Button>(R.id.ppnBtn).setOnClickListener {
            ppnPicker.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.ppnClearBtn).setOnClickListener {
            prefs.edit().remove(VoiceService.KEY_PPN).apply()
            refreshPpnStatus()
        }

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            val key = accessKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                toast(getString(R.string.need_key))
                return@setOnClickListener
            }
            prefs.edit()
                .putString(VoiceService.KEY_ACCESS, key)
                .putString(VoiceService.KEY_KEYWORD, keywordSpinner.selectedItem as String)
                .putString(VoiceService.KEY_LANG, if (langEn.isChecked) "en-US" else "sw-TZ")
                .apply()

            // Anzisha upya ili mipangilio mipya ichukuliwe
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            if (micGranted()) startVoiceService() else requestPerms()
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            status.text = getString(R.string.status_stopped)
        }

        findViewById<Button>(R.id.overlayBtn).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun importPpn(uri: Uri) {
        try {
            val dest = File(filesDir, "wake.ppn")
            contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            prefs.edit().putString(VoiceService.KEY_PPN, dest.absolutePath).apply()
            refreshPpnStatus()
        } catch (e: Exception) {
            toast("Imeshindwa kusoma faili: ${e.message}")
        }
    }

    private fun refreshPpnStatus() {
        val path = prefs.getString(VoiceService.KEY_PPN, "").orEmpty()
        ppnStatus.text = if (path.isNotBlank()) getString(R.string.ppn_custom)
        else getString(R.string.ppn_builtin)
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startVoiceService() {
        // Subiri kidogo ili STOP ya awali imalize kabla ya kuanza upya
        status.postDelayed({
            ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
            val custom = prefs.getString(VoiceService.KEY_PPN, "").orEmpty().isNotBlank()
            val word = if (custom) getString(R.string.ppn_custom_short) else keywordSpinner.selectedItem as String
            status.text = getString(R.string.status_started, word)
        }, 400)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
