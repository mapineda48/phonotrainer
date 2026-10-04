"""Download and cache the models PhonoTrainer uses.

Usage: .venv/bin/python scripts/download_models.py
"""

import sys


def main() -> int:
    print("[1/5] faster-whisper small (int8, CPU)…", flush=True)
    from faster_whisper import WhisperModel

    WhisperModel("small", device="cpu", compute_type="int8")
    print("      OK", flush=True)

    # the default phone engine (timit61); its weights are Apache-2.0, its training
    # data (TIMIT, LDC93S1) is licensed for non-commercial research
    timit = "excalibur12/wav2vec2-large-lv60_phoneme-timit_english_timit-4k"
    print(f"[2/5] {timit}…", flush=True)
    from huggingface_hub import hf_hub_download
    from transformers import AutoModelForCTC, AutoProcessor, Wav2Vec2FeatureExtractor

    Wav2Vec2FeatureExtractor.from_pretrained(timit)
    AutoModelForCTC.from_pretrained(timit)
    hf_hub_download(timit, "vocab.json")
    print("      OK", flush=True)

    # the espeak engine (--phone-engine espeak)
    print("[3/5] facebook/wav2vec2-lv-60-espeak-cv-ft…", flush=True)
    AutoProcessor.from_pretrained("facebook/wav2vec2-lv-60-espeak-cv-ft")
    AutoModelForCTC.from_pretrained("facebook/wav2vec2-lv-60-espeak-cv-ft")
    print("      OK", flush=True)

    print("[4/5] NLTK data for g2p_en (cmudict + taggers)…", flush=True)
    import nltk

    for pkg in ("cmudict", "averaged_perceptron_tagger", "averaged_perceptron_tagger_eng"):
        try:
            nltk.download(pkg, quiet=True)
        except Exception as exc:  # the _eng tagger does not exist in older nltk
            print(f"      warning: {pkg}: {exc}", flush=True)
    print("      OK", flush=True)

    print("[5/5] dialogue separator (htdemucs, ~80 MB)…", flush=True)
    from phonotrainer.separation import fetch_weights

    fetch_weights()
    print("      OK", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
