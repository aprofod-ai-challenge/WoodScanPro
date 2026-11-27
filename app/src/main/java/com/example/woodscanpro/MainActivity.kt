package com.example.woodscanpro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
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
import androidx.camera.core.DynamicRange
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
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
    const val MAX_ROTATION_RATE = 50.0   // era 35: troppo sensibile
    const val MIN_FLOW_RECORDING = 1.2
    const val MAX_FLOW_OK = 26.0         // era 18: troppo sensibile
    const val MIN_SHARPNESS = 5.0        // era 6.5
    // Peso del nuovo campione nella media mobile di flow/rotazione: più basso = brevi scatti ignorati
    const val PACE_SMOOTHING = 0.15
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
        UploadNetworkWatcher.install(this)
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
    val scope = rememberCoroutineScope()
    // testo di avanzamento della calibrazione calcolata sul telefono (null = nessun calcolo in corso)
    var calibProgress by remember { mutableStateOf<String?>(null) }
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

    var roll by remember { mutableDoubleStateOf(0.0) }
    var pitch by remember { mutableDoubleStateOf(0.0) }
    var rotSpeed by remember { mutableDoubleStateOf(0.0) }
    var luma by remember { mutableDoubleStateOf(128.0) }
    var overExposedPct by remember { mutableDoubleStateOf(0.0) }
    var underExposedPct by remember { mutableDoubleStateOf(0.0) }
    var frameFlow by remember { mutableDoubleStateOf(0.0) }
    var sharpness by remember { mutableDoubleStateOf(15.0) }

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
    val repo = remember { ScanRepository.get(context) }
    var showCodeDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showScanList by remember { mutableStateOf(false) }
    val logger = remember { CaptureLogger(context) }

    // Guida alla calibrazione: rileva la scacchiera nei frame di analisi e misura la copertura
    var calibStatus by remember { mutableStateOf(CalibStatus()) }
    val calibGuide = remember { CalibrationGuide { calibStatus = it } }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
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
                        rotSpeed = rotSpeed * (1 - ScanConfig.PACE_SMOOTHING) + mag * ScanConfig.PACE_SMOOTHING
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        gravitySensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensorManager.unregisterListener(listener) }
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
            issues.add(if (roll > 0) "Livella l'orizzonte (inclina un po' a sinistra)" else "Livella l'orizzonte (inclina un po' a destra)")
            "warn"
        }
        else -> {
            elevate("err")
            issues.add(if (roll > 0) "Orizzonte storto! Ruota in senso antiorario" else "Orizzonte storto! Ruota in senso orario")
            "err"
        }
    }

    val pitchStatus = when {
        absPitch <= ScanConfig.MAX_PITCH_OK -> "ok"
        absPitch <= ScanConfig.MAX_PITCH_WARN -> {
            elevate("warn")
            issues.add(if (pitch > 0) "Inclina la fotocamera un po' verso l'ALTO" else "Inclina la fotocamera un po' verso il BASSO")
            "warn"
        }
        else -> {
            elevate("err")
            issues.add(if (pitch > 0) "Punti troppo in basso! Tieni il telefono verticale" else "Punti troppo in alto! Tieni il telefono verticale")
            "err"
        }
    }

    val (lightStatus, lightLabel) = when {
        luma < ScanConfig.MIN_LUMA_OK || underExposedPct > ScanConfig.MAX_CLIP_PCT -> {
            elevate("err")
            issues.add("Immagine troppo scura: si perdono i dettagli tra i tronchi")
            "err" to "Buio"
        }
        luma > ScanConfig.MAX_LUMA_OK || overExposedPct > ScanConfig.MAX_CLIP_PCT -> {
            elevate("warn")
            issues.add("Zone sovraesposte: evita il riflesso diretto del sole")
            "warn" to "Riflessi"
        }
        else -> "ok" to "${((luma / 255.0) * 100).roundToInt()}% OK"
    }

    val (paceStatus, paceLabel) = when {
        frameFlow > ScanConfig.MAX_FLOW_OK || rotSpeed > ScanConfig.MAX_ROTATION_RATE -> {
            elevate("err")
            issues.add("Movimento troppo veloce! Cammina piano parallelo alla catasta")
            "err" to "Veloce!"
        }
        sharpness < ScanConfig.MIN_SHARPNESS -> {
            elevate("warn")
            issues.add("Immagine sfocata: tieni ferme le mani o controlla la messa a fuoco")
            "warn" to "Sfocato"
        }
        isRecording && frameFlow < ScanConfig.MIN_FLOW_RECORDING -> {
            elevate("warn")
            issues.add("Cammina con passo costante lungo la catasta")
            "warn" to "Fermo"
        }
        else -> "ok" to "Stabile"
    }

    // In modalità calibrazione i controlli della catasta non contano: decide la guida
    val calibHint = computeCalibHint(calibStatus, isRecording, rotSpeed)
    val headerStatus = if (calibMode) {
        when (calibHint.level) {
            HintLevel.OK -> "ok"
            HintLevel.WARN -> "warn"
            HintLevel.ERR -> "err"
        }
    } else severity

//    val coachingText = if (calibMode) {
//        calibHint.text
//    }
//    else if (issues.isEmpty()) {
//        if (isRecording) "Great! Keep walking horizontally parallel to the pile."
//        else "All checks passed! Lock AE/AF, press Record, and walk parallel."
//    } else {
//        issues.first()
//    }

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
            Toast.makeText(context, "AE/AF e bilanciamento del bianco bloccati", Toast.LENGTH_SHORT).show()
        } else {
            cam.cameraControl.cancelFocusAndMetering()
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                .build()
            camera2Control.setCaptureRequestOptions(options)
            isAeAfLocked = false
            Toast.makeText(context, "AE/AF sbloccati (automatico)", Toast.LENGTH_SHORT).show()
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
            if (!logger.start(context.cacheDir, currentVideoName.removeSuffix(".mp4"), exportOrientation = !calibMode))
                Toast.makeText(context, "Sensore di orientamento non disponibile: IMU non registrata", Toast.LENGTH_LONG).show()

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
                            Toast.makeText(context, "Registrazione non riuscita.", Toast.LENGTH_SHORT).show()
                        } else {
                            logger.stop(file)
                            showSummaryModal = true
                        }
                    }
                }
        }
    }

    /** Salva la scansione con il codice scelto e, se c'è connessione, la carica subito. */
    fun saveScan(code: String) {
        val video = tempVideoFile ?: return
        if (saving) return
        val orientation = logger.files.firstOrNull { it.name.endsWith(".orientation.json") }
        saving = true
        saveError = null
        repo.scope.launch {
            try {
                repo.save(code, video, orientation)
            } catch (e: Exception) {
                // il video temporaneo resta: si può riprovare
                Log.e(VIDEO_TAG, "Salvataggio non riuscito", e)
                saveError = if (e is IllegalArgumentException) e.message else "Salvataggio non riuscito: ${e.message}"
                return@launch
            } finally {
                saving = false
            }
            video.delete()
            logger.deleteFiles()
            tempVideoFile = null
            showCodeDialog = false
            showSummaryModal = false
            Toast.makeText(context, "Scansione $code salvata", Toast.LENGTH_SHORT).show()
            Toast.makeText(context, uploadMessage(repo.upload(code), code), Toast.LENGTH_SHORT).show()
        }
    }

    /** Calcola calibration.json dal video della scacchiera; si conserva solo il risultato. */
    fun saveCalibration() {
        val file = tempVideoFile ?: return
        logger.deleteFiles()
        tempVideoFile = null
        calibMode = false   // dopo il salvataggio si torna alla scansione
        showSummaryModal = false
        scope.launch {
            calibProgress = "Calibrazione in corso… 0%"
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    CalibrationExport.compute(file) { p ->
                        calibProgress = "Calibrazione in corso… ${(p * 100).roundToInt()}%"
                    }
                }
            }
            file.delete()
            calibProgress = null
            val r = result.getOrNull()
            if (r == null) {
                result.exceptionOrNull()?.printStackTrace()
                Toast.makeText(context, "Calibrazione non riuscita: scacchiera non trovata", Toast.LENGTH_LONG).show()
                return@launch
            }
            repo.scope.launch {
                try {
                    repo.saveCalibration(r.json)
                    Toast.makeText(
                        context,
                        String.format(Locale.US, "Calibrazione salvata: RMS %.2f px, %d fotogrammi (%dx%d)", r.rms, r.used, r.width, r.height),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Log.e(VIDEO_TAG, "Scrittura di calibration.json non riuscita", e)
                    Toast.makeText(context, "Impossibile salvare la calibrazione: ${e.message}", Toast.LENGTH_LONG).show()
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
                        // capPreview = true limita la preview a 720p: lo schermo non ne risente, ma libera
                        // banda allo stream video su dispositivi che non reggono preview + analisi + video a piena risoluzione.
                        fun buildPreview(capPreview: Boolean): Preview = Preview.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                    .also {
                                        if (capPreview) it.setResolutionStrategy(
                                            ResolutionStrategy(
                                                android.util.Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                            )
                                        )
                                    }
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
                                            if (prevSampledLuma != null) {
                                                val rawFlow = flowDiffSum.toDouble() / totalSamples
                                                frameFlow = frameFlow * (1 - ScanConfig.PACE_SMOOTHING) + rawFlow * ScanConfig.PACE_SMOOTHING
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

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        val cameraInfo = cameraSelector.filter(cameraProvider.availableCameraInfos).firstOrNull()

                        // Qualità massima che la fotocamera dichiara per la registrazione (es. FHD = 1920x1080)
                        val qualityRank = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                        val supported = cameraInfo?.let {
                            Recorder.getVideoCapabilities(it).getSupportedQualities(DynamicRange.SDR)
                        }.orEmpty()
                        val bestQuality = qualityRank.firstOrNull { it in supported } ?: Quality.FHD
                        val targetSize = cameraInfo?.let { QualitySelector.getResolution(it, bestQuality) }
                        Log.i(VIDEO_TAG, "Qualità supportate: $supported -> richiesta $bestQuality ($targetSize)")

                        val recorder = Recorder.Builder()
                            .setQualitySelector(
                                QualitySelector.from(bestQuality, FallbackStrategy.lowerQualityThan(bestQuality))
                            )
                            .build()
                        val videoCapture = VideoCapture.withOutput(recorder)
                        videoCaptureRef = videoCapture

                        fun bindAll(capPreview: Boolean): Boolean = try {
                            cameraProvider.unbindAll()
                            cameraRef = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                buildPreview(capPreview),
                                imageAnalysis,
                                videoCapture
                            )
                            true
                        } catch (e: Exception) {
                            Log.e(VIDEO_TAG, "Bind fallito (capPreview=$capPreview)", e)
                            false
                        }

                        fun videoIsDowngraded(): Boolean {
                            val actual = videoCapture.resolutionInfo?.resolution ?: return false
                            val target = targetSize ?: return false
                            return minOf(actual.width, actual.height) < minOf(target.width, target.height)
                        }

                        // 1° tentativo: preview a piena risoluzione. Se CameraX ha dovuto abbassare il video
                        // per far stare insieme preview + analisi + video, si riprova con la preview a 720p.
                        var bound = bindAll(capPreview = false)
                        if (!bound || videoIsDowngraded()) {
                            Log.w(VIDEO_TAG, "Video ridotto a ${videoCapture.resolutionInfo?.resolution}, riprovo con preview a 720p")
                            bound = bindAll(capPreview = true)
                        }
                        Log.i(VIDEO_TAG, "Risoluzione video finale: ${videoCapture.resolutionInfo?.resolution}")
                        if (bound) logger.onCameraBound(cameraRef!!)
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

        // FULL-SCREEN CANVAS FOR LINES (Moved outside the Column)
        if (!calibMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 8.dp.toPx()), 0f)

                // Now size.height is the full screen height!
                val topY = size.height * GUIDE_TOP_FRAC
                val botY = size.height * GUIDE_BOTTOM_FRAC

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
        }

        // Etichette delle linee guida: alla stessa distanza dalla rispettiva linea,
        // sotto la linea in alto e sopra la linea in basso (cioè dentro la fascia)
        if (!calibMode) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val gap = 8.dp
                val labelModifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                Text(
                    "▲ ALLINEA LA CIMA DELLA CATASTA ENTRO LA LINEA ▲",
                    color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .offset(y = maxHeight * GUIDE_TOP_FRAC + gap)
                        .then(labelModifier)
                )
                Text(
                    "▼ ALLINEA LA BASE / IL TERRENO ENTRO LA LINEA ▼",
                    color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .offset(y = -(maxHeight * (1f - GUIDE_BOTTOM_FRAC) + gap))
                        .then(labelModifier)
                )
            }
        }

        Column(
            // safeDrawing: niente sotto la barra di stato, il foro della fotocamera o la barra di navigazione
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (headerStatus == "err") Color(0xC87F1D1D) else glassBg)
                        .border(1.5.dp, statusColor(headerStatus), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor(headerStatus)))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = when {
                                calibMode -> when {
                                    isRecording && calibStatus.complete -> "CALIBRAZIONE — COMPLETA"
                                    isRecording -> "CALIBRAZIONE — REC"
                                    else -> "MODALITÀ CALIBRAZIONE"
                                }
                                isRecording && severity == "ok" -> "REGISTRAZIONE — OTTIMALE"
                                severity == "ok" -> "PRONTO A SCANSIONARE"
                                severity == "warn" -> "PICCOLA CORREZIONE CONSIGLIATA"
                                else -> "CORREZIONE NECESSARIA"
                            },
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                    }
                    val mins = (elapsedSec / 60).toString().padStart(2, '0')
                    val secs = (elapsedSec % 60).toString().padStart(2, '0')
                    Text(text = if (isAeAfLocked) "🔒 $mins:$secs" else "$mins:$secs", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                        MetricCard(modifier = Modifier.weight(1f), label = "ORIZZONTE", value = String.format(Locale.US, "%+.1f°", roll), borderColor = statusColor(rollStatus))
                        MetricCard(modifier = Modifier.weight(1f), label = "INCLINAZ.", value = String.format(Locale.US, "%+.1f°", pitch), borderColor = statusColor(pitchStatus))
                        MetricCard(modifier = Modifier.weight(1f), label = "LUCE", value = lightLabel, borderColor = statusColor(lightStatus))
                        MetricCard(modifier = Modifier.weight(1f), label = "MOVIMENTO", value = paceLabel, borderColor = statusColor(paceStatus))
                    }
                }
            }

            // Middle Spacer (le etichette delle linee sono posizionate sopra, rispetto alle linee stesse)
            Box(modifier = Modifier.fillMaxWidth().weight(1f))

            // blocco di controllo in basso (Rec), un po' sollevato dal bordo
            Column(modifier = Modifier.padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (calibMode) {
                    val coachingText = calibHint.text
                    Text(text = coachingText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.clip(RoundedCornerShape(50)).background(glassBg).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 9.dp))
                }

                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(glassBg).border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    // tre posizioni fisse: il pulsante Rec resta al centro anche senza Lock AE/AF
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        // in calibrazione AE/AF non si blocca: il pulsante non c'è
                        if (!calibMode) {
                            Button(onClick = { toggleAeAfLock() }, colors = ButtonDefaults.buttonColors(containerColor = if (isAeAfLocked) colorWarn else Color.White.copy(alpha = 0.15f))) {
                                Text(text = if (isAeAfLocked) "AE/AF bloccati" else "Blocca AE/AF", color = if (isAeAfLocked) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Box(modifier = Modifier.size(66.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).clickable { toggleRecording() }, contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(if (isRecording) 28.dp else 50.dp).clip(if (isRecording) RoundedCornerShape(6.dp) else CircleShape).background(colorErr))
                    }

                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val pending = repo.pendingCount
                            Button(
                                onClick = { showScanList = true },
                                enabled = !isRecording && tempVideoFile == null,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = if (pending > 0) "Scansioni ($pending)" else "Scansioni",
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1
                                )
                            }
                            // Pulsante modalità calibrazione: bloccato durante la registrazione e finché
                            // il video precedente non è stato salvato o scartato
                            Button(
                                onClick = {
                                    calibMode = !calibMode
                                    if (calibMode) {
                                        calibGuide.reset()
                                        if (isAeAfLocked) toggleAeAfLock()   // in calibrazione AE/AF resta automatico
                                    }
                                },
                                enabled = !isRecording && tempVideoFile == null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (calibMode) colorCalib else Color.White.copy(alpha = 0.15f)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = "Calibrazione",   // attiva = sfondo colorato
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Overlay di calibrazione: griglia di copertura allineata al fotogramma registrato
        // (la preview è in FIT_CENTER), cella obiettivo in giallo, scacchiera rilevata in ciano.
        // È disegnato sopra l'interfaccia perché barra di stato e controlli coprono i bordi dell'immagine.
        if (calibMode && !showSummaryModal && !showScanList) {
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

        calibProgress?.let { text ->
            Box(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 76.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xE61E293B))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Post-Recording Video Review Modal
        if (showSummaryModal) {
            val isCalib = currentVideoName.startsWith("calib_")
            val score = remember(telemetryLog.size) { ScanScore.compute(telemetryLog) }
            val scorePct = score.total

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xEB090D16))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF1E293B))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(if (isCalib) "Rivedi calibrazione" else "Rivedi scansione", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

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
                            // anteprima grande: ~65% dell'altezza dello schermo (il video è verticale)
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((LocalConfiguration.current.screenHeightDp * 0.65f).dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black)
                        )
                    } else {
                        Text("Anteprima non disponibile", color = Color.Gray, modifier = Modifier.padding(20.dp))
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
                            text = "Punteggio qualità: $scorePct%",
                            color = when {
                                scorePct >= 80 -> colorOk
                                scorePct >= 50 -> colorWarn
                                else -> colorErr
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Movimento ${score.motion}% · Nitidezza ${score.sharpness}% · Livello ${score.level}% · Luce ${score.light}%",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // SAVE BUTTON
                        Button(
                            onClick = {
                                if (isCalib) saveCalibration()
                                else { saveError = null; showCodeDialog = true }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colorOk),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Salva", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                            Text("Scarta", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (showCodeDialog) {
            ScanCodeDialog(
                usedCodes = repo.usedCodes,
                calibrated = repo.calibrated,
                saving = saving,
                saveError = saveError,
                onConfirm = { saveScan(it) },
                onDismiss = { showCodeDialog = false }
            )
        }

        if (showScanList) ScanListScreen(repo, onClose = { showScanList = false })
    }
}

@Composable
fun MetricCard(modifier: Modifier = Modifier, label: String, value: String, borderColor: Color) {
    Column(modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xC80F172A)).border(1.5.dp, borderColor, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private const val VIDEO_TAG = "WoodScanVideo"

// Posizione delle linee guida della catasta, in frazione dell'altezza dello schermo
private const val GUIDE_TOP_FRAC = 0.18f
private const val GUIDE_BOTTOM_FRAC = 0.82f
