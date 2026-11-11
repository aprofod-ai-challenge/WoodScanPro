package com.example.woodscanpro

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/**
 * Accanto al video salva:
 *  <base>_imu.csv     timestamp_ns,x,y,z,w   orientamento (game rotation vector, ~100 Hz), solo se il sensore esiste
 *  <base>_frames.csv  sensor_timestamp_ns    istante di ogni frame della camera
 *  <base>_meta.json   camera, sensore IMU, rotazione schermo, orologi all'avvio del video, formato del video
 *
 * Sensori e camera sono sullo stesso orologio solo se timestamp_source = "realtime".
 * start/stop/onVideoStart vanno chiamati dal thread principale.
 */
class CaptureLogger(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var imuWriter: BufferedWriter? = null
    private var frameWriter: BufferedWriter? = null
    private var cameraInfo = JSONObject()
    private var meta = JSONObject()

    /** File dell'ultima registrazione, nell'ordine: imu (se c'è il sensore), frames, meta. */
    var files: List<File> = emptyList(); private set

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            val line = "${e.timestamp},${v[0]},${v[1]},${v[2]},${v.getOrNull(3) ?: ""}\n"
            synchronized(lock) { imuWriter?.write(line) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Da passare a Camera2Interop: una chiamata per frame. */
    val frameCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val t = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            synchronized(lock) { frameWriter?.write("$t\n") }
        }
    }

    /** Da chiamare dopo bindToLifecycle: legge focale, sensore e orologio dei timestamp. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun onCameraBound(camera: Camera) {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        fun <T> c(key: CameraCharacteristics.Key<T>): T? =
            runCatching { info.getCameraCharacteristic(key) }.getOrNull()

        cameraInfo = JSONObject().apply {
            c(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { f ->
                put("focal_lengths_mm", JSONArray(f.map { it.toDouble() }))
            }
            c(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
                put("sensor_physical_size_mm", JSONArray(listOf(it.width.toDouble(), it.height.toDouble())))
            }
            c(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)?.let {
                put("timestamp_source", if (it == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "realtime" else "unknown")
            }
        }
    }

    /**
     * Avvia la registrazione in [dir], subito prima del video.
     * Restituisce false se manca il sensore di orientamento (frame e meta vengono salvati comunque).
     */
    fun start(dir: File, baseName: String): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val imu = File(dir, "${baseName}_imu.csv")
        val frames = File(dir, "${baseName}_frames.csv")
        files = listOfNotNull(imu.takeIf { sensor != null }, frames, File(dir, "${baseName}_meta.json"))
        meta = JSONObject()
            .put("camera", cameraInfo)
            .put("imu_sensor", sensor?.name ?: JSONObject.NULL)
            .put("screen_rotation_deg", screenRotationDeg())

        synchronized(lock) {
            frameWriter = frames.bufferedWriter().apply { write("sensor_timestamp_ns\n") }
            if (sensor != null) imuWriter = imu.bufferedWriter().apply { write("timestamp_ns,x,y,z,w\n") }
        }
        if (sensor != null) {
            val t = HandlerThread("imu-logger").apply { start() }
            thread = t
            sensorManager.registerListener(sensorListener, sensor, 10_000, Handler(t.looper))   // 100 Hz
        }
        return sensor != null
    }

    /** Da chiamare su VideoRecordEvent.Start. */
    fun onVideoStart() {
        meta.put(
            "clocks_at_video_start",
            JSONObject()
                .put("elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
                .put("system_nanotime_ns", System.nanoTime())
        )
    }

    /** Chiude i file e scrive i metadati. Passa il video finito per salvarne il formato. */
    fun stop(video: File?) {
        val wasRunning = frameWriter != null
        sensorManager.unregisterListener(sensorListener)
        thread?.quitSafely()
        thread = null
        synchronized(lock) {
            imuWriter?.close(); imuWriter = null
            frameWriter?.close(); frameWriter = null
        }
        if (!wasRunning) return

        video?.let { meta.put("video", describeVideo(it)) }
        files.last().writeText(meta.toString(2))
    }

    fun deleteFiles() {
        stop(null)
        files.forEach { it.delete() }
        files = emptyList()
    }

    @Suppress("DEPRECATION")
    private fun screenRotationDeg(): Int =   // Surface.ROTATION_0..3 valgono 0..3
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation * 90

    private fun describeVideo(file: File): JSONObject {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            fun key(k: Int) = r.extractMetadata(k)?.toIntOrNull()
            JSONObject()
                .put("width", key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                .put("height", key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                .put("rotation_deg", key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
        } catch (e: Exception) {
            JSONObject()
        } finally {
            r.release()
        }
    }
}