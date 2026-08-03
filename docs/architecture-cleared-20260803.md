# RinCore 乱麻理顺 — 版本线/模块关系/消息流/石山关联

> 日期：2026-08-03 | 基于：git 全历史分析 + 模块扫描 + 来源标注（architecture-audit-20260803.md）

## 一、版本线全景（理清两轮循环）

### 主线（main）演进
```
第一轮（07-11 ~ 07-30 上午）——已废弃：
  RikkaHub v2.4.1 fork (07-11)
  → v1.0/v0.1.0 (07-11, 版本号首次混乱)
  → v2.9.7/v2.9.8 (07-24/25)
  → v3.0.2~v3.0.9 (07-25~27, 压缩系统/白名单/缓存)
  → v3.1.0/v3.1.1 (07-28)
  → v3.2.0~v3.4.0 (07-29, 生态修复/动态工具)
  → v3.5.0~v3.8.0 (07-30 凌晨, stdio/MCP 实验线)
      ↑ 07-30 13:16 版本号硬重置为 v3.2.0 ← 断裂点

第二轮（07-30 下午 ~ 今）——当前有效线：
  v3.2.0 (07-30 13:16, MCP 动态工具 F1 修复, 版本号复用)
  → v3.2.1 (SSE 中断恢复)
  → v3.2.2 (域分类体系 ×4, 07-31)
  → v3.3.0 (RikkaHub 2.4.5 移植, 07-31)
  → v3.3.1~v3.3.12 (缓存稳定性系列, 08-01)
  → v3.3.13~v3.3.17 + v3.4.0 (三功能移植: 保活/权限/工作流, 08-02)
  → v3.4.1~v3.4.10 (传输层补丁期, 08-02/03)
  → v3.5.0 (回滚 3.2.2) → v3.5.1~v3.5.3 (当前, 08-03)
```

### 分支
| 分支 | 内容 | 状态 |
|---|---|---|
| main | 当前主线（第二轮） | ✅ 有效 |
| backup-bad-merge | 坏合并备份（含第一轮 v3.8.0 线） | 存档 |
| origin/desk, desk-phase0 | Desk 桌面面板功能（07-24/25） | 独立分支 |
| upstream-try/master | 上游 RikkaHub 适配试验（2.4.5） | 参考（适配源） |

### 关键结论
- **版本号不可靠**：两轮循环（v3.2.0-v3.5.x 重复）+ 硬重置（v3.8.0→v3.2.0）+ v1.0/v0.1.0 混乱
- **唯一可靠标识：versionCode**（当前 164，全程递增）
- **管理约定**：此后一切版本对照以 versionCode 为准；文档标注轮次（如 v3.5.3-2R）

## 二、模块关系与核心消息流

### 依赖链（数据流向）
```
UI (ui/pages) 
  → ChatService (service)          ← 会话编排/落盘/工具池构建
    → GenerationHandler (data/ai)   ← 消息组装/分层路由/域过滤/transform
      → OpenAIProvider (ai)         ← ChatCompletions / Responses API 分发
        → ChatCompletionsAPI / ResponseAPI (ai)   ← HTTP 传输/SSE
  → settingsStore (SSOT)            ← UI / list_domains / invoke_tools / Prompt 四投影
  → ToolRouter (data/ai/tools/routing)  ← 每步从 settings 重建（三位一体）
  → DynamicTools (ecosystem)        ← MCP 工具（v3.5.3 起走懒加载）
```

### 消息流（一次生成）
```
1. ChatService.generate → 构建 tools 池（local/搜索/workspace/生态/动态/技能/域/MCP）
2. GenerationHandler.generateText
   ├─ 分离 frameworkTools / domainTools
   ├─ 每步: ToolRouter 重建 + currentMcpTools 合并 allDomainTools
   ├─ toolsInternal: layered(框架+invoke_tools+已加载域) / full(全量+去重)
   ├─ system: buildCacheAnchor + systemPrompt + layer1 + 框架工具提示 + 记忆
   └─ sanitize + transforms (Placeholder/PromptInjection/ContextCompression)
3. Provider.streamText/generateText → SSE → GenerationChunk 回调
4. ChatService: 流式增量落盘 (v3.4.6 保留) + 通知
```

### 关键缝隙（回滚后现状）
| 链路点 | 现状 | 风险 |
|---|---|---|
| system 首条 | 3.2.2 版（BEFORE_SYSTEM_PROMPT 独立消息） | G1 协议违规 |
| 消息清洗 | 3.2.2 版（无 sanitizeToolCallSequence） | G2 孤儿 tool_call |
| 空流 | 3.2.2 版（无重试） | G3 |
| 缓存诊断 | 3.2.2 版（无断层日志） | G4 |

## 三、石山关联分组（一起修）

### 组 A：协议合规（G1+G2）——DeepSeek 中断根因区
- G1 BEFORE_SYSTEM_PROMPT 合并进 system（移植 v3.4.5——已验证）
- G2 孤儿 tool_call 清洗（移植 v3.3.13——已验证）
- 关联：修完 A 组后 DeepSeek 长对话中断应显著减少

### 组 B：可观测性（G4+G3）——诊断能力
- G4 缓存断层日志重加（v3.4.1 的 cache: prompt/cached）
- G3 空流诊断/重试（v3.4.7——先加诊断，重试观察后决定）
- 关联：先有诊断再谈修复

### 组 C：成本（S1 残余）——70K 收尾
- v3.5.3 已修 MCP 懒加载（264→~18）
- 待确认：useLayeredTools 配置、域启用过滤（S1）
- 关联：实测日志（toolsInternal 构成）确认后收尾

### 组 D：维护性（S6/S7/S8/S10/S11）——防再乱
- S6 RouteActivity 拆分（适配时）
- S7 SkillsTools 完整性验证
- S8 来源标注修正（已改文档+Skill）
- S10 版本号发布校验（build.gradle.kts 与提交同步——脚本检查）
- S11 版本线两轮循环（以 versionCode 为唯一标识 + 文档标注轮次）

### 组 E：能力补齐（S9 适配规划）
- P0 工作流阶段 3 → P1 Skill 管理 → P2 触发器 → P3 Telegram/Codex

## 四、理顺后的工作模型（防再乱）

1. **版本标识**：versionCode 唯一；提交必须同步 build.gradle.kts（S10 校验）
2. **来源标注**：改动任何模块先查来源表（architecture-audit）——自研/移植/继承
3. **移植纪律**：只移植验证过的补丁；移植后更新来源表 + changelog
4. **适配参考**：upstream-try（2.4.5）为上游适配试验分支——后续 RikkaHub 更新先在其上试
5. **清理节奏**：A 组（协议）→ B 组（诊断）→ C 组（成本收尾）→ D 组（维护）→ E 组（能力）
