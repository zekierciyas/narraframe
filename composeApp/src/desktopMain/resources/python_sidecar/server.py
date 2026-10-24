"""
Narraframe — Local Neural TTS Sidecar
==============================================

A tiny FastAPI HTTP server that exposes Kokoro-82M and Qwen3-TTS as a
single-process inference backend. Called from the Kotlin Compose Desktop
client over localhost HTTP.

Contract
--------
GET  /health                 → {"ok": true, "device": "cpu|cuda|mps"}
POST /install_model          → lazily downloads / caches model weights
POST /tts                    → returns audio/wav bytes

All requests MUST include header `X-S2V-Token: <shared_token>` — matches
the token the Kotlin app spawned the process with.

Usage
-----
    python server.py --host 127.0.0.1 --port 0 --token <secret> \
                     --cache-dir ~/.narraframe/ai-runtime/cache

When --port is 0 the OS picks a free port which is written as a single
line to stdout ("PORT=12345") so the parent process can read it.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import socket
import sys
import tempfile
import time
import traceback
import wave
from threading import Lock
from typing import Optional

try:
    import numpy as np
except Exception as e:
    print(f"FATAL: numpy import failed: {e}", file=sys.stderr)
    raise

try:
    from fastapi import FastAPI, Header, HTTPException, Request
    from fastapi.responses import Response, JSONResponse
    import uvicorn
except Exception as e:
    print(f"FATAL: FastAPI import failed: {e}", file=sys.stderr)
    raise


# ── Lazy engine singletons ─────────────────────────────────────────────────
_kokoro_instance = None
_qwen3_instance = None
_qwen3_model_tag = None
_qwen3_vd_instance = None   # Voice Design model (separate variant)
_lock = Lock()


def log(msg: str):
    print(f"[s2v-tts] {msg}", flush=True)


def detect_device() -> str:
    try:
        import torch  # type: ignore
        if torch.cuda.is_available():
            return "cuda"
        if getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
            return "mps"
    except Exception:
        pass
    return "cpu"


def get_kokoro(cache_dir: str):
    """Load kokoro-onnx lazily. Model files live under cache_dir/kokoro."""
    global _kokoro_instance
    if _kokoro_instance is not None:
        return _kokoro_instance
    with _lock:
        if _kokoro_instance is not None:
            return _kokoro_instance

        from kokoro_onnx import Kokoro  # type: ignore

        kdir = os.path.join(cache_dir, "kokoro")
        os.makedirs(kdir, exist_ok=True)
        model_path = os.path.join(kdir, "kokoro-v1.0.onnx")
        voices_path = os.path.join(kdir, "voices-v1.0.bin")

        if not (os.path.isfile(model_path) and os.path.isfile(voices_path)):
            raise RuntimeError(
                "Kokoro model files missing. Call /install_model with engine=kokoro first."
            )

        log(f"Loading Kokoro from {kdir}")
        _kokoro_instance = Kokoro(model_path, voices_path)
        return _kokoro_instance


def get_qwen3(cache_dir: str, model_tag: str):
    """Load Qwen3-TTS lazily. model_tag is '0.6B' or '1.7B'."""
    global _qwen3_instance, _qwen3_model_tag
    if _qwen3_instance is not None and _qwen3_model_tag == model_tag:
        return _qwen3_instance
    with _lock:
        if _qwen3_instance is not None and _qwen3_model_tag == model_tag:
            return _qwen3_instance

        try:
            import torch  # type: ignore
            from transformers import AutoModel, AutoTokenizer  # type: ignore
        except Exception as e:
            raise RuntimeError(
                "Qwen3-TTS requires torch + transformers. Install via /install_model first."
            ) from e

        device = detect_device()
        dtype = torch.float16 if device != "cpu" else torch.float32
        repo_id = f"Qwen/Qwen3-TTS-12Hz-{model_tag}-CustomVoice"
        log(f"Loading Qwen3-TTS {model_tag} on {device} (dtype={dtype})")
        tokenizer = AutoTokenizer.from_pretrained(repo_id, cache_dir=os.path.join(cache_dir, "hf"))
        model = AutoModel.from_pretrained(
            repo_id,
            torch_dtype=dtype,
            cache_dir=os.path.join(cache_dir, "hf"),
            trust_remote_code=True,
        ).to(device).eval()

        _qwen3_instance = {"model": model, "tokenizer": tokenizer, "device": device}
        _qwen3_model_tag = model_tag
        return _qwen3_instance


# ── Audio helpers ──────────────────────────────────────────────────────────
def numpy_to_wav_bytes(samples: np.ndarray, sample_rate: int) -> bytes:
    # Normalise to int16 PCM
    if samples.dtype != np.int16:
        peak = max(1e-9, float(np.max(np.abs(samples))))
        samples = (samples / peak * 32767.0).astype(np.int16)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(samples.tobytes())
    return buf.getvalue()


# ── Download helpers ───────────────────────────────────────────────────────
def _download(url: str, dest: str, on_log):
    import urllib.request
    if os.path.isfile(dest) and os.path.getsize(dest) > 1024:
        on_log(f"cached: {os.path.basename(dest)}")
        return
    tmp = dest + ".part"
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    on_log(f"downloading {os.path.basename(dest)} from {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "narraframe/1.0"})
    with urllib.request.urlopen(req, timeout=600) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        read = 0
        last = time.time()
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            f.write(chunk)
            read += len(chunk)
            now = time.time()
            if total and now - last > 1.0:
                pct = read * 100 // total
                on_log(f"  {os.path.basename(dest)}: {pct}% ({read // (1024*1024)} MB)")
                last = now
    os.replace(tmp, dest)
    on_log(f"saved: {os.path.basename(dest)}")


def install_kokoro(cache_dir: str, on_log):
    kdir = os.path.join(cache_dir, "kokoro")
    _download(
        "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx",
        os.path.join(kdir, "kokoro-v1.0.onnx"),
        on_log,
    )
    _download(
        "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin",
        os.path.join(kdir, "voices-v1.0.bin"),
        on_log,
    )


def install_qwen3(cache_dir: str, model_tag: str, on_log):
    # HuggingFace snapshot download pulls every shard.
    try:
        from huggingface_hub import snapshot_download  # type: ignore
    except Exception as e:
        raise RuntimeError("huggingface_hub is required") from e
    repo_id = f"Qwen/Qwen3-TTS-12Hz-{model_tag}-CustomVoice"
    on_log(f"downloading HF snapshot: {repo_id}")
    path = snapshot_download(
        repo_id=repo_id,
        cache_dir=os.path.join(cache_dir, "hf"),
        resume_download=True,
    )
    on_log(f"snapshot ready: {path}")


def install_qwen3_vd(cache_dir: str, on_log):
    """Download the Voice Design model variant (Qwen3-TTS-12Hz-1.7B-VoiceDesign)."""
    try:
        from huggingface_hub import snapshot_download  # type: ignore
    except Exception as e:
        raise RuntimeError("huggingface_hub is required") from e
    repo_id = "Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign"
    on_log(f"downloading HF snapshot: {repo_id}")
    path = snapshot_download(
        repo_id=repo_id,
        cache_dir=os.path.join(cache_dir, "hf"),
        resume_download=True,
    )
    on_log(f"snapshot ready: {path}")


def get_qwen3_vd(cache_dir: str):
    """Load the Qwen3-TTS Voice Design model lazily."""
    global _qwen3_vd_instance
    if _qwen3_vd_instance is not None:
        return _qwen3_vd_instance
    with _lock:
        if _qwen3_vd_instance is not None:
            return _qwen3_vd_instance

        try:
            import torch  # type: ignore
            from transformers import AutoModel, AutoTokenizer  # type: ignore
        except Exception as e:
            raise RuntimeError(
                "Qwen3-TTS requires torch + transformers. Install via /install_model first."
            ) from e

        device = detect_device()
        dtype = torch.float16 if device != "cpu" else torch.float32
        repo_id = "Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign"
        log(f"Loading Qwen3-TTS VoiceDesign on {device} (dtype={dtype})")
        tokenizer = AutoTokenizer.from_pretrained(repo_id, cache_dir=os.path.join(cache_dir, "hf"))
        model = AutoModel.from_pretrained(
            repo_id,
            torch_dtype=dtype,
            cache_dir=os.path.join(cache_dir, "hf"),
            trust_remote_code=True,
        ).to(device).eval()

        _qwen3_vd_instance = {"model": model, "tokenizer": tokenizer, "device": device}
        return _qwen3_vd_instance


# ── FastAPI app ────────────────────────────────────────────────────────────
def build_app(args) -> FastAPI:
    app = FastAPI(title="narraframe-neural-tts")
    cache_dir = os.path.abspath(args.cache_dir)
    os.makedirs(cache_dir, exist_ok=True)
    shared_token = args.token

    def check_auth(x_s2v_token: Optional[str]):
        if x_s2v_token != shared_token:
            raise HTTPException(status_code=401, detail="invalid token")

    @app.get("/health")
    def health(x_s2v_token: Optional[str] = Header(default=None, alias="X-S2V-Token")):
        check_auth(x_s2v_token)
        return {"ok": True, "device": detect_device(), "cache_dir": cache_dir}

    @app.post("/install_model")
    async def install_model(request: Request,
                            x_s2v_token: Optional[str] = Header(default=None, alias="X-S2V-Token")):
        check_auth(x_s2v_token)
        body = await request.json()
        engine = body.get("engine", "kokoro")
        logs: list[str] = []

        def on_log(msg: str):
            log(msg)
            logs.append(msg)

        try:
            if engine == "kokoro":
                install_kokoro(cache_dir, on_log)
            elif engine == "qwen3_06b":
                install_qwen3(cache_dir, "0.6B", on_log)
            elif engine == "qwen3_17b":
                install_qwen3(cache_dir, "1.7B", on_log)
            elif engine == "qwen3_vd":
                install_qwen3_vd(cache_dir, on_log)
            else:
                raise HTTPException(status_code=400, detail=f"unknown engine: {engine}")
            return {"ok": True, "logs": logs}
        except HTTPException:
            raise
        except Exception as e:
            tb = traceback.format_exc()
            log(tb)
            return JSONResponse(status_code=500, content={"ok": False, "error": str(e), "logs": logs})

    @app.post("/tts")
    async def tts(request: Request,
                  x_s2v_token: Optional[str] = Header(default=None, alias="X-S2V-Token")):
        check_auth(x_s2v_token)
        body = await request.json()
        engine = body.get("engine", "kokoro")
        voice = body.get("voice", "af_heart")
        speed = float(body.get("speed", 1.0))
        text = body.get("text", "")
        reference_audio_b64 = body.get("reference_audio_b64")  # optional, for voice cloning
        if not text.strip():
            raise HTTPException(status_code=400, detail="text is empty")

        ref_audio_path = None
        try:
            # Write reference audio to a temp file if provided (used for voice cloning)
            if reference_audio_b64:
                ref_bytes = base64.b64decode(reference_audio_b64)
                suffix = ".wav"
                tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
                try:
                    tmp.write(ref_bytes)
                    tmp.close()
                    ref_audio_path = tmp.name
                except Exception:
                    tmp.close()
                    raise

            if engine == "kokoro":
                k = get_kokoro(cache_dir)
                samples, sr = k.create(text, voice=voice, speed=speed, lang="en-us")
                samples = np.asarray(samples, dtype=np.float32)
                return Response(content=numpy_to_wav_bytes(samples, sr),
                                media_type="audio/wav")

            elif engine in ("qwen3_06b", "qwen3_17b"):
                tag = "0.6B" if engine == "qwen3_06b" else "1.7B"
                q = get_qwen3(cache_dir, tag)
                # The Qwen3-TTS custom model exposes a `.generate_speech` helper via
                # trust_remote_code. Fall back to __call__ if available.
                model = q["model"]
                if ref_audio_path and hasattr(model, "generate_speech"):
                    # Voice cloning: use reference audio instead of a preset voice
                    log(f"Voice cloning with reference audio: {ref_audio_path}")
                    out = model.generate_speech(text=text, ref_audio_path=ref_audio_path, speed=speed)
                elif hasattr(model, "generate_speech"):
                    out = model.generate_speech(text=text, voice=voice, speed=speed)
                else:
                    out = model(text=text, voice=voice, speed=speed)
                # Expected: dict or tuple with 'audio' (np.ndarray) + 'sampling_rate'
                if isinstance(out, dict):
                    samples = np.asarray(out["audio"], dtype=np.float32)
                    sr = int(out.get("sampling_rate", 24000))
                elif isinstance(out, tuple) and len(out) >= 2:
                    samples = np.asarray(out[0], dtype=np.float32)
                    sr = int(out[1])
                else:
                    raise RuntimeError("Qwen3 model returned unrecognised payload")
                return Response(content=numpy_to_wav_bytes(samples, sr),
                                media_type="audio/wav")
            elif engine == "qwen3_vd":
                # Voice Design: voice param = natural-language voice description
                q = get_qwen3_vd(cache_dir)
                model = q["model"]
                voice_description = voice  # e.g. "calm, warm narrator with a British accent"
                log(f"Voice Design synthesis with description: {voice_description!r}")
                if hasattr(model, "generate_speech"):
                    out = model.generate_speech(text=text, voice=voice_description, speed=speed)
                else:
                    out = model(text=text, voice=voice_description, speed=speed)
                if isinstance(out, dict):
                    samples = np.asarray(out["audio"], dtype=np.float32)
                    sr = int(out.get("sampling_rate", 24000))
                elif isinstance(out, tuple) and len(out) >= 2:
                    samples = np.asarray(out[0], dtype=np.float32)
                    sr = int(out[1])
                else:
                    raise RuntimeError("Qwen3-VD model returned unrecognised payload")
                return Response(content=numpy_to_wav_bytes(samples, sr),
                                media_type="audio/wav")
            else:
                raise HTTPException(status_code=400, detail=f"unknown engine: {engine}")
        except HTTPException:
            raise
        except Exception as e:
            tb = traceback.format_exc()
            log(tb)
            raise HTTPException(status_code=500, detail=f"{e}\n{tb}")
        finally:
            if ref_audio_path and os.path.exists(ref_audio_path):
                try:
                    os.unlink(ref_audio_path)
                except Exception:
                    pass

    return app


def pick_free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--token", required=True)
    parser.add_argument("--cache-dir", required=True)
    args = parser.parse_args()

    port = args.port if args.port != 0 else pick_free_port()

    # Announce the actual port on stdout so the parent JVM can read it.
    print(f"PORT={port}", flush=True)

    app = build_app(args)
    uvicorn.run(app, host=args.host, port=port, log_level="warning", access_log=False)


if __name__ == "__main__":
    main()
