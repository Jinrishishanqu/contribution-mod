import tempfile
import unittest
from pathlib import Path

import numpy as np

from stock_visualizer import (
    SimulationResult,
    _find_first_delisting_hits,
    export_results,
    load_config,
    simulate,
)


CONFIG_PATH = Path(__file__).with_name("stock_config.yaml")


class StockVisualizerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = load_config(CONFIG_PATH)

    def test_simulation_is_deterministic_and_has_expected_shape(self) -> None:
        first = simulate(self.config)
        second = simulate(self.config)
        expected_shape = (self.config.days, len(self.config.stocks))
        self.assertEqual(first.prices_with.shape, expected_shape)
        np.testing.assert_allclose(first.prices_with, second.prices_with)
        self.assertTrue(np.all(first.prices_with > 0))

    def test_rank_and_weights_remain_fixed_for_all_days(self) -> None:
        result = simulate(self.config)
        for weights in result.rank_weight:
            np.testing.assert_array_equal(weights, result.rank_weight[0])

    def test_each_stock_has_its_own_ou_path(self) -> None:
        result = simulate(self.config)
        for stock_index in range(1, len(self.config.stocks)):
            self.assertFalse(
                np.array_equal(result.ou_noise[:, 0], result.ou_noise[:, stock_index])
            )

    def test_first_delisting_hit_uses_both_price_rules(self) -> None:
        prices = np.array(
            [
                [100.0, 100.0],
                [80.0, 300.0],
                [29.0, 200.0],
                [20.0, 40.0],
            ]
        )
        indices, reasons = _find_first_delisting_hits(prices, self.config)
        self.assertEqual(indices.tolist(), [2, 3])
        self.assertEqual(reasons[0], "上市价30%")
        self.assertEqual(reasons[1], "历史最高价15%")

    def test_default_export_uses_timestamp_and_has_no_single_charts(self) -> None:
        result = simulate(self.config)
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            run_dir, paths = export_results(
                result, self.config, CONFIG_PATH, output_root=root
            )
            self.assertEqual(run_dir.parent, root)
            self.assertRegex(run_dir.name, r"^\d{8}_\d{6}(?:_\d{2})?$")
            self.assertFalse((run_dir / "stocks").exists())
            self.assertTrue((run_dir / CONFIG_PATH.name).exists())
            self.assertTrue(all(path.exists() for path in paths))

    def test_single_chart_flag_exports_individual_chart(self) -> None:
        full = simulate(self.config)
        one = SimulationResult(
            days=full.days,
            industries=full.industries[:1],
            stock_names=full.stock_names[:1],
            prosperity_rank=full.prosperity_rank[:1],
            rank_weight=full.rank_weight[:, :1],
            ou_noise=full.ou_noise[:, :1],
            prices_with=full.prices_with[:, :1],
            prices_without=full.prices_without[:, :1],
            delisting_index_with=full.delisting_index_with[:1],
            delisting_index_without=full.delisting_index_without[:1],
            delisting_reason_with=full.delisting_reason_with[:1],
            delisting_reason_without=full.delisting_reason_without[:1],
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            run_dir, _ = export_results(
                one,
                self.config,
                CONFIG_PATH,
                output_root=Path(temp_dir),
                include_single_charts=True,
            )
            self.assertEqual(len(list((run_dir / "stocks").glob("*.png"))), 1)


if __name__ == "__main__":
    unittest.main()
