# 澎湃 OS4 与骁龙 8 Elite 适配状态 (2026-08-23 建立)

## 背景
- 澎湃 OS4 (HyperOS 4.0): 小米 2026-08 起首批 Beta 推送, 底层 Android 17
- 骁龙 8 Elite (8 Gen 4, SM8750): 2+6 全大核 Oryon 架构, 无小核, 主频最高 4.32GHz

## 已核查并达标项

### 16KB 页兼容 (APK 实测, 2026-08-23 v3.9.14)
全部 14 个 native so LOAD 段对齐 >= 16384:
libandroidx.graphics.path / libbarhopper_v3 / libdatastore_shared_counter /
libimage_processing_util_jni / libmupdf_java / libproot_exec / libproot_loader /
libquickjs-android-wrapper / libsimple / libsqlite3x / libsurface_util_jni /
libtermux / libucrop / libworkspace
结论: 16KB 页设备不触发 dlopen 崩溃, requery sqlite-android 官方 issue #195
的问题在本项目不适用 (所用版本已对齐)。

### 系统版本基线
- compileSdk = 37, targetSdk = 37, minSdk = 26
- 构建工具 AGP 9.3.1 (16KB 对齐能力内置)

### 系统集成
- edge-to-edge: RouteActivity/SafeModeActivity 均 enableEdgeToEdge, 页面 insets 处理完备
- 通知权限 POST_NOTIFICATIONS: 运行时请求 + 权限清单页
- 后台: FGS dataSync/specialUse/mediaPlayback 类型声明 + 电池优化豁免引导
- 精确闹钟: SCHEDULE_EXACT_ALARM + USE_EXACT_ALARM + setExactAndAllowWhileIdle
- ABI: 仅 arm64-v8a (8 Elite 全大核最优)

## 已添加的适配铺垫
- v3.9.14 RikkaHubApp.logDeviceEnvironment(): 启动时记录 Android API / 系统版本 /
  厂商型号 / SoC / 页大小 / ABI / 系统增量, tag=RinCoreEnv, 落运行日志。
  16KB 设备 (sysconf 返回 16384) 如果以后出现 native 问题, 可立即关联镜像版本。

## 待澎湃 OS4 正式版真机验证项
1. HyperOS 4 对 FGS 后台限制: 确认 dataSync 类型 FGS 可在息屏/省电下存活
2. HyperOS 4 通知: 灵动岛/胶囊样式下 ChatNotificationManager 显示正常
3. 16KB 页: 小米 17 系若启用 16KB, 启动 + 数据库 + 工作区 sandbox 全链路冒烟
4. 精确闹钟: HyperOS 4 的闹钟权限弹窗链路
5. 预测性返回: targetSdk 37 下返回手势与页面锚点
6. 骁龙 8 Elite 高刷场景: 聊天长列表 / WebView 渲染 / 流式输出滚动帧率

## 骁龙 8 Elite 性能优化点 (已就绪)
- 连接池 12 / keepalive 60s / maxRequestsPerHost 8: 高并发大核利用充分
- PDF/WEB 渲染双缓冲: 连续手势缩放不阻塞主线程
- 流式解析 IO 线程: 大小核迁移无感知 (8 Elite 全大核天然友好)
- 冷启动: ConnectionWarmer DNS+TCP 预热 + 凭证预热线程

## 后续计划
- 澎湃 OS4 正式版推送后, 按上述验证清单逐项跑真机
- 16KB 页专项: 真机确认 sysconf 返回值并记录库兼容性快照