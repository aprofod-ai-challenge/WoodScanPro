package com.example.woodscanpro

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Punteggio di qualità graduale della scansione.
 *
 * Ogni campione di telemetria riceve un voto 0..1 per ciascun aspetto (movimento, nitidezza,
 * livellamento, esposizione). Ogni voto è 1 finché il valore è comodamente dentro i limiti di
 * ScanConfig e scende linearmente fino a 0 quando il valore è chiaramente fuori. In questo modo
 * anche un movimento "sotto soglia ma vivace" abbassa il punteggio, invece di contare come perfetto.
 *
 * Voto del fotogramma = prodotto dei voti (due problemi medi pesano più di uno solo).
 * Punteggio finale = media dei voti dei fotogrammi.
 */
object ScanScore {

    // Movimento: pieno voto fino al 40% della soglia "Too Fast", zero al 150%
    private const val FLOW_GOOD = ScanConfig.MAX_FLOW_OK * 0.4
    private const val FLOW_BAD = ScanConfig.MAX_FLOW_OK * 1.5
    private const val ROT_GOOD = ScanConfig.MAX_ROTATION_RATE * 0.4
    private const val ROT_BAD = ScanConfig.MAX_ROTATION_RATE * 1.5

    // Nitidezza: pieno voto sopra 1.6x la soglia "Soft/Blur", zero sotto la metà
    private const val SHARP_GOOD = ScanConfig.MIN_SHARPNESS * 1.6
    private const val SHARP_BAD = ScanConfig.MIN_SHARPNESS * 0.5

    // Livellamento: pieno voto entro la soglia OK; nella zona gialla scende piano fino a 0.7;
    // nella zona rossa (oltre la soglia WARN) crolla fino a 0 a 1.2x la soglia WARN
    private const val TILT_AT_WARN = 0.7
    private const val TILT_RED_ZERO_FACTOR = 1.2

    // Esposizione
    private const val LUMA_DARK_BAD = 25.0
    private const val LUMA_BRIGHT_BAD = 240.0
    private const val CLIP_GOOD = ScanConfig.MAX_CLIP_PCT * 0.5
    private const val CLIP_BAD = ScanConfig.MAX_CLIP_PCT * 2.0

    data class Breakdown(
        val total: Int,      // 0..100
        val motion: Int,
        val sharpness: Int,
        val level: Int,
        val light: Int
    )

    private data class FrameScore(val motion: Double, val sharpness: Double, val level: Double, val light: Double) {
        val total get() = motion * sharpness * level * light
    }

    /** 1 se value <= good, 0 se value >= bad, lineare in mezzo. */
    private fun falloff(value: Double, good: Double, bad: Double): Double =
        if (value <= good) 1.0 else if (value >= bad) 0.0 else 1.0 - (value - good) / (bad - good)

    /** Come falloff ma per valori dove "più alto è meglio". */
    private fun rise(value: Double, bad: Double, good: Double): Double =
        if (value >= good) 1.0 else if (value <= bad) 0.0 else (value - bad) / (good - bad)

    /** Voto di inclinazione: dolce in zona gialla, severo in zona rossa. */
    private fun tilt(deg: Double, ok: Double, warn: Double): Double {
        val redZero = warn * TILT_RED_ZERO_FACTOR
        return when {
            deg <= ok -> 1.0
            deg <= warn -> 1.0 - (1.0 - TILT_AT_WARN) * (deg - ok) / (warn - ok)
            deg >= redZero -> 0.0
            else -> TILT_AT_WARN * (1.0 - (deg - warn) / (redZero - warn))
        }
    }

    private fun frame(s: TelemetrySample): FrameScore {
        val motion = minOf(
            falloff(s.opticalFlow, FLOW_GOOD, FLOW_BAD),
            falloff(s.rotSpeedDps, ROT_GOOD, ROT_BAD)
        )
        val sharp = rise(s.sharpness, SHARP_BAD, SHARP_GOOD)
        val level = tilt(abs(s.rollDeg), ScanConfig.MAX_ROLL_OK, ScanConfig.MAX_ROLL_WARN) *
            tilt(abs(s.pitchDeg), ScanConfig.MAX_PITCH_OK, ScanConfig.MAX_PITCH_WARN)
        val luma = minOf(
            rise(s.lumaMean, LUMA_DARK_BAD, ScanConfig.MIN_LUMA_OK),
            falloff(s.lumaMean, ScanConfig.MAX_LUMA_OK, LUMA_BRIGHT_BAD)
        )
        val clip = falloff(maxOf(s.overExposedPct, s.underExposedPct), CLIP_GOOD, CLIP_BAD)
        return FrameScore(motion, sharp, level, luma * clip)
    }

    /** Voto 0..1 di un singolo campione (salvato anche nel JSON di telemetria). */
    fun frameScore(s: TelemetrySample): Double = frame(s).total

    fun compute(samples: List<TelemetrySample>): Breakdown {
        if (samples.isEmpty()) return Breakdown(0, 0, 0, 0, 0)
        val frames = samples.map(::frame)
        fun pct(sel: (FrameScore) -> Double) = (frames.sumOf(sel) / frames.size * 100).roundToInt()
        return Breakdown(
            total = pct { it.total },
            motion = pct { it.motion },
            sharpness = pct { it.sharpness },
            level = pct { it.level },
            light = pct { it.light }
        )
    }
}
