# Third-party notices

Narraframe's own source code is licensed under the [MIT License](LICENSE).

The app is built on, and works with, software and AI models made by others. They keep
their own licenses. This page lists them so you know what applies when you use, build or
redistribute Narraframe.

There are two groups:

1. **Bundled** — compiled into the app or shipped inside the DMG.
2. **Downloaded at run time** — not part of this repository or the DMG. The setup wizard
   downloads them from their official sources when you choose them, and runs them as
   separate programs. Their licenses apply to your use of them.

If you redistribute any of the downloaded components yourself, follow their licenses.

---

## 1. Bundled with the app

| Component | License | Source |
|---|---|---|
| Kotlin standard library, Kotlin Coroutines, kotlinx.serialization, kotlinx-datetime | Apache-2.0 | [github.com/JetBrains/kotlin](https://github.com/JetBrains/kotlin) · [github.com/Kotlin](https://github.com/Kotlin) |
| Compose Multiplatform, Material 3, Material Icons | Apache-2.0 | [github.com/JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| AndroidX Lifecycle (multiplatform) | Apache-2.0 | [developer.android.com/jetpack/androidx](https://developer.android.com/jetpack/androidx) |
| Ktor client | Apache-2.0 | [github.com/ktorio/ktor](https://github.com/ktorio/ktor) |
| OkHttp, Okio | Apache-2.0 | [github.com/square/okhttp](https://github.com/square/okhttp) |
| Skiko (Skia for Kotlin) | Apache-2.0; Skia: BSD-3-Clause | [github.com/JetBrains/skiko](https://github.com/JetBrains/skiko) |
| Java runtime in the macOS app (Eclipse Temurin / OpenJDK) | GPL-2.0 with Classpath Exception | [adoptium.net](https://adoptium.net) |
| Python sidecar scripts (`server.py`, `piper_synth.py`) | MIT (part of Narraframe) | this repository |

## 2. Downloaded at run time

### Engines

| Component | License | Source | Used for |
|---|---|---|---|
| llama.cpp | MIT | [github.com/ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | Text model runtime |
| stable-diffusion.cpp | MIT | [github.com/leejet/stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) | Image model runtime |
| FFmpeg (static builds) | GPL-2.0-or-later (builds include x264) | [ffmpeg.org](https://ffmpeg.org) · macOS/Linux builds by [shaka-project/static-ffmpeg-binaries](https://github.com/shaka-project/static-ffmpeg-binaries) · Windows builds by [BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds) | Audio and video encoding |
| CUDA runtime (Windows/Linux CUDA builds only) | NVIDIA CUDA EULA | [docs.nvidia.com/cuda/eula](https://docs.nvidia.com/cuda/eula/) | GPU acceleration |
| Portable Python (python-build-standalone) | MPL-2.0 for the build scripts; CPython: PSF License | [github.com/astral-sh/python-build-standalone](https://github.com/astral-sh/python-build-standalone) | Runs the voice engines |

FFmpeg and Piper are GPL-licensed. Narraframe does not link to them: it starts them as
separate programs, so the GPL does not extend to Narraframe's MIT code.

### Voice engines (installed into the Python runtime with pip)

| Component | License | Source |
|---|---|---|
| Piper (`piper-tts`, includes espeak-ng) | GPL-3.0 | [github.com/OHF-Voice/piper1-gpl](https://github.com/OHF-Voice/piper1-gpl) |
| kokoro-onnx | MIT | [github.com/thewh1teagle/kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx) |
| ONNX Runtime | MIT | [github.com/microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) |
| Qwen3-TTS (`qwen-tts`, only when chosen) | Apache-2.0 | [github.com/QwenLM/Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS) |
| PyTorch (only for Qwen3-TTS) | BSD-3-Clause | [github.com/pytorch/pytorch](https://github.com/pytorch/pytorch) |
| Transformers, Hugging Face Hub | Apache-2.0 | [github.com/huggingface](https://github.com/huggingface) |
| FastAPI | MIT | [github.com/fastapi/fastapi](https://github.com/fastapi/fastapi) |
| Uvicorn | BSD-3-Clause | [github.com/encode/uvicorn](https://github.com/encode/uvicorn) |
| NumPy | BSD-3-Clause | [github.com/numpy/numpy](https://github.com/numpy/numpy) |

### AI models

| Model | License | Source |
|---|---|---|
| DreamShaper 8 LCM (GGUF) | CreativeML Open RAIL-M | [huggingface.co/haven-ai-companion/dreamshaper8-lcm-gguf](https://huggingface.co/haven-ai-companion/dreamshaper8-lcm-gguf) |
| Stable Diffusion 1.5 (GGUF) | CreativeML Open RAIL-M | [huggingface.co/gpustack/stable-diffusion-v1-5-GGUF](https://huggingface.co/gpustack/stable-diffusion-v1-5-GGUF) |
| Stable Diffusion 2.1 (GGUF) | CreativeML Open RAIL++-M | [huggingface.co/jiaowobaba02/stable-diffusion-v2-1-GGUF](https://huggingface.co/jiaowobaba02/stable-diffusion-v2-1-GGUF) |
| Llama 3.2 1B / 3B Instruct (GGUF) | Llama 3.2 Community License | [huggingface.co/bartowski](https://huggingface.co/bartowski) · [llama.com/llama3_2/license](https://www.llama.com/llama3_2/license/) |
| Phi-3.5 Mini Instruct (GGUF) | MIT | [huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF](https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF) |
| Kokoro-82M | Apache-2.0 | [huggingface.co/hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) |
| Qwen3-TTS models | Apache-2.0 | [huggingface.co/Qwen](https://huggingface.co/Qwen) |
| Piper voices | Per voice (see each voice's model card) | [huggingface.co/rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) |

Notes on model licenses:

- **CreativeML Open RAIL-M / RAIL++-M** allow free use, including commercial use, but forbid
  certain harmful uses. The use restrictions pass on to anyone you share generated content
  or the model with.
- **Llama 3.2 Community License**: if you build and distribute a product with Llama, the
  license asks you to show "Built with Llama".
- Models you load from your own `.gguf` or `.onnx` files keep their own licenses.

---

This list is provided for convenience and may be incomplete. Each project's own license file
is authoritative. If you spot a mistake, please open an issue or a pull request.
