# CSU-YSU Contribution System 0.1.1

Minecraft Java Edition 26.3 / Fabric Loader 0.19.5 / Fabric API 0.161.0+26.3 / Java 25。

## 安装与使用

将 `build/libs/CSU-YSU-contribution-system-0.1.1.jar` 和匹配版本的 Fabric API 放进服务端 mods 目录。模组只有一份通用 JAR，逻辑在服务端运行；原版客户端无需安装即可进入服务器并使用贡献值、签到、股票和商店的文字/原版 Dialog 界面。若也在客户端安装同一 JAR 和 Fabric API，`/contribution`、`/stock` 和 `/shop` 会打开各自的专用界面。

进入游戏输入 `/contribution` 打开贡献值图形界面。原版客户端使用 26.3 原生 Dialog；安装模组的客户端在账户、流水、统计、行业、签到和管理员页面内保持统一的专用界面，不会跳回 Dialog。`/contribution ui_vanilla`、`/stock ui_vanilla`、`/shop ui_vanilla` 可在已安装客户端模组时分别调试原版界面。股票系统独立使用 `/stock`。文字查询和管理员命令继续可用，完整列表见[命令速查](docs/COMMANDS.md)。

单人游戏和单服开箱即用：首次启动自动在当前世界的 `contribution/contribution.mv.db` 建立本地数据库和业务表，不用安装 MySQL，也不需填写数据库配置。旧版生成的 `database.enabled=false` 配置会自动按新的本地模式读取；无需手动改开关。

本地运行数据集中在该世界的 `contribution/` 文件夹：数据库文件保存账户、流水和建设度，`statistics-journal/` 保存尚待入库的建设度恢复日志。旧版放在 `config/contribution/statistics-journal/` 的日志会在启动时自动迁入。备份或回退时先退出世界/停止服务器，再完整复制或替换整个 `contribution/` 文件夹；不要在游戏运行时复制数据库文件，也不要用文本编辑器直接修改 `.mv.db`。配置文件 `config/contribution/server.json` 单独保存。群组服的权威数据在共享 MySQL 中，必须做 MySQL 备份；子服世界里的 `contribution/` 只包含本地恢复日志，不能代替数据库备份。

管理员文字命令：`/contribution add|remove <玩家> <数量> <原因> <影响历史总收入:true|false> [备注]`。`true` 会让历史总收入与余额同向增减，`false` 只变更余额；任一数值越界则整笔操作拒绝，不写流水。

群组服必须将每台服务器的 `config/contribution/server.json` 设为 `database.mode="mysql"`，填写同一个 MySQL 8.4 数据库地址和凭据，并为各服设置唯一 `serverId`。MySQL 服务需要由运维环境提供；主服务器在账号有建库权限时可自动创建指定数据库及业务表，其他子服只校验结构。旧版 `database.enabled=true` 且没有 `mode` 的配置继续按 MySQL 读取。两种模式的数据不会自动互相导入，切换前应备份。口令可由 `CONTRIBUTION_DB_PASSWORD` 覆盖。正式部署应使用独立数据库用户，不要照搬开发测试目录的 root 连接。

生存服 `mainServer=true`，负责迁移和每日结算；其他子服设为 false。各服 serverId 唯一，并共享数据库与 statisticsServers 列表。默认只有 survival 采集统计。

## Basic 实现范围

- 贡献值账户：整数余额、总收入、退款、事务行锁、离线名称/UUID、唯一幂等请求和跨服共享。
- 流水：权限控制、游标分页、类型/来源/服务器/日期筛选、按过期自然月归档及归档后查询、重试。
- 玩家统计：原始放置/挖掘次数及九行业个人累计建设度，20 tick 同位置同动作去重。
- 行业统计：合成、放置、挖掘、实际物品消耗/耐久、有效方块交互、机器生产、实体动作、交易、钓鱼、锻造、附魔、铁砧修复和酿造。
- 交通：鞘翅、马、船、矿车、猪、炽足兽六类距离；默认 16 格 1 点，余数事务持久化。
- 每日结算：主服 8 点关日屏障、九行业快照、总量、EMA 和繁荣度；首个不完整日不进入 EMA 初始化。
- 运行保护：有界异步队列、查询限流、统计日志、批次重放、容量与数值溢出保护。
- 规则：主服数据包重载，次日 8 点统一切换；规则快照按哈希存储，旧批次按历史规则恢复。

股票系统已实现：第 3 个游戏日上市，20 支股票覆盖九行业；主服 8 点依繁荣度更新股价，10—14 点可交易，每次买卖收费 2%。新股初价随机为 100—400，股价上限为上市价 10 倍；自 0.0.8 起，退市阈值为初价 50% 与历史最高价 25% 的较高者（先分别向下取整）。退市当天可以正常卖出，窗口结束时未卖的持股按当日股价的 50% 自动返还；持仓者会收到退市风险和返还提醒。买入、卖出的账户流水分别为 `SPEND`、`STOCK`，均不影响历史总收入。客户端安装模组时，`/stock` 显示多行业筛选、排序、批量买卖及连续细线股价曲线；列表左侧勾选，中间小型“详情”按钮进入详情。详情曲线置顶，默认 360 日，可切换 7 日、30 日和全部，内容可滚动且底部交易控件固定。原版客户端仍能进服，用文字命令和原版 Dialog 交易。单服数据仍在世界 `contribution/`；群组服各服共用 MySQL，由主服推进股市时钟。具体规则见[股票设计](../design/extensions/stock.md)。

0.0.10 客户端界面中，详情页和个人页按 Esc 返回市场，市场页按 Esc 才退出；返回后勾选和详情仍可点击。刷新保留当前筛选和勾选，退出后重新打开会重置。当日价格持平时沿用最近一次涨跌颜色；市场小图的横轴固定为 30 个游戏日。

0.0.11 为股票市场、持仓列表和详情页增加可拖动滚动条，并把详情“股数”标签放在输入框左侧。贡献值专用客户端页使用固定像素列绘制账户、流水、统计和行业建设度；原版 Dialog 后备页也有标题行，流水每笔仅占一行。玩家统计的原版页压缩为放置/挖掘一行、每行三个行业。流水保存时间仍为 UTC，界面按 `rewards.timeZone` 显示到分钟（默认北京时间），筛选日期暂仍按 UTC 日界线计算。股票原版页增加涨跌、退市风险和持仓信息，以及更多排序；商店客户端页压缩布局，避免控件互相遮挡。

本版增加建设度贡献值定期发放、现实日自动签到、活动签到和系统商店。默认建设权重均为 1/10000，每 300 秒核算；每日累计在线满 10 分钟自动签到，7 日奖励为 10/10/10/10/15/25/25。`/shop` 提供商品购买，默认面包、火把和铁镐；管理员可创建限时签到活动。奖励、商品和时区在 `config/contribution/server.json` 的 `rewards` 部分配置。群组服所有节点必须同步该配置，模组会比对共享配置指纹。不包含回收、拍卖、抽奖和自定义行业。即时投掷等未定义来源不会仅因加入标签而自动获得建设度。完整行为见 [design](../design/README.md)。

0.1.0 统一贡献值客户端的管理员、签到、账户列表、变动确认和错误页导航；商店商品行可直接点选。股票滚动条只显示位置，继续使用滚轮滚动，不再响应点击或拖动。商店原版 Dialog 也可通过调试命令直接打开。

0.1.1 将股票页显示的主世界实时钟与股价核算日分离保存。刷新和实时推送使用同一主服务器世界时钟，核算落后时显示“核算中”并暂停交易，而不是在旧时间与当前时间之间跳动。群组服须确保仅生存服配置 `mainServer=true`、各服 `serverId` 唯一、`statisticsServers` 在各服一致；缺少任一指定服的关日记录会延后股票日结，服务器日志会提示等待的核算日。

## 规则资源

27 个 `craft/place/mine_<行业>.json` 只处理玩家操作，另有 18 个 use/interact 标签。相同动作下一个对象不能重复归类。熔炉产物标签、游戏事件映射与自动化独立。无损可逆合成物品禁止放入 craft 标签。

完整物品和方块来自本机 26.3 注册表，见 all_items/all_blocks；placement_map 保留物品与实际方块的一对多关系。中文设计审核表在 `design/definitions`，不由模组直接读取。

## 构建与验证

```powershell
.\run-gradle.ps1 build --offline
```

脚本使用项目内 Java 25 和 Gradle 缓存，不把 JDK 下载到 C 盘。build 自动检查全部 JSON、45 个玩家标签、查询条件、距离换算、账户 UUID 迁移、股票结算、奖励交易及原生 Dialog 的 JSON/网络编码。客户端股票和商店界面会编译进入同一 JAR；正式发布前仍应在实际客户端中检查不同 GUI 缩放下的布局。

日常 `build` 已包含嵌入式数据库首次建表、账户交易、统计、股票交易、签到与商店事务和重启持久化测试。项目专用测试 MySQL 使用 127.0.0.1:23306；仅在测试实例运行时额外执行：

```powershell
.\run-gradle.ps1 verifyDatabaseIntegration verifyStatisticsIntegration verifyMySqlStocks verifyMySqlRewards --offline
```

数据库测试使用 contribution_test_v2 和 contribution_stats_test 独立数据库，股票测试使用自动清理的随机测试库，正常测试服使用 contribution_server_test。测试工具保存在项目的 .test-tools/.test-mysql，不安装 Windows 全局服务。验证范围、结果与局限见 [Basic 验证记录](docs/VALIDATION.md)和[股票验证记录](docs/STOCK_VALIDATION.md)。

JAR 已内嵌 H2、MySQL 驱动、连接池及迁移依赖；不要安装 sources.jar。第三方许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 开发入口

- [贡献值 API](docs/API.md)
- [运行架构](../design/infrastructure/runtime-architecture.md)
- [图形界面设计](../design/basic/graphical-interface.md)
