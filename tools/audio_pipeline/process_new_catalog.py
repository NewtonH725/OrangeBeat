#!/usr/bin/env python3
import os, sys, glob, json, subprocess, math, struct
from pathlib import Path

BASE_DIR = Path("/home/marco/ANTIGRAVITY PROJECT/Montatore")
STAGING_DIR = BASE_DIR / "tools" / "audio_pipeline" / "staging"
DIST_DIR = BASE_DIR / "tools" / "audio_pipeline" / "dist"
DIST_TRACKS_DIR = DIST_DIR / "tracks"
RAW_DIR = BASE_DIR / "app" / "src" / "main" / "res" / "raw"

GENRES = ["ROCK", "METAL", "TECHNO", "DUBSTEP"]
TARGET_DURATION = 60.0

def find_best_window(file_path: Path):
    cmd_probe = ["ffprobe", "-v", "quiet", "-show_format", "-of", "json", str(file_path)]
    res = subprocess.run(cmd_probe, capture_output=True, text=True)
    fmt = json.loads(res.stdout).get("format", {})
    total_dur = float(fmt.get("duration", 0))

    if total_dur <= TARGET_DURATION:
        return 0.0, total_dur

    cmd_pcm = [
        "ffmpeg", "-y", "-i", str(file_path),
        "-vn", "-acodec", "pcm_s16le", "-ac", "1", "-ar", "44100",
        "-f", "s16le", "-"
    ]
    p = subprocess.Popen(cmd_pcm, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    raw_data, _ = p.communicate()

    num_samples = len(raw_data) // 2
    if num_samples == 0:
        return 0.0, min(total_dur, TARGET_DURATION)

    # 50ms windows
    window_samples = int(44100 * 0.05)
    num_windows = num_samples // window_samples

    rms_values = []
    for w in range(num_windows):
        start = w * window_samples * 2
        chunk = raw_data[start : start + window_samples * 2]
        samples = struct.unpack(f"<{len(chunk)//2}h", chunk)
        if not samples:
            rms_values.append(0.0)
            continue
        sum_sq = sum(s * s for s in samples)
        rms = math.sqrt(sum_sq / len(samples))
        rms_values.append(rms)

    # Window of TARGET_DURATION (60s = 1200 windows)
    target_windows = int(TARGET_DURATION / 0.05)
    
    # Exclude first 5s and last 5s if file is long enough
    min_start_w = int(5.0 / 0.05) if total_dur > TARGET_DURATION + 15 else 0
    max_start_w = num_windows - target_windows - (int(5.0 / 0.05) if total_dur > TARGET_DURATION + 15 else 0)
    max_start_w = max(min_start_w, max_start_w)

    best_start_w = min_start_w
    best_energy = -1.0

    for sw in range(min_start_w, max_start_w + 1):
        win_rms = rms_values[sw : sw + target_windows]
        if not win_rms:
            continue
        avg_rms = sum(win_rms) / len(win_rms)
        # We also check that the start of the window is not silent
        start_rms = sum(win_rms[:40]) / 40.0 # first 2 seconds
        if start_rms < avg_rms * 0.5:
            continue
        if avg_rms > best_energy:
            best_energy = avg_rms
            best_start_w = sw

    best_start_sec = best_start_w * 0.05
    return best_start_sec, TARGET_DURATION

# Rich metadata profiles per track ID: (subStyle, energy)
# subStyle: CLASSIC_GROOVY, FAST_DRIVING, DARK_INDUSTRIAL, EXTREME_PEAK
# energy: LOW, MEDIUM, HIGH, EXTREME
TRACK_PROFILES = {
    "rock_01": ("CLASSIC_GROOVY", "MEDIUM"),
    "rock_02": ("FAST_DRIVING", "HIGH"),
    "rock_03": ("DARK_INDUSTRIAL", "MEDIUM"),
    "rock_04": ("EXTREME_PEAK", "HIGH"),
    "metal_01": ("CLASSIC_GROOVY", "HIGH"),
    "metal_02": ("FAST_DRIVING", "EXTREME"),
    "metal_03": ("EXTREME_PEAK", "EXTREME"),
    "metal_04": ("DARK_INDUSTRIAL", "HIGH"),
    "techno_01": ("FAST_DRIVING", "HIGH"),
    "techno_02": ("CLASSIC_GROOVY", "MEDIUM"),
    "techno_03": ("EXTREME_PEAK", "HIGH"),
    "techno_04": ("DARK_INDUSTRIAL", "EXTREME"),
    "dubstep_01": ("DARK_INDUSTRIAL", "HIGH"),
    "dubstep_02": ("CLASSIC_GROOVY", "MEDIUM"),
    "dubstep_03": ("FAST_DRIVING", "HIGH"),
    "dubstep_04": ("EXTREME_PEAK", "EXTREME"),
}

def analyze_audio_features(file_path: Path):
    cmd_pcm = [
        "ffmpeg", "-y", "-i", str(file_path),
        "-vn", "-acodec", "pcm_s16le", "-ac", "1", "-ar", "44100",
        "-f", "s16le", "-"
    ]
    p = subprocess.Popen(cmd_pcm, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    raw_data, _ = p.communicate()
    num_samples = len(raw_data) // 2
    window_samples = int(44100 * 0.05)
    num_windows = num_samples // window_samples

    rms_values = []
    for w in range(num_windows):
        start = w * window_samples * 2
        chunk = raw_data[start : start + window_samples * 2]
        samples = struct.unpack(f"<{len(chunk)//2}h", chunk)
        if not samples:
            rms_values.append(0.0)
            continue
        sum_sq = sum(s * s for s in samples)
        rms = math.sqrt(sum_sq / len(samples))
        rms_values.append(rms)

    # Detect beats
    beats = []
    moving_avg = []
    for w, rms in enumerate(rms_values):
        moving_avg.append(rms)
        if len(moving_avg) > 20:
            moving_avg.pop(0)
        avg = sum(moving_avg) / len(moving_avg)
        t_ms = int(w * 50)
        if rms > avg * 1.35 and (not beats or t_ms - beats[-1][0] > 250):
            beats.append((t_ms, rms))

    bpm = 130
    if len(beats) >= 5:
        intervals = [beats[i][0] - beats[i-1][0] for i in range(1, len(beats)) if 250 <= beats[i][0] - beats[i-1][0] <= 1200]
        if intervals:
            med = sorted(intervals)[len(intervals)//2]
            bpm = int(round(60000.0 / med))

    # Pick strongest beat near 15s (13000..19000ms) and near 30s (27000..34000ms)
    mid_candidates = [b for b in beats if 13000 <= b[0] <= 19000]
    late_candidates = [b for b in beats if 27000 <= b[0] <= 34000]
    offset_mid = max(mid_candidates, key=lambda x: x[1])[0] if mid_candidates else 15000
    offset_late = max(late_candidates, key=lambda x: x[1])[0] if late_candidates else 30000

    return bpm, [0, offset_mid, offset_late]

def process_all():
    DIST_TRACKS_DIR.mkdir(parents=True, exist_ok=True)
    RAW_DIR.mkdir(parents=True, exist_ok=True)

    catalog = {
        "version": 4,
        "ROCK": [],
        "METAL": [],
        "TECHNO": [],
        "DUBSTEP": []
    }

    for genre in GENRES:
        staging_files = sorted(glob.glob(str(STAGING_DIR / genre / "*_src.mp3")))
        for idx, src_file in enumerate(staging_files, 1):
            track_id = f"{genre.lower()}_{idx:02d}"
            out_filename = f"{track_id}.mp3"
            dist_out = DIST_TRACKS_DIR / out_filename
            raw_out = RAW_DIR / out_filename

            start_sec, dur_sec = find_best_window(Path(src_file))
            print(f"[{genre}] Processing {os.path.basename(src_file)} -> {out_filename} (start: {start_sec:.2f}s, dur: {dur_sec:.1f}s)")

            # Trimming, volume normalization and clean encoding
            cmd_cut = [
                "ffmpeg", "-y", "-ss", f"{start_sec:.3f}",
                "-i", src_file,
                "-t", f"{dur_sec:.3f}",
                "-af", "afade=t=in:ss=0:d=0.08,loudnorm=I=-14:TP=-1.5:LRA=11",
                "-acodec", "libmp3lame", "-b:a", "192k", "-ar", "44100", "-ac", "2",
                "-metadata", f"genre={genre}",
                "-metadata", f"title={track_id.upper()}",
                "-metadata", "artist=OrangeBeat Pro Catalog",
                str(dist_out)
            ]
            subprocess.run(cmd_cut, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

            # Copy to raw_out
            import shutil
            shutil.copyfile(dist_out, raw_out)

            bpm, drop_offsets = analyze_audio_features(dist_out)
            sub_style, energy = TRACK_PROFILES.get(track_id, ("FAST_DRIVING", "HIGH"))
            print(f"    -> Generated {out_filename}: BPM {bpm}, subStyle={sub_style}, energy={energy}, offsets={drop_offsets}")

            catalog[genre].append({
                "id": track_id,
                "genre": genre,
                "subStyle": sub_style,
                "energy": energy,
                "bpm": bpm,
                "durationMs": int(dur_sec * 1000),
                "dropOffsetsMs": drop_offsets,
                "url": f"https://raw.githubusercontent.com/NewtonH725/orangebeat-audio-db/main/tracks/{out_filename}"
            })

    catalog_path = DIST_DIR / "catalog.json"
    with open(catalog_path, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2)

    print("\nCatalog v4 written to", catalog_path)

if __name__ == "__main__":
    process_all()
