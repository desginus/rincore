<div align="center">
  <h1>RinCore</h1>

[![Build](https://img.shields.io/github/actions/workflow/status/desginus/rincore/build.yml?label=構建&logo=github)](https://github.com/desginus/rincore/actions)
[![Last commit](https://img.shields.io/github/last-commit/desginus/rincore?logo=git)](https://github.com/desginus/rincore/commits)
[![Version](https://img.shields.io/badge/版本-v3.8.29-blue)](https://github.com/desginus/rincore/releases)
[![License](https://img.shields.io/badge/許可-segmented_dual-cyan)](LICENSE)

**手機上一個真正全面的 AI 助手。** 不是套殼，是一台重新打造過的引擎——背後是六週的高強度實測迭代與 400+ 個版本。

RinCore 是 [RikkaHub](https://github.com/re-ovo/rikkahub) 的獨立分支持續維護。它繼承了 Rika 系一貫的基因（原生 Android、Material You、多 Provider），然後把底層的引擎重新做了一遍——更便宜、更穩定、更可控，並注入設備級的 Agent 能力。

> **真機實測：** 400+ 工具載入、疊加超長角色預設上下文，冷啟動只需約 10K token。（全量注入時代是 70K~100K+。）

[English](README.md) | [简体中文](README_ZH_CN.md) | 繁體中文

</div>

## 🚀 下載

RinCore 每次推送都會建置。兩種方式取得最新 APK：

1. **GitHub Releases（推薦）** —— `nightly` 預發佈版每晚重新發佈，始終指向最新建置：<https://github.com/desginus/rincore/releases>
2. **GitHub Actions 產物** —— 每次 CI 通過都會產出即時 APK，打開最新執行、展開 `rincore-release` 即可下載：<https://github.com/desginus/rincore/actions>

下載後直接安裝，無需任何商店。

## 🔁 更新節奏：快，且每一步都是真的

RinCore 的更新是持續的，每個版本號都承載了實打實的改進：

- 專案歷史約 **6 週**（2026 年 7 月起），累計 **400+ 個版本發佈**，近 30 天 **540+ 次提交**。
- 每個版本都是真實的一步：每一個設計與修改都來自**日常使用的實測**——用著不舒服就記錄，量出問題就改，改完就發版。
- 更新日誌公開可查，沒有空轉版本號、沒有為發而發。

## 🏗️ 這是一次重建，不是加功能

RinCore 不是「RikkaHub 加了些功能」。核心是被日常使用推著重做出來的：

- **單一信源（SSOT）** —— UI、域名列表、工具注入、Prompt 全部派生自同一個設定源，任何視圖只讀同一份資料，逐層漂移在機制上不可能發生。
- **網路與快取，按失敗資料重寫** —— SSE 斷流指數退避重試、掛起看門狗、純 HTTP/1.1 傳輸（弱網失敗的真根因）、TCP+TLS 連線預熱壓首字延遲、斷流續傳、快取指紋診斷（精確告訴你 DeepSeek 前綴快取在哪一環斷了）。中斷與靜默被殺，全部從根上修。
- **壓縮機制重做** —— 不再按固定條數截斷。保留邊界按對話輪 + token 數（60%）定位、四捨五入到最近的整輪；絕不壓縮你剛發出的內容，且按下壓縮必然真正壓縮到東西。
- **成本從設計上降低** —— 工具域分層使冷啟動從 100K+ 降到約 10K，請求前綴保持穩定，Provider 的 Prompt 快取持續命中。

## ✨ 為什麼選 RinCore —— 引擎級的差異

- **工具域分類分層管理（成本殺手）** —— 400+ 工具按域歸類，經 `invoke_tools` 按需載入，不再把全部工具塞進每個請求。域系統全程可視化：改域、移動工具、按域計數、一致性對照（幽靈域/矛盾一鍵揪出）。
- **完整 MCP，含 STDIO** —— HTTP / Streamable HTTP / SSE / **STDIO** 全支援。STDIO 伺服器在沙箱工作區以程序方式啟動（設備端無需 Python）；工具宣告靜態化，連線波動不影響請求前綴與快取；OAuth 令牌透明刷新。
- **外掛與技能** —— `ecosystem/plugins` 即裝即卸；技能以一級工具存在（`skill__名稱`）。請求頂層只放行批准的框架工具與使用者豁免工具，其餘一律藏在 `invoke_tools` 之內。
- **手機 Agent，劃好安全線** —— 值得搬的 Agent 能力幾乎全部移植並在設備端執行：proot Linux 工作區、檔案管理（批次/歸檔/讀寫/下載）、瀏覽器、媒體播放、鬧鐘、行事曆、電量、真實定位 + 地圖、剪貼簿、TTS、通知、螢幕常亮、定時任務、互動式串流輸出、需要時向你確認。**高風險行為——替你操作螢幕（點擊/滑動）——明確不做**。Agent 處理的是你的資料與檔案，不是你的螢幕。
- **OpenCode / OpenCode Zen 專項適配** —— 看門狗、無 `[DONE]` 串流完成判定、模型定義、推理模式對齊，上游打不順的這裡能跑。
- **容量隨時可見** —— 用量面板：多金鑰卡片、即時餘額、剩餘時間倒數、精確重置時段，額度心裡有數。同時提供便捷的容量查詢入口。
- **一個軟體，多份工作** —— 常規設定之後，圖片生成、工作區資料分析、文件生成與匯出、學習助手模式，與核心聊天體驗一起扛。
- **一整打體驗級修復** —— 延遲自動回覆（訊息先排隊，模型不會打斷你輸入）、訊息多版本編輯、記憶 ID 時間戳化、時間感知排程、液態玻璃輸入、可重現崩潰日誌，等等。

## 🎨 能力傳承

**繼承自原版 RikkaHub（保留且可用）：**

Material You + 深色模式 · 多 Provider（自訂 API/位址/模型，OpenAI/Anthropic/Google 相容）· 多模態輸入（圖片/文字/PDF/DOCX）· proot Linux 工作區 · Web 多端 · MCP · Markdown（程式碼高亮/LaTeX/表格/Mermaid）· 訊息分支 · 多引擎搜尋（Exa/Tavily/Zhipu/LinkUp/Brave/Perplexity 等）· Prompt 變數 · 設定 QR Code 匯入匯出 · 助手客製 · 類 ChatGPT 記憶 · AI 翻譯 · 自訂請求頭與請求體 · Silly Tavern 角色卡匯入。

**自 Agent 系移植（設備端執行，去掉高風險項）：**

設備工具——鬧鐘、行事曆、電量、定位與地圖、媒體播放與掃描、通知、剪貼簿、TTS、螢幕常亮、系統意圖 · 檔案管理（批次與歸檔）· 定時任務 · 內建瀏覽器/網頁抓取 · 跨對話讀取 · 時間戳記憶 · 互動式串流工具輸出。

*（替你點擊/滑動螢幕的螢幕控制，是我們不越的那條線。）*

## 🛠️ 建置

使用 [Android Studio](https://developer.android.com/studio) 開發。

技術棧： [Kotlin](https://kotlinlang.org/) · [Jetpack Compose](https://developer.android.com/jetpack/compose) · [Koin](https://insert-koin.io/) · [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) · [Room](https://developer.android.com/training/data-storage/room) · [Coil](https://coil-kt.github.io/coil/) · [Material You](https://m3.material.io/) · [OkHttp](https://square.github.io/okhttp/) · [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)

> [!TIP]
> 本地建置需要 `app` 目錄下存在 `google-services.json`。

## 🔧 維護與支援

RinCore 處於活躍維護中，歡迎 Issue 與 PR。每一個被報告的問題都會進入更新日誌並轉化為回歸防線。優化與適配持續進行。

## 🤝 致謝

- 基於 [RikkaHub](https://github.com/re-ovo/rikkahub)（作者 re-ovo 及貢獻者）二次開發，感謝 Rika 系客戶端為這個專案打下的基礎。
- 如果你喜歡這類產品但想要其他呈現方式，這些同樣是非常優秀的軟體：
  - **RikkaHub**（原版）—— 同類型客戶端
  - **RikkaHub Agent** —— Agent 能力強化版
  - **Orange Chat** —— AI 伴侶向優化版

## 📄 許可

[許可協議](LICENSE)