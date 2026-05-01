import assert from "node:assert/strict";
import test from "node:test";

import { runKnowledgeDocumentUploadQueue } from "../src/services/knowledgeUploadQueue.js";

test("uploads every selected file with a per-file payload", async () => {
  const files = [
    new File(["first"], "first.png", { type: "image/png" }),
    new File(["second"], "second.jpg", { type: "image/jpeg" })
  ];
  const seenNames: string[] = [];

  const result = await runKnowledgeDocumentUploadQueue({
    files,
    createPayload: (file) => ({ sourceType: "file", file }),
    upload: async (payload) => {
      seenNames.push(payload.file?.name || "");
      return { docName: payload.file?.name };
    }
  });

  assert.deepEqual(seenNames, ["first.png", "second.jpg"]);
  assert.equal(result.total, 2);
  assert.equal(result.successful.length, 2);
  assert.equal(result.failed.length, 0);
});

test("continues after an item fails and reports the failed file", async () => {
  const files = [
    new File(["first"], "first.png", { type: "image/png" }),
    new File(["bad"], "bad.png", { type: "image/png" }),
    new File(["last"], "last.png", { type: "image/png" })
  ];
  const attempted: string[] = [];

  const result = await runKnowledgeDocumentUploadQueue({
    files,
    createPayload: (file) => ({ sourceType: "file", file }),
    upload: async (payload) => {
      const name = payload.file?.name || "";
      attempted.push(name);
      if (name === "bad.png") {
        throw new Error("upload failed");
      }
      return { docName: name };
    }
  });

  assert.deepEqual(attempted, ["first.png", "bad.png", "last.png"]);
  assert.equal(result.total, 3);
  assert.equal(result.successful.length, 2);
  assert.equal(result.failed.length, 1);
  assert.equal(result.failed[0].file.name, "bad.png");
  assert.match(String(result.failed[0].error), /upload failed/);
});
