# Security policy

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Use GitHub's private reporting instead: open the repository's **Security** tab and choose
**Report a vulnerability**. Include:

- what the problem is and what an attacker could do with it,
- steps to reproduce, or a proof of concept,
- the Narraframe version, macOS version and chip.

You will get a first answer within 7 days. Once a fix is released, we credit you in the
release notes unless you prefer to stay anonymous.

## Scope

Narraframe runs entirely on your computer and does not send your scripts or videos
anywhere. Relevant reports include, for example:

- a way for a downloaded engine, model or file to run unexpected code,
- leaking local files or settings to another process or the network,
- downloads that can be tampered with.

Vulnerabilities in third-party engines and models (llama.cpp, stable-diffusion.cpp, FFmpeg,
Piper, Python packages) should be reported to those projects; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
