# Orange-Beat

## 1. Identità del Progetto
* **Tag Notion:** `Orange-Beat`
* **Descrizione:** Applicazione Android nativa per il montaggio video automatizzato di Reel dinamici/CrossFit sincronizzati al ritmo musicale. Analizza le clip in locale tramite computer vision e machine learning (energia cinetica e rilevamento pose), integra un motore audio Cloud-First con database su GitHub, caching su disco e catalogo a 4 generi musicali autentici (Rock, Metal, Techno, Dubstep) con 16 basi offline Drop-Hunter, ed esporta video sia in formato Verticale (Portrait 9:16 - 1080×1920) sia Orizzontale (Landscape 16:9 - 1920×1080) con color grading cinematografico, effetti VFX ed esposizione real-time delle specifiche di output.
* **Protocollo di Versionamento (`vX.Y.Z`):** Versione corrente `v2.2.0` (`versionCode = 20200`), gestita in `app/build.gradle.kts` (`X` = nuove funzionalità, `Y` = upgrade sostanziosi, `Z` = bug fix e upgrade minori).

## 2. Stack Tecnologico & Librerie Chiave
* **Linguaggio:** Kotlin 2.2.10 (JVM Target: Java 17)
* **Framework Principale:** Android SDK nativo (`minSdk 35`, `targetSdk 37`, `compileSdk 37`, AGP 9.4.1)
* **UI Toolkit:** Jetpack Compose (Compose BOM 2026.02.01, Material 3 1.2.x, Activity Compose 1.8.0)
* **Gestione dello Stato:** Compose State (`remember`, `mutableStateOf`), State Hoisting unidirezionale
* **Motore Video & Pipeline Montaggio:** AndroidX Media3 1.4.1 (`media3-transformer`, `media3-effect`, `media3-common`)
* **Computer Vision & Pose Detection On-Device:** Google ML Kit Pose Detection 17.0.0 (`com.google.mlkit:pose-detection`, modello base single-image) + Android `MediaMetadataRetriever`
* **Decodifica Audio & Beat Detection:** Android Low-Level Media API (`MediaExtractor`, `MediaCodec` per decodifica raw PCM 16-bit a 44.1kHz)
* **Grafica & Shaders:** OpenGL ES 2.0 (Custom GLSL fragment/vertex shaders, Media3 `GlEffect` & `RgbMatrix`)
* **Icone & Grafica:** Android Vector Drawables / Painter Resources (`R.drawable.*`)
* **Concorrenza & Asincronismo:** Kotlin Coroutines 1.8+ (`Dispatchers.Default`, `Dispatchers.IO`, `suspendCancellableCoroutine`, `async`/`awaitAll`)

## 3. Albero della Struttura (Max 2-3 livelli)
* `app/`: Modulo principale dell'applicazione Android.
  * `src/main/AndroidManifest.xml`: Entry-point dichiarativo con permessi (`READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`), single activity e `FileProvider`.
  * `src/main/java/com/example/crossreel/`: Sorgenti logica applicativa, UI ed engine multimediale.
    * `MainActivity.kt`: Entry-point UI dell'app (Single Activity Compose, gestione selezione clip, parametri montaggio e avvio export).
    * `AiDirector.kt`: Motore di regia deterministico locale (suddivisione timeline in sezioni emotive INTRO/BUILDUP/DROP/BREAKDOWN/CLIMAX e calcolo tagli a tempo di musica).
    * `VideoEditor.kt`: Pipeline Media3 Transformer (concatenazione clip, audio mixing, cropping 9:16 a 1080x1920 ed esportazione MP4).
    * `RealBeatDetector.kt`: Decodifica audio low-level (`MediaCodec`) e rilevamento percussioni con algoritmo RMS a finestre mobili (50ms).
    * `AudioBeatDetector.kt`: Gestione libreria musicale offline con partizionamento a 4 generi autentici (Rock, Metal, Techno, Dubstep, 4 tracce per genere per un totale di 16 tracce Drop-Hunter) con rotazione equa anti-ripetizione.
    * `MotionAnalyzer.kt`: Analisi del movimento frame-by-frame via SAD (Sum of Absolute Differences) a 64x64 pixel.
    * `PoseActionFinder.kt`: Tracciamento scheletrico ML Kit su spalle e anche per isolare i picchi di salto ed estensione.
    * `ColorPreset.kt`: Definizioni delle matrici 4x4 `RgbMatrix` per i preset di color grading (Teal & Orange, Kodak Super 8, Ilford PAN F, ecc.).
    * `KenBurnsEffect.kt`: Trasformazione matriciale Media3 per zoom lento (1.0x -> 1.15x) nelle sezioni musicali calme.
    * `VfxEffect.kt` / `VfxPreset.kt`: Effetti video tramite shader custom GLSL (`old_film_shader.glsl`, `tv_glitch_shader.glsl`) con `BaseGlShaderProgram`.
    * `VolumeAudioProcessor.kt`: `AudioProcessor` Media3 custom a 16-bit PCM per controllo gain e fade-out finale.
    * `MixerKnob.kt`: Componente UI personalizzato in stile hardware analogico (manopola rotativa e ago VU-meter reattivo).
    * `ui/theme/`: Palette colori brand CrossFit (`Color.kt`), tipografia (`Type.kt`) e configurazione tema scuro (`Theme.kt`).
  * `src/main/assets/`: File shader OpenGL GLSL (`vertex_shader.glsl`, `old_film_shader.glsl`, `tv_glitch_shader.glsl`).
  * `src/main/res/`: Risorse native (cartella `raw/` con 16 basi musicali autentiche Drop-Hunter a 60s su 4 generi, icone vettoriali `drawable/`, configurazioni `xml/`).
* `gradle/libs.versions.toml`: Version Catalog centralizzato per dipendenze e plugin Gradle.
* `build.gradle.kts` & `app/build.gradle.kts`: Script di build del progetto e del modulo application.

## 4. Backend, Database & Servizi Esterni
* **Backend & Cloud:** Nessuno. Architettura 100% on-device, senza server dedicati, senza credenziali cloud e senza costi di computazione remota.
* **Database & Persistenza:** Nessun database relazionale o documentale locale. I dati di selezione e montaggio risiedono nella memoria di stato Compose per la sola durata della sessione.
* **Storage Locale & File System:**
  * Lettura input: Accesso alle clip della galleria tramite Android Photo Picker / Storage Access Framework via `Uri`.
  * Scrittura output: File video finale generato in formato MP4 nella directory pubblica `Environment.DIRECTORY_MOVIES` (`OrangeBeat_Final_[timestamp].mp4`).
  * Indicizzazione: Registrazione automatica nella galleria di sistema tramite `MediaScannerConnection.scanFile`.
  * Condivisione sicura: `FileProvider` Android (`${applicationId}.fileprovider`) configurato con mapping in `res/xml/file_paths.xml`.
* **API Esterne:** Nessuna chiamata di rete esterna o API REST (le API cloud di Gemini sono state rimosse e sostituite interamente dall'engine algoritmico locale).

## 5. Convenzioni di Codice & Pattern Architetturali
* **Architettura Applicativa:** Single-Activity Architecture basata su Jetpack Compose con pattern procedurale reattivo a passaggi sequenziali (Estrazione Metadati -> Analisi Audio/Beats -> Analisi Video/Motion/Pose -> Regia/EDL -> Montaggio Media3).
* **Gestione della Concorrenza:** Uso rigoroso di Kotlin Coroutines con segregazione dei thread:
  * `Dispatchers.Default`: Computazioni matematiche pesanti (ML Kit Pose, SAD Motion, decodifica audio RMS, regia).
  * `Dispatchers.IO`: Lettura e scrittura filesystem, estrazione header con `MediaMetadataRetriever`.
  * `Dispatchers.Main`: Emissione degli stati UI e aggiornamento dei testi di avanzamento montaggio.
* **Pattern per Callback Asincroni:** Incapsulamento delle API asincrone a listener (Media3 `Transformer.Listener`, ML Kit `Task`) tramite `suspendCancellableCoroutine` con gestione della cancellazione.
* **Ottimizzazione UI & Compose:**
  * State Hoisting per i componenti di controllo (`MixerKnobWithVuMeter`, `LazyRow` per preset).
  * Zero allocazioni di oggetti grafici (`Paint`, `Typeface`) all'interno del DrawScope del Canvas a 60/120 Hz: istanziazione esterna memorizzata tramite `remember(density)`.
* **Naming Conventions:**
  * Classi, Composable e Interfacce: PascalCase (es. `VideoEditor`, `RealBeatDetector`, `MixerKnobWithVuMeter`).
  * Funzioni, variabili e parametri: camelCase (es. `executeEditPlan`, `actionPeaks`, `isHighEnergy`).
  * Enum e costanti globali: UPPER_SNAKE_CASE (es. `TEAL_ORANGE`, `DROP`, `BUILDUP`).
  * Proprietà Enum moderne: Uso esclusivo di `Enum.entries` invece del deprecato e allocativo `Enum.values()`.

## 6. Note Critiche per i Prompt
* **Divieto Assoluto di API Cloud per Editing/AI:** Non introdurre dipendenze da servizi remoti, backend server o API esterne con chiavi (es. Gemini API, cloud rendering). Tutto il processo decisionale e il video editing devono rimanere al 100% offline e on-device.
* **Prevenzione OOM su Video 4K (Allocazione RAM):** Non decodificare mai frame video a piena risoluzione per l'analisi. Utilizzare tassativamente `retriever.getScaledFrameAtTime(timeUs, OPTION_CLOSEST, width, height)` (es. 64x64 per il calcolo motion, 480x270 per la posa scheletrica) per evitare saturazione della memoria heap nativa.
* **Zero Allocazioni nei Canvas DrawScope:** Non creare mai istanze di `Paint`, `Path`, `Shader` o matrici all'interno di lambda `DrawScope` o chiamate a 60/120 fps. Pre-allocarli sempre all'esterno con `remember`.
* **Vincoli Toolchain & Piattaforma:** `minSdk = 35`, `targetSdk = 37`, compatibilità bytecode Java 17. Non reintrodurre chiamate deprecate (es. `window.statusBarColor`), rispettando la gestione edge-to-edge nativa (`enableEdgeToEdge`).
* **Integrità Media3 Transformer:**
  * I timestamp passati agli effetti matriciali in una sequenza possono essere cumulativi: calcolare sempre il tempo relativo alla clip (`presentationTimeUs - firstPtsUs`).
  * Nei custom `AudioProcessor`, supportare esplicitamente la codifica `C.ENCODING_PCM_16BIT` nel metodo `onConfigure`, altrimenti il processore viene silenziato o ignorato da Media3.
* **Safety File System su Android Scoped Storage:** Eseguire sempre `outputFile.parentFile?.mkdirs()` prima di avviare l'esportazione con `Transformer` per prevenire eccezioni del Muxer dovute a directory di sistema non ancora inizializzate.
* **Segregazione Rigorosa Catalogo Audio (Zero Contaminazione):** Le tracce in `res/raw/` devono rispettare una corrispondenza univoca ed esclusiva con il rispettivo genere musicale (`TECHNO_POOL` 8 tracce elettroniche/club, `ROCK_POOL` 4 tracce rock con chitarre acustiche/elettriche, `METAL_POOL` 4 tracce industrial/heavy metal, `MOTIVATIONAL_POOL` 1 traccia synthwave ad alta carica). Mai inserire tracce riempitive o spurie per gonfiare il catalogo. Se un genere non dispone di tracce offline locali (es. Dubstep), documentarlo esplicitamente e gestire il fallback in modo trasparente e protetto.
