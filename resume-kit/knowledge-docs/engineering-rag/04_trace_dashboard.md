# Trace Dashboard

## 可观测目标

RAG 项目最怕两类问题：一种是“答案不准但不知道问题出在哪一段链路”，另一种是“系统变慢了但不知道是检索慢、模型慢还是排队慢”。因此项目里专门做了 RAG Trace 和 dashboard，把一次链路拆成可追踪、可汇总、可导出的节点数据。

## Trace 节点

当前链路里重点可观测的节点包括：

- query rewrite
- intent resolve
- retrieval engine
- multi-channel retrieval
- llm routing
- model provider

其中多通道检索节点现在会额外记录：

- 启用的检索通道
- 每个通道的耗时、置信度、返回 Chunk 数
- 最终返回的文档 ID 和分数

这意味着你可以直接基于 Trace 做离线评估，而不是手工看日志。

## Dashboard 指标

管理端 dashboard 已经能输出这些核心指标：

- Overview
  总用户数、活跃用户数、总会话数、近窗口会话数、总消息数、近窗口消息数。

- Performance
  平均延迟、P95 延迟、成功率、错误率、无文档回复率、慢请求占比。

- Trends
  会话、消息、活跃用户、平均延迟、质量趋势。

## 数据怎么用在简历里

dashboard 的价值不只是“有页面”，而是它能让你写出可被追问的数据。比如：

- 平均 RT / P95
- 成功率 / 错误率
- 无文档回复率
- 近 24 小时或近 7 天趋势

如果你能把 dashboard 截图、导出摘要和压测结果放在一起，这个项目的可信度会明显提升。
