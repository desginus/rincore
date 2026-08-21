<div align="center">
  <h1>RinCore</h1>

[![Build](https://img.shields.io/github/actions/workflow/status/desginus/rincore/build.yml?label=build&logo=github)](https://github.com/desginus/rincore/actions)
[![Last commit](https://img.shields.io/github/last-commit/desginus/rincore?logo=git)](https://github.com/desginus/rincore/commits)
[![Version](https://img.shields.io/badge/version-v3.8.29-blue)](https://github.com/desginus/rincore/releases)
[![License](https://img.shields.io/badge/license-segmented_dual-cyan)](LICENSE)

A deep-modified build of the Rikka-line native Android LLM client. Built for low token cost,
high controllability, and real extensibility — not just another skin on top of the original.

> RinCore is maintained as a standalone fork of [RikkaHub](https://github.com/re-ovo/rikkahub).
> It inherits the original philosophy (native Android, Material You, multi-provider), then goes
> further: rebuilding the architecture, fixing systemic bugs, and adding a fully layered tool
> domain system, MCP STDIO, a plugin system, and smart compression.

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English

</div>

## 🚀 Download

RinCore builds on every push. Two ways to get the latest APK:

1. **GitHub Releases (recommended)** — the `nightly` prerelease is re-published daily and always
   points to the latest daily build:

   🔗 <https://github.com/desginus/rincore/releases>

2. **GitHub Actions artifacts** — every green build produces an instant APK. Open the latest
   workflow run, expand the `rincore-release` artifact, and download the APK directly:

   🔗 <https://github.com/desginus/rincore/actions>

   Direct artifact link (available right after a green build):
   `https://github.com/desginus/rincore/actions/runs/<run-id>/artifacts/<artifact-id>`

Install the APK directly; no extra store is required.

## ✨ Why RinCore

What makes RinCore different from the upstream:

- **Architecture rework** — modules are refactored and responsibilities are separated; one
  single source of truth (SSOT) drives the UI, the domain list, tool injection, and prompts, so
  every view reads the same data and nothing drifts out of sync.

- **A huge number of bug fixes** — streaming interruptions, silent failures, cold-start
  regressions, settings not persisting, UI glitches… fixed at the root, not patched.

- **Full MCP support, including STDIO** — STDIO servers launch in the sandboxed workspace (no
  Python dependency on-device); MCP tool declarations are static and connection-state visible;
  OAuth token refresh is handled transparently.

- **Plugin system** — install/uninstall plugins from `ecosystem/plugins`, with plugin skills and
  manual MCP bridging kept cleanly separated.

- **Tool domain classification & layered injection** — tools are grouped into domains and
  delivered on demand through `invoke_tools`, instead of dumping everything into every request.
  Cold-start token cost dropped from 100K+ (full injection) to ~6K, dramatically cutting API
  spend while improving prompt-cache hit rate.

- **Smarter context compression** — compression no longer cuts a fixed number of messages.
  The boundary is located by conversation rounds and token count (60%), rounded to the nearest
  whole round. It never compresses the just-sent content and always really compresses something.

- **Deferred auto-reply** — switch on `deferAutoReply` and sending a message queues it without
  immediately triggering the model, so you can finish typing faster than the reply and the model
  never interrupts mid-thought.

- **Many small refinements** — usage quota dashboard with per-key cards and reset countdown,
  SSE auto-retry with fast fail, TCP+TLS connection pre-warm to cut TTFB, compress retention
  points (up to 3, view/resume), tool-consistency checker, timestamp-based memory IDs, OAuth
  refresh, multi-version message editing, liquid-glass input blur, crash log persistence.

## 🎨 Core features (inherited & kept)

- Material You design with dark mode
- Multiple provider support: custom API / base URL / models (OpenAI, Google, Anthropic compatible)
- Multimodal input: image, text, PDF, DOCX (and more)
- Workspace: a proot-based Linux agent environment
- Web access for multi-platform use
- MCP support (HTTP / SSE / STDIO)
- Markdown rendering with code highlight, LaTeX, tables, and Mermaid
- Message branching
- Search providers (Exa, Tavily, Zhipu, LinkUp, Brave, Perplexity, …)
- Prompt variables & AI translation
- QR-code export/import for providers
- Agent customization & ChatGPT-like memory
- Custom HTTP headers and request bodies
- Silly Tavern character card import

## 🛠️ Building

Developed with [Android Studio](https://developer.android.com/studio).

Technology stack:

- [Kotlin](https://kotlinlang.org/) — language
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI
- [Koin](https://insert-koin.io/) — dependency injection
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) — settings
- [Room](https://developer.android.com/training/data-storage/room) — database
- [Coil](https://coil-kt.github.io/coil/) — image loading
- [Material You](https://m3.material.io/) — design
- [OkHttp](https://square.github.io/okhttp/) — networking
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) — JSON

> [!TIP]
> You need a `google-services.json` file at the `app` folder to build the app.

## 🙋 Contributing

RinCore is an independent open-source project. Issues and PRs are welcome. If you want to make a
major change, please open an issue first to discuss the design.

## 📄 License & credits

- [License](LICENSE)
- Based on [RikkaHub](https://github.com/re-ovo/rikkahub) by re-ovo & contributors. We are
  grateful for the original Rika-series client this project is built on.
