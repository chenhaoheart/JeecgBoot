# 山洪灾害防御报告 AI 助手 — 功能描述文档

> 版本：V2.0　日期：2026-10-22
> 后端代码：`jeecg-boot-module-airag/org/jeecg/modules/airag/shreport/`（43 个类，约 9600 行）
> 前端代码：`jeecgboot-vue3/src/views/super/airag/shreport/`（41 个文件，约 1.5 万行）

---

## 目录

1. 系统概述
2. 总体架构
3. 报告生成核心引擎
4. 对话式工作台（前端主页面）
5. 章节审核与版本管理
6. 两阶段大纲生成
7. 流域资料（素材）管理
8. 知识库检索（RAG）
9. 数据问答（白名单 SQL）
10. 质量保障体系
11. Word 公文导出
12. GIS 一张图
13. 地图数据管理与知识图谱
14. AI 隐患识别与台账
15. 会话持久化与多端同步
16. 后端 API 接口清单
17. 关键数据表
18. 配置项清单
19. 可靠性设计要点
20. 典型用户操作流程

---

## 1. 系统概述

山洪灾害防御报告 AI 助手是一套面向山洪灾害调查评价业务的**对话式智能报告生成工作台**。系统以大语言模型（LLM）为核心，结合一期调查成果知识库（RAG）、PostgreSQL 空间业务数据、LangGraph 状态图编排与 LiteFlow 流程引擎，把传统需要人工数周完成的《山洪灾害风险隐患调查评价报告》（标准 7 章）压缩到一次对话内自动成稿，并提供素材管理、自动质检、人工审核、版本对比、Word 公文导出、GIS"一张图"选流域一键生成等完整闭环能力。

### 1.1 报告体例（7 章）

| 章号 | 章名 | 对应 LiteFlow 流程 |
|---|---|---|
| 1 | 一、流域概况 | 山洪报告_01流域概况 |
| 2 | 二、前期准备 | 山洪报告_02前期准备 |
| 3 | 三、外业调查成果 | 山洪报告_03外业调查成果 |
| 4 | 四、影响分析 | 山洪报告_04影响分析 |
| 5 | 五、历史山洪灾害情况 | 山洪报告_05历史灾害 |
| 6 | 六、防治区分析 | 山洪报告_06防治区分析 |
| 7 | 七、成果与建议 | 山洪报告_07成果与建议 |

另有"山洪报告_09合并导出"流程用于合并导出。生成模式为**整份生成**（1-6 章并行 + 第 7 章汇总）或**单章生成/重生成**。

### 1.2 核心价值

- **对话即生产**：用户在聊天窗口用自然语言下达"生成 XX 流域整份报告""先出大纲""第 4 章重写"等指令，系统自动解析意图并执行。
- **数据可溯源**：章节写作以"实测数据包"为唯一事实来源，范文只学体例不学数字，关键指标（面积、河长、比降、人口、防治对象数、沟口高程）在生成前锁定、成文后跨章核对。
- **人机协同闭环**：AI 初稿 → 规则质检（BLOCK/HINT）→ 人工逐章审核（通过/驳回带意见）→ 驳回意见回传 AI 重生成 → 版本对比 → 合并导出公文级 Word。
- **多入口联动**：对话工作台、GIS 一张图选流域一键生成、知识图谱数据维护三个入口共享同一套数据与批次。

---

## 2. 总体架构

### 2.1 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Ant Design Vue + Leaflet（天地图 WMTS）+ ECharts + markdown-it（流式渲染 + XSS 消毒） |
| 后端 | JeecgBoot（Spring Boot）+ langgraph4j（StateGraph 状态图编排）+ LiteFlow（单章生成流程引擎）+ langchain4j（模型调用） |
| 数据库 | MySQL（主业务库，airag_* 表）+ PostgreSQL（shreport_map 库，空间/业务表，`@DS("shreport")` 动态数据源路由）+ Redis（会话上下文、批次锁） |
| AI 能力 | 可配置多模型（`airag_model` 表，GLM / 火山方舟 / DeepSeek 等）、pgvector 向量检索、视觉模型（图片资料转写、隐患识别） |

### 2.2 四层结构

- **前端层**：对话工作台（三栏 + 悬浮流水线窗）、GIS 一张图、知识图谱、地图数据管理、AI 隐患台账。
- **接口层**：5 个业务 Controller（agent / chapter / dashboard / graph / material）+ 一张图 map 系列接口，对话走 SSE 流式协议。
- **智能层**：意图解析（规则 + LLM 双路）、LangGraph 状态图（7 节点 + 条件路由）、LiteFlow 单章流程（SQL 数据节点 + LLM 节点 + Java 增强落库节点）、规则质检与跨章一致性、RAG 三级检索。
- **数据层**：MySQL 主库、PG 空间业务库（流域/防治对象/隐患/断面/报告章节/质检/指标/素材/会话）、Redis、pgvector、磁盘/对象存储资料。

---

## 3. 报告生成核心引擎

### 3.1 LangGraph 状态图

```
start → prepare(锁定关键指标/章节置"生成中")
      → write(第1-6章并行撰写 → 每章写完立即落"待审核")
      → qc(规则质检: BLOCK阻断 / HINT提示)
      → [autoRewrite开启时] rewrite(带质检问题重写, ≤maxRetry=2) → write
      → write_ch7(汇总前六章写第7章)
      → consistency(跨章数字一致性核对)
      → persist(占位→"失败"; 正常→"待审核"; 质检问题落表; 第1章抽指标)
      → end
```

- **拓扑单一真源**：`ShReportGraphTopology` 同时供真实 StateGraph 构建与前端 `/graph/schema` 看板输出，"改图必然改图"；拓扑节点缺实现、条件边缺路由函数会在启动期报错。
- **路由逻辑**：取消→persist；autoRewrite 且有 BLOCK 且未达重试上限→rewrite；整份且未写第 7 章→write_ch7；否则 persist。一致性矛盾且允许回写→rewrite。
- **自动重写默认关闭**（`shreport.graph.auto-rewrite=false`）：质检结果只作参考展示，由用户逐章决定编辑或重新生成，避免 token 翻倍与来回拉锯。
- **运行轨迹**：`ShReportGraphTraceStore` 内存环形缓存（每批次最近 30 轮 × 200 事件），供前端轮询与 graph SSE 快照。
- **演示模式**：`/graph/demo?scenario=` 提供三种空跑场景（完整流程/质检打回/只改一章），不消耗大模型，供编排图演示。

### 3.2 单章生成流程（LiteFlow）

每章一个"AI 流程设计"（`airag_flow` 表，name=山洪报告_01~07，status=enable）。`writeViaFlow` 组装入参：

- `sjbao`：**实测数据包**（见 3.3）；
- `tigang`：本章写作提纲（大纲确认产物或内置模板）；
- `buochong`：补充资料（用户要求 + 大纲 note + 第 4 章计算参数 + 上一稿 prevDraft + 按章选取的上传素材）；
- 第 5 章额外 `honghen/lishijilv`（历史灾害素材）；第 7 章额外 ch1~ch6 全文。

运行方式：未选模型跑库存流程 runFlow（streaming + eventCallback 逐 token 推 SSE）；会话选了模型时经 `debugFlow` **内存覆盖**全部 LLM 节点的 modelId（并把节点 timeout 抬到 ≥600s 防"排队中"假象），不复制入库 #M 变体（启动时自动清理旧变体）。编排自愈：设计器把降级节点拖成并行分支（WHEN 无 CATCH）会被识别并还原成 CATCH 兜底。

### 3.3 实测数据包（Data Packet）

章节生成前按流域编码从 PG 调查表（sh_survey_* / sh_map_*）执行固定形状的统计 SQL，输出结构化 Markdown 注入提示词。**原则：数字一律来自数据库；查不到的数据明确写「暂无调查数据」，严禁编造或引用其他流域数字**。每个小节独立容错，单表异常不影响整体。

| 章 | 数据包小节 |
|---|---|
| 1 | 流域基本信息（sh_map_watershed）、防治对象分类统计、防治对象总量、乡镇分布 |
| 2 | 防治对象总量、乡镇分布 |
| 3 | 防治对象分类统计、重点防治对象（按受威胁人口）、总量、风险隐患要素统计、沟道及沟滩占地调查成果、隐患要素点位统计、隐患点位清单（≤30 条）、跨沟桥涵隐患点（带现场照片编码，≤20 条） |
| 4 | 洪水计算参数（设计洪峰流量 Q、糙率 n、比降 S，可配置）、重点防治对象、总量、跨沟道路桥涵坝阻水影响 |
| 5 | 防治对象背景数据；历史灾害记录（有上传资料→引导引用并标明来源文件名；无→明示"暂无调查数据，不得虚构年份/场次/损失"） |
| 6 | 乡镇分布（分区统计基础）、重点防治对象、总量、防治区（防护对象）统计 |
| 7 | 防治对象总量、分类统计、乡镇分布 |

### 3.4 并发与互斥

- **双 Semaphore 闸门**：任务级（`max-running-tasks`，整份/单章全局生效）+ LLM 调用级（`max-llm-calls`，实测 6 路并发稳定可跑满一波 6 章 + 第 7 章），等待上限 `llm-wait-seconds`（默认 600s），ThreadLocal 防同线程嵌套自锁。
- **批次锁**：按"批次+流域" Redis `setIfAbsent` + 随机 token + TTL 4h，解锁校验 token 防误删；Redis 不可用降级本机 ConcurrentHashMap；启动时清锁防重启后死锁。

### 3.5 意图识别与会话调度

用户消息进入 `/chat/send` 后"规则优先、模型兜底"：

1. **规则意图**（正则）：确认流域、查进度、导出、章节生成、大纲、统计等；
2. 未命中时调 LLM 意图解析（temperature=0，JSON 输出），经 `ShReportIntentValidator` 校验（action 必须属 8 枚举、章号 1-7、dataQuery 白名单、scope 仅 bound/all、过滤槽位仅透传合法字段），失败重试 1 次后降级规则；
3. **意图枚举**：GENERATE_ALL（整份生成）/ GENERATE_CHAPTER（单章）/ QUERY_STATUS（查进度）/ EXPORT（导出）/ PREPARE_OUTLINE（出大纲）/ QUERY_DATA（数据统计）/ QUERY_KB（知识库问答）/ CHAT（闲聊）；
4. **流域绑定 `ensureWatershed`**：0 候选→推 choose 事件请用户点选；1 候选自动绑定；多候选推 choose 列表；"你自己选/随便"类表达（PICKER_PATTERN）自动选一个；LCS 相似度推荐兜底。流域检索四级降级：精确→模糊→去口语前后缀→反向包含。

### 3.6 SSE 事件协议（13 类）

| 事件 | 方向 | 含义 |
|---|---|---|
| text / delta | 服务端 | 对话正文 / 逐 token 增量（过滤 `<think>` 标签） |
| refresh | 服务端 | 通知前端刷新章节列表 |
| progress | 服务端 | 章节进度（每章 ⏳/✅/❌ 就地更新） |
| choose | 服务端 | 流域多候选点选列表 |
| watershed | 服务端 | 流域已绑定通知 |
| outline | 服务端 | 两阶段大纲产物（触发确认弹窗） |
| graph | 服务端 | 状态图节点进出事件（驱动流水线悬浮窗） |
| kb_sources | 服务端 | 知识库命中来源（文档名 + 相似度） |
| usage | 服务端 | token 用量累计快照（按场景/章节/模型分桶） |
| download | 服务端 | 触发浏览器下载 Word（`batchNo\|lybm\|chapterNos`） |
| error / done | 服务端 | 错误 / 轮次结束（done:gone 表示轮次不存在） |

基建：15s comment 心跳防代理断流；`requestId` 断线重连（`/receive/{requestId}` 补发缓存事件后继续实时推送）；TurnRecorder 服务端按前端气泡拼接规则还原正文并在 done 时落库。

---

## 4. 对话式工作台（前端主页面）

三栏布局 + 悬浮流水线窗（`ShReportAssistant.vue`）：

- **顶部工具栏**：品牌区、7 格迷你章节进度（实时"已通过 x/7"，点击切章节视图）、视图切换 Segmented（三栏/对话/章节）、流水线开关、导出 Word 主按钮。
- **左栏 · 会话侧栏**：新建会话、会话/已归档两个 Tab（带计数）、归档/取消归档/彻底删除（强确认）、强制同步（服务端权威对账）、清除本机缓存。
- **中栏 · 对话区**：
  - 欢迎页 + 4 张快捷指令卡（生成整份报告/先出大纲/查看进度/查流域数据，已绑定流域时自动带流域名）；
  - 消息流：Markdown 流式渲染（100ms 节流 + 闪烁光标）、知识库来源展示、流域多候选点选按钮（点击自动发送"确认使用流域：名称|编码"）、章节进度状态行；
  - 滚动管理：回顶/回底悬浮箭头，用户上翻时暂停自动跟随；
  - 输入区：Shift+Enter 发送、**Token 用量弹层**（总量/调用次数/输入输出比例条/按场景按章明细/最近模型）、**会话级模型选择下拉**、资料上传回形针（需先绑定流域）、发送/停止二合一按钮；
  - 前端看门狗：8 秒空闲监测 + 连续 24 次空闲（约 3.2 分钟）自动断开并提示"后台仍在生成"；buffer 中 `event:done` 兜底解析防代理吞尾包。
- **右栏 · 章节面板**：章节审核 + 流域资料两个 Tab。
- **悬浮流水线窗（GraphFloat）**：Teleport 到 body，可拖拽/八向缩放/可收起；生成时自动弹出（graph 事件驱动，用户手动关闭后本轮不再弹出）；多实例领导选举防 keep-alive 重复挂载；内嵌紧凑版 GraphBoard。
- **流水线看板（GraphBoard）**：SVG 自绘 9 节点编排图（与后端拓扑同源），节点状态（灰=未走到/蓝闪=正在做带 LED 呼吸灯/绿=完成/红=失败）、流动虚线数据流、条件边虚线、正交折线自动布线；顶部状态条 + 7 个章节小灯；底部图例、节点说明、"刚才走过的步骤"时间线。

### 4.1 一张图联动生成

监听路由 query `?action=generate&lybm=&lymc=`：确保会话存在 → 绑定流域 → 清空 query → 自动发送"帮我生成 XX 的整份报告"，走标准对话/SSE 生成链路。

---

## 5. 章节审核与版本管理

- **进度总览条**：7 段彩色分段条（按状态着色）+ 各状态计数 + 已通过标识 + 刷新 + 导出 Word。
- **空态**：7 章流水线占位 chip、刷新按钮、**单独查询表单**（批次号 + 流域编码，可不依赖会话绑定直接查库）。
- **章节卡片**（可展开）：章号章名、质检徽标（BLOCK 红/HINT 黄，tooltip 前 3 条）、状态标签（待审核黄/已通过绿/已驳回橙红/生成中蓝/失败红）、更新时间、生成耗时（如"3分12秒"）；展开显示失败原因、审核意见、质检问题列表、引用资料清单（material_ver 解析）、Markdown 正文。
- **操作**：
  - **通过/驳回**：驳回必填意见，意见作为重新生成的修改要求回传 AI；
  - **重新生成**：弹窗填补充要求；先检查是否有资料未解析完（弹确认）；异步接口后 5 秒轮询直到章节脱离"生成中"，最长 10 分钟；
  - **在线编辑**：1100px 弹窗，左编辑右实时预览，保存后状态回"待审核"；
  - **对比上一版**：行级 LCS diff（`versionDiff.ts`），+/- 着色，忽略空白判断实质差异；
  - **单章导出 Word**。
- **导出选择弹窗**：勾选可导出章节（有正文且非生成中），支持全选。
- **轮询兜底**：存在"生成中"章节或会话 generating 时 8s 轮询章节（上限 30 分钟，落库仍在推进则续期），防 SSE 断线后卡片永远"生成中"。

---

## 6. 两阶段大纲生成

1. 用户发"先出大纲" → `ShReportOutlineService` 产出 7 章标准提纲（内置模板含小节 + 应有表格；可用大模型按流域名 + 用户要求润色，temperature=0，normalize 校验必须 7 章、章节号一致、sections 非空，失败一律回退内置模板）；
2. SSE `outline` 事件 → 前端弹 900px"报告大纲确认"窗：每章可勾选小节（两列 checkbox）、查看应有表格提示、填写"本章额外要求"；深拷贝编辑不污染父数据；
3. 确认后过滤掉"无小节且无备注"的章，备注合并进写作要求（`collectNotes`），调 `/outline/confirm` 放行暂存的 pendingAction，复用对话 SSE 链路继续生成。

---

## 7. 流域资料（素材）管理

### 7.1 全生命周期

**上传**（扩展名白名单 xlsx/xls/pdf/doc/docx/txt/md/jpg/jpeg/png/webp/bmp，≤50MB，SSRF/路径穿越校验，落盘 `shreport/material/{lybm}` 或对象存储）→ PG 表落库（pending）→ **解析池异步解析**（parsing→ready/失败记 fail_reason）→ 自动章节打标 → 生成时按章选取注入。

### 7.2 解析器

- **Excel**（POI）：转 Markdown 表格，每 sheet 前 200 行，其余行给数值列 min/max/合计摘要，总字符 12000 截断，单元格转义 `|` 与换行；
- **PDF/Word/TXT**（Tika）：maxChars 截断；`likelyScan`（文件 >20KB 但抽出字 <50）判扫描件 → 提示"请上传翻拍图片"；
- **图片**：视觉模型转写（`vision-model-id` 可配，走 LLM 并发额度，120s 超时）。

### 7.3 自动章节打标（纯规则，不调 LLM）

按"文件名+正文"关键词路由：历史灾害/洪痕→5；桥涵/沟道/急弯/泥石流→3；洪峰/比降/壅水→4；人口/乡镇/面积→1,6；预案/监测/治理→7；准备/队伍/内业→2；无命中默认 1。类别推断：文件名含历史/灾害/洪痕→"历史山洪调查"，图片→"现场照片图纸"，Excel→"基础数据"，其他→"其他"。用户已指定 chapterNos/category 时不覆盖（auto_tagged=0）。

### 7.4 生成注入与溯源

按章选取 ready 资料：章节标签命中优先，"基础数据"类兜底 1 份，"以此为准"排序最前；字符预算 chapterMaxChars=5000/章（修订重写减半），单文件 2000 字截断；拼"### 来源：文件名（以此为准）"块注入 buochong/lishijilv 入参。`material_ver`（文件名@更新时间）随章节落库，章节卡片可查看"引用资料清单"。

### 7.5 前端面板（ShMaterialPanel）

上传（多选）、刷新、资料卡片（文件名/大小/解析状态/失败原因/内容摘要）；可编辑**资料类别**（基础数据/历史山洪调查/现场照片图纸/其他）、**关联章节 1-7 勾选**、**"以此为准"**；操作：预览（Blob 新窗口）、重新解析、逻辑删除。每 2.5 秒轮询直到无 pending/parsing。

---

## 8. 知识库检索（RAG）

一期调查成果知识库（默认 knowId `2099990000000000101`，由 `ai_flows/import_sh_survey_kb.py` 按"流域 · 第 X 章 标题"切片导入 MySQL airag_knowledge / airag_knowledge_doc）。**三级检索降级**：

1. **章节标题精确**：整篇取范文（排除本流域，"（续N）"排后，分页只排 id/title 防 MySQL sort_buffer 溢出）；
2. **向量检索**：pgvector `searchEmbedding`（topNumber=4、similarity=0.45）；
3. **关键词 LIKE**：文档表关键词兜底。

三种用途：

- **写作范文**：带"只学习体例、数字不得照抄"约束头注入章节流程；
- **对话问答**（QUERY_KB）：拼接最近 2 轮用户消息解决指代，流式回答 + kb_sources 事件展示来源；
- **聊天增强**：未命中时给出可查流域名单 + 示例问法引导。

---

## 9. 数据问答（白名单 SQL）

统计类问题（QUERY_DATA）只允许执行预定义固定 SQL（防注入、防模型编数字），9 类：

| 类别 | 内容 |
|---|---|
| overview | 全域总览统计 |
| by_type / by_county / by_town | 按类型 / 县 / 乡镇分组统计 |
| risk | 风险隐患统计 |
| culvert / gully | 桥涵 / 沟滩专题 |
| list_objects / list_watersheds | 防治对象 / 流域清单 |

过滤器（县区/乡镇/类型/关键词/绑定流域）全部参数化；结果转 GFM Markdown 表格（单元格转义竖线换行）；空结果明确"暂无匹配数据"。

---

## 10. 质量保障体系

### 10.1 三道防线

1. **生成前指标锁定**（`sh_report_metric`）：OBJECT_COUNT / POPULATION 从数据包锁定（source=DATA，幂等覆盖）；第 1 章成文后正则抽取 AREA / RIVER_LENGTH / SLOPE / ELEVATION（source=CH1，仅缺省填充"宁漏勿滥"）。
2. **单章规则质检**（`ShReportQualityChecker`，纯规则不依赖 LLM 可单测）：

| 级别 | 类型 | 规则 |
|---|---|---|
| BLOCK | PLACEHOLDER | 系统占位/占位符（TBD、（待补充）、{{}}、XX流域等） |
| BLOCK | SHORT | 篇幅 < 200 字（`shreport.qc.min-length` 可配） |
| BLOCK | TABLE | Markdown 表格结构崩坏 |
| BLOCK | — | 提示词泄漏（"实测数据包"等混入正文） |
| HINT | REQUIRED_TABLE | 缺应有表格（1/3/4/6 章，`table-chapters` 可配） |
| HINT | NUMBER | 百分数 >100%、关键计量负值 |
| HINT | WATERSHED | 未提及目标流域（串流域风险） |
| HINT | STYLE | 缺体例关键词（各章 `chapter-keywords` JSON 可配） |
| HINT | MATERIAL | 第 5 章疑似虚构历史灾害（有年份/受灾人数但无上传资料） |

3. **跨章一致性**（`ShReportConsistencyChecker`）：各章正文正则抽取面积/河长/比降/沟口高程/受威胁人口/防治对象数，与 metric 锁定值比对（容差 2%；人口/对象数为汇总型指标，分项拆解值 ≤ 总数不算矛盾，只有超过总数才判矛盾）；无锁定值时以出现最早章为基准。产出 CONSISTENCY 类 HINT，`chapterNos` 结构化标注需回写章号，统一挂在第 7 章落库（先清后写）。

### 10.2 呈现与闭环

问题落 `sh_report_qc_issue` 表（level/type/message/chapter_no）；章节卡片徽标 + 展开明细；BLOCK 在 autoRewrite 开启时触发自动重写（≤maxRetry=2，把问题清单写进 requirement"请重新完整撰写并逐项改正"），默认关闭时由人工处理。

---

## 11. Word 公文导出

`ReportWordBuilder`（POI XWPF）Markdown → 公文风格 docx：

- 标题"方正小标宋简体"22 号居中；目录页（TOC 域 + settings.xml updateFields 打开自动刷新）；
- `#`/`##`/`###` → 黑体 16/14/12 带大纲级别（自动进目录）；正文仿宋_GB2312 小四、首行缩进、28 磅行距、`**` 加粗；
- Markdown 表格转 XWPFTable（黑体表头/宋体正文/全边框）；
- 表格单元格`【照片：编码】`经 CellPhotoResolver 嵌入隐患点照片（等比缩至 120×90px，同一次导出内同编码只查一次；取不到写"暂无"）；含照片的表整表放独立 A4 横版页（节结束符切换方向）；
- 合并导出自动跳过空内容/生成中章节并按章号排序，文件名形如 `山洪灾害风险隐患调查评价报告_{流域}_第X章_{批次}.docx`；
- 三条触发路径：工具栏/章节面板勾选导出、对话 SSE `download` 事件、章节卡片单章导出。

---

## 12. GIS 一张图

全屏 Leaflet + 天地图 WMTS（影像 img/矢量 vec/地形 ter 三套底图 + 注记层，key 来自后端 `/map/config` 兜底内置）：

- **四个业务图层**：
  1. 小流域（面）：蓝色描边淡填充，选中橙色高亮，tooltip 名称+面积；
  2. 断面（线）：横断面红色实线、纵断面蓝色虚线；选中流域按流域取，未选中按视野 bbox 取（moveend/zoomend 300ms 防抖），避免一次拉全图 7000+ 条；
  3. 防治对象（面/点）：10 类着色（村落蓝/企业绿/集镇橙/景区青/城镇红等），点击 popup（类型/河流/防护对象/小流域/人口/编码 + 前 3 张照片缩略图 + "查看详情"）；
  4. 隐患要素（点）：16 类着色（跨沟道路/桥涵/塘堰坝/沟滩占地/滑坡/泥石流/溃决/壅水等），同样 popup。
- **筛选**：政区（县→乡镇树）/流域（卡片墙：名称+对象数）双 Tab；图层控制支持 4 层开关 + 按类型图例逐项勾选过滤 + 面板折叠/拖拽调高。
- **右侧风险列表**：防治对象/隐患双 Tab，搜索（名称/河流/流域）+ 隐患类型多选，分页卡片，点击 → 地图 flyTo + 高亮 + 要素弹窗。
- **断面剖面**：640px 抽屉，ECharts 折线（横断面地面高程含糙率/特征点 tooltip；纵断面河底高程 + 水面高程双线）。
- **要素详情抽屉**（860px）：图层信息 Descriptions（标签取自 comment_map）、AI 识别风险隐患（隐患 Tab）、现场照片/视频画廊（磁盘索引按编码自动关联 + 人工上传列）、调查成果（按数据集列配置完整展示）。
- **一键生成报告**：顶部工具条（含流域/断面/防治/隐患统计）→"生成报告"→ 确认框（流域名/编码/防治对象数/受威胁人口）→ 跳工作台自动绑定并开跑。

---

## 13. 地图数据管理与知识图谱

### 13.1 数据管理（ShMapDataAdmin）

左侧数据集导航 + 右侧视图：

- **数据图层**：watershed（面）/ section（线）/ prevention（面）/ hazard（点）四个空间数据集；
- **调查成果**：survey_object（防治对象名录）/ survey_road（跨沟道路）/ survey_gully（沟滩占地）；
- 表格视图：年份筛选（含"未标注"）、县/乡镇/流域三级筛选（乡镇按县代码前缀级联）、任意字段搜索、可拖列宽（按数据集记忆）、行选择批量删除；
- 编辑弹窗：按数据集列配置动态表单；空间数据集内嵌**几何编辑器**（点拖动/双击放置，线/面顶点拖动、中点插入、双击删点/空白追加，多部件/带内环几何 skeleton 原样拼回）；非空间含经纬度列用**选点小地图**（防治对象名录还按 fcd 叠加关联防治对象面蓝框）；有媒体编码展示只读画廊；
- **Excel 导入**：年份必填、追加/覆盖两种模式、表头支持列名或中文名；提示"导入不含空间几何，不上地图"。

### 13.2 文件管理（ShFilePanel）

磁盘照片/视频索引：类别/上下游/关联状态/流域筛选 + 文件名/编码搜索；"扫描索引"（扫描磁盘目录入库，报告总数/新增/更新/文件夹名匹配编码统计）；表格带缩略图；全屏浮层预览（上下张、滚轮缩放、拖拽平移、双击放大）；绑定/改绑隐患点编码。

### 13.3 知识图谱（ShKnowledgeGraph）

- ECharts force 布局，九类节点着色：小流域/河流/县/乡镇/防治对象/断面/隐患/跨沟道路/沟滩占地；
- 三种模式：overview 关系总览（类型级聚合）、focus 流域展开、search 关键字检索；
- 点击节点 → 右侧信息栏：类别/名称/数量/属性表、"展开这个小流域"/"打开 XX 表"/"编辑"操作、"和谁有关"自然语言关系列表（如"本小流域 威胁 防治对象「某村」，共 3 条"，动词润色），点击关系跳对端节点；
- 与数据维护联动编辑，图谱数据变更经 kgRevision 联动刷新。

### 13.4 图谱关系维护（ShKgRelationAdmin）

- **从业务数据初始化/重新初始化**（Modal 确认；派生关系重建、停用保持停用、手工保留、删掉的派生会复活）；状态行（初始化时间、总数/派生/手工/停用）；
- 规则卡片墙（每种关系一个 chip 点击过滤）；关系表格（关系/起点/终点/小流域/来源/启用状态/年份），停用/启用/删除；
- **手工添加**：选用已有关系（下拉规则）或新增一种关系（自定义名称 + 起点/终点类型九选）。

---

## 14. AI 隐患识别与台账

- 一张图隐患详情"开始识别"：`POST /map/hazards/{id}/ai-risk`（3 分钟超时），视觉模型结合现场照片 + 外业备注输出风险分析 Markdown；**结果不回写调查数据**；元信息行显示参考照片数/是否含备注/模型名/已记台账；识别期间禁止关闭抽屉；
- **降级**：无照片时仅文字识别并打"仅文字"降级标记；
- **台账**（`sh_hazard_ai_risk_log`）：时间/操作人/隐患点/编码/类型/流域/成败/降级标记/照片数/耗时；搜索 + 状态过滤；"查看"以 code/presetText 模式回看当时识别结果；可独立成页也可内嵌数据管理页。

---

## 15. 会话持久化与多端同步

- **运行时上下文**（批次/流域/大纲/pendingAction/取消标记/runId/usage/最近 20 条历史）：Redis（`shreport:conv:{id}`，TTL 7 天，进程内 Map 降级）；
- **会话列表与消息**：PG 表 `sh_report_agent_session`（跨浏览器恢复）：selectForUpdate 行锁 + upsert；消息经 `ShReportSessionMessageMerge` 按条并集合并（稳定 id=djb2+base36，与前端 `stableMsgId` 算法一致；内容指纹折叠同消息双份；陈旧窗口快照只能并集补消息不能回滚元数据）；
- **删除墓碑**（`shreport:del:{user}:{conv}`）：先打墓碑再删库，正在跑的轮次结束也不会 upsert 回来，保存接口对已删会话拒绝写回；
- **前端三层机制**：localStorage（按登录名隔离 key，旧 userId key 自动迁移）→ 服务端权威对账（防抖 2s 推送 + syncedTs 水印 + 在途去重 + 指数退避重试 ≤3 次）→ 跨标签页 storage 事件即时合并 + 30s（前台）/120s（后台）定时合并 + focus/可见性变化拉取；
- **合并算法**：只增不减并集；messageKey 按 id 优先 → 进度行按章节号 → role+前 200 字指纹兜底；取时间新、内容长的一侧；清理 `__streaming` 标记；
- **账号切换自愈**：监听登录人变化，清空全部状态重新 init，防跨账号数据泄露。

---

## 16. 后端 API 接口清单

### 16.1 AI 助手对话 `/sh/report/agent`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/sessions` | 会话列表（跨浏览器恢复，含消息） |
| POST | `/sessions/save` | 幂等保存会话（删除墓碑拒绝写回） |
| POST | `/sessions/delete` | 删除会话（先打墓碑再删库） |
| GET | `/models` | 已激活模型列表（id/name/provider/modelName，不含密钥） |
| POST | `/conversation` | 新建会话（UUID conversationId + "B"+时间戳批次号） |
| GET | `/context` | 会话上下文快照（batchNo/lybm/lymc/modelId/usage/materials） |
| POST | `/bindWatershed` | 绑定流域 |
| POST | `/bindModel` | 绑定模型（空串恢复系统默认；未激活模型报错） |
| POST | `/cancel` | 打断 SSE/TokenStream，生成中章节置失败 |
| POST | `/outline/confirm` | 大纲确认后放行 pendingAction（SSE，1800s 超时） |
| GET | `/receive/{requestId}` | 断线重连：补发缓存事件后继续推送（SSE） |
| POST | `/chat/send` | 主对话入口（SSE） |

### 16.2 章节管理 `/sh/report/chapter`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/watersheds` | 流域列表 + 每流域已生成/已通过章节数（keyword 检索或全量合并进度） |
| GET | `/list` | 章节列表（content_prev/fail_reason/flow_ver/material_ver/gen_cost_ms） |
| GET | `/qc/issues` | 质检问题列表（BLOCK/HINT，含跨章一致性） |
| POST | `/save` | 人工编辑保存（状态回"待审核"） |
| POST | `/audit` | 审核（status 仅允许 已通过/已驳回） |
| POST | `/regenerate` | 单章重生成（批次锁→单线程排队→并发闸门等 10 分钟→生成；驳回意见自动带出；异常兜底置失败） |
| POST | `/generateAll` | 批量整份生成（每流域一任务，1-6 章并行 + 第 7 章汇总） |
| GET | `/download` | 合并导出 Word（docx 流，嵌照片，跳过空/生成中章节） |

### 16.3 流域资料 `/sh/report/material`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/upload` | 上传资料（multipart，落盘后异步解析） |
| GET | `/list` | 资料列表（不含解析全文） |
| POST | `/update` | 修改类别/章节标签/"以此为准" |
| POST | `/delete` | 逻辑删除（deleted=1） |
| POST | `/reparse` | 重新解析（回 pending 再入解析池） |
| GET | `/preview` | 登录态预览/下载（inline + 正确 MediaType） |

### 16.4 编排图 `/sh/report/graph`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/schema` | 图拓扑（节点/边/坐标/文案，与真实 StateGraph 同源） |
| GET | `/trace` | 某批次最近一次图运行轨迹 |
| GET | `/demo?scenario=` | 演示空跑（完整流程/质检打回/只改一章，不耗模型） |

### 16.5 首页概览 `/sh/report/dashboard`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/overview` | AI 概览：report（完整报告数=7 章齐的流域数、章节总数、状态分布、最近批次）、knowledge、map（一张图要素统计）、aiRisk（识别成败统计） |

### 16.6 一张图与数据管理 `/sh/report/map/**`（节选）

- `/map/config` `/map/filters` `/map/geojson` `/map/stats`：天地图 key 与默认视野 / 流域县乡选项 / 四层 GeoJSON / 要素统计；
- `/map/prevention` `/map/hazards` `/map/hazard` `/map/section` `/map/section/profile`：要素列表详情、断面剖面；
- `POST /map/hazards/{id}/ai-risk`、`/map/ai-risk/logs`、`/logs/{id}`：AI 隐患识别与台账；
- `/map/admin/datasets|years|facets|list|row/update|row/delete|import|geom|geom/update|geom/by-pid|row`：数据集管理；
- `/map/admin/kg`、`kg/rel/status|list|init|save|enabled|delete`：知识图谱与关系维护；
- `/map/file/scan|list|facets|by-pid|bind|preview`：文件索引管理。

---

## 17. 关键数据表

| 表 | 库 | 说明 |
|---|---|---|
| `sh_report_chapter` | PG | 章节正文（content/content_prev 上一版/fail_reason/flow_ver/material_ver/gen_cost_ms/状态：待审核·已通过·已驳回·生成中·失败） |
| `sh_report_qc_issue` | PG | 质检问题（level/type/message/chapter_no；跨章一致性挂第 7 章） |
| `sh_report_metric` | PG | 关键指标锁定值（metric_key：OBJECT_COUNT/POPULATION/AREA/RIVER_LENGTH/SLOPE/ELEVATION；source=DATA/CH1） |
| `sh_report_material` | PG | 流域资料（解析状态/章节标签/类别/dataOverride/auto_tagged/extracted_text/content_summary/fail_reason/material_ver） |
| `sh_report_agent_session` | PG | 会话（userId+conversationId 联合唯一 upsert；messages JSON；title/archived/updateTime 毫秒戳） |
| `sh_hazard_ai_risk_log` | PG | AI 隐患识别台账 |
| `sh_map_watershed` / `sh_survey_*` | PG | 流域（wscd/wsnm/alias_name）与调查成果空间业务数据（ShReportPgMapper 28 个查询方法） |
| `airag_model` / `airag_flow` / `airag_knowledge(_doc)` | MySQL | 模型配置 / AI 流程（山洪报告_01~07、_09）/ 知识库 |

---

## 18. 配置项清单（application yml `shreport.*`）

| 配置 | 默认/示例 | 说明 |
|---|---|---|
| `agent.model-id` | 火山方舟 agent id | 默认模型；会话内选择 > 本配置 > 系统默认 |
| `knowledge.enabled / id / top-number / similarity` | false / 2099990000000000101 / 3 / 0.45 | 知识库开关与检索参数 |
| `graph.max-retry / auto-rewrite / purge-flow-variants` | 2 / false / true | 自动重写上限与开关、启动清理 #M 流程变体 |
| `qc.min-length / table-chapters / chapter-keywords` | 200 / '1,3,4,6' / JSON | 质检规则（体例调整改配置即可，无需改代码） |
| `concurrency.max-running-tasks / max-llm-calls / llm-wait-seconds` | 100 / 9 / 600 | 并发闸门（按模型侧实测稳定性调整） |
| `map.tianditu-key / center-lat / center-lng / zoom` | 开发 key / 35.6 / 104.2 / 4 | 天地图 WMTS 与默认视野 |
| `file.root-path / allowed-ext` | 照片根目录 / jpg…mp4 | 一张图照片根目录（库表只存相对路径，换环境只改配置） |
| `material.enabled / max-size-mb / parse-thread / excel-max-rows / excel-max-chars / doc-max-chars / chapter-max-chars / per-file-chars / vision-model-id / vision-timeout-seconds` | true / 50 / 2 / 200 / 12000 / 20000 / 5000 / 2000 / '' / 120 | 素材全链路参数 |

---

## 19. 可靠性设计要点

1. **并发与互斥**：任务/LLM 双 Semaphore 闸门 + Redis 批次锁（随机 token 防误删，启动清锁防死锁）；
2. **启动自愈**：残留"生成中"章节置失败、清理批次锁、LiteFlow 编排体检修复（降级分支还原 CATCH 兜底）、PACKET_LINE 措辞补丁、清理旧 #M 流程变体；
3. **异常兜底**：生成异常章节置"失败"并记 fail_reason，绝不永远"生成中"；占位文案统一口径（`ShReportTextChecker` 前后端共用判定）；意图解析失败重试后降级规则；
4. **断线容灾**：SSE 15s 心跳、requestId 重连补发、前端 8s 看门狗 + 章节轮询双保险；
5. **数据安全**：资料上传白名单 + 路径穿越/SSRF 校验、数据问答 SQL 白名单参数化、归属校验防跨流域操作、模型列表不返回密钥、动态数据源 `@DS("shreport")` 类级标注保证先切库再开事务；
6. **版本保护**：章节重写时旧内容挡入 content_prev，persist 强制 preservePrevVersion 保护已审核版本，`trimToChapter` 按 Markdown 章节标题裁剪越界输出，行级 diff 可对比。

---

## 20. 典型用户操作流程

1. **新建会话**：进入页面自动恢复本地 + 服务端会话（无则新建，插入欢迎语）；
2. **绑定流域**：对话中说"帮我生成 XX 流域的报告"（多候选时点选按钮确认），或在一张图点选流域一键跳转；
3. **上传素材**：回形针或"流域资料"Tab 上传 Excel/PDF/Word/图片，异步解析，可设类别/关联章节/"以此为准"；
4. **（可选）生成大纲**："先出一份大纲"→ 确认弹窗勾小节、补要求 → 确认放行；
5. **生成章节**：流水线自动跑（prepare→write→qc→write_ch7→consistency→persist），对话进度行、悬浮流水线窗、章节卡片三处实时联动；可随时停止；刷新/断线凭 requestId 重连；
6. **检查与修订**：查看质检徽标与明细 → 在线编辑 / 带意见重新生成 / 对比上一版；
7. **逐章审核**：通过 / 驳回（驳回意见回传 AI）；工具栏实时"已通过 x/7"；
8. **导出 Word**：勾选章节 → 下载公文级 docx；
9. **会话治理**：归档/删除/多端同步/清除本机缓存。
