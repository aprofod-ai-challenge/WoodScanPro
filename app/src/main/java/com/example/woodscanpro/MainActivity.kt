package com.example.woodscanpro

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Photogrammetry & Woodpile CV Capture Thresholds
object ScanConfig {
    const val MAX_ROLL_OK = 4.0
    const val MAX_ROLL_WARN = 8.5
    const val MAX_PITCH_OK = 6.0
    const val MAX_PITCH_WARN = 12.0
    const val MIN_LUMA_OK = 55.0
    const val MAX_LUMA_OK = 205.0
    const val MAX_CLIP_PCT = 18.0
    const val MAX_ROTATION_RATE = 35.0
    const val MIN_FLOW_RECORDING = 1.2
    const val MAX_FLOW_OK = 18.0
    const val MIN_SHARPNESS = 6.5
}

data class TelemetrySample(
    val tMs: Long,
    val rollDeg: Double,
    val pitchDeg: Double,
    val rotSpeedDps: Double,
    val lumaMean: Double,
    val overExposedPct: Double,
    val underExposedPct: Double,
    val opticalFlow: Double,
    val sharpness: Double,
    val qualityStatus: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WoodScanApp()
            }
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun WoodScanApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Android 9 e precedenti: permesso per scrivere in Movies/ e Download/ (richiesto dopo quello della camera)
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    var roll by remember { mutableDoubleStateOf(0.0) }
    var pitch by remember { mutableDoubleStateOf(0.0) }
    var rotSpeed by remember { mutableDoubleStateOf(0.0) }
    var luma by remember { mutableDoubleStateOf(128.0) }
    var overExposedPct by remember { mutableDoubleStateOf(0.0) }
    var underExposedPct by remember { mutableDoubleStateOf(0.0) }
    var frameFlow by remember { mutableDoubleStateOf(0.0) }
    var sharpness by remember { mutableDoubleStateOf(15.0) }

    var simMode by remember { mutableStateOf(false) }
    var simRoll by remember { mutableFloatStateOf(0f) }
    var simPitch by remember { mutableFloatStateOf(0f) }
    var simShake by remember { mutableFloatStateOf(2f) }

    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var videoCaptureRef by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isAeAfLocked by remember { mutableStateOf(false) }
    var calibMode by remember { mutableStateOf(false) }   // modalità video di calibrazione
    var recordStartMs by remember { mutableLongStateOf(0L) }
    var elapsedSec by remember { mutableIntStateOf(0) }

    val telemetryLog = remember { mutableStateListOf<TelemetrySample>() }
    var showSummaryModal by remember { mutableStateOf(false) }

    // NEW: Temporary File Tracking
    var tempVideoFile by remember { mutableStateOf<File?>(null) }
    var currentVideoName by remember { mutableStateOf("") }
    val logger = remember { CaptureLogger(context) }

    // Guida alla calibrazione: rileva la scacchiera nei frame di analisi e misura la copertura
    var calibStatus by remember { mutableStateOf(CalibStatus()) }
    val calibGuide = remember { CalibrationGuide { calibStatus = it } }

    DisposableEffect(simMode) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (simMode) return
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                        val gx = event.values[0].toDouble()
                        val gy = event.values[1].toDouble()
                        val gz = event.values[2].toDouble()
                        val rawPitch = Math.toDegrees(atan2(gz, hypot(gx, gy)))
                        val absX = abs(gx)
                        val absY = abs(gy)
                        val rawRoll = if (absY >= absX) {
                            Math.toDegrees(atan2(gx, absY))
                        } else {
                            Math.toDegrees(atan2(gy, absX))
                        }
                        pitch = pitch * 0.75 + rawPitch * 0.25
                        roll = roll * 0.75 + rawRoll * 0.25
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val wx = Math.toDegrees(event.values[0].toDouble())
                        val wy = Math.toDegrees(event.values[1].toDouble())
                        val wz = Math.toDegrees(event.values[2].toDouble())
                        val mag = hypot(wx, hypot(wy, wz))
                        rotSpeed = rotSpeed * 0.7 + mag * 0.3
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        gravitySensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    LaunchedEffect(simMode, simRoll, simPitch, simShake) {
        if (simMode) {
            roll = simRoll.toDouble()
            pitch = simPitch.toDouble()
            frameFlow = simShake.toDouble()
            rotSpeed = simShake.toDouble() * 1.8
        }
    }

    val absRoll = abs(roll)
    val absPitch = abs(pitch)
    val issues = mutableListOf<String>()
    var severity = "ok"

    fun elevate(level: String) {
        if (level == "err") severity = "err"
        else if (level == "warn" && severity == "ok") severity = "warn"
    }

    val rollStatus = when {
        absRoll <= ScanConfig.MAX_ROLL_OK -> "ok"
        absRoll <= ScanConfig.MAX_ROLL_WARN -> {
            elevate("warn")
            issues.add(if (roll > 0) "Level horizon (tilt left slightly)" else "Level horizon (tilt right slightly)")
            "warn"
        }
        else -> {
            elevate("err")
            issues.add(if (roll > 0) "Horizon crooked! Rotate counter-clockwise" else "Horizon crooked! Rotate clockwise")
            "err"
        }
    }

    val pitchStatus = when {
        absPitch <= ScanConfig.MAX_PITCH_OK -> "ok"
        absPitch <= ScanConfig.MAX_PITCH_WARN -> {
            elevate("warn")
            issues.add(if (pitch > 0) "Tilt camera UP slightly" else "Tilt camera DOWN slightly")
            "warn"
        }
        else -> {
            elevate("err")
            issues.add(if (pitch > 0) "Pointing too far down! Hold phone vertical" else "Pointing too far up! Hold phone vertical")
            "err"
        }
    }

    val (lightStatus, lightLabel) = when {
        luma < ScanConfig.MIN_LUMA_OK || underExposedPct > ScanConfig.MAX_CLIP_PCT -> {
            elevate("err")
            issues.add("Image too dark — shadow detail between logs is lost")
            "err" to "Too Dark"
        }
        luma > ScanConfig.MAX_LUMA_OK || overExposedPct > ScanConfig.MAX_CLIP_PCT -> {
            elevate("warn")
            issues.add("Overexposed highlights — avoid direct sun glare")
            "warn" to "Glare/Bright"
        }
        else -> "ok" to "${((luma / 255.0) * 100).roundToInt()}% OK"
    }

    val (paceStatus, paceLabel) = when {
        frameFlow > ScanConfig.MAX_FLOW_OK || rotSpeed > ScanConfig.MAX_ROTATION_RATE -> {
            elevate("err")
            issues.add("Moving/panning too fast! Walk slowly parallel to the pile")
            "err" to "Too Fast!"
        }
        sharpness < ScanConfig.MIN_SHARPNESS -> {
            elevate("warn")
            issues.add("Image looks blurry — steady your hands or check focus")
            "warn" to "Soft/Blur"
        }
        isRecording && frameFlow < ScanConfig.MIN_FLOW_RECORDING -> {
            elevate("warn")
            issues.add("Walk steadily sideways along the woodpile")
            "warn" to "Stopped"
        }
        else -> "ok" to "Steady"
    }

    // In modalità calibrazione i controlli della catasta non contano: decide la guida
    val calibHint = computeCalibHint(calibStatus, isRecording, isAeAfLocked, rotSpeed)
    val headerStatus = if (calibMode) {
        when (calibHint.level) {
            HintLevel.OK -> "ok"
            HintLevel.WARN -> "warn"
            HintLevel.ERR -> "err"
        }
    } else severity

    val coachingText = if (calibMode) {
        calibHint.text
    } else if (issues.isEmpty()) {
        if (isRecording) "Great! Keep walking horizontally parallel to the pile."
        else "All checks passed! Lock AE/AF, press Record, and walk parallel."
    } else {
        issues.first()
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            val now = System.currentTimeMillis()
            elapsedSec = ((now - recordStartMs) / 1000).toInt()
            telemetryLog.add(
                TelemetrySample(
                    tMs = now - recordStartMs, rollDeg = roll, pitchDeg = pitch,
                    rotSpeedDps = rotSpeed, lumaMean = luma, overExposedPct = overExposedPct,
                    underExposedPct = underExposedPct, opticalFlow = frameFlow,
                    sharpness = sharpness, qualityStatus = severity
                )
            )
            delay(100L)
        }
    }

    fun toggleAeAfLock() {
        val cam = cameraRef ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val newLockState = !isAeAfLocked

        if (newLockState) {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val centerPoint = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
                .disableAutoCancel()
                .build()
            cam.cameraControl.startFocusAndMetering(action)

            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .build()
            camera2Control.setCaptureRequestOptions(options)
            isAeAfLocked = true
            Toast.makeText(context, "AE/AF & White Balance Locked", Toast.LENGTH_SHORT).show()
        } else {
            cam.cameraControl.cancelFocusAndMetering()
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                .build()
            camera2Control.setCaptureRequestOptions(options)
            isAeAfLocked = false
            Toast.makeText(context, "AE/AF Unlocked (Continuous)", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleRecording() {
        val videoCapture = videoCaptureRef ?: return
        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
            elapsedSec = 0
            calibGuide.accumulate = false
        } else {
            if (tempVideoFile != null) return   // la registrazione precedente non è ancora chiusa
            telemetryLog.clear()
            recordStartMs = System.currentTimeMillis()

            // NEW: Record to a hidden cache file instead of the public gallery
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // il prefisso "calib_" identifica i video di calibrazione (usato anche dal modal)
            val prefix = if (calibMode) "calib" else "woodpile_scan"
            currentVideoName = "${prefix}_$timestamp.mp4"
            val file = File(context.cacheDir, currentVideoName)
            tempVideoFile = file
            if (!logger.start(context.cacheDir, currentVideoName.removeSuffix(".mp4")))
                Toast.makeText(context, "Orientation sensor not available: IMU not logged", Toast.LENGTH_LONG).show()

            val outputOptions = FileOutputOptions.Builder(file).build()

            // la copertura si conta da zero, solo durante la registrazione di calibrazione
            if (calibMode) {
                calibGuide.reset()
                calibGuide.accumulate = true
            }

            isRecording = true
            activeRecording = videoCapture.output
                .prepareRecording(context, outputOptions)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Start) logger.onVideoStart()
                    if (event is VideoRecordEvent.Finalize) {
                        if (event.hasError()) {
                            file.delete()
                            logger.deleteFiles()
                            tempVideoFile = null
                            Toast.makeText(context, "Recording failed.", Toast.LENGTH_SHORT).show()
                        } else {
                            logger.stop(file)
                            showSummaryModal = true
                        }
                    }
                }
        }
    }

    val colorOk = Color(0xFF10B981)
    val colorWarn = Color(0xFFF59E0B)
    val colorErr = Color(0xFFEF4444)
    val colorCalib = Color(0xFF3B82F6)
    val glassBg = Color(0xC80F172A)

    fun statusColor(status: String): Color = when (status) {
        "ok" -> colorOk
        "warn" -> colorWarn
        else -> colorErr
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF090D16))) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    val analysisExecutor = Executors.newSingleThreadExecutor()
                    var prevSampledLuma: IntArray? = null
                    var lastAnalysisMs = 0L

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        // Preview e analisi in 16:9 come il video: l'operatore vede (e la guida analizza)
                        // lo stesso campo di vista che viene registrato. Il default di CameraX è 4:3.
                        val preview = Preview.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                    .build()
                            )
                            .also { Camera2Interop.Extender(it).setSessionCaptureCallback(logger.frameCallback) }
                            .build().also { it.surfaceProvider = previewView.surfaceProvider }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            android.util.Size(640, 360),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                        )
                                    )
                                    .build()
                            )
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(analysisExecutor) { image: ImageProxy ->
                                    try {
                                        val now = System.currentTimeMillis()
                                        if (now - lastAnalysisMs >= 100L) {
                                            lastAnalysisMs = now
                                            val plane = image.planes[0]
                                            val buffer = plane.buffer
                                            val rowStride = plane.rowStride
                                            val pixelStride = plane.pixelStride
                                            val step = 8 // Skip pixels for speed (Change to 16 for old tablets)

                                            val gridW = image.width / step
                                            val gridH = image.height / step
                                            val totalSamples = (gridW * gridH).coerceAtLeast(1)
                                            val currentGrid = IntArray(totalSamples)

                                            var sumY = 0L
                                            var overCount = 0
                                            var underCount = 0
                                            var flowDiffSum = 0L

                                            var idx = 0
                                            for (r in 0 until gridH) {
                                                val rowOffset = (r * step) * rowStride
                                                for (c in 0 until gridW) {
                                                    val byteIndex = rowOffset + (c * step) * pixelStride
                                                    val yVal = if (byteIndex < buffer.limit()) {
                                                        buffer.get(byteIndex).toInt() and 0xFF
                                                    } else 128

                                                    currentGrid[idx] = yVal
                                                    sumY += yVal
                                                    if (yVal > 245) overCount++
                                                    else if (yVal < 15) underCount++

                                                    if (prevSampledLuma != null && idx < prevSampledLuma!!.size) {
                                                        flowDiffSum += abs(yVal - prevSampledLuma!![idx])
                                                    }
                                                    idx++
                                                }
                                            }

                                            var gradSum = 0L
                                            var gradCount = 0
                                            for (r in 2 until gridH - 2) {
                                                for (c in 2 until gridW - 2) {
                                                    val p = r * gridW + c
                                                    val gx = abs(currentGrid[p + 1] - currentGrid[p - 1])
                                                    val gy = abs(currentGrid[p + gridW] - currentGrid[p - gridW])
                                                    gradSum += (gx + gy) / 2
                                                    gradCount++
                                                }
                                            }

                                            luma = sumY.toDouble() / totalSamples
                                            overExposedPct = (overCount.toDouble() / totalSamples) * 100.0
                                            underExposedPct = (underCount.toDouble() / totalSamples) * 100.0
                                            if (gradCount > 0) sharpness = gradSum.toDouble() / gradCount
                                            if (prevSampledLuma != null && !simMode) {
                                                val rawFlow = flowDiffSum.toDouble() / totalSamples
                                                frameFlow = frameFlow * 0.6 + rawFlow * 0.4
                                            }
                                            prevSampledLuma = currentGrid
                                        }
                                        // rilevamento della scacchiera (limitato internamente a ~5 Hz)
                                        if (calibMode) calibGuide.process(image)
                                    } finally {
                                        image.close()
                                    }
                                }
                            }

                        val qualitySelector = QualitySelector.fromOrderedList(
                            listOf(Quality.UHD, Quality.FHD, Quality.HD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                        val recorder = Recorder.Builder()
                            .setQualitySelector(qualitySelector)
                            .build()
                        val videoCapture = VideoCapture.withOutput(recorder)
                        videoCaptureRef = videoCapture

                        try {
                            cameraProvider.unbindAll()
                            cameraRef = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis,
                                videoCapture
                            )
                            logger.onCameraBound(cameraRef!!)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                // In calibrazione la preview non viene ritagliata: si vede tutto il fotogramma registrato
                update = { view ->
                    view.scaleType = if (calibMode) PreviewView.ScaleType.FIT_CENTER
                    else PreviewView.ScaleType.FILL_CENTER
                }
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (headerStatus == "err") Color(0xC87F1D1D) else glassBg)
                        .border(1.5.dp, statusColor(headerStatus), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor(headerStatus)))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = when {
                                calibMode -> when {
                                    isRecording && calibStatus.complete -> "CALIBRAZIONE — COMPLETA"
                                    isRecording -> "CALIBRAZIONE — REC"
                                    else -> "MODALITÀ CALIBRAZIONE"
                                }
                                isRecording && severity == "ok" -> "RECORDING — OPTIMAL"
                                severity == "ok" -> "READY TO SCAN WOODPILE"
                                severity == "warn" -> "MINOR ADJUSTMENT ADVISED"
                                else -> "CORRECTION NEEDED"
                            },
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                    }
                    val mins = (elapsedSec / 60).toString().padStart(2, '0')
                    val secs = (elapsedSec % 60).toString().padStart(2, '0')
                    Text(text = if (isAeAfLocked) "🔒 $mins:$secs" else "$mins:$secs", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (calibMode) {
                        // Avanzamento della calibrazione al posto delle metriche della catasta
                        val s = calibStatus
                        MetricCard(modifier = Modifier.weight(1f), label = "COPERTURA", value = "${s.coveragePct}%", borderColor = statusColor(if (s.coveragePct >= CalibConfig.MIN_COVERAGE_PCT) "ok" else "warn"))
                        MetricCard(modifier = Modifier.weight(1f), label = "INCLINAZ.", value = "${s.tiltsDone}/4", borderColor = statusColor(if (s.tiltsDone == 4) "ok" else "warn"))
                        MetricCard(modifier = Modifier.weight(1f), label = "DISTANZA", value = "${s.distDone}/2", borderColor = statusColor(if (s.distDone == 2) "ok" else "warn"))
                        MetricCard(modifier = Modifier.weight(1f), label = "SCACCHIERA", value = if (s.detected) "Rilevata" else "—", borderColor = statusColor(if (s.detected) "ok" else "err"))
                    } else {
                        MetricCard(modifier = Modifier.weight(1f), label = "HORIZON", value = String.format(Locale.US, "%+.1f°", roll), borderColor = statusColor(rollStatus))
                        MetricCard(modifier = Modifier.weight(1f), label = "PITCH", value = String.format(Locale.US, "%+.1f°", pitch), borderColor = statusColor(pitchStatus))
                        MetricCard(modifier = Modifier.weight(1f), label = "EXPOSURE", value = lightLabel, borderColor = statusColor(lightStatus))
                        MetricCard(modifier = Modifier.weight(1f), label = "PACE/BLUR", value = paceLabel, borderColor = statusColor(paceStatus))
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                // le linee guida della catasta non servono in calibrazione (la griglia è disegnata più sotto)
                if (!calibMode) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 8.dp.toPx()), 0f)
                        val topY = size.height * 0.10f
                        val botY = size.height * 0.90f
                        for (y in listOf(topY, botY)) {
                            // dark outline underneath, so the line stays visible on light backgrounds
                            drawLine(color = Color.Black.copy(alpha = 0.6f), start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 7.dp.toPx(), pathEffect = dashEffect)
                            // dashed yellow line on top
                            drawLine(color = Color(0xFFFFEB3B), start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 3.dp.toPx(), pathEffect = dashEffect)
                        }

                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(color = Color.White.copy(alpha = 0.35f), radius = 48.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
                        drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 6.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))

                        val pitchOffsetPx = (pitch.toFloat() * 2.2f).coerceIn(-45f, 45f).dp.toPx()
                        val barColor = if (rollStatus == "ok" && pitchStatus == "ok") colorOk else statusColor(severity)
                        val barCenter = Offset(center.x, center.y + pitchOffsetPx)
                        val halfWidth = 75.dp.toPx()
                        rotate(degrees = -roll.toFloat(), pivot = barCenter) {
                            drawLine(color = barColor, start = Offset(barCenter.x - halfWidth, barCenter.y), end = Offset(barCenter.x + halfWidth, barCenter.y), strokeWidth = 3.5.dp.toPx())
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize().padding(vertical = 26.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▲ ALIGN TOP OF WOODPILE INSIDE LINE ▲", color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                        Text("▼ ALIGN BOTTOM / GROUND INSIDE LINE ▼", color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Pulsante modalità calibrazione: bloccato durante la registrazione e finché
                // il video precedente non è stato salvato o scartato
                Button(
                    onClick = {
                        calibMode = !calibMode
                        if (calibMode) calibGuide.reset()
                    },
                    enabled = !isRecording && tempVideoFile == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (calibMode) colorCalib else Color.White.copy(alpha = 0.15f)
                    )
                ) {
                    Text(
                        text = if (calibMode) "📐 Calibrazione attiva" else "📐 Calibrazione",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }

                Text(text = coachingText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.clip(RoundedCornerShape(50)).background(glassBg).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 9.dp))

                if (simMode) {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(glassBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        SimSliderRow("Sim Roll (°)", simRoll, -25f..25f) { simRoll = it }
                        SimSliderRow("Sim Pitch (°)", simPitch, -25f..25f) { simPitch = it }
                        SimSliderRow("Sim Speed", simShake, 0f..30f) { simShake = it }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(glassBg).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { toggleAeAfLock() }, colors = ButtonDefaults.buttonColors(containerColor = if (isAeAfLocked) colorWarn else Color.White.copy(alpha = 0.15f))) {
                        Text(text = if (isAeAfLocked) "🔒 AE/AF Locked" else "Lock AE/AF", color = if (isAeAfLocked) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Box(modifier = Modifier.size(66.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable { toggleRecording() }, contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(if (isRecording) 28.dp else 50.dp).clip(if (isRecording) RoundedCornerShape(6.dp) else CircleShape).background(colorErr))
                    }

                    Button(onClick = { simMode = !simMode }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))) {
                        Text(text = if (simMode) "Exit Sim" else "Test Sim", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Overlay di calibrazione: griglia di copertura allineata al fotogramma registrato
        // (la preview è in FIT_CENTER), cella obiettivo in giallo, scacchiera rilevata in ciano.
        // È disegnato sopra l'interfaccia perché barra di stato e controlli coprono i bordi dell'immagine.
        if (calibMode && !showSummaryModal) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val s = calibStatus
                val aspect = s.frameAspect
                val frameW: Float
                val frameH: Float
                if (size.width / size.height > aspect) {
                    frameH = size.height; frameW = frameH * aspect
                } else {
                    frameW = size.width; frameH = frameW / aspect
                }
                val left = (size.width - frameW) / 2f
                val top = (size.height - frameH) / 2f
                val cellW = frameW / s.gridCols
                val cellH = frameH / s.gridRows
                fun cellTopLeft(i: Int) = Offset(left + (i % s.gridCols) * cellW, top + (i / s.gridCols) * cellH)

                val covered = s.covered
                for (i in covered.indices) {
                    val tl = cellTopLeft(i)
                    if (covered[i]) drawRect(colorOk.copy(alpha = 0.25f), tl, Size(cellW, cellH))
                    else if (isRecording) drawRect(colorErr.copy(alpha = 0.12f), tl, Size(cellW, cellH))
                    drawRect(Color.White.copy(alpha = 0.35f), tl, Size(cellW, cellH), style = Stroke(width = 1.dp.toPx()))
                }
                calibHint.targetCell?.let { i ->
                    drawRect(Color(0xFFFFEB3B), cellTopLeft(i), Size(cellW, cellH), style = Stroke(width = 4.dp.toPx()))
                }
                s.quad?.let { q ->
                    for (k in 0 until 4) {
                        val j = (k + 1) % 4
                        drawLine(
                            color = Color(0xFF22D3EE),
                            start = Offset(left + q[2 * k] * frameW, top + q[2 * k + 1] * frameH),
                            end = Offset(left + q[2 * j] * frameW, top + q[2 * j + 1] * frameH),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                }

                // Fase viste di sbieco: lato della scacchiera da avvicinare, evidenziato in giallo
                val targetSide = calibHint.targetSide
                val sideEdges = s.liveSideEdge
                val q = s.quad
                if (targetSide != null && sideEdges != null && q != null) {
                    val k = sideEdges[targetSide]
                    val j = (k + 1) % 4
                    drawLine(
                        color = Color(0xFFFFEB3B),
                        start = Offset(left + q[2 * k] * frameW, top + q[2 * k + 1] * frameH),
                        end = Offset(left + q[2 * j] * frameW, top + q[2 * j + 1] * frameH),
                        strokeWidth = 9.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Fase distanze: riquadro tratteggiato centrato, con l'area obiettivo e le proporzioni
                // del contorno attuale, da raggiungere (vicino) o in cui stare (lontano)
                calibHint.targetArea?.let { area ->
                    val aspectPx = if (q != null) {
                        val xs = listOf(q[0], q[2], q[4], q[6])
                        val ys = listOf(q[1], q[3], q[5], q[7])
                        ((xs.max() - xs.min()) * frameW) / ((ys.max() - ys.min()) * frameH).coerceAtLeast(1f)
                    } else 0.75f
                    var hN = sqrt(area * frameW / (aspectPx * frameH))
                    var wN = area / hN
                    val shrink = minOf(1f, 0.95f / wN, 0.95f / hN)   // resta dentro l'immagine
                    wN *= shrink; hN *= shrink
                    val rw = wN * frameW
                    val rh = hN * frameH
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(left + (frameW - rw) / 2f, top + (frameH - rh) / 2f),
                        size = Size(rw, rh),
                        style = Stroke(
                            width = 3.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 10.dp.toPx()), 0f)
                        )
                    )
                }
            }
        }

        // Post-Recording Video Review Modal
        if (showSummaryModal) {
            val isCalib = currentVideoName.startsWith("calib_")
            val total = telemetryLog.size.coerceAtLeast(1)
            val validFrames = telemetryLog.count { it.qualityStatus != "err" }
            val scorePct = ((validFrames.toDouble() / total) * 100).roundToInt()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xEB090D16))
                    .padding(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF1E293B))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(if (isCalib) "Review Calibration" else "Review Scan", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

                    // NEW: Video Playback Player
                    if (tempVideoFile != null && tempVideoFile!!.exists()) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(tempVideoFile!!.absolutePath)
                                    val mediaController = MediaController(ctx)
                                    mediaController.setAnchorView(this)
                                    setMediaController(mediaController)
                                    requestFocus()
                                    setOnPreparedListener { it.isLooping = true }
                                    start() // Auto-play the video
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black)
                        )
                    } else {
                        Text("Preview unavailable", color = Color.Gray, modifier = Modifier.padding(20.dp))
                    }

                    // Il quality score è pensato per la scansione della catasta, non per la calibrazione:
                    // per la calibrazione si mostra il riepilogo della guida
                    if (isCalib) {
                        val s = calibStatus
                        Text(
                            text = "Copertura ${s.coveragePct}% · Inclinazioni ${s.tiltsDone}/4 · Distanze ${s.distDone}/2",
                            color = if (s.complete) colorOk else colorWarn,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        if (!s.complete) {
                            Text("Ripresa incompleta: conviene scartarla e rifarla", color = colorWarn, fontSize = 12.sp)
                        }
                    } else {
                        Text(
                            text = "Quality Score: $scorePct%",
                            color = if (scorePct >= 85) colorOk else colorWarn,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // SAVE BUTTON
                        Button(
                            onClick = {
                                tempVideoFile?.let { file ->
                                    // Download/WoodScanPro/calibrazione oppure Download/WoodScanPro/Scansione_NN
                                    val folder = if (isCalib) CALIBRATION_FOLDER else nextScanFolder(context)
                                    exportVideo(context, file, currentVideoName, folder)
                                    Toast.makeText(context, "Salvato in $ROOT_DIR/$folder", Toast.LENGTH_SHORT).show()

                                    // Export JSON matching the video name (solo per le scansioni:
                                    // la telemetria di qualità non serve per la calibrazione)
                                    if (!isCalib) {
                                        val jsonName = currentVideoName.replace(".mp4", ".json")
                                        exportTelemetryJson(context, telemetryLog, jsonName, folder)
                                    }

                                    logger.files.forEach { exportFile(context, it, folder) }
                                    logger.deleteFiles()
                                    // Clean up temporary file
                                    file.delete()
                                    tempVideoFile = null
                                }
                                if (isCalib) calibMode = false   // dopo il salvataggio si torna alla scansione
                                showSummaryModal = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colorOk),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Data", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        // DISCARD BUTTON (in calibrazione la modalità resta attiva, per rifare subito il video)
                        Button(
                            onClick = {
                                tempVideoFile?.delete() // Instantly delete the cache file
                                logger.deleteFiles()
                                tempVideoFile = null
                                showSummaryModal = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Discard", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(modifier: Modifier = Modifier, label: String, value: String, borderColor: Color) {
    Column(modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xC80F172A)).border(1.5.dp, borderColor, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun SimSliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text = label, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(0.35f))
        Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(0.65f))
    }
}

fun exportTelemetryJson(context: Context, samples: List<TelemetrySample>, fileName: String, folder: String) {
    try {
        val root = JSONObject()
        root.put("capturedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
        val arr = JSONArray()
        for (s in samples) {
            val obj = JSONObject()
            obj.put("t_ms", s.tMs)
            obj.put("roll_deg", s.rollDeg)
            obj.put("pitch_deg", s.pitchDeg)
            obj.put("rot_speed_dps", s.rotSpeedDps)
            obj.put("luma_mean", s.lumaMean)
            obj.put("over_exposed_pct", s.overExposedPct)
            obj.put("under_exposed_pct", s.underExposedPct)
            obj.put("optical_flow", s.opticalFlow)
            obj.put("sharpness", s.sharpness)
            obj.put("quality_status", s.qualityStatus)
            arr.put(obj)
        }
        root.put("samples", arr)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath(folder))
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(root.toString(2).toByteArray())
                }
            }
        } else {
            File(legacyDir(folder), fileName).writeText(root.toString(2))
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Salva il video in Download/WoodScanPro/<folder>, nella stessa cartella dei log (_imu.csv, _frames.csv, _meta.json),
 * così sul PC video e log restano insieme. (Movies/ su Android 10+ accetterebbe solo file video.)
 */
fun exportVideo(context: Context, file: File, name: String, folder: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(folder))
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    } else {
        val dest = File(legacyDir(folder), name)
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
    }
}

/** Salva un file del logger in Download/WoodScanPro/<folder>. */
fun exportFile(context: Context, file: File, folder: String) {
    val mime = if (file.extension == "csv") "text/csv" else "application/json"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath(folder))
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    } else {
        file.copyTo(File(legacyDir(folder), file.name), overwrite = true)
    }
}

// ---------------------------------------------------------------------------------------------
// Organizzazione delle cartelle:
//   Download/WoodScanPro/calibrazione/   tutti i video di calibrazione con i loro log
//   Download/WoodScanPro/Scansione_NN/   una cartella per ogni scansione salvata
// ---------------------------------------------------------------------------------------------

const val ROOT_DIR = "WoodScanPro"
const val CALIBRATION_FOLDER = "calibrazione"
private const val SCAN_PREFIX = "Scansione_"

/** Percorso relativo per MediaStore (Android 10+). */
private fun relativePath(folder: String) = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_DIR/$folder"

/** Cartella su disco per Android 9 e precedenti (creata se manca). */
private fun legacyDir(folder: String) =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$ROOT_DIR/$folder").apply { mkdirs() }

/**
 * Nome della prossima cartella di scansione libera (Scansione_01, Scansione_02, ...).
 * Il numero è il massimo tra le cartelle già presenti e l'ultimo numero salvato nelle preferenze,
 * più uno: le preferenze coprono i casi in cui l'app non vede le cartelle esistenti (vedi nota sotto).
 * Nota: su Android 10+ l'app vede solo i file creati da lei; dopo una reinstallazione non vede più le
 * scansioni precedenti e, se anche le preferenze sono state cancellate, la numerazione riparte da 01.
 */
fun nextScanFolder(context: Context): String {
    val prefs = context.getSharedPreferences("woodscan", Context.MODE_PRIVATE)
    var maxN = prefs.getInt("last_scan_number", 0)
    val regex = Regex("$SCAN_PREFIX(\\d+)")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("${relativePath(SCAN_PREFIX)}%"),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                regex.find(cursor.getString(0) ?: "")?.let { maxN = maxOf(maxN, it.groupValues[1].toInt()) }
            }
        }
    } else {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT_DIR)
            .listFiles()?.forEach { f ->
                regex.matchEntire(f.name)?.let { maxN = maxOf(maxN, it.groupValues[1].toInt()) }
            }
    }

    val next = maxN + 1
    prefs.edit().putInt("last_scan_number", next).apply()
    return String.format(Locale.US, "%s%02d", SCAN_PREFIX, next)
}