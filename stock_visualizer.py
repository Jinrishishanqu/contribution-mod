"""固定繁荣度排名下的股票算法对比模拟与可视化导出。"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import shutil
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
import numpy as np
import yaml


DEFAULT_CONFIG_PATH = Path("stock_config.yaml")


@dataclass(frozen=True)
class StockDefinition:
    industry: str
    stock: str


@dataclass(frozen=True)
class SimulationConfig:
    days: int
    random_seed: int
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
    stocks: tuple[StockDefinition, ...]
    prosperity_ranking: tuple[str, ...]
    prosperity_correction_by_rank: tuple[float, ...]


@dataclass
class SimulationResult:
    days: np.ndarray
    industries: list[str]
    stock_names: list[str]
    prosperity_rank: np.ndarray
    rank_weight: np.ndarray
    ou_noise: np.ndarray
    prices_with: np.ndarray
    prices_without: np.ndarray
    delisting_index_with: np.ndarray
    delisting_index_without: np.ndarray
    delisting_reason_with: list[str | None]
    delisting_reason_without: list[str | None]


def load_config(path: Path) -> SimulationConfig:
    """从 YAML 加载全部可调参数并执行必要校验。"""

    with path.open("r", encoding="utf-8") as file:
        data = yaml.safe_load(file)
    if not isinstance(data, dict):
        raise ValueError("YAML 顶层必须是对象")

    simulation = data["simulation"]
    ou = data["ou"]
    price = data["price"]
    delisting = data["delisting"]
    output = data["output"]
    prosperity = data["prosperity"]
    stocks = tuple(
        StockDefinition(industry=str(item["industry"]), stock=str(item["stock"]))
        for item in data["stocks"]
    )
    config = SimulationConfig(
        days=int(simulation["days"]),
        random_seed=int(simulation["random_seed"]),
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
        stocks=stocks,
        prosperity_ranking=tuple(str(value) for value in prosperity["ranking_high_to_low"]),
        prosperity_correction_by_rank=tuple(
            float(value) for value in prosperity["correction_by_rank"]
        ),
    )
    _validate_config(config)
    return config


def _validate_config(config: SimulationConfig) -> None:
    if config.days < 2:
        raise ValueError("simulation.days 至少为 2")
    if not config.stocks:
        raise ValueError("stocks 不能为空")
    if not 0.0 <= config.b_ema_beta < 1.0:
        raise ValueError("price.b_ema_beta 必须位于 [0, 1) 区间")
    if config.initial_price_min <= 0 or config.initial_price_min > config.initial_price_max:
        raise ValueError("初始股价范围无效")
    if config.output_dpi <= 0:
        raise ValueError("output.dpi 必须为正数")

    industries = [item.industry for item in config.stocks]
    if len(set(industries)) != len(industries):
        raise ValueError("stocks 中的行业不能重复")
    if len(config.prosperity_ranking) != len(industries):
        raise ValueError("繁荣度排名必须恰好包含全部行业")
    if len(set(config.prosperity_ranking)) != len(config.prosperity_ranking):
        raise ValueError("繁荣度排名中不能有重复行业")
    if set(config.prosperity_ranking) != set(industries):
        raise ValueError("繁荣度排名与 stocks 中的行业不一致")
    if len(config.prosperity_correction_by_rank) != len(config.prosperity_ranking):
        raise ValueError("繁荣度修正参数数量必须与排名数量一致")


def _configure_chinese_font() -> None:
    plt.rcParams["font.sans-serif"] = [
        "Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Arial Unicode MS", "DejaVu Sans",
    ]
    plt.rcParams["axes.unicode_minus"] = False


def _fixed_rank_data(
    config: SimulationConfig, industries: list[str]
) -> tuple[np.ndarray, np.ndarray]:
    rank_by_industry = {
        industry: rank
        for rank, industry in enumerate(config.prosperity_ranking, start=1)
    }
    correction_by_industry = {
        industry: config.prosperity_correction_by_rank[rank - 1]
        for industry, rank in rank_by_industry.items()
    }
    ranks = np.array([rank_by_industry[industry] for industry in industries], dtype=int)
    one_day_weights = np.array(
        [correction_by_industry[industry] for industry in industries], dtype=float
    )
    return ranks, np.tile(one_day_weights, (config.days, 1))


def _generate_ou_noise(
    stock_count: int, config: SimulationConfig, rng: np.random.Generator
) -> np.ndarray:
    """每支股票维护独立 OU 状态，共用同一组参数。"""

    noise = np.zeros((config.days, stock_count), dtype=float)
    noise[0] = rng.normal(config.ou_mu, config.ou_initial_sigma, size=stock_count)
    shocks = rng.normal(0.0, 1.0, size=(config.days - 1, stock_count))
    for day in range(1, config.days):
        previous = noise[day - 1]
        noise[day] = (
            previous
            + config.ou_theta * (config.ou_mu - previous) * config.ou_dt
            + config.ou_sigma * math.sqrt(config.ou_dt) * shocks[day - 1]
        )
    return noise


def _simulate_prices(
    initial_prices: np.ndarray,
    ou_noise: np.ndarray,
    rank_weight: np.ndarray,
    config: SimulationConfig,
) -> np.ndarray:
    prices = np.zeros_like(ou_noise)
    prices[0] = initial_prices
    volatility_base = config.initial_b_price_ratio * initial_prices
    for day in range(1, config.days):
        old_price = prices[day - 1]
        current_noise = ou_noise[day]
        weight = rank_weight[day]
        prosperity_multiplier = np.where(current_noise > 0.0, 1.0 + weight, 1.0 - weight)
        candidate = (
            old_price
            + config.price_change_coefficient
            * current_noise
            * prosperity_multiplier
            * volatility_base
        )
        new_price = np.clip(
            candidate,
            config.daily_price_floor_ratio * old_price,
            config.daily_price_ceiling_ratio * old_price,
        )
        prices[day] = new_price
        target_base = config.b_target_price_ratio * new_price
        volatility_base = (
            config.b_ema_beta * volatility_base
            + (1.0 - config.b_ema_beta) * target_base
        )
    return prices


def _find_first_delisting_hits(
    prices: np.ndarray, config: SimulationConfig
) -> tuple[np.ndarray, list[str | None]]:
    """查找每支股票第一次达到任一价格退市标准的日期索引。"""

    stock_count = prices.shape[1]
    hit_indices = np.full(stock_count, -1, dtype=int)
    reasons: list[str | None] = [None] * stock_count
    historical_high = prices[0].copy()
    initial_threshold = prices[0] * config.delisting_initial_price_ratio
    for day in range(1, prices.shape[0]):
        historical_high = np.maximum(historical_high, prices[day])
        initial_hit = prices[day] <= initial_threshold
        high_hit = prices[day] <= historical_high * config.delisting_historical_high_ratio
        newly_hit = (hit_indices < 0) & (initial_hit | high_hit)
        for index in np.flatnonzero(newly_hit):
            hit_indices[index] = day
            if initial_hit[index] and high_hit[index]:
                reasons[index] = "上市价30%且历史最高价15%"
            elif initial_hit[index]:
                reasons[index] = "上市价30%"
            else:
                reasons[index] = "历史最高价15%"
    return hit_indices, reasons


def simulate(config: SimulationConfig) -> SimulationResult:
    rng = np.random.default_rng(config.random_seed)
    industries = [item.industry for item in config.stocks]
    stock_names = [item.stock for item in config.stocks]
    stock_count = len(config.stocks)
    prosperity_rank, rank_weight = _fixed_rank_data(config, industries)
    ou_noise = _generate_ou_noise(stock_count, config, rng)
    initial_prices = np.rint(
        rng.uniform(config.initial_price_min, config.initial_price_max, size=stock_count)
    )
    prices_with = _simulate_prices(initial_prices, ou_noise, rank_weight, config)
    prices_without = _simulate_prices(initial_prices, ou_noise, np.zeros_like(rank_weight), config)
    delisting_index_with, delisting_reason_with = _find_first_delisting_hits(prices_with, config)
    delisting_index_without, delisting_reason_without = _find_first_delisting_hits(prices_without, config)
    return SimulationResult(
        days=np.arange(1, config.days + 1),
        industries=industries,
        stock_names=stock_names,
        prosperity_rank=prosperity_rank,
        rank_weight=rank_weight,
        ou_noise=ou_noise,
        prices_with=prices_with,
        prices_without=prices_without,
        delisting_index_with=delisting_index_with,
        delisting_index_without=delisting_index_without,
        delisting_reason_with=delisting_reason_with,
        delisting_reason_without=delisting_reason_without,
    )


def _safe_filename(text: str) -> str:
    return re.sub(r"[^\w\-]+", "_", text, flags=re.UNICODE).strip("_")


def _draw_stock_axis(ax: plt.Axes, result: SimulationResult, index: int) -> None:
    ax.plot(result.days, result.prices_with[:, index], color="#d95f02", linewidth=1.7)
    ax.plot(
        result.days, result.prices_without[:, index],
        color="#1b9e77", linewidth=1.45, linestyle="--",
    )
    with_hit = result.delisting_index_with[index]
    if with_hit >= 0:
        ax.scatter(
            result.days[with_hit], result.prices_with[with_hit, index],
            color="#d62728", marker="o", s=34, zorder=5,
        )
    without_hit = result.delisting_index_without[index]
    if without_hit >= 0:
        ax.scatter(
            result.days[without_hit], result.prices_without[without_hit, index],
            color="#d62728", marker="X", s=38, zorder=5,
        )
    ax.set_title(f"{result.stock_names[index]}（{result.industries[index]}）")
    ax.set_xlabel("游戏日")
    ax.set_ylabel("股价（代币）")
    ax.grid(alpha=0.25, linewidth=0.6)


def _legend_handles() -> list[Line2D]:
    return [
        Line2D([0], [0], color="#d95f02", linewidth=1.7, label="有繁荣度影响"),
        Line2D([0], [0], color="#1b9e77", linewidth=1.45, linestyle="--", label="无繁荣度影响"),
        Line2D([0], [0], color="#d62728", marker="o", linestyle="None", label="有繁荣度：首次达到退市标准"),
        Line2D([0], [0], color="#d62728", marker="X", linestyle="None", label="无繁荣度：首次达到退市标准"),
    ]


def _create_timestamp_output_dir(base_dir: Path) -> Path:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    candidate = base_dir / timestamp
    suffix = 1
    while candidate.exists():
        candidate = base_dir / f"{timestamp}_{suffix:02d}"
        suffix += 1
    candidate.mkdir(parents=True)
    return candidate


def export_results(
    result: SimulationResult,
    config: SimulationConfig,
    config_path: Path,
    output_root: Path | None = None,
    include_single_charts: bool = False,
) -> tuple[Path, list[Path]]:
    _configure_chinese_font()
    run_dir = _create_timestamp_output_dir(output_root or config.output_base_dir)
    exported: list[Path] = []
    copied_config = run_dir / config_path.name
    shutil.copy2(config_path, copied_config)
    exported.append(copied_config)

    overview_path = run_dir / f"all_stocks_{config.days}_days.png"
    rows = math.ceil(len(result.industries) / 3)
    figure, axes = plt.subplots(rows, 3, figsize=(18, rows * 3.6), constrained_layout=True)
    for index, ax in enumerate(np.asarray(axes).flat):
        if index >= len(result.industries):
            ax.set_visible(False)
        else:
            _draw_stock_axis(ax, result, index)
    figure.legend(handles=_legend_handles(), loc="outside lower center", ncol=2, frameon=False)
    figure.suptitle(
        f"全部股票 {config.days} 天走势：繁荣度影响对比（固定 seed={config.random_seed}）",
        fontsize=16,
    )
    figure.savefig(overview_path, dpi=config.output_dpi, bbox_inches="tight")
    plt.close(figure)
    exported.append(overview_path)

    if include_single_charts:
        stocks_dir = run_dir / "stocks"
        stocks_dir.mkdir()
        for index, (industry, stock_name) in enumerate(zip(result.industries, result.stock_names)):
            path = stocks_dir / (
                f"{index + 1:02d}_{_safe_filename(industry)}_{_safe_filename(stock_name)}.png"
            )
            figure, ax = plt.subplots(figsize=(10.5, 5.8), constrained_layout=True)
            _draw_stock_axis(ax, result, index)
            ax.legend(handles=_legend_handles(), frameon=False)
            figure.savefig(path, dpi=config.output_dpi, bbox_inches="tight")
            plt.close(figure)
            exported.append(path)

    csv_path = run_dir / f"stock_simulation_{config.days}_days.csv"
    with csv_path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.writer(file)
        writer.writerow([
            "day", "industry", "stock", "fixed_prosperity_rank", "rank_weight_w",
            "ou_noise_n", "price_with_prosperity", "price_without_prosperity",
            "price_difference", "first_delisting_hit_with", "first_delisting_hit_without",
        ])
        for day_index, day in enumerate(result.days):
            for stock_index, industry in enumerate(result.industries):
                with_price = result.prices_with[day_index, stock_index]
                without_price = result.prices_without[day_index, stock_index]
                writer.writerow([
                    int(day), industry, result.stock_names[stock_index],
                    int(result.prosperity_rank[stock_index]),
                    f"{result.rank_weight[day_index, stock_index]:.2f}",
                    f"{result.ou_noise[day_index, stock_index]:.8f}",
                    f"{with_price:.4f}", f"{without_price:.4f}",
                    f"{with_price - without_price:.4f}",
                    day_index == result.delisting_index_with[stock_index],
                    day_index == result.delisting_index_without[stock_index],
                ])
    exported.append(csv_path)

    summary_path = run_dir / "simulation_summary.json"
    summary = {
        "config": {**asdict(config), "output_base_dir": str(config.output_base_dir)},
        "stocks": [
            {
                "industry": industry,
                "stock": result.stock_names[index],
                "fixed_prosperity_rank": int(result.prosperity_rank[index]),
                "rank_weight_w": float(result.rank_weight[0, index]),
                "initial_price": round(float(result.prices_with[0, index]), 4),
                "final_price_with_prosperity": round(float(result.prices_with[-1, index]), 4),
                "final_price_without_prosperity": round(float(result.prices_without[-1, index]), 4),
                "first_delisting_day_with_prosperity": (
                    int(result.days[result.delisting_index_with[index]])
                    if result.delisting_index_with[index] >= 0 else None
                ),
                "delisting_reason_with_prosperity": result.delisting_reason_with[index],
                "first_delisting_day_without_prosperity": (
                    int(result.days[result.delisting_index_without[index]])
                    if result.delisting_index_without[index] >= 0 else None
                ),
                "delisting_reason_without_prosperity": result.delisting_reason_without[index],
            }
            for index, industry in enumerate(result.industries)
        ],
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    exported.append(summary_path)
    return run_dir, exported


def show_overview(result: SimulationResult, config: SimulationConfig) -> None:
    _configure_chinese_font()
    rows = math.ceil(len(result.industries) / 3)
    figure, axes = plt.subplots(rows, 3, figsize=(18, rows * 3.6), constrained_layout=True)
    for index, ax in enumerate(np.asarray(axes).flat):
        if index >= len(result.industries):
            ax.set_visible(False)
        else:
            _draw_stock_axis(ax, result, index)
    figure.legend(handles=_legend_handles(), loc="outside lower center", ncol=2, frameon=False)
    figure.suptitle(f"全部股票 {config.days} 天走势：繁荣度影响对比", fontsize=16)
    plt.show()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="模拟并导出股票繁荣度影响对比图")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH, help="YAML 配置文件")
    parser.add_argument(
        "--output-root", type=Path, default=None,
        help="覆盖 YAML 中的输出根目录；实际结果仍写入其时间戳子目录",
    )
    parser.add_argument(
        "--single-charts", action="store_true", help="额外导出每支股票的单独曲线图"
    )
    parser.add_argument("--show", action="store_true", help="导出后显示总览窗口")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config = load_config(args.config)
    result = simulate(config)
    run_dir, _ = export_results(
        result, config, args.config,
        output_root=args.output_root,
        include_single_charts=args.single_charts,
    )
    print(f"已模拟 {len(result.industries)} 支股票，共 {config.days} 天。")
    print(f"本次结果目录：{run_dir.resolve()}")
    print(f"总览图：{(run_dir / f'all_stocks_{config.days}_days.png').resolve()}")
    if args.single_charts:
        print(f"单股图目录：{(run_dir / 'stocks').resolve()}")
    if args.show:
        show_overview(result, config)


if __name__ == "__main__":
    main()
