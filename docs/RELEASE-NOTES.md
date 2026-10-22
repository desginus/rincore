## RinCore v5.0.63
①记忆模块删错位置回滚+改对:设置tab的「新建记忆归属」「做梦范围」已从checkpoint/v5.0.61原样恢复;真正该删的是记忆tab里MemoryMemoriesTab的「整个助手」「单对话记忆」两个FilterChip(已删,分支条件改conversationMemories.isEmpty()) ②产出三动作协程取消根修:onClick里先onOpenChange(false)会让调用方if(showArtifactsSheet)整块出栈→组件销毁→rememberCoroutineScope被取消+rememberLauncherForActivityResult回调丢失;分享因hostPath快路径赶在取消前跑完才碰巧成功,渲染必然被中断→改为动作完成后才关sheet ③导出launcher回调同样修复

### 本次提交
- a694c407e release: v5.0.62 — 产出改回直接打开(唯一对应码=宿主绝对路径)①ProducedFile新增hostPath:登记时落盘宿主File.absolutePath ②扫描侧ScannedFile直接带absolutePath ③直登记侧用Koin取WorkspaceRepository.hostFileFor解析(修CI:GenerationHandler无该构造参数) ④resolveOrAsk快路径:File(hostPath).isFile一次stat,跨包名按/files/重建 ⑤materializeHostFile直接复制 ⑥找不到时toast打印path/cwd/hostPath ⑦记忆页删「新建记忆归属」「做梦范围」两块UI
- ffcd79db1 release: v5.0.62 — 产出改回直接打开(唯一对应码=宿主绝对路径)①ProducedFile新增hostPath:登记时就把宿主File.absolutePath落盘(扫描侧ScannedFile直接带absolutePath,直登记侧调hostFileFor解析)②resolveOrAsk快路径:File(hostPath).isFile一次stat直接用,跨包名按/files/之后重建,不再猜CWD③materializeHostFile直接复制,不经exportResolvedFile④找不到时toast打印记录的path/cwd/hostPath原样⑤记忆页删掉「新建记忆归属」「做梦范围」两块UI(归属应由顶部范围Chip承担)
- c6ea6a1eb release: v5.0.61 — 补齐重试机制最后一块业界标配:尊重服务器Retry-After ①新增ai/core/RetryAfter.kt(解析秒数与HTTP-date,封顶120s,对齐OpenAI SDK MAX_RETRY_AFTER_DELAY)+RetryAfterException(IOException子类,不改catch类型) ②ChatCompletionsAPI:5xx与429都进重试链并读Retry-After头 ③ClaudeProvider:429纳入瞬时可重试并读头 ④RetryDecision.decide新增retryAfterMs参数,优先级高于本地退避与headerDead的0等待,思考静默不参与 ⑤4个新单测+parse逻辑6个用例验证
- c3b8cdeee release: v5.0.60 — ①JS工具:独立守护线程+Future 20s超时+finally close()(修卡死/泄漏),显式lambda标签js@ ②气泡分支丢内容:挂起点前移+updateConversationState原子读改写+落库取权威状态 ③ci-check:X支持连续注解/@Preview/剥注释,Y区分重载,S.C.I降advisory,B全量降级(279误报→0)
- ec39115ba release: v5.0.60 — 两处长期待办修复+检查器大修 ①JS工具:QuickJS同步JNI无法被协程取消→独立守护线程+Future 20s超时+finally close()(修卡死与native泄漏) ②气泡分支丢内容:editMessage旧快照整体写回(lost update,中间夹settingsFlow.first挂起点)→挂起点前移+updateConversationState原子读改写+落库取权威状态 ③ci-check:X支持连续注解块与@Preview/Y区分重载/X剥注释/S.C.I全量降advisory+B全量降级(279项误报→0硬问题,探针验证仍抓真错)
- 5e04a5684 release: v5.0.59 — 本对话产出全链重做(按用户逻辑:唯一对应码+最小CWD集合) ①真根因: FilesPicker传conversation.workspaceCwd(已退役恒null)→项目包CWD丢失→探不到 ②ChatList传folderCwd=null同样问题 ③ProducedFile新增workspaceId+cwd定位键,记录时落盘 ④resolveOrAsk改为ProducedFile入参:用记录键+项目包/助手CWD/根最小集合精确rootfsFileSize探测,零目录遍历 ⑤渲染失败不再静默return(改弹Unsupported说明) ⑥删满工作区搜索/多命中弹层

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
