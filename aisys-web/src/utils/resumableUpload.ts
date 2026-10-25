/** Upload only missing parts; progress reaches 100 only after finalization. */
export async function uploadMissingParts(options: {
  file: Blob;
  chunkSize: number;
  totalChunks: number;
  receivedPartNumbers: number[];
  send: (part: number, blob: Blob, progress: (loaded: number) => void) => Promise<void>;
  progress: (percent: number, completed: number) => void;
  retry?: (part: number, attempt: number) => void;
  retryDelayMs?: number;
}) {
  const { file, chunkSize, totalChunks } = options;
  if (chunkSize <= 0 || Math.ceil(file.size / chunkSize) !== totalChunks) {
    throw new Error("文件大小或分片信息不匹配，请选择原始文件");
  }
  const received = new Set(options.receivedPartNumbers);
  const partSize = (part: number) => Math.min(chunkSize, file.size - (part - 1) * chunkSize);
  const loaded = Array.from({ length: totalChunks }, (_, i) => received.has(i + 1) ? partSize(i + 1) : 0);
  const missing = Array.from({ length: totalChunks }, (_, i) => i + 1).filter(n => !received.has(n));
  let completed = totalChunks - missing.length;
  const update = () => options.progress(Math.min(99, Math.floor(loaded.reduce((a, b) => a + b, 0) / file.size * 100)), completed);
  update();
  let next = 0;
  let failure: Error | undefined;
  const worker = async () => {
    while (!failure) {
      const part = missing[next++];
      if (part === undefined) return;
      const blob = file.slice((part - 1) * chunkSize, Math.min(part * chunkSize, file.size));
      for (let attempt = 1; attempt <= 3; attempt++) {
        try {
          await options.send(part, blob, n => {
            loaded[part - 1] = Math.min(Math.max(n, 0), blob.size);
            update();
          });
          loaded[part - 1] = blob.size;
          completed++;
          update();
          break;
        } catch (error: any) {
          loaded[part - 1] = 0;
          update();
          if (attempt === 3 || error?.retryable === false) {
            failure = new Error(`第 ${part} 片上传失败：${error?.message || "网络错误"}。已上传分片已保留，可继续上传。`);
            return;
          }
          options.retry?.(part, attempt);
          await new Promise(resolve => setTimeout(resolve, (options.retryDelayMs ?? 1000) * attempt));
        }
      }
    }
  };
  // Settle active workers before exposing retry, avoiding overlapping uploads.
  await Promise.all(Array.from({ length: Math.min(3, missing.length) }, worker));
  if (failure) throw failure;
}
