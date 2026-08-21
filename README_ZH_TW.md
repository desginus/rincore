<div align="center">
  <h1>RinCore</h1>

[![Build](https://img.shields.io/github/actions/workflow/status/desginus/rincore/build.yml?label=構建&logo=github)](https://github.com/desginus/rincore/actions)
[![Last commit](https://img.shields.io/github/last-commit/desginus/rincore?logo=git)](https://github.com/desginus/rincore/commits)
[![Version](https://img.shields.io/badge/版本-v3.8.29-blue)](https://github.com/desginus/rincore/releases)
[![License](https://img.shields.io/badge/許可-segmented_dual-cyan)](LICENSE)

Rika 系原生 Android 大模型聊天客戶端的深度改造版。以低成本呼叫、高可控性、真正可擴展為目標，絕對不只是給原版套了一層皮。

> RinCore 作為 [RikkaHub](https://github.com/re-ovo/rikkahub) 的獨立分支持續維護。它繼承了原版的理念（原生 Android、Material You、多 Provider），再往前一步：重構整體架構、修復系統性 Bug、加入工具域分層管理、MCP STDIO 支援、外掛系統與智慧壓縮。

[English](README.md) | [简体中文](README_ZH_CN.md) | 繁體中文

</div>

## 🚀 下載

RinCore 每次推送都會建置。兩種方式取得最新 APK：

1. **GitHub Releases（推薦）** —— `nightly` 預發佈版每晚重新發佈，始終指向最新每日建置：

   🔗 <https://github.com/desginus/rincore/releases>

2. **GitHub Actions 建置產物** —— 每次 CI 通過都會產出即時 APK。打開最新工作流執行，展開 `rincore-release` 產物即可直接下載：

   🔗 <https://github.com/desginus/rincore/actions>

   產物直鏈（建置通過後即可用）：
   `https://github.com/desginus/rincore/actions/runs/<run-id>/artifacts/<artifact-id>`

下載 APK 直接安裝即可，無需任何商店。

## ✨ 為什麼選 RinCore

我們相對上游做了什麼：

- **整體架構優化** —— 模組職責重新梳理，UI、域名列表、工具注入、Prompt 全部派生自同一個資訊源頭（SSOT），任何視圖只讀同一份資料，杜絕層層漂移。

- **大量 Bug 修復** —— 流式中斷、靜默失敗、冷啟動回退、設定不落盤、介面抽動等，全部從根因層級修復，而非打補丁。

- **完整 MCP 支援，含 STDIO** —— STDIO 伺服器在沙箱工作區內以程序方式啟動（裝置端無需 Python 依賴）；工具宣告靜態化、連線狀態可見、OAuth 令牌自動刷新。

- **外掛系統** —— 從 `ecosystem/plugins` 安裝/卸載外掛，外掛技能與 MCP 橋接清晰分離。

- **工具域分類分層管理** —— 工具按域歸類，透過 `invoke_tools` 按需載入，而非把全部工具塞進每個請求。冷啟動 token 從全量注入的 100K+ 降至約 6K，呼叫成本巨幅下降，同時顯著提升 Prompt 快取命中率。

- **更智慧的壓縮模式** —— 壓縮不再按固定條數截斷。邊界按對話輪 + token 數（60%）定位，四捨五入到最近整輪；絕不壓縮剛發出的內容，且按下壓縮必然真正壓縮到東西。

- **延遲自動回覆** —— 開啟 `deferAutoReply` 後，發訊息先排隊、不立即觸發模型回覆，你的訊息不會發到一半就被打斷，回覆時機由你掌握。

- **多個小功能** —— 金鑰用量統計頁（多金鑰卡片 + 剩餘時間倒數）、斷流自動重試（快速失敗）、TCP+TLS 連線預熱降低首字延遲、上下文壓縮位點管理（最多 3 個，可檢視摘要可恢復）、工具對照一致性校驗、記憶 ID 時間戳化、崩潰日誌持久化、液態玻璃輸入框、訊息多版本編輯。

## 🎨 核心特性（繼承並保留）

- Material You 設計 + 深色模式
- 多 Provider 支援：自訂 API / 位址 / 模型（相容 OpenAI、Google、Anthropic 系）
- 多模態輸入：圖片、文字、PDF、DOCX 等
- Workspace：proot 的 Linux 代理環境
- Web 多端使用
- MCP 支援（HTTP / SSE / STDIO）
- Markdown 渲染：程式碼高亮、LaTeX 公式、表格、Mermaid
- 訊息分支
- 多搜尋引擎（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity 等）
- Prompt 變數、AI 翻譯
- 設定 QR Code 匯入匯出
- 助手客製、類 ChatGPT 記憶
- 自訂 HTTP 請求頭與請求體
- Silly Tavern 角色卡匯入

## 🛠️ 建置

使用 [Android Studio](https://developer.android.com/studio) 開發。

技術棧：

- [Kotlin](https://kotlinlang.org/) —— 開發語言
- [Jetpack Compose](https://developer.android.com/jetpack/compose) —— UI
- [Koin](https://insert-koin.io/) —— 依賴注入
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) —— 偏好儲存
- [Room](https://developer.android.com/training/data-storage/room) —— 資料庫
- [Coil](https://coil-kt.github.io/coil/) —— 圖片載入
- [Material You](https://m3.material.io/) —— 設計
- [OkHttp](https://square.github.io/okhttp/) —— 網路
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) —— 序列化

> [!TIP]
> 本地建置需要 `app` 目錄下存在 `google-services.json`。

## 🙋 參與貢獻

RinCore 是獨立開源專案，歡迎提 Issue 與 PR。大型變更請先開 Issue 討論方案。

## 📄 許可與致謝

- [許可協議](LICENSE)
- 基於 [RikkaHub](https://github.com/re-ovo/rikkahub)（作者 re-ovo 及貢獻者）二次開發。感謝 Rika 系客戶端為我們打下的基礎。
