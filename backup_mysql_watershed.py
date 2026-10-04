# -*- coding: utf-8 -*-
"""备份并清空 MySQL 流域调查数据（已由一张图 PostgreSQL shreport_map 取代）。"""
import os
import time

import pymysql

TABLES = ['prevention_object', 'ia_m_hsurface', 'road_culvert_bridge_dam', 'gully_occupy_area']


def esc(v):
    s = str(v)
    for a, b in (('\\', '\\\\'), ('"', '\\"'), ('\n', '\\n'), ('\r', '\\r'), ('\t', '\\t')):
        s = s.replace(a, b)
    return s


def main():
    conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='123456',
                           database='jeecg-boot', charset='utf8mb4')
    cur = conn.cursor()
    os.makedirs('db_backup', exist_ok=True)
    out_path = 'db_backup/mysql_watershed_backup_%s.sql' % time.strftime('%Y%m%d_%H%M%S')
    with open(out_path, 'w', encoding='utf-8') as f:
        for t in TABLES:
            cur.execute('SHOW CREATE TABLE %s' % t)
            f.write(cur.fetchone()[1] + ';\n\n')
            cur.execute('SELECT * FROM %s' % t)
            cols = [d[0] for d in cur.description]
            n = 0
            for row in cur.fetchall():
                vals = ','.join('NULL' if v is None else '"%s"' % esc(v) for v in row)
                f.write('INSERT INTO %s (%s) VALUES (%s);\n' % (t, ','.join(cols), vals))
                n += 1
            f.write('\n')
            print(t, 'backed up rows:', n)
    print('backup file:', out_path)
    with open(out_path, 'rb') as f:
        print('backup size bytes:', os.fstat(f.fileno()).st_size)


if __name__ == '__main__':
    main()
