# 贡献值账户

## 0.2.3 总建设度排行汇总

总建设度榜从player_development_total读取九内置行业精确总和，V21从旧player_industry_stats一次性回填，后续仅在统计批次事务中更新涉及玩家。原明细保留为权威数据，未知行业不计入；UUID迁移、机器人删除覆盖汇总行。榜单入口、15秒缓存、前100名和原版Dialog兼容不变。详见[性能架构](../infrastructure/runtime-architecture.md)。

> 当前实现基线：0.1.8。完整指令与权限以[离线 Wiki](../../documentation/wiki.html)和[指令表](../../documentation/commands.xlsx)为准；功能变更须同步代码、本文及统一文档源。

## 账户系统

### 账户身份

每名玩家有且仅有一个账户。在代币系统的语境中，“玩家”即指该玩家的账户。

- 当前登录注册只创建新 UUID 的账户，不自动改写已有账户名称；更名或 UUID 变化须由管理员核对身份，不能按名称悄悄重绑余额。
- 支持通过玩家名称或 UUID 查询、操作离线玩家。
- 使用目标选择器的命令只有在选择器恰好选中一名玩家时才会成功。

### 数值规则

| 项目 | 规则 |
| --- | --- |
| 代币类型 | 整数 |
| 账户余额 | 不允许为负数 |
| 账户余额范围 | $0$ 至 $2^{31}-1$ |
| 历史总收入范围 | $0$ 至 $2^{31}-1$ |
| 单次数量变动范围 | $-2^{31}$ 至 $2^{31}-1$ |

默认情况下，历史总收入累计除退款外的正向代币变动，负向变动不影响它。管理员命令或外部 API 可以显式指定本次是否影响历史总收入：指定 `true` 时，正向变动增加、负向变动减少相同数量；指定 `false` 时不改变。退款始终不影响历史总收入。余额和历史总收入都不得低于 $0$ 或高于 $2^{31}-1$。

### 账户表

账户数据存储在 `contribution_account` 表中。下表给出群组服 MySQL 结构；单机 H2 使用同名字段与等价约束。

| 字段 | MySQL 类型 | 约束与说明 |
| --- | --- | --- |
| `player_uuid` | `BINARY(16)` | 主键；账户的唯一玩家标识 |
| `player_name` | `VARCHAR(16)` | 非空；保留玩家名称的原始大小写，使用区分大小写的 ASCII 排序规则 |
| `player_name_normalized` | `VARCHAR(16)` | 非空、唯一；保存名称的小写形式，用于不区分大小写地解析账户 |
| `balance` | `INT` | 非空；约束为 $0\le balance\le 2^{31}-1$ |
| `total_income` | `INT` | 非空；约束为 $0\le total\_income\le 2^{31}-1$ |
| `created_at` | `DATETIME(6)` | 非空；账户创建时间 |
| `updated_at` | `DATETIME(6)` | 非空；账户最后更新时间 |

除主键外，`player_name_normalized` 建立唯一索引。写入前由应用层按 ASCII 小写规则生成该字段；显示和流水快照仍使用 `player_name`。余额修改统一使用行锁，因此账户表不增加乐观锁版本字段；`updated_at` 只用于审计和缓存失效，不参与并发控制。

### 流水表

账户流水存储在 `contribution_transaction` 表中。下表给出群组服 MySQL 结构；单机 H2 使用同名字段与等价约束。

| 字段 | MySQL 类型 | 约束与说明 |
| --- | --- | --- |
| `record_no` | `BIGINT` | 自增主键；作为 InnoDB 顺序聚簇键和流水分页游标，不作为对外业务身份 |
| `transaction_id` | `BINARY(16)` | 非空、唯一；对外使用的全局唯一流水 ID |
| `idempotency_id` | `BINARY(16)` | 非空、唯一；全局唯一幂等 ID |
| `request_hash` | `BINARY(32)` | 非空；规范化请求的 SHA-256，用于识别幂等冲突 |
| `player_uuid` | `BINARY(16)` | 非空；目标账户 UUID |
| `player_name` | `VARCHAR(16)` | 非空；操作发生时记录的玩家名称 |
| `amount` | `INT` | 非空且不能为 $0$；本次数量变动 |
| `income_delta` | `INT` | 非空；本次历史总收入变化量，可以为负数或 $0$ |
| `balance_before` | `INT` | 非空；变动前余额 |
| `balance_after` | `INT` | 非空；变动后余额 |
| `type` | `VARCHAR(32)` | 非空；操作类型 |
| `source` | `VARCHAR(128)` | 非空；命令或调用模组提供的来源 ID |
| `reason` | `VARCHAR(64)` | 非空；使用 `utf8mb4` |
| `operator` | `VARCHAR(128)` | 非空；发起操作的玩家、控制台或模组主体 |
| `server_id` | `VARCHAR(64)` | 非空；操作发生的子服 ID |
| `created_at` | `DATETIME(6)` | 非空；流水时间 |
| `note` | `VARCHAR(64)` | 可空；使用 `utf8mb4` |

流水表建立以下查询索引：

- `(player_uuid, created_at DESC, record_no DESC)`：查询指定玩家流水；
- `(created_at DESC, record_no DESC)`：查询全服流水；
- `(type, created_at DESC, record_no DESC)`：按操作类型筛选；
- `(source, created_at DESC, record_no DESC)`：按来源筛选；
- `(server_id, created_at DESC, record_no DESC)`：按子服筛选。

数据库约束要求 `balance_after=balance_before+amount`。`income_delta` 只能是 `0` 或 `amount`；退款必须为 `0`。账户的 `total_income` 与流水写入在同一事务中按 `income_delta` 更新，写入前检查上下限。

所有现实时间在写入数据库前转换为 UTC，统一使用微秒精度的 `DATETIME(6)`。Java 代码使用 `Instant` 表示现实时间。基于游戏日的任务另外保存整数 `game_day`，不能通过现实时间反推游戏日。

数据库结构使用 Flyway 版本化 SQL 管理，迁移文件采用 `V<版本>__<说明>.sql` 命名。单机嵌入式库首次启动自动创建，并在后续启动时迁移；群组服生产环境只允许生存服或独立部署流程执行 MySQL 迁移，其他子服启动时只校验结构版本，发现版本不匹配时拒绝启用数据库功能，避免多个子服并发修改表结构。

账户及现有拓展会产生以下类型；筛选器也识别 `EXCHANGE` 与历史遗留的股票交易类型：

| 类型 | 含义 |
| --- | --- |
| `ADMIN` | 管理员命令 |
| `CHECK_IN` | 签到收益 |
| `EVENT_CHECK_IN` | 活动签到贡献值奖励 |
| `DEVELOP` | 服务器建设度奖励；只增加余额和历史总收入 |
| `SHOP_BUY` | 商店购买扣款；不改变历史总收入 |
| `SPEND` | 消费扣款 |
| `EXCHANGE` | 兑换游戏币时销毁的贡献值扣款；不改变历史总收入 |
| `BONUS` | 额外收益 |
| `EXTERNAL` | 外部调用 |
| `REFUND` | 退款 |
| `TAX` | 收税扣款 |

### 账户查询

| 命令 | 权限 | 客户端要求 | 功能 |
| --- | --- | --- | --- |
| `/contribution query` | 所有玩家 | 无 | 查询自己的余额和历史总收入 |
| `/contribution query <玩家>` | 管理员 | 无 | 查询指定玩家的余额和历史总收入 |
| `/contribution accounts` | 管理员 | 无 | 以聊天分页方式查看所有玩家的账户情况 |
| `/contribution history` | 所有玩家 | 无 | 以聊天分页方式查看自己的流水记录 |
| `/contribution history <玩家>` | 管理员 | 无 | 以聊天分页方式查看指定玩家的流水记录 |
| `/contribution history *` | 管理员 | 无 | 以聊天分页方式查看全服流水记录 |
| `/contribution history-search <范围> <筛选条件>` | 所有玩家查询自己；管理员可查询他人或全服 | 无 | 对在线流水应用筛选并以聊天分页显示 |

其中 `<玩家>` 支持目标选择器、玩家名称或 UUID；目标选择器必须恰好选中一名玩家。

管理员账户列表也使用可点击聊天文本按玩家名称排序翻页。`/contribution query *` 不再用于查询列表，统一使用 `/contribution accounts`；翻页命令由聊天文本生成，不要求玩家手动输入游标。

流水查询默认按 `created_at`、`record_no` 倒序排列，使用二者组成的游标分页，不使用大偏移量分页。对外返回的流水身份仍是 `transaction_id`，不暴露对 `record_no` 的业务依赖。默认每页 20 条，单次请求最多返回 100 条。允许按玩家、操作类型、来源、子服和时间范围筛选。

`history-search` 的 `<范围>` 使用 `self` 表示本人、`*` 表示全服，管理员也可填写玩家名称、UUID 或恰好选中一人的目标选择器。筛选条件使用一个或多个 `名称=值`，以空格分隔；支持 `type`、`source`、`server`、`from`、`to`，后两者采用 UTC 的 `YYYY-MM-DD` 日期且包含 `to` 当天。例如：

```text
/contribution history-search self type=EXTERNAL from=2026-09-01
/contribution history-search * server=survival to=2026-09-28
```

服务端独立校验筛选条件和权限，翻页保留筛选。管理员联合查询在线及归档表，默认最近 365 天；指定历史 from/to 可查询更早记录，单次最多 365 天。仅指定 from 时向后补足 365 天，仅指定 to 时向前补足 365 天。

普通玩家默认最多查询最近 90 天的本人流水；管理员单次查询范围最多为一年。在线表保留至少一年，超过一年且已完整过期的 UTC 自然月搬入同库的 `contribution_transaction_archive`，每次最多 500 条。复制和删除处于同一事务，流水与幂等 ID 永久保留；未完整过期的月份暂留在线表。归档记录仍可由管理员查询，但不参与普通玩家的即时分页。

原版客户端同时支持聊天分页与原生 Dialog 图形页面。输入 `/contribution` 打开界面，使用相同权限、筛选及游标限制，详见[图形界面](graphical-interface.md)。

### 账户创建与 UUID 迁移

管理员命令 `/contribution account create <UUID> <玩家名称>`：仅当该 UUID 尚无账户、该名称也未被其他 UUID 占用时，创建余额和历史总收入均为零的账户。玩家首次正常进入服务器仍自动创建账户。名称冲突不会静默绑定到错误 UUID，而是提示管理员先核对旧账户。

名称以 `bot_` 开头的假人（不区分大小写）进入游戏时不创建贡献值账户，也不累计每日在线签到时间；管理员显式创建同名前缀账户也会被拒绝。管理员可执行 `/contribution bot_check`，在一笔数据库事务中找出当前名称仍为 `bot_` 前缀的账户，并删除账户及该 UUID 的流水、玩家统计、股票持仓和交易、退市返还、签到、商店及奖励记录。该命令不会回滚已经计入服务器全局建设度的历史汇总；执行前应备份数据库，清理后无法从模组内恢复被删的玩家记录。非 `bot_` 账户不受影响。

`/contribution account migrate <旧UUID> <新UUID> confirm` 将旧 UUID 的账户转到新 UUID。仅允许在主服务器控制台、该服务器没有在线玩家时执行；群组服运维还应先停止其他子服、备份共享数据库并处理完未确定结果的交易。目标 UUID 可以没有账户；如果已存在，则必须是完全空白账户，不能有余额、历史收入、流水、统计、股票交易、持仓或返还。迁移使用同一数据库事务和行锁，同时更新账户、在线及归档流水、玩家统计、交通距离余量、股票持仓及交易、批量请求和退市返还，并记录 `account_uuid_migration` 审计行。目标已有数据时一律拒绝覆盖或合并。迁移后旧 UUID 发出的未完成请求不应继续重试。

全部命令见[命令速查](../../code/docs/COMMANDS.md)。

## 余额变更

### API

模组通过 `cn.contribution.api` 包提供稳定的公共 API。其他模组只能依赖该包，不应直接访问账户仓库、数据库连接、流水服务或其他内部实现。

公共入口为 `ContributionApi`，余额变更函数命名为：

```java
CompletableFuture<BalanceChangeResult> changeBalance(
        BalanceChangeRequest request
);
```

API 不分别提供 `add` 和 `remove` 函数。所有余额变更统一通过 `changeBalance` 提交，使数值校验、余额检查、历史总收入更新、幂等检查和流水写入无法被调用方绕过。

#### 余额变更请求

`BalanceChangeRequest` 至少包含以下字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `idempotencyId` | `UUID` | 调用方为一次业务操作生成的全局唯一幂等 ID |
| `target` | `AccountTarget` | 通过玩家 UUID 或玩家名称指定的单个账户 |
| `amount` | `int` | 带符号的余额变动量；正数增加余额，负数减少余额，不能为 `0` |
| `type` | `BalanceChangeType` | 余额变更类型，目前支持 `EXTERNAL` 和 `REFUND` |
| `source` | `Identifier` | 调用来源，例如 `quest_mod:quest_reward`；字符串形式最多 128 个字符。调用方应使用自己的模组 ID 作为命名空间，但当前服务端不以此字段验证真实调用者身份 |
| `reason` | `String` | 流水原因，最多 64 个 Unicode 字符 |
| `note` | `String` | 可选备注，空字符串表示无备注，最多 64 个 Unicode 字符 |
| `affectTotalIncome` | `boolean` | 是否将带符号的 `amount` 同步作用于历史总收入；退款必须为 `false` |

`AccountTarget` 提供以下工厂函数：

```java
AccountTarget.byUuid(UUID playerUuid);
AccountTarget.byName(String playerName);
```

API 不接受目标选择器。每次请求只能对应一个明确的账户，并且支持操作离线玩家。

`EXTERNAL` 允许正向或负向变动。旧的七参数构造器保留原默认规则：正向 `EXTERNAL` 计入历史总收入，负向 `EXTERNAL` 不影响历史总收入；新的八参数构造器可明确传入 `affectTotalIncome`。`REFUND` 的 `amount` 必须为正数，且 `affectTotalIncome` 必须为 `false`。

#### 余额变更结果

`BalanceChangeResult` 至少提供以下信息：

| 字段 | 说明 |
| --- | --- |
| `status` | 操作状态 |
| `idempotencyId` | 本次请求的幂等 ID |
| `transactionId` | 成功时的流水 ID；失败或处理中时为空 |
| `balanceBefore` | 成功时的变动前余额；其他状态为空 |
| `balanceAfter` | 成功时的变动后余额；其他状态为空 |
| `replayed` | 是否为幂等重试返回的既有结果 |
| `message` | 可直接记录或反馈的结果说明 |

余额等可选数值使用 `OptionalInt` 表示，流水 ID 使用 `Optional<UUID>` 表示，不使用 `0` 或负数作为缺失值。

`BalanceChangeStatus` 至少包含：

| 状态 | 含义 |
| --- | --- |
| `SUCCESS` | 操作成功，或幂等重试取得了原成功结果 |
| `ACCOUNT_NOT_FOUND` | 玩家名称或 UUID 对应的账户不存在 |
| `INVALID_AMOUNT` | 数量为 `0`、超出范围，或不符合操作类型要求 |
| `INVALID_TEXT` | 原因、备注或来源标识不符合要求 |
| `INSUFFICIENT_BALANCE` | 扣款后的余额将小于 `0`，或历史总收入将小于 `0`；后者反馈“历史总收入不足” |
| `BALANCE_OVERFLOW` | 操作后的余额或历史总收入将超过 $2^{31}-1$ |
| `DATABASE_UNAVAILABLE` | 数据服务不可用或结果暂时无法确认；必须沿用原幂等 ID 重试 |
| `IN_PROGRESS` | 为兼容保留；当前实现不会主动返回该状态，异步调用等待事务结果，连接故障时返回 `DATABASE_UNAVAILABLE` |
| `IDEMPOTENCY_CONFLICT` | 相同幂等 ID 被用于内容不同的请求 |

参数错误、余额或历史总收入不足等可预期的业务失败通过 `BalanceChangeResult` 返回，不使用异常表示。数据库异常先按原幂等 ID 尝试查询已提交流水，仍无法确认时返回 `DATABASE_UNAVAILABLE`；其他未恢复的内部错误才可能使 `CompletableFuture` 异常完成。

#### 事务与幂等规则

余额、变动前后数值、时间、子服和流水 ID 等信息由代币系统计算。幂等检查、账户行锁、余额校验、账户更新和流水写入必须在同一个数据库事务中完成。

调用方必须为一次业务操作生成并保存一个幂等 ID。网络超时、`IN_PROGRESS` 或返回结果丢失后，只能使用完全相同的请求和原幂等 ID 重试。系统返回原操作结果，不重复修改账户。同一幂等 ID 对应的目标、数量、类型、来源、原因、备注或 `affectTotalIncome` 发生变化时，返回 `IDEMPOTENCY_CONFLICT`。升级前流水的原幂等哈希在旧默认规则下仍可重试。

#### 异步与线程规则

`changeBalance` 异步执行数据库操作。调用方不得在服务器主线程使用 `join()`、`get()` 或其他阻塞方式等待结果。

`CompletableFuture` 的完成回调不保证位于服务器线程。调用方需要修改世界、访问实体或向玩家发送消息时，必须通过 `MinecraftServer.execute` 返回服务器线程。

API 只能在逻辑服务端调用。客户端界面需要修改余额时，必须先向服务端发送经过校验的请求，再由服务端调用 API。

其他模组的完整依赖配置、调用示例、退款示例和重试方式记录在 `../../code/docs/API.md`。

#### 调用方信任与兼容性

Java 模组 API 的调用方与 Contribution 运行在同一个 JVM 中，属于服务器管理员主动安装的受信任代码。`source` 用于审计、统计、日志和可选的来源白名单，不作为无法伪造的安全身份凭证。API 仍会独立执行全部参数、数值和余额校验，任何调用方都不能通过公共接口绕过这些规则。

公共 API 遵循语义化版本。`cn.contribution.api` 中的类型在同一主版本内保持源代码和二进制兼容；删除类型、修改函数签名或改变既有状态语义时必须增加主版本。兼容版本可以增加新的结果状态，因此调用方处理 `BalanceChangeStatus` 时必须保留未知状态分支，不能只编写无 `default` 的穷举逻辑。

### 管理员命令

管理员（OP 2）或服务器控制台可执行 `/contribution diagnose`，异步检查数据库、主服市场时钟、规则与关账/核算进度，并向聊天栏或控制台反馈报告。自动恢复只重试正常连接与后台任务，不删除数据或跳过校验；约100 tick后复查。每实例最多一个诊断会话，15秒冷却。详情见[运行时与性能架构](../infrastructure/runtime-architecture.md)。

管理员可以使用以下命令修改账户余额：

```text
/contribution add <玩家> <数量> <原因> <影响历史总收入:true|false> [备注]
/contribution remove <玩家> <数量> <原因> <影响历史总收入:true|false> [备注]
```

`add` 表示增加余额，`remove` 表示减少余额。除备注外，其余参数均为必填项。布尔值为 `true` 时，`add` 同时增加历史总收入，`remove` 同时减少历史总收入；为 `false` 时只改变余额。图形管理操作已移除，余额变动仅通过 add/remove 命令。成功执行后，账户表和流水表会在同一事务中更新，`operator` 记录为 `command-<执行者>`，`source` 记录为 `contribution:admin_command`；历史旧图形操作流水可能仍含 `contribution:admin_dialog`，保留用于审计。

管理员不能绕过余额范围和数量范围限制。

每次命令先返回请求 ID 和可点击的原请求重试文本，再异步执行。`/contribution retry <幂等UUID> <玩家> <带符号变动量> <原因> <影响历史总收入:true|false> [备注]` 使用原请求重试；原因包含空格时按命令提示加引号。网络中断或反馈不确定时应使用此入口，而不是再次执行会生成新请求 ID 的 add/remove。参数必须与原请求相同，改变目标表示方式、原因、备注或布尔值也会被视为幂等冲突。

## 命令与权限

### 权限

- 所有玩家都可以查询自己的账户和流水。
- 管理员可以查询其他玩家、查询全服流水以及增减代币。
- 管理员不能绕过余额和数量限制。

### 字符限制

命令中的原因和备注最多包含 64 个 Unicode 字符。MySQL 使用 `utf8mb4` 字符集时，相应字段使用 `VARCHAR(64)`。

### 反馈文本

文字命令使用 `[贡献值]` 前缀，图形页面用标题和正文反馈。参数语法错误及权限拒绝也可能由原版命令框架直接显示。

| 场景 | 当前业务反馈 |
| --- | --- |
| 选择多个目标 | 目标选择器必须恰好选择一名玩家 |
| 账户不存在 | 未找到该玩家账户 |
| 余额不足 | 余额不足 |
| 历史总收入不足 | 历史总收入不足 |
| 数值溢出 | 余额或历史总收入超过上限 |
| 文本无效 | 来源、原因或备注无效 |
| 数据库异常 | 数据服务暂时不可用，请稍后重试 |
| 幂等冲突 | 幂等 ID 已用于不同请求 |
| 请求过快 | 操作过快，请稍后重试 |
| 查询成功 | <玩家>：余额 <余额>，历史总收入 <历史总收入> |
| 操作成功 | 操作成功，余额 <之前> → <之后>，流水 ID <流水ID> |

失败请求不创建成功流水。提交状态不确定时，“稍后重试”指沿用原请求与幂等 ID，不代表可以重复创建新交易。股票功能的反馈与重试规则见[股票系统](../extensions/stock.md)；其他拓展功能在实现时单独约定。
