# -*- coding: utf-8 -*-
"""把 MySQL 的 sh_report_chapter、sh_report_metric、sh_report_qc_issue 同步到 PostgreSQL。

连接信息从 application-dev.yml 读取：
  spring.datasource.dynamic.datasource.master   -> MySQL（库名以 url 为准）
  spring.datasource.dynamic.datasource.shreport -> PostgreSQL（库名以 url 为准）
不在源码里写死密码。下列环境变量非空时覆盖 yml：
  SH_SYNC_YML
  MYSQL_HOST MYSQL_PORT MYSQL_USER MYSQL_PASSWORD MYSQL_DATABASE
  PG_HOST PG_PORT PG_USER PG_PASSWORD PG_DATABASE

幂等：
  chapter / metric 按主键 id；仅当 MySQL update_time 晚于 PG（或 PG 无此 id）时覆盖。
  qc_issue 无 update_time：PG 已有该 id 则跳过，否则插入。
不删除、不修改 MySQL 数据。

用法（在仓库根目录）：
  python ai_flows/sync_sh_report_pg.py --apply-ddl
  python ai_flows/sync_sh_report_pg.py
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import datetime
from decimal import Decimal
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_YML = (
    REPO
    / "jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml"
)
DEFAULT_DDL = (
    REPO
    / "jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/postgresql"
    / "V3.9.5_1__sh_report_chapter_metric_qc.sql"
)

JDBC_RE = re.compile(
    r"^jdbc:(?:mysql|postgresql)://(?P<host>[^/:]+)(?::(?P<port>\d+))?/(?P<db>[^?]+)",
    re.IGNORECASE,
)

# (data_type, character_maximum_length or None)
CHAPTER_TYPES = {
    "id": ("character varying", 36),
    "batch_no": ("character varying", 64),
    "chapter_no": ("integer", None),
    "chapter_name": ("character varying", 100),
    "content": ("text", None),
    "status": ("character varying", 20),
    "audit_note": ("character varying", 500),
    "create_by": ("character varying", 50),
    "create_time": ("timestamp without time zone", None),
    "update_by": ("character varying", 50),
    "update_time": ("timestamp without time zone", None),
    "lybm": ("character varying", 64),
    "lymc": ("character varying", 200),
    "content_prev": ("text", None),
    "fail_reason": ("character varying", 500),
    "flow_ver": ("character varying", 200),
    "gen_start_ts": ("bigint", None),
    "gen_cost_ms": ("bigint", None),
}
QC_TYPES = {
    "id": ("character varying", 36),
    "batch_no": ("character varying", 64),
    "lybm": ("character varying", 64),
    "chapter_no": ("integer", None),
    "level": ("character varying", 10),
    "type": ("character varying", 50),
    "message": ("character varying", 1000),
    "create_time": ("timestamp without time zone", None),
}
METRIC_TYPES = {
    "id": ("character varying", 36),
    "batch_no": ("character varying", 64),
    "lybm": ("character varying", 64),
    "metric_key": ("character varying", 64),
    "metric_name": ("character varying", 100),
    "value": ("character varying", 200),
    "unit": ("character varying", 20),
    "source": ("character varying", 10),
    "chapter_no": ("integer", None),
    "update_time": ("timestamp without time zone", None),
}

TABLES = [
    {
        "name": "sh_report_chapter",
        "columns": list(CHAPTER_TYPES),
        "types": CHAPTER_TYPES,
        "time_col": "update_time",
        "unique_cols": ["batch_no", "lybm", "chapter_no"],
        "indexes": {"idx_batch_chapter": False, "uk_batch_lybm_chapter": True},
    },
    {
        "name": "sh_report_metric",
        "columns": list(METRIC_TYPES),
        "types": METRIC_TYPES,
        "time_col": "update_time",
        "unique_cols": ["batch_no", "lybm", "metric_key"],
        "indexes": {"uk_metric_batch_lybm_key": True},
    },
    {
        "name": "sh_report_qc_issue",
        "columns": list(QC_TYPES),
        "types": QC_TYPES,
        "time_col": None,
        "unique_cols": [],
        "indexes": {"idx_qc_batch_lybm_chapter": False},
    },
]

INT_COLS = {"chapter_no", "gen_start_ts", "gen_cost_ms"}


def _configure_stdio():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8")
            except Exception:
                pass


def _scalar(value):
    if value is None:
        return ""
    return str(value).strip().strip("'\"")


def _parse_scalar(raw):
    raw = raw.strip()
    if not raw:
        return ""
    if raw[0] in "'\"":
        quote = raw[0]
        end = raw.find(quote, 1)
        if end > 0:
            return raw[1:end]
    if " #" in raw:
        raw = raw.split(" #", 1)[0].strip()
    return raw.strip().strip("'\"")


def _find_block(text, key, url_prefix):
    lines = text.splitlines()
    found = None
    for i, line in enumerate(lines):
        matched = re.match(r"^(\s*)" + re.escape(key) + r":\s*$", line)
        if not matched:
            continue
        indent = len(matched.group(1))
        child_indent = None
        block = {}
        for nxt in lines[i + 1 :]:
            if not nxt.strip() or nxt.strip().startswith("#"):
                continue
            ind = len(nxt) - len(nxt.lstrip(" "))
            if ind <= indent:
                break
            if child_indent is None:
                child_indent = ind
            if ind != child_indent:
                continue
            item = re.match(r"^\s*([\w-]+):\s*(.*)$", nxt)
            if not item:
                continue
            block[item.group(1)] = _parse_scalar(item.group(2))
        url = block.get("url", "")
        if url.startswith(url_prefix):
            found = block
    return found


def load_datasources(path):
    text = Path(path).read_text(encoding="utf-8")
    master = shreport = None
    try:
        import yaml

        doc = yaml.safe_load(text)
        ds = doc["spring"]["datasource"]["dynamic"]["datasource"]
        master, shreport = ds.get("master"), ds.get("shreport")
    except Exception:
        master = shreport = None
    if not master or not shreport or "url" not in master or "url" not in shreport:
        master = _find_block(text, "master", "jdbc:mysql:")
        shreport = _find_block(text, "shreport", "jdbc:postgresql:")
    if not master or not shreport:
        raise SystemExit(f"无法从 {path} 解析 master / shreport 数据源")
    return master, shreport


def jdbc_cfg(block, default_port):
    url = _scalar(block.get("url"))
    matched = JDBC_RE.match(url)
    if not matched:
        raise SystemExit(f"无法解析 JDBC URL: {url}")
    port = matched.group("port")
    return {
        "host": matched.group("host"),
        "port": int(port) if port else default_port,
        "database": matched.group("db"),
        "user": _scalar(block.get("username")),
        "password": _scalar(block.get("password")),
    }


def apply_env(cfg, mapping):
    for field, name in mapping.items():
        val = os.environ.get(name)
        if val is None or val == "":
            continue
        cfg[field] = int(val) if field == "port" else val
    return cfg


def load_config(yml_path):
    master, shreport = load_datasources(yml_path)
    mysql_cfg = jdbc_cfg(master, 3306)
    pg_cfg = jdbc_cfg(shreport, 5432)
    apply_env(
        mysql_cfg,
        {
            "host": "MYSQL_HOST",
            "port": "MYSQL_PORT",
            "user": "MYSQL_USER",
            "password": "MYSQL_PASSWORD",
            "database": "MYSQL_DATABASE",
        },
    )
    apply_env(
        pg_cfg,
        {
            "host": "PG_HOST",
            "port": "PG_PORT",
            "user": "PG_USER",
            "password": "PG_PASSWORD",
            "database": "PG_DATABASE",
        },
    )
    return mysql_cfg, pg_cfg


def _target(cfg):
    return f"{cfg['user']}@{cfg['host']}:{cfg['port']}/{cfg['database']}"


def connect_mysql(cfg):
    try:
        import pymysql
    except ImportError as exc:
        raise SystemExit("未安装 pymysql，请先 pip install pymysql") from exc
    try:
        conn = pymysql.connect(
            host=cfg["host"],
            port=int(cfg["port"]),
            user=cfg["user"],
            password=cfg["password"],
            database=cfg["database"],
            charset="utf8mb4",
            connect_timeout=10,
            read_timeout=300,
            cursorclass=pymysql.cursors.DictCursor,
        )
    except Exception as exc:
        raise SystemExit(f"MySQL 连接失败 {_target(cfg)}: {exc}") from exc
    with conn.cursor() as cur:
        cur.execute("SET SESSION TRANSACTION READ ONLY")
    return conn


def connect_pg(cfg):
    errors = None
    conn = None
    try:
        import psycopg2
        from psycopg2 import errors as pg_errors

        errors = pg_errors
        conn = psycopg2.connect(
            host=cfg["host"],
            port=int(cfg["port"]),
            user=cfg["user"],
            password=cfg["password"],
            dbname=cfg["database"],
            connect_timeout=10,
        )
    except ImportError:
        try:
            import psycopg
            from psycopg import errors as pg_errors

            errors = pg_errors
            conn = psycopg.connect(
                host=cfg["host"],
                port=int(cfg["port"]),
                user=cfg["user"],
                password=cfg["password"],
                dbname=cfg["database"],
                connect_timeout=10,
            )
        except ImportError as exc:
            raise SystemExit("未安装 psycopg2 或 psycopg，请先 pip install psycopg2-binary") from exc
        except Exception as exc:
            raise SystemExit(f"PostgreSQL 连接失败 {_target(cfg)}: {exc}") from exc
    except Exception as exc:
        raise SystemExit(f"PostgreSQL 连接失败 {_target(cfg)}: {exc}") from exc
    cur = conn.cursor()
    cur.execute("SET search_path TO public")
    conn.commit()
    cur.close()
    return conn, errors


def split_sql(text):
    lines = []
    for line in text.splitlines():
        if line.strip().startswith("--"):
            continue
        lines.append(line)
    body = "\n".join(lines)
    parts = []
    buf = []
    in_single = False
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "'":
            if in_single and i + 1 < len(body) and body[i + 1] == "'":
                buf.append("''")
                i += 2
                continue
            in_single = not in_single
            buf.append(ch)
            i += 1
            continue
        if ch == ";" and not in_single:
            stmt = "".join(buf).strip()
            if stmt:
                parts.append(stmt)
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        parts.append(tail)
    return parts


def apply_ddl(conn, path):
    ddl = Path(path)
    if not ddl.is_file():
        raise SystemExit(f"找不到 DDL 文件: {ddl}")
    stmts = split_sql(ddl.read_text(encoding="utf-8"))
    cur = conn.cursor()
    try:
        for i, stmt in enumerate(stmts, 1):
            try:
                cur.execute(stmt)
            except Exception as exc:
                conn.rollback()
                preview = " ".join(stmt.split())[:200]
                raise SystemExit(f"DDL 第 {i} 条失败: {exc}\nSQL: {preview}") from exc
        conn.commit()
    finally:
        cur.close()
    print(f"DDL 已执行: {ddl}（{len(stmts)} 条语句）")


def verify_schema(conn):
    cur = conn.cursor()
    problems = []
    print("=== 表结构 ===")
    for spec in TABLES:
        name = spec["name"]
        cur.execute(
            """
            SELECT column_name, data_type, character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = %s
            """,
            (name,),
        )
        found = {row[0]: (row[1], row[2]) for row in cur.fetchall()}
        missing = [col for col in spec["columns"] if col not in found]
        type_bad = []
        for col, expect in spec["types"].items():
            if col not in found:
                continue
            actual = found[col]
            if actual[0] != expect[0] or (expect[1] is not None and actual[1] != expect[1]):
                type_bad.append(f"{col} 实际 {actual[0]}({actual[1]}) 期望 {expect[0]}({expect[1]})")
        cur.execute(
            """
            SELECT ic.relname, ix.indisunique
            FROM pg_class tc
            JOIN pg_namespace ns ON ns.oid = tc.relnamespace
            JOIN pg_index ix ON ix.indrelid = tc.oid
            JOIN pg_class ic ON ic.oid = ix.indexrelid
            WHERE ns.nspname = 'public' AND tc.relname = %s
            """,
            (name,),
        )
        indexes = {row[0]: bool(row[1]) for row in cur.fetchall()}
        index_bad = []
        for idx, unique in spec["indexes"].items():
            if idx not in indexes:
                index_bad.append(f"缺少索引 {idx}")
            elif indexes[idx] != unique:
                index_bad.append(f"索引 {idx} 唯一性不符")
        extra = [col for col in found if col not in spec["types"]]
        ok = not missing and not type_bad and not index_bad
        flag = "齐全" if ok else "不齐全"
        print(f"{name}: {len(spec['columns'])} 列{flag}，已有 {len(found)} 列")
        if missing:
            problems.append(f"{name} 缺列: {', '.join(missing)}")
        problems.extend(f"{name} {item}" for item in type_bad)
        problems.extend(f"{name} {item}" for item in index_bad)
        if extra:
            print(f"  额外列: {', '.join(extra)}")
    cur.close()
    if problems:
        for item in problems:
            print("结构问题: " + item, file=sys.stderr)
        raise SystemExit(2)
    print("三张表列与索引齐全")


def _lower_row(raw):
    return {str(k).lower(): v for k, v in raw.items()}


def load_mysql_rows(conn, table, columns):
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT COLUMN_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = %s
            """,
            (table,),
        )
        have = {str(row["COLUMN_NAME"]).lower() for row in cur.fetchall()}
        if not have:
            raise SystemExit(f"MySQL 中不存在表 {table}")
        missing = [col for col in columns if col not in have]
        present = [col for col in columns if col in have]
        if "id" not in have:
            raise SystemExit(f"MySQL 表 {table} 没有 id 列")
        cur.execute("SELECT " + ", ".join(present) + " FROM " + table + " ORDER BY id")
        rows = []
        for raw in cur.fetchall():
            item = _lower_row(raw)
            for col in missing:
                item[col] = None
            if not item.get("id"):
                continue
            rows.append(item)
    return rows, missing


def to_dt(value):
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.replace(tzinfo=None) if value.tzinfo else value
    if isinstance(value, str):
        text = value.strip().replace("T", " ")
        if text.endswith("Z"):
            text = text[:-1]
        for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
            try:
                return datetime.strptime(text[:26], fmt)
            except ValueError:
                continue
    return None


def mysql_is_newer(mysql_ts, pg_ts):
    mysql_dt = to_dt(mysql_ts)
    if mysql_dt is None:
        return False
    pg_dt = to_dt(pg_ts)
    if pg_dt is None:
        return True
    return mysql_dt > pg_dt


def norm_db(col, value):
    if value is None:
        return None
    if col in INT_COLS and isinstance(value, (int, float, Decimal)) and not isinstance(value, bool):
        return int(value)
    return value


def unique_key(row, cols):
    if not cols:
        return None
    parts = []
    for col in cols:
        value = norm_db(col, row.get(col))
        if value is None:
            return None
        parts.append(value)
    return tuple(parts)


def sync_table(pg_conn, mysql_conn, spec, pg_errors):
    name = spec["name"]
    columns = spec["columns"]
    rows, missing = load_mysql_rows(mysql_conn, name, columns)
    if missing:
        print(f"警告: MySQL {name} 缺少列 {', '.join(missing)}，这些列按 NULL 写入")

    select_cols = ["id"]
    if spec["time_col"]:
        select_cols.append(spec["time_col"])
    select_cols.extend(col for col in spec["unique_cols"] if col not in select_cols)
    cur = pg_conn.cursor()
    cur.execute("SELECT " + ", ".join(select_cols) + " FROM " + name)
    existing_time = {}
    existing_key = {}
    key_by_id = {}
    for rec in cur.fetchall():
        rec_map = {select_cols[i]: rec[i] for i in range(len(select_cols))}
        row_id = rec_map["id"]
        existing_time[row_id] = rec_map.get(spec["time_col"]) if spec["time_col"] else None
        key = unique_key(rec_map, spec["unique_cols"])
        key_by_id[row_id] = key
        if key is not None:
            existing_key[key] = row_id

    insert_sql = (
        "INSERT INTO " + name + " (" + ", ".join(columns) + ") VALUES ("
        + ", ".join(["%s"] * len(columns))
        + ")"
    )
    update_cols = [col for col in columns if col != "id"]
    update_sql = (
        "UPDATE " + name + " SET " + ", ".join(col + "=%s" for col in update_cols) + " WHERE id=%s"
    )

    stat = {"insert": 0, "update": 0, "skip": 0, "conflict": 0}
    conflict_shown = 0

    def params(row, with_id_last=False):
        if with_id_last:
            return tuple(norm_db(col, row.get(col)) for col in update_cols) + (row["id"],)
        return tuple(norm_db(col, row.get(col)) for col in columns)

    def note_conflict(row_id, reason):
        nonlocal conflict_shown
        stat["conflict"] += 1
        if conflict_shown < 20:
            print(f"冲突跳过 {name} id={row_id}: {reason}")
            conflict_shown += 1

    for row in rows:
        row_id = row["id"]
        new_key = unique_key(row, spec["unique_cols"])
        owner = existing_key.get(new_key) if new_key is not None else None
        if owner is not None and owner != row_id:
            note_conflict(row_id, "唯一键已被 id=" + str(owner) + " 占用")
            continue
        if row_id not in existing_time:
            try:
                cur.execute(insert_sql, params(row))
            except pg_errors.UniqueViolation:
                pg_conn.rollback()
                raise
            stat["insert"] += 1
            existing_time[row_id] = row.get(spec["time_col"]) if spec["time_col"] else None
            key_by_id[row_id] = new_key
            if new_key is not None:
                existing_key[new_key] = row_id
            continue
        if spec["time_col"] is None:
            stat["skip"] += 1
            continue
        if not mysql_is_newer(row.get(spec["time_col"]), existing_time.get(row_id)):
            stat["skip"] += 1
            continue
        cur.execute(update_sql, params(row, with_id_last=True))
        stat["update"] += 1
        old_key = key_by_id.get(row_id)
        if old_key is not None and existing_key.get(old_key) == row_id:
            existing_key.pop(old_key, None)
        key_by_id[row_id] = new_key
        if new_key is not None:
            existing_key[new_key] = row_id
        existing_time[row_id] = row.get(spec["time_col"])
    cur.close()
    stat["mysql"] = len(rows)
    return stat


def main(argv=None):
    _configure_stdio()
    parser = argparse.ArgumentParser(description="同步山洪报告三张表 MySQL -> PostgreSQL")
    parser.add_argument("--apply-ddl", action="store_true", help="同步前执行 PostgreSQL 建表脚本")
    parser.add_argument("--ddl", default=str(DEFAULT_DDL), help="DDL 文件路径")
    parser.add_argument("--yml", default=os.environ.get("SH_SYNC_YML", str(DEFAULT_YML)), help="数据源 yml")
    args = parser.parse_args(argv)

    yml_path = Path(args.yml)
    if not yml_path.is_file():
        raise SystemExit(f"找不到数据源配置: {yml_path}")
    mysql_cfg, pg_cfg = load_config(yml_path)
    print(f"MySQL {_target(mysql_cfg)}")
    print(f"PostgreSQL {_target(pg_cfg)}")

    mysql_conn = connect_mysql(mysql_cfg)
    pg_conn, pg_errors = connect_pg(pg_cfg)
    try:
        if args.apply_ddl:
            apply_ddl(pg_conn, args.ddl)
        verify_schema(pg_conn)
        print("=== 同步结果 ===")
        for spec in TABLES:
            stat = sync_table(pg_conn, mysql_conn, spec, pg_errors)
            pg_conn.commit()
            line = (
                f"{spec['name']}: mysql={stat['mysql']} "
                f"insert={stat['insert']} update={stat['update']} skip={stat['skip']}"
            )
            if stat["conflict"]:
                line += f" conflict={stat['conflict']}"
            print(line)
    finally:
        mysql_conn.close()
        pg_conn.close()


if __name__ == "__main__":
    main()
