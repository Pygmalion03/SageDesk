import assert from "node:assert/strict";
import test from "node:test";

import { runKnowledgeDocumentActionQueue } from "../src/services/knowledgeActionQueue.js";

test("runs document actions sequentially for every selected item", async () => {
  const attempted: string[] = [];

  const result = await runKnowledgeDocumentActionQueue({
    items: ["1", "2", "3"],
    run: async (docId) => {
      attempted.push(docId);
      return docId;
    }
  });

  assert.deepEqual(attempted, ["1", "2", "3"]);
  assert.equal(result.total, 3);
  assert.equal(result.successful.length, 3);
  assert.equal(result.failed.length, 0);
});

test("continues document actions after one item fails", async () => {
  const attempted: string[] = [];

  const result = await runKnowledgeDocumentActionQueue({
    items: ["1", "bad", "3"],
    run: async (docId) => {
      attempted.push(docId);
      if (docId === "bad") {
        throw new Error("chunk failed");
      }
      return docId;
    }
  });

  assert.deepEqual(attempted, ["1", "bad", "3"]);
  assert.equal(result.successful.length, 2);
  assert.equal(result.failed.length, 1);
  assert.equal(result.failed[0].item, "bad");
  assert.match(String(result.failed[0].error), /chunk failed/);
});
