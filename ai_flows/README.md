# 山洪灾害风险隐患调查报告自动生成 — AI 流程交付说明

## 概述

在 JeecgBoot AI 流程设计（`/process/list/airag`）中创建 **8 个流程**（7 个章节子流程 + 1 个总调度流程），
替代原 Python 写死模板的报告生成。流程内建 **异常降级**（模型失败不阻塞）与 **章节审核/单章重生成/Word 合并** 能力。

## 流程清单

| 流程名 | 结构 | 说明 |
|---|---|---|
| 山洪报告_01流域概况 | start→sql×3→llm→end | 数据+AI（表1-1~1-3） |
| 山洪报告_02前期准备 | start→sql→llm→end | 数据+AI（表2-1） |
| 山洪报告_03外业调查成果 | start→sql×3→llm→end | 数据+AI（表3-1~3-3，R1分级，照片占位） |
| 山洪报告_04影响分析 | start→sql×2→llm→end | SQL内计算（壅水/溃决公式）+AI（表4-1） |
| 山洪报告_05历史灾害 | start→llm→end | 纯AI（历史资料从输入参数传入） |
| 山洪报告_06防治区分析 | start→sql×2→llm→end | 数据+AI（表6-1/6-2） |
| 山洪报告_07成果与建议 | start→llm→end | 纯AI（汇总前六章，输入 ch1~ch6） |
| 山洪灾害风险隐患调查报告自动生成 | start→subflow×7→end | 总调度，每个 subflow 包 CATCH 兜底 |

## 两项核心机制（本次新增）

### 1. 异常降级（模型失败/未配置不阻塞）

- 每个章节子流程的 LLM 节点外包 **LiteFlow CATCH**：`CATCH(THEN(llm,end)).DO(end-fb)`。
  LLM 调用失败（含模型未配置）时自动切换到 `end-fb` 节点，输出该章**系统默认内容**（章节框架+待补充提示），流程正常完成。
- 总调度流程对每个 subflow 再包一层 `CATCH(subflow).DO(fbX)`：单章整体异常时输出该章默认占位文案（SQL 常量节点），
  **保证 7 章全部产出、总流程 success**。已实测：模型不可用时全链跑通并输出 7 段默认内容。
- 注意：SQL 兜底节点的输出是 `[{"result":"文案"}]` 的 JSON 包装形式（引擎 sql 节点固定按 body 键输出行数组），
  仅出现在模型不可用的降级场景；配置模型后走正常路径输出纯 Markdown。

### 2. 章节审核校对 → 单章重新生成 → 合并 Word

新增后端代码（`jeecg-boot-module-airag` 模块 `org.jeecg.modules.airag.shreport` 包）：

- **表 `sh_report_chapter`**：批次号/章节号/章节名/内容/状态(待审核·已通过·已驳回)/审核意见
  （flyway：`V3.9.5_1__sh_report_chapter.sql`，已直接建表）。
- **`ShReportChapterController`**（`/sh/report/chapter/*`）：
  | 接口 | 说明 |
  |---|---|
  | POST `/save` | {batchNo, chapterNo, content} 保存章节为待审核 |
  | GET `/list?batchNo=` | 章节列表（内容+状态+审核意见） |
  | POST `/audit` | {id, status:已通过/已驳回, auditNote} 审核校对 |
  | POST `/regenerate` | {batchNo, chapterNo, auditNote?} **单章重新生成**（异步），审核意见自动注入提示词"补充资料"，完成后重置待审核；生成失败自动写入默认提示不阻塞 |
  | GET `/download?batchNo=` | 全部章节"已通过"后合并导出 **Word**（任一章节未通过则拒绝并提示） |
- **`ReportWordBuilder`**：Markdown→docx 公文排版（方正小标宋标题/黑体章节/仿宋正文/宋体表格），与 HTML 预览样式一致。
- 典型闭环：生成(batch_no=B001) → list 审核 → 驳回填意见 → regenerate(自动带意见重生成) → 全部通过 → download 得到完整 Word。

**需要重启后端生效**（JeecgSystemApplication，新增了 Controller 与表）。表已提前建好，重启即用。

## 使用步骤

1. **配置 LLM Key**：AI 模型管理中给 **火山方舟-agent**（`ark-code-latest`）填真实 API Key，并保持激活、设为默认。
2. **生成**：设计器里给 7 个子流程的 LLM 节点选模型 → 运行总调度流程，输入 `batch_no=B001`（不填自动生成）。
3. **审核**：调 `/sh/report/chapter/list?batchNo=B001` 逐章校对 → `/audit` 通过或驳回。
4. **重生成**：驳回的章节 `/regenerate`（自动带审核意见重新生成该章，不影响其他章节）。
5. **导出**：全部通过后 `/download?batchNo=B001` 得到完整 Word。

## 数据与配置

- 调查数据在 PostgreSQL `shreport_map`（`sh_survey_object` / `sh_survey_road` / `sh_survey_gully` / `sh_map_watershed` 等）。
- 流程 SQL 节点使用数据源 **`local_mysql2`（山洪报告数据源）**：id 仍是 `1999990000000000001`，但 JDBC 已切到
  `jdbc:postgresql://127.0.0.1:5432/shreport_map`（`db_type=6` postgresql）。改数据源后需清 Redis `sys:cache:dbconnect:*` 并重启后端。
- 章节 LLM 节点统一为 **火山方舟-agent**（`airag_model` id `2099900548148649986`，`ark-code-latest`）。
- 影响分析假设参数：Q=30m³/s、n=0.035、S=0.01（写在 SQL 中，可按需修改）。
- 照片：章节内容中带 `【照片:编码】` 占位符，按调查成果编码映射本地照片文件。

## 引擎/设计器的坑（重要，后续维护必读）

- **`code`、`enhanceJava` 节点在当前前端包（@jeecg/aiflow 3.9.5-beta）设计器中渲染空白**（官方示例同样空白），
  因此统计/公式全部写在 SQL 里，落库/合并逻辑全部放在 Controller。不要在设计器里往这些流程里添加 code/java 节点。
- 多个节点的 outputParams **name 不能重复**（会渲染崩溃）；SQL 节点输出固定写 context 键 `<节点id>.body`（行数组 JSON），
  下游节点 inputParams 的 field 需填 `body`。
- 链式表达式组件名是节点类型（sql/llm/end/subflow/enhanceJava），tag 值才是节点 id；try-catch 语法是
  `CATCH(THEN(...)).DO(...)`（该版本无 TRY 关键字）。end 节点会抛 ChainEndException 终止链路，不能放在 CATCH 的 DO 分支中间。
- `sys_data_source` 的密码是 AES 密文（key=JEECGBOOT1423670），改动数据源后需清 Redis `sys:cache:dbconnect:dynamic:local_mysql2` 并重启后端。

## 文件

- `ai_flows/fix_pg_volc.py`：把线上流程 SQL 切到 PostgreSQL、LLM 切到火山方舟-agent（幂等）。
- `ai_flows/upgrade_watershed.py`：按流域过滤的章节流程生成脚本（与线上结构一致，SQL 已是 PG）。
- `ai_flows/upgrade_audit.py` / `update_flows.py`：历史脚本，**不要重跑覆盖线上**。
- `ai_flows/*.jeecgai`：流程导出文件（Base64，可导入其他环境）。
- `ai_flows/make_docs.py` + `output/`：文档转换管线验证脚本与样例（HTML+Word）。
- Java：`jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/shreport/`（Controller + WordBuilder）。
