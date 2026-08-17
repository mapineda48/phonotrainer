"""Download and cache the models PhonoTrainer uses.

Usage: .venv/bin/python scripts/download_models.py
"""

import sys


def main() -> int:
    print("[1/3] faster-whisper small (int8, CPU)…", flush=True)
    from faster_whisper import WhisperModel

    WhisperModel("small", device="cpu", compute_type="int8")
    print("      OK", flush=True)

    print("[2/3] facebook/wav2vec2-lv-60-espeak-cv-ft…", flush=True)
    from transformers import AutoModelForCTC, AutoProcessor

    AutoProcessor.from_pretrained("facebook/wav2vec2-lv-60-espeak-cv-ft")
    AutoModelForCTC.from_pretrained("facebook/wav2vec2-lv-60-espeak-cv-ft")
    print("      OK", flush=True)

    print("[3/3] NLTK data for g2p_en (cmudict + taggers)…", flush=True)
    import nltk

    for pkg in ("cmudict", "averaged_perceptron_tagger", "averaged_perceptron_tagger_eng"):
        try:
            nltk.download(pkg, quiet=True)
        except Exception as exc:  # the _eng tagger does not exist in older nltk
            print(f"      warning: {pkg}: {exc}", flush=True)
    print("      OK", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
