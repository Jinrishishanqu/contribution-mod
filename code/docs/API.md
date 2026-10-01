# 贡献值 API 调用说明

本文面向需要与 Contribution 模组联动的 Fabric 模组作者，描述如何为在线或离线玩家增加、扣除或退还贡献值。

> 当前状态：`cn.contribution.api` 已包含在 0.0.3 开发版 JAR 中。它仍是早期接口，正式发布前请以本项目的源码和构建产物核对兼容性。

## 运行要求

- Minecraft Java Edition 26.3；
- Fabric Loader 0.19.5 或更高的兼容版本；
- Java 25；
- Contribution 0.0.3；
- API 只能在逻辑服务端调用。

Fabric 使用 `fabric.mod.json` 声明模组身份、入口点和依赖关系。`depends` 表示缺少依赖时拒绝启动，`suggests` 用于可选联动。参见 [Fabric 的 `fabric.mod.json` 文档](https://docs.fabricmc.net/develop/loader/fabric-mod-json)。

## 添加开发依赖

在 Contribution 尚未发布到 Maven 仓库时，可以把它的开发 JAR 放进调用模组的 `libs` 目录：

```groovy
dependencies {
    compileOnly files("libs/CSU-YSU-contribution-system-0.0.3.jar")
}
```

`compileOnly` 只用于编译。运行服务器时，Contribution 模组本体仍需单独放入服务器的 `mods` 目录，不能把完整 Contribution JAR 嵌入调用模组。

目前尚未发布单独的 API 构件或 Maven 仓库。

## 声明必须依赖

如果你的模组没有 Contribution 就无法工作，在 `fabric.mod.json` 中声明必须依赖：

```json
{
  "depends": {
    "fabricloader": ">=0.19.5",
    "minecraft": "~26.3",
    "contribution": ">=0.0.3"
  }
}
```

Fabric Loader 会保证 Contribution 存在并满足版本要求，否则拒绝加载调用模组。

## 可选联动

如果贡献值功能只是可选功能，则使用 `suggests`：

```json
{
  "suggests": {
    "contribution": ">=0.0.3"
  }
}
```

调用前检查模组是否存在：

```java
if (FabricLoader.getInstance().isModLoaded("contribution")) {
    ContributionIntegration.initialize();
}
```

可选联动代码应放在独立的 `ContributionIntegration` 类中。只有确认 Contribution 已加载后，才能加载这个类或访问 `cn.contribution.api` 下的类型，避免缺少 API 类时发生类加载错误。Fabric Loader 提供 `FabricLoader.getInstance().isModLoaded` 检查其他模组是否存在，参见 [Fabric Loader 文档](https://docs.fabricmc.net/develop/loader/)。

## 公共入口

公共入口类为：

```java
import cn.contribution.api.ContributionApi;

ContributionApi api = ContributionApi.getInstance();
```

余额变更统一使用：

```java
CompletableFuture<BalanceChangeResult> changeBalance(
        BalanceChangeRequest request
);
```

API 不提供绕过校验的直接账户写入函数，也不提供独立的 `add`、`remove` 函数。

## 构造目标账户

通过 UUID 操作玩家：

```java
AccountTarget target = AccountTarget.byUuid(playerUuid);
```

通过玩家名称操作玩家：

```java
AccountTarget target = AccountTarget.byName("PlayerName");
```

两种方式都支持离线玩家。API 不接受命令目标选择器，每个请求只能对应一个明确账户。

只要调用方已经持有玩家 UUID，就应优先使用 UUID，避免不必要的名称查询。

## 请求字段

`BalanceChangeRequest` 包含：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `idempotencyId` | 是 | 调用方生成并保存的全局唯一 UUID |
| `target` | 是 | UUID 或名称形式的目标账户 |
| `amount` | 是 | 带符号的 `int`；正数增加，负数扣除，不能为 `0` |
| `type` | 是 | `EXTERNAL` 或 `REFUND` |
| `source` | 是 | 调用来源，例如 `quest_mod:quest_reward` |
| `reason` | 是 | 流水原因，最多 64 个 Unicode 字符 |
| `note` | 是 | 可为空字符串，最多 64 个 Unicode 字符 |
| `affectTotalIncome` | 否 | 八参数构造器可显式指定；`true` 令历史总收入同步增减，`false` 不改变；七参数构造器沿用默认规则 |

数值和历史总收入规则如下：

| 类型 | `amount` | 历史总收入 |
| --- | ---: | --- |
| `EXTERNAL` | 正数 | 增加相同数量 |
| `EXTERNAL` | 负数 | 不变 |
| `REFUND` | 正数 | 不变 |
| `REFUND` | `0` 或负数 | 请求无效 |

上表是七参数构造器的默认行为。需要冲销既有收入时，可在末尾追加 `true`：

```java
new BalanceChangeRequest(idempotencyId, target, -200,
        BalanceChangeType.EXTERNAL, source, "冲销误发奖励", "", true);
```

此时余额和历史总收入各减少 200。若任一数值将低于 0，操作被拒绝且不写流水。八参数构造器传入 `false` 时只变更余额；`REFUND` 只能传 `false`。布尔值属于幂等请求内容，重试时不能改变。

`source` 使用调用模组自己的模组 ID 作为命名空间，并以路径区分业务来源：

```java
Identifier source = Identifier.fromNamespaceAndPath(
        "quest_mod",
        "quest_reward"
);
```

不要冒用 `contribution` 或其他模组的命名空间。

## 增加贡献值

以下示例为玩家发放 500 贡献值：

```java
UUID idempotencyId = UUID.randomUUID();

BalanceChangeRequest request = new BalanceChangeRequest(
        idempotencyId,
        AccountTarget.byUuid(playerUuid),
        500,
        BalanceChangeType.EXTERNAL,
        Identifier.fromNamespaceAndPath("quest_mod", "quest_reward"),
        "完成任务奖励",
        "任务编号：main_001"
);

ContributionApi.getInstance().changeBalance(request);
```

这次正向 `EXTERNAL` 变动会同时增加余额和历史总收入。

## 扣除贡献值

以下示例扣除 200 贡献值：

```java
BalanceChangeRequest request = new BalanceChangeRequest(
        idempotencyId,
        AccountTarget.byUuid(playerUuid),
        -200,
        BalanceChangeType.EXTERNAL,
        Identifier.fromNamespaceAndPath("shop_mod", "purchase"),
        "购买商城物品",
        "商品编号：building_pack"
);
```

如果最终应付值大于玩家余额，结果为 `INSUFFICIENT_BALANCE`。系统不会修改余额或写入流水，也不会自行交付商品。调用方必须在收到 `SUCCESS` 后才能交付商品；如果商品交付失败，应使用单独的退款业务操作处理，不能修改原请求后重复使用原幂等 ID。

## 退款

退款使用正数和 `REFUND`：

```java
BalanceChangeRequest refundRequest = new BalanceChangeRequest(
        refundIdempotencyId,
        AccountTarget.byUuid(playerUuid),
        200,
        BalanceChangeType.REFUND,
        Identifier.fromNamespaceAndPath("shop_mod", "order_refund"),
        "商城订单退款",
        "订单编号：20260926001"
);
```

退款会增加余额，但完全不影响历史总收入。退款是一笔新的业务操作，必须使用新的幂等 ID，不能复用原扣款操作的幂等 ID。

## 处理异步结果

数据库操作异步执行。不要在服务器主线程调用 `join()`、`get()` 或循环等待结果。

```java
ContributionApi.getInstance()
        .changeBalance(request)
        .thenAccept(result -> {
            server.execute(() -> {
                if (result.successful()) {
                    int balanceAfter = result.balanceAfter().orElseThrow();
                    player.sendSystemMessage(Component.literal(
                            "贡献值操作成功，当前余额：" + balanceAfter
                    ));
                } else {
                    player.sendSystemMessage(Component.literal(result.message()));
                }
            });
        });
```

`CompletableFuture` 的完成回调不保证在服务器线程运行。访问玩家实体、修改世界或发送游戏消息前，必须使用 `MinecraftServer.execute` 返回服务器线程。

## 返回状态

| 状态 | 是否修改账户 | 调用方处理 |
| --- | --- | --- |
| `SUCCESS` | 是，或已由原请求完成 | 读取流水 ID 和变动后余额；继续后续业务 |
| `ACCOUNT_NOT_FOUND` | 否 | 检查玩家 UUID 或名称 |
| `INVALID_AMOUNT` | 否 | 修正数量或操作类型 |
| `INVALID_TEXT` | 否 | 修正来源、原因或备注 |
| `INSUFFICIENT_BALANCE` | 否 | 提示余额不足，不交付商品 |
| `BALANCE_OVERFLOW` | 否 | 停止增加并记录错误 |
| `DATABASE_UNAVAILABLE` | 无法统一断言；连接中断时提交结果可能不确定 | 提示稍后使用原请求、原幂等 ID 重试，不交付商品，也不要直接补偿或创建新请求 |
| `IN_PROGRESS` | 尚未确定 | 预留状态；若未来返回，延迟后使用原请求重试 |
| `IDEMPOTENCY_CONFLICT` | 否 | 视为调用方程序错误，不得自动重试 |

参数和业务校验失败会正常完成 `CompletableFuture`，并通过状态返回。只有错误的调用方式或不可恢复的内部错误才会使 Future 异常完成，因此调用方仍应追加异常处理：

```java
.exceptionally(throwable -> {
    LOGGER.error("Contribution API call failed", throwable);
    return null;
});
```

## 幂等和重试

每次业务操作只生成一次幂等 ID：

```java
UUID idempotencyId = UUID.randomUUID();
```

调用方应先把该 ID 与自己的订单、任务或业务记录关联，再提交余额变更。如果发生网络超时、结果丢失或返回 `DATABASE_UNAVAILABLE` / `IN_PROGRESS`，必须原样重用整个请求：

```java
api.changeBalance(originalRequest);
```

不要在每次重试时调用 `UUID.randomUUID()`。否则系统会把每次尝试视为新交易，造成重复发放或重复扣款。

相同幂等 ID 的目标、数量、类型、来源、原因和备注必须完全一致。任何字段不同都会返回 `IDEMPOTENCY_CONFLICT`。

幂等重试取得原成功结果时，状态仍为 `SUCCESS`，同时 `replayed()` 返回 `true`，流水 ID、变动前余额和变动后余额与第一次成功结果一致。

## 完整调用流程

调用方应按以下顺序处理一次业务操作：

1. 创建并持久化业务记录与幂等 ID。
2. 构造不可变的 `BalanceChangeRequest`。
3. 调用 `changeBalance`，不阻塞服务器主线程。
4. 检查 `BalanceChangeStatus`。
5. 仅在 `SUCCESS` 后执行依赖余额变更成功的后续操作。
6. `IN_PROGRESS` 或超时后，使用原请求重试。
7. 业务失败时保留原结果，不使用新幂等 ID 暗中重做同一操作。

## 调用方身份与信任边界

Contribution 与调用模组运行在同一个 JVM 中。服务器管理员安装的 Java 模组属于受信任代码，Contribution 无法把 `source` 当作不可伪造的安全身份。

`source` 的用途包括：

- 记录流水来源；
- 查询和审计不同模组的操作；
- 统计调用量；
- 应用服务器配置的来源白名单或频率限制。

即使来源位于白名单中，Contribution 仍会执行数量范围、余额范围、退款类型、文本长度、账户状态和幂等校验。白名单不会授予绕过这些规则的能力。

## API 版本兼容性

正式发布后，公共 API 计划遵循语义化版本；当前 0.0.3 开发版尚不承诺跨版本二进制兼容：

- 同一主版本内保持 `cn.contribution.api` 的源代码和二进制兼容；
- 新增可选函数、结果字段或状态时增加次版本；
- 删除类型、修改既有函数签名或改变既有状态含义时增加主版本；
- 修复不改变接口行为的问题时增加修订版本。

未来版本可能增加新的 `BalanceChangeStatus`。处理状态时应保留未知状态分支：

```java
switch (result.status()) {
    case SUCCESS -> handleSuccess(result);
    case IN_PROGRESS -> retryLater(originalRequest);
    case INSUFFICIENT_BALANCE -> notifyInsufficientBalance();
    default -> handleRejectedOrUnknown(result);
}
```

不要依赖枚举当前只有固定数量的成员。

## API 边界

其他模组不得：

- 直接修改账户表或流水表；
- 获取并长期持有数据库连接；
- 绕过余额上下限或单次数量范围；
- 自行计算变动前后余额；
- 自行生成流水 ID；
- 从客户端直接调用余额变更；
- 把 `IDEMPOTENCY_CONFLICT` 当作普通网络错误重试。

Contribution 负责行锁、事务、历史总收入、流水、数值范围、数据库不可用处理和幂等结果复用。调用模组只负责描述业务操作并处理明确的返回状态。
