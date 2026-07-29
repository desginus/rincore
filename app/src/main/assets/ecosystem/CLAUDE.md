# CLAUDE.md — Claude Code 项目指令

## 代码风格
- 使用 Kotlin 标准风格, 4 空格缩进
- 函数命名: camelCase, 类命名: PascalCase
- 优先使用不可变数据类

## 工具使用
- 进行文件操作前先检查文件是否存在
- 使用 workspace_shell 执行命令时始终指定 cwd
- 搜索优先用 search_web, 网页抓取用 scrape_web

## 安全约束
- 不修改 ai/ 库中的核心接口
- 新增功能放在独立包中
- 所有 HTTP 请求使用 okhttp, 不引入新库
