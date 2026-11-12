package com.vdo.app.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.vdo.app.data.Store
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sound enhancements applied to whatever the player is playing: a multi-band equalizer,
 * bass boost, surround widening and a volume booster. Uses the platform audio effects,
 * attached to the player's audio session, so it needs no extra permission.
 */
object AudioFx {
    class Band(val centerHz: Int, val min: Int, val max: Int)

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null

    var available by mutableStateOf(false); private set
    var bands by mutableStateOf<List<Band>>(emptyList()); private set
    var presets by mutableStateOf<List<String>>(emptyList()); private set

    var enabled by mutableStateOf(false); private set
    var preset by mutableStateOf(-1); private set // -1 = custom
    var levels by mutableStateOf<List<Int>>(emptyList()); private set // millibels per band
    var bassStrength by mutableStateOf(0); private set // 0..1000
    var surround by mutableStateOf(0); private set // 0..1000
    var boost by mutableStateOf(0); private set // extra gain in millibels, 0..2000

    /** Called by the playback service whenever the player gets an audio session. */
    fun attach(sessionId: Int) {
        release()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        eq = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virt = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        loud = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        val e = eq
        if (e != null) runCatching {
            val range = e.bandLevelRange
            bands = (0 until e.numberOfBands).map { Band(e.getCenterFreq(it.toShort()) / 1000, range[0].toInt(), range[1].toInt()) }
            presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) }
        }
        available = eq != null || bass != null || loud != null
        // Restore what the user chose last time.
        enabled = Store.fxEnabled
        preset = Store.fxPreset.coerceIn(-1, presets.size - 1)
        val saved = Store.fxLevels.split(',').mapNotNull { it.toIntOrNull() }
        levels = if (saved.size == bands.size) saved else List(bands.size) { 0 }
        bassStrength = Store.fxBass
        surround = Store.fxSurround
        boost = Store.fxBoost
        apply()
    }

    fun release() {
        listOf(eq, bass, virt, loud).forEach { runCatching { it?.release() } }
        eq = null; bass = null; virt = null; loud = null
        available = false
    }

    private fun apply() {
        runCatching {
            eq?.let { e ->
                e.enabled = enabled
                if (enabled) {
                    if (preset >= 0) {
                        e.usePreset(preset.toShort())
                        levels = bands.indices.map { e.getBandLevel(it.toShort()).toInt() }
                    } else levels.forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.toShort()) }
                }
            }
        }
        runCatching { bass?.let { it.enabled = enabled && bassStrength > 0; if (it.enabled) it.setStrength(bassStrength.toShort()) } }
        runCatching { virt?.let { it.enabled = enabled && surround > 0; if (it.enabled) it.setStrength(surround.toShort()) } }
        runCatching { loud?.let { it.enabled = enabled && boost > 0; if (it.enabled) it.setTargetGain(boost) } }
    }

    private fun save() {
        Store.fxEnabled = enabled; Store.fxPreset = preset; Store.fxLevels = levels.joinToString(",")
        Store.fxBass = bassStrength; Store.fxSurround = surround; Store.fxBoost = boost
    }

    fun setOn(on: Boolean) { enabled = on; apply(); save() }
    fun choosePreset(index: Int) { preset = index; enabled = true; apply(); save() }
    fun setBand(index: Int, level: Int) {
        preset = -1; enabled = true
        levels = levels.toMutableList().also { if (index in it.indices) it[index] = level }
        apply(); save()
    }
    fun changeBass(v: Int) { bassStrength = v.coerceIn(0, 1000); if (v > 0) enabled = true; apply(); save() }
    fun changeSurround(v: Int) { surround = v.coerceIn(0, 1000); if (v > 0) enabled = true; apply(); save() }
    fun changeBoost(v: Int) { boost = v.coerceIn(0, 2000); if (v > 0) enabled = true; apply(); save() }
    fun reset() {
        preset = -1; levels = List(bands.size) { 0 }; bassStrength = 0; surround = 0; boost = 0
        apply(); save()
    }
}

/**
 * Feeds the music visualizer. The player hands every block of decoded audio to this sink;
 * it turns roughly thirty blocks a second into a handful of frequency-band levels.
 * Nothing is recorded and no microphone permission is involved.
 */
object AudioLevels : TeeAudioProcessor.AudioBufferSink {
    const val BANDS = 32
    private const val N = 1024
    private const val DELAY_NS = 220_000_000L // audio is analysed slightly before it is heard

    private var sampleRate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val window = FloatArray(N)
    private var filled = 0
    private var lastEmit = 0L
    private val frames = ArrayDeque<Pair<Long, FloatArray>>()
    private val hann = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }

    fun processor(): AudioProcessor = TeeAudioProcessor(this)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz; channels = channelCount.coerceAtLeast(1); this.encoding = encoding
        filled = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytesPerSample * channels
        while (b.remaining() >= frameBytes) {
            var sum = 0f
            for (c in 0 until channels) sum += if (bytesPerSample == 2) b.short / 32768f else b.float
            window[filled++] = sum / channels
            if (filled == N) {
                filled = 0
                val now = System.nanoTime()
                if (now - lastEmit >= 30_000_000L) { lastEmit = now; emit(now) }
            }
        }
    }

    private fun emit(now: Long) {
        val re = FloatArray(N) { window[it] * hann[it] }
        val im = FloatArray(N)
        fft(re, im)
        val out = FloatArray(BANDS)
        val lo = 45.0
        val hi = minOf(16000.0, sampleRate / 2.0)
        for (band in 0 until BANDS) {
            val f0 = lo * (hi / lo).pow(band.toDouble() / BANDS)
            val f1 = lo * (hi / lo).pow((band + 1.0) / BANDS)
            val k0 = (f0 * N / sampleRate).toInt().coerceIn(1, N / 2 - 1)
            val k1 = (f1 * N / sampleRate).toInt().coerceIn(k0 + 1, N / 2)
            var peak = 0f
            for (k in k0 until k1) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            // Decibels mapped to 0..1, with a gentle lift for the quieter high bands.
            val db = 20f * log10(peak / (N / 4f) + 1e-6f)
            out[band] = ((db + 62f + band * 0.5f) / 56f).coerceIn(0f, 1f)
        }
        synchronized(frames) {
            frames.addLast(now to out)
            while (frames.size > 40) frames.removeFirst()
        }
    }

    /** Levels for what is being heard right now, or null when nothing is playing. */
    fun current(): FloatArray? = synchronized(frames) {
        val now = System.nanoTime()
        while (frames.size > 1 && frames[1].first <= now - DELAY_NS) frames.removeFirst()
        val f = frames.firstOrNull() ?: return null
        if (now - f.first > 900_000_000L) { frames.clear(); null } else if (f.first <= now - DELAY_NS / 2) f.second else null
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
