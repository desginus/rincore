<div align="center">
  <h1>RinCore</h1>

[![Build](https://img.shields.io/github/actions/workflow/status/desginus/rincore/build.yml?label=build&logo=github)](https://github.com/desginus/rincore/actions)
[![Last commit](https://img.shields.io/github/last-commit/desginus/rincore?logo=git)](https://github.com/desginus/rincore/commits)
[![Version](https://img.shields.io/badge/version-v3.9.2-blue)](https://github.com/desginus/rincore/releases)
[![License](https://img.shields.io/badge/license-segmented_dual-cyan)](LICENSE)

**A real, self-contained AI assistant on your phone.** Not a wrapper — a rebuilt engine with six
weeks of daily-driven iteration and 400+ releases behind it.

RinCore is an independently maintained fork of [RikkaHub](https://github.com/re-ovo/rikkahub).
It keeps the Rika-series philosophy — native Android, Material You, multi-provider — then redoes
the engine underneath to be cheap, stable and controllable, and fills it with device-level agent
capabilities.

> **Measured on a real device:** with 400+ tools loaded and a very long character-preset context,
> RinCore cold-starts at ~10K tokens. (The full-injection era took 70K–100K+.)

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English

</div>

## 🚀 Download

RinCore builds on every push. Two ways to get the latest APK:

1. **GitHub Releases (recommended)** — the `nightly` prerelease is re-published daily and always
   points to the latest build: <https://github.com/desginus/rincore/releases>
2. **GitHub Actions artifacts** — every green build produces an instant APK. Open the latest run,
   expand `rincore-release`, and download: <https://github.com/desginus/rincore/actions>

Install the APK directly. No store required.

## 🔁 Moving fast, on purpose

RinCore is updated relentlessly and every release earns its version number:

- **~6 weeks of history** (Jul 2026 → now), **400+ versioned releases**, **540+ commits in the
  last 30 days**.
- Each version is a real step: every design decision and modification comes from daily hands-on
  usage, not from theory. We log what we felt, we measure what we changed, and we ship the fix.
- The changelog is maintained and public. There are no placeholder or vanity version bumps.

## 🏗️ This is a rebuild, not an upgrade

RinCore is not "RikkaHub plus some features". The core was rebuilt in place, driven by how the
app is actually used day to day:

- **One source of truth** — UI, domain list, tool injection and prompts all derive from a single
  settings source; every view reads the same data, nothing drifts out of sync.
- **Network & cache, rewritten from failure data** — SSE auto-retry with exponential backoff, a
  watchdog for hangs, HTTP/1.1-only transport (the real fix for weak-network failures),
  connection pre-warm to cut first-word latency, stream resume on drop, and a cache fingerprint
  tool that reports *exactly* where DeepSeek's prompt-cache broke. Interruptions and silent
  kills are fixed at the root.
- **Smarter compression, rebuilt** — compression no longer cuts a fixed number of messages. The
  boundary is located by conversation rounds and token count (60%), rounded to the nearest whole
  round. It never compresses what you just sent, and it always actually compresses something.
- **Cost by design** — with a layered tool-domain system, cold-start dropped from 100K+ to ~10K
  while the request prefix stays stable, so provider prompt-cache keeps hitting.

## ✨ Why RinCore — engine-level changes

- **Layered tool domains (the cost killer)** — 400+ tools are grouped into domains and delivered
  on demand through `invoke_tools`, instead of dumping everything into every request. The whole
  domain system is manageable visually: edit domains, move tools, see per-domain counts, and run
  a consistency checker that flags ghosts and contradictions.
- **Full MCP, including STDIO** — HTTP / Streamable HTTP / SSE / **STDIO**. STDIO servers run as
  processes inside the sandboxed workspace (no Python dependency on-device); declarations are
  static so connection noise can't break your cache prefix; OAuth refresh is transparent.
- **Plugins & skills** — install/uninstall plugins from `ecosystem/plugins`; skills live as
  first-class tools (`skill__name`). A small set of approved framework tools + user-exempted tools
  is all that ever hits the top level — everything else stays behind `invoke_tools`.
- **In-phone agent, with a hard safety line** — the phone agents that matter are nearly fully
  ported and run *on-device*: proot Linux workspace, file manager (batch/archive/read/write/
  download), browser, media playback, alarms, calendar, battery, real location + map, clipboard,
  TTS, notifications, screen-keep-awake, cron jobs, interactive streaming output, and Ask-You
  confirmation. **High-risk screen control (tapping/swiping for you) is deliberately not
  supported** — the agent works with your data and files, not over your screen.
- **OpenCode / OpenCode Zen tuning** — watchdogs, `[DONE]`-less streaming completion detection,
  model definitions, and reasoner-mode alignment make it work where upstream struggled.
- **Capacity, visible** — a quota dashboard with per-API-key cards, live balance, remaining-time
  countdown and precise reset windows, so you always know where your quota stands.
- **One app, many jobs** — with normal configuration, RinCore also covers image generation,
  data analysis in the workspace, document generation & export, and a learning-assistant mode —
  alongside the core chat experience.
- **Dozen-level quality-of-life fixes** — deferred auto-reply (queue your message, the model
  won't interrupt), multi-version message editing, timestamp-based memory IDs, quota-aware
  scheduling, liquid-glass input, reproducible crash logs, and more.

## 🎨 Feature lineage

**Inherited from the original RikkaHub (kept & working):**

Material You + dark mode · multi-provider (custom API / base URL / models, OpenAI/Anthropic/
Google compatible) · multimodal input (image, text, PDF, DOCX) · proot Linux workspace · web
access · MCP · Markdown (code highlight, LaTeX, tables, Mermaid) · message branching ·
multi-engine search (Exa, Tavily, Zhipu, LinkUp, Brave, Perplexity, …) · prompt variables · QR
provider import/export · agent customization · ChatGPT-like memory · AI translation · custom
HTTP headers/bodies · Silly Tavern character-card import.

**Brought in from the agent-line (running on-device, minus high-risk items):**

device tools — alarms, calendar, battery, location & map, media playback & scanning,
notifications, clipboard, TTS, screen-keep-awake, system intents · file manager with batch &
archive · scheduled cron jobs · in-app browser / web fetch · cross-conversation reading ·
memory with timestamp IDs · interactive streaming tool output.

*(Screen control — tapping/swiping/typing for the user — is the one line we will not cross.)*

## 🛠️ Building

Developed with [Android Studio](https://developer.android.com/studio).

Stack: [Kotlin](https://kotlinlang.org/) · [Jetpack Compose](https://developer.android.com/jetpack/compose) ·
[Koin](https://insert-koin.io/) · [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) ·
[Room](https://developer.android.com/training/data-storage/room) ·
[Coil](https://coil-kt.github.io/coil/) · [Material You](https://m3.material.io/) ·
[OkHttp](https://square.github.io/okhttp/) · [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)

> [!TIP]
> A `google-services.json` in the `app` folder is required to build.

## 🔧 Maintenance

RinCore is actively maintained. Issues and PRs are welcome — every problem reported becomes a
changelog entry and a regression guard. Optimizations keep coming.

## 🤝 Credits

- Built on [RikkaHub](https://github.com/re-ovo/rikkahub) by re-ovo & contributors — the
  foundation of this project.
- If you like the idea but want a different take, these are excellent projects too:
  - **RikkaHub** — the original, same-type client
  - **RikkaHub Agent** — a strengthened, agent-focused build
  - **Orange Chat** — an optimized build focused on AI companionship

## 📄 License

[License](LICENSE)
