## RinCore v5.0.51
①编译流精简(删web-ui/NDK/android-36/合并Gradle调用) ②应用文件锚定文件页 ③工作目录重置修复(项目包优先+双层清零) ④关于页改本项目 ⑤删赞助 ⑥删使用文档

### 本次提交
- 62778c0ea release: v5.0.50 — ★恢复架构修正：15G工作区改为就地解压(不进逐文件journal/几十万条→爆内存), PendingRestore只处理DB/设置/附件；失败自动回滚工作区
- 48693173c release: v5.0.49 — 工作区自愈 + 恢复异常兜底 + 符号链接恢复
- 335df7e77 release: v5.0.49 — 工作区自愈：启动扫描 workspaces 目录补登记 DB 条目(只勾文件也能用) + 恢复异常兜底防闪退 + resolveInside不再canonicalFile(符号链接可恢复)
- 1cd3da280 release: v5.0.47 — 导入崩溃修复：15G备份读取移到IO线程+分块进度(原主线程ANR被杀) + 导入进度UI + 玻璃工具箱
- bbd828882 release: v5.0.46 — 承载版(.hub)：玻璃工具箱UI恢复 + 功能回滚5.0.15 + 全量备份
- 0bacf1ff2 release: v5.0.46 — 承载版(.hub)：玻璃工具箱UI恢复 + 功能回滚5.0.15 + 全量备份

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
