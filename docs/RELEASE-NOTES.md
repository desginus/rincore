## RinCore v5.3.73
★★★速度计数器根因：tok/s 用了整轮任务墙钟 + 最后一步 token（工具一多就变成 1/N）；改为每步真实生成时长累计 + 多轮 usage 累加 + step/final 日志

### 本次提交
- a21df2366 release: v5.3.73 — ★★★速度计数器根因：tok/s 用了整轮任务墙钟 + 最后一步 token（工具一多就变成 1/N）；改为每步真实生成时长累计 + 多轮 usage 累加 + step/final 日志
- 6edd1d6b7 release: v5.3.72 — ★★顶层工具面收敛：搜索三合一（workspace_search）/ 旧文件管理套件退场 / MCP与插件管理退出顶层 / 工作区工具归位文件控制 / 清理重复兜底区
- 1d0a5ad25 release: v5.3.72 — ★★顶层工具面收敛：搜索三合一（workspace_search）/ 旧文件管理套件退场 / MCP与插件管理退出顶层 / 工作区工具归位文件控制 / 清理重复兜底区
- f28fc9e91 release: v5.3.71 — ★★★OC/CC 预热全链修复：启动预热带鉴权 + 代理/长池同池 + 换池自动重启 + 快速/极致池一起预热 + 探活同池 + PREWARM 可对照日志
- 3659cd8ee release: v5.3.70 — ★★流式热路径再降载：FTS 分词延迟到收尾 + 去掉每秒全量 base64 扫描/重复哈希 + currentMessages 缓存 + 预热降 CPU 优先级 + 预热窗口减半
- 69fa818fe release: v5.3.69 — ★★修「写文件工具慢」与「流式卡顿」：写入改宿主直写（去掉两次 proot 冷启动）+ 落库改增量（只写变化节点，不再每秒全表重写/FTS 重建）

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
