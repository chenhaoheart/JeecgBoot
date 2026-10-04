# -*- coding: utf-8 -*-
"""Idempotent: add Volcengine Doubao embedding model using 火山agent API key."""
import json
import pymysql

EMBED_ID = "2099990000000000103"
KNOW_ID = "2099990000000000101"
AGENT_ID = "2099900548148649986"
BASE_URL = "https://ark.cn-beijing.volces.com/api/plan/v3"
NAME = "火山方舟 豆包向量"
MODEL = "doubao-embedding-vision"


def main():
    conn = pymysql.connect(host="127.0.0.1", user="root", password="123456", database="jeecg-boot", charset="utf8mb4")
    cur = conn.cursor()
    cur.execute("SELECT credential FROM airag_model WHERE id=%s", (AGENT_ID,))
    row = cur.fetchone()
    if not row:
        raise SystemExit("找不到火山 Agent 模型，无法复用 API Key")
    cred = row[0]
    cur.execute("SELECT id FROM airag_model WHERE id=%s OR (provider='VOLCENGINE' AND model_type='EMBED') LIMIT 1", (EMBED_ID,))
    exists = cur.fetchone()
    if exists:
        cur.execute(
            "UPDATE airag_model SET name=%s, provider='VOLCENGINE', model_name=%s, model_type='EMBED', "
            "base_url=%s, credential=%s, activate_flag=1, update_time=NOW() WHERE id=%s",
            (NAME, MODEL, BASE_URL, cred, exists[0]),
        )
        mid = exists[0]
        print("updated", mid)
    else:
        cur.execute(
            "INSERT INTO airag_model (id, create_by, create_time, sys_org_code, tenant_id, name, provider, "
            "model_name, credential, base_url, model_type, activate_flag) "
            "VALUES (%s,'admin',NOW(),'A04','0',%s,'VOLCENGINE',%s,%s,%s,'EMBED',1)",
            (EMBED_ID, NAME, MODEL, cred, BASE_URL),
        )
        mid = EMBED_ID
        print("inserted", mid)
    cur.execute("UPDATE airag_knowledge SET embed_id=%s WHERE id=%s", (mid, KNOW_ID))
    conn.commit()
    cur.close()
    conn.close()
    print("knowledge embed_id ->", mid)


if __name__ == "__main__":
    main()
