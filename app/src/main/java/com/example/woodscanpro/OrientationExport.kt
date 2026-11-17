package com.example.woodscanpro

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Crea <base>.orientation.json (schema "woodpile-orientation-v1") per lo stitcher, a partire da
 * <base>_imu.csv, <base>_frames.csv e i metadati. Stessa logica di orientation_convert.py:
 *  - primo frame del video = frame della camera più vicino all'avvio della registrazione
 *  - per ogni frame, slerp tra i due campioni IMU vicini (dispositivo -> mondo)
 *  - conversione negli assi camera OpenCV (x destra, y giù, z verso la scena): R_dev * diag(1,-1,-1)
 */
object OrientationExport {

    /** Restituisce null (e non scrive nulla) se i dati non bastano o il video non è verticale. */
    fun write(imuCsv: File, framesCsv: File, videoStartElapsedNs: Long, screenRotationDeg: Int, out: File): File? {
        if (screenRotationDeg != 0 || !imuCsv.exists() || !framesCsv.exists()) return null

        val imu = imuCsv.readLines().drop(1).mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 4) return@mapNotNull null
            val t = p[0].toLongOrNull() ?: return@mapNotNull null
            val x = p[1].toDouble(); val y = p[2].toDouble(); val z = p[3].toDouble()
            val w = p.getOrNull(4)?.toDoubleOrNull() ?: sqrt(max(0.0, 1 - x * x - y * y - z * z))
            t to doubleArrayOf(x, y, z, w)
        }.sortedBy { it.first }
        if (imu.size < 2) return null
        val imuT = LongArray(imu.size) { imu[it].first }

        val allFrames = framesCsv.readLines().drop(1).mapNotNull { it.trim().toLongOrNull() }.distinct().sorted()
        if (allFrames.isEmpty()) return null
        val t0 = allFrames.minBy { abs(it - videoStartElapsedNs) }
        val frames = allFrames.filter { it >= t0 && it >= imuT.first() && it <= imuT.last() }
        if (frames.isEmpty()) return null

        val rows = frames.map { t ->
            var i = imuT.binarySearch(t).let { if (it >= 0) it + 1 else -it - 1 }   // searchsorted side="right"
            i = i.coerceIn(1, imuT.size - 1)
            val a = ((t - imuT[i - 1]).toDouble() / max(1L, imuT[i] - imuT[i - 1])).coerceIn(0.0, 1.0)
            val qDev = slerp(imu[i - 1].second, imu[i].second, a)
            val qCam = matToQuat(times(quatToMat(qDev), CAMERA_TO_DEVICE))
            val ms = (t - t0) / 1e6
            "    {\"timestamp_ms\": ${round(ms, 3)}, \"camera_to_world_quaternion\": " +
                qCam.joinToString(", ", "[", "]") { round(it, 7).toString() } + "}"
        }

        // un campione per riga, come lo script Python
        out.writeText(
            "{\n  \"schema\": \"woodpile-orientation-v1\",\n  \"quaternion_order\": \"xyzw\",\n" +
                "  \"samples\": [\n${rows.joinToString(",\n")}\n  ]\n}\n"
        )
        return out
    }

    private val CAMERA_TO_DEVICE = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0),
        doubleArrayOf(0.0, -1.0, 0.0),
        doubleArrayOf(0.0, 0.0, -1.0)
    )

    private fun round(v: Double, digits: Int): Double = String.format(Locale.US, "%.${digits}f", v).toDouble()

    private fun normalize(q: DoubleArray): DoubleArray {
        val n = sqrt(q.sumOf { it * it })
        return DoubleArray(4) { q[it] / n }
    }

    private fun quatToMat(q0: DoubleArray): Array<DoubleArray> {
        val (x, y, z, w) = normalize(q0).toList()
        return arrayOf(
            doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
            doubleArrayOf(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
            doubleArrayOf(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y))
        )
    }

    private fun times(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { r -> DoubleArray(3) { c -> (0 until 3).sumOf { k -> a[r][k] * b[k][c] } } }

    /** Matrice di rotazione -> quaternione XYZW. */
    private fun matToQuat(r: Array<DoubleArray>): DoubleArray {
        val t = r[0][0] + r[1][1] + r[2][2]
        val q = when {
            t > 0 -> {
                val s = 2.0 * sqrt(t + 1.0)
                doubleArrayOf((r[2][1] - r[1][2]) / s, (r[0][2] - r[2][0]) / s, (r[1][0] - r[0][1]) / s, 0.25 * s)
            }
            r[0][0] > r[1][1] && r[0][0] > r[2][2] -> {
                val s = 2.0 * sqrt(1.0 + r[0][0] - r[1][1] - r[2][2])
                doubleArrayOf(0.25 * s, (r[0][1] + r[1][0]) / s, (r[0][2] + r[2][0]) / s, (r[2][1] - r[1][2]) / s)
            }
            r[1][1] > r[2][2] -> {
                val s = 2.0 * sqrt(1.0 + r[1][1] - r[0][0] - r[2][2])
                doubleArrayOf((r[0][1] + r[1][0]) / s, 0.25 * s, (r[1][2] + r[2][1]) / s, (r[0][2] - r[2][0]) / s)
            }
            else -> {
                val s = 2.0 * sqrt(1.0 + r[2][2] - r[0][0] - r[1][1])
                doubleArrayOf((r[0][2] + r[2][0]) / s, (r[1][2] + r[2][1]) / s, 0.25 * s, (r[1][0] - r[0][1]) / s)
            }
        }
        return normalize(q)
    }

    /** Interpolazione tra due quaternioni (a tra 0 e 1), arco più corto. */
    private fun slerp(q0: DoubleArray, q1In: DoubleArray, a: Double): DoubleArray {
        var d = (0 until 4).sumOf { q0[it] * q1In[it] }
        var q1 = q1In
        if (d < 0) { q1 = DoubleArray(4) { -q1In[it] }; d = -d }
        val q = if (d > 0.9995) {
            DoubleArray(4) { q0[it] + a * (q1[it] - q0[it]) }
        } else {
            val th = acos(d)
            DoubleArray(4) { (sin((1 - a) * th) * q0[it] + sin(a * th) * q1[it]) / sin(th) }
        }
        return normalize(q)
    }
}
