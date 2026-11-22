package com.msaidizi.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

/** Inacheza nyimbo zilizo kwenye simu kwa jina (mwimbaji, wimbo au albamu). Inafanya kazi bila kufungua skrini. */
class MusicPlayer(private val ctx: Context) {

    private data class Track(val id: Long, val title: String, val artist: String)

    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<Track> = emptyList()
    private var index = 0
    private var player: MediaPlayer? = null
    private var generation = 0          // inazuia nyimbo za zamani kuanza baada ya amri mpya
    private var wasPlaying = false

    // ------------------------------------------------------------ amri

    fun play(spoken: String): String {
        if (!hasPermission()) return "Ruhusu kusoma muziki ndani ya app kwanza"
        val tracks = search(spoken)
        if (tracks.isEmpty()) {
            return if (spoken.isBlank()) "Sijapata muziki kwenye simu yako"
            else "Sijapata wimbo wa $spoken kwenye simu yako"
        }
        queue = tracks
        index = 0
        startCurrent(1800)   // subiri TTS imalize kusema kwanza
        return "Nacheza ${tracks[0].title}"
    }

    fun next(): String {
        if (queue.isEmpty()) return "Hakuna wimbo unaochezwa"
        index = (index + 1) % queue.size
        startCurrent(0)
        return "Nacheza ${queue[index].title}"
    }

    fun pause(): String {
        wasPlaying = false
        val p = player ?: return "Hakuna wimbo unaochezwa"
        try { if (p.isPlaying) p.pause() } catch (_: Exception) {}
        return "Nimesimamisha"
    }

    fun resume(): String {
        val p = player ?: return "Hakuna wimbo wa kuendeleza"
        try { p.start() } catch (_: Exception) {}
        return "Naendelea"
    }

    fun stop(): String {
        generation++
        wasPlaying = false
        releasePlayer()
        queue = emptyList()
        return "Nimeacha muziki"
    }

    /** Muziki unasimamishwa kwa muda wakati amri inasikilizwa (ili usizuie maikrofoni). */
    fun pauseForCommand() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                wasPlaying = true
            }
        } catch (_: Exception) {}
    }

    fun resumeAfterCommand() {
        if (!wasPlaying) return
        wasPlaying = false
        try { player?.start() } catch (_: Exception) {}
    }

    fun release() {
        generation++
        releasePlayer()
    }

    // ------------------------------------------------------------ ndani

    private fun startCurrent(delayMs: Long) {
        val my = ++generation
        wasPlaying = false
        releasePlayer()
        val track = queue.getOrNull(index) ?: return
        handler.postDelayed({
            if (my != generation) return@postDelayed
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(
                    ctx, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
                )
                mp.setOnPreparedListener { if (my == generation) it.start() }
                mp.setOnCompletionListener {
                    if (my == generation && index + 1 < queue.size) {
                        index++
                        startCurrent(0)
                    }
                }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w("Msaidizi", "MediaPlayer error $what/$extra")
                    if (my == generation && index + 1 < queue.size) {
                        index++
                        startCurrent(0)
                    }
                    true
                }
                player = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                Log.e("Msaidizi", "play failed", e)
            }
        }, delayMs)
    }

    private fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }

    private fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Jina tupu = nyimbo zote kwa mpangilio wa nasibu. */
    private fun search(spoken: String): List<Track> {
        val query = normalize(spoken)
        val words = query.split(" ").filter { it.isNotEmpty() }
        val scored = ArrayList<Pair<Int, Track>>()

        val cursor = ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DISPLAY_NAME
            ),
            "${MediaStore.Audio.Media.DURATION} >= 20000",   // puuza milio mifupi ya arifa
            null, null
        ) ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                val title = it.getString(1) ?: it.getString(4) ?: continue
                val artist = it.getString(2) ?: ""
                val album = it.getString(3) ?: ""
                val file = it.getString(4) ?: ""
                val track = Track(it.getLong(0), title, artist)
                if (query.isEmpty()) {
                    scored.add(0 to track)
                    continue
                }
                val nt = normalize(title)
                val all = normalize("$title $artist $album $file")
                val score = when {
                    nt == query -> 100
                    nt.startsWith(query) -> 90
                    nt.contains(query) -> 80
                    all.contains(query) -> 70
                    words.all { w -> all.contains(w) } -> 60
                    else -> {
                        val hits = words.count { w -> w.length > 2 && all.contains(w) }
                        if (hits > 0) 10 * hits else 0
                    }
                }
                if (score > 0) scored.add(score to track)
            }
        }
        return if (query.isEmpty()) scored.map { p -> p.second }.shuffled().take(100)
        else scored.sortedByDescending { p -> p.first }.map { p -> p.second }.take(30)
    }
}
