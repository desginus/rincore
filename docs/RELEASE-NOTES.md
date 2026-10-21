## RinCore v5.0.55
①工具超时700s→120s ②取消链2s超时 ③压缩历史横条 ④记忆两页+三范围锚定 ⑤memoryWriteScope生效

### 本次提交
- bb5c497a4 release: v5.0.55 — ①工具超时700s→120s ②取消链2s超时 ③压缩历史横条恢复 ④记忆两页+三范围锚定 ⑤memoryWriteScope生效
- 7d163f1f0 release: v5.0.55 — ①工具超时700s→120s(卡住不再等11分钟) ②取消链NonCancellable无限join→2s超时(停止键立刻响应) ③压缩历史横条恢复 ④记忆两页+三范围锚定 ⑤记忆归属memoryWriteScope生效
- 0a1e1beac release: v5.0.54 — ①压缩历史恢复(横条+位点弹窗+查看/恢复/级联撤销) ②记忆架构恢复(5.0.26两页:记忆/设置+范围/来源分类) ③新建记忆归属三锚点(助手/项目包/对话)实际生效
- 49eb728e2 release: v5.0.53 — ①压缩上下文修复(补totalMessages/defaultKeep智能推荐) ②撤速览页4图标(翻译/多媒体/子代理预算/产出) ③加号菜单拍照/图片/上传文件回调接线 ④编译流精简
- f5d9506f6 release: v5.0.52 — UI修正：输入栏5.0.31 + 应用文件锚定文件页 + 工作目录重置 + 编译流精简
- 047a9567f release: v5.0.52 — UI修正：输入栏5.0.31 + 应用文件锚定文件页 + 工作目录重置 + 编译流精简

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
