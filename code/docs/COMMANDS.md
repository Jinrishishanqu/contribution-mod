# 命令参考

当前完整指令表由真实 Brigadier 注册树导出，避免在多个 Markdown 文件中重复维护语法。

- [离线 HTML Wiki](../../documentation/wiki.html)：全部指令的功能、完整用法、权限、示例与参数限制，可搜索。
- [指令 XLSX](../../documentation/commands.xlsx)：同源生成的84条可执行路径及使用指南。
- [文档源](../../documentation/source/catalog.json)：命令族说明及具体分支 overrides。
- [维护规范](CODE_STYLE.md)：生成、校验与构建同步流程。

使用 `code/tools/update-documentation.ps1` 同时更新 HTML 和 XLSX。构建不接受与当前源码不一致的输出。`<参数>`必须替换成实际值；可选尾部参数在完整表中以不同可执行路径分别列出。
