## RinCore v5.0.49
工作区自愈：启动扫描 workspaces 目录补登记 DB 条目(只勾文件也能用) + 恢复异常兜底防闪退 + resolveInside不再canonicalFile(符号链接可恢复)

### 本次提交
- 1cd3da280 release: v5.0.47 — 导入崩溃修复：15G备份读取移到IO线程+分块进度(原主线程ANR被杀) + 导入进度UI + 玻璃工具箱
- bbd828882 release: v5.0.46 — 承载版(.hub)：玻璃工具箱UI恢复 + 功能回滚5.0.15 + 全量备份
- 0bacf1ff2 release: v5.0.46 — 承载版(.hub)：玻璃工具箱UI恢复 + 功能回滚5.0.15 + 全量备份
- 328a66d7e release: v5.0.46 — 承载版(.hub)：玻璃工具箱UI恢复 + 功能回滚5.0.15 + 全量备份
- 96289c9f7 release: v5.0.46 — 承载版(.hub)：玻璃工具箱UI恢复 + 功能回滚5.0.15 + 全量备份/导入
- c693373a7 release: v5.0.46 — 承载版(.hub)：玻璃工具箱UI恢复(v5.0.39 FilesPicker) + 功能回滚5.0.15 + 全量备份/导入

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
