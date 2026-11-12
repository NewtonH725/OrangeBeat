# Registro dei Ragionamenti e Soluzioni (Chat History)

Questo documento traccia l'evoluzione logica, le intuizioni e la risoluzione dei bug più complessi affrontati durante lo sviluppo dell'app OrangeBeat (precedentemente "CrossReel" / "Montatore"). 
È pensato per fornire contesto a un'intelligenza artificiale o a uno sviluppatore che debba riprendere in mano il progetto.

## 1. Architettura della UI e Design
- **Slider Analogico (MixerKnob.kt):** L'utente desiderava un'interfaccia "hardware vintage", simile a un mixer da DJ per selezionare la durata. Abbiamo implementato un Canvas personalizzato con slider orizzontale. Per la retroilluminazione (stile VU meter), i classici gradienti risultavano in uno "spotlight" troppo netto. Soluzione: abbiamo esteso enormemente il `radius` (1200f) del `Brush.radialGradient` usando tonalità calde (Ambra/Arancio) per simulare un pannello diffusore reale.
- **Icona e Splash Screen:** Adattata per usare sfondi puramente neri (#000000) e un'illuminazione "soft orange" della statuetta, per far risaltare il branding.

## 2. Rilevamento Audio e Categorizzazione
- **Classificazione per Durata:** Per evitare che video brevi di 15s usassero brani lenti da 3 minuti (o viceversa), abbiamo implementato un sistema a "Bucket" in `AudioBeatDetector.kt`. I brani importati (Dubstep, Techno, Rock, Motivational) vengono raggruppati in SHORT (<=20s), MEDIUM (<=45s) e LONG (>45s).
- **Estrazione PCM (RealBeatDetector.kt):** Estraiamo matematicamente i colpi di cassa analizzando l'energia delle frequenze su finestre di 50ms. 

## 3. La Caccia al Bug dei "Freeze Frame" (Blocchi dell'immagine)
La sfida tecnica più grande è stata eliminare i frame congelati a fine video. Abbiamo scoperto che il problema non aveva una sola causa, ma tre concause distinte generate dall'inclusione degli Speed Ramps (rallenty):

1. **Desincronizzazione Audio/Video in Media3:** Quando applicavamo uno `SpeedChangeEffect` (es. velocità 2.0x) alla traccia video, l'audio nativo di sottofondo restava a velocità normale. Il motore Media3, per mantenere la sincronia di una clip ibrida, "congelava" l'ultimo fotogramma video accelerato in attesa che terminasse l'audio lungo.
   *Soluzione:* Inserito `setRemoveAudio(true)` per rimuovere l'audio sorgente esclusivamente dalle clip che subiscono variazioni di velocità.
2. **Timeout del Beat Detector:** Analizzare brani di 4 minuti intasava la CPU. Il detector andava in timeout a 8 secondi, restituendo alla IA tagli validi solo per i primi 30 secondi. Oltre i 30s, l'AiDirector smetteva di operare, ma l'audio del video andava avanti fino a 60s, causando un freeze di 30 secondi.
   *Soluzione:* Il `RealBeatDetector` ora sa in anticipo la `targetDurationMs`. Interrompe l'analisi istantaneamente non appena estrae abbastanza battiti per riempire il montaggio richiesto. Inoltre, aggiunge un *padding matematico* finale se il brano ha pause/breakdown prolungati senza cassa.
3. **Scollamento della Timeline (Clip corte):** Se l'AiDirector decideva un taglio da 4 secondi, ma il video sorgente ne durava solo 2, l'AiDirector clamped la clip a 2 secondi. Tuttavia, l'orologio musicale "chiudeva" l'operazione come se ne fossero passati 4. Questo causava la fine precoce del video rispetto alla traccia musicale.
   *Soluzione:* Riscritto il ciclo logico dell'AiDirector. Ora l'`AiDirector` è schiavo del tempo assoluto (`currentTimelineMs`). Se un video è troppo corto, l'orologio interno avanza solo per i millisecondi reali usati e al giro successivo cerca dinamicamente il beat più vicino.

## 4. Evoluzione dello Speed Ramping (Effetto GoPro)
- **Logica:** Utilizziamo 3 micro-clip (2.0x -> 0.4x -> 2.0x) per ottenere l'ingresso veloce, l'azione lenta (centrata sul `peakMs` ricavato dalla ML) e l'uscita veloce.
- **Prevenzione crash:** Se la clip sorgente è troppo corta per tollerare l'estensione temporale del rallentatore, il sistema fa fallback automatico al taglio standard 1.0x (grazie a un clamp di sicurezza).
- **Aumento Frequenza:** Per renderli molto più presenti (richiesta utente), abbiamo autorizzato gli Speed Ramps nelle sezioni musicali DROP e CLIMAX, portando la probabilità randomica di innesco al 75%.

## 5. Il Filtro di Qualità alla Selezione (I 3 secondi)
Poiché clip di 1 o 2 secondi costringevano l'AiDirector a fare tagli goffi, sballare il ritmo o disattivare i rallenty per mancanza di fotogrammi, abbiamo inserito un check alla base. In `MainActivity`, il picker scarta preventivamente tutti i file `< 3000ms`, notificando l'utente tramite Toast. Questo garantisce standard qualitativi "Action Cam" altissimi all'ingresso.
