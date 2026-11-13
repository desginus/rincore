# RinCore 文档索引

> 先读 **`AGENT-HANDOFF.md`**。它是唯一主交接文档；其他文档是它的证据/附录。
> 工作区文档中心 `/workspace/docs/` 与仓库 `docs/` 分工：仓库文档随代码走；工作区文档是沙箱内快速查阅/体检报告。

## 必读

| 文档 | 用途 |
|---|---|
| [`AGENT-HANDOFF.md`](AGENT-HANDOFF.md) | **全栈交接主文档**：当前状态、架构、验证、发布、多 Agent 协议 |
| [`handoffs/TEMPLATE.md`](handoffs/TEMPLATE.md) | 交付文档模板（复制使用） |
| [`handoffs/2026-11-13-integration-agent-v5.3.77.md`](handoffs/2026-11-13-integration-agent-v5.3.77.md) | 最新一批交付记录 |
| [`handoffs/CLAIMS.tsv`](handoffs/CLAIMS.tsv) | 并行 Agent 的文件/范围占用声明 |
| [`KNOWN-ISSUES.md`](KNOWN-ISSUES.md) | 已知问题总表（排查前先看） |

## 代码导航

| 文档 | 用途 |
|---|---|
| [`CODE-NAVIGATION.md`](CODE-NAVIGATION.md) | “我要改 X 去哪”路径索引 |
| [`STRUCTURE-MAP.md`](STRUCTURE-MAP.md) | 文件/符号/模块/耦合总图 |
| [`EXTENSION-POINTS.md`](EXTENSION-POINTS.md) | 扩展点/检测接口/迁移指南 |
| [`APP_MAP.md`](APP_MAP.md) | 应用域地图 |
| [`module-map.md`](module-map.md) | 模块地图 |

## 当前功能/设计

| 文档 | 用途 |
|---|---|
| [`3.2.0-移植进度.md`](3.2.0-移植进度.md) | 上游 3.2.0 移植进度（非手机/手机边界） |
| [`PHONE-CONTROL-DESIGN.md`](PHONE-CONTROL-DESIGN.md) | 手机操控三方选型（3.2.0/Mika/DSH） |
| [`手机操控-v5.3.77.md`](手机操控-v5.3.77.md) | 本版手机操控实现与测试步骤 |
| [`WORKSPACE-MODEL.md`](WORKSPACE-MODEL.md) | 工作区三层定位 |
| [`DECISIONS-USER.md`](DECISIONS-USER.md) | 用户已定案/不要再问的决策 |

## 验证/发布

| 文档 | 用途 |
|---|---|
| [`RELEASE-NOTES.md`](RELEASE-NOTES.md) | 最近发布说明 |
| [`version-management.md`](version-management.md) | 版本号/发布纪律 |
| [`audit/`](audit/) | 体检报告目录 |
| [`测试债-v5.3.26.md`](测试债-v5.3.26.md) | 存量测试债（不要删断言变绿） |

## 维护规则

1. 每次交付：更新 `AGENT-HANDOFF.md` + 新增 `handoffs/YYYYMMDD-<agent>-<topic>.md`。
2. 每次发版：更新 `RELEASE-NOTES.md`、`KNOWN-ISSUES.md`、主文档版本/CI/SHA。
3. 文档里的路径必须能被脚本验证；不要写不存在的路径。
4. 不把密钥/凭据写进文档。
