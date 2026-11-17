package com.example.woodscanpro

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Calibrazione della camera direttamente sul telefono, a partire dal video della scacchiera.
 * Stessi passi di calibration_convert.py (get_angoli_video + calibrateCamera):
 *  - 1 frame ogni [STEP], al massimo [MAX_FRAMES] frame accettati
 *  - scarta i frame mossi (varianza del Laplaciano < [BLUR_MIN])
 *  - scarta le pose troppo simili all'ultima accettata (spostamento medio < [MIN_MOV] px)
 *  - angoli con findChessboardCornersSB (ACCURACY) alla risoluzione piena del video
 * I frame sono girati come li legge OpenCV su PC (rotazione del video applicata), quindi
 * image_size corrisponde a quella vista dallo stitcher.
 */
object CalibrationExport {

    private const val TAG = "WoodScanCalib"
    private const val STEP = 5
    private const val MAX_FRAMES = 60
    private const val MIN_MOV = 30.0
    private const val BLUR_MIN = 50.0
    private const val PREVIEW_SIDE = 640   // lato lungo dell'immagine ridotta per il controllo rapido

    class Result(val json: String, val rms: Double, val used: Int, val analysed: Int, val width: Int, val height: Int)

    /** Restituisce null se non è stato trovato nessun frame con la scacchiera. Da chiamare fuori dal main thread. */
    fun compute(video: File, onProgress: (Float) -> Unit = {}): Result? {
        check(OpenCVLoader.initLocal()) { "OpenCV non disponibile" }

        val pattern = Size(CalibConfig.PATTERN_COLS.toDouble(), CalibConfig.PATTERN_ROWS.toDouble())
        val square = CalibConfig.SQUARE_MM / 1000.0   // in metri, come lo script
        val objp = MatOfPoint3f(*Array(CalibConfig.PATTERN_COLS * CalibConfig.PATTERN_ROWS) { i ->
            Point3((i % CalibConfig.PATTERN_COLS) * square, (i / CalibConfig.PATTERN_COLS) * square, 0.0)
        })

        val objPoints = mutableListOf<Mat>()
        val imgPoints = mutableListOf<Mat>()
        var last: FloatArray? = null
        var analysed = 0
        var size: Size? = null
        val lap = Mat(); val mean = MatOfDouble(); val std = MatOfDouble(); val small = Mat()

        decodeGrayFrames(video, STEP, onProgress) { gray ->
            analysed++
            if (size == null) size = gray.size()
            if (gray.size() != size) return@decodeGrayFrames true

            // frame mosso
            Imgproc.Laplacian(gray, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, std)
            val sd = std.toArray()[0]
            if (sd * sd < BLUR_MIN) return@decodeGrayFrames true

            // controllo rapido su un'immagine ridotta: scarta subito i frame senza scacchiera
            val scale = max(1.0, max(gray.cols(), gray.rows()) / PREVIEW_SIDE.toDouble())
            Imgproc.resize(gray, small, Size(gray.cols() / scale, gray.rows() / scale), 0.0, 0.0, Imgproc.INTER_AREA)
            val quick = MatOfPoint2f()
            if (!Calib3d.findChessboardCorners(small, pattern, quick,
                    Calib3d.CALIB_CB_ADAPTIVE_THRESH + Calib3d.CALIB_CB_NORMALIZE_IMAGE + Calib3d.CALIB_CB_FAST_CHECK)
            ) return@decodeGrayFrames true

            // angoli precisi a piena risoluzione
            val corners = Mat()
            val ok = Calib3d.findChessboardCornersSB(gray, pattern, corners, Calib3d.CALIB_CB_ACCURACY)
            if (!ok || corners.rows() * corners.cols() != CalibConfig.PATTERN_COLS * CalibConfig.PATTERN_ROWS) {
                return@decodeGrayFrames true
            }
            val pts = MatOfPoint2f(corners).toArray().flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()

            // pose troppo simile all'ultima accettata
            last?.let { prev ->
                var sum = 0.0
                for (k in pts.indices step 2) {
                    val dx = pts[k] - prev[k]; val dy = pts[k + 1] - prev[k + 1]
                    sum += sqrt((dx * dx + dy * dy).toDouble())
                }
                if (sum / (pts.size / 2) < MIN_MOV) return@decodeGrayFrames true
            }

            last = pts
            objPoints.add(objp)
            imgPoints.add(corners)
            imgPoints.size < MAX_FRAMES
        }
        Log.i(TAG, "$analysed frame analizzati, ${imgPoints.size} accettati")
        val imgSize = size ?: return null
        if (imgPoints.isEmpty()) return null

        val k = Mat(); val dist = Mat()
        val rms = Calib3d.calibrateCamera(objPoints, imgPoints, imgSize, k, dist, mutableListOf(), mutableListOf(), 0)

        val w = imgSize.width.toInt(); val h = imgSize.height.toInt()
        fun d(v: Double) = String.format(Locale.US, "%.10g", v)
        val kRows = (0 until 3).joinToString(",\n    ", "[\n    ", "\n  ]") { r ->
            (0 until 3).joinToString(", ", "[", "]") { c -> d(k.get(r, c)[0]) }
        }
        val distVals = MatOfDouble(dist.reshape(1, 1)).toArray().joinToString(", ", "[", "]") { d(it) }
        val json = "{\n  \"schema\": \"opencv-camera-calibration-v1\",\n" +
            "  \"image_size\": [$w, $h],\n" +
            "  \"camera_matrix\": $kRows,\n" +
            "  \"distortion_coefficients\": $distVals,\n" +
            "  \"rms_px\": ${d(rms)},\n" +
            "  \"frames_used\": ${imgPoints.size}\n}\n"
        return Result(json, rms, imgPoints.size, analysed, w, h)
    }

    /**
     * Decodifica il video con MediaCodec e passa a [onFrame] un frame ogni [step], in scala di grigi
     * (piano Y) e già ruotato secondo i metadati del video. [onFrame] restituisce false per fermarsi.
     */
    private fun decodeGrayFrames(video: File, step: Int, onProgress: (Float) -> Unit, onFrame: (Mat) -> Boolean) {
        val extractor = MediaExtractor()
        extractor.setDataSource(video.path)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var index = -1
        var keepGoing = true
        val gray = Mat(); val rotated = Mat()
        try {
            while (keepGoing) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    index++
                    if (index % step == 0) {
                        codec.getOutputImage(outIdx)?.use { image ->
                            val plane = image.planes[0]
                            val w = image.cropRect.width(); val h = image.cropRect.height()
                            val rowStride = plane.rowStride
                            val bytes = ByteArray(w * h)
                            val b = plane.buffer
                            for (r in 0 until h) {
                                b.position((r + image.cropRect.top) * rowStride + image.cropRect.left * plane.pixelStride)
                                if (plane.pixelStride == 1) b.get(bytes, r * w, w)
                                else for (c in 0 until w) bytes[r * w + c] = b.get(b.position() + c * plane.pixelStride)
                            }
                            gray.create(h, w, CvType.CV_8UC1)
                            gray.put(0, 0, bytes)
                        }
                        val frame = when (rotation) {
                            90 -> { Core.rotate(gray, rotated, Core.ROTATE_90_CLOCKWISE); rotated }
                            180 -> { Core.rotate(gray, rotated, Core.ROTATE_180); rotated }
                            270 -> { Core.rotate(gray, rotated, Core.ROTATE_90_COUNTERCLOCKWISE); rotated }
                            else -> gray
                        }
                        keepGoing = onFrame(frame)
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (eos) break
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
        }
    }
}
