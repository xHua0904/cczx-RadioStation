# 项目协作要求

- 始终使用简体中文回复，代码注释和 git commit message 使用中文。
- 每次任务结束时清理测试运行产生的数据库、临时导出、测试报告、浏览器配置和截图；保留测试源码与 Maven 依赖缓存。
- 收尾清理使用 `powershell -NoProfile -ExecutionPolicy Bypass -File tools/cleanup.ps1`。正式投稿数据仅在用户明确要求清空时使用 `-ResetData`，不要将本次清空授权延续到后续任务。
- 默认管理员密码为 `admin`，文档只记录默认值；当前密码通过 `--show-password` 查询，`--reset-password` 恢复默认值。登录仍用哈希，本机查询密钥存放于 data/，不提交 Git。
- 项目说明集中到工作目录 `PROJECT.md` 和公开 `README.md`，文档手动维护；代码修改不要自动更新 `.md`，只有用户明确要求修改说明时才编辑。应用启动、改密、查询和重置均不得创建或改写 `.md`。不再保留运行时文档模板或外部启动脚本。Kotlin 根包名使用 `com.cczxcsc`。
- 递归删除前验证目标绝对路径位于此项目中，不跨 shell 执行删除，不删除源代码、正式构建 jar 或依赖缓存。
