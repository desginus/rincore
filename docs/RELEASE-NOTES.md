## RinCore v5.0.58
新重试机制(可回滚到 retry-baseline-v5.0.57) ①结构化看门狗异常取代中文字符串嗅探 ②实质进度判据:空壳保活帧不再刷新空闲计时(两个Provider) ③配额: Init 5→8/Stream 18→12/DataTimeout 1→3/空响应1→2 ④指数退避500ms→8s封顶+50%~100%jitter ⑤快速失败检测:连续3次秒失败退避抬到30s ⑥单测锁全分支(StreamProgressTest/RetryPolicyTest)

### 本次提交
- e3bdbe6b8 release: v5.0.57 — ①本对话产出『未找到文件』真根因修复(canonicalPath cwd双前缀/去二次规范化/旧脏数据兼容/宿主侧兜底) ②子代理预算面板重做(可调预算+并发+用量) ③记忆页删两个过时开关 ④glob改原生(去掉proot冷启动) ⑤ci-check加Composable注解/重复声明检查
- fa1458ac4 release: v5.0.56 — ①子代理/工具并发白名单 ②工具超时120s ③取消链2s ④压缩历史横条 ⑤记忆两页+三锚定 ⑥VM去重+import
- ec0479883 release: v5.0.56 — ①子代理/工具并发: subagent_dispatch+workspace_job 加入并行白名单(此前串行拖死全部工具)+并行上限6 ②工具超时700s→120s ③取消链NonCancellable无限join→2s ④压缩历史横条+位点弹窗 ⑤记忆两页+三范围锚定 ⑥VM记忆流去重+rememberScrollState import
- af14c540a release: v5.0.56 — ①子代理/工具并发: subagent_dispatch+workspace_job 加入并行白名单(此前串行拖死全部工具) 并行上限4→6 ②工具超时700s→120s ③取消链2s超时 ④压缩历史横条 ⑤记忆两页+三锚点
- fa9c4ff96 release: v5.0.55 — ①工具超时700s→120s ②取消链2s超时 ③压缩历史横条 ④记忆两页+三范围锚定 ⑤memoryWriteScope生效
- b6b613f82 release: v5.0.55 — ①工具超时700s→120s ②取消链2s超时 ③压缩历史横条 ④记忆两页+三范围锚定 ⑤memoryWriteScope生效

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
