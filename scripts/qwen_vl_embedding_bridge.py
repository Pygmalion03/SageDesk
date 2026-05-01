#!/usr/bin/env python
from __future__ import annotations

import argparse
import json
import logging
import os
import threading
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


LOGGER = logging.getLogger("qwen_vl_embedding_bridge")
_RUNTIME_LOCK = threading.Lock()
_RUNTIME: "EmbeddingRuntime | None" = None


def env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.lower() in {"1", "true", "yes", "on"}


@dataclass
class EmbeddingRuntime:
    model_name: str
    device: str
    max_length: int
    batch_size: int
    normalize: bool
    processor: Any
    tokenizer: Any
    model: Any

    def embed(self, texts: list[str], dimensions: int | None) -> list[list[float]]:
        vectors: list[list[float]] = []
        for offset in range(0, len(texts), self.batch_size):
            batch = texts[offset:offset + self.batch_size]
            vectors.extend(self._embed_batch(batch, dimensions))
        return vectors

    def _embed_batch(self, texts: list[str], dimensions: int | None) -> list[list[float]]:
        import torch
        import torch.nn.functional as functional

        inputs = self._build_inputs(texts)
        with torch.inference_mode():
            tensor = self._forward(inputs)
            if tensor.dim() == 3:
                attention_mask = inputs.get("attention_mask")
                tensor = self._mean_pool(tensor, attention_mask)
            tensor = tensor.detach().float().cpu()

        rows: list[list[float]] = []
        for row in tensor:
            if dimensions and dimensions > 0:
                if row.numel() >= dimensions:
                    row = row[:dimensions]
                else:
                    row = functional.pad(row, (0, dimensions - row.numel()))
            if self.normalize:
                row = functional.normalize(row, p=2, dim=0)
            rows.append([float(value) for value in row.tolist()])
        return rows

    def _build_inputs(self, texts: list[str]) -> dict[str, Any]:
        if self.processor is not None:
            try:
                encoded = self.processor(
                    text=texts,
                    padding=True,
                    truncation=True,
                    max_length=self.max_length,
                    return_tensors="pt",
                )
                return self._move_to_device(dict(encoded))
            except TypeError:
                LOGGER.debug("AutoProcessor does not accept text-only arguments, falling back to tokenizer")

        if self.tokenizer is None:
            raise RuntimeError("No text tokenizer is available for the embedding model")

        encoded = self.tokenizer(
            texts,
            padding=True,
            truncation=True,
            max_length=self.max_length,
            return_tensors="pt",
        )
        return self._move_to_device(dict(encoded))

    def _move_to_device(self, inputs: dict[str, Any]) -> dict[str, Any]:
        moved: dict[str, Any] = {}
        for key, value in inputs.items():
            moved[key] = value.to(self.device) if hasattr(value, "to") else value
        return moved

    def _forward(self, inputs: dict[str, Any]) -> Any:
        if hasattr(self.model, "get_text_features"):
            return self._extract_tensor(self.model.get_text_features(**inputs))
        if hasattr(self.model, "encode_text"):
            return self._extract_tensor(self.model.encode_text(**inputs))
        try:
            output = self.model(**inputs, return_dict=True)
        except TypeError:
            output = self.model(**inputs)
        return self._extract_tensor(output)

    def _extract_tensor(self, output: Any) -> Any:
        import torch

        if isinstance(output, torch.Tensor):
            return output
        if isinstance(output, dict):
            for key in ("text_embeds", "embeddings", "pooler_output", "last_hidden_state"):
                value = output.get(key)
                if isinstance(value, torch.Tensor):
                    return value
        for attr in ("text_embeds", "embeddings", "pooler_output", "last_hidden_state"):
            value = getattr(output, attr, None)
            if isinstance(value, torch.Tensor):
                return value
        if isinstance(output, (list, tuple)):
            for value in output:
                if isinstance(value, torch.Tensor):
                    return value
        raise RuntimeError("Cannot find embedding tensor in model output")

    def _mean_pool(self, hidden_state: Any, attention_mask: Any) -> Any:
        if attention_mask is None:
            return hidden_state.mean(dim=1)
        mask = attention_mask.unsqueeze(-1).to(hidden_state.dtype)
        return (hidden_state * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)


def load_runtime() -> EmbeddingRuntime:
    global _RUNTIME
    if _RUNTIME is not None:
        return _RUNTIME
    with _RUNTIME_LOCK:
        if _RUNTIME is not None:
            return _RUNTIME
        _RUNTIME = create_runtime()
        return _RUNTIME


def create_runtime() -> EmbeddingRuntime:
    import torch
    from transformers import AutoModel, AutoProcessor, AutoTokenizer

    model_name = os.getenv("QWEN_VL_EMBEDDING_MODEL", "Qwen/Qwen3-VL-Embedding-2B")
    requested_device = os.getenv("QWEN_VL_EMBEDDING_DEVICE", "cuda")
    device = "cuda" if requested_device == "auto" and torch.cuda.is_available() else requested_device
    if requested_device == "auto" and not torch.cuda.is_available():
        device = "cpu"
    if requested_device.startswith("cuda") and not torch.cuda.is_available():
        LOGGER.warning("CUDA was requested but is unavailable; using CPU")
        device = "cpu"

    dtype_name = os.getenv("QWEN_VL_EMBEDDING_DTYPE", "auto").lower()
    model_kwargs: dict[str, Any] = {"trust_remote_code": True}
    if dtype_name == "auto":
        model_kwargs["torch_dtype"] = "auto"
    elif dtype_name in {"bf16", "bfloat16"}:
        model_kwargs["torch_dtype"] = torch.bfloat16
    elif dtype_name in {"fp16", "float16"}:
        model_kwargs["torch_dtype"] = torch.float16
    elif dtype_name in {"fp32", "float32"}:
        model_kwargs["torch_dtype"] = torch.float32

    processor = None
    tokenizer = None
    try:
        processor = AutoProcessor.from_pretrained(model_name, trust_remote_code=True)
    except Exception as exc:  # noqa: BLE001
        LOGGER.info("AutoProcessor load skipped: %s", exc)
    try:
        tokenizer = AutoTokenizer.from_pretrained(model_name, trust_remote_code=True)
    except Exception as exc:  # noqa: BLE001
        LOGGER.info("AutoTokenizer load skipped: %s", exc)

    LOGGER.info("Loading embedding model %s on %s", model_name, device)
    model = AutoModel.from_pretrained(model_name, **model_kwargs)
    model.eval()
    if device != "auto":
        model.to(device)

    return EmbeddingRuntime(
        model_name=model_name,
        device=device,
        max_length=env_int("QWEN_VL_EMBEDDING_MAX_LENGTH", 8192),
        batch_size=max(1, env_int("QWEN_VL_EMBEDDING_BATCH_SIZE", 4)),
        normalize=env_bool("QWEN_VL_EMBEDDING_NORMALIZE", True),
        processor=processor,
        tokenizer=tokenizer,
        model=model,
    )


class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "QwenVLEmbeddingBridge/1.0"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self.send_json(HTTPStatus.OK, {"status": "ok"})
            return
        self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/embeddings":
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        if not self.authorized():
            self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        try:
            payload = self.read_json()
            texts = normalize_input(payload.get("input"))
            if not texts:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "input is required"})
                return
            dimensions = normalize_dimensions(payload.get("dimensions"))
            runtime = load_runtime()
            vectors = runtime.embed(texts, dimensions)
            response = {
                "model": payload.get("model") or runtime.model_name,
                "embeddings": vectors,
                "dimension": len(vectors[0]) if vectors else dimensions,
            }
            self.send_json(HTTPStatus.OK, response)
        except Exception as exc:  # noqa: BLE001
            LOGGER.exception("Embedding request failed")
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(exc)})

    def authorized(self) -> bool:
        api_key = os.getenv("QWEN_VL_EMBEDDING_API_KEY", "")
        if not api_key:
            return True
        return self.headers.get("Authorization", "") == f"Bearer {api_key}"

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8")
        return json.loads(raw or "{}")

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: Any) -> None:
        LOGGER.info("%s - %s", self.address_string(), fmt % args)


def normalize_input(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [str(item) if item is not None else "" for item in value]
    return []


def normalize_dimensions(value: Any) -> int | None:
    if value is None or value == "":
        fallback = env_int("QWEN_VL_EMBEDDING_DIMENSIONS", 1024)
        return fallback if fallback > 0 else None
    try:
        dimensions = int(value)
        return dimensions if dimensions > 0 else None
    except (TypeError, ValueError):
        return None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Qwen3-VL embedding local HTTP bridge")
    parser.add_argument("--host", default=os.getenv("QWEN_VL_EMBEDDING_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=env_int("QWEN_VL_EMBEDDING_PORT", 8115))
    return parser.parse_args()


def main() -> None:
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO").upper(), format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args()
    server = ThreadingHTTPServer((args.host, args.port), BridgeHandler)
    LOGGER.info("Qwen3-VL embedding bridge listening on http://%s:%s", args.host, args.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
