export interface KnowledgeActionSuccess<TItem, TResult> {
  index: number;
  item: TItem;
  result: TResult;
}

export interface KnowledgeActionFailure<TItem> {
  index: number;
  item: TItem;
  error: unknown;
}

export interface KnowledgeActionQueueResult<TItem, TResult> {
  total: number;
  successful: KnowledgeActionSuccess<TItem, TResult>[];
  failed: KnowledgeActionFailure<TItem>[];
}

interface KnowledgeActionQueueOptions<TItem, TResult> {
  items: readonly TItem[];
  run: (item: TItem, index: number) => Promise<TResult>;
}

export async function runKnowledgeDocumentActionQueue<TItem, TResult>({
  items,
  run
}: KnowledgeActionQueueOptions<TItem, TResult>): Promise<KnowledgeActionQueueResult<TItem, TResult>> {
  const successful: KnowledgeActionSuccess<TItem, TResult>[] = [];
  const failed: KnowledgeActionFailure<TItem>[] = [];

  for (let index = 0; index < items.length; index += 1) {
    const item = items[index];
    try {
      const result = await run(item, index);
      successful.push({ index, item, result });
    } catch (error) {
      failed.push({ index, item, error });
    }
  }

  return {
    total: items.length,
    successful,
    failed
  };
}
