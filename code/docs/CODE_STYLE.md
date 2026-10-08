# 代码与文档维护规范

## Java

- 使用 Java 25。Java 统一采用 google-java-format 1.37.0 的 AOSP 四空格风格和100列换行；不手工维护互相矛盾的缩进规则。
- 类/record 使用 PascalCase，方法/字段/局部变量使用 camelCase，常量使用 UPPER_SNAKE_CASE。稳定的行业 ID、注册项、数据库列、已发布 API 不为美观而改名。
- 包按账户、统计、股票、奖励、商店、数据库、命令/UI、运行时及物品适配分层；不要把业务 SQL 放入客户端绘制代码，也不要在事件适配器中直接发贡献值。
- 数据库操作返回 Future，服务端主线程不 join/get；回调访问玩家/世界时重新调度主线程。
- 错误必须有终态；经济请求依赖原幂等 ID，不用 UI 超时盲目重发或取消提交。
- 限制集合、队列、载荷和历史绘图容量；新增缓存必须定义失效点与生命周期。不要用全服/实体扫描替代原生完成事件。
- 保留运行语义和测试。格式清理与事务/算法变更分别审核；不改已发布 SQL 迁移。

## 开发命令

```powershell
code/tools/setup-formatter.ps1
code/run-gradle.ps1 formatJava --offline
code/tools/update-documentation.ps1
code/run-gradle.ps1 build --offline
```

`formatJava` 格式化所有 Java 源码和测试；`verifyJavaStyle` 检查格式。格式化器仅开发使用，不内嵌到模组。

## HTML/XLSX 同步

实际注册的 Brigadier 命令树是语法唯一来源，`documentation/source/catalog.json` 补充功能、权限、示例与参数说明。
`update-documentation.ps1` 导出命令，再用 @oai/artifact-tool 生成两个工作表、离线 Wiki 与校验清单。预览留在 `code/build/documentation/previews`。
开发环境可设置 `CONTRIBUTION_DOC_NODE` 与 `CONTRIBUTION_DOC_MODULES` 指向包含 @oai/artifact-tool 的 Node 运行时；默认使用本机已配置的 Codex 运行时。不要向用户游戏运行环境安装此开发依赖。

`run-gradle.ps1 build` 自动先同步文档；直接运行 Gradle 的 `check/build` 会拒绝过期文档。指纹覆盖源码、资源、设计文档（排除 items/definitions）、生成脚本、版本与构建规范。输出同时校验 HTML 和 XLSX 的 SHA-256。不要仅手动修改生成文件。

新增命令至少添加所属命令族的说明；具体分支行为不同用 overrides 提供独立功能、权限、示例和限制。行为调整同时更新对应 design 文档；不要用版本升级公告替代设计规则。
