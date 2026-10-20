# 上游 3.1.0 · 第二批存档（已放入 · **未激活**）

> 状态：**纯源码存档，不参与编译、不接线、不影响现有行为。**
> 目的：按用户 2026-10-20 指令「先保存进来、标注清楚、先不要激活」。
> 激活时按本文「激活步骤」逐项搬运，不要直接复制到 src 目录。

---

## 一、这批是什么

上游 3.1.0 的「账号型提供商 + 配额 + 用量流水」整批（19 个新增件 + 3 个修改件整版）：

### A. 账号/OAuth 基础设施（ai 模块）
| 文件 | 作用 |
|---|---|
| `provider/AccountCredentialProvider.kt` | 账号凭据提供者接口（accessToken 来源抽象） |
| `provider/ProviderQuota.kt` | 配额数据模型（各窗口/重置时间） |
| `provider/QuotaQueryRunner.kt` | 配额查询执行器（自定义 header 模板） |
| `provider/providers/AccountAuth.kt` | `configureAccountAuth` / `withAccountAuthenticator`（OAuth 注入） |
| `provider/providers/chatgpt/ChatGPTProvider.kt` | ChatGPT 账号型 provider（ResponseAPI，参照 codex login） |
| `provider/providers/grok/GrokProvider.kt` | Grok 账号型 provider（OAuth） |

### B. 账号登录与凭据管理（app 模块）
| 文件 | 作用 |
|---|---|
| `data/ai/account/ChatGPTAuth.kt` | ChatGPT OAuth 流程 |
| `data/ai/account/GrokAuth.kt` | Grok OAuth 流程 |
| `data/ai/account/Jwt.kt` | JWT 解析（expiresAt/accountId） |
| `data/ai/account/ProviderAccountManager.kt` | 账号登录态/token 刷新/持久化总管 |
| `data/datastore/migration/PreferenceStoreV5Migration.kt` | 设置结构迁移（ProviderSetting 新旧兼容） |

### C. 用量流水（已在 v5.0.22 激活）
| 文件 | 状态 |
|---|---|
| `data/ai/UsageRecorder.kt` | 待激活（依赖 TokenUsage.merge + ChatService 钩子） |
| `data/db/dao/UsageRecordDAO.kt` | **已激活**（v5.0.22，DB v34 建表） |
| `data/db/entity/UsageRecordEntity.kt` | **已激活**（同上） |
| `data/db/migrations/Migration_30_31.kt` | 参照（我方对应件 = `Migration_33_34.kt`，已激活） |

### D. 设置 UI 件
| 文件 | 作用 |
|---|---|
| `ui/components/ai/ProviderQuotaText.kt` | 提供商列表的配额文本 |
| `ui/pages/setting/components/QuotaQueryOption.kt` | 配额查询配置项 |
| `ui/context/LocalWorkspace.kt` | 工作区 CompositionLocal（上游侧） |

### E. 修改件整版（激活时必须整体替换，不能打补丁）
- `provider/ProviderSetting.kt`（333 行）：**sealed `ApiKey` / sealed `Account` 双族 + `OAuthCredential` + 全 provider `quotaQuery` 字段 + `supportsBuiltInSearch`**
- `provider/ProviderManager.kt`（67 行）：账号型 provider 的注册与分发
- `provider/Provider.kt`（127 行）：provider 接口面（配额/账号）

### F. SSE 读取器
- `common/http/sse/ServerSentEventReader.kt`（184 行）：与 `RealEventSource` 配对使用；**未激活**（替换会改变流式解析行为，需单独一轮验证）。

---

## 二、激活步骤（下一轮执行）

1. **ai 模块**：`ProviderSetting.kt` 整版替换 → 全库修所有 `ProviderSetting.XXX(apiKey=...)` 调用点（新增 `quotaQuery` 参数）→ `ProviderManager` / `Provider` 同步 → 新增 A 档 6 件。
2. **app 模块**：B 档 4 件 + D 档 2 件（`LocalWorkspace` 与我方同名件二选一，**保留我方**）。
3. **用量**：`UsageRecorder` 接线（`TokenUsage.merge` 需确认在 ai 模块存在）+ ChatService 回合结束钩子（+28/−9 在上游）。
4. **设置 UI**：Provider 详情页接「账号登录 / 配额查询」分区（对照上游 3.1.0 `SettingProviderDetailPage`）。
5. **DB**：无需再迁移（v34/v35 已含 usage_record 与 memory folder）。
6. **验证**：ChatGPT/Grok 登录 → 列表配额显示 → 一轮对话后 `usage_record` 有行。

---

## 三、OpenCode / CommandCode 登录可行性（用户点名）

**代码事实**：
- 我方 OpenCode 走 `OpenAIProvider` + 特化 baseUrl/UA（`opencode/OpencodeRequestMode.kt`），鉴权 = **API key（Bearer）**，`keyRoulette` 轮询。
- CommandCode 同样 API key（`user_` 前缀族）。
- 上游 3.1.0 的「账号型」= 独立 provider 类（ChatGPT/Grok），走 OAuth token + ResponseAPI/OAuth 端点，不是 API key。

**结论（分层）**：
1. **API key 直连（现状）**：OpenCode / CommandCode 都可用，无需 OAuth——这是当前最稳路径。
2. **OAuth 登录（用户期望）**：技术上可行，但需要两家各自的 OAuth/device-flow 端点与 client_id（公开信息里 OpenCode 官方 CLI 用 `opencode auth login`，CommandCode 未见公开 OAuth 文档）。
   → 若要实施，需要先做一次官方 CLI 源码/端点调研（联网），再在 `AccountAuth` 里加两家配置。
3. **建议**：先把 3.1.0 的 OAuth **架构**激活（ChatGPT/Grok 可用），再把 OpenCode/CC 作为 `AccountAuth` 的第三个/第四个实现接入——架构层一次性到位，避免二次重构。

**待用户确认**：是否要我去调研 OpenCode/CommandCode 的 OAuth 端点（需要联网搜索官方 CLI/文档）。
