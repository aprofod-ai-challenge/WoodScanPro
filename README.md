# WoodScan Pro

Applicazione Android per riprendere in video una catasta di legna in modo adatto alla fotogrammetria e all'analisi con computer vision.
Durante la ripresa guida l'operatore (orizzonte, inclinazione, esposizione, velocità, nitidezza) e salva, insieme al video, i dati dei sensori e i metadati della camera.

Versione attuale: **1.0.2**. Le modifiche sono elencate in [CHANGELOG.md](CHANGELOG.md).

---

## Requisiti

- Android 8.0 o superiore (testato su tablet Android 8.1).
- Fotocamera posteriore.
- Consigliato: giroscopio. Senza giroscopio il file `_imu.csv` non viene creato, ma video, frame e metadati vengono salvati comunque.

## Permessi

| Permesso | Quando | Perché |
|---|---|---|
| `CAMERA` | sempre | ripresa video |
| `WRITE_EXTERNAL_STORAGE` | solo Android 9 e precedenti | salvataggio in `Movies/` e `Download/` |

I permessi vengono chiesti al primo avvio.

## Come si usa

1. Mettiti di fronte alla catasta e inquadrala tra le due **linee gialle tratteggiate** (sopra la cima, sotto la base o il terreno).
2. Tieni il dispositivo verticale finché gli indicatori **HORIZON**, **PITCH**, **EXPOSURE** e **PACE/BLUR** sono verdi.
3. Premi **Lock AE/AF** per bloccare esposizione, messa a fuoco e bilanciamento del bianco.
4. Premi il pulsante rosso e cammina lentamente, parallelo alla catasta.
5. Premi di nuovo per fermare. Nella schermata **Review Scan** il video si ripete in loop e viene mostrato il **Quality Score** (percentuale di campioni senza errori).
6. Premi **Save Data** per salvare oppure **Discard** per scartare.

Il pulsante **Test Sim** attiva dei cursori per simulare inclinazione e movimento senza camera reale.

## File salvati

Tutti i file di una ripresa hanno lo stesso nome base: `woodpile_scan_<yyyyMMdd_HHmmss>`.

| File | Cartella | Contenuto |
|---|---|---|
| `<base>.mp4` | `Movies/WoodScanPro/` | video (risoluzione massima disponibile tra 4K, Full HD, HD, SD) |
| `<base>.json` | `Download/WoodScanPro/` | telemetria di qualità, un campione ogni 100 ms |
| `<base>_imu.csv` | `Download/WoodScanPro/` | orientamento del dispositivo, ~100 Hz (solo se c'è il sensore) |
| `<base>_frames.csv` | `Download/WoodScanPro/` | istante di ogni frame della camera |
| `<base>_meta.json` | `Download/WoodScanPro/` | metadati di camera, sensori e video |

Fino al salvataggio i file restano nella cache privata dell'app e vengono cancellati con **Discard**.

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
- La matrice K ricavata da focale e dimensione del sensore è una stima (il video 16:9 ritaglia un sensore di solito 4:3). Per misure precise serve una calibrazione.

## Struttura del codice

| File | Ruolo |
|---|---|
| `MainActivity.kt` | interfaccia, analisi in tempo reale, ripresa, revisione e salvataggio |
| `CaptureLogger.kt` | registrazione di IMU, timestamp dei frame e metadati |
| `AndroidManifest.xml` | permessi e configurazione dell'activity |

Le soglie di qualità sono nell'oggetto `ScanConfig` in `MainActivity.kt`.

## Limitazioni note

- La copia del video al salvataggio avviene sul thread principale: con video lunghi l'interfaccia può bloccarsi per qualche secondo.
- Su Android 9 e precedenti, se il permesso di scrittura viene negato, il salvataggio fallisce.
- La risoluzione reale dipende dal dispositivo e dalla combinazione di flussi attivi (anteprima, analisi, video); il valore effettivo è in `_meta.json`.