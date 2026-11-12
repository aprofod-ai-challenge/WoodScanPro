# WoodScan Pro

Applicazione Android per riprendere in video una catasta di legna in modo adatto alla fotogrammetria e all'analisi con computer vision.
Durante la ripresa guida l'operatore (orizzonte, inclinazione, esposizione, velocità, nitidezza) e salva, insieme al video, i dati dei sensori e i metadati della camera.
Include una modalità di calibrazione che guida la ripresa di una scacchiera, da cui si stimano sul PC i parametri della camera.

Versione attuale: **1.0.3**. Le modifiche sono elencate in [CHANGELOG.md](CHANGELOG.md).

---

## Requisiti

- Android 8.0 o superiore (testato su tablet Android 8.1).
- Fotocamera posteriore.
- Consigliato: giroscopio. Senza giroscopio il file `_imu.csv` non viene creato, ma video, frame e metadati vengono salvati comunque.
- Per la calibrazione: scacchiera di 10×7 quadrati da 25 mm (9×6 angoli interni), generata dal notebook di calibrazione (`scacchiera_A4.pdf`) e stampata al 100%.

## Permessi

| Permesso | Quando | Perché |
|---|---|---|
| `CAMERA` | sempre | ripresa video |
| `WRITE_EXTERNAL_STORAGE` | solo Android 9 e precedenti | salvataggio in `Download/WoodScanPro/` |

I permessi vengono chiesti al primo avvio.

## Come si usa

### Scansione della catasta

1. Mettiti di fronte alla catasta e inquadrala tra le due **linee gialle tratteggiate** (sopra la cima, sotto la base o il terreno).
2. Tieni il dispositivo verticale finché gli indicatori **HORIZON**, **PITCH**, **EXPOSURE** e **PACE/BLUR** sono verdi.
3. Premi **Lock AE/AF** per bloccare esposizione, messa a fuoco e bilanciamento del bianco.
4. Premi il pulsante rosso e cammina lentamente, parallelo alla catasta.
5. Premi di nuovo per fermare. Nella schermata **Review Scan** il video si ripete in loop e viene mostrato il **Quality Score** (percentuale di campioni senza errori).
6. Premi **Save Data** per salvare oppure **Discard** per scartare.

Il pulsante **Test Sim** attiva dei cursori per simulare inclinazione e movimento senza camera reale.

### Calibrazione della camera

Va fatta una volta per dispositivo, con le stesse impostazioni delle scansioni: stessa app, stessa risoluzione, dispositivo in verticale.

1. Appoggia la scacchiera stampata su un piano, con buona luce e senza riflessi.
2. Premi **📐 Calibrazione**. L'anteprima mostra l'intero fotogramma che verrà registrato, con sopra una griglia di copertura; il **contorno azzurro** indica la scacchiera rilevata.
3. Inquadra la scacchiera, premi **Lock AE/AF**, poi il pulsante rosso.
4. Segui le istruzioni, divise in tre fasi. La scacchiera resta ferma: si muove solo il dispositivo, lentamente.

| Fase | Cosa fare | Indicatore |
|---|---|---|
| 1. Copertura | Porta la scacchiera nel **riquadro giallo** della griglia, zona per zona, finché quasi tutte le celle sono verdi. | **COPERTURA** (obiettivo 90%) |
| 2. Viste di sbieco (4) | Un lato del contorno diventa **giallo**: spostati verso quel lato e ruota il dispositivo per tenere la scacchiera al centro (circa 25–30°). Quando la barra è piena resta fermo un attimo. Si ripete per lato sinistro, destro, superiore e inferiore. | **INCLINAZ.** x/4 |
| 3. Distanze (2) | *Da vicino*: avvicinati finché il contorno azzurro raggiunge il **riquadro tratteggiato**. *Da lontano*: allontanati finché il contorno sta dentro il riquadro piccolo. | **DISTANZA** x/2 |

5. Quando compare **Calibrazione completa ✓**, ferma la registrazione.
6. La schermata **Review Calibration** riassume copertura, viste di sbieco e distanze; se la ripresa è incompleta conviene scartarla e rifarla. Con **Save Data** il video va in `calibrazione/` e l'app torna in modalità scansione; con **Discard** resta in modalità calibrazione.

Se il dispositivo si muove troppo in fretta compare **Più lento!**; se la scacchiera non viene vista per più di 1,5 s compare **Scacchiera persa**.
I parametri della guida (soglie di copertura, inclinazione, distanza, rilevamento) sono nell'oggetto `CalibConfig` in `CalibrationGuide.kt`.

## File salvati

Tutto viene salvato nella memoria interna, in `Download/WoodScanPro/`, con una sottocartella per ogni ripresa:

```
Download/WoodScanPro/
├── calibrazione/          video di calibrazione con i loro log
│   ├── calib_20261112_113000.mp4
│   ├── calib_20261112_113000_meta.json
│   └── ...
├── Scansione_01/          una cartella per ogni scansione salvata
│   ├── woodpile_scan_20261112_120000.mp4
│   ├── woodpile_scan_20261112_120000.json
│   └── ...
└── Scansione_02/
```

| Da dove | Percorso |
|---|---|
| App "File" del dispositivo | Memoria interna › Download › WoodScanPro |
| PC via cavo USB | Memoria condivisa interna › Download › WoodScanPro |
| adb | `/sdcard/Download/WoodScanPro/` |

Il numero `Scansione_NN` viene assegnato al momento del salvataggio: le riprese scartate non lasciano buchi nella numerazione.
Fino al salvataggio i file restano nella cache privata dell'app e vengono cancellati con **Discard**.

Tutti i file di una ripresa hanno lo stesso nome base: `woodpile_scan_<yyyyMMdd_HHmmss>` per le scansioni, `calib_<yyyyMMdd_HHmmss>` per la calibrazione.

| File | Contenuto |
|---|---|
| `<base>.mp4` | video (risoluzione massima disponibile tra 4K, Full HD, HD, SD) |
| `<base>.json` | telemetria di qualità, un campione ogni 100 ms (solo scansioni) |
| `<base>_imu.csv` | orientamento del dispositivo, ~100 Hz (solo se c'è il sensore) |
| `<base>_frames.csv` | istante di ogni frame della camera |
| `<base>_meta.json` | metadati di camera, sensori e video |

### `<base>.json` (telemetria)

`capturedAt` e un array `samples`; ogni campione contiene:

| Campo | Significato |
|---|---|
| `t_ms` | millisecondi dall'inizio della ripresa |
| `roll_deg`, `pitch_deg` | inclinazione laterale e avanti/indietro (gradi) |
| `rot_speed_dps` | velocità di rotazione (gradi/s) |
| `luma_mean` | luminosità media (0–255) |
| `over_exposed_pct`, `under_exposed_pct` | % di pixel bruciati (>245) e troppo scuri (<15) |
| `optical_flow` | stima del movimento tra due analisi (differenza media di luminosità) |
| `sharpness` | nitidezza stimata dal gradiente |
| `quality_status` | `ok`, `warn` o `err` |

### `<base>_imu.csv`

```
timestamp_ns,x,y,z,w
```

Quaternione del *game rotation vector* (senza bussola: l'angolo orizzontale parte da un riferimento arbitrario). Timestamp in nanosecondi sull'orologio `elapsedRealtime`. Se `w` è vuoto: `w = sqrt(1 - x² - y² - z²)`.

### `<base>_frames.csv`

```
sensor_timestamp_ns
```

Una riga per frame della camera.

### `<base>_meta.json`

| Campo | Significato |
|---|---|
| `camera.focal_lengths_mm` | focali disponibili |
| `camera.sensor_physical_size_mm` | dimensione fisica del sensore [larghezza, altezza] |
| `camera.timestamp_source` | `realtime` o `unknown` (vedi sotto) |
| `imu_sensor` | nome del sensore di orientamento, `null` se assente |
| `screen_rotation_deg` | rotazione dello schermo all'avvio |
| `clocks_at_video_start` | `elapsed_realtime_ns` e `system_nanotime_ns` all'avvio del video |
| `video` | `width`, `height`, `rotation_deg` del file finale |

## Sincronizzazione dei dati

- Se `timestamp_source` è `realtime`, `_imu.csv` e `_frames.csv` sono sullo stesso orologio e si possono allineare direttamente.
- Se è `unknown`, i timestamp dei frame vanno confrontati con `system_nanotime_ns`, quelli dell'IMU con `elapsed_realtime_ns`.
- I CSV iniziano poco prima del video e finiscono poco dopo: le righe in più si scartano con `clocks_at_video_start` (precisione di qualche decina di ms).
- La telemetria `<base>.json` usa un tempo relativo indipendente e non è adatta a un allineamento preciso con i frame.
- La matrice K ricavata da focale e dimensione del sensore è solo una stima (il video 16:9 ritaglia un sensore di solito 4:3). Per misure precise si usa la calibrazione (vedi sotto).

## Elaborazione sul PC

L'app produce solo video e dati grezzi; parametri della camera e file per lo stitcher si ricavano sul PC.

1. **Copia i file** dal dispositivo:
   ```bash
   adb pull /sdcard/Download/WoodScanPro/
   ```
2. **Calibrazione** (una volta per dispositivo): il notebook di calibrazione analizza `calibrazione/calib_….mp4` con `get_angoli_video` e `calibrateCameraExtended`, poi salva il risultato nel formato dello stitcher:
   ```python
   from woodscan_sidecar import salva_calibrazione
   salva_calibrazione("telefono.calibration.json", K, dist, size, rms=rms)
   ```
   `image_size` è la dimensione dei frame come li legge OpenCV (già ruotati), la stessa convenzione usata dallo stitcher.
3. **Orientamento** (per ogni scansione): crea `<base>.orientation.json` accanto al video, sincronizzando i log e convertendo l'orientamento negli assi della camera:
   ```bash
   python woodscan_sidecar.py orientamento WoodScanPro/Scansione_*/woodpile_scan_*.mp4
   ```
   La sincronizzazione è precisa a circa un frame; se serve si corregge con `--offset-ms`.
4. **Stitching**: lo stitcher trova da solo `orientation.json` accanto al video; la calibrazione si passa esplicitamente:
   ```bash
   python3 main.py WoodScanPro/Scansione_01/woodpile_scan_20261112_120000.mp4 \
     --calibration-json telefono.calibration.json --output output/catasta.png --crop
   ```

La calibrazione vale solo per i video registrati con questa app sullo stesso dispositivo: risoluzione e campo di vista dipendono dalla modalità video.

## Struttura del codice

| File | Ruolo |
|---|---|
| `MainActivity.kt` | interfaccia, analisi in tempo reale, ripresa, modalità calibrazione, revisione e salvataggio nelle cartelle |
| `CalibrationGuide.kt` | guida alla calibrazione: rilevamento della scacchiera senza librerie esterne, copertura, viste di sbieco, distanze, istruzioni |
| `CaptureLogger.kt` | registrazione di IMU, timestamp dei frame e metadati |
| `AndroidManifest.xml` | permessi e configurazione dell'activity |
| `woodscan_sidecar.py` | (PC) creazione di `calibration.json` e `orientation.json` per lo stitcher |

Le soglie di qualità sono nell'oggetto `ScanConfig` in `MainActivity.kt`, quelle della calibrazione in `CalibConfig` in `CalibrationGuide.kt`.

## Limitazioni note

- La copia del video al salvataggio avviene sul thread principale: con video lunghi l'interfaccia può bloccarsi per qualche secondo.
- Su Android 9 e precedenti, se il permesso di scrittura viene negato, il salvataggio fallisce.
- La risoluzione reale dipende dal dispositivo e dalla combinazione di flussi attivi (anteprima, analisi, video); il valore effettivo è in `_meta.json`.
- La stabilizzazione video è quella predefinita del dispositivo: va disattivata esplicitamente, con la stessa impostazione per calibrazione e scansioni.
- Dalla 1.0.3 anteprima e analisi sono in 16:9: la metrica di nitidezza può differire dalle versioni precedenti, da verificare la soglia `MIN_SHARPNESS`.
- Il rilevamento della scacchiera nell'app è preciso al pixel e serve solo per la guida; la stima dei parametri si fa sul PC. Su dispositivi lenti si può alzare `DETECT_SIDE` in `CalibConfig`.
- Su Android 10+ l'app vede solo i file creati da lei: dopo una reinstallazione la numerazione `Scansione_NN` può ripartire da 01.
- La conversione dell'orientamento per lo stitcher è verificata con il dispositivo in verticale; per riprese in orizzontale va controllata ruotando il dispositivo sul posto.