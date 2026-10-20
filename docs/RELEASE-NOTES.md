## RinCore v5.0.21
文件胶囊三选项回滚 + +号移至模型与思考之间 + 测试版共存(.test) + 编译提速

### 本次提交
- 654a33de8 release: v5.0.20 — 撤销外挂恢复常规调用链 + 工具箱12格方形玻璃 + 输入栏效果统一玻璃
- 9a334bca7 docs(5.0.19): changelog + bug-record B201（数据吞噬事故完整复盘）+ 定版清单第7节数据安全铁律
- 59b806925 release: v5.0.19 — 紧急修复：禁止对话打开时用空壳覆盖消息（吞数据根因）+ CI 单链提速
- c0b27e504 release: v5.0.18 — 紧急修复：撤除对话页最顶层双星轨浮层（覆盖抽屉/对话导致无法操作）
- 96c14ed84 ci(2026-10-20): release notes 步骤合并（去掉 if: env.X 的 GITHUB_ENV 时序依赖）
- e36e29dfa ci(2026-10-20): 单链发布 workflow（gate→build→release 正式版）+ paths-ignore + nightly 停定时

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
