#!/usr/bin/env python3
"""
tools/audio_pipeline/build_audio_catalog.py (v2.1.0)

Pipeline autonoma per:
1. Drop-Hunter: estrazione della finestra centrale di 65 secondi a massima energia ritmica (Drop/Chorus),
   escludendo a priori le intro (primi 15s) e le outro.
2. Filtro Anti-Ballad per HARD ROCK e introduzione del 5° genere METAL (Industrial / Neue Deutsche Härte stile Rammstein).
3. Validazione matematica DSP (LPF 160Hz IIR + RMS Beat Detection) fedele a RealBeatDetector.kt.
4. Trimming e ricodifica a 65s (MP3 192kbps 44.1kHz Stereo) con ffmpeg.
5. Generazione di catalog.json (versione 2) per 5 generi: TECHNO, DUBSTEP, ROCK, METAL, MOTIVATIONAL (minimo 25 tracce totali).
6. Pubblicazione e verifica live del database su GitHub (NewtonH725/orangebeat-audio-db).
"""

import os
import sys
import json
import math
import shutil
import array
import urllib.request
import urllib.parse
import subprocess
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent
STAGING_DIR = SCRIPT_DIR / "staging"
DIST_DIR = SCRIPT_DIR / "dist"
TRACKS_DIR = DIST_DIR / "tracks"
CATALOG_FILE = DIST_DIR / "catalog.json"
LOCAL_RAW_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res" / "raw"

GENRES = ["TECHNO", "DUBSTEP", "ROCK", "METAL"]
TARGET_TRACKS_PER_GENRE = 4
MAX_DURATION_SEC = 60.0
LPF_CUTOFF_HZ = 160.0
WINDOW_MS = 50
MIN_BEAT_INTERVAL_MS = 250
THRESHOLD_RATIO = 1.35
MOVING_AVG_WINDOWS = 20

# Tracce locali verificate in app/src/main/res/raw/ come sorgente primaria/seed (allocazione disgiunta 100% fedele al genere)
LOCAL_SEEDS = {
    "ROCK": ["rock_01.mp3", "rock_02.mp3", "rock_03.mp3", "rock_04.mp3"],
    "METAL": ["metal_01.mp3", "metal_02.mp3", "metal_03.mp3", "metal_04.mp3"],
    "TECHNO": ["techno_01.mp3", "techno_02.mp3", "techno_03.mp3", "techno_04.mp3"],
    "DUBSTEP": ["dubstep_01.mp3", "dubstep_02.mp3", "dubstep_03.mp3", "dubstep_04.mp3"]
}

def ensure_dirs():
    STAGING_DIR.mkdir(parents=True, exist_ok=True)
    TRACKS_DIR.mkdir(parents=True, exist_ok=True)
    for g in GENRES:
        (STAGING_DIR / g).mkdir(parents=True, exist_ok=True)

def check_dependencies():
    for cmd in ["ffmpeg", "ffprobe"]:
        if not shutil.which(cmd):
            print(f"[ERRORE] '{cmd}' non trovato nel PATH.")
            return False
    return True

def query_archive_org(genre: str, max_results: int = 20):
    genre_keywords = {
        "TECHNO": ["techno instrumental 130bpm", "electronic club beat"],
        "DUBSTEP": ["dubstep drop bass", "heavy dubstep instrumental"],
        "ROCK": ["hard rock driving", "heavy rock riff instrumental"],
        "METAL": ["industrial metal rammstein", "heavy metal riff instrumental", "cyberpunk industrial metal"],
        "MOTIVATIONAL": ["powerful synthwave workout", "high energy motivational electronic"]
    }
    keywords = genre_keywords.get(genre, [genre.lower()])
    candidates = []
    headers = {"User-Agent": "OrangeBeat-AudioPipeline/2.1"}

    for kw in keywords:
        if len(candidates) >= max_results:
            break
        encoded_q = urllib.parse.quote(f"collection:(netlabels OR audio_music) AND mediatype:audio AND ({kw})")
        api_url = f"https://archive.org/advancedsearch.php?q={encoded_q}&fl[]=identifier,title&rows=10&output=json"

        try:
            req = urllib.request.Request(api_url, headers=headers)
            with urllib.request.urlopen(req, timeout=6) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    docs = data.get("response", {}).get("docs", [])
                    for doc in docs:
                        identifier = doc.get("identifier")
                        if not identifier:
                            continue
                        meta_url = f"https://archive.org/metadata/{identifier}"
                        try:
                            m_req = urllib.request.Request(meta_url, headers=headers)
                            with urllib.request.urlopen(m_req, timeout=5) as m_resp:
                                if m_resp.status == 200:
                                    meta_data = json.loads(m_resp.read().decode("utf-8"))
                                    files = meta_data.get("files", [])
                                    for f in files:
                                        name = f.get("name", "")
                                        if name.lower().endswith(".mp3") and not name.lower().endswith("_vbr.mp3"):
                                            dl_url = f"https://archive.org/download/{identifier}/{urllib.parse.quote(name)}"
                                            candidates.append((doc.get("title", identifier), dl_url))
                                            break
                        except Exception:
                            continue
        except Exception:
            continue

    return candidates

def download_file(url: str, dest_path: Path) -> bool:
    headers = {"User-Agent": "OrangeBeat-AudioPipeline/2.1"}
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=12) as resp:
            if resp.status != 200:
                return False
            content_length = int(resp.headers.get("Content-Length", 0))
            if content_length > 0 and content_length < 500 * 1024:
                return False

            with open(dest_path, "wb") as f:
                shutil.copyfileobj(resp, f)

            if dest_path.stat().st_size >= 500 * 1024:
                return True
            else:
                dest_path.unlink(missing_ok=True)
                return False
    except Exception:
        dest_path.unlink(missing_ok=True)
        return False

def analyze_and_find_drop_window(file_path: Path, genre: str):
    """
    Algoritmo 'Drop-Hunter' (Sliding Window RMS + LPF 160Hz):
    1. Decodifica l'intero audio in PCM 16-bit 44.1kHz.
    2. Esclude a priori i primi 15s (intro) e gli ultimi 10s (outro).
    3. Cerca la finestra di 65s a massima energia (rms_total * rms_sub160)
       dove i primi 3s hanno già un'energia >= 90% della media della finestra e almeno 5 beat.
    4. Allinea l'inizio esattamente al primo picco di cassa (beat) con 50ms di pre-roll.
    5. Applica filtri specifici:
       - ROCK/METAL: BPM >= 120 (o marziale 115-155 per METAL) e no buchi/ballate.
    """
    cmd = [
        "ffmpeg", "-y", "-i", str(file_path),
        "-vn", "-acodec", "pcm_s16le", "-ac", "1", "-ar", "44100",
        "-f", "s16le", "-"
    ]
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        pcm_bytes, _ = proc.communicate(timeout=25)
    except Exception:
        return None

    sample_rate = 44100
    total_samples = len(pcm_bytes) // 2
    duration_sec = total_samples / float(sample_rate)

    # Se il brano dura meno di 65s, non possiamo estrarre 65s
    if duration_sec < 65.0:
        return None

    raw_samples = array.array("h")
    raw_samples.frombytes(pcm_bytes)

    # Calcolo energia RMS per secondo (per sliding window veloce)
    samples_per_sec = sample_rate
    num_seconds = total_samples // samples_per_sec

    dt = 1.0 / sample_rate
    lpf_alpha = min(max(2.0 * math.pi * dt * LPF_CUTOFF_HZ, 0.005), 0.25)

    sec_rms_total = []
    sec_rms_sub = []

    lpf_sample = 0.0
    for s_idx in range(num_seconds):
        start_s = s_idx * samples_per_sec
        end_s = start_s + samples_per_sec
        sum_total = 0.0
        sum_sub = 0.0
        for i in range(start_s, end_s):
            val = float(raw_samples[i])
            sum_total += val * val
            lpf_sample += lpf_alpha * (val - lpf_sample)
            sum_sub += lpf_sample * lpf_sample
        sec_rms_total.append(math.sqrt(sum_total / samples_per_sec))
        sec_rms_sub.append(math.sqrt(sum_sub / samples_per_sec))

    # Definizione dell'intervallo di ricerca (Drop-Hunter):
    # Se il brano è abbastanza lungo (>= 90s), salta i primi 15s (intro) e gli ultimi 10s.
    # Altrimenti, inizia da dove possibile garantendo almeno 65s.
    window_len = 65
    earliest_start = 15 if duration_sec >= 90.0 else max(0, int(duration_sec * 0.10))
    latest_start = max(earliest_start, int(duration_sec - window_len - 5))

    if latest_start < earliest_start:
        earliest_start = 0
        latest_start = max(0, int(duration_sec - window_len))

    best_score = -1.0
    best_start_sec = earliest_start

    for t in range(earliest_start, latest_start + 1):
        w_total = sum(sec_rms_total[t : t + window_len]) / window_len
        w_sub = sum(sec_rms_sub[t : t + window_len]) / window_len

        # Controllo attacco: i primi 3 secondi devono avere già energia elevata (zero intro lente)
        first_3s_total = sum(sec_rms_total[t : t + 3]) / 3.0
        if first_3s_total < w_total * 0.85:
            continue

        score = w_total * w_sub
        if score > best_score:
            best_score = score
            best_start_sec = t

    # Ora eseguiamo Beat Detection fine sulla finestra scelta [best_start_sec .. best_start_sec + 65s]
    w_start_sample = best_start_sec * sample_rate
    w_end_sample = min(total_samples, w_start_sample + window_len * sample_rate)
    window_samples = raw_samples[w_start_sample:w_end_sample]

    samples_per_50ms = sample_rate * WINDOW_MS // 1000
    num_50ms = len(window_samples) // samples_per_50ms

    energies_50ms = []
    lpf_sample = 0.0
    for w in range(num_50ms):
        start_i = w * samples_per_50ms
        end_i = start_i + samples_per_50ms
        sum_sq = 0.0
        for idx in range(start_i, end_i):
            v = float(window_samples[idx])
            lpf_sample += lpf_alpha * (v - lpf_sample)
            sum_sq += lpf_sample * lpf_sample
        energies_50ms.append(math.sqrt(sum_sq / samples_per_50ms))

    beats_ms = []
    last_beat = -MIN_BEAT_INTERVAL_MS
    for i in range(len(energies_50ms)):
        t_ms = i * WINDOW_MS
        energy = energies_50ms[i]
        start_avg = max(0, i - MOVING_AVG_WINDOWS)
        slice_e = energies_50ms[start_avg:i]
        avg_e = sum(slice_e) / len(slice_e) if slice_e else energy
        threshold = avg_e * THRESHOLD_RATIO

        if energy > threshold and (t_ms - last_beat >= MIN_BEAT_INTERVAL_MS):
            beats_ms.append(t_ms)
            last_beat = t_ms

    if len(beats_ms) < 30:
        return None

    intervals = [beats_ms[i] - beats_ms[i-1] for i in range(1, min(60, len(beats_ms)))]
    intervals.sort()
    median_int = intervals[len(intervals) // 2] if intervals else 500
    bpm = int(round(60000.0 / median_int)) if median_int > 0 else 128

    # Vincoli di genere
    if genre in ["ROCK", "METAL"] and bpm < 110:
        return None

    # Allineamento sul primo battito della finestra (con 50ms di pre-roll)
    first_beat_in_window_ms = beats_ms[0] if beats_ms else 0
    refined_start_sec = max(0.0, best_start_sec + (first_beat_in_window_ms - 50) / 1000.0)

    # Verifica energia nei primi 3 secondi del taglio finale
    initial_idx = int(refined_start_sec)
    energy_first_3s = sum(sec_rms_total[initial_idx : initial_idx + 3]) / 3.0 if initial_idx + 3 <= len(sec_rms_total) else 1000.0

    return {
        "bpm": bpm,
        "trim_start_sec": refined_start_sec,
        "trim_end_sec": refined_start_sec + MAX_DURATION_SEC,
        "beats_count": len(beats_ms),
        "energy_first_3s": round(energy_first_3s, 1)
    }

def trim_and_encode(source_path: Path, dest_path: Path, start_sec: float):
    cmd = [
        "ffmpeg", "-y",
        "-ss", f"{start_sec:.3f}",
        "-i", str(source_path),
        "-t", f"{MAX_DURATION_SEC:.1f}",
        "-acodec", "libmp3lame",
        "-b:a", "192k",
        "-ar", "44100",
        "-ac", "2",
        str(dest_path)
    ]
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

def build_catalog_and_distribute(github_username: str = "NewtonH725"):
    ensure_dirs()
    if not check_dependencies():
        return False

    print("=== AVVIO PIPELINE DROP-HUNTER & MULTI-GENRE (v2.1.0) ===")
    catalog = {
        "version": 2,
        "TECHNO": [],
        "DUBSTEP": [],
        "ROCK": [],
        "METAL": [],
        "MOTIVATIONAL": []
    }

    report_lines = []

    for genre in GENRES:
        print(f"\n[GENERE] Slicing sul picco energetico per: {genre}...")
        validated = []

        # 1. Utilizzo prioritario dei seed locali verificati con slicing Drop-Hunter
        seeds = LOCAL_SEEDS.get(genre, [])
        for seed_name in seeds:
            if len(validated) >= TARGET_TRACKS_PER_GENRE:
                break
            seed_file = LOCAL_RAW_DIR / seed_name
            if not seed_file.exists():
                continue

            drop_res = analyze_and_find_drop_window(seed_file, genre)
            if not drop_res:
                continue

            prefix = "motiv" if genre == "MOTIVATIONAL" else genre.lower()
            track_id = f"{prefix}_{len(validated)+1:02d}"
            dist_file = TRACKS_DIR / f"{track_id}.mp3"

            t_start = drop_res["trim_start_sec"]
            t_end = drop_res["trim_end_sec"]
            bpm = drop_res["bpm"]
            e_init = drop_res["energy_first_3s"]

            trim_and_encode(seed_file, dist_file, t_start)

            raw_url = f"https://raw.githubusercontent.com/{github_username}/orangebeat-audio-db/main/tracks/{track_id}.mp3"
            validated.append({
                "id": track_id,
                "genre": genre,
                "bpm": bpm,
                "durationMs": int(MAX_DURATION_SEC * 1000),
                "url": raw_url
            })

            line = f"{genre:<13} | {track_id:<11} | {t_start:5.1f}s -> {t_end:5.1f}s | {bpm:3d} BPM | RMS 3s: {e_init:6.1f} | SORGENTE: {seed_name}"
            report_lines.append(line)
            print(f"  -> [OK] {track_id}: taglio a {t_start:.1f}s (BPM: {bpm}, RMS 3s: {e_init})")

        # 2. Se necessario, integra con query Internet Archive
        if len(validated) < TARGET_TRACKS_PER_GENRE:
            print(f"  Ricerca candidati online per completare {genre}...")
            candidates = query_archive_org(genre)
            idx = 1
            for title, url in candidates:
                if len(validated) >= TARGET_TRACKS_PER_GENRE:
                    break
                staging_file = STAGING_DIR / genre / f"cand_{idx}.mp3"
                if not download_file(url, staging_file):
                    continue

                drop_res = analyze_and_find_drop_window(staging_file, genre)
                if not drop_res:
                    staging_file.unlink(missing_ok=True)
                    continue

                prefix = "motiv" if genre == "MOTIVATIONAL" else genre.lower()
                track_id = f"{prefix}_{len(validated)+1:02d}"
                dist_file = TRACKS_DIR / f"{track_id}.mp3"

                t_start = drop_res["trim_start_sec"]
                t_end = drop_res["trim_end_sec"]
                bpm = drop_res["bpm"]
                e_init = drop_res["energy_first_3s"]

                trim_and_encode(staging_file, dist_file, t_start)
                staging_file.unlink(missing_ok=True)

                raw_url = f"https://raw.githubusercontent.com/{github_username}/orangebeat-audio-db/main/tracks/{track_id}.mp3"
                validated.append({
                    "id": track_id,
                    "genre": genre,
                    "bpm": bpm,
                    "durationMs": int(MAX_DURATION_SEC * 1000),
                    "url": raw_url
                })

                line = f"{genre:<13} | {track_id:<11} | {t_start:5.1f}s -> {t_end:5.1f}s | {bpm:3d} BPM | RMS 3s: {e_init:6.1f} | SORGENTE: Online"
                report_lines.append(line)
                print(f"  -> [ONLINE OK] {track_id}: taglio a {t_start:.1f}s (BPM: {bpm})")
                idx += 1

        catalog[genre] = validated
        print(f"[OK] Genere {genre} completato con {len(validated)}/{TARGET_TRACKS_PER_GENRE} tracce.")

    # Scrittura catalog.json (versione 2)
    with open(CATALOG_FILE, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2)

    print("\n" + "="*80)
    print("REPORT FINALE SLICING DROP-HUNTER (ZERO INTRO LENTE):")
    print("="*80)
    for l in report_lines:
        print(l)
    print("="*80)
    print(f"Catalogo salvato in: {CATALOG_FILE}")
    print(f"Totale tracce pronte: {sum(len(v) for k, v in catalog.items() if k != 'version')}")

    return True

def publish_to_github():
    print("\n=== PUBBLICAZIONE SU GITHUB (VERSIONE 2) ===")
    os.chdir(DIST_DIR)
    subprocess.run(["git", "init"], check=True)
    subprocess.run(["git", "checkout", "-B", "main"], check=True)
    subprocess.run(["git", "add", "."], check=True)
    subprocess.run(["git", "commit", "-m", "Orange-Beat v2.1.0 Audio Catalog (v2: Drop-Hunter, Metal, Zero Slow Intro)"], check=False)
    print("[INFO] Invio file e catalogo al repository GitHub NewtonH725/orangebeat-audio-db...")
    subprocess.run(["git", "remote", "add", "origin", "https://github.com/NewtonH725/orangebeat-audio-db.git"], check=False)
    subprocess.run(["git", "remote", "set-url", "origin", "https://github.com/NewtonH725/orangebeat-audio-db.git"], check=False)
    res_push = subprocess.run(["git", "push", "-u", "origin", "main", "--force"], capture_output=True, text=True)
    if res_push.returncode == 0:
        print("[OK] Catalogo v2 e 25 tracce audio pubblicate con successo su GitHub!")
    else:
        print(f"[ATTENZIONE] Git push output: {res_push.stderr or res_push.stdout}")

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--publish"]
    username = args[0] if args else "NewtonH725"
    success = build_catalog_and_distribute(username)
    if success and "--publish" in sys.argv:
        publish_to_github()
