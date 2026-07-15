package com.brimedge.voiceassistant.commands

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Maps parsed Swahili voice commands to Android actions.
 *
 * Note: Some toggles (mobile data, airplane mode, ending calls) require system-level
 * permissions that non-system apps cannot hold on modern Android — those commands
 * open the corresponding Settings panel instead of toggling silently.
 */
class CommandExecutor(private val ctx: Context) {

    private val audio by lazy { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val camMgr by lazy { ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var torchOn = false

    fun execute(raw: String): String {
        val t = raw.lowercase(Locale.getDefault()).trim()
        return when {
            // Torch
            t.contains("washa tochi") -> setTorch(true)
            t.contains("zima tochi") -> setTorch(false)

            // Data / WiFi / BT / Airplane -> open panel
            t.contains("data") -> openPanel(Settings.ACTION_DATA_ROAMING_SETTINGS, "Fungua mipangilio ya data")
            t.contains("wifi") -> toggleWifi(t.contains("washa"))
            t.contains("bluetooth") -> toggleBluetooth(t.contains("washa"))
            t.contains("airplane") -> openPanel(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Fungua airplane mode")

            // Brightness
            t.contains("punguza mwanga") -> setBrightness(-40)
            t.contains("ongeza mwanga") -> setBrightness(+40)

            // Silent / sound
            t.contains("weka kimya") -> { audio.ringerMode = AudioManager.RINGER_MODE_SILENT; "Nimeweka kimya" }
            t.contains("washa sauti") -> { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL; "Sauti imewashwa" }

            // Volume
            t.contains("punguza sauti") -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); "Nimepunguza sauti" }
            t.contains("ongeza sauti") -> { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); "Nimeongeza sauti" }

            // Calls
            t.startsWith("nipigie simu") -> callByName(t.removePrefix("nipigie simu").trim())
            t.startsWith("piga namba") -> callNumber(t.removePrefix("piga namba").trim())
            t.contains("kata simu") -> "Kwa sababu za usalama, huwezi kukata simu kutoka kwa app. Tumia kitufe cha simu."
            t.contains("zima simu") -> openPanel(Settings.ACTION_SETTINGS, "Fungua mipangilio kuzima simu")

            // SMS
            t.contains("soma sms") -> readSms(t.removePrefix("soma sms").removePrefix("zangu").removePrefix("za").trim())
            t.startsWith("tuma sms") -> sendSms(t)

            // Music
            t.contains("cheza muziki") -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, "Ninacheza muziki")
            t.contains("simamisha muziki") -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, "Nimesimamisha")
            t.contains("endelea muziki") -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, "Naendelea")
            t.contains("wimbo unaofuata") -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, "Wimbo unaofuata")
            t.contains("wimbo uliopita") -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Wimbo uliopita")
            t.startsWith("cheza") -> playSong(t.removePrefix("cheza").trim())

            // Apps
            t.contains("fungua kamera") || t.contains("piga picha") -> openIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Nafungua kamera")
            t.contains("rudi nyumbani") -> {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(home); "Sawa"
            }
            t.startsWith("fungua") -> openAppByName(t.removePrefix("fungua").trim())

            // Info
            t.contains("saa ngapi") -> "Saa ni " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            t.contains("betri") -> batteryStatus()
            t.contains("status ya simu") -> batteryStatus()
            t.contains("siku gani") -> SimpleDateFormat("EEEE, d MMMM yyyy", Locale("sw")).format(Date())

            else -> "Samahani, sikukuelewa"
        }
    }

    // --- helpers --------------------------------------------------------------
    private fun setTorch(on: Boolean): String {
        return try {
            val id = camMgr.cameraIdList.firstOrNull { camMgr.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return "Hakuna tochi"
            camMgr.setTorchMode(id, on)
            torchOn = on
            if (on) "Tochi imewashwa" else "Tochi imezimwa"
        } catch (e: Exception) { "Imeshindikana kuwasha tochi" }
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(on: Boolean): String {
        // Direct WifiManager.setWifiEnabled is blocked on Android 10+. Open panel instead.
        return openPanel(Settings.Panel.ACTION_WIFI, if (on) "Fungua WiFi" else "Zima WiFi")
    }

    private fun toggleBluetooth(on: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "Nahitaji ruhusa ya Bluetooth"
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "Hakuna Bluetooth"
        @Suppress("DEPRECATION")
        if (on) adapter.enable() else adapter.disable()
        return if (on) "Bluetooth imewashwa" else "Bluetooth imezimwa"
    }

    private fun setBrightness(delta: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return "Nipe ruhusa ya kubadilisha mwanga"
        }
        val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val next = (cur + delta).coerceIn(10, 255)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        return "Mwanga umebadilishwa"
    }

    private fun openPanel(action: String, reply: String): String {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
        return reply
    }

    private fun openIntent(i: Intent, reply: String): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) { return "Imeshindikana" }
        return reply
    }

    private fun callByName(name: String): String {
        if (name.isBlank()) return "Nipe jina"
        val number = lookupNumber(name) ?: return "Sikupata $name"
        return callNumber(number)
    }

    private fun callNumber(number: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        if (n.isBlank()) return "Namba si sahihi"
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return "Nahitaji ruhusa ya simu"
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Ninapiga $n"
    }

    private fun lookupNumber(name: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cr: ContentResolver = ctx.contentResolver
        val c: Cursor? = cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )
        c?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun readSms(fromName: String): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return "Nahitaji ruhusa ya SMS"
        val where = if (fromName.isNotBlank()) {
            val num = lookupNumber(fromName) ?: return "Sikupata $fromName"
            "${Telephony.Sms.ADDRESS} LIKE '%${num.takeLast(9)}%'"
        } else null
        val c = ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            where, null, "${Telephony.Sms.DATE} DESC LIMIT 3"
        ) ?: return "Hakuna SMS"
        val sb = StringBuilder()
        c.use {
            while (it.moveToNext()) {
                sb.append("Kutoka ${it.getString(0)}: ${it.getString(1)}. ")
            }
        }
        return if (sb.isEmpty()) "Hakuna SMS mpya" else sb.toString()
    }

    private fun sendSms(raw: String): String {
        // Pattern: "tuma sms kwa <name> ujumbe <message>"
        val kwaIdx = raw.indexOf("kwa")
        val ujIdx = raw.indexOf("ujumbe")
        if (kwaIdx < 0 || ujIdx < 0 || ujIdx <= kwaIdx) return "Sema: tuma SMS kwa jina ujumbe maandishi"
        val name = raw.substring(kwaIdx + 3, ujIdx).trim()
        val msg = raw.substring(ujIdx + 6).trim()
        val number = lookupNumber(name) ?: return "Sikupata $name"
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "Nahitaji ruhusa ya SMS"
        return try {
            val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendTextMessage(number, null, msg, null, null)
            "SMS imetumwa kwa $name"
        } catch (e: Exception) { "Imeshindikana kutuma SMS" }
    }

    private fun mediaKey(code: Int, reply: String): String {
        val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)
        audio.dispatchMediaKeyEvent(event)
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return reply
    }

    private fun playSong(query: String): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try { ctx.startActivity(i); "Ninacheza $query" } catch (e: Exception) { "Sina app ya muziki" }
    }

    private fun openAppByName(name: String): String {
        if (name.isBlank()) return "Nipe jina la app"
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()).contains(name) }
            ?: return "Sikupata app hiyo"
        val launch = pm.getLaunchIntentForPackage(match.packageName) ?: return "Imeshindikana kufungua"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        return "Nafungua ${pm.getApplicationLabel(match)}"
    }

    private fun batteryStatus(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Betri ni asilimia $level"
    }
}
