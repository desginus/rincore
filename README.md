<div align="center">
  <h1>RinCore</h1>

[![Build](https://img.shields.io/github/actions/workflow/status/desginus/rincore/build.yml?label=build&logo=github)](https://github.com/desginus/rincore/actions)
[![Last commit](https://img.shields.io/github/last-commit/desginus/rincore?logo=git)](https://github.com/desginus/rincore/commits)
[![Version](https://img.shields.io/badge/version-v3.10.0-blue)](https://github.com/desginus/rincore/releases)
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

## 📋 Changelog

Recent major releases (newest first):

- **v4.8.116** — Browser tools rewritten end-to-end: navigation settle (load + network idle), visible failures (ERR/timeout + throw_on_error), native-setter typing for React/Vue inputs, page-state semantics, parallel-call mutual exclusion, screenshots unified into the workspace (sandbox-readable + inline renderable)
- **v4.8.115** — Tool-image render addresses unified: one canonical form (percent-encoded file://), one decode point (Coil claim layer), one producer/extractor source
- **v4.8.114** — Network-failure visibility: "retrying (attempt N)" status + actionable terminal message (connectivity control / proxy / switch network)
- **v4.8.113** — Tool images visible inside the chat bubble: private file:// allowed through the markdown XSS gate + unified render_urls + render_markdown restatement + tool-section thumbnails
- **v4.8.112** — Silent-interruption completion gate across all five channels + share/photo hardening + /@ cache root fix + English-drift reinforcement
- **v4.8.111** — Assistant switching stays put (zero navigation), final fix
- **v4.8.110** — Feature batch (B1–B6) + tool parallelism aligned with Claude Code
- **v4.8.109** — XLSX upload exception + degradation dialog + cache continuity root fix
- **v4.8.108** — Sub-agent budget false-trip root fix
- **v4.8.107** — "Image upload breaks all caches" root fix (audited against upstream)
- **v4.8.106** — Removed the "tool done, still generating…" banner + poke-gate fallback
- **v4.8.105** — Seamless tool→output continuation: pre-poke + zero-wait gate + TTFT breakdown tracing
- **v4.8.104** — Drawer cleanup + quick-overview page entry + tap-blank-to-return + minimal-mode tool gate
- **v4.8.103** — VideoGen crash fix + video generation aligned to the image-generation flow
- **v4.8.102** — Video generation for real (Google Veo direct) + rendering speedups
- **v4.8.101** — Render-chain fix (officecli artifact contract) + video-gen page aligned with image-gen
- **v4.8.100** — Document-render gaps closed (real officecli rendering) + video generation
- **v4.8.99** — Threading reform: AppDispatchers single source of truth + sandbox process-family isolation pool
- **v4.8.98** — Real document rendering + speedups + four-in-one batch (true PPT canvas size)
- **v4.8.97** — Whole-repo deep optimization batch (dead-code cleanup + caching)
- **v4.8.96** — MCP stdio connection regression fix
- **v4.8.95** — GoogleProvider headers + three-language string coverage + precise title-model errors
- **v4.8.94** — Upstream 2.5.6 port: chart_display tool chain + MCP OAuth/$ref fixes
- **v4.8.93** — Drawer entries restored + stats page ported + duplicate entries removed
- **v4.8.92** — Factory-reset removed (mis-tap risk) + /@ zone picker & origin labels + per-conversation memory + sub-agent budget review
- **v4.8.91** — Upload codes as time codes (UTC+8, in filenames) + in-conversation upload listing + warm connection on resume
- **v4.8.90** — /@ tool-matrix picker (3-level drill-down) + upload-code fetch tool + UI/model tool-pool parity
- **v4.8.89** — Tool-matrix write chain rewritten: single transactional write entry, false success/failure fixed
- **v4.8.88** — Preset skills (physics-tutor) + sub-agent budget + notification split + real generation stop
- **v4.8.87** — Tool-matrix architecture rewrite: identity separated from path + single write entry
- **v4.8.86** — Streaming render/cache rewrite: MarkdownStream engine, no per-chunk full reparse
- **v4.8.85** — Minimal mode (zero tool injection) + free tool re-assignment
- **v4.8.84** — Tool matrix: model-side management (manage_zone) + four fixes
- **v4.8.83** — Tool matrix fully rewritten: builtin/custom split removed, unified declarative zones
- **v4.8.82** — Compile-log readability: shell keeps head+tail, errors no longer truncated away
- **v4.8.81** — One-tap clear for capability modules (MCP / Skills)
- **v4.8.80** — MCP schema dangling-$ref fix + parameters null fallback
- **v4.8.75** — ask_user fold exemption + pending-phase direct-send fix
- **v4.8.72** — Sandbox background-job primitive (workspace_job) + timeout relax (600s/700s)
- **v4.8.67** — Render path rolled back (stable v4.8.64 form) + fold-range correction

## 🤝 Credits

- **desginus** — design, development and maintenance
- **Claude** — model-side collaborator

## 📄 License

[License](LICENSE)
