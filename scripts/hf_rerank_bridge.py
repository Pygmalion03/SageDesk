#!/usr/bin/env python
from __future__ import annotations

import argparse
import json
import logging
import math
import os
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

LOGGER = logging.getLogger("hf_rerank_bridge")


class RerankRuntime:
    def __init__(self) -> None:
        self.device = os.getenv("HF_RERANK_DEVICE", "cuda")
        self.default_model_name = os.getenv("HF_RERANK_MODEL", "Qwen/Qwen3-Reranker-0.6B")
        self.batch_size = env_int("HF_RERANK_BATCH_SIZE", 4)
        self.apply_sigmoid = env_bool("HF_RERANK_SIGMOID", True)
        self._lock = threading.Lock()
        self._model_name: str | None = None
        self._model: Any = None

    def rerank(self, model_name: str | None, query: str, documents: list[str], top_n: int | None) -> list[dict[str, Any]]:
        model = self._load_model(model_name or self.default_model_name)
        pairs = [(query, document) for document in documents]
        scores = model.predict(pairs, batch_size=self.batch_size, show_progress_bar=False)
        values = normalize_scores(scores, self.apply_sigmoid)
        results = [
            {"index": index, "score": score, "document": documents[index]}
            for index, score in enumerate(values)
        ]
        results.sort(key=lambda item: item["score"], reverse=True)
        if top_n is not None and top_n > 0:
            results = results[:top_n]
        return results

    def _load_model(self, model_name: str) -> Any:
        with self._lock:
            if self._model is not None and self._model_name == model_name:
                return self._model

            from sentence_transformers import CrossEncoder

            LOGGER.info("Loading rerank model %s on %s", model_name, self.device)
            self._model = CrossEncoder(
                model_name,
                device=self.device,
                trust_remote_code=True,
            )
            self._model_name = model_name
            return self._model


runtime: RerankRuntime | None = None


class BridgeHandler(BaseHTTPRequestHandler):
    server_version = "HfRerankBridge/1.0"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self.send_json(HTTPStatus.OK, {"status": "ok"})
            return
        self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/rerank":
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return

        try:
            payload = self.read_json()
            query = str(payload.get("query") or "")
            documents = normalize_documents(payload.get("documents"))
            top_n = normalize_top_n(payload.get("top_n"))
            model = payload.get("model")
            results = get_runtime().rerank(str(model) if model else None, query, documents, top_n)
            self.send_json(HTTPStatus.OK, {"model": model or get_runtime().default_model_name, "results": results})
        except Exception as exc:  # noqa: BLE001
            LOGGER.exception("Rerank request failed")
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


def get_runtime() -> RerankRuntime:
    global runtime
    if runtime is None:
        runtime = RerankRuntime()
    return runtime


def normalize_documents(value: Any) -> list[str]:
    if not isinstance(value, list):
        raise ValueError("documents must be a list of strings")
    return [str(item) if item is not None else "" for item in value]


def normalize_top_n(value: Any) -> int | None:
    if value is None:
        return None
    top_n = int(value)
    return top_n if top_n > 0 else None


def normalize_scores(scores: Any, apply_sigmoid: bool) -> list[float]:
    values: list[float] = []
    for item in scores:
        if hasattr(item, "tolist"):
            item = item.tolist()
        if isinstance(item, list):
            item = item[0] if item else 0.0
        value = float(item)
        if apply_sigmoid and (value < 0.0 or value > 1.0):
            value = 1.0 / (1.0 + math.exp(-value))
        values.append(value)
    return values


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
    parser = argparse.ArgumentParser(description="Hugging Face rerank local HTTP bridge")
    parser.add_argument("--host", default=os.getenv("HF_RERANK_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=env_int("HF_RERANK_PORT", 8126))
    return parser.parse_args()


def main() -> None:
    logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO").upper(), format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args()
    server = ThreadingHTTPServer((args.host, args.port), BridgeHandler)
    LOGGER.info("HF rerank bridge listening on http://%s:%s", args.host, args.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
