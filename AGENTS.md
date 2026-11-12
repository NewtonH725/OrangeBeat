# Orange-Beat — Regole Architetturali & Agente

## Stack & Vincoli Critici
- **100% On-Device & Offline**: App Android nativa in Kotlin 2.2, Jetpack Compose M3, Media3 Transformer, ML Kit Pose Detection e OpenGL ES 2.0 (`app/src/main/java/com/example/crossreel/`). Divieto assoluto di introdurre API cloud esterne, backend remoti o chiavi API.
- **Prevenzione OOM su Video 4K**: Non decodificare mai frame a piena risoluzione per l'analisi. Usa tassativamente `retriever.getScaledFrameAtTime(timeUs, OPTION_CLOSEST, width, height)` (64x64 per SAD Motion, 480x270 per ML Kit Pose).
- **Zero Allocazioni in Compose DrawScope**: Non istanziare mai `Paint`, `Path`, `Shader` o matrici dentro i blocchi `DrawScope` a 60/120 Hz; pre-allocali sempre all'esterno con `remember`.
- **Concorrenza Coroutines**: Usa `Dispatchers.Default` per calcolo pesante (ML Kit, SAD, RMS audio, regia), `Dispatchers.IO` per filesystem/MediaMetadataRetriever e `Dispatchers.Main` per lo stato UI. Usa `Enum.entries` invece di `Enum.values()`.
- **Integrità Media3 & Scoped Storage**:
  - Calcola sempre il tempo relativo della clip negli shader/effetti (`presentationTimeUs - firstPtsUs`).
  - Supporta esplicitamente `C.ENCODING_PCM_16BIT` in `onConfigure` nei custom `AudioProcessor`.
  - Esegui sempre `outputFile.parentFile?.mkdirs()` prima dell'export con `Transformer`.
- **Versioning Semantico Ufficiale (`vX.Y.Z`)**:
  - La versione dell'app è definita unicamente in `app/build.gradle.kts` tramite `versionMajor` (`X`), `versionMinor` (`Y`), `versionPatch` (`Z`), con `versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch` e `versionName = "v$versionMajor.$versionMinor.$versionPatch"` (es. `v1.0.0`, `v1.2.7`), ed esposta nella UI tramite `BuildConfig.VERSION_NAME`.
  - **Primo numero (`X` - Nuove Funzionalità)**: incrementa (`v(X+1).0.0`, azzerando `Y` e `Z`) esclusivamente quando viene introdotta una **nuova funzionalità** nell'app.
  - **Secondo numero (`Y` - Upgrade Sostanziosi)**: incrementa (`vX.(Y+1).0`, azzerando `Z`) nel caso di **upgrade sostanziosi e miglioramenti importanti** a funzionalità o architetture già esistenti.
  - **Terzo numero (`Z` - Bug Fix & Upgrade Minori)**: incrementa (`vX.Y.(Z+1)`) solo per **bug fix, correzioni e upgrade minori**.

---

# Workflow Task Notion
- **Database Notion di riferimento**: "Database" (dentro la pagina "Antigravity Dev Hub")
- **Database ID**: `403d88999137807f8554c067ad93846f` (Page ID contenitore: `403d8899913780f2ab76c7df534535ab`)
- **Tag Progetto associato**: `Orange-Beat`

## Procedura di esecuzione ("Procedi con Notion")
Quando ti scrivo in chat "Procedi con Notion", segui rigorosamente questi passaggi usando gli strumenti del server MCP di Notion:
1. Esegui una query sul database `403d88999137807f8554c067ad93846f` filtrando solo le righe dove la proprietà **Progetto** è uguale a `Orange-Beat` e la proprietà **Stato** è uguale a `Pronto per Antigravity`.
2. Per ciascuna riga trovata (procedendo una alla volta):
   - Leggi il contenuto testuale della colonna **Prompt Tecnico**.
   - Tratta quel testo come il tuo comando operativo ed esegui tutte le modifiche richieste nel codice, rispettando tutte le regole architetturali presenti in questo file `AGENTS.md`.
   - Verifica che non ci siano errori di compilazione o di sintassi.
   - Usa lo strumento MCP di Notion per aggiornare la pagina della riga appena eseguita, cambiando la proprietà **Stato** in `Completato`.
3. Passa al task successivo finché la coda `Pronto per Antigravity` di questo progetto non è vuota, poi fammi un riepilogo finale in chat.
