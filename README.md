# WoodScan Pro

Applicazione Android per riprendere in video una catasta di legna in modo adatto alla fotogrammetria e all'analisi con computer vision.
Durante la ripresa guida l'operatore (orizzonte, inclinazione, esposizione, velocità, nitidezza) e salva, insieme al video, l'orientamento della camera.
Include una modalità di calibrazione che guida la ripresa di una scacchiera, da cui si stimano i parametri della camera.

Versione attuale: **1.0.4**. Le modifiche sono elencate in [CHANGELOG.md](CHANGELOG.md).

---

## Requisiti

- Android 8.0 o superiore (testato su tablet Android 8.1).
- Fotocamera posteriore.
- Consigliato: giroscopio. Se manca il sensore di orientamento viene salvato solo il video.
- Per la calibrazione: scacchiera di 10×7 quadrati da 25 mm (9×6 angoli interni), generata dal notebook di calibrazione (`scacchiera_A4.pdf`) e stampata al 100%.

## Permessi

| Permesso | Quando | Perché |
|---|---|---|
| `CAMERA` | sempre | ripresa video |
| `INTERNET`, `ACCESS_NETWORK_STATE` | sempre | verifica della connessione e caricamento |

Il permesso della fotocamera viene chiesto al primo avvio; gli altri sono concessi dal sistema.

## Come si usa

### Scansione della catasta

1. Mettiti di fronte alla catasta e inquadrala tra le due **linee gialle tratteggiate** (sopra la cima, sotto la base o il terreno).
2. Tieni il dispositivo verticale finché gli indicatori **HORIZON**, **PITCH**, **EXPOSURE** e **PACE/BLUR** sono verdi.
3. Premi **Lock AE/AF** per bloccare esposizione, messa a fuoco e bilanciamento del bianco.
4. Premi il pulsante rosso e cammina lentamente, parallelo alla catasta.
5. Premi di nuovo per fermare. Nella schermata **Review Scan** il video si ripete in loop e viene mostrato il **Quality Score** (percentuale di campioni senza errori).
6. Premi **Salva** e inserisci il **codice della scansione**, oppure **Scarta**. Se c'è connessione la scansione viene caricata subito, altrimenti si carica in seguito da **Scansioni**.

Il pulsante **Test Sim** attiva dei cursori per simulare inclinazione e movimento senza camera reale.

### Calibrazione della camera

Fortemente consigliata, non obbligatoria. Va fatta una volta per dispositivo, con le stesse impostazioni delle scansioni: stessa app, stessa risoluzione, dispositivo in verticale.

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
6. La schermata **Review Calibration** riassume copertura, viste di sbieco e distanze; se la ripresa è incompleta conviene scartarla e rifarla. Con **Salva** viene calcolato `calibration.json`, il video viene cancellato e l'app torna in modalità scansione; con **Discard** resta in modalità calibrazione.

Se il dispositivo si muove troppo in fretta compare **Più lento!**; se la scacchiera non viene vista per più di 1,5 s compare **Scacchiera persa**.
I parametri della guida (soglie di copertura, inclinazione, distanza, rilevamento) sono nell'oggetto `CalibConfig` in `CalibrationGuide.kt`.

## File salvati

Tutto resta nella memoria dell'app, accessibile da PC con il cavo USB:

```
Android/data/com.example.woodscanpro/files/
├── calibration.json               calibrazione attuale del dispositivo
├── <codice>.tar                   una per scansione
└── ...
```

Contenuto di `<codice>.tar` (senza cartelle né compressione):

```
<codice>.mp4
<codice>.orientation.json          (solo se c'è il sensore e il video è verticale)
<codice>-calibration.json          (solo se il dispositivo era calibrato al salvataggio)
```

- Il tar si crea al salvataggio: una calibrazione successiva vale solo per le scansioni nuove.
- Il codice può contenere lettere (senza accenti), cifre, `-` e `_` (massimo 64 caratteri). Un codice già usato sul dispositivo viene rifiutato, senza distinguere maiuscole e minuscole, anche se la copia locale è stata eliminata.
- **Disinstallare l'app cancella tutto**, comprese le scansioni non ancora caricate.
- I file sono esclusi dal backup di Android: la calibrazione vale solo per il dispositivo su cui è stata fatta.
- Fino al salvataggio i file restano nella cache privata dell'app.

## Caricamento

- Dopo il salvataggio, se c'è connessione, il tar viene caricato; altrimenti resta **Da caricare**.
- La schermata **Scansioni** permette di caricare una o tutte le scansioni e di eliminare le copie locali già caricate. Il caricamento non riparte da solo quando torna la connessione.
- Calibrazione e orientamento sono fortemente consigliati ma non obbligatori: il tar si crea e si carica anche senza.
- Il database contiene solo i file `.tar`. Se il codice è già presente (per esempio da un altro dispositivo) il caricamento viene rifiutato, nulla viene sovrascritto e la scansione si può **rinominare**: il tar viene ricreato con il nuovo codice e ricaricato.
- Il database **è simulato**: `MockScanDatabase` copia i tar in `Android/data/com.example.woodscanpro/files/mock_database/`.
- Per collegare il database vero: implementare `ScanDatabase` con un nuovo `id` e usarlo in `ScanRepository.get`. Ciò che era caricato solo sul simulato, se è ancora sul dispositivo, torna **Da caricare**.

## Struttura del codice

| File | Ruolo |
|---|---|
| `MainActivity.kt` | interfaccia, analisi in tempo reale, ripresa, modalità calibrazione, revisione |
| `CalibrationGuide.kt` | guida alla calibrazione: rilevamento della scacchiera senza librerie esterne, copertura, viste di sbieco, distanze, istruzioni |
| `CaptureLogger.kt` | registrazione di IMU, timestamp dei frame e metadati |
| `AndroidManifest.xml` | permessi e configurazione dell'activity |
| `CalibrationExport.kt`, `OrientationExport.kt` | calcolo di `calibration.json` e dell'orientamento |
| `ScanNaming.kt` | regole del codice della scansione |
| `ScanTar.kt` | scrittura e rinomina di `<codice>.tar` |
| `ScanDatabase.kt` | interfaccia del database e versione simulata |
| `ScanRepository.kt` | indice delle scansioni, salvataggio, coda di caricamento |
| `ScanScreens.kt` | richiesta del codice e schermata Scansioni |

Le soglie di qualità sono nell'oggetto `ScanConfig` in `MainActivity.kt`, quelle della calibrazione in `CalibConfig` in `CalibrationGuide.kt`.

## Limitazioni note

- Salvataggio e rinomina richiedono temporaneamente spazio per una seconda copia del video. Ogni file nel tar può arrivare a 8 GB.
- La risoluzione reale dipende dal dispositivo e dalla combinazione di flussi attivi (anteprima, analisi, video).
- La stabilizzazione video è quella predefinita del dispositivo: va disattivata esplicitamente, con la stessa impostazione per calibrazione e scansioni.
- Dalla 1.0.3 anteprima e analisi sono in 16:9: la metrica di nitidezza può differire dalle versioni precedenti, da verificare la soglia `MIN_SHARPNESS`.
- Il rilevamento della scacchiera nell'app è preciso al pixel e serve solo per la guida; la stima dei parametri si fa dopo la ripresa. Su dispositivi lenti si può alzare `DETECT_SIDE` in `CalibConfig`.
- La conversione dell'orientamento per lo stitcher è verificata con il dispositivo in verticale; per riprese in orizzontale va controllata ruotando il dispositivo sul posto.