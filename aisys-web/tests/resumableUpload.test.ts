import { test } from "node:test";
import assert from "node:assert/strict";
import { uploadMissingParts } from "../src/utils/resumableUpload.ts";

test("resumes the observed 74/76 upload with exactly parts 24 and 26", async () => {
  const sent: number[] = [];
  const progress: number[] = [];
  const file = new Blob([new Uint8Array(758)]);
  await uploadMissingParts({
    file, chunkSize: 10, totalChunks: 76,
    receivedPartNumbers: Array.from({ length: 76 }, (_, i) => i + 1).filter(n => n !== 24 && n !== 26),
    send: async (n, blob, report) => { sent.push(n); assert.equal(blob.size, 10); report(blob.size); },
    progress: n => progress.push(n)
  });
  assert.deepEqual(sent.sort((a, b) => a - b), [24, 26]);
  assert.equal(progress.at(-1), 99);
  assert.ok(progress.every(n => n < 100));
});

test("retries transient failures and preserves the short final part", async () => {
  let attempts = 0;
  await uploadMissingParts({
    file: new Blob([new Uint8Array(25)]), chunkSize: 10, totalChunks: 3,
    receivedPartNumbers: [1, 2], retryDelayMs: 0,
    send: async (n, blob) => {
      assert.equal(n, 3); assert.equal(blob.size, 5);
      if (++attempts < 3) throw new Error("timeout");
    },
    progress: () => {}
  });
  assert.equal(attempts, 3);
});

test("stops retrying after three attempts and never reports completion", async () => {
  let attempts = 0;
  let completed = 0;
  await assert.rejects(uploadMissingParts({
    file: new Blob([new Uint8Array(10)]), chunkSize: 10, totalChunks: 1,
    receivedPartNumbers: [], retryDelayMs: 0,
    send: async () => { attempts++; throw new Error("network"); },
    progress: (_, n) => { completed = n; }
  }), /第 1 片上传失败/);
  assert.equal(attempts, 3);
  assert.equal(completed, 0);
});

test("rejects mismatched file geometry before sending any parts", async () => {
  await assert.rejects(uploadMissingParts({
    file: new Blob([new Uint8Array(10)]), chunkSize: 10, totalChunks: 76,
    receivedPartNumbers: [], send: async () => assert.fail("must not upload"), progress: () => {}
  }), /不匹配/);
});

test("does not resend acknowledged parts when finalization needs retrying", async () => {
  await uploadMissingParts({
    file: new Blob([new Uint8Array(20)]), chunkSize: 10, totalChunks: 2,
    receivedPartNumbers: [1, 2], send: async () => assert.fail("already uploaded"),
    progress: percent => assert.equal(percent, 99)
  });
});

test("does not retry permanent errors such as expired credentials", async () => {
  let attempts = 0;
  await assert.rejects(uploadMissingParts({
    file: new Blob([new Uint8Array(10)]), chunkSize: 10, totalChunks: 1,
    receivedPartNumbers: [], retryDelayMs: 0,
    send: async () => { attempts++; throw Object.assign(new Error("HTTP 401"), { retryable: false }); },
    progress: () => {}
  }), /HTTP 401/);
  assert.equal(attempts, 1);
});
