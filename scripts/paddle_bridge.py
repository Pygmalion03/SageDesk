#!/usr/bin/env python
from __future__ import annotations

import argparse
import base64
import io
import json
import logging
import os
import tempfile
import traceback
import uuid
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from PIL import Image

try:
    import fitz  # type: ignore
except ImportError:  # pragma: no cover
    fitz = None

try:
    from paddlex import create_pipeline  # type: ignore
except ImportError:  # pragma: no cover
    create_pipeline = None


LOGGER = logging.getLogger("paddle_bridge")
RUNTIME_DIR = Path(__file__).resolve().parent / "paddle_bridge_runtime"
INPUT_DIR = RUNTIME_DIR / "inputs"
PAGE_DIR = RUNTIME_DIR / "pages"
CROP_DIR = RUNTIME_DIR / "crops"


def ensure_dirs() -> None:
    for path in (INPUT_DIR, PAGE_DIR, CROP_DIR):
        path.mkdir(parents=True, exist_ok=True)


def normalize_mode(mode: str | None) -> str:
    if not mode:
        return "pp_structure_v3"
    value = mode.strip().lower()
    aliases = {
        "pp_structure_v3": "pp_structure_v3",
        "pp-structurev3": "pp_structure_v3",
        "paddleocr_vl_1_5": "pp_structure_v3",
        "paddleocr-vl-1.5": "pp_structure_v3",
        "paddleocr_vl": "pp_structure_v3",
    }
    return aliases.get(value, "pp_structure_v3")


def mime_to_suffix(mime_type: str) -> str:
    normalized = (mime_type or "").lower()
    if "pdf" in normalized:
        return ".pdf"
    if "png" in normalized:
        return ".png"
    if "jpeg" in normalized or "jpg" in normalized:
        return ".jpg"
    if "webp" in normalized:
        return ".webp"
    if "bmp" in normalized:
        return ".bmp"
    if "gif" in normalized:
        return ".gif"
    return ".bin"


def bbox_to_dict(bbox: list[float] | tuple[float, float, float, float] | None) -> dict[str, float] | None:
    if not bbox or len(bbox) < 4:
        return None
    return {
        "x1": float(bbox[0]),
        "y1": float(bbox[1]),
        "x2": float(bbox[2]),
        "y2": float(bbox[3]),
    }


def normalize_bbox(raw_bbox: Any) -> list[float] | None:
    if raw_bbox is None:
        return None
    if isinstance(raw_bbox, (list, tuple)) and len(raw_bbox) >= 4 and all(isinstance(v, (int, float)) for v in raw_bbox[:4]):
        return [float(raw_bbox[0]), float(raw_bbox[1]), float(raw_bbox[2]), float(raw_bbox[3])]
    if isinstance(raw_bbox, (list, tuple)) and len(raw_bbox) >= 4:
        xs: list[float] = []
        ys: list[float] = []
        for point in raw_bbox:
            if isinstance(point, (list, tuple)) and len(point) >= 2:
                xs.append(float(point[0]))
                ys.append(float(point[1]))
        if xs and ys:
            return [min(xs), min(ys), max(xs), max(ys)]
    return None


def clamp_bbox(bbox: list[float], width: int, height: int) -> tuple[int, int, int, int] | None:
    x1 = max(0, min(width, int(bbox[0])))
    y1 = max(0, min(height, int(bbox[1])))
    x2 = max(0, min(width, int(bbox[2])))
    y2 = max(0, min(height, int(bbox[3])))
    if x2 <= x1 or y2 <= y1:
        return None
    return x1, y1, x2, y2


def extract_markdown_text(markdown_payload: Any) -> str:
    if markdown_payload is None:
        return ""
    if isinstance(markdown_payload, str):
        return markdown_payload
    if isinstance(markdown_payload, dict):
        texts = markdown_payload.get("markdown_texts")
        if isinstance(texts, str):
            return texts
        if isinstance(texts, dict):
            return "\n\n".join(str(v) for _, v in sorted(texts.items(), key=lambda item: str(item[0])))
        if isinstance(texts, list):
            return "\n\n".join(str(v) for v in texts)
    return ""


def response_json(handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


@dataclass
class PageInput:
    page_no: int
    source_path: Path
    image_path: Path
    page_text: str = ""
    text_blocks: list[dict[str, Any]] | None = None


class LocalPaddleBridge:

    def __init__(self) -> None:
        self._pipelines: dict[str, Any] = {}

    def analyze(self, payload: dict[str, Any]) -> dict[str, Any]:
        ensure_dirs()
        mime_type = str(payload.get("mimeType") or "")
        encoded = payload.get("contentBase64")
        if not encoded:
            raise ValueError("contentBase64 is required")

        file_bytes = base64.b64decode(encoded)
        input_path = self._write_input(file_bytes, mime_type)
        requested_mode = str(payload.get("mode") or "")
        normalized_mode = normalize_mode(requested_mode)
        options = payload.get("options") or {}

        page_inputs = self._prepare_page_inputs(input_path, mime_type)
        pipeline = None
        pipeline_error: Exception | None = None
        engine_name = normalized_mode

        try:
            pipeline = self._get_pipeline(normalized_mode)
        except Exception as exc:  # pragma: no cover - integration path
            pipeline_error = exc
            engine_name = "pdf_page_fallback"
            LOGGER.warning("failed to initialize %s pipeline, falling back to page rendering: %s", normalized_mode, exc)

        markdown_pages: list[str] = []
        visual_blocks: list[dict[str, Any]] = []

        for page_input in page_inputs:
            if self._is_page_skipped(page_input, options):
                continue
            try:
                if pipeline is not None:
                    page_markdown, page_visual_blocks = self._run_page(pipeline, page_input)
                else:
                    raise RuntimeError("visual pipeline unavailable")
            except Exception as exc:  # pragma: no cover - integration path
                if pipeline is not None:
                    LOGGER.warning("visual parsing failed on page %s, falling back to page rendering: %s", page_input.page_no, exc)
                pipeline = None
                if pipeline_error is None:
                    pipeline_error = exc
                engine_name = "pdf_page_fallback"
                page_markdown, page_visual_blocks = self._fallback_page(page_input, exc)
            if page_markdown.strip():
                markdown_pages.append(page_markdown.strip())
            visual_blocks.extend(page_visual_blocks)

        text = "\n\n".join(markdown_pages).strip()
        document = {
            "text": text,
            "sections": [],
            "tables": [],
            "visualBlocks": visual_blocks,
            "metadata": {
                "engine": engine_name,
                "requestedMode": requested_mode or normalized_mode,
                "fallbackApplied": engine_name != normalized_mode,
                "fallbackReason": str(pipeline_error) if pipeline_error else None,
                "pageCount": len(page_inputs),
                "sourcePath": str(input_path),
            },
        }
        return {
            "text": text,
            "metadata": document["metadata"],
            "document": document,
        }

    def _get_pipeline(self, mode: str) -> Any:
        if create_pipeline is None:
            raise RuntimeError("paddlex is not installed. Run `pip install -r scripts/paddle_bridge_requirements.txt` first.")
        if mode not in self._pipelines:
            LOGGER.info("initializing paddle pipeline: %s", mode)
            self._pipelines[mode] = create_pipeline(pipeline="PP-StructureV3")
        return self._pipelines[mode]

    def _write_input(self, file_bytes: bytes, mime_type: str) -> Path:
        suffix = mime_to_suffix(mime_type)
        file_path = INPUT_DIR / f"{uuid.uuid4().hex}{suffix}"
        file_path.write_bytes(file_bytes)
        return file_path

    def _prepare_page_inputs(self, input_path: Path, mime_type: str) -> list[PageInput]:
        if "pdf" not in (mime_type or "").lower():
            return [PageInput(page_no=1, source_path=input_path, image_path=input_path)]
        if fitz is None:
            raise RuntimeError("PyMuPDF is required for PDF rendering. Run `pip install -r scripts/paddle_bridge_requirements.txt` first.")

        page_inputs: list[PageInput] = []
        with fitz.open(input_path) as document:
            for index, page in enumerate(document, start=1):
                pixmap = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
                image_path = PAGE_DIR / f"{input_path.stem}-page-{index}.png"
                pixmap.save(image_path)
                page_text, text_blocks = self._extract_pdf_text(page, pixmap.width, pixmap.height)
                page_inputs.append(PageInput(
                    page_no=index,
                    source_path=input_path,
                    image_path=image_path,
                    page_text=page_text,
                    text_blocks=text_blocks,
                ))
        return page_inputs

    def _run_page(self, pipeline: Any, page_input: PageInput) -> tuple[str, list[dict[str, Any]]]:
        predict_kwargs = {
            "input": str(page_input.image_path),
            "use_chart_recognition": True,
            "format_block_content": True,
        }
        results = list(pipeline.predict(**predict_kwargs))
        if not results:
            return "", []
        result = results[0]
        json_payload = getattr(result, "json", None)
        markdown_payload = getattr(result, "markdown", None)
        if callable(json_payload):
            json_payload = json_payload()
        if callable(markdown_payload):
            markdown_payload = markdown_payload()
        if not isinstance(json_payload, dict):
            json_payload = {}

        page_markdown = extract_markdown_text(markdown_payload)
        page_visual_blocks = self._extract_visual_blocks(page_input, json_payload)
        return page_markdown, page_visual_blocks

    def _fallback_page(self, page_input: PageInput, error: Exception | None = None) -> tuple[str, list[dict[str, Any]]]:
        with Image.open(page_input.image_path) as image:
            image.load()
            width, height = image.size
        page_text = page_input.page_text.strip()
        summary = page_text[:500] if page_text else f"Rendered page image {page_input.page_no}"
        visual_block = {
            "blockId": f"{page_input.source_path.stem}-p{page_input.page_no}-page",
            "blockType": "page",
            "pageNo": page_input.page_no,
            "imageUri": str(page_input.image_path),
            "text": page_text,
            "markdown": page_text,
            "summary": summary,
            "nearbyContext": page_text,
            "boundingBox": {
                "x1": 0.0,
                "y1": 0.0,
                "x2": float(width),
                "y2": float(height),
            },
            "metadata": {
                "source_page_image": str(page_input.image_path),
                "fallback": "pdf_text_layer" if page_text else "page_image_only",
                "fallback_error": str(error) if error else None,
            },
        }
        return page_text, [visual_block]

    def _extract_visual_blocks(self, page_input: PageInput, json_payload: dict[str, Any]) -> list[dict[str, Any]]:
        payload = json_payload.get("res") if isinstance(json_payload.get("res"), dict) else json_payload
        parsing_res = payload.get("parsing_res_list") or []
        if not isinstance(parsing_res, list):
            return []

        with Image.open(page_input.image_path) as image:
            image.load()
            width, height = image.size
            text_blocks = [block for block in parsing_res if str(block.get("block_label") or "").lower() in {"text", "paragraph_title", "doc_title", "figure_title"}]
            visual_blocks: list[dict[str, Any]] = []
            for index, block in enumerate(parsing_res):
                label = str(block.get("block_label") or "").lower()
                if label not in {"image", "chart", "table", "formula", "figure_title", "seal"}:
                    continue
                bbox = normalize_bbox(block.get("block_bbox"))
                crop_path = None
                if bbox:
                    crop_box = clamp_bbox(bbox, width, height)
                    if crop_box:
                        crop = image.crop(crop_box)
                        crop_path = CROP_DIR / f"{page_input.image_path.stem}-block-{index}.png"
                        crop.save(crop_path)
                summary = str(block.get("block_content") or "").strip()
                nearby_context = self._find_nearby_context(bbox, text_blocks)
                visual_blocks.append({
                    "blockId": f"{page_input.source_path.stem}-p{page_input.page_no}-b{index}",
                    "blockType": label,
                    "pageNo": page_input.page_no,
                    "imageUri": str(crop_path) if crop_path else str(page_input.image_path),
                    "text": summary,
                    "markdown": summary,
                    "summary": summary,
                    "nearbyContext": nearby_context,
                    "boundingBox": bbox_to_dict(bbox),
                    "metadata": {
                        "source_page_image": str(page_input.image_path),
                    },
                })
        return visual_blocks

    def _extract_pdf_text(self, page: Any, image_width: int, image_height: int) -> tuple[str, list[dict[str, Any]]]:
        if fitz is None:
            return "", []
        page_rect = page.rect
        scale_x = image_width / float(page_rect.width or 1)
        scale_y = image_height / float(page_rect.height or 1)
        texts: list[str] = []
        blocks: list[dict[str, Any]] = []
        for block in page.get_text("blocks", sort=True):
            if len(block) < 7 or int(block[6]) != 0:
                continue
            raw_text = str(block[4] or "").strip()
            if not raw_text:
                continue
            normalized_text = "\n".join(line.strip() for line in raw_text.splitlines() if line.strip())
            if not normalized_text:
                continue
            bbox = [
                float(block[0]) * scale_x,
                float(block[1]) * scale_y,
                float(block[2]) * scale_x,
                float(block[3]) * scale_y,
            ]
            texts.append(normalized_text)
            blocks.append({
                "block_label": "text",
                "block_content": normalized_text,
                "block_bbox": bbox,
            })
        return "\n\n".join(texts).strip(), blocks

    def _find_nearby_context(self, bbox: list[float] | None, text_blocks: list[dict[str, Any]]) -> str:
        if bbox is None:
            return ""
        y1, y2 = bbox[1], bbox[3]
        candidates: list[str] = []
        for block in text_blocks:
            block_bbox = normalize_bbox(block.get("block_bbox"))
            if not block_bbox:
                continue
            center_y = (block_bbox[1] + block_bbox[3]) / 2
            if y1 - 200 <= center_y <= y2 + 200:
                content = str(block.get("block_content") or "").strip()
                if content:
                    candidates.append(content)
        return "\n".join(candidates[:3])

    def _is_page_skipped(self, page_input: PageInput, options: dict[str, Any]) -> bool:
        page_limit = options.get("pageLimit")
        return bool(page_limit and page_input.page_no > int(page_limit))


class PaddleBridgeHandler(BaseHTTPRequestHandler):

    bridge = LocalPaddleBridge()
    api_key = os.getenv("PADDLE_BRIDGE_API_KEY", "").strip()

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/document-analysis":
            response_json(self, HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        if self.api_key:
            auth = self.headers.get("Authorization", "")
            if auth != f"Bearer {self.api_key}":
                response_json(self, HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(content_length)
            payload = json.loads(raw_body.decode("utf-8"))
            result = self.bridge.analyze(payload)
            response_json(self, HTTPStatus.OK, result)
        except Exception as exc:  # pragma: no cover - integration path
            LOGGER.exception("paddle bridge request failed")
            response_json(
                self,
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {
                    "error": str(exc),
                    "traceback": traceback.format_exc(),
                },
            )

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A003
        LOGGER.info("%s - %s", self.client_address[0], format % args)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Local Paddle document-analysis bridge")
    parser.add_argument("--host", default=os.getenv("PADDLE_BRIDGE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("PADDLE_BRIDGE_PORT", "8099")))
    parser.add_argument("--log-level", default=os.getenv("PADDLE_BRIDGE_LOG_LEVEL", "INFO"))
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    logging.basicConfig(level=getattr(logging, args.log_level.upper(), logging.INFO))
    ensure_dirs()
    server = ThreadingHTTPServer((args.host, args.port), PaddleBridgeHandler)
    LOGGER.info("paddle bridge listening on http://%s:%s", args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        LOGGER.info("paddle bridge stopped")


if __name__ == "__main__":
    main()
