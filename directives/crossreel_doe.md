# Framework DOE: CrossReel AI (App Nativa Android)

Questo documento applica i principi teorici del **Framework DOE (Directives, Objectives, Executions)** descritti nella letteratura accademica e le regole operative del file `setup.md` all'architettura della nostra applicazione nativa Android (CrossReel).

L'obiettivo è garantire una separazione netta tra l'intento strategico/creativo (Gemini), il rigore del risultato (Obiettivi) e il motore di rendering deterministico (Kotlin/FFmpeg sul Pixel 9 Pro).

---

## 1. Livello delle Direttive (Governance e Allineamento)
*Le Direttive sostituiscono il "prompting casuale" con regole immutabili a livello di sistema.*

Nel nostro progetto, l'Intelligenza Artificiale (Gemini 1.5) funge **esclusivamente da Analista e Regista**, mai da esecutore diretto.

*   **Task Alignment (Costituzione Architetturale):**
    *   **D1 (Separazione dei Compiti):** Gemini riceve in input solo metadati o frame compressi a bassa risoluzione estratti localmente. **Vietato** tentare di far elaborare il file video originale ad alta risoluzione tramite API cloud.
    *   **D2 (Output Vincolato):** L'output del modello LLM deve essere **esclusivamente un JSON strutturato** (Edit Decision List) contenente gli array di timestamp. Nessun testo discorsivo o allucinazione creativa è permesso nella fase di risposta.
    *   **D3 (Stile Invariabile - Teal & Orange):** Indipendentemente dalle condizioni di luce del video di input, il sistema deve applicare in modo inflessibile la LUT 3D Teal & Orange pre-caricata nell'app. L'estetica non è lasciata all'interpretazione probabilistica dell'LLM.
    *   **D4 (Compressione Semantica):** Per evitare la *Saturazione del Contesto (Vertical Bloat)* descritta nel PDF, non invieremo l'intero stream video. L'app Android (Execution Harness) comprimerà l'informazione estraendo solo keyframe per interrogare Gemini.

---

## 2. Livello degli Obiettivi (Contratti Comportamentali - ABC)
*Passaggio dall'intento testuale allo stato finale validabile. Il successo si misura in codice, non a parole.*

L'app implementa un rigido **Agent Behavioral Contract (Contratto ABC)** prima, durante e dopo il processo:

*   **Precondizioni (P):**
    *   Almeno N clip selezionate dalla galleria.
    *   Traccia MP3 (Techno, Rock, Motivational) selezionata.
    *   Durata target specificata (15s, 30s, 45s).
    *   Nessuna operazione inizia se (P) non è soddisfatta.
*   **Invarianti (I):**
    *   *Hard Invariant:* Il formato del file in uscita DEVE essere Verticale (9:16).
    *   *Hard Invariant:* I tagli video DEVONO coincidere con i millisecondi (Onset/Beats) calcolati deterministicamente da TarsosDSP.
*   **Recupero e Validazione (Evaluator-Optimizer):**
    *   Terminato il processo, il codice Kotlin agirà da *Valutatore*. Controllerà l'esistenza fisica del file `.mp4` e la validità del codec.
    *   Se l'LLM restituisce un JSON malformato, l'Execution Harness (Kotlin) intercetta l'errore deterministicamente senza crash, riprovando o bloccando la pipeline in sicurezza.

---

## 3. Livello delle Esecuzioni (Autonomia e Confini Operativi)
*L'ambiente sandbox in cui l'IA si interfaccia con il mondo reale (il processore del Pixel).*

Questo livello rappresenta la segregazione fisica tra il "Ragionamento" (Cloud AI) e l'"Esecuzione" (Hardware Locale).

*   **L'Execution Harness (Imbracatura di Esecuzione):**
    *   L'app Kotlin è l'imbracatura. Riceve la "ricetta" (JSON) da Gemini ma esegue i compiti in una sandbox deterministica sul telefono.
    *   **Runtime Enforcement (Simil-AgentSpec):** Prima di passare la stringa di comando a `ffmpeg-kit`, Kotlin convalida l'input. Verifica che i percorsi dei file `os.path` puntino esclusivamente alle clip autorizzate dalla galleria dell'utente.
*   **Script Deterministici (Strumenti di Basso Livello):**
    1.  **Tool A (Beat Detector):** `TarsosDSP` in Java. Riceve un file MP3 e restituisce un array di millisecondi `[beat1, beat2, ...]`. (Nessuna IA coinvolta, matematica pura per impedire allucinazioni ritmiche).
    2.  **Tool B (Media Extractor):** Esecuzione locale per preparare i dati leggeri (Compressione Semantica) per l'invio al Cloud.
    3.  **Tool C (FFmpeg Composer):** Esecutore hardware puro. Applica la stringa: taglio ai millisecondi esatti, unione, applicazione LUT 3D Teal & Orange, e mixaggio audio.

---

## Implementazione nel nostro Workspace
Seguendo il paradigma imposto in `setup.md`, il nostro repository opererà in questo modo:
1. **`directives/`**: Conterrà questo file e le eventuali altre SOP.
2. **Orchestrazione (Antigravity)**: Il mio ruolo sarà mappare le decisioni, rispettare questa direttiva, e chiamare i comandi per sviluppare l'app.
3. **`execution/`**: In questo contesto per lo sviluppo, i file `.kt` e i comandi gradle sono i nostri strumenti di esecuzione deterministici.
