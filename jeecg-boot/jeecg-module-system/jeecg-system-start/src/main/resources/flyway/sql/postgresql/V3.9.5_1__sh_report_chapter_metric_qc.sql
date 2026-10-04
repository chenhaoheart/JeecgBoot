-- 山洪报告：章节产出、关键指标、质检问题（PostgreSQL）
-- 可重复执行：CREATE TABLE / INDEX IF NOT EXISTS，缺列则 ADD COLUMN IF NOT EXISTS，注释可覆盖。
-- 原 MySQL flyway 建表脚本已删除，这三张表只在 PostgreSQL 建。
-- 列含 ensureSchema 补过的 content_prev、fail_reason、flow_ver、gen_start_ts、gen_cost_ms。
-- mediumtext -> text，datetime -> timestamp。id 为 VARCHAR(36)，不使用序列。

SET search_path TO public;

-- ---------- sh_report_chapter ----------
CREATE TABLE IF NOT EXISTS sh_report_chapter (
    id VARCHAR(36) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    chapter_no INTEGER NOT NULL,
    chapter_name VARCHAR(100) NOT NULL,
    content TEXT,
    status VARCHAR(20) NOT NULL DEFAULT '待审核',
    audit_note VARCHAR(500),
    create_by VARCHAR(50),
    create_time TIMESTAMP,
    update_by VARCHAR(50),
    update_time TIMESTAMP,
    lybm VARCHAR(64),
    lymc VARCHAR(200),
    content_prev TEXT,
    fail_reason VARCHAR(500),
    flow_ver VARCHAR(200),
    gen_start_ts BIGINT,
    gen_cost_ms BIGINT,
    PRIMARY KEY (id)
);

ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS id VARCHAR(36);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS batch_no VARCHAR(64);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS chapter_no INTEGER;
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS chapter_name VARCHAR(100);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS content TEXT;
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT '待审核';
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS audit_note VARCHAR(500);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS create_by VARCHAR(50);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS create_time TIMESTAMP;
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS update_by VARCHAR(50);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS update_time TIMESTAMP;
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS lybm VARCHAR(64);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS lymc VARCHAR(200);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS content_prev TEXT;
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS fail_reason VARCHAR(500);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS flow_ver VARCHAR(200);
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS gen_start_ts BIGINT;
ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS gen_cost_ms BIGINT;

CREATE INDEX IF NOT EXISTS idx_batch_chapter
    ON sh_report_chapter (batch_no, chapter_no);

CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_lybm_chapter
    ON sh_report_chapter (batch_no, lybm, chapter_no);

COMMENT ON TABLE sh_report_chapter IS '山洪报告章节产出与审核';
COMMENT ON COLUMN sh_report_chapter.id IS '主键';
COMMENT ON COLUMN sh_report_chapter.batch_no IS '报告批次号';
COMMENT ON COLUMN sh_report_chapter.chapter_no IS '章节序号1-7';
COMMENT ON COLUMN sh_report_chapter.chapter_name IS '章节名称';
COMMENT ON COLUMN sh_report_chapter.content IS '章节内容(Markdown)';
COMMENT ON COLUMN sh_report_chapter.status IS '状态:待审核/已通过/已驳回/生成中/失败';
COMMENT ON COLUMN sh_report_chapter.audit_note IS '审核意见';
COMMENT ON COLUMN sh_report_chapter.lybm IS '流域编码(wscd)';
COMMENT ON COLUMN sh_report_chapter.lymc IS '流域名称(wsnm)';
COMMENT ON COLUMN sh_report_chapter.content_prev IS '上一版内容(重新生成前保留)';
COMMENT ON COLUMN sh_report_chapter.fail_reason IS '生成失败原因(失败状态下有值)';
COMMENT ON COLUMN sh_report_chapter.flow_ver IS '生成所用流程版本(流程名@设计更新时间)';
COMMENT ON COLUMN sh_report_chapter.gen_start_ts IS '本章本次生成开始时间(毫秒时间戳)';
COMMENT ON COLUMN sh_report_chapter.gen_cost_ms IS '本章从开始生成到最终完成的耗时(毫秒)';

-- ---------- sh_report_qc_issue ----------
CREATE TABLE IF NOT EXISTS sh_report_qc_issue (
    id VARCHAR(36) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    lybm VARCHAR(64),
    chapter_no INTEGER NOT NULL,
    level VARCHAR(10) NOT NULL DEFAULT 'HINT',
    type VARCHAR(50) NOT NULL,
    message VARCHAR(1000),
    create_time TIMESTAMP,
    PRIMARY KEY (id)
);

ALTER TABLE sh_report_qc_issue ADD COLUMN IF NOT EXISTS id VARCHAR(36);
ALTER TABLE sh_report_qc_issue ADD COLUMN IF NOT EXISTS batch_no VARCHAR(64);
ALTER TABLE sh_report_qc_issue ADD COLUMN IF NOT EXISTS lybm VARCHAR(64);
ALTER TABLE sh_report_qc_issue ADD COLUMN IF NOT EXISTS chapter_no INTEGER;
ALTER TABLE sh_report_qc_issue ADD COLUMN IF NOT EXISTS level VARCHAR(10) DEFAULT 'HINT';
ALTER TABLE sh_report_qc_issue ADD COLUMN IF NOT EXISTS type VARCHAR(50);
ALTER TABLE sh_report_qc_issue ADD COLUMN IF NOT EXISTS message VARCHAR(1000);
ALTER TABLE sh_report_qc_issue ADD COLUMN IF NOT EXISTS create_time TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_qc_batch_lybm_chapter
    ON sh_report_qc_issue (batch_no, lybm, chapter_no);

COMMENT ON TABLE sh_report_qc_issue IS '山洪报告章节自动质检问题';
COMMENT ON COLUMN sh_report_qc_issue.id IS '主键';
COMMENT ON COLUMN sh_report_qc_issue.batch_no IS '报告批次号';
COMMENT ON COLUMN sh_report_qc_issue.lybm IS '流域编码(wscd)';
COMMENT ON COLUMN sh_report_qc_issue.chapter_no IS '章节序号1-7';
COMMENT ON COLUMN sh_report_qc_issue.level IS '级别:BLOCK阻断/HINT提示';
COMMENT ON COLUMN sh_report_qc_issue.type IS '问题类型:PLACEHOLDER/SHORT/TABLE/NUMBER/WATERSHED/REQUIRED_TABLE/CONSISTENCY';
COMMENT ON COLUMN sh_report_qc_issue.message IS '问题描述';

-- ---------- sh_report_metric ----------
CREATE TABLE IF NOT EXISTS sh_report_metric (
    id VARCHAR(36) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    lybm VARCHAR(64),
    metric_key VARCHAR(64) NOT NULL,
    metric_name VARCHAR(100),
    value VARCHAR(200),
    unit VARCHAR(20),
    source VARCHAR(10) NOT NULL DEFAULT 'DATA',
    chapter_no INTEGER,
    update_time TIMESTAMP,
    PRIMARY KEY (id)
);

ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS id VARCHAR(36);
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS batch_no VARCHAR(64);
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS lybm VARCHAR(64);
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS metric_key VARCHAR(64);
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS metric_name VARCHAR(100);
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS value VARCHAR(200);
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS unit VARCHAR(20);
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS source VARCHAR(10) DEFAULT 'DATA';
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS chapter_no INTEGER;
ALTER TABLE sh_report_metric ADD COLUMN IF NOT EXISTS update_time TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uk_metric_batch_lybm_key
    ON sh_report_metric (batch_no, lybm, metric_key);

COMMENT ON TABLE sh_report_metric IS '山洪报告关键指标(跨章一致性基准)';
COMMENT ON COLUMN sh_report_metric.id IS '主键';
COMMENT ON COLUMN sh_report_metric.batch_no IS '报告批次号';
COMMENT ON COLUMN sh_report_metric.lybm IS '流域编码(wscd)';
COMMENT ON COLUMN sh_report_metric.metric_key IS '指标键:AREA/RIVER_LENGTH/SLOPE/ELEVATION/OBJECT_COUNT/POPULATION';
COMMENT ON COLUMN sh_report_metric.metric_name IS '指标中文名';
COMMENT ON COLUMN sh_report_metric.value IS '指标值(文本保留原始口径)';
COMMENT ON COLUMN sh_report_metric.unit IS '单位';
COMMENT ON COLUMN sh_report_metric.source IS '来源:DATA/CH1';
COMMENT ON COLUMN sh_report_metric.chapter_no IS '来源章节(抽取时)';
