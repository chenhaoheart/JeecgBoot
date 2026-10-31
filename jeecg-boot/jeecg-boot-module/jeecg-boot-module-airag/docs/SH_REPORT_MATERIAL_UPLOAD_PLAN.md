# 山洪报告助手「用户上传资料」实施计划

> 状态：**评审已吸收，按此实施**
> 日期：2026-10-13
> 范围：山洪报告助手新增「流域资料上传」能力（Excel 数据表、图片、历史山洪调查等文档），上传资料参与章节生成；Jeecg 其它 AI 应用与现有一张图文件扫描不动。

---

## 1. 背景与目标

当前章节生成的事实数据**只来自 PostgreSQL 调查库**：`ShReportDataPacketService.buildPacket()` 按章节查 `sh_survey_* / sh_map_*` 表，拼成 Markdown 数据包注入流程入参 `sjbao`。库中没有的数据（最典型是**第 5 章历史山洪灾害**，代码里硬写「暂无调查数据，不得虚构」）模型只能写「暂无调查数据」。

新需求：用户按流域上传自己手里的资料——

- **Excel 表**（调查表、统计表、补充数据表）；
- **图片**（现场照片、手绘图纸、文件翻拍件）；
- **文档**（历史山洪调查记录、PDF/Word 报告、政府文件）；

上传后这些资料能被解析、按章节取用，与实测数据包一起支撑报告编写，并可追溯每章引用了哪些资料。

## 2. 做什么、不做什么

**做**

- 流域级资料库：上传、异步解析、自动章节打标、列表管理、删除。
- 三类文件解析：Excel→Markdown 表格；PDF/Word→正文抽取；图片→视觉模型一次性转写文字。
- 生成章节时按章节标签选取资料，拼进流程入参参与写作；第 5 章历史灾害随上传资料解锁。
- 章节级引用来源展示与落库追溯。
- 前端助手页新增「流域资料」面板与对话附件上传入口。
- 一期即提供**登录态预览**（不走 anon 的 `/sys/common/static/**`）。
- 第 5 章把历史类资料填入流程已有入参 `lishijilv`（必要时 `honghen`）；其它章并入 `buochong`。
- 启动时把 7 个流程提示词里的 PACKET_LINE「唯一事实来源」改成与 Java 数据包抬头一致的双源措辞（只改这一句，不改节点/图）。

**不做（一期）**

- 不改 7 个单章流程的节点结构、不新增 `${ziliao}` 入参（二期）。
- 不把图片以多模态消息直接透传给章节流程（`FlowRunParams.inputParams` 是纯文本 Map；图片统一在上传时视觉转写为文字）。
- 不动现有 `sh_file_object` 磁盘扫描索引与一张图照片面板。
- 不做资料向量化检索、不做 Excel 大表 LLM 归纳、不做数字级冲突比对（列为二/三期）。
- 不改通用上传 `/sys/common/upload` 与其它模块。
- 打标一期保持**文件级**（混表靠用户手改标签；不按 Sheet 拆记录）。

## 3. 现状与可复用能力

| 能力 | 现有实现 | 复用方式 |
|---|---|---|
| 数据包注入 | `ShReportConstants.ShReportDataPacketService.buildPacket()` → 入参 `sjbao` | 不动，上传资料走平行通道 |
| 补充资料入参 | `ShReportGenerateService.buildBuochong()`（用户要求+第4章参数+上一稿）→ 入参 `buochong`，7 个流程均已引用 `${buochong}` | **一期把上传资料并入此入参** |
| 文档解析 | `org.jeecg.modules.airag.llm.document.TikaDocumentParser`：PDF/TXT 走 Tika，docx/xlsx/pptx 走 POI（知识库已在用，支持 xlsx/xls/docx/pdf） | 直接注入该 Parser |
| 视觉模型 | `AIChatHandler.imageContentFromBytes(byte[], mime)`、`buildImageContents(List<String>)`；`ShHazardAiRiskService:560/576` 已有磁盘/上传图片判读先例 | 图片上传后视觉转写 |
| 文件落盘 | `CommonUtils.uploadLocal / upload`，按 `jeecg.uploadType=local|minio|alioss` 自动分流 | 上传接口直接调用 |
| 范文来源回传 | `FlowWriteContext.kbSourcesOut` → `ShReportLlmChapterWriter` 按 `batch|chapter` 缓存 → 前端展示 | 照搬实现 `materialSourcesOut` |
| 异步建表风格 | `ShFileObjectService.initTable()` 用 `@EventListener(ApplicationReadyEvent)` 幂等 `CREATE TABLE IF NOT EXISTS`（PG 库） | 资料表照此办理 |
| 章节追溯列 | `ShReportGenerateService.ensureSchema()` 启动时 `ADD COLUMN IF NOT EXISTS`（flow_ver 等） | 同法补 `material_ver` |

## 4. 文件存储

- 走 JeecgBoot 通用上传体系：当前 `application-dev.yml` 为 `uploadType: local`、`jeecg.path.upload=/opt/upFiles`。
- 物理文件：`${jeecg.path.upload}/shreport/material/{lybm}/原文件名_时间戳.ext`
  - Windows 开发机即 `D:\opt\upFiles\shreport\material\{lybm}\...`；Linux 生产即 `/opt/upFiles/...`；切 MinIO/OSS 时 `file_path` 自动存对象 URL，生成链路（视觉转写对 http URL/本地路径都兼容）不受影响。
- **库表只存相对路径/URL**，与 `sh_file_object` 只存相对路径的原则一致，换环境不改数据。
- 与一张图扫描根目录 `shreport.file.root-path`（外部汇集只读数据源）物理隔离，不混用。
- 预览：**一期即用登录态专用接口** `GET /sh/report/material/preview?id=`（登录校验 + 扩展名白名单 + 路径必须落在 `shreport/material/` 下）。不走 Shiro anon 的 `/sys/common/static/**`（调查表/现场照片不可公网直链）。

## 5. 库表设计

PostgreSQL 库（`@DS("shreport")`，与 `sh_report_chapter` 同库），服务启动幂等建表：

```sql
CREATE TABLE IF NOT EXISTS sh_report_material (
  id              BIGSERIAL PRIMARY KEY,
  lybm            TEXT,                     -- 流域编码；未绑定流域时允许为空，绑定后补填
  lymc            TEXT,                     -- 流域名称（冗余展示）
  batch_no        TEXT,                     -- NULL=流域公共资料（所有批次可用）；非空=该批次专用
  file_name       TEXT NOT NULL,           -- 原始文件名
  file_path       TEXT NOT NULL,           -- CommonUtils 返回的相对路径或对象 URL
  file_type       TEXT,                    -- excel / document / image
  ext             TEXT,
  file_size       BIGINT,
  category        TEXT,                    -- 基础数据 / 历史山洪调查 / 现场照片图纸 / 其他
  chapter_nos     TEXT,                    -- 适用章节，逗号分隔，如 "1,3,5"；NULL=自动打标结果
  auto_tagged     INT DEFAULT 0,           -- 章节标签是否为系统自动打标（1=自动，0=用户指定）
  data_override   INT DEFAULT 0,           -- 1=与数据库数字冲突时以本文件为准
  parse_status    TEXT DEFAULT 'pending',  -- pending / parsing / ready / failed
  extracted_text  TEXT,                    -- 解析文本或图片视觉转写文本
  content_summary TEXT,                    -- 100~200 字摘要（选取与前端展示用）
  fail_reason     TEXT,
  create_by       TEXT,
  create_time     TIMESTAMP DEFAULT now(),
  update_time     TIMESTAMP DEFAULT now(),
  deleted         INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sh_report_material_watershed
  ON sh_report_material (lybm, batch_no);
CREATE INDEX IF NOT EXISTS idx_sh_report_material_status
  ON sh_report_material (parse_status);
```

`sh_report_chapter` 启动补列（仿 `ensureSchema()`）：

```sql
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS material_ver text;
COMMENT ON COLUMN sh_report_chapter.material_ver IS '本章引用的上传资料清单(文件名@更新时间，分号分隔)';
```

**归属语义**：资料以**流域为单位沉淀**（同流域以后再生成可复用）；`batch_no` 非空表示「这一批临时补充」。生成时取数条件：

```sql
deleted=0 AND parse_status='ready'
AND COALESCE(lybm,'')=COALESCE(?当前lybm,'')
AND (batch_no IS NULL OR batch_no=?当前batchNo)
```

## 6. 解析与打标

上传接口落盘、落库（`parse_status=pending`）后，提交独立线程池异步解析（仿知识库 `rebuildDocument` 的 `CompletableFuture` 固定池；池大小可配，默认 2），前端轮询列表看状态。单文件失败只置 `failed + fail_reason`，不影响其它文件与生成主流程。

### 6.1 Excel（xlsx/xls）

- POI 逐 Sheet 读取 → 每 Sheet 输出 `### {文件名} / {Sheet名}` + Markdown 表格（表头取首行，空行跳过，单元格换行/竖线转义，与 `ShReportDataPacketService.toMarkdownTable` 格式一致）。
- **大表保护**：单 Sheet 行数 > `shreport.material.excel-max-rows`（默认 200）时，表头 + 前 N 行原样保留，其余行仅保留并追加一行汇总说明（行数、数值列 min/max/合计，能算则算）；单文件抽取总字数上限 `excel-max-chars`（默认 12000），超出截断并标注「内容过长已截断」。
- 数值、年份等单元格按文本保留，不做单位换算。

### 6.2 文档（pdf/doc/docx/txt/md）

- 复用 `TikaDocumentParser`（PDF/TXT 走 Tika，docx/doc 走 POI）。
- 抽出文本按页/段落保留层级标题；总字数上限 `doc-max-chars`（默认 20000）。
- 扫描件 PDF：抽到的正文 ≥ 50 字则仍标 `ready`，并在文本末标注「可能缺 OCR，疑似含扫描页」；完全抽不出文字时才 `failed`，`fail_reason` 提示「疑似扫描件，请上传翻拍图片」。二期再考虑逐页视觉转写。

### 6.3 图片（jpg/jpeg/png/webp/bmp）

- 调 `AIChatHandler.imageContentFromBytes()` 组图，用当前会话绑定模型（未选模型时用 `shreport.agent.model-id` 默认模型；默认模型非视觉模型时降级为只记录「图片未转写：当前模型不支持视觉，请选择视觉模型后点重新解析」）。
- 转写提示词固定为：逐字转写图中文字/表格；并按山洪调查要素结构化描述（河沟名称、地点、时间、灾种、水毁现象、工程措施、可见数据）。输出为 Markdown。
- 单图压缩沿用 handler 内 `compressForVision`，不额外占带宽。
- 重新解析接口：换了视觉模型后可对 failed/ready 的图片重跑。

### 6.4 章节自动打标

解析完成后按文件名 + Sheet 名 + 表头 + 正文关键词路由（纯规则，不额外烧 token），写入 `chapter_nos`、`auto_tagged=1`，用户可手动改：

| 关键词（命中任一） | 章节 |
|---|---|
| 历史灾害、灾情、受灾、洪水年份、洪痕调查 | 5 |
| 桥涵、隐患点、沟道、沟滩、急弯、泥石流、外业 | 3 |
| 洪峰、流量、比降、糙率、壅水、溃决、洪水计算 | 4 |
| 人口、乡镇、社会经济、村庄、面积 | 1,6 |
| 预案、监测、预警、治理、工程措施、建议 | 7 |
| 准备、队伍、物资、内业 | 2 |
| 其余/通用统计表 | 1（仅在数据包缺数时作兜底参考） |

类别 `category` 同步粗分：文件名含「历史/灾害」→ 历史山洪调查；图片 → 现场照片图纸；Excel → 基础数据；其余 → 其他。

### 6.5 摘要

抽取文本前 200 字清洗后存入 `content_summary`（一期不调 LLM 摘要，省 token；三期再做 LLM 摘要）。

## 7. 生成链路接入（一期唯一代码接缝）

新增 `org.jeecg.modules.airag.shreport.material` 包（实体/服务/控制器），核心服务：

```java
public interface ShReportMaterialService {
    // 上传落盘 + 落库 + 提交异步解析，返回资料记录
    Map<String,Object> upload(MultipartFile file, String lybm, String lymc, String batchNo,
                              String category, String chapterNos);
    Map<String,Object> list(String lybm, String batchNo);
    void updateTags(Long id, String category, String chapterNos, Boolean dataOverride);
    void delete(Long id);                  // 逻辑删除；物理文件保留（与系统其它上传一致）
    void reparse(Long id);                 // 图片/失败文件重新解析

    /** 生成期：按章节选取 ready 资料，返回拼好的资料块；为空返回 null */
    String buildChapterMaterials(String batchNo, String lybm, int chapterNo);
    /** 仿 flowVerByChapter：只存不删，persist / saveChapter 取用 */
    String lastMaterialVer(String batchNo, int chapterNo);
    boolean hasHistoryMaterials(String lybm, String batchNo);
    int countUnready(String lybm, String batchNo);
}
```

### 7.1 选取规则

1. 取数范围见第 5 节归属语义；只选 `chapter_nos` 含本章的资料；本章无任何命中时，允许 `category='基础数据'` 的通用表兜底（最多 1 份，预算减半），避免无关长文灌入。
2. 排序：`data_override=1` 优先 → `batch_no` 非空（本批专用）优先 → 历史山洪调查类对第 5 章优先 → 上传时间新者优先。
3. 预算：每章资料块总字数 `shreport.material.chapter-max-chars`（默认 5000），单文件最多截入 `per-file-chars`（默认 2000，Excel 表格例外可放宽到 4000），超出截断并标注；硬上限同时保护提示词不被资料撑爆。有 `prevDraft`（质检回写）时预算减半，避免与上一稿叠爆上下文。
4. 每次选取结果按 `batch|chapter` 写入 `materialVerByChapter`（**只存不删**，与 `flowVerByChapter` 相同；不要做成 consume 即删，否则 persist 拿不到）。

### 7.2 注入位置与文案

改 `ShReportGenerateService.buildChapterFlowInput()`（约 673–704 行）一处：`buildBuochong(...)` 计算后，追加资料块：

```
【用户上传资料（本流域调查人员提供，文件名即出处）】
使用规则：
1. 资料中的事实可以采用，引用具体数字、年份、灾情时必须写明来源文件名，如「据《XX县历史山洪调查记录》…」；
2. 与「实测数据包」冲突时：标注「以此为准」的资料优先采用；否则一律以实测数据包为准，禁止在同一章混用两个数字；
3. 资料中没有、数据包中也没有的内容，仍写「暂无调查数据」，严禁编造。
### 来源：XX县历史山洪调查记录.pdf
……
### 来源：补充调查表.xlsx / Sheet1
……
```

实现上：

- **第 1–4、6–7 章**：把该块 append 进 `buochong`（空 buochong 时新建）。
- **第 5 章**：把选取到的资料正文写入流程已有入参 `lishijilv`（文件名含「洪痕」的另写入 `honghen`）；`buochong` 只追加一句「本章历史灾害资料已注入历史灾害记录入参」，避免 5000 字重复占用。`buildPacket(..., hasHistoryMaterial)` 在有资料时不再输出「历史灾害记录：暂无调查数据」。
- `ShReportDataPacketService.buildPacket()` 抬头改为「结构化事实的主要来源；用户上传资料为补充/修正」。
- 启动时 `REPLACE` 7 个 `airag_flow.design` 中的 PACKET_LINE 旧句（与抬头同一句），避免流程提示词仍写「唯一允许使用的事实数据来源」把资料顶掉。`buildPacket` 增加 `hasHistoryMaterial` 布尔参数，由 `buildChapterFlowInput` 传入，DataPacketService **不**直接依赖 MaterialService。

### 7.3 来源回传与追溯

- `buildChapterFlowInput` 选取资料后写入 `materialVerByChapter`（仿 `flowVerByChapter`）。
- `saveChapter` 的 UPDATE/INSERT **始终**带上 `lastMaterialVer()`；`listChapters` 的 SELECT 增加 `material_ver`。
- persist 节点不必新接缝：它已走 `saveChapter`，会一并落库。
- 前端章节卡片展示「本章引用资料」（从 `materialVer` 解析文件名）。

### 7.4 质检延伸

`ShReportQualityChecker.check(..., hasHistoryMaterial)` 增一条规则（仅警告级，不阻断）：第 5 章出现**具体四位年份**或「死亡/倒塌/受灾\\d+人」这类事件句，但本章无历史灾害类资料时，挂「疑似虚构历史灾害」。不要用「历史|年份|受灾」泛匹配——正确文案「暂无历史灾害调查数据」会误报。图 QC 节点从 MaterialService 取 `hasHistoryMaterials`。

### 7.5 对话问答（一期简化）

`ShReportAgentService.handleChat`：意图识别已有的对话链路不动；仅在 `contextSnapshot` 中增加 `materials`（该流域资料文件名+状态，**不含** `extracted_text`）与 `materialPending`。`startGenerate` 若有 pending/parsing 文件，SSE 提示「N 份资料尚未解析完成，本章只使用已解析资料」，不硬阻断。用户直接问资料内容的 RAG 检索放二期。

## 8. 后端接口

新增 `ShReportMaterialController`，`@RequestMapping("/sh/report/material")`，登录态由 Shiro 默认拦截（与 `/sh/report/agent/**` 同策略）：

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| POST | `/upload` | multipart：file；form：conversationId 或 lybm（+可选 batchNo/lymc/category/chapterNos） | 内部 `CommonUtils.upload(file, "shreport/material/"+lybm, uploadType)`；落库；异步解析；返回记录 |
| GET | `/list` | conversationId 或 lybm，可选 batchNo | 资料列表（状态、摘要、标签、大小、时间） |
| POST | `/update` | JSON：id, category, chapterNos, dataOverride | 改标签/类别/以此为准 |
| POST | `/delete` | JSON：id | 逻辑删除 |
| POST | `/reparse` | JSON：id | 重新解析（图片换视觉模型、失败文件、改了 excel-max-rows 后也可重跑） |
| GET | `/preview` | id | 登录态预览/下载；白名单扩展名；路径约束在 `shreport/material/` |

conversationId → (batchNo, lybm) 的换算复用 `ShReportSessionStore`。未绑定流域时上传直接拒绝（禁止 `material/null/`）。删除/改标签校验记录 `lybm` 与当前会话一致。
白名单扩展名：`xlsx,xls,pdf,doc,docx,txt,md,jpg,jpeg,png,webp,bmp`（`heic` 明确报「暂不支持，请转为 jpg/png」）；单文件大小上限默认 50MB（`shreport.material.max-size-mb`），复用 `SsrfFileTypeFilter` 做文件安全校验。
`list` **不返回** `extracted_text`，只返回摘要。`shreport.material.enabled=false` 时接口 403、生成跳过注入、前端隐藏入口。

## 9. 前端改动

目录 `jeecgboot-vue3/src/views/super/airag/shreport/`：

1. **新增 `components/ShMaterialPanel.vue`**：章节面板顶部增加 Tab「章节审核 | 流域资料」，资料面板放在第二章节栏内，不新开第四栏。
2. 列表项：文件名/大小、类型图标、解析状态、摘要 tooltip、类别下拉、适用章节勾选、「以此为准」、删除、重新解析。预览走登录态接口拉 blob。
3. **`AgentChat.vue`**：输入区加附件按钮（已绑定流域才可用），chip 展示在输入框上方；上传接口即绑定当前流域+batch。
4. **`ChapterPanel.vue`**：章节卡片展示「本章引用资料」。重新生成前若有 pending 资料，`Modal.confirm` 提示后仍可继续。
5. **`useReportSession.ts` / `ShReportAssistant.api.ts`**：material 列表/上传/删除/改标签；切换流域或会话时刷新面板。大纲确认生成时同样提示 pending。

## 10. 配置项（application-dev.yml，全部带默认值，可不配）

```yaml
shreport:
  material:
    enabled: true
    max-size-mb: 50
    parse-thread: 2            # 异步解析线程数
    excel-max-rows: 200        # Sheet 原样保留行数上限
    excel-max-chars: 12000     # 单 Excel 抽取总字数
    doc-max-chars: 20000       # 单文档抽取总字数
    chapter-max-chars: 5000    # 每章注入资料总字数
    per-file-chars: 2000       # 每份资料每章截入字数
    vision-model-id:           # 图片转写专用视觉模型；空=用会话/默认模型
    vision-timeout-seconds: 120
```

`enabled=false`：接口 403、生成不注入、前端隐藏入口。图片转写走 `ShReportConcurrencyLimiter.withLlmPermit`，避免与章节写作抢模型。

## 11. 任务拆解

### 一期（全链路可用，预估 3–5 人日）

1. 建表与补列：`ShReportMaterialService.initTable()`（ApplicationReadyEvent 幂等）+ `ensureSchema` 加 `material_ver`。
2. `ShReportMaterialService`：upload（CommonUtils 落盘）/list/updateTags/delete/reparse。
3. 解析器三件：`MaterialExcelExtractor`（POI→MD）、`MaterialDocExtractor`（TikaDocumentParser）、`MaterialVisionExtractor`（AIChatHandler 转写）；异步线程池编排 + 章节关键词打标。
4. `buildChapterMaterials` 选取/预算/排序 + 改 `buildChapterFlowInput` 注入 + 数据包抬头措辞。
5. `materialVerByChapter` → `saveChapter`/`listChapters` 带 `material_ver`。
6. 第 5 章填 `lishijilv` + 数据包动态句 + PACKET_LINE 启动补丁 + 质检疑似虚构（四位年份/受灾人数，警告级）。
7. `ShReportMaterialController` 六个接口（含 preview）+ 未绑定流域拒绝。
8. 前端 Tab 面板 + 对话附件 + 章节引用 + pending 提示 + api/session 接线。
9. 单测：打标路由、章节预算截断、第 5 章动态包、质检虚构规则、PACKET_LINE 替换。
10. 联调：三类文件各一份走完「上传→解析→生成对应章→引用展示」；第 5 章历史灾害专项验证。

### 二期（2–3 人日）

- 独立流程入参 `${ziliao}`：升级 7 个流程提示词为「数据包 + 上传资料」双源结构（流程设计器改动，需回归所有章节）。
- 资料冲突在质检中显式挂问题（数字级比对）。
- 对话 RAG：用户追问「我传的表里怎么说」时检索上传资料（关键词先行）。
- 扫描件 PDF 逐页视觉转写。

### 三期（可选）

- 上传图片按隐患点 pid 打标，纳入 Word 导出选片（`ShFileObjectService.pickPhoto` / `ShReportCellPhotos` 候选池）。
- 资料向量化（复用 EmbeddingHandler + 现有知识库）替代关键词章节打标，支持跨章语义取用。
- Excel 大表 LLM 归纳、LLM 摘要。

## 12. 验收标准

1. 绑定流域后可上传 xlsx/pdf/docx/jpg，文件落在 `${jeecg.path.upload}/shreport/material/{lybm}/`，列表正确显示解析状态；超大/非法类型被拒并给出明确提示。
2. Excel 解析结果为带 Sheet 名的 Markdown 表格；文档正文可抽取；图片在视觉模型下产出文字转写；失败文件状态为 failed 且原因可读，不阻断其它文件。
3. 章节自动打标正确（历史灾害文件命中第 5 章），用户手动改标签生效。
4. 用带历史灾害资料的流域生成第 5 章：正文引用具体年份/灾情且标注来源文件名；删除全部资料后重新生成，第 5 章回到「暂无调查数据」且不虚构。
5. 冲突策略一期靠提示词：未勾「以此为准」文案写明以数据包为准；勾选后写明资料优先。不作为自动化硬指标（数字级比对放二期）；人工抽查一章即可。
6. 章节面板显示「本章引用资料」，`sh_report_chapter.material_ver` 有值可追溯。
7. 不上传任何资料时，现有全部生成/对话/导出行为与改动前完全一致（回归零影响）。
8. 未登录无法预览资料文件。解析未完成时生成会提示并只使用 ready 资料。
9. MinIO 组合（`uploadType=minio`）部署前各传一类文件验证一次，不列入日常联调必过项。

## 13. 风险与对策

| 风险 | 对策 |
|---|---|
| 资料过长撑爆提示词、烧 token | 章节预算 + 单文件截断双上限，配置可调；选取按标签而非全量灌入 |
| 用户资料数字错误污染报告 | 默认数据包优先，「以此为准」需显式勾选；正文强制标注来源；`material_ver` 可追溯到人/文件 |
| 默认模型不支持视觉导致图片转写失败 | 明确 failed 原因 + 重新解析接口；可配 `vision-model-id` 专用视觉模型 |
| POI/Tika 解析特殊格式异常 | 单文件 try-catch 降级 failed，绝不影响生成主流程（沿用数据包「单小节容错」原则） |
| 一期资料与「唯一事实来源」抬头指令冲突 | Java 抬头 + 启动时 REPLACE 流程 PACKET_LINE；第 5 章走 `lishijilv` |
| 解析未完成就点生成 | SSE/前端提示；只注入 ready；不硬阻断 |
| `/sys/common/static` 公网可下调查资料 | 一期登录态 preview，不走 anon 静态 |
| 质检「历史\|年份」误报正确空章 | 改为四位年份或「受灾N人」事件句 |
| 本地存储迁移/备份丢文件 | 文档明确 `/opt/upFiles` 备份要求；生产建议 MinIO（框架零代码切换） |
| 混表 Excel 文件级打标不准 | 一期靠用户手改章节勾选；三期再按 Sheet 拆 |

---

## 14. 评审确认（已吸收）

1. **第 5 章注入 = A + C**：历史类资料填 `lishijilv`/`honghen`（流程原本就按这两个人参写）；其它章进 `buochong`；PACKET_LINE 改一句双源措辞。
2. **`material_ver` 仿 `flow_ver`**：ConcurrentMap 只存不删，`saveChapter` SQL 带列。
3. **预览一期鉴权**；**list 不回全文**；**未绑定流域拒绝上传**。
4. 打标文件级、关键词多章命中都打上、由预算截断。
5. `enabled` / 视觉转写限流 / 回写预算减半 / HEIC 明确拒绝。
