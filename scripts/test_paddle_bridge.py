import os
import unittest

import paddle_bridge


class PaddleBridgePipelineTests(unittest.TestCase):

    def tearDown(self):
        os.environ.pop("PADDLE_BRIDGE_DEVICE", None)
        os.environ.pop("PADDLE_VL_REC_BACKEND", None)
        os.environ.pop("PADDLE_VL_REC_SERVER_URL", None)
        os.environ.pop("PADDLE_BRIDGE_USE_GITHUB_PADDLEOCR_VL", None)

    def test_normalizes_paddleocr_vl_15_aliases_to_vl_mode(self):
        self.assertEqual("paddleocr_vl_1_5", paddle_bridge.normalize_mode("PaddleOCR-VL-1.5"))
        self.assertEqual("paddleocr_vl_1_5", paddle_bridge.normalize_mode("PaddleOCR-VL-1.5-0.9B"))
        self.assertEqual("pp_structure_v3", paddle_bridge.normalize_mode("PP-StructureV3"))

    def test_vl_mode_initializes_paddleocr_vl_pipeline_with_selected_model(self):
        calls = []

        def fake_create_pipeline(**kwargs):
            calls.append(kwargs)
            return object()

        original = paddle_bridge.create_pipeline
        paddle_bridge.create_pipeline = fake_create_pipeline
        try:
            bridge = paddle_bridge.LocalPaddleBridge()
            bridge._get_pipeline("paddleocr_vl_1_5", "PaddleOCR-VL-1.5-0.9B")
        finally:
            paddle_bridge.create_pipeline = original

        self.assertEqual(1, len(calls))
        self.assertEqual("PaddleOCR-VL", calls[0]["pipeline"])
        self.assertEqual("PaddleOCR-VL-1.5-0.9B", calls[0]["vl_rec_model_name"])

    def test_resolves_distinct_primary_and_fallback_modes(self):
        modes = paddle_bridge.resolve_pipeline_modes({
            "fallbackMode": "pp_structure_v3",
        }, "paddleocr_vl_1_5")

        self.assertEqual(["paddleocr_vl_1_5", "pp_structure_v3"], modes)


if __name__ == "__main__":
    unittest.main()
