#!/usr/bin/env python
from __future__ import annotations

import argparse
import json
import logging
import os
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

LOGGER = logging.getLogger("hf_embedding_bridge")


class EmbeddingRuntime:
    def __init__(self) -> None:
        self.model_name = os.getenv("HF_EMBEDDING_MODEL", "Qwen/Qwen3-Embedding-0.6B")
        self.device = os.getenv("HF_EMBEDDING_DEVICE", "cuda")
        self.batch_size = env_int("HF_EMBEDDING_BATCH_SIZE", 8)
        self.default_dimensions = env_int("HF_EMBEDDING_DIMENSIONS", 1024)
        self.normalize = env_bool("HF_EMBEDDING_NORMALIZE", True)

        from sentence_transformers import SentenceTransformer

        LOGGER.info("Loading embedding model %s on %s", self.model_name, self.device)
        self.model = SentenceTransformer(
            self.model_name,
            device=self.device,
            trust_remote_code=True,
        )

    def embed(self, texts: list[str], dimensions: int | None) -> list[list[float]]:
        dim = dimensions or self.default_dimensions
        vectors = self.model.encode(
            texts,
            batch_size=self.batch_size,
            normalize_embeddings=self.normalize,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        rows: list[list[float]] = []
        for vector in vectors:
            row = vector.astype("float32").tolist()
            if dim and dim > 0:
                row = fit_dimensions(row, dim)
            rows.append([float(value) for value in row])
        return rows


runtime: EmbeddingRuntime | None = None


class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "HfEmbeddingBridge/1.0"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self.send_json(HTTPStatus.OK, {"status": "ok"})
            return
        self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/embeddings":
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return

        try:
            payload = self.read_json()
            texts = normalize_input(payload.get("input"))
            dimensions = normalize_dimensions(payload.get("dimensions"))
            vectors = get_runtime().embed(texts, dimensions)
            self.send_json(HTTPStatus.OK, {
                "model": payload.get("model") or get_runtime().model_name,
                "embeddings": vectors,
                "dimension": len(vectors[0]) if vectors else dimensions,
            })
        except Exception as exc:  # noqa: BLE001
            LOGGER.exception("Embedding request failed")
            self.send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(exc)})

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8")
        if not raw:
            return {}
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise ValueError("JSON body must be an object")
        return payload

    def send_json(self, status: HTTPStatus, body: dict[str, Any]) -> None:
        content = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, fmt: str, *args: Any) -> None:
        LOGGER.debug(fmt, *args)


def get_runtime() -> EmbeddingRuntime:
    global runtime
    if runtime is None:
        runtime = EmbeddingRuntime()
    return runtime


def normalize_input(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return value
    raise ValueError("input must be a string or a list of strings")


def normalize_dimensions(value: Any) -> int | None:
    if value is None:
        return None
    dim = int(value)
    if dim <= 0:
        return None
    return dim


def fit_dimensions(row: list[float], dimensions: int) -> list[float]:
    if len(row) == dimensions:
        return row
    if len(row) > dimensions:
        return row[:dimensions]
    return row + [0.0] * (dimensions - len(row))


def env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if not value:
        return default
    return int(value)


def env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Hugging Face embedding local HTTP bridge")
    parser.add_argument("--host", default=os.getenv("HF_EMBEDDING_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=env_int("HF_EMBEDDING_PORT", 8125))
    return parser.parse_args()


def main() -> None:
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO").upper(), format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args()
    server = ThreadingHTTPServer((args.host, args.port), BridgeHandler)
    LOGGER.info("HF embedding bridge listening on http://%s:%s", args.host, args.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
