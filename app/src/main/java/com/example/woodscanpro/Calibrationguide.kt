package com.example.woodscanpro

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Parametri della guida alla calibrazione (da tarare sul campo). */
object CalibConfig {
    const val PATTERN_COLS = 9            // angoli interni della scacchiera 10x7
    const val PATTERN_ROWS = 6
    const val GRID_SHORT = 4              // celle di copertura sul lato corto dell'immagine
    const val GRID_LONG = 6               // celle sul lato lungo
    const val HITS_PER_CELL = 6           // angoli rilevati perché una cella conti come coperta
    const val MIN_COVERAGE_PCT = 90       // percentuale di celle coperte richiesta
    const val TILT_RATIO = 1.15f          // rapporto tra lati opposti oltre il quale la scacchiera è inclinata (~20-25°)
    const val TILT_HITS = 3               // rilevamenti richiesti per ciascuna inclinazione
    const val NEAR_AREA = 0.20f           // area del quadrilatero degli angoli interni / area immagine: "vicino" (scacchiera su ~1/3 dello schermo)
    const val FAR_AREA = 0.08f            // sotto questa soglia: "lontano"
    const val DIST_HITS = 3
    const val DETECT_INTERVAL_MS = 200L   // circa 5 rilevamenti al secondo
    const val LOST_MS = 1500L             // dopo questo tempo senza rilevamenti la scacchiera è "persa"
    const val MAX_ROT_SPEED_DPS = 25.0    // velocità di rotazione oltre la quale si chiede di rallentare

    // Rilevatore di angoli (provato su immagini reali e sintetiche: inclinate, ruotate, piccole, grandi)
    const val DETECT_SIDE = 400           // l'immagine viene ridotta di un fattore intero finché il lato lungo non supera ~2x questo valore
    const val MIN_CONTRAST = 40           // differenza minima tra chiaro e scuro sull'anello (livelli 0-255)
    const val RESP_K = 1.5f               // risposta minima del filtro ad angolo, in proporzione al contrasto
    const val MAX_CANDIDATES = 300        // candidati tenuti dopo la soppressione dei non massimi
    const val NMS_RADIUS = 5              // px: un solo punto per angolo (il passo minimo rilevabile è ~6 px)
    const val LINK_FACTOR = 1.5f          // due angoli sono vicini se distano meno di 1.5 volte il passo locale
    const val MIN_CORNERS = 40            // angoli minimi perché il gruppo sia considerato la scacchiera
    const val MAX_CORNERS = 70
    const val MIN_GRID_FILL = 0.4f        // area / (angoli * passo²): scarta gruppi allineati su una linea
}

enum class HintLevel { OK, WARN, ERR }

/**
 * Istruzione per l'operatore e cosa evidenziare sull'overlay:
 * [targetCell] cella della griglia da raggiungere (fase copertura),
 * [targetSide] lato della scacchiera da avvicinare alla camera, 0 sinistro, 1 destro, 2 superiore, 3 inferiore (fase inclinazioni),
 * [targetArea] riquadro tratteggiato da raggiungere o in cui stare, come frazione dell'immagine (fase distanze).
 */
data class CalibHint(
    val text: String,
    val level: HintLevel,
    val targetCell: Int? = null,
    val targetSide: Int? = null,
    val targetArea: Float? = null,
)

/**
 * Stato della guida. Le coordinate sono normalizzate (0..1) sull'immagine già ruotata
 * come la vede l'operatore, che ha lo stesso campo di vista del video (entrambi 16:9).
 */
data class CalibStatus(
    val gridCols: Int = CalibConfig.GRID_SHORT,
    val gridRows: Int = CalibConfig.GRID_LONG,
    val hits: List<Int> = List(CalibConfig.GRID_SHORT * CalibConfig.GRID_LONG) { 0 },
    val tiltHits: List<Int> = List(4) { 0 },   // lato più vicino alla camera: sinistro, destro, superiore, inferiore
    val nearHits: Int = 0,
    val farHits: Int = 0,
    val accepted: Int = 0,                     // rilevamenti conteggiati durante la registrazione
    val quad: List<Float>? = null,             // angoli esterni rilevati ora: x0,y0,..,x3,y3 (null se non rilevata)
    val cx: Float = 0.5f,                      // centro dell'ultima scacchiera rilevata
    val cy: Float = 0.5f,
    val msSinceFound: Long = Long.MAX_VALUE,
    val frameAspect: Float = 9f / 16f,         // larghezza / altezza dell'immagine ruotata
    // Misure sul frame attuale, disponibili solo se la griglia è completa (altrimenti null):
    val liveSideEdge: List<Int>? = null,       // per lato (sx, dx, alto, basso): indice k del lato del quad, da quad[k] a quad[k+1]
    val liveSideRatio: List<Float>? = null,    // per lato: lunghezza / lunghezza del lato opposto (>1 = lato più vicino)
    val liveArea: Float? = null,               // area del quad / area dell'immagine
) {
    val covered: List<Boolean> get() = hits.map { it >= CalibConfig.HITS_PER_CELL }
    val coveragePct: Int get() = covered.count { it } * 100 / covered.size
    val tiltsDone: Int get() = tiltHits.count { it >= CalibConfig.TILT_HITS }
    val nearDone: Boolean get() = nearHits >= CalibConfig.DIST_HITS
    val farDone: Boolean get() = farHits >= CalibConfig.DIST_HITS
    val distDone: Int get() = (if (nearDone) 1 else 0) + (if (farDone) 1 else 0)
    val detected: Boolean get() = quad != null
    val complete: Boolean get() = coveragePct >= CalibConfig.MIN_COVERAGE_PCT && tiltsDone == 4 && distDone == 2

    /** Cella non ancora coperta più vicina alla scacchiera: il prossimo obiettivo. */
    fun nearestUncovered(): Int? {
        val cov = covered
        return cov.indices.filter { !cov[it] }.minByOrNull { i ->
            val ux = ((i % gridCols) + 0.5f) / gridCols
            val uy = ((i / gridCols) + 0.5f) / gridRows
            hypot((ux - cx) * frameAspect, uy - cy)
        }
    }

    fun isBorderCell(i: Int): Boolean {
        val c = i % gridCols
        val r = i / gridCols
        return c == 0 || c == gridCols - 1 || r == 0 || r == gridRows - 1
    }

    fun zoneName(i: Int): String {
        val c = i % gridCols
        val r = i / gridCols
        val v = when {
            r < gridRows / 3 -> "in alto"
            r >= gridRows - gridRows / 3 -> "in basso"
            else -> ""
        }
        val h = when (c) {
            0 -> "a sinistra"
            gridCols - 1 -> "a destra"
            else -> ""
        }
        return listOf(v, h).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "al centro" }
    }
}

/** Barra di avanzamento testuale (0..1). */
private fun progressBar(p: Float): String {
    val n = (p.coerceIn(0f, 1f) * 10).roundToInt()
    return "▰".repeat(n) + "▱".repeat(10 - n)
}

/** Riga di avanzamento: barra più un commento quando si è vicini all'obiettivo. */
private fun progressLine(p: Float?): String = when {
    p == null -> "Inquadra tutta la scacchiera"
    p >= 1f -> "${progressBar(p)}  Così! Resta fermo un attimo"
    p >= 0.5f -> "${progressBar(p)}  Ancora un po'"
    else -> progressBar(p)
}

/**
 * Istruzione da mostrare all'operatore in modalità calibrazione. La ripresa è divisa in tre fasi:
 * 1. copertura: portare la scacchiera in tutte le zone dell'immagine;
 * 2. viste di sbieco: guardare la scacchiera da sinistra, destra, sopra e sotto (~25-30°);
 * 3. distanze: una vista da vicino e una da lontano.
 */
fun computeCalibHint(s: CalibStatus, isRecording: Boolean, aeAfLocked: Boolean, rotSpeedDps: Double): CalibHint {
    if (!isRecording) return when {
        !s.detected -> CalibHint("Inquadra tutta la scacchiera", HintLevel.WARN)
        !aeAfLocked -> CalibHint("Scacchiera rilevata: blocca AE/AF, poi premi Rec", HintLevel.WARN)
        else -> CalibHint("Pronto: premi Rec. Tre fasi: copertura, viste di sbieco, distanze", HintLevel.OK)
    }
    if (rotSpeedDps > CalibConfig.MAX_ROT_SPEED_DPS)
        return CalibHint("Più lento! Muovi il telefono con calma", HintLevel.ERR)
    if (s.msSinceFound > CalibConfig.LOST_MS)
        return CalibHint("Scacchiera persa: inquadrala tutta, senza riflessi", HintLevel.WARN)
    if (s.complete)
        return CalibHint("Calibrazione completa ✓ Puoi fermare la registrazione", HintLevel.OK)

    // Fase 1: copertura
    if (s.coveragePct < CalibConfig.MIN_COVERAGE_PCT) {
        val target = s.nearestUncovered()
        if (target != null) {
            val extra = if (s.isBorderCell(target)) "\nSe esce dallo schermo, allontanati un po'" else ""
            return CalibHint(
                "Fase 1/3 · Copertura ${s.coveragePct}%\n" +
                        "Sposta il telefono: porta la scacchiera nel riquadro giallo (${s.zoneName(target)})$extra",
                HintLevel.OK, targetCell = target
            )
        }
    }

    // Fase 2: viste di sbieco. Il lato da avvicinare è evidenziato in giallo sul contorno della scacchiera
    val side = s.tiltHits.indexOfFirst { it < CalibConfig.TILT_HITS }
    if (side >= 0) {
        val name = listOf("sinistro", "destro", "superiore", "inferiore")[side]
        val ratio = s.liveSideRatio?.get(side)
        val p = ratio?.let { (it - 1f) / (CalibConfig.TILT_RATIO - 1f) }
        return CalibHint(
            "Fase 2/3 · Vista di sbieco ${s.tiltsDone + 1} di 4: lato $name\n" +
                    "Sposta il telefono verso il lato giallo della scacchiera e ruotalo per tenerla al centro\n" +
                    progressLine(p),
            HintLevel.OK, targetSide = side
        )
    }

    // Fase 3: distanze. Il contorno azzurro della scacchiera va confrontato con il riquadro tratteggiato
    if (!s.nearDone) {
        val p = s.liveArea?.let { it / CalibConfig.NEAR_AREA }
        return CalibHint(
            "Fase 3/3 · Vista da vicino\n" +
                    "Avvicina il telefono finché il contorno azzurro raggiunge il riquadro tratteggiato\n" +
                    progressLine(p),
            HintLevel.OK, targetArea = CalibConfig.NEAR_AREA
        )
    }
    val p = s.liveArea?.let { CalibConfig.FAR_AREA / it }
    return CalibHint(
        "Fase 3/3 · Vista da lontano\n" +
                "Allontana il telefono finché il contorno azzurro sta dentro il riquadro tratteggiato\n" +
                progressLine(p),
        HintLevel.OK, targetArea = CalibConfig.FAR_AREA
    )
}

/**
 * Rileva la scacchiera nei frame di ImageAnalysis e tiene traccia di copertura, inclinazioni e distanze.
 * Non usa librerie esterne: gli angoli interni ("a X") sono trovati con il filtro ChESS
 * su un anello di 16 pixel, poi raggruppati in una griglia regolare.
 * La posizione è approssimata al pixel: basta per guidare l'operatore, la stima precisa la fa Python.
 *
 * [process] va chiamato dal thread di analisi; [reset] e [accumulate] dal thread principale.
 * [onUpdate] viene chiamato dal thread di analisi a ogni rilevamento (circa 5 volte al secondo).
 */
class CalibrationGuide(private val onUpdate: (CalibStatus) -> Unit) {

    /** True durante la registrazione: solo allora i rilevamenti contano per la copertura. */
    @Volatile var accumulate = false

    @Volatile private var resetRequested = false
    private var status = CalibStatus()
    private var lastRunMs = 0L
    private var lastFoundMs = 0L

    // buffer riutilizzati tra un frame e l'altro
    private var img = IntArray(0)
    private var resp = IntArray(0)
    private var suppressed = BooleanArray(0)
    private val ring = IntArray(16)
    private val px = FloatArray(CalibConfig.MAX_CANDIDATES)
    private val py = FloatArray(CalibConfig.MAX_CANDIDATES)

    /** Angoli interni trovati (normalizzati e ruotati), quadrilatero esterno e se i suoi lati sono quelli della griglia completa. */
    private class Detection(val pts: FloatArray, val quad: List<Float>, val fullGrid: Boolean)

    /** Azzera copertura e contatori (applicato al prossimo frame). */
    fun reset() {
        resetRequested = true
    }

    fun process(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastRunMs < CalibConfig.DETECT_INTERVAL_MS) return
        lastRunMs = now

        val rot = image.imageInfo.rotationDegrees
        val rw = if (rot % 180 == 0) image.width else image.height
        val rh = if (rot % 180 == 0) image.height else image.width
        val gridCols = if (rw <= rh) CalibConfig.GRID_SHORT else CalibConfig.GRID_LONG
        val gridRows = if (rw <= rh) CalibConfig.GRID_LONG else CalibConfig.GRID_SHORT

        if (resetRequested || status.gridCols != gridCols) {
            status = CalibStatus(gridCols = gridCols, gridRows = gridRows, hits = List(gridCols * gridRows) { 0 })
            lastFoundMs = 0L
            resetRequested = false
        }

        var s = status.copy(
            frameAspect = rw.toFloat() / rh,
            quad = null, liveSideEdge = null, liveSideRatio = null, liveArea = null
        )
        val det = detect(image, rot)
        if (det != null) {
            lastFoundMs = now
            val q = det.quad
            s = s.copy(
                quad = q,
                cx = (q[0] + q[2] + q[4] + q[6]) / 4f,
                cy = (q[1] + q[3] + q[5] + q[7]) / 4f
            )
            // Prospettiva e dimensione si misurano solo se il quadrilatero è quello della griglia completa:
            // un angolo esterno mancante lo deformerebbe e simulerebbe un'inclinazione che non c'è
            if (det.fullGrid) {
                val sides = sideInfo(q, rw, rh)
                s = s.copy(liveSideEdge = sides.edge.toList(), liveSideRatio = sides.ratio.toList(), liveArea = quadArea(q))
            }
            if (accumulate) s = accumulateDetection(s, det)
        }
        status = s.copy(msSinceFound = if (lastFoundMs == 0L) Long.MAX_VALUE else now - lastFoundMs)
        onUpdate(status)
    }

    private fun detect(image: ImageProxy, rot: Int): Detection? {
        // 1. Luminanza (piano Y), ridotta di un fattore intero
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val f = max(1, max(image.width, image.height) / CalibConfig.DETECT_SIDE)
        val w = image.width / f
        val h = image.height / f
        if (img.size != w * h) {
            img = IntArray(w * h)
            resp = IntArray(w * h)
            suppressed = BooleanArray(w * h)
        }
        val blockArea = f * f
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                for (dy in 0 until f) {
                    val row = (y * f + dy) * rs
                    for (dx in 0 until f) sum += buf.get(row + (x * f + dx) * ps).toInt() and 0xFF
                }
                img[y * w + x] = sum / blockArea
            }
        }

        // 2. Risposta ChESS: in un angolo a X i punti opposti dell'anello hanno lo stesso colore,
        //    quelli a 90° colore opposto, e il centro è grigio medio
        val off = IntArray(16) { RING[2 * it + 1] * w + RING[2 * it] }
        java.util.Arrays.fill(resp, 0)
        var nCand = 0
        for (y in 4 until h - 4) {
            for (x in 4 until w - 4) {
                val p = y * w + x
                var mn = 255
                var mx = 0
                var sum = 0
                for (k in 0 until 16) {
                    val v = img[p + off[k]]
                    ring[k] = v
                    sum += v
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
                val contrast = mx - mn
                if (contrast < CalibConfig.MIN_CONTRAST) continue
                val mean = sum / 16
                val local = (img[p] + img[p - 1] + img[p + 1] + img[p - w] + img[p + w]) / 5
                var sr = 0
                for (k in 0 until 4) sr += abs(ring[k] + ring[k + 8] - ring[k + 4] - ring[(k + 12) and 15])
                var dr = 0
                for (k in 0 until 8) dr += abs(ring[k] - ring[k + 8])
                val r = sr - dr - 16 * abs(mean - local)
                if (r < CalibConfig.RESP_K * contrast) continue
                resp[p] = r
                nCand++
            }
        }
        if (nCand < CalibConfig.MIN_CORNERS) return null

        // 3. Soppressione dei non massimi: un solo punto per angolo (raggio circolare NMS_RADIUS)
        val cand = IntArray(nCand)
        var c = 0
        for (p in resp.indices) if (resp[p] > 0) cand[c++] = p
        val order = cand.sortedByDescending { resp[it] }
        java.util.Arrays.fill(suppressed, false)
        val nr = CalibConfig.NMS_RADIUS
        var n = 0
        for (p in order) {
            if (suppressed[p]) continue
            val x = p % w
            val y = p / w
            px[n] = x + 0.5f
            py[n] = y + 0.5f
            n++
            for (yy in max(0, y - nr)..min(h - 1, y + nr))
                for (xx in max(0, x - nr)..min(w - 1, x + nr))
                    if ((xx - x) * (xx - x) + (yy - y) * (yy - y) <= nr * nr) suppressed[yy * w + xx] = true
            if (n >= CalibConfig.MAX_CANDIDATES) break
        }
        if (n < CalibConfig.MIN_CORNERS) return null

        // 4. Raggruppamento: si collegano angoli vicini rispetto al passo locale della griglia
        val nn = FloatArray(n) { Float.MAX_VALUE }
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = hypot(px[i] - px[j], py[i] - py[j])
            if (d < nn[i]) nn[i] = d
            if (d < nn[j]) nn[j] = d
        }
        val parent = IntArray(n) { it }
        fun find(a0: Int): Int {
            var a = a0
            while (parent[a] != a) {
                parent[a] = parent[parent[a]]
                a = parent[a]
            }
            return a
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = hypot(px[i] - px[j], py[i] - py[j])
            if (d <= CalibConfig.LINK_FACTOR * min(nn[i], nn[j])) {
                val ra = find(i)
                val rb = find(j)
                if (ra != rb) parent[ra] = rb
            }
        }
        val groupSize = IntArray(n)
        for (i in 0 until n) groupSize[find(i)]++
        val best = groupSize.indices.maxByOrNull { groupSize[it] } ?: return null
        val members = (0 until n).filter { find(it) == best }
        if (members.size !in CalibConfig.MIN_CORNERS..CalibConfig.MAX_CORNERS) return null

        // 5. Quadrilatero esterno: la diagonale più lunga e i due punti più lontani da essa
        var a = members[0]
        var b = members[0]
        var bestD = -1f
        for (i in members) for (j in members) {
            val dx = px[i] - px[j]
            val dy = py[i] - py[j]
            val d = dx * dx + dy * dy
            if (d > bestD) { bestD = d; a = i; b = j }
        }
        var crossMax = 0f
        var crossMin = 0f
        var ci = -1
        var di = -1
        for (i in members) {
            val cr = (px[b] - px[a]) * (py[i] - py[a]) - (py[b] - py[a]) * (px[i] - px[a])
            if (cr > crossMax) { crossMax = cr; ci = i }
            if (cr < crossMin) { crossMin = cr; di = i }
        }
        if (ci < 0 || di < 0) return null

        // scarta gruppi "lineari" (es. venature): una griglia vera occupa un'area proporzionale al numero di angoli
        val quadArea = (crossMax - crossMin) / 2f
        val step = members.map { nn[it] }.sorted()[members.size / 2]
        if (quadArea < CalibConfig.MIN_GRID_FILL * members.size * step * step) return null

        // Il quadrilatero è affidabile (per inclinazione e distanza) solo se i suoi vertici sono i veri
        // angoli esterni: allora i lati opposti contengono lo stesso numero di angoli, 9 e 6
        val qi = intArrayOf(a, ci, b, di)
        val onEdge = IntArray(4)
        val tol = 0.35f * step
        for (k in 0 until 4) {
            val p0 = qi[k]
            val p1 = qi[(k + 1) % 4]
            val ex = px[p1] - px[p0]
            val ey = py[p1] - py[p0]
            val len = hypot(ex, ey)
            if (len <= 0f) return null
            for (i in members) {
                val t = ((px[i] - px[p0]) * ex + (py[i] - py[p0]) * ey) / (len * len)
                if (t < -0.05f || t > 1.05f) continue
                val dist = abs((px[i] - px[p0]) * ey - (py[i] - py[p0]) * ex) / len
                if (dist <= tol) onEdge[k]++
            }
        }
        val fullGrid = onEdge[0] == onEdge[2] && onEdge[1] == onEdge[3] &&
                setOf(onEdge[0], onEdge[1]) == setOf(CalibConfig.PATTERN_COLS, CalibConfig.PATTERN_ROWS)

        // 6. Coordinate normalizzate, ruotate come l'immagine mostrata all'operatore
        fun put(i: Int, out: FloatArray, o: Int) {
            val u = px[i] / w
            val v = py[i] / h
            when (rot) {
                90 -> { out[o] = 1f - v; out[o + 1] = u }
                180 -> { out[o] = 1f - u; out[o + 1] = 1f - v }
                270 -> { out[o] = v; out[o + 1] = 1f - u }
                else -> { out[o] = u; out[o + 1] = v }
            }
        }
        val pts = FloatArray(members.size * 2)
        members.forEachIndexed { k, i -> put(i, pts, 2 * k) }
        val quad = FloatArray(8)
        listOf(a, ci, b, di).forEachIndexed { k, i -> put(i, quad, 2 * k) }   // in ordine lungo il perimetro
        return Detection(pts, quad.toList(), fullGrid)
    }

    private fun accumulateDetection(s: CalibStatus, det: Detection): CalibStatus {
        val pts = det.pts
        val hits = s.hits.toMutableList()
        for (i in 0 until pts.size / 2) {
            val c = (pts[2 * i] * s.gridCols).toInt().coerceIn(0, s.gridCols - 1)
            val r = (pts[2 * i + 1] * s.gridRows).toInt().coerceIn(0, s.gridRows - 1)
            hits[r * s.gridCols + c]++
        }

        // vista di sbieco e distanza contano solo con le misure del frame (cioè con la griglia completa)
        val ratio = s.liveSideRatio
        val area = s.liveArea
        if (ratio == null || area == null) return s.copy(hits = hits, accepted = s.accepted + 1)

        val tilts = s.tiltHits.toMutableList()
        for (side in 0 until 4) if (ratio[side] >= CalibConfig.TILT_RATIO) tilts[side]++

        return s.copy(
            hits = hits,
            tiltHits = tilts,
            nearHits = s.nearHits + if (area >= CalibConfig.NEAR_AREA) 1 else 0,
            farHits = s.farHits + if (area <= CalibConfig.FAR_AREA) 1 else 0,
            accepted = s.accepted + 1
        )
    }

    /** Area del quadrilatero (formula di Gauss) come frazione dell'immagine. */
    private fun quadArea(q: List<Float>): Float {
        var area = 0f
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            area += q[2 * k] * q[2 * j + 1] - q[2 * j] * q[2 * k + 1]
        }
        return abs(area) / 2f
    }

    /** Per ogni lato della scacchiera (0 sinistro, 1 destro, 2 superiore, 3 inferiore, come appare sullo schermo). */
    private class SideInfo(
        val edge: IntArray,     // indice k del lato del quad (da quad[k] a quad[k+1])
        val ratio: FloatArray   // lunghezza / lunghezza del lato opposto: > 1 se è il lato più vicino alla camera
    )

    /**
     * Prospettiva della scacchiera: il lato più vicino alla camera appare più lungo di quello opposto.
     * Si separano i lati "verticali" (sinistro/destro) da quelli "orizzontali" (superiore/inferiore).
     */
    private fun sideInfo(q: List<Float>, rw: Int, rh: Int): SideInfo {
        val x = FloatArray(4) { q[2 * it] * rw }
        val y = FloatArray(4) { q[2 * it + 1] * rh }
        val len = FloatArray(4) { k -> hypot(x[(k + 1) % 4] - x[k], y[(k + 1) % 4] - y[k]) }
        fun verticality(k: Int) = abs(y[(k + 1) % 4] - y[k]) / max(len[k], 1e-3f)
        fun midX(k: Int) = (x[k] + x[(k + 1) % 4]) / 2f
        fun midY(k: Int) = (y[k] + y[(k + 1) % 4]) / 2f

        val firstPairVertical = verticality(0) + verticality(2) >= verticality(1) + verticality(3)
        val (v1, v2) = if (firstPairVertical) 0 to 2 else 1 to 3
        val (h1, h2) = if (firstPairVertical) 1 to 3 else 0 to 2
        val left = if (midX(v1) < midX(v2)) v1 else v2
        val right = if (left == v1) v2 else v1
        val top = if (midY(h1) < midY(h2)) h1 else h2
        val bottom = if (top == h1) h2 else h1

        return SideInfo(
            edge = intArrayOf(left, right, top, bottom),
            ratio = floatArrayOf(
                len[left] / max(len[right], 1e-3f), len[right] / max(len[left], 1e-3f),
                len[top] / max(len[bottom], 1e-3f), len[bottom] / max(len[top], 1e-3f)
            )
        )
    }

    private companion object {
        /** Cerchio di raggio 3 (16 punti, dx/dy alternati), percorso in senso orario. */
        val RING = intArrayOf(
            0, -3, 1, -3, 2, -2, 3, -1, 3, 0, 3, 1, 2, 2, 1, 3,
            0, 3, -1, 3, -2, 2, -3, 1, -3, 0, -3, -1, -2, -2, -1, -3
        )
    }
}