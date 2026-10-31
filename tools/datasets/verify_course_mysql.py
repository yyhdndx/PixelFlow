"""Real MySQL publication test using only newly created disposable databases."""
import argparse
import hashlib
import json
import uuid
from import_corpus import Mysql,ingest
from publish_course_demo import publish,withdraw,select_rows,business_name,TABLE,AUTHOR
from wukong_corpus import normalize,sql_text


def verify(defaults_file):
    token=uuid.uuid4().hex
    db=Mysql(defaults_file,'pixelflow_corpus_test_'+token)
    business='pixelflow_demo_test_'+token
    app=business_name(business)
    if db.ensure_schema(False) or db.query('SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='+sql_text(business),False):
        raise ValueError('Test databases already exist')
    created=False
    passed=[]
    try:
        db.query('CREATE DATABASE '+app+' CHARACTER SET utf8mb4',False);created=True
        db.query('CREATE TABLE '+app+'.users(id VARCHAR(36) PRIMARY KEY,username VARCHAR(32) UNIQUE,password_hash VARCHAR(100),nickname VARCHAR(40),role VARCHAR(16),enabled BOOLEAN DEFAULT TRUE,bio VARCHAR(60)); CREATE TABLE '+app+'.posts(id BIGINT AUTO_INCREMENT PRIMARY KEY,author_id VARCHAR(36),title VARCHAR(120),content VARCHAR(5000),clips_json TEXT,has_video BOOLEAN,status VARCHAR(16),create_time BIGINT,FOREIGN KEY(author_id) REFERENCES users(id)); CREATE TABLE '+app+'.seed_batches(name VARCHAR(80) PRIMARY KEY,created_at BIGINT); INSERT INTO '+app+".users(id,username) VALUES('original','original'); INSERT INTO "+app+".posts(author_id,title,status) VALUES('original','original','PUBLISHED');",False)
        rows=[normalize({'caption':s,'url':'https://example.com/%d.jpg'%i},'wukong_release/wukong_100m_0.csv',i+1) for i,s in enumerate(['咖啡美食样本记录','猫咪宠物样本记录','雪山旅行样本记录'])]
        manifest={'records_sha256':hashlib.sha256(json.dumps(rows).encode()).hexdigest(),'accepted':3}
        checks={'records':{r['source_record_id']:{'resource_status':'REACHABLE'} for r in rows}}
        dims={'records_sha256':manifest['records_sha256'],'records':{r['source_record_id']:{'width':640,'height':480} for r in rows}}
        ingest(db,'test-corpus',rows,manifest,checks,True)
        selected=select_rows(rows,manifest,checks,dims)
        assert publish(db,selected,False,business)['status']=='dry_run'
        assert db.query('SELECT COUNT(*) FROM '+app+'.posts')==[['1']]
        passed.append('dry_run_does_not_publish')
        rejected=sql_text(selected[-1][0]['title'])
        db.query('ALTER TABLE '+app+'.posts ADD CONSTRAINT reject_last CHECK(title<>'+rejected+')')
        try:
            publish(db,selected,True,business)
        except RuntimeError:
            assert db.query('SELECT COUNT(*) FROM '+app+'.posts')==[['1']]
            assert db.query('SELECT COUNT(*) FROM '+app+'.users')==[['1']]
            assert db.query('SELECT COUNT(*) FROM '+app+'.seed_batches')==[['0']]
            assert db.query('SELECT COUNT(*) FROM '+TABLE)==[['0']]
            passed.append('mid_batch_failure_rolls_back_all_business_writes')
        else: raise AssertionError('Expected constraint failure')
        db.query('ALTER TABLE '+app+'.posts DROP CHECK reject_last')
        assert publish(db,selected,True,business)['inserted']==3
        assert db.query('SELECT COUNT(*) FROM '+app+'.posts')==[['4']]
        passed.append('publishes_posts_with_source_mapping')
        assert publish(db,selected,True,business)['inserted']==0
        assert db.query('SELECT COUNT(*) FROM '+TABLE)==[['3']]
        passed.append('replay_is_idempotent')
        assert db.query('SELECT COUNT(*) FROM '+app+".posts WHERE author_id="+sql_text(AUTHOR)+" AND JSON_EXTRACT(clips_json,'$[0].width')=640 AND JSON_EXTRACT(clips_json,'$[0].height')=480")==[['3']]
        passed.append('stored_clip_dimensions_match')
        assert withdraw(db,False,business)['count']==3
        assert db.query('SELECT COUNT(*) FROM '+app+".posts WHERE status='PUBLISHED'")==[['4']]
        assert withdraw(db,True,business)['count']==3
        assert db.query('SELECT COUNT(*) FROM '+app+".posts WHERE status='PUBLISHED'")==[['1']]
        assert withdraw(db,True,business)['count']==0
        passed.append('withdrawal_preserves_existing_posts')
        assert publish(db,selected,True,business)['inserted']==0
        assert db.query('SELECT COUNT(*) FROM '+app+".posts WHERE status='WITHDRAWN'")==[['3']]
        passed.append('replay_does_not_republish_withdrawn_samples')
    finally:
        if created: db.query('DROP DATABASE '+app,False)
        if db.ensure_schema(False): db.query('DROP DATABASE `'+db.database+'`',False)
    return {'passed':passed,'count':len(passed),'temporary_databases_removed':True}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--defaults-file',required=True)
    print(json.dumps(verify(parser.parse_args().defaults_file)))
