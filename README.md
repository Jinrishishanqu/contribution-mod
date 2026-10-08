# CSU-YSU 贡献值系统

面向 Minecraft Java Edition 26.3 的 Fabric 服务端模组，为生存服提供**贡献值经济**、**行业建设度统计**和**股票市场**。

模组只有一份通用 JAR，逻辑在服务端运行，**原版客户端可以直接进服**并使用全部功能；在客户端安装同一 JAR 后，`/contribution`、`/stock` 和 `/shop` 会切换到专用的像素绘制界面。

| 项目 | 值 |
| --- | --- |
| 当前版本 | `0.2.6`（见 [code/gradle.properties](code/gradle.properties)） |
| 运行环境 | Minecraft 26.3 / Fabric Loader 0.19.5 / Fabric API 0.161.0+26.3 / Java 25 |
| 模组 ID | `contribution` |
| 设计入口 | [design/README.md](design/README.md) |

> Java 25 由项目内 `.jdk/` 提供，Gradle 缓存位于 `.gradle-home/`，`code/run-gradle.ps1` 会自动指向这两处，不会把 JDK 下载到系统盘。

## 功能范围

### 已实现

- **贡献值账户**：整数余额与历史总收入、行锁事务、全局唯一幂等 ID、退款、离线名称/UUID 操作、公共 Java API。
- **流水**：权限控制、游标分页、按类型/来源/子服/日期筛选、按 UTC 自然月归档。
- **服务器建设度**：九个内置行业的合成、放置、挖掘、物品消耗、方块交互、机器生产、实体动作、交易、钓鱼、锻造、附魔、铁砧与酿造；20 tick 同位置去重。
- **每日结算与繁荣度**：主服 8 点关日屏障，长期/近期 EMA 与繁荣度公式。
- **玩家统计**：原始放置/挖掘次数与九行业个人累计建设度。
- **交通**：鞘翅、马、船、矿车、猪、炽足兽六类距离，默认每 16 格 1 点，余数持久化。
- **股票市场**：第 3 个游戏日上市，20 支正常股票覆盖九行业，从确认表导入1,535个候选，待退市股票保留卖出窗口，10—14 点交易、2% 手续费、繁荣度驱动的自适应日涨跌、退市与自动返还、黑天鹅事件。
- **建设度奖励**：按九行业权重（默认 1/1000）定期发放贡献值。
- **签到**：现实日在线满 10 分钟自动每日签到（7 日周期奖励），以及管理员开设的活动签到。
- **系统商城**：以贡献值购买原版物品，带订单幂等与待发物品领取队列。
- **图形界面**：账户、流水、统计、行业总览、签到、管理员操作；原版客户端走 26.3 原生 Dialog。

### 尚未实现

- 商城的**回收、拍卖、抽奖**模块；自定义行业；`TODO.md` 中记录的其他玩法决策项。

完整行为与指令见 [离线 Wiki](wiki/index.html) 与 [指令 XLSX](documentation/commands.xlsx)，架构规则见 [设计文档](design/README.md)。维护规则与本轮审查见 [代码规范](code/docs/CODE_STYLE.md) 和 [审查报告](code/docs/AUDIT-0.1.8.md)。

## 目录结构

| 路径 | 内容 |
| --- | --- |
| [code/](code/) | Fabric 模组本体（Gradle 项目，含源码、资源、测试与开发文档） |
| [design/](design/) | 设计文档，唯一设计入口为 [design/README.md](design/README.md) |
| [design/definitions/](design/definitions/) | 中文行业定义审核表（CSV/XLSX），模组运行时不读取 |
| [design/items/](design/items/) | 内置物品功能及旧原型分析，本轮按要求保持不动 |
| [stock-simulation/](stock-simulation/) | 独立的 Python 股票算法可视化程序及其输出 |
| [datapack/](datapack/) | 被模组取代的旧数据包原型，仅作历史参考，不参与模组运行 |
| [TODO.md](TODO.md) | 仍需项目负责人决定的玩法机制与数值 |

## 快速开始

### 使用模组

1. 构建或取得 `code/build/libs/CSU-YSU-contribution-system-<版本>.jar`。
2. 把该 JAR 与匹配版本的 Fabric API 放进服务端 `mods/` 目录。
3. 启动服务器。单人游戏和单服会**自动**在世界目录建立嵌入式数据库，无需安装数据库服务。

进服后输入 `/contribution` 打开贡献值界面，`/stock` 打开股票市场，`/shop` 打开商店。完整安装说明、群组服 MySQL 配置与备份建议见 [code/README.md](code/README.md)。

### 构建与验证

```powershell
cd code
.\run-gradle.ps1 build --offline
```

构建会自动执行资源校验（JSON 可解析、45 个玩家标签互斥、可逆合成排除）、账户查询、距离换算、股票与奖励事务、原生 Dialog 编解码和嵌入式数据库回归。需要真实 MySQL 的测试见 [code/README.md](code/README.md)。

验证范围、结果与**未覆盖的边界**记录在 [Basic 验证记录](code/docs/VALIDATION.md) 与 [股票验证记录](code/docs/STOCK_VALIDATION.md)。其中明确说明：自动化构建与服务端测试不能替代真实客户端的逐项鼠标与视觉验收。

### 运行股票算法可视化

```powershell
cd stock-simulation
python stock_visualizer.py
```

依赖 Python 3.10+、NumPy、Matplotlib、PyYAML（见 [requirements.txt](requirements.txt)）。该程序需要从 `stock-simulation/` 目录内运行，结果写入其 `output/` 子目录。详见 [stock-simulation/README.md](stock-simulation/README.md)。

## 数据与部署要点

- **单机 / 单服**：默认使用世界目录 `contribution/contribution.mv.db`，统计恢复日志在同一目录的 `statistics-journal/`。备份或回退时先停服，再整体复制 `contribution/` 文件夹；不要在运行时复制数据库文件，也不要手工编辑 `.mv.db`。
- **群组服**：所有子服连接同一个 MySQL 8.4，`config/contribution/server.json` 设置 `database.mode="mysql"`，各服 `serverId` 唯一，仅生存服 `mainServer=true`。权威数据在 MySQL 中，必须单独备份数据库；子服世界里的 `contribution/` 只是本地恢复日志。
- **配置**：`config/contribution/server.json` 保存数据库、服务器身份与 `rewards`（建设权重、时区、签到、商品）。该文件已被 `.gitignore` 排除，不要提交含口令的副本。数据库口令可交由环境变量 `CONTRIBUTION_DB_PASSWORD` 覆盖。
- 奖励配置写入共享数据库指纹，群组服各节点必须保持一致；商店目录首次导入后由共享数据库管理，不再进入奖励配置指纹。

## 第三方与许可

模组遵循 `All-Rights-Reserved`。内嵌的 HikariCP、MySQL Connector/J、H2 与 Flyway 的许可与来源见 [code/THIRD_PARTY_NOTICES.md](code/THIRD_PARTY_NOTICES.md)。

## 其他模组联动

`cn.contribution.api` 提供稳定的余额变更 API，调用示例、幂等与重试规则见 [API 说明](code/docs/API.md)。
