package com.kydium.tecladoxp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * XP sounds, synthesized in code (no audio files): a short bright "pling" when you
 * earn XP, with a slightly random pitch each time, and a rising chime on level up.
 */
class SoundFx(private val ctx: Context) {
    private val rate = 44100
    private var orb: AudioTrack? = null
    private var levelUp: AudioTrack? = null
    private var lastOrb = 0L

    // user-chosen sounds (copied into the app's files folder from the settings screen)
    private var pool: SoundPool? = null
    private val customIds = HashMap<String, Int>()      // file name -> sound id
    private val customStamp = HashMap<String, Long>()   // file name -> lastModified when loaded
    private val loaded = HashSet<Int>()

    companion object {
        const val ORB_FILE = "sonido_xp"
        const val LEVEL_FILE = "sonido_nivel"
        fun customFile(ctx: Context, name: String) = File(ctx.filesDir, name)
    }

    /** Returns a ready SoundPool id for a custom file, or null to use the built-in sound. */
    private fun custom(name: String): Int? {
        val f = customFile(ctx, name)
        if (!f.exists()) {
            customIds.remove(name)
            return null
        }
        val p = pool ?: SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build().also { sp ->
                sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
                pool = sp
            }
        val stamp = f.lastModified()
        val old = customIds[name]
        if (old == null || customStamp[name] != stamp) {
            if (old != null) { p.unload(old); loaded.remove(old) }
            customIds[name] = p.load(f.absolutePath, 1)
            customStamp[name] = stamp
        }
        val id = customIds[name] ?: return null
        return if (loaded.contains(id)) id else -1   // -1 = still loading, stay quiet
    }

    /** Preload custom sounds so the first word already has sound. */
    fun preload() {
        try { custom(ORB_FILE); custom(LEVEL_FILE) } catch (_: Exception) {}
    }

    private fun bell(out: FloatArray, start: Int, freq: Double, dur: Double, vol: Double, decay: Double) {
        val n = min((dur * rate).toInt(), out.size - start)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val attack = min(1.0, t / 0.003)
            val env = attack * exp(-t / decay)
            val s = sin(2 * PI * freq * t) +
                0.35 * sin(2 * PI * freq * 2.0 * t) * exp(-t / (decay * 0.5)) +
                0.12 * sin(2 * PI * freq * 3.01 * t) * exp(-t / (decay * 0.3))
            out[start + i] += (s * env * vol).toFloat()
        }
    }

    private fun build(samples: FloatArray): AudioTrack? = try {
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track
    } catch (_: Exception) {
        null
    }

    private fun ensure() {
        if (orb == null) {
            val s = FloatArray((0.22 * rate).toInt())
            bell(s, 0, 1480.0, 0.22, 0.32, 0.055)
            orb = build(s)
        }
        if (levelUp == null) {
            val s = FloatArray((1.1 * rate).toInt())
            val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
            notes.forEachIndexed { i, f ->
                bell(s, (i * 0.11 * rate).toInt(), f, 1.1 - i * 0.11, if (i == 3) 0.34 else 0.26, if (i == 3) 0.35 else 0.14)
            }
            levelUp = build(s)
        }
    }

    private fun allowed(): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    private fun play(t: AudioTrack?, pitch: Float) {
        if (t == null) return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.playbackRate = (rate * pitch).toInt()
            t.reloadStaticData()
            t.play()
        } catch (_: Exception) {
        }
    }

    fun playOrb() {
        if (!allowed()) return
        val now = System.currentTimeMillis()
        if (now - lastOrb < 60) return
        lastOrb = now
        val pitch = 0.8f + Random.nextFloat() * 0.45f
        val c = try { custom(ORB_FILE) } catch (_: Exception) { null }
        if (c != null) {
            if (c > 0) pool?.play(c, 0.8f, 0.8f, 1, 0, pitch)
            return
        }
        ensure()
        play(orb, pitch)
    }

    fun playLevelUp() {
        if (!allowed()) return
        val c = try { custom(LEVEL_FILE) } catch (_: Exception) { null }
        if (c != null) {
            if (c > 0) pool?.play(c, 1f, 1f, 1, 0, 1f)
            return
        }
        ensure()
        play(levelUp, 1f)
    }

    fun release() {
        orb?.release(); orb = null
        levelUp?.release(); levelUp = null
        pool?.release(); pool = null
        customIds.clear(); loaded.clear()
    }
}
