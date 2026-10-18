"""Generate bundled pronunciation audio with Piper (offline neural TTS).

Usage: gen_audio.py <manifest.tsv> <model.onnx> <cache dir> <asset dir>

The manifest comes from `./gradlew :core:audioManifest` and holds "key<TAB>text" lines.
Each text becomes <asset dir>/<key>.webm (Opus, mono, 32 kbps). Files already in the cache
dir are reused, so only new or changed words are synthesized.
"""
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

from piper import PiperVoice


def main() -> None:
    manifest, model, cache_dir, asset_dir = map(Path, sys.argv[1:5])
    cache_dir.mkdir(parents=True, exist_ok=True)
    asset_dir.mkdir(parents=True, exist_ok=True)
    voice = PiperVoice.load(str(model))

    entries = [line.split("\t", 1) for line in manifest.read_text(encoding="utf-8").splitlines() if line]
    made = 0
    for key, text in entries:
        cached = cache_dir / f"{key}.webm"
        if not cached.exists():
            with tempfile.TemporaryDirectory() as tmp:
                wav_path = Path(tmp) / "out.wav"
                with wave.open(str(wav_path), "wb") as wav:
                    voice.synthesize_wav(text, wav)
                subprocess.run(
                    ["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav_path),
                     "-ac", "1", "-c:a", "libopus", "-b:a", "32k", str(cached)],
                    check=True,
                )
            made += 1
        shutil.copyfile(cached, asset_dir / cached.name)
    print(f"{len(entries)} audio files ready ({made} newly synthesized)")


if __name__ == "__main__":
    main()
