import assert from "node:assert/strict";
import test from "node:test";

import { createStreamResponse } from "../src/hooks/useStreamResponse.ts";

function sseResponse(chunks: string[]) {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(chunk));
        }
        controller.close();
      }
    }),
    {
      status: 200,
      headers: {
        "Content-Type": "text/event-stream"
      }
    }
  );
}

test("dispatches final SSE event when stream closes without a trailing blank line", async () => {
  const originalFetch = globalThis.fetch;
  let done = false;

  globalThis.fetch = (async () => sseResponse(["event: done\n", "data: [DONE]"])) as typeof fetch;

  try {
    await createStreamResponse(
      {
        url: "http://example.test/stream",
        retryCount: 0
      },
      {
        onDone: () => {
          done = true;
        }
      }
    ).start();
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(done, true);
});
