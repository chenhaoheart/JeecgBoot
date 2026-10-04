# -*- coding: utf-8 -*-
"""
在 AI 模型配置表（airag_model）中幂等写入「智谱 GLM-4.7 Flash」模型并激活。
写入后无需重启后端（模型走数据库实时读取），刷新山洪报告助手页面即可在顶栏「模型」下拉看到。

用法：
  set ZHIPU_API_KEY=你的key   (Windows PowerShell: $env:ZHIPU_API_KEY='你的key')
  python setup_zhipu_model.py
  也可直接带参：python setup_zhipu_model.py --api-key 你的key [--model glm-4.7]

数据库连接默认与 upgrade_*.py 一致（127.0.0.1 root/123456 jeecg-boot），
可用环境变量覆盖：DB_HOST/DB_USER/DB_PASSWORD/DB_NAME。
"""
import argparse
import json
import os
import sys

import pymysql

MODEL_CODE_DEFAULT = "glm-4.7"
BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
MODEL_PARAMS = {
    "temperature": 0.3,
    "topP": 0.95,
    "maxTokens": 4096,
    "timeout": 120,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api-key", default=os.environ.get("ZHIPU_API_KEY", ""))
    ap.add_argument("--model", default=os.environ.get("ZHIPU_MODEL", MODEL_CODE_DEFAULT))
    ap.add_argument("--name", default="智谱 GLM-4.7")
    args = ap.parse_args()
    if not args.api_key:
        print("缺少 API Key：请设置环境变量 ZHIPU_API_KEY 或用 --api-key 传入")
        sys.exit(1)

    conn = pymysql.connect(
        host=os.environ.get("DB_HOST", "127.0.0.1"),
        user=os.environ.get("DB_USER", "root"),
        password=os.environ.get("DB_PASSWORD", "123456"),
        database=os.environ.get("DB_NAME", "jeecg-boot"),
        charset="utf8mb4",
    )
    cur = conn.cursor(pymysql.cursors.DictCursor)
    credential = json.dumps({"apiKey": args.api_key}, ensure_ascii=False)
    model_params = json.dumps(MODEL_PARAMS, ensure_ascii=False)

    # 同一模型代码只保留一条（按 model_name 幂等），已存在则更新凭证/地址并确保激活
    cur.execute("SELECT id FROM airag_model WHERE model_name=%s AND provider='OPENAI' "
                "ORDER BY activate_flag DESC LIMIT 1", (args.model,))
    row = cur.fetchone()
    if row:
        cur.execute(
            "UPDATE airag_model SET name=%s, base_url=%s, credential=%s, model_params=%s, "
            "model_type='LLM', activate_flag=1 WHERE id=%s",
            (args.name, BASE_URL, credential, model_params, row["id"]),
        )
        print("已更新并激活模型：", args.name, args.model, row["id"])
    else:
        import uuid
        mid = "19" + uuid.uuid4().hex[:17]
        cur.execute(
            "INSERT INTO airag_model (id, create_by, create_time, sys_org_code, "
            "name, provider, model_type, model_name, base_url, credential, model_params, activate_flag) "
            "VALUES (%s,'admin',NOW(),'A04',%s,'OPENAI','LLM',%s,%s,%s,%s,1)",
            (mid, args.name, args.model, BASE_URL, credential, model_params),
        )
        print("已新增并激活模型：", args.name, args.model, mid)
    conn.commit()
    cur.close()
    conn.close()
    print("完成。刷新山洪报告助手页面，顶栏「模型」下拉选择「%s」即可。" % args.name)


if __name__ == "__main__":
    main()
