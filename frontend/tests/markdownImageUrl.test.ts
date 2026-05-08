import assert from "node:assert/strict";
import test from "node:test";

import { resolveMarkdownImageSrc } from "../src/services/markdownImageUrl.ts";

test("proxies stored image URIs through backend media preview", () => {
  assert.equal(
    resolveMarkdownImageSrc("s3://rag-default-store/image 1.jpg", "/api/ragent"),
    "/api/ragent/rag/media/preview?uri=s3%3A%2F%2Frag-default-store%2Fimage%201.jpg"
  );
});

test("proxies local file paths through backend media preview", () => {
  assert.equal(
    resolveMarkdownImageSrc("E:\\Projects\\ragent\\scripts\\paddle_api_runtime\\page.png", "/api/ragent"),
    "/api/ragent/rag/media/preview?uri=E%3A%5CProjects%5Cragent%5Cscripts%5Cpaddle_api_runtime%5Cpage.png"
  );
});

test("keeps browser-loadable image URLs unchanged", () => {
  assert.equal(resolveMarkdownImageSrc("https://example.test/a.png", "/api/ragent"), "https://example.test/a.png");
  assert.equal(resolveMarkdownImageSrc("data:image/png;base64,abc", "/api/ragent"), "data:image/png;base64,abc");
  assert.equal(
    resolveMarkdownImageSrc("/api/ragent/rag/media/preview?uri=s3%3A%2F%2Fbucket%2Fa.png", "/api/ragent"),
    "/api/ragent/rag/media/preview?uri=s3%3A%2F%2Fbucket%2Fa.png"
  );
});

test("adds the API base to bare media preview paths", () => {
  assert.equal(
    resolveMarkdownImageSrc("/rag/media/preview?uri=s3%3A%2F%2Fbucket%2Fa.png", "/api/ragent"),
    "/api/ragent/rag/media/preview?uri=s3%3A%2F%2Fbucket%2Fa.png"
  );
});
