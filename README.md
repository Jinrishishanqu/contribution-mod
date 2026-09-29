# 股票算法可视化程序

> 贡献值模组的整体设计入口见 [design/README.md](design/README.md)。

该程序根据 [股票系统设计](design/extensions/stock.md) 的股票算法，模拟 18 个行业各一只股票的 100 天走势。退市、交易、库存等机制不参与模拟。行业繁荣度用一个从高到低的固定排名列表表示，整个 100 天内不会变化。

两条对比曲线使用完全相同的初始股价和 OU 随机噪声：

- **有繁荣度影响**：每日按行业繁荣度排名应用 `w` 修正；
- **无繁荣度影响**：每日令 `w = 0`。

因此，两条曲线之间的差异只来自繁荣度排名机制。

## 运行

需要 Python 3.10+、NumPy、Matplotlib 和 PyYAML。

```powershell
python stock_visualizer.py
```

建议显式指定配置文件：

```powershell
python stock_visualizer.py --config stock_config.yaml
```

默认结果写入 `output/<YYYYMMDD_HHMMSS>/`，每次运行建立独立的时间戳目录：

- `all_stocks_100_days.png`：全部股票小多图总览；
- `stock_simulation_100_days.csv`：逐日排名权重、OU 噪声、两组股价和首次退市标准触发标记；
- `simulation_summary.json`：参数与各股票最终结果摘要。
- `stock_config.yaml`：本次运行所用配置的原样副本。

默认不生成单股图。需要同时生成 18 张单股图时运行：

```powershell
python stock_visualizer.py --single-charts
```

显示可交互缩放的 Matplotlib 总览窗口：

```powershell
python stock_visualizer.py --show
```

所有可调参数集中在 `stock_config.yaml`，包括：

- 固定繁荣度排名及各名次的修正参数 `w`；
- 模拟天数和固定随机种子；
- OU 的 `theta / mu / sigma / dt` 与初始噪声标准差；
- 初始股价范围；
- 初始基准 `B`、目标 `B` 比例和 EMA beta；
- 股价变化系数及每日价格上下限。
- 两条价格退市标准和输出设置。

程序固定使用随机种子 `20260924`，因此参数不变时每次运行结果完全一致。

也可以指定另一份配置文件：

```powershell
python stock_visualizer.py --config another_config.yaml
```

## 退市标记

程序不执行退市后的连续下跌、出售或清算流程，只检查价格标准。某条曲线第一次达到以下任一标准时，会在当天标一个红点：

- 股价不高于上市首日股价的 30%；
- 股价不高于截至当天历史最高价的 15%。

圆形红点表示“有繁荣度影响”曲线，叉形红点表示“无繁荣度影响”曲线。

## 当前采用的 OU 公式

设计文档尚未确定 OU 的完整参数，本程序采用 Euler-Maruyama 离散化：

```text
N(t+1) = N(t) + theta * (mu - N(t)) * dt + sigma * sqrt(dt) * epsilon
epsilon ~ Normal(0, 1)
```

默认值为 `theta=0.18`、`mu=0`、`sigma=1.25`、`dt=1`。每只股票维护自己的 OU 状态并接收独立的标准正态随机冲击，但所有股票共用上述参数。

## 测试

```powershell
python -m unittest -v
```

也可以直接运行测试文件：

```powershell
python test_stock_visualizer.py
```

注意：测试命令只验证算法，不导出正式的 100 天结果；生成图表仍使用 `python stock_visualizer.py`。

## 单只股票多修正参数对比

第二个程序只模拟一只股票，不读取、不计算繁荣度排名。`single_stock_config.yaml` 中的 `prosperity_corrections` 列表每个唯一数值对应一条曲线，重复值会按首次出现顺序自动去重。同一个子图内的所有曲线共用相同的初始股价和同一条 OU 噪声路径。

```powershell
python single_stock_visualizer.py --config single_stock_config.yaml
```

程序默认使用配置中的 `random_seed`、`random_seed+1`、……、`random_seed+15`，一次生成 4×4 共 16 个子图。网格行列数也可以在 YAML 中调整。曲线颜色规则为：最低 `w` 红色、`w=0` 蓝色、最高 `w` 绿色，中间值按数值渐变。

默认结果写入 `output/single_stock/<YYYYMMDD_HHMMSS>/`，包含：

- `single_stock_16_seeds_100_days.png`：16 个连续随机种子的 4×4 总览；
- `single_stock_simulation_100_days.csv`：OU 噪声、各参数股价及退市触发标记；
- `simulation_summary.json`：各曲线最终价格和首次达到退市标准的日期；
- `single_stock_config.yaml`：本次运行所用配置副本。

两个程序均可用 `--config <配置文件.yaml>` 指定配置。全股票程序读取固定繁荣度排名；单股票程序的配置结构中没有排名参数。
