# -*- coding: utf-8 -*-
"""山洪报告流程提示词补丁：无数据 = 有效调查结论，禁止空占位表。

与 flyway V3.9.5_17__sh_report_ch5_no_disaster_conclusion.sql 保持一致，
用于已初始化的存量库直接热修（flow 定义有缓存，改完需重启后端或从流程管理页重新保存）。

用法：python upgrade_ch5_no_disaster.py
"""
import json
import os

import pymysql

DB = dict(host='127.0.0.1', port=3306, user='root', password='123456',
          database='jeecg-boot', charset='utf8mb4')

SYS_TAIL = '报告针对单一小流域编写，行文中始终使用给定的流域名称。'
SYS_NEW = SYS_TAIL + (
    '若某项统计数据为空，只说明该指标在本流域客观不存在或本次调查未发现，'
    '应直接写明「本次调查未发现」或「该指标暂无数据」这一调查结论，'
    '严禁输出空占位表格，严禁出现 XX、TBD、待补充、待填写、（待补充）等未完成标记。'
)

CH5_OLD = '若资料为空，则撰写章节框架并说明需补充历史灾害调查资料。不得虚构具体数据。'
CH5_NEW = (
    '若资料为空，必须写成「本次调查未发现历史山洪灾害」的调查结论，而不是「资料待补充」：'
    '5.1 灾害记录统计——写明本次调查在该流域范围内未发现可考证的历史山洪灾害记录，记录数为 0 起；'
    '5.2 严重程度分级——写明因灾害记录数为 0，不存在特大型、大型、中型、小型等级判定；'
    '5.3 历史洪痕与入户调查情况——写明本次调查未发现历史洪痕与受灾痕迹，或据实说明已开展的洪痕测量与入户走访情况；'
    '5.4 灾害演变规律分析——写明因历史灾害样本数为 0，暂不进行发生频次、强度与演变规律的定量分析，'
    '本流域山洪灾害风险以现状隐患为主（见第三、四、六章）；'
    '5.5 后续复核建议——列出可从地方志、防汛档案、乡镇访谈等渠道补充复核的技术途径。'
    '严禁输出任何空占位表格，严禁出现 XX、TBD、待补充、待填写等未完成标记。不得虚构具体数据。'
)


def patch_design(design):
    """返回 (新 design, 是否有改动)"""
    changed = False
    if SYS_TAIL in design and '若某项统计数据为空' not in design:
        design = design.replace(SYS_TAIL, SYS_NEW)
        changed = True
    if CH5_OLD in design:
        design = design.replace(CH5_OLD, CH5_NEW)
        changed = True
    return design, changed


def main():
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    backup_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'output', 'flow_backup')
    os.makedirs(backup_dir, exist_ok=True)

    cur.execute("SELECT id, name, design FROM airag_flow WHERE name LIKE '山洪报告_%'")
    rows = cur.fetchall()
    for fid, name, design in rows:
        new_design, changed = patch_design(design or '')
        if not changed:
            print('[skip] %s' % name)
            continue
        with open(os.path.join(backup_dir, '%s.json' % fid), 'w', encoding='utf-8') as f:
            f.write(design or '')
        cur.execute("UPDATE airag_flow SET design=%s, update_time=NOW() WHERE id=%s", (new_design, fid))
        print('[patch] %s -> 已更新' % name)
    conn.commit()

    # 校验：design 必须仍是合法 JSON
    cur.execute("SELECT id, name, design FROM airag_flow WHERE name LIKE '山洪报告_%'")
    for fid, name, design in cur.fetchall():
        json.loads(design)
        has = '若某项统计数据为空' in design
        ch5 = CH5_NEW in (design or '') if '05' in name else None
        print('[check] %s json-ok sys=%s ch5=%s' % (name, has, ch5))
    conn.close()


if __name__ == '__main__':
    main()
