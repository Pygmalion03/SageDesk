export interface KnowledgeUploadSuccess<TFile extends File, TResult> {
  index: number;
  file: TFile;
  result: TResult;
}

export interface KnowledgeUploadFailure<TFile extends File> {
  index: number;
  file: TFile;
  error: unknown;
}

export interface KnowledgeUploadQueueResult<TFile extends File, TResult> {
  total: number;
  successful: KnowledgeUploadSuccess<TFile, TResult>[];
  failed: KnowledgeUploadFailure<TFile>[];
}

export interface KnowledgeUploadPayload {
  sourceType: "file" | "url";
  file?: File | null;
  [key: string]: unknown;
}

interface KnowledgeUploadQueueOptions<TFile extends File, TResult> {
  files: readonly TFile[];
  createPayload: (file: TFile, index: number) => KnowledgeUploadPayload;
  upload: (payload: KnowledgeUploadPayload, file: TFile, index: number) => Promise<TResult>;
}

export async function runKnowledgeDocumentUploadQueue<TFile extends File, TResult>({
  files,
  createPayload,
  upload
}: KnowledgeUploadQueueOptions<TFile, TResult>): Promise<KnowledgeUploadQueueResult<TFile, TResult>> {
  const successful: KnowledgeUploadSuccess<TFile, TResult>[] = [];
  const failed: KnowledgeUploadFailure<TFile>[] = [];

  for (let index = 0; index < files.length; index += 1) {
    const file = files[index];
    try {
      const payload = createPayload(file, index);
      const result = await upload(payload, file, index);
      successful.push({ index, file, result });
    } catch (error) {
      failed.push({ index, file, error });
    }
  }

  return {
    total: files.length,
    successful,
    failed
  };
}
