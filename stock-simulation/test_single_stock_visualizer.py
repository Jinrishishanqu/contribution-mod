import tempfile
import unittest
from pathlib import Path

import numpy as np
import yaml

from matplotlib.colors import to_rgb

from single_stock_visualizer import (
    _correction_colors,
    export_results,
    load_config,
    simulate,
)


CONFIG_PATH = Path(__file__).with_name("single_stock_config.yaml")


class SingleStockVisualizerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.config = load_config(CONFIG_PATH)

    def test_config_has_no_ranking_parameter(self) -> None:
        with CONFIG_PATH.open("r", encoding="utf-8") as file:
            raw = yaml.safe_load(file)
        self.assertNotIn("prosperity", raw)
        self.assertNotIn("ranking_high_to_low", raw)

    def test_all_curves_share_noise_and_initial_price(self) -> None:
        result = simulate(self.config)
        self.assertEqual(len(result.runs), 16)
        self.assertEqual(
            [run.seed for run in result.runs],
            list(range(self.config.random_seed, self.config.random_seed + 16)),
        )
        for run in result.runs:
            self.assertEqual(
                run.prices.shape, (len(self.config.corrections), self.config.days)
            )
            np.testing.assert_allclose(run.prices[:, 0], run.initial_price)
            self.assertEqual(run.ou_noise.shape, (self.config.days,))

    def test_different_corrections_produce_different_curves(self) -> None:
        result = simulate(self.config)
        run = result.runs[0]
        self.assertGreater(np.max(np.abs(run.prices[0] - run.prices[-1])), 0.0)

    def test_corrections_are_unique_and_anchor_colors_are_exact(self) -> None:
        self.assertEqual(len(self.config.corrections), len(set(self.config.corrections)))
        colors = _correction_colors(self.config.corrections)
        np.testing.assert_allclose(colors[min(self.config.corrections)], to_rgb("#d62728"))
        np.testing.assert_allclose(colors[0.0], to_rgb("#1f77b4"))
        np.testing.assert_allclose(colors[max(self.config.corrections)], to_rgb("#2ca02c"))

    def test_export_uses_timestamp_and_copies_yaml(self) -> None:
        result = simulate(self.config)
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            run_dir, paths = export_results(
                result, self.config, CONFIG_PATH, output_root=root
            )
            self.assertEqual(run_dir.parent, root)
            self.assertRegex(run_dir.name, r"^\d{8}_\d{6}(?:_\d{2})?$")
            self.assertTrue((run_dir / CONFIG_PATH.name).exists())
            self.assertTrue(
                (run_dir / "single_stock_16_seeds_100_days.png").exists()
            )
            self.assertTrue(all(path.exists() for path in paths))


if __name__ == "__main__":
    unittest.main()
