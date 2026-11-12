# OrangeBeat - Developer Handoff Guide

## Panoramica dell'Architettura
OrangeBeat è un'applicazione Android nativa (scritta in Kotlin) progettata per eseguire un **montaggio video automatizzato e ritmico**, completamente in locale sul dispositivo. 
È costruita per processare filmati d'azione (CrossFit, sport estremi) e tagliarli perfettamente a tempo di musica utilizzando l'ecosistema **Media3 (Transformer)**, con euristiche avanzate di intelligenza artificiale per l'analisi del movimento (**ML Kit**).

Non ci sono chiamate Cloud o API per il montaggio: il rendering e l'inferenza sono totalmente off-grid.

## Struttura e Classi Core

### 1. Interfaccia e Punto d'Ingresso (`MainActivity.kt`)
- Sviluppata in **Jetpack Compose**.
- Gestisce i permessi, l'accesso alla galleria e la selezione delle configurazioni (Colore, Effetti, Genere Musicale, Durata).
- **Filtro Qualità:** Il `ActivityResultContracts.GetMultipleContents` è wrappato in una Coroutine che analizza i metadata pre-selezione, scartando i video inferiori a 3.0 secondi per garantire che l'AiDirector abbia "abbastanza fotogrammi" su cui applicare lo speed-ramping.
- Mantiene lo schermo acceso (`view.keepScreenOn`) durante il processo di estrazione e rendering.

### 2. UI Personalizzata (`MixerKnob.kt`)
- Ospita i componenti grafici complessi.
- **Duration Slider:** Un fader orizzontale in stile DJ mixer disegnato su `Canvas`. Utilizza la logica di misurazione del drag (pointerInput) per far scorrere la levetta e aggiornare proporzionalmente il tempo (da 15s a 60s).
- Include l'implementazione del VU meter con retroilluminazione analogica ambrata.

### 3. Analisi Video (Machine Learning locale)
- **`MotionAnalyzer.kt`**: Converte un subset di fotogrammi a risoluzione infinitesimale e ne calcola la *Sum of Absolute Differences (SAD)*. Assegna un punteggio di "energia" alla clip, permettendo all'AiDirector di destinare i video più concitati alle fasi salienti della canzone.
- **`PoseActionFinder.kt`**: Usa Google ML Kit (Pose Detection) per tracciare le variazioni dell'asse Y di spalle/bacino, individuando in automatico il millisecondo esatto in cui avviene uno sforzo esplosivo (un salto, un sollevamento). Produce i `peakMs` (i picchi d'azione).

### 4. Analisi Audio e Ritmo
- **`AudioBeatDetector.kt`**: Sistema di smistamento. Classifica i file raw musicali in `SHORT`, `MEDIUM` e `LONG` in base alla durata richiesta dall'utente, evitando di abbinare una clip finale da 15 secondi a un'epopea rock di 5 minuti.
- **`RealBeatDetector.kt`**: Il motore di rilevamento PCM. Usa `MediaExtractor` e `MediaCodec` per leggere l'audio grezzo della risorsa selezionata e processa l'RMS. I timestamp che superano la media mobile vengono flaggati come "battiti" (beats). Contiene logiche anti-timeout: arresta la decodifica anticipatamente quando raggiunge la durata target e applica un *matematical padding* se rileva lunghi breakdown silenziosi per evitare desincronizzazioni a valle.

### 5. Il Cervello: La Regia (`AiDirector.kt`)
- Genera un file **EDL (Edit Decision List) in formato JSON**.
- Non modifica fisicamente i file, decide solo *cosa* tagliare.
- Usa i beat estratti per stabilire la durata di ogni taglio. Allinea le clip con alto "motionScore" ai "Drop" musicali.
- **Speed Ramping:** Nelle sezioni DROP e CLIMAX (con probabilità 75%), divide un singolo taglio in 3 micro-tagli (2.0x, 0.4x, 2.0x), centrando il rallenty esattamente sul `peakMs` fornito dalla ML (es. il momento del salto).
- L'orologio interno (ciclo while) scala rigorosamente in base ai millisecondi della timeline video effettiva, azzerando le differenze per clip troppo corte e adattandosi al beat successivo.

### 6. Il Motore di Rendering (`VideoEditor.kt`)
- Basato su `androidx.media3.transformer`.
- Prende la lista JSON dell'AiDirector e costruisce una sequenza di `EditedMediaItem`.
- Applica in tempo reale il `SpeedChangeEffect` per i rallenty, rimuovendo l'audio nativo sulle clip alterate per impedire il freeze-frame.
- Applica i filtri colore (`GlEffect`/LUT) o VFX generati dal file.
- Unisce la sequenza video a un `MediaItem` audio a volume boostato (250%).
- Esporta tutto in un singolo file MP4 nella cartella DCIM (tramite `MediaStore`).

## Stack Tecnologico e Dipendenze
- **Lingua:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Video Processing:** Media3 (ExoPlayer Transformer, Common, Effect)
- **Computer Vision:** com.google.mlkit:pose-detection
- **Coroutines:** Kotlinx-coroutines-android
- **SDK Target:** Android 34 / Min 26
