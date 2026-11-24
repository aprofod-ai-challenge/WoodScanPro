# Change Log


Tags:
- New    -- ... ;
- Fix    -- ... ;
- HotFix -- ... ;
- Change -- ... ;

----------------------------------------------------------------------------------------------------

# v1.0.4 (2026-11-24)

- New    -- L'operatore inserisce il codice della scansione, che dà il nome al tar e ai file che contiene;
- New    -- Ogni scansione è un <codice>.tar con video, orientamento e calibrazione (gli ultimi due se disponibili);
- New    -- Coda di caricamento: subito se c'è connessione, altrimenti dalla schermata Scansioni (database simulato);
- New    -- Codice già presente nel database: caricamento rifiutato e scansione rinominabile;
- New    -- Schermata Scansioni: stato, caricamento ed eliminazione delle scansioni caricate;
- Change -- File nella memoria dell'app invece che in Download, esclusi dal backup; permesso di scrittura rimosso;
- Change -- Della calibrazione si conserva solo calibration.json;
- Fix    -- La copia del video al salvataggio non blocca più l'interfaccia;
----------------------------------------------------------------------------------------------------

# v1.0.3 (2026-11-12)

- New    -- Modalità calibrazione (pulsante 📐 Calibrazione) per registrare il video della scacchiera;
- New    -- Guida alla calibrazione in tre fasi: copertura, viste di sbieco, distanze;
- New    -- Rilevamento della scacchiera in tempo reale senza librerie esterne (CalibrationGuide.kt);
- New    -- Overlay di calibrazione: griglia di copertura, lato da avvicinare, riquadro della distanza, barre di avanzamento;
- New    -- Riepilogo della calibrazione nella schermata di revisione;
- Change -- Anteprima e analisi in 16:9 come il video; in calibrazione l'anteprima mostra l'intero fotogramma;
- Change -- Tutti i file in Download/WoodScanPro, nelle sottocartelle calibrazione/ e Scansione_NN/;
- Change -- Per i video di calibrazione non vengono salvati telemetria di qualità e Quality Score;
----------------------------------------------------------------------------------------------------

# v1.0.2 (2026-11-11)

- New    -- Aggiunto CaptureLogger per registrare orientamento, timestamps e metadati;
- New    -- Video esportati in Movies/WoodScanPro e logger files in Download/WoodScanPro;
----------------------------------------------------------------------------------------------------

# v1.0.1 (2026-11-10)

- Change -- Linee guida più visibili;
- Change -- Qualità video;
- Fix    -- Riproduzione finale video;
----------------------------------------------------------------------------------------------------

# v1.0.0 (2026-11-10)

- New    -- Prima versione;
----------------------------------------------------------------------------------------------------