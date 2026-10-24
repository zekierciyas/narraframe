# Contributing to Narraframe

Thanks for your interest in Narraframe. This guide explains how to set up the
project, how the code is organised, and how changes get merged and released.

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
Security problems go through the [security policy](SECURITY.md), not public issues.

## Requirements

- JDK 17 or newer (CI uses Temurin 21)
- macOS, Windows or Linux
- About 6 GB of free disk space if you want to download the default local models

You do not need any API keys. Narraframe only uses local AI.

## Run the app

```shell
./run.sh                 # or: ./gradlew :composeApp:run
```

Start with a debugger on port 5005:

```shell
./run-debug.sh           # attach any time
./run-debug.sh --suspend # JVM waits for the debugger
```

IntelliJ IDEA / Android Studio users can use the shared run configurations in
[`.run/`](./.run): **Desktop Run**, **Desktop Debug**, **Desktop Attach
Debugger** and **Tail Logs**.

To see the first-launch setup wizard again without losing your settings, run
`./gradlew :composeApp:run -PforceSetup`, or use **Settings → Storage → Run
setup again**.

Logs are written to `~/.narraframe/logs/app-YYYY-MM-DD.log`.

### Run a generation without the UI

Useful for testing engine changes and measuring speed. It uses your saved
settings and prints the time of every step:

```shell
./gradlew :composeApp:run -PheadlessScript=script.txt -PheadlessOut=/tmp/nf-out
```

Add `-PheadlessSdModel=<model id or .gguf path>` to try another image model
without changing your settings.

### GPU builds

`Hardware.kt` detects the GPU (Metal, `nvidia-smi` for CUDA, the Vulkan loader)
and the downloaders pick the matching llama.cpp and stable-diffusion.cpp build.
Images are rendered by a long-running `sd-server` (`SdServer.kt`) so the model
loads once per session. Settings → Models → Compute switches between
Automatic, GPU and CPU.

## Project layout

```
composeApp/src/
  commonMain/kotlin/com/narraframe/
    models/      data classes (presets, steps, state)
    pipeline/    the text → voice → images → video pipeline and checkpoints
    services/    local engines: llama.cpp, Ollama/LM Studio, sd.cpp, TTS engines
    settings/    AppSettings (persisted with JVM Preferences)
    ui/          Compose screens: setup wizard, storyboard, library, settings
    viewmodel/   StudioViewModel (generation) and SetupController (installs)
  desktopMain/kotlin/com/narraframe/
    platform/    downloaders, model manager, ffmpeg, Python sidecar, file pickers
  desktopMain/resources/python_sidecar/
                 FastAPI server for Kokoro / Qwen3 neural TTS
```

Everything the app downloads lives in the engines & models folder the user
picks during setup (default `~/.narraframe/`, see `DataDir.kt`):

```
<engines folder>/
  bin/          ffmpeg, llama-completion, sd-server, sd (+ shared libraries)
  models/       Stable Diffusion GGUF files
  models/llm/   text model GGUF files
  models/piper/ Piper voices (.onnx + .onnx.json)
  ai-runtime/   portable Python, venv (Kokoro, Qwen3, piper-tts) and model cache
```

Logs always go to `~/.narraframe/logs/`.

## Adding a model to the curated lists

The models shown in the setup wizard are defined in
[`LocalModelManager.kt`](./composeApp/src/desktopMain/kotlin/com/narraframe/platform/LocalModelManager.kt):

- `sdModels` for image models (GGUF files that stable-diffusion.cpp can load)
- `llmModels` for text models (GGUF files that llama.cpp can load)
- `piperVoices` for Piper voices

Add an entry with a stable `id`, a display name, the size in MB and a direct
download URL. Please test the model end to end before you open a PR, and
mention in the PR how long one image or one response took on your machine.

## Making a change

1. Open an issue first for anything larger than a small fix, so we can agree on
   the approach. Small fixes (typos, obvious bugs) can go straight to a PR.
2. Fork the repo and create a branch from `main`, for example
   `feat/voice-preview` or `fix/piper-mac-crash`.
3. Keep each PR focused on one change.
4. Make sure the desktop build compiles:
   ```shell
   ./gradlew :composeApp:compileKotlinDesktop
   ```
5. Run through the manual checklist below for the parts you touched.
6. Open a pull request against `main` and fill in the PR template.

### Pull request rules

- **One topic per PR.** Split unrelated changes, even small ones.
- **Link the issue** it solves (`Closes #123`) or explain the motivation.
- **The Build check workflow must pass.** PRs with a red build are not reviewed.
- **Describe how you tested it**: which OS and chip, which models and voice, and
  the result. For UI changes, add a screenshot or a short GIF
  (`./gradlew :composeApp:run -Pscreenshots=/tmp/shots` renders the main screens).
- **No secrets, models or binaries** in the diff: no API keys, `.gguf`, `.onnx`,
  `.dmg` or generated videos.
- **New dependencies** need a reason in the PR description and a compatible
  license (MIT, Apache-2.0, BSD). Update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
  when you add one, or when you add a model or engine download.
- **User-visible changes** update the README or this guide when needed.
- **Keep the history readable**: Conventional Commit messages (see below). PRs
  are merged with *squash and merge*, so the PR title becomes the commit message.
- **Review**: at least one maintainer approval is required. Please answer review
  comments or mark them resolved; a PR without activity for 30 days may be closed.

### Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add voice preview to the setup wizard
fix: retry sd.cpp download after a network error
docs: explain custom Piper voices
```

Common types are `feat`, `fix`, `docs`, `refactor`, `perf`, `ci` and `chore`.

### Code style

- Kotlin official code style (`kotlin.code.style=official`).
- Match the style of the surrounding code, including comment density.
- Keep platform-specific code in `desktopMain`, behind an `expect` declaration
  in `commonMain/.../platform/Platform.kt`.
- Never commit API keys, model files, `local.properties` or keystores.

## Manual test checklist

There is no automated UI test suite yet. Before you open a PR, check what
applies to your change:

- [ ] First launch: the setup wizard appears, downloads show progress, cancel
      and retry work.
- [ ] Existing install: the wizard shows already-downloaded models as installed.
- [ ] A short script (2–3 paragraphs) produces a video, and scene cards fill in
      while images are generated.
- [ ] Killing the app during generation and reopening it offers **Resume**.
- [ ] The new generation appears in the Library and plays.
- [ ] Settings changes persist after a restart.

## Releases

Maintainers cut a release by pushing a version tag:

```shell
git tag v1.2.0
git push origin v1.2.0
```

The [Release workflow](./.github/workflows/release.yml) builds the macOS (Apple
Silicon) app, packages it as a DMG and publishes a GitHub Release. The release
notes show the DMG download link, its SHA-256, install steps and the
auto-generated changelog. Tags with a suffix, such as `v1.2.0-beta.1`, are marked
as pre-releases. Label your PR (`feature`, `bug`, `models`, `documentation`)
so it lands in the right section of the release notes.

## Reporting security issues

Please do not open a public issue. Use **Report a vulnerability** under the
repository's Security tab.

## License

By contributing, you agree that your contributions are licensed under the
[MIT License](./LICENSE).
