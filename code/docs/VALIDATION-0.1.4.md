# 0.1.4 验证记录

## 环境和范围

- Minecraft 26.3、Fabric Loader 0.19.5、Fabric API 0.161.0+26.3、Java 25；使用项目已有运行库，没有重新下载游戏。
- 内置数据：58 个配方、38 个独立进度（37 个可见进度和根节点）、58 个隐藏配方解锁进度。
- 外观：12 个装备定义、12 个物品定义、12 个物品模型、25 张 PNG。没有 mcfunction。

## 自动检查

`run-gradle.ps1 build --offline` 执行完整项目检查。新增 `verifyItemContent` 使用实际 26.3 原版 Codec 解析全部配方、进度、装备定义和资源包元数据，并读取全部 PNG。

实际调用原版配方装配逻辑验证：钻石胸甲添翼、添翼胸甲强化、下界合金胸甲换主题时，损耗耐久、自定义名称、附魔与纹饰保留；强化后仍有滑翔组件；强化属性替换而不累加。

原有账户、UUID 迁移、建设度、股票、奖励、商店、双 UI、配置与资源检查亦通过。此次没有运行外部 MySQL 集成测试；新增玩法数据不依赖数据库。

## 独立服务端

运行 `verifyPackagedServer -PvalidationDir=run-items --offline`，直接加载构建后的发布 JAR。隔离目录为 `code/run-items`，新测试世界 `item-content-test`，监听 `127.0.0.1:25614`，没有修改用户的 `F:/server/server263`。

首次启动发现旧原型的珊瑚标签引用残留，已改为明确的原版珊瑚扇方块集合，并补充禁止旧命名空间的回归检查。复测启动成功，加载 1962 个总进度，出现 `Done (4.525s)`，内置 H2 首次建库及 17 次迁移成功。全过程没有执行 `/reload`。

资源包自动导出到测试世界的 `contribution/resource-packs/CSU-YSU-items-0.1.4.zip`；与构建输出 ZIP 的 SHA-1 一致：`b6a73e0838d2a099683b9c02978a5c71cfefadaa`。

启动时存在系统 OSHI/Windows 性能计数器警告、原有原生访问与 H2/Flyway 版本提示，不是配方或注册表加载失败。测试进程在检查后停止。

## 尚未覆盖的验证边界

本次没有进行真实客户端逐件穿戴、飞行与贴图视觉验收；原版装配和服务端加载成功不等于视觉验收。原版客户端显示主题外观需要管理员分发导出的服务器资源包；仅服务端安装 JAR 不会自动向客户端传输贴图。安装模组的客户端会自动加载 JAR 内资源。

## 输出

- `code/build/libs/CSU-YSU-contribution-system-0.1.4.jar`：服务端及可选客户端共用模组。
- `code/build/libs/CSU-YSU-items-0.1.4-resource-pack.zip`：原版客户端可用的外观包。
- `code/build/libs/CSU-YSU-contribution-system-0.1.4-sources.jar`：源码归档，不用于安装。
