## RinCore v5.0.52
UI修正：输入栏恢复5.0.31 + 应用文件锚定文件页 + 工作目录重置 + 编译流精简(保留web-ui)

### 本次提交
- 87fb73d09 release: v5.0.52 — UI修正：输入栏恢复5.0.31(PlusActionMenu加号/搜索/toolPicker) + 应用文件锚定文件页 + 工作目录重置 + 编译流精简
- 37160dbe6 release: v5.0.51 — 编译流精简 + 应用文件锚定文件页 + 工作目录重置修复 + 关于页改本项目 + 删赞助/使用文档
- 2f97f97c9 release: v5.0.51 — ①编译流精简(删web-ui/NDK/android-36/合并Gradle调用) ②应用文件锚定文件页 ③工作目录重置修复(项目包优先+双层清零) ④关于页改本项目 ⑤删赞助 ⑥删使用文档
- 62778c0ea release: v5.0.50 — ★恢复架构修正：15G工作区改为就地解压(不进逐文件journal/几十万条→爆内存), PendingRestore只处理DB/设置/附件；失败自动回滚工作区
- 48693173c release: v5.0.49 — 工作区自愈 + 恢复异常兜底 + 符号链接恢复
- 335df7e77 release: v5.0.49 — 工作区自愈：启动扫描 workspaces 目录补登记 DB 条目(只勾文件也能用) + 恢复异常兜底防闪退 + resolveInside不再canonicalFile(符号链接可恢复)

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
