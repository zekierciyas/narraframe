"""
Narraframe — Piper text-to-speech helper.

Reads text from stdin and writes a WAV file:
    python piper_synth.py --model voice.onnx --output out.wav [--length-scale 1.0] [--espeak-dir DIR]

Batch mode loads the voice once for many scenes: stdin is a JSON array of strings and
part_000.wav, part_001.wav, … are written to DIR:
    python piper_synth.py --model voice.onnx --batch-dir DIR [...]

espeak-ng silently fails when its data path is longer than ~160 characters, which
happens when the venv lives deep inside an external drive. The app passes a short
symlink to the bundled espeak-ng-data via --espeak-dir.
"""
import argparse
import json
import os
import sys
import wave

from piper import PiperVoice
from piper.voice import SynthesisConfig


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--output", default=None)
    ap.add_argument("--batch-dir", default=None)
    ap.add_argument("--length-scale", type=float, default=1.0)
    ap.add_argument("--espeak-dir", default=None)
    args = ap.parse_args()

    raw = sys.stdin.read().strip()
    if not raw:
        print("No text given on stdin", file=sys.stderr)
        return 2

    kwargs = {"espeak_data_dir": args.espeak_dir} if args.espeak_dir else {}
    voice = PiperVoice.load(args.model, **kwargs)
    syn_config = SynthesisConfig(length_scale=args.length_scale)

    if args.batch_dir:
        texts = json.loads(raw)
        for i, text in enumerate(texts):
            with wave.open(os.path.join(args.batch_dir, "part_%03d.wav" % i), "wb") as wav:
                voice.synthesize_wav(text, wav, syn_config=syn_config)
            print("done %d/%d" % (i + 1, len(texts)), flush=True)
        return 0

    with wave.open(args.output, "wb") as wav:
        voice.synthesize_wav(raw, wav, syn_config=syn_config)
    return 0


if __name__ == "__main__":
    sys.exit(main())
