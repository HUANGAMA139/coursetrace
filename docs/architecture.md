# 架构与关键调用链

项目采用模块化单体：server 按鉴权、课程、视频、任务、学习资料、检索和 Agent 分包，client 提供学习工作台。数据库迁移使用 Flyway，当前为 V1～V7。

## 离线处理

~~~text
UploadController → UploadService → MinIO 分片与合并 → SHA-256 / ffprobe
→ VideoService.saveUploaded → 同事务提交视频与上传完成状态
旧 VideoController 上传入口保留兼容
ProcessingService → 同事务创建 task / step / outbox
OutboxPublisher → Kafka ACK → 标记已发送
ProcessingWorker → LearningWorker / IndexWorker
~~~

上传对象与数据库不共享一个事务，失败按保存进度补偿。已实现 MP4 分片续传与鉴权 Range 读取。分片默认 8 MiB，服务器计算分片摘要并确认记录；最终合并后再次检查完整摘要和媒体。新视频仍进入原有处理流程。

处理版本 1 检查资源；版本 2 执行 FFmpeg 音频提取、ASR 提交/轮询、字幕与笔记；版本 3 执行切块、向量批次、Qdrant 写入与活动索引发布。

任务、阶段和首个事件在本地事务内提交。Kafka 发送成功但数据库标记失败时，Outbox 仍可能再次投递，消费者必须校验业务身份、runNo、阶段和 executionToken。不能把它解释成第三方请求严格恰好一次。

阶段领取和结果提交使用短事务，外部网络与媒体操作在事务外。音频、远端任务、响应和向量批次保存检查点；ASR 等待通过 WAITING_EXTERNAL 和后续事件唤醒，不长时间阻塞消费者。有限重试耗尽后记录失败，部分中断由用户人工恢复。

主要代码：ProcessingService、OutboxPublisher、ProcessingWorker、LearningWorker、ArtifactStore、IndexWorker。

## 普通问答与引用

~~~text
CourseQaService → SearchIndexService.active
→ QwenEmbeddingClient → RetrievalService → QdrantIndexClient
→ MySQL 原文与来源映射校验
→ QaModelClient → CitationValidator
→ VideoQuestionAnswer → CourseWorkspace → VideoPlayer.seekTo
~~~

后端固定 owner/course/video/transcript/index 范围。向量命中后核对点、块、摘要与字幕映射，有限补充邻句。模型选择来源编号，引用中的视频、文本偏移与时间由服务端生成。

chat_turn 保存请求幂等、状态、证据、模型响应、校验后的答案和调用计数；历史读取会重新校验引用。引用结构正确不能替代内容支持度审阅。

## 单学习 Agent

~~~text
共享问答准入 → 冻结当前视频与索引范围
→ 模型提出 ToolExecutionRequest
→ Java 校验工具 / 参数 / 来源 / 预算
→ 搜索或补读 → 匹配 tool_call_id 回传
→ 继续或停止 → 最终 JSON 回答与引用校验
~~~

只读工具为 searchSegments 与 readSurroundingContext。模型不能通过参数修改用户或视频范围，补读锚点必须来自本轮已提供材料。使用 LangChain4j 低层协议显式实现循环。

默认最多 4 次决策、4 次工具、2 次搜索、2 次补读、5 次聊天和 120 秒；最终生成包含在预算内。普通/深入模式共享请求幂等、每用户活动 1 个、每分钟 6 次及进程并发 2 个。

agent_run 与 agent_step 记录额度、步骤、来源、耗时和停止原因。缓存仍计工具交互，但不会重复调用向量。UNKNOWN 不自动收费重跑，LIMIT_REACHED 与资料不足分开。

主要代码：AgentLoop、AgentModelClient、AgentToolService、ContextReadService、EvidenceLedger、AgentJournal。

## 设计边界

业务数据和有限近期对话存入 MySQL，媒体与结果对象存入 MinIO，向量索引存入 Qdrant。Redis Lua 对用户桶、全局桶和请求准入票据做原子检查；历史仍在 MySQL，Semaphore 并发限制仅作用于当前 JVM。Redis 限制新工作的提交速率，未将所有后台模型 API 改为供应商级限流。

项目以中文口述课程、本人当前视频为首版范围。媒体格式、语义支持度、第三方超时和长课负载需要分别验证。

## 代码入口

| 路径 | 关注点 |
|---|---|
| [ProcessingService](../server/src/main/java/com/coursetrace/processing/ProcessingService.java) → [OutboxPublisher](../server/src/main/java/com/coursetrace/outbox/OutboxPublisher.java) | 本地事务提交任务与事件、确认投递、重复事件处理 |
| [LearningWorker](../server/src/main/java/com/coursetrace/learning/LearningWorker.java) → [IndexWorker](../server/src/main/java/com/coursetrace/retrieval/IndexWorker.java) | 转录与笔记阶段、字幕切块与索引检查点 |
| [CourseQaService](../server/src/main/java/com/coursetrace/retrieval/CourseQaService.java) → [CitationValidator](../server/src/main/java/com/coursetrace/retrieval/CitationValidator.java) | 问答准入、证据检索与最终引用校验 |
| [AgentLoop](../server/src/main/java/com/coursetrace/agent/AgentLoop.java) → [AgentToolService](../server/src/main/java/com/coursetrace/agent/AgentToolService.java) → [AgentJournal](../server/src/main/java/com/coursetrace/agent/AgentJournal.java) | 工具协议、执行预算与持久化执行记录 |
| [VideoQuestionAnswer](../client/src/components/VideoQuestionAnswer.vue) → [CourseWorkspace](../client/src/components/CourseWorkspace.vue) → [VideoPlayer](../client/src/components/VideoPlayer.vue) | 来源按钮触发 seek 事件，工作台调用播放器 seekTo |

## 上传恢复与令牌桶

上传会话与分片记录在 MySQL。分片对象采用唯一临时键，确认后不可覆盖。合并采用短事务领取、事务外 MinIO 组合/下载校验、短事务提交；会话与视频在同一事务完成。合并有 15 分钟领取期限和 executionToken，重启后活动领取只查询，过期领取可由用户再次确认。不同领取使用不同对象键，旧结果不能覆盖新记录。定时清理取消/过期会话及完成会话的临时对象，保留正式视频对象。

Redis 入口覆盖新问答、新转录笔记、新索引及收费流程的人工恢复；已有任务、旧 requestId 重放和只读确认跳过扣减。脚本使用 Redis 时间补充令牌，两个桶都满足才扣减，并保存内容指纹票据。额度按准入尝试计数，数据库失败不无条件退还，同身份重试复用有效票据。Redis 调用位于短准入事务内，连接/命令超时均限制为 1 秒，不包含长时间 AI 工作。

Redis 故障时新收费工作返回 503；额度不足返回 429 和 Retry-After。已有 MySQL 每分钟规则与进程并发保留，尚未验证整个应用的多实例部署。
