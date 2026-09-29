"""比较一只股票在不同繁荣度修正参数 w 下的 100 天走势。"""

from __future__ import annotations

import argparse
import csv
import json
import math
import shutil
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.colors import to_rgb
import numpy as np
import yaml


DEFAULT_CONFIG_PATH = Path("single_stock_config.yaml")


@dataclass(frozen=True)
class SingleStockConfig:
    days: int
    random_seed: int
    seed_grid_rows: int
    seed_grid_columns: int
    industry: str
    stock_name: str
    corrections: tuple[float, ...]
    ou_theta: float
    ou_mu: float
    ou_sigma: float
    ou_dt: float
    ou_initial_sigma: float
    initial_price_min: float
    initial_price_max: float
    initial_b_price_ratio: float
    b_target_price_ratio: float
    b_ema_beta: float
    price_change_coefficient: float
    daily_price_floor_ratio: float
    daily_price_ceiling_ratio: float
    delisting_initial_price_ratio: float
    delisting_historical_high_ratio: float
    output_dpi: int
    output_base_dir: Path


@dataclass
class SeedResult:
    seed: int
    initial_price: float
    ou_noise: np.ndarray
    prices: np.ndarray
    delisting_indices: np.ndarray
    delisting_reasons: list[str | None]


@dataclass
class MultiSeedResult:
    days: np.ndarray
    corrections: tuple[float, ...]
    runs: list[SeedResult]


def load_config(path: Path) -> SingleStockConfig:
    """读取单股 YAML；此配置结构中没有繁荣度排名。"""

    with path.open("r", encoding="utf-8") as file:
        data = yaml.safe_load(file)
    if not isinstance(data, dict):
        raise ValueError("YAML 顶层必须是对象")

    simulation = data["simulation"]
    stock = data["stock"]
    ou = data["ou"]
    price = data["price"]
    delisting = data["delisting"]
    output = data["output"]
    raw_corrections = [float(value) for value in data["prosperity_corrections"]]
    # 按首次出现顺序去重，避免同一个 w 被重复绘制。
    corrections = tuple(dict.fromkeys(raw_corrections))
    config = SingleStockConfig(
        days=int(simulation["days"]),
        random_seed=int(simulation["random_seed"]),
        seed_grid_rows=int(simulation.get("seed_grid_rows", 4)),
        seed_grid_columns=int(simulation.get("seed_grid_columns", 4)),
        industry=str(stock["industry"]),
        stock_name=str(stock["name"]),
        corrections=corrections,
        ou_theta=float(ou["theta"]),
        ou_mu=float(ou["mu"]),
        ou_sigma=float(ou["sigma"]),
        ou_dt=float(ou["dt"]),
        ou_initial_sigma=float(ou["initial_sigma"]),
        initial_price_min=float(price["initial_price_min"]),
        initial_price_max=float(price["initial_price_max"]),
        initial_b_price_ratio=float(price["initial_b_price_ratio"]),
        b_target_price_ratio=float(price["b_target_price_ratio"]),
        b_ema_beta=float(price["b_ema_beta"]),
        price_change_coefficient=float(price["change_coefficient"]),
        daily_price_floor_ratio=float(price["daily_floor_ratio"]),
        daily_price_ceiling_ratio=float(price["daily_ceiling_ratio"]),
        delisting_initial_price_ratio=float(delisting["initial_price_ratio"]),
        delisting_historical_high_ratio=float(delisting["historical_high_ratio"]),
        output_dpi=int(output["dpi"]),
        output_base_dir=Path(output["base_dir"]),
    )
    _validate_config(config)
    return config


def _validate_config(config: SingleStockConfig) -> None:
    if config.days < 2:
        raise ValueError("simulation.days 至少为 2")
    if config.seed_grid_rows <= 0 or config.seed_grid_columns <= 0:
        raise ValueError("随机种子网格行列数必须为正数")
    if not config.industry or not config.stock_name:
        raise ValueError("stock.industry 和 stock.name 不能为空")
    if not config.corrections:
        raise ValueError("prosperity_corrections 不能为空")
    if not 0.0 <= config.b_ema_beta < 1.0:
        raise ValueError("price.b_ema_beta 必须位于 [0, 1) 区间")
    if config.initial_price_min <= 0 or config.initial_price_min > config.initial_price_max:
        raise ValueError("初始股价范围无效")
    if config.output_dpi <= 0:
        raise ValueError("output.dpi 必须为正数")


def _configure_chinese_font() -> None:
    plt.rcParams["font.sans-serif"] = [
        "Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Arial Unicode MS", "DejaVu Sans",
    ]
    plt.rcParams["axes.unicode_minus"] = False


def _generate_ou_noise(config: SingleStockConfig, rng: np.random.Generator) -> np.ndarray:
    noise = np.zeros(config.days, dtype=float)
    noise[0] = rng.normal(config.ou_mu, config.ou_initial_sigma)
    shocks = rng.normal(0.0, 1.0, size=config.days - 1)
    for day in range(1, config.days):
        noise[day] = (
            noise[day - 1]
            + config.ou_theta * (config.ou_mu - noise[day - 1]) * config.ou_dt
            + config.ou_sigma * math.sqrt(config.ou_dt) * shocks[day - 1]
        )
    return noise


def _simulate_one_correction(
    initial_price: float,
    ou_noise: np.ndarray,
    correction: float,
    config: SingleStockConfig,
) -> np.ndarray:
    prices = np.zeros(config.days, dtype=float)
    prices[0] = initial_price
    volatility_base = config.initial_b_price_ratio * initial_price
    for day in range(1, config.days):
        old_price = prices[day - 1]
        prosperity_multiplier = (
            1.0 + correction if ou_noise[day] > 0.0 else 1.0 - correction
        )
        candidate = (
            old_price
            + config.price_change_coefficient
            * ou_noise[day]
            * prosperity_multiplier
            * volatility_base
        )
        prices[day] = np.clip(
            candidate,
            config.daily_price_floor_ratio * old_price,
            config.daily_price_ceiling_ratio * old_price,
        )
        target_base = config.b_target_price_ratio * prices[day]
        volatility_base = (
            config.b_ema_beta * volatility_base
            + (1.0 - config.b_ema_beta) * target_base
        )
    return prices


def _find_first_delisting_hit(
    prices: np.ndarray, config: SingleStockConfig
) -> tuple[int, str | None]:
    historical_high = prices[0]
    initial_threshold = prices[0] * config.delisting_initial_price_ratio
    for day in range(1, len(prices)):
        historical_high = max(historical_high, prices[day])
        initial_hit = prices[day] <= initial_threshold
        high_hit = prices[day] <= historical_high * config.delisting_historical_high_ratio
        if initial_hit and high_hit:
            return day, "上市价30%且历史最高价15%"
        if initial_hit:
            return day, "上市价30%"
        if high_hit:
            return day, "历史最高价15%"
    return -1, None


def _simulate_seed(config: SingleStockConfig, seed: int) -> SeedResult:
    rng = np.random.default_rng(seed)
    ou_noise = _generate_ou_noise(config, rng)
    initial_price = float(
        np.rint(rng.uniform(config.initial_price_min, config.initial_price_max))
    )
    prices = np.vstack(
        [
            _simulate_one_correction(initial_price, ou_noise, correction, config)
            for correction in config.corrections
        ]
    )
    delisting = [_find_first_delisting_hit(curve, config) for curve in prices]
    return SeedResult(
        seed=seed,
        initial_price=initial_price,
        ou_noise=ou_noise,
        prices=prices,
        delisting_indices=np.array([item[0] for item in delisting], dtype=int),
        delisting_reasons=[item[1] for item in delisting],
    )


def simulate(config: SingleStockConfig) -> MultiSeedResult:
    run_count = config.seed_grid_rows * config.seed_grid_columns
    return MultiSeedResult(
        days=np.arange(1, config.days + 1),
        corrections=config.corrections,
        runs=[
            _simulate_seed(config, config.random_seed + offset)
            for offset in range(run_count)
        ],
    )


def _create_timestamp_output_dir(base_dir: Path) -> Path:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    candidate = base_dir / timestamp
    suffix = 1
    while candidate.exists():
        candidate = base_dir / f"{timestamp}_{suffix:02d}"
        suffix += 1
    candidate.mkdir(parents=True)
    return candidate


def _interpolate_color(start: str, end: str, ratio: float) -> tuple[float, float, float]:
    start_rgb = np.array(to_rgb(start))
    end_rgb = np.array(to_rgb(end))
    return tuple(start_rgb + np.clip(ratio, 0.0, 1.0) * (end_rgb - start_rgb))


def _correction_colors(corrections: tuple[float, ...]) -> dict[float, tuple[float, float, float]]:
    """最低值为红、零为蓝、最高值为绿，其余按数值线性渐变。"""

    minimum = min(corrections)
    maximum = max(corrections)
    colors: dict[float, tuple[float, float, float]] = {}
    for correction in corrections:
        if math.isclose(correction, 0.0, abs_tol=1e-12):
            colors[correction] = to_rgb("#1f77b4")
        elif correction < 0.0 and minimum < 0.0:
            colors[correction] = _interpolate_color(
                "#d62728", "#1f77b4", (correction - minimum) / (0.0 - minimum)
            )
        elif correction > 0.0 and maximum > 0.0:
            colors[correction] = _interpolate_color(
                "#1f77b4", "#2ca02c", correction / maximum
            )
        elif math.isclose(maximum, minimum):
            colors[correction] = to_rgb("#1f77b4")
        else:
            colors[correction] = _interpolate_color(
                "#d62728", "#2ca02c", (correction - minimum) / (maximum - minimum)
            )
    # 无论参数分布如何，非零范围端点都严格满足最低红、最高绿。
    if not math.isclose(minimum, 0.0, abs_tol=1e-12):
        colors[minimum] = to_rgb("#d62728")
    if not math.isclose(maximum, 0.0, abs_tol=1e-12):
        colors[maximum] = to_rgb("#2ca02c")
    return colors


def _draw_chart(
    result: MultiSeedResult, config: SingleStockConfig
) -> tuple[plt.Figure, np.ndarray]:
    figure, axes = plt.subplots(
        config.seed_grid_rows,
        config.seed_grid_columns,
        figsize=(20, 16),
        constrained_layout=True,
        squeeze=False,
    )
    colors = _correction_colors(result.corrections)
    for ax, run in zip(axes.flat, result.runs):
        for index, correction in enumerate(result.corrections):
            color = colors[correction]
            ax.plot(
                result.days,
                run.prices[index],
                color=color,
                linewidth=1.25,
            )
            hit_index = run.delisting_indices[index]
            if hit_index >= 0:
                ax.scatter(
                    result.days[hit_index],
                    run.prices[index, hit_index],
                    color="#d62728",
                    edgecolor="#111111",
                    linewidth=0.5,
                    s=24,
                    zorder=5,
                )
        ax.set_title(f"seed={run.seed}｜初始价={run.initial_price:.0f}")
        ax.set_xlabel("游戏日")
        ax.set_ylabel("股价（代币）")
        ax.grid(alpha=0.22, linewidth=0.5)

    handles = [
        Line2D([0], [0], color=colors[value], linewidth=1.8, label=f"w={value:+.2f}")
        for value in sorted(result.corrections)
    ]
    handles.append(
        Line2D(
            [0], [0], color="#d62728", marker="o", markeredgecolor="#111111",
            linestyle="None", label="首次达到退市标准",
        )
    )
    figure.legend(handles=handles, loc="outside lower center", ncol=min(6, len(handles)), frameon=False)
    figure.suptitle(
        f"{config.stock_name}（{config.industry}）不同 w 的 {config.days} 天走势："
        f"{config.seed_grid_rows}×{config.seed_grid_columns} 个连续随机种子",
        fontsize=16,
    )
    return figure, axes


def export_results(
    result: MultiSeedResult,
    config: SingleStockConfig,
    config_path: Path,
    output_root: Path | None = None,
) -> tuple[Path, list[Path]]:
    _configure_chinese_font()
    run_dir = _create_timestamp_output_dir(output_root or config.output_base_dir)
    exported: list[Path] = []

    copied_config = run_dir / config_path.name
    shutil.copy2(config_path, copied_config)
    exported.append(copied_config)

    run_count = len(result.runs)
    chart_path = run_dir / f"single_stock_{run_count}_seeds_{config.days}_days.png"
    figure, _ = _draw_chart(result, config)
    figure.savefig(chart_path, dpi=config.output_dpi, bbox_inches="tight")
    plt.close(figure)
    exported.append(chart_path)

    csv_path = run_dir / f"single_stock_simulation_{config.days}_days.csv"
    with csv_path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.writer(file)
        writer.writerow([
            "seed", "day", "initial_price", "correction_w", "ou_noise_n", "price",
            "first_delisting_hit",
        ])
        for run in result.runs:
            for correction_index, correction in enumerate(result.corrections):
                for day_index, day in enumerate(result.days):
                    writer.writerow([
                        run.seed, int(day), f"{run.initial_price:.4f}", f"{correction:.4f}",
                        f"{run.ou_noise[day_index]:.8f}",
                        f"{run.prices[correction_index, day_index]:.4f}",
                        day_index == run.delisting_indices[correction_index],
                    ])
    exported.append(csv_path)

    summary_path = run_dir / "simulation_summary.json"
    summary = {
        "config": {**asdict(config), "output_base_dir": str(config.output_base_dir)},
        "deduplicated_corrections": list(result.corrections),
        "runs": [
            {
                "seed": run.seed,
                "initial_price": run.initial_price,
                "curves": [
                    {
                        "correction_w": correction,
                        "final_price": round(float(run.prices[index, -1]), 4),
                        "first_delisting_day": (
                            int(result.days[run.delisting_indices[index]])
                            if run.delisting_indices[index] >= 0 else None
                        ),
                        "delisting_reason": run.delisting_reasons[index],
                    }
                    for index, correction in enumerate(result.corrections)
                ],
            }
            for run in result.runs
        ],
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    exported.append(summary_path)
    return run_dir, exported


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="比较一只股票在不同繁荣度修正参数下的走势")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH, help="YAML 配置文件")
    parser.add_argument(
        "--output-root", type=Path, default=None,
        help="覆盖 YAML 中的输出根目录；实际结果仍写入其时间戳子目录",
    )
    parser.add_argument("--show", action="store_true", help="导出后显示图表窗口")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config = load_config(args.config)
    result = simulate(config)
    run_dir, _ = export_results(result, config, args.config, args.output_root)
    print(f"已模拟股票：{config.stock_name}（{config.industry}）")
    print(f"修正参数数量：{len(config.corrections)}")
    print(f"本次结果目录：{run_dir.resolve()}")
    run_count = config.seed_grid_rows * config.seed_grid_columns
    print(f"随机种子数量：{run_count}")
    print(f"曲线图：{(run_dir / f'single_stock_{run_count}_seeds_{config.days}_days.png').resolve()}")
    if args.show:
        figure, _ = _draw_chart(result, config)
        plt.show()
        plt.close(figure)


if __name__ == "__main__":
    main()
