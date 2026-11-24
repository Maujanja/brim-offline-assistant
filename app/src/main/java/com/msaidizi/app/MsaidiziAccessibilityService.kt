package com.msaidizi.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Huduma ya Accessibility ya Msaidizi. Inatumika kwa mambo manne tu:
 *  1) Kufungua apps/kamera/mipangilio kutoka nyuma (Android inaruhusu huduma za Accessibility kuanzisha skrini
 *     hata bila "Display over other apps"; hii inafanya kazi vizuri kuliko arifa ya kugusa).
 *  2) Kufunga skrini (lock).
 *  3) Kugusa swichi ya data ya simu kwenye paneli ya mipangilio (jitihada bora; muundo hutofautiana kwa kila simu).
 *  4) Kusoma maandishi ya skrini iliyo wazi, TU pale unapoomba kwa sauti ("soma skrini").
 * Haihifadhi wala kutuma maudhui ya skrini popote.
 */
class MsaidiziAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MsaidiziAccessibilityService? = null
        private const val TAG = "Msaidizi"
        private val GENERIC_DATA_LABELS = listOf(
            "mobile data", "data ya simu", "mtandao wa simu", "mobile network", "data ya mkononi", "mobile internet"
        )
        private val CONFIRM_LABELS = listOf(
            "ok", "yes", "switch", "turn on", "turn off", "ndiyo", "sawa", "washa", "zima", "badilisha", "allow"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ------------------------------------------------------------ vitendo

    fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /** Inakusanya maandishi yanayoonekana kwenye skrini iliyo wazi, kwa mpangilio wa kusoma (juu hadi chini). */
    fun readScreenText(maxChars: Int = 8000): String {
        val root = rootInActiveWindow ?: return ""
        val parts = ArrayList<String>()
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || ++count > 2500) return
            if (!n.isVisibleToUser) return
            val own = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val piece = when {
                n.isPassword -> ""
                own.isNotEmpty() -> own
                n.childCount == 0 && desc.isNotEmpty() -> desc
                else -> ""
            }
            if (piece.isNotEmpty() && parts.lastOrNull() != piece) parts.add(piece)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        try {
            walk(root, 0)
        } catch (e: Exception) {
            Log.w(TAG, "readScreenText", e)
        }
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.length + p.length > maxChars) break
            sb.append(p)
            if (!p.last().let { it == '.' || it == '!' || it == '?' || it == ':' }) sb.append('.')
            sb.append(' ')
        }
        return sb.toString().trim()
    }

    fun lockScreen(): Boolean =
        Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    /**
     * Inatafuta safu ya "data ya simu" (kwa jina la mtandao wa SIM husika, au maneno ya kawaida) kwenye skrini
     * iliyo wazi na kugusa swichi yake. Inaita kutoka thread ya nyuma. Inarudisha true ikifanikiwa.
     * Kwa usalama, inagusa swichi tu pale safu ina swichi MOJA (haitagusa Wi-Fi kwa bahati mbaya).
     */
    fun toggleMobileData(carrierLabels: List<String>, on: Boolean): Boolean {
        val candidates = (carrierLabels + GENERIC_DATA_LABELS).filter { it.isNotBlank() }
        val deadline = SystemClock.elapsedRealtime() + 9000
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(600)
            val root = rootInActiveWindow ?: continue
            for (label in candidates) {
                val nodes = try {
                    root.findAccessibilityNodeInfosByText(label)
                } catch (e: Exception) {
                    emptyList<AccessibilityNodeInfo>()
                }
                for (node in nodes) {
                    val sw = singleSwitchNear(node) ?: continue
                    if (sw.isChecked == on) return true            // tayari iko hivyo
                    if (clickNode(sw)) {
                        Thread.sleep(800)
                        confirmDialog()
                        return true
                    }
                }
            }
        }
        return false
    }

    // ------------------------------------------------------------ usaidizi

    /** Panda juu kwa wazazi hadi tupate kikundi chenye swichi moja haswa. Zaidi ya moja = utata, acha. */
    private fun singleSwitchNear(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = start
        for (level in 0..5) {
            val c = cur ?: return null
            val found = ArrayList<AccessibilityNodeInfo>()
            collectCheckable(c, found, 0)
            if (found.size == 1) return found[0]
            if (found.size > 1) return null
            cur = c.parent
        }
        return null
    }

    private fun collectCheckable(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n.isCheckable) {
            out.add(n)
            return
        }
        if (depth > 5) return
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            collectCheckable(child, out, depth + 1)
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        for (i in 0..4) {
            val c = cur ?: return false
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = c.parent
        }
        return false
    }

    /** Baadhi ya simu huuliza uthibitisho ("Badilisha data kwenda SIM 2?"). */
    private fun confirmDialog() {
        try {
            val root = rootInActiveWindow ?: return
            for (label in CONFIRM_LABELS) {
                for (n in root.findAccessibilityNodeInfosByText(label)) {
                    val text = n.text?.toString()?.trim() ?: continue
                    val isButton = n.className?.toString()?.contains("Button") == true
                    if (isButton && text.equals(label, ignoreCase = true) && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "confirmDialog", e)
        }
    }
}
