# RinCore 屎山地图与拉齐工程 (v4.1.6)

## 一、基线差距矩阵 (原版 2.5.1 vs RinCore)

### 结构性缺失 (原版已重构, RinCore 未跟) — 专项版本待办
原版 2.5.0/2.5.1 完成了流式解码架构重构, RinCore 仍堆叠旧形态:

| 原版 2.5.1 架构 | RinCore 现状 | 移植规模 | 优先级 |
|---|---|---|---|
| stream/StreamChunkDecoder 体系 (SseEvent/StreamChunk/StreamChunkHandler) | SSE 解析内联在 ChatCompletionsAPI/ClaudeProvider (1270/1145 行) | 中 | P1 |
| providers/openai/OpenAIProvider + ChatCompletionsAPI 分层 | 单文件 1270 行 (解析+请求+重试+错误分类堆叠) | 中 | P1 |
| providers/claude/ClaudeProvider + ClaudeStreamDecoder | 单文件 1145 行 | 中 | P1 |
| GenerationLoop (生成编排) | GenerationHandler 1301 行单文件 | 中 | P2 |
| TranslationHandler 独立 | 翻译逻辑内联 | 小 | P2 |
| UIMessagePart.kt 独立文件 + UIMessageAnnotation | Message.kt 单文件 (原版 441 行差异) | 小 | P2 |

### 移植约束 (历史教训)
- 数据模型 (Tool 签名) 变更牵动全链路 (provider→handler→UI), 必须全档位回归
- 强兼容通道 (qwen Anthropic 直传 / CC) 不得回归
- 大重构分两次发版: 先解码层后编排层

### 已确认不需要移植 (RinCore 自研等价或更优)
- ChatToolFactory → RinCore ToolsBuilder (四投影架构能力, 原版无)
- web-ui 系弹窗 (composer/模型弹窗/ask_user web) — 用户定版放弃

## 二、大文件拆分方案 (分块处理)

| 文件 | 行数 | 拆分方案 | 风险 |
|---|---|---|---|
| ChatService.kt | 1638 | ConversationSession 状态机 / MessageQueue / 语音会话 / 通知 编排四模块 | 高 (本轮不做, 需回归矩阵) |
| SettingProviderDetailPage.kt | 1586 | 配置表单/连接测试/API Key 管理 三组件 | 低 |
| TTSProviderConfigure.kt | 1522 | 原版同构文件 (对齐原版自然变小) | - |
| MarkdownNew.kt / Markdown.kt | 1508/1357 | 原版同构 | - |
| WorkspaceDetailPage.kt | 1310 | 详情/预览/设置 页签拆分 | 低 |
| GenerationHandler.kt | 1301 | 随 GenerationLoop 移植一并解决 | 中 |

## 三、本轮完成 (v4.1.6 打扫批次)

1. **标注补全**: 文件头【原版对齐】/【自研】标注 263/594 → 589/593 (99%),
   每文件标注与基线 2.5.1 的逐字节差异行数
2. **死文件清除**: MCP 迁移残留 (McpOAuthCallback/McpOAuthClient/
   McpOAuthCallbackActivity 空壳 + SseClientTransport/StreamableHttpClientTransport
   全注释壳) — 5 文件
3. **未使用 import 清理**: ~540 行 (198 文件), by 语法隐式运算符
   (getValue/setValue/provideDelegate) 与通配 import 已恢复保护
4. 考古注释压缩已尝试并回滚 (风险实证: 块注释边界破坏), 考古注释
   (v3.x 逐版本决策史) 现状 3% 占比, 收益低, 转由知识库承载

## 四、清理纪律
- 死代码判定 = 全项目 grep 零引用 (含 Manifest/资源) 后删除
- 大文件拆分 = 等价重构, 单次发版 ≤2 文件, 全功能回归矩阵验证
