# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RinCore is a native Android AI assistant, independently maintained as a fork of RikkaHub 2.4.5.
Built with Jetpack Compose, Kotlin, Material Design 3. Current version: v3.9.2 (single product line).

Before making ANY code change, read `.claude/skills/rincore-project-brief/SKILL.md` and
`.claude/skills/rincore-dev-process/SKILL.md`. All historical knowledge lives in
`docs/ecosystem/` and the `.claude/skills/` knowledge base.

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic (also: service/data/di/ui)
- **ai**: AI transport layer — Provider abstraction, SSE streaming, message model
- **common**: Common utilities and extensions
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools
- **web-ui**: Web management interface (TypeScript, built by pnpm)

## Key Concepts

- **Settings (SSOT)**: `settingsStore.settingsFlow.value` is the single source of truth. Four projections derive from it: UI stats row, list_domains, invoke_tools, Prompt. No bypass reads.
- **Cache discipline**: any field that changes per-turn breaks the prompt prefix cache. Evaluate cache impact before touching system prompt / tools array / message structure.
- **MessageNode**: multi-version messages with selectIndex; edit appends a new version (new id), never mutates.
- **UIMessage**: platform-agnostic message with parts (text/image/reasoning/tool/docs); streaming merge via MessageChunk.
- **Tool system**: layered dynamic injection — 7 framework tools in system prompt, the rest in request tools array. NO full injection (user rule). MCP tools are statically declared from config; connections are lazy (first tool call).
- **Transport**: HTTP/1.1 only (baseline v2.9.8), SSE physical judgment (last line JSON completeness), runtime-adaptive reasoning/body separation.
- **Render**: capsule window render supports all document types (HTML WebView dynamic / PDF PdfRenderer / DOCX / XLSX / CSV / text).

## Workflow (user rules)

1. Bump version third digit + versionCode in the SAME commit as code changes (currently 3.9.x / vc 204).
2. Fix-before-push: local verify → user confirm → push → CI green → artifact direct link. Never push without CI confirmation.
3. Only deliver the rincore-release artifact. No product flavors. WaterHub B-line is deprecated/removed.
4. Output style: no dashes, no parenthetical decorations, concise and direct.
5. Only implement user-named features. Never add unrequested extras.
6. Signature continuity is mandatory (old release.jks; no uninstall/reinstall).
7. Record every change: changelog skill + bug-record skill + decisions skill + 03-修改全记录.
8. Knowledge base active copy: .claude/skills (sync to .agents/skills after edits).

## Common Commands

```bash
./gradlew assembleRelease   # release build (single artifact)
./gradlew assembleDebug     # debug build
```

The CI workflow (`.github/workflows/build.yml`) builds release, uploads `rincore-release` artifact.