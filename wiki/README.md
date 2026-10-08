# 贡献值模组维基

网站入口为 `index.html`。整个 wiki 文件夹可直接复制、离线打开或作为静态网站发布；图片、样式、搜索索引和命令工作簿均位于站内。

## 先设计内容

内容职责、阅读路径、条目模板及全站 40 页安排见 [维基内容设计](../design/infrastructure/wiki-content-design.md)。修改内容时先确定页面解决的问题，再安排章节与图片。

- 首页提供分类入口，玩法入门指导第一次参与，玩法总览说明内容范围。
- 机制页依次说明参与、操作、规则和计算。管理与实现资料置于末尾。
- 物品页说明获取、使用、属性，并链接配方；配方页展示产物、槽位和材料数量。
- 进度页使用自身图标，说明操作与全部条件；原始定义保留在折叠项。
- 命令页说明用途、示例、准确语法、权限和参数限制。原版参数类型作为补充数据。
- 管理和接口页面按照前置条件、操作、结果与恢复约定组织。

图片放在对应内容中：首页使用带图入口，物品使用图鉴，配方使用槽位图，进度使用自身图标。流程中的物品图标是示意符号。技术页面按解释需要配图。

## 统一用词与格式

正文统一使用“维基、命令、商店、进度、原版对话界面、标识符、游戏刻、指数移动平均”。中文与数字、拉丁文字之间留空格，使用中文标点。段落说明因果，顺序操作用步骤列表，比较和参数用表格，二级标题区分主题，三级标题区分获取、用途和属性。

Minecraft、Fabric、Java、H2、MySQL 等正式名称保留。正文称 Minecraft Java 版。主题盔甲正文称“马克六型”，首次说明对应游戏内名称“Mark 6”。命令、配置键、路径、注册标识符、接口类型和要求准确输入的文本保持原文并用代码格式。原始数据与数学公式保持原样。

## 编辑源

| 位置 | 用途 |
| --- | --- |
| `content/site.json` | 站点信息、页面顺序、主题导航 |
| `content/pages/*.json` | 31 个主题的人工正文 |
| `content/editorial.json` | 逐页实用摘要和术语规范 |
| `content/names.json` | 当前配方和进度涉及的原版中文名称 |
| `content/translation-source.json` | 本机 Minecraft 26.3 中文语言资源的来源与指纹 |
| `content/media.json` | 图片索引、皮肤名称及原素材来源与指纹 |
| `content/visuals.json` | 可复用的图鉴和流程内容 |
| `assets/wiki.css`、`assets/site.js` | 样式、搜索、主题及导航 |
| `assets/images/` | 复制的原始材质、模型与静态缩略图 |

配方、进度和皮肤目录由 `code/tools/wiki-game-content.mjs` 从当前模组资源生成。中文条件解释由 `wiki-editorial.mjs` 提供，未知字段会使生成失败；原始定义始终保留。命令语法来自实际注册树，用途与权限由 `documentation/source/catalog.json` 维护。新增页面要登记主题导航、页面摘要和写作职责，保持每页只有一个主要入口。

人工章节原 `id` 保留。移入补充资料的章节在折叠项中保留原锚点；程序标识符、物品名称与模组数据不作为文风调整的对象。

## 图片来源与更新

所需原版材质及模型由用户提供的 `pack/assets/minecraft` 复制到 `assets/images/source/minecraft`；当前模组素材复制到相应 contribution 目录。`media.json` 记录原路径与 SHA-256。原版素材归原权利人所有，来源记录不表示取得其版权。

运行 `python code/tools/import-wiki-media.py` 更新素材快照（需要 Pillow、NumPy）。导入器选择物品栏模型并生成透明缩略图；动画取首帧，静态图不绘制附魔光效。普通文档生成只读取站内快照，不依赖 pack。

## 同步生成与检查

运行 `code/tools/update-documentation.ps1` 同时生成网站、兼容入口 `documentation/wiki.html` 和 `documentation/commands.xlsx`，并复制命令工作簿到 wiki。生成器检查页面职责摘要、术语、本地资源、链接和锚点；`verifyDocumentation` 检查源指纹及全站产物哈希。浏览器检查覆盖桌面、手机、独立离线目录、图片、搜索、主题、无脚本和兼容入口。

兼容入口的图片引用相邻 wiki 目录；独立发布使用 wiki 入口。JavaScript 关闭时仍可读正文、打开折叠资料和使用普通链接。

内容安排参考 [鞘翅](https://zh.minecraft.wiki/w/鞘翅?variant=zh-cn)、[交易](https://zh.minecraft.wiki/w/交易)和[进度指南](https://zh.minecraft.wiki/w/Tutorial:进度指南)。正文和模组规则以本仓库当前资源及实现为准。
