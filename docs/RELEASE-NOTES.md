## RinCore v5.0.42
正式版(同包名)：可覆盖安装继承数据 + 全量导出(含工作区tar.gz) + 导出进度 + rootfs符号链接完整恢复

### 本次提交
- 418b1562e release: v5.0.41 — 备份/恢复可用性修复：rootfs符号链接纳入恢复 + 导出进度提示(阶段/MB) + 完整链路审查
- 2a300eb64 release: v5.0.40 — 功能回滚5.0.15 + 玻璃模糊恢复 + 完整备份
- 6b3ffe59a release: v5.0.40 — 功能回滚5.0.15 + 玻璃模糊恢复 + 完整备份
- fcb10e610 release: v5.0.40 — 功能回滚5.0.15 + 玻璃模糊恢复 + 完整备份
- e2fecd9d3 release: v5.0.40 — 功能回滚5.0.15 + 玻璃模糊恢复 + 完整备份(含workspaces.tar.gz)
- a5cd2333e release: v5.0.40 — 功能回滚5.0.15 + 玻璃模糊恢复 + 完整备份(含workspaces.tar.gz)

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
