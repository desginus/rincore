## RinCore v5.0.57
①本对话产出『未找到文件』真根因修复(canonicalPath cwd双前缀/去二次规范化/旧脏数据兼容/宿主侧兜底) ②子代理预算面板重做(可调预算+并发+用量) ③记忆页删两个过时开关 ④glob改原生(去掉proot冷启动) ⑤ci-check加Composable注解/重复声明检查

### 本次提交
- fa1458ac4 release: v5.0.56 — ①子代理/工具并发白名单 ②工具超时120s ③取消链2s ④压缩历史横条 ⑤记忆两页+三锚定 ⑥VM去重+import
- ec0479883 release: v5.0.56 — ①子代理/工具并发: subagent_dispatch+workspace_job 加入并行白名单(此前串行拖死全部工具)+并行上限6 ②工具超时700s→120s ③取消链NonCancellable无限join→2s ④压缩历史横条+位点弹窗 ⑤记忆两页+三范围锚定 ⑥VM记忆流去重+rememberScrollState import
- af14c540a release: v5.0.56 — ①子代理/工具并发: subagent_dispatch+workspace_job 加入并行白名单(此前串行拖死全部工具) 并行上限4→6 ②工具超时700s→120s ③取消链2s超时 ④压缩历史横条 ⑤记忆两页+三锚点
- fa9c4ff96 release: v5.0.55 — ①工具超时700s→120s ②取消链2s超时 ③压缩历史横条 ④记忆两页+三范围锚定 ⑤memoryWriteScope生效
- b6b613f82 release: v5.0.55 — ①工具超时700s→120s ②取消链2s超时 ③压缩历史横条 ④记忆两页+三范围锚定 ⑤memoryWriteScope生效
- bb5c497a4 release: v5.0.55 — ①工具超时700s→120s ②取消链2s超时 ③压缩历史横条恢复 ④记忆两页+三范围锚定 ⑤memoryWriteScope生效

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
