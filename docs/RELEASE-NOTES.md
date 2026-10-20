## RinCore v5.0.18
紧急修复：撤除对话页最顶层双星轨浮层（覆盖抽屉/对话导致无法操作）

### 本次提交
- 96c14ed84 ci(2026-10-20): release notes 步骤合并（去掉 if: env.X 的 GITHUB_ENV 时序依赖）
- e36e29dfa ci(2026-10-20): 单链发布 workflow（gate→build→release 正式版）+ paths-ignore + nightly 停定时
- 4ad916e1c docs(5.0.17): changelog + bug-record B204（三批 UI 重构） [skip ci]
- 8e377c55b fix(5.0.17): FilesPicker 工具箱状态声明提升到函数级（MAIN 分支 lambda 内声明的 showSketch/scope 等弹窗不可见）
- b0ba7e9cd feat(5.0.17): 设置页功能分类重构+当前助手设置 / 输入栏 CS 式 AI 交互窗+纸箱工具箱 / 工具箱收纳更多菜单与草图
- c93cc10ef fix(5.0.16): setChatModel(单参) 走 ConversationConfig.chatModelId（Conversation 无该字段）

> 构建：gate（编译+测试）→ build（assembleRelease）→ 正式版 Release
