# 股票系统 0.0.2 验证记录

验证环境：Minecraft 26.3、Fabric Loader 0.19.5、Fabric API 0.161.0+26.3、Java 25、H2 2.5.250、项目专用 MySQL 8.4.6。

最终构建文件为 `build/libs/CSU-YSU-contribution-system-0.0.2.jar`，SHA-256：`D3A2C7390EC9014B91811AC9C98348454B2010B151F2D9747C8CC6C46F995AA6`。

`run-gradle.ps1 build --offline` 成功；`verifyEmbeddedStocks` 在新建的世界文件数据库内验证 V8 建表、30 支股票覆盖九行业、买卖和 2% 手续费、当日买入不可卖、每日交易次数、重复请求只成交一次、余额不足及持仓超限拒绝、曲线输出、主服务器时钟过期后暂停交易、退市返还不增加历史总收入。`verifyDialogs` 对实际曲线文本、数量输入框和交易命令模板完成原版 Dialog JSON 与网络编解码往返。

`verifyMySqlStocks --offline` 在真实 MySQL 8.4 InnoDB 中通过相同股票交易流程。测试使用随机命名的独立数据库，在运行结束并关闭连接后清理，不接触玩家世界或正式数据库。

使用打包后的单一服务端 JAR 启动真实 Fabric 服务器，日志确认加载 `contribution 0.0.2`、从既有 V7 迁移到 V8、数据库连接可用；测试库随后查到 `stock_market_state` 已推进、`stock_listing` 有 30 支股票。测试服务端无需客户端模组。

验证边界：没有真人客户端逐页查看字形和按钮布局；曲线的可传输性已验证，但具体视觉效果仍会随玩家字体及资源包变化。没有多台物理服务器同时高负载切服的长期试运行，也没有在生产备份上验证升级。正式群组服上线前应备份 MySQL 并安排试运行。
