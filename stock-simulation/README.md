# 股票算法可视化程序

> 本目录是**独立的 Python 分析工具**，用于观察和验证股票价格模型的走势。它不读取、也不写入模组数据库，与 [code/](../code/) 下的 Fabric 模组没有代码依赖。

两个程序都从**本目录**运行，配置里的相对路径与 `output/` 都相对当前工作目录解析。

## 依赖

Python 3.10+、NumPy、Matplotlib、PyYAML：

```powershell
pip install -r ../requirements.txt
```

## 一、全行业对比模拟

[stock_visualizer.py](stock_visualizer.py) 按 [stock_config.yaml](stock_config.yaml) 模拟 18 个行业各一只股票；行业繁荣度用一个从高到低的固定排名表示，整个模拟期间不变。退市、交易、库存等机制不参与模拟。

两条对比曲线使用完全相同的初始股价和 OU 随机噪声，差异只来自繁荣度排名机制：

- **有繁荣度影响**：每日按排名应用修正参数 `w`；
- **无繁荣度影响**：每日令 `w = 0`。

```powershell
python stock_visualizer.py
python stock_visualizer.py --config stock_config.yaml
python stock_visualizer.py --single-charts   # 额外生成每只股票的单股图
python stock_visualizer.py --show            # 打开可交互缩放的窗口
```

结果写入 `output/<YYYYMMDD_HHMMSS>/`：`all_stocks_*_days.png` 总览、`stock_simulation_*_days.csv` 逐日数据、`simulation_summary.json` 摘要，以及本次所用配置的副本。

[default.yaml](default.yaml) 是同一结构的备用配置，未被上述命令默认引用。

## 二、单只股票多修正参数对比

[single_stock_visualizer.py](single_stock_visualizer.py) 只模拟一只股票，配置中没有繁荣度排名。`single_stock_config.yaml` 的 `prosperity_corrections` 列表每个唯一数值对应一条曲线，重复值按首次出现顺序去重。同一子图内所有曲线共用相同初始股价与同一条 OU 噪声路径。

```powershell
python single_stock_visualizer.py --config single_stock_config.yaml
```

程序默认使用配置中的 `random_seed`、`random_seed+1`、……，一次生成 `seed_grid_rows × seed_grid_columns` 个子图。曲线颜色规则为：最低 `w` 红色、`w=0` 蓝色、最高 `w` 绿色，中间值按数值渐变。结果写入 `output/single_stock/<YYYYMMDD_HHMMSS>/`。

## 三、测试

```powershell
python -m unittest -v
```

测试只验证算法与导出行为（不产出正式图表），全程使用临时目录。

> **已知问题**：`test_single_stock_visualizer.py::test_export_uses_timestamp_and_copies_yaml` 当前**失败**。该断言硬编码了文件名 `single_stock_16_seeds_100_days.png`，而 [single_stock_config.yaml](single_stock_config.yaml) 后来把 `simulation.days` 改成了 `1000`，导出的实际文件名为 `single_stock_16_seeds_1000_days.png`。其余 10 项测试通过。此问题早于本目录的整理，尚未修复。

## 与模组实现的差异

本工具用于**探索**价格模型，因此采用的参数与模组当前实现并不相同，不能作为模组的规范来源：

| 项目 | 本工具 | 模组实现 |
| --- | --- | --- |
| OU 离散化 | Euler-Maruyama，`theta=0.18`、`sigma=1.25` | `N_t=(1−θ)N_{t−1}+σZ_t`，`θ=0.35`、`σ=0.8` |
| 修正参数 `w` | 18 档，`0.25` … `−0.25` | 9 档，`0.28` … `−0.28`（按繁荣度排名） |
| 退市标准 | 首日价 30%、历史最高价 15% | `max(⌊p₀/2⌋, ⌊p_h/4⌋)` |
| 行业数量 | 18 | 9 个内置行业 |
| 初始股价 | 600–5000 连续均匀 | 上市首日 `UniformInt(100,400)` |
| 单日涨跌限制 | 0.30–3.00 倍 | 0.3 倍 – `min(3 倍, 10p₀)` |

模组的权威规则见 [股票系统设计](../design/extensions/stock.md)。

## 输出目录

`output/` 保存历史运行结果，已被 `.gitignore` 排除，不进入版本库。可以随时删除，删除后重新运行即可再生成。
