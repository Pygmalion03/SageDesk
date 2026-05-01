import assert from "node:assert/strict";
import test from "node:test";

import {
  filterSupportedUploadFiles,
  getUploadFileDisplayName,
  isPreviewableUploadImage
} from "../src/services/knowledgeUploadFiles.js";

test("keeps supported document and image files while reporting rejected files", () => {
  const files = [
    new File(["pdf"], "manual.pdf", { type: "application/pdf" }),
    new File(["doc"], "policy.docx", {
      type: "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }),
    new File(["png"], "screenshot.png", { type: "image/png" }),
    new File(["exe"], "tool.exe", { type: "application/octet-stream" })
  ];

  const result = filterSupportedUploadFiles(files);

  assert.deepEqual(result.supported.map((file) => file.name), ["manual.pdf", "policy.docx", "screenshot.png"]);
  assert.deepEqual(result.rejected.map((file) => file.name), ["tool.exe"]);
});

test("recognizes previewable images by mime type or extension", () => {
  assert.equal(isPreviewableUploadImage(new File(["a"], "a.bin", { type: "image/png" })), true);
  assert.equal(isPreviewableUploadImage(new File(["b"], "b.jpg", { type: "" })), true);
  assert.equal(isPreviewableUploadImage(new File(["c"], "c.pdf", { type: "application/pdf" })), false);
});

test("uses folder-relative path when available", () => {
  const file = new File(["a"], "a.png", { type: "image/png" });
  Object.defineProperty(file, "webkitRelativePath", {
    value: "folder/nested/a.png"
  });

  assert.equal(getUploadFileDisplayName(file), "folder/nested/a.png");
});
