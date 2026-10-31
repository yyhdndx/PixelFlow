"""Explicit, reversible publication of validated samples for the course prototype.

Separate from research ingestion; never run automatically on import or deployment.
"""
import argparse
import hashlib
import json
import re
import time
from pathlib import Path
from import_corpus import Mysql, load_input
from wukong_corpus import sql_text

AUTHOR = 'demo-wukong-20261030'
USERNAME = 'demo_media_wukong_20261030'
BATCH = 'wukong-course-demo-20261030'
TABLE = 'corpus_demo_publications'


def business_name(value):
    if value != 'pixelflow_app' and not re.fullmatch(r'pixelflow_demo_test_[a-f0-9]{32}',value):
        raise ValueError('Unexpected business database')
    return '`'+value+'`'


def select_rows(rows, manifest, checks, dimensions):
    if dimensions.get('records_sha256') != manifest['records_sha256']:
        raise ValueError('Dimension input checksum mismatch')
    selected=[]
    for row in rows:
        if checks['records'][row['source_record_id']]['resource_status'] != 'REACHABLE': continue
        size=dimensions.get('records',{}).get(row['source_record_id'],{})
        w,h=size.get('width'),size.get('height')
        if type(w) is not int or type(h) is not int: continue
        if not (1 <= w <= 16384 and 1 <= h <= 16384 and w*h <= 50_000_000): continue
        if len(row['source_url'])>2048: continue
        selected.append((row,{'type':0,'width':w,'height':h,'url':row['source_url']}))
    if not selected: raise ValueError('No usable demo images')
    return sorted(selected,key=lambda pair:pair[0]['source_record_id'])


def table_exists(db):
    return bool(db.query('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='+sql_text(db.database)+' AND TABLE_NAME='+sql_text(TABLE)))


def publish(db, selected, apply=False, business='pixelflow_app'):
    app=business_name(business)
    if not db.ensure_schema(False): raise ValueError('Import research corpus first')
    if not 1 <= len(selected) <= 10000: raise ValueError('Invalid publication size')
    record_ids=[r['source_record_id'] for r,_ in selected]
    if len(set(record_ids)) != len(record_ids): raise ValueError('Duplicate source records')
    found=db.query('SELECT source_record_id,content_hash FROM corpus_documents WHERE source_record_id IN ('+','.join(sql_text(i) for i in record_ids)+')')
    if dict(found)!={r['source_record_id']:r['content_hash'] for r,_ in selected}:
        raise ValueError('Sources do not match imported corpus')
    digest=hashlib.sha256(json.dumps([(r['source_record_id'],r['content_hash'],clip) for r,clip in selected],sort_keys=True,ensure_ascii=False).encode()).hexdigest()
    exists=table_exists(db)
    marker=db.query('SELECT name FROM '+app+'.seed_batches WHERE name='+sql_text(BATCH))
    if marker:
        if not exists: raise ValueError('Batch marker without mapping')
        old=db.query('SELECT source_record_id,publication_hash FROM '+TABLE+' WHERE batch_id='+sql_text(BATCH))
        if len(old)!=len(selected) or {r[0] for r in old}!=set(record_ids) or any(r[1]!=digest for r in old):
            raise ValueError('Publication batch does not match input')
        actual=db.query('SELECT COUNT(*) FROM '+TABLE+' m JOIN '+app+'.posts p ON p.id=m.post_id AND p.author_id='+sql_text(AUTHOR)+' WHERE m.batch_id='+sql_text(BATCH))
        if actual!=[[str(len(selected))]]: raise ValueError('Published post mapping damaged')
        return {'status':'already_published','count':len(selected),'inserted':0}
    if exists and db.query('SELECT post_id FROM '+TABLE+' WHERE source_record_id IN ('+','.join(sql_text(i) for i in record_ids)+') LIMIT 1'):
        raise ValueError('Source already published in another batch')
    author=db.query('SELECT id,username,enabled FROM '+app+'.users WHERE id='+sql_text(AUTHOR)+' OR username='+sql_text(USERNAME))
    if author and author!=[[AUTHOR,USERNAME,'1']]: raise ValueError('Demo author collision or disabled author')
    report={'status':'published' if apply else 'dry_run','batch':BATCH,'count':len(selected),'inserted':len(selected),'author_id':AUTHOR}
    if not apply: return report
    db.query('CREATE TABLE IF NOT EXISTS '+TABLE+' (batch_id VARCHAR(80) NOT NULL,source_record_id CHAR(64) NOT NULL UNIQUE,post_id BIGINT NOT NULL UNIQUE,publication_hash CHAR(64) NOT NULL,created_at BIGINT NOT NULL,PRIMARY KEY(batch_id,source_record_id)) ENGINE=InnoDB')
    now=int(time.time())
    sql=['START TRANSACTION']
    if not author:
        # Reserved seed username; this sentinel can never validate as a BCrypt password.
        values=[AUTHOR,USERNAME,'!NO_LOGIN:COURSE_DATASET!','悟空图文样本','CREATOR','课设图文样本，来源：Noah-WuKong；非真实用户创作']
        sql.append('INSERT INTO '+app+'.users(id,username,password_hash,nickname,role,bio) VALUES('+','.join(sql_text(v) for v in values)+')')
    sql.append('INSERT INTO '+app+'.seed_batches(name,created_at) VALUES('+sql_text(BATCH)+','+str(now)+')')
    for row,clip in selected:
        values=[sql_text(AUTHOR),sql_text(row['title']),sql_text(row['content']),sql_text(json.dumps([clip],ensure_ascii=False)),'FALSE',sql_text('PUBLISHED'),str(now)]
        sql.append('INSERT INTO '+app+'.posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES('+','.join(values)+')')
        sql.append('INSERT INTO '+TABLE+'(batch_id,source_record_id,post_id,publication_hash,created_at) VALUES('+','.join([sql_text(BATCH),sql_text(row['source_record_id']),'LAST_INSERT_ID()',sql_text(digest),str(now)])+')')
    sql.append('COMMIT')
    db.query(';\n'.join(sql)+';')
    return report


def withdraw(db,apply=False,business='pixelflow_app'):
    app=business_name(business)
    if not db.ensure_schema(False) or not table_exists(db): return {'status':'absent','count':0}
    join=app+'.posts p JOIN '+TABLE+' m ON p.id=m.post_id'
    condition='m.batch_id='+sql_text(BATCH)+' AND p.author_id='+sql_text(AUTHOR)+" AND p.status='PUBLISHED'"
    count=int(db.query('SELECT COUNT(*) FROM '+join+' WHERE '+condition)[0][0])
    if apply:
        db.query("START TRANSACTION; UPDATE "+join+" SET p.status='WITHDRAWN' WHERE "+condition+'; COMMIT;')
    return {'status':'withdrawn' if apply else 'withdraw_dry_run','count':count}


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--defaults-file',type=Path,required=True)
    parser.add_argument('--directory',type=Path)
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--withdraw',action='store_true')
    args=parser.parse_args()
    db=Mysql(args.defaults_file,'pixelflow_corpus')
    if args.withdraw:
        result=withdraw(db,args.apply)
    else:
        if args.directory is None: parser.error('--directory is required')
        root=args.directory
        data=load_input(root/'records.jsonl',root/'manifest.json',root/'resource-checks.json')
        dims=json.loads((root/'demo-dimensions.json').read_text(encoding='utf-8'))
        result=publish(db,select_rows(*data,dims),args.apply)
    print(json.dumps(result,ensure_ascii=False))
