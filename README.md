<div align="center">
  <h1>RinCore</h1>

[![Build](https://img.shields.io/github/actions/workflow/status/desginus/rincore/build.yml?label=build&logo=github)](https://github.com/desginus/rincore/actions)
[![Last commit](https://img.shields.io/github/last-commit/desginus/rincore?logo=git)](https://github.com/desginus/rincore/commits)
[![Version](https://img.shields.io/badge/version-v4.8.116-blue)](https://github.com/desginus/rincore/releases)
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

v4.8.54 → v4.8.116 (newest first):

- **v4.8.116** — Fixed browser tools returning stale URL/title after navigation (now waits for the page to actually finish loading); fixed unreachable pages still reporting success (now returns explicit error codes); fixed typing into React/Vue inputs having no effect (native setter + input/change events); fixed reads racing an in-flight navigation in the same batch; added the browser_wait_for_load tool; screenshots now land in the workspace — sandbox-readable and displayable inline.
- **v4.8.115** — Fixed tool-generated images having inconsistent render-address formats across entry points (one canonical form, one decode point); local image caches now keyed by file mtime, so overwritten images show the new version immediately.
- **v4.8.114** — Fixed long silent waits when the network is down: now shows "network failure, retrying (attempt N)" and an actionable message after final failure (connectivity control / in-app proxy / switch network).
- **v4.8.113** — Fixed tool-generated images not rendering inside chat bubbles (private file:// allowed + unified field + verbatim-echoable image lines); thumbnails now show inside the tool section, tap to zoom.
- **v4.8.112** — Fixed DeepSeek-family outputs being silently treated as "finished" at the halfway cut-off (completion criteria completed for all channels; abnormal endings now continue or retry); fixed occasional empty shares and lost photos; fixed conversation cache being flushed by @ mentions; English proper nouns no longer mistranslated.
- **v4.8.111** — Fixed switching assistants jumping to another conversation (switching now switches only, no navigation).
- **v4.8.110** — Completed the B1–B6 feature list; tool parallelism aligned with Claude Code (bounded concurrency, ordered output).
- **v4.8.109** — Fixed XLSX upload exceptions, the degradation dialog, and cache discontinuity across upload rounds.
- **v4.8.108** — Fixed sub-agent budget false trips.
- **v4.8.107** — Fixed all conversation caches breaking after an image upload (root-caused against upstream).
- **v4.8.106** — Removed the "tool done, still generating…" banner; restored the poke-gate fallback.
- **v4.8.105** — Shorter wait between tool completion and resumed output: earlier pre-poke, zero-wait gate, TTFT breakdown tracing.
- **v4.8.104** — Drawer "feature fold" removed; new quick-overview entry; tap-blank-to-return; minimal-mode tool gate.
- **v4.8.103** — Fixed the video-generation page crash; video flow aligned with image generation.
- **v4.8.102** — Video generation actually works (Google Veo direct); rendering speedups.
- **v4.8.101** — Fixed "file not found" when opening rendered documents (artifact filename now matches the viewer contract); video-gen page aligned with image-gen.
- **v4.8.100** — Document-rendering gaps closed (PPT / real officecli rendering); video generation added.
- **v4.8.99** — Threading reform: unified dispatchers + sandbox process-family isolation pool.
- **v4.8.98** — Fixed long PPTs showing only the left half (true canvas size); document rendering speedups.
- **v4.8.97** — Repo-wide cleanup: 107 unused string keys, 4 unused icons, 3 dead files removed; caching improvements.
- **v4.8.96** — Fixed the MCP stdio connection regression.
- **v4.8.95** — GoogleProvider request headers completed; 30 string keys added across three languages; more precise title-model errors.
- **v4.8.94** — Upstream 2.5.6 port: in-chat chart tool (chart_display); MCP OAuth callback 403 fixed; MCP tool schema $refs expanded.
- **v4.8.93** — Restored the drawer's "AI translate / image generation" entries; stats page ported; two duplicate entries removed.
- **v4.8.92** — "Factory reset" removed (mis-taps revived deleted configs); /@ picker can select whole zones + tool origin labels; sub-agent budget false-zero fixed; per-conversation memory added; local settings gained alarm/workflow switches.
- **v4.8.91** — Upload codes are now time codes (MMDDHHmm, UTC+8, embedded in filenames); in-conversation upload listing; warm connection on app resume (faster first token); workspaces sorted by creation time.
- **v4.8.90** — New /@ tool-matrix picker (3-level drill-down, tap to fill); new upload-code fetch tool; UI tool pool now matches the model tool pool.
- **v4.8.89** — Fixed the "says it saved but didn't" family in the tool matrix (single transactional write path; false success/failure fixed).
- **v4.8.88** — Built-in physics-tutor preset skill; sub-agent token budget (100K/conversation default, trip on exceed); separate notification channel for sub-agents; cancel now truly stops the underlying request.
- **v4.8.87** — Tool-matrix architecture rewrite (identity separated from path; single write entry).
- **v4.8.86** — Streaming render rewrite (MarkdownStream engine), no per-chunk full reparse — smoother long chats.
- **v4.8.85** — Minimal mode added (zero tool injection); tools can be moved back to top-level direct.
- **v4.8.84** — Tool matrix gains model-side management (four actions); fixed dead caches, gate false-blocks, hidden-zone disconnects, load loss.
- **v4.8.83** — Tool matrix fully rewritten: builtin/custom split removed, unified declarative zones; deletes actually delete; config surfaces cut from 9 to 5.
- **v4.8.82** — Compile-log readability: shell output keeps head+tail, errors no longer truncated away (model no longer misreads "no errors").
- **v4.8.81** — One-tap clear for capability modules (MCP / Skills).
- **v4.8.80** — Fixed MCP tool-schema dangling $refs; null parameters fallback.
- **v4.8.79** — "Provider balance" module rolled back; CC empty-plan criteria fixed.
- **v4.8.78** — async scope-extension call fix (compile fix with the version).
- **v4.8.77** — Missing share icon import (compile fix).
- **v4.8.76** — Poke timestamp now held across tool rounds (compile fix).
- **v4.8.75** — ask_user cards exempt from folding; pending-phase direct send fixed.
- **v4.8.74** — runInterruptible context fix (compile fix).
- **v4.8.73** — Live segmented wrapping; CWD-pack render/reset/log fixes.
- **v4.8.72** — Sandbox background jobs (workspace_job); command timeout relaxed to 600s.
- **v4.8.71** — Segmented thinking/tool folding (capsules); OC/CC heartbeat at startup.
- **v4.8.70** — DI layer fix + Markdown incremental-parse type visibility (compile fixes).
- **v4.8.69** — Selective revert of the material-library upgrade (glass-effect investigation).
- **v4.8.68** — Block-level parsing pipeline (speed mechanism, rendering unchanged).
- **v4.8.67** — Render path rolled back to the stable form; fold scope corrected (thinking + tools only).
- **v4.8.66** — List-iteration compatibility fix (compile fix).
- **v4.8.65** — Chat-list composable fix (compile fix).
- **v4.8.64** — MCP stdio branch fixes; material-library migration (compile fixes).
- **v4.8.63** — Three-line spec rewrite: sort persistence, branch ownership, MCP CWD-lock removal, file-parsing engine.
- **v4.8.62** — Accessibility semantics completed (compile fix).
- **v4.8.61** — Workspace tool suspend calls fixed (compile fix).
- **v4.8.60** — Usage page tidy-up ("in use" removed, key reveal); sandbox diagnostics (doccheck / blockscan).
- **v4.8.59** — Ownership snapshot; expand-arrow direction fixed; usage query page rewritten.
- **v4.8.58** — Project-pack smart ordering + expanded vertical list; capsule-window export semantics fixed.
- **v4.8.57** — Capsule-window file-chain CWD resolution; request-fingerprint hardening.
- **v4.8.56** — "Silent truncation" fixed: usage-tail packets no longer count as completion; cut-off output auto-continues.
- **v4.8.55** — Continuation-decision fix (compile fix).
- **v4.8.54** — Error triage extended on OC/CC gateways.

## 🤝 Credits

- **desginus** — design, development and maintenance
- **Claude** — model-side collaborator

## 📄 License

[License](LICENSE)
