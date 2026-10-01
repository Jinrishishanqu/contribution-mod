# 命令速查（0.0.6）

本模组的命令入口为：贡献值、建设度和签到使用 `/contribution`，股票使用 `/stock`，商店使用 `/shop`。`<…>` 是必填参数，`[…]` 是可选参数。未安装客户端模组的玩家仍可进入服务器，并使用全部文字命令和原版 Dialog。

## 普通玩家

| 命令 | 作用 |
| --- | --- |
| `/contribution` 或 `/contribution ui` | 打开贡献值首页，不含股票入口 |
| `/contribution query` | 查询自己的余额与历史总收入 |
| `/contribution history` | 查询自己的流水，聊天中可点击翻页 |
| `/contribution history-search self <条件>` | 高级流水筛选；界面也提供无需输入条件的常用筛选按钮 |
| `/contribution stats` | 查询自己的放置、挖掘及行业建设度统计 |
| `/contribution checkin` | 查看今日累计在线与自动签到状态 |
| `/contribution checkin events` | 查看当前签到活动 |
| `/contribution checkin claim <活动ID>` | 在活动期限内领取一次活动奖励 |
| `/shop` | 打开商店；原版客户端用 Dialog，安装模组的客户端用专用界面 |
| `/shop page <页码>` | 原版商店分页，通常直接点击“上一页/下一页” |
| `/shop buy <商品ID> <份数>` | 购买 1—64 份商品；余额不足不产生订单或流水 |
| `/shop retry <订单ID> <商品ID> <份数>` | 结果不明时沿用原订单 ID 和参数重试 |
| `/shop claim` | 领取商店或活动的待发物品 |
| `/stock` | 打开股票大厅；安装本模组的客户端显示绘制曲线，否则显示原版 Dialog |
| `/stock portfolio` | 查看个人持仓、市值、成本与未实现盈亏；原版客户端也可用 |
| `/stock check <股票> [week\|month\|year]` | 查看股票详情与相应时间跨度，省略时为 `week` |
| `/stock buy <股票> <股数>` | 买入 1—10000 股 |
| `/stock sell <股票> <股数>` | 卖出 1—10000 股 |
| `/stock retry <请求ID> <buy\|sell> <股票> <股数>` | 网络结果不明时按原参数重试单股交易 |
| `/stock batch <buy\|sell> <股数> <股票ID列表>` | 对逗号分隔的 1—20 支股票逐只提交同一股数的交易；不是全有或全无 |
| `/stock batch-retry <批量请求ID> <buy\|sell> <股数> <股票ID列表>` | 用原请求 ID 和完全相同参数重试；已成交的单股不会重复成交 |
| `/stock claim` | 领取因余额上限而暂未全额入账的退市返还 |

股票大厅里的选择框、股数输入和批量买卖按钮会自动组装批量命令。每天游戏时间 10:00—14:00 可交易；退市当天只能卖出，14:00 未卖的持股自动按当日股价的 50% 返还，不计入历史总收入。

原版客户端的股票 Dialog 还使用 `/stock browse <name\|price> [筛选词]`。可直接按名称或股价排序；筛选词只需填写股票名、行业名或物品 ID 的一部分，`all` 显示全部。全部 20 支股票在一页显示，不需要翻页。

## 管理员

以下命令需要原版管理员权限，通常为 OP 等级 2。`<玩家>` 接受名称、UUID，或恰好选中一名在线玩家的目标选择器。

| 命令 | 作用 |
| --- | --- |
| `/contribution query <玩家>` | 查询指定玩家账户 |
| `/contribution accounts` | 列出全部账户，聊天中可点击翻页 |
| `/contribution stats <玩家>` | 查询指定玩家统计 |
| `/contribution history <玩家>` | 查询指定玩家流水 |
| `/contribution history *` | 查询全服流水 |
| `/contribution history-search <玩家\|*> <条件>` | 按类型、来源、子服和日期高级筛选 |
| `/contribution add <玩家> <数量> <原因> <影响历史总收入> [备注]` | 增加余额；数量为 1—2147483647 |
| `/contribution remove <玩家> <数量> <原因> <影响历史总收入> [备注]` | 扣除余额；数量为 1—2147483648，余额与历史总收入不得变负 |
| `/contribution retry <请求ID> <玩家> <带符号数量> <原因> <影响历史总收入> [备注]` | 以原参数重试管理员余额变更 |
| `/contribution account create <UUID> <玩家名称>` | 仅当 UUID 尚无账户时创建零余额账户；名称已被别的 UUID 占用会拒绝 |
| `/contribution account migrate <旧UUID> <新UUID> confirm` | 将旧账户的余额、历史收入、流水、统计、持仓、交易、批量请求与退市返还迁至新 UUID |
| `/contribution bot_check` | 清理数据库中名称以 `bot_` 开头的假人账户及其玩家关联数据；执行前备份 |
| `/contribution checkin event create <ID> <标题> <开始日期> <结束日期> <贡献值> [<物品ID> <数量>]` | 在主服定义活动；日期为 `YYYY-MM-DD`，标题含空格时加引号 |
| `/contribution checkin event create-extension <ID> <标题> <开始日期> <结束日期> <提供者ID> <参数>` | 用已注册的其他模组奖励提供者定义活动 |

账户迁移是维护命令：只允许在主服务器控制台、该服没有在线玩家时执行；群组服应先停掉其他子服，备份共享数据库，并等待所有未确定结果的交易完成。目标 UUID 若已有账户，必须是完全空白账户；有余额、流水、统计或股票数据时一律拒绝覆盖。迁移会保存审计记录。迁移后，旧 UUID 发出的未完成请求不应再重试。命令中的 `confirm` 是防误操作确认词，执行前请仔细核对两个 UUID。

## 高级流水筛选与自动翻页

筛选条件由空格分隔的 `名称=值` 组成，支持 `type`、`source`、`server`、`from`、`to`。日期为 UTC 的 `YYYY-MM-DD`，结束日期包含当天。例如：

```text
/contribution history-search self type=STOCK source=contribution:stock from=2026-09-01
/contribution history-search * server=survival type=REFUND
```

`/contribution history-next`、`history-search-next`、`accounts-next` 及 `/contribution ui <页面参数>` 用于可点击的翻页或界面内部跳转，普通使用无需手动输入。管理员图形界面也支持点击常用流水类型筛选；复杂条件仍可在高级输入框或文字命令中填写。
