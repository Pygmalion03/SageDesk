# Resume Metrics Summary

## Dashboard Snapshot

- Window: 24h
- Total Users: 1
- Active Users: 1
- Total Sessions: 49
- Sessions In Window: 48
- Total Messages: 101
- Messages In Window: 100
- Avg Latency: 23560 ms
- P95 Latency: 29740 ms
- Success Rate: 94.3%
- Error Rate: 5.7%
- No-Doc Rate: 0.0%
- Slow Rate: 72.7%

## Benchmark Snapshot

- Concurrency: 1
- Requests: 3
- Throughput: 0.05 QPS
- Avg Total RT: 20187.47 ms
- P95 Total RT: 27248.16 ms
- Avg TTFB: 11.87 ms
- P95 TTFB: 14.13 ms

## Retrieval Evaluation Snapshot

- Dataset: resume-rag-eval-v1
- Case Count: 6
- Top-3 Recall: 100%
- Avg Keyword Coverage: 100%

## Resume Ready Bullets

1. Built dual retrieval and rerank with query rewrite and intent routing; on 6 regression cases, Top-3 recall reached 100% and answer keyword coverage reached 100%.
2. Implemented Redis + Lua + ZSET based queue limiting and SSE resource protection; under 1 concurrent clients and 3 requests, single-node throughput reached about 0.05 QPS with avg RT 20187.47 ms and P95 27248.16 ms.
3. Built RAG trace and dashboard observability for latency, success rate, error rate, no-doc rate, and slow-request monitoring.

## Recommended Next Optimizations

1. Add queue wait time into trace and dashboard so queueing cost and model cost can be separated.
2. Record predicted and expected labels for intent resolution to compute intent accuracy.
3. Add human-scored answer labels to the regression set beyond keyword coverage.
