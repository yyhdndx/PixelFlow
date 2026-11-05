import argparse
import hashlib
import socket
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'server/target/load-test'
BASE = 'http://127.0.0.1:18096'
DATABASE = 'pf_load_test'


def sql_text(value):
    return "CONVERT(X'%s' USING utf8mb4)" % str(value).encode().hex()


def mysql(statement, database=True):
    user = os.environ.get('LOAD_TEST_DB_USER')
    password = os.environ.get('LOAD_TEST_DB_PASSWORD')
    if not user or password is None:
        raise RuntimeError('Inject LOAD_TEST_DB_USER and LOAD_TEST_DB_PASSWORD; never use production credentials')
    env = dict(os.environ, MYSQL_PWD=password)
    command = ['mysql', '--protocol=TCP', '-h', '127.0.0.1', '-P', '3306', '-u', user,
               '--default-character-set=utf8mb4', '-N', '-B']
    if database:
        command.append(DATABASE)
    result = subprocess.run(command, input=statement, text=True, encoding='utf-8',
                            capture_output=True, env=env)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


def api(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


def start(cache, directory):
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 18096))
    java = Path(os.environ.get('LOAD_TEST_JAVA_HOME', 'C:/Program Files/Java/jdk-17')) / 'bin/java.exe'
    jar = ROOT / 'server/target/pixelflow-server-0.1.0.jar'
    if not java.is_file() or not jar.is_file():
        raise RuntimeError('Build the Java 17 JAR and set LOAD_TEST_JAVA_HOME')
    env = dict(os.environ, DB_URL='jdbc:mysql://127.0.0.1:3306/pf_load_test?connectionTimeZone=UTC&tinyInt1isBit=true',
               DB_USER=os.environ['LOAD_TEST_DB_USER'], DB_PASSWORD=os.environ['LOAD_TEST_DB_PASSWORD'],
               SERVER_ADDRESS='127.0.0.1', SERVER_PORT='18096', PIXELFLOW_SEED='false',
               PIXELFLOW_ADMIN_PASSWORD='', PIXELFLOW_JWT_SECRET=secrets.token_hex(32),
               PIXELFLOW_RATE_LIMIT_ENABLED='false', PIXELFLOW_LOCAL_CACHE_ENABLED=str(cache).lower(),
               PIXELFLOW_INTELLIGENCE_BASE_URL='', PIXELFLOW_PUBLIC_BASE_URL=BASE)
    log = open(directory / 'server.log', 'w', encoding='utf-8')
    process = subprocess.Popen([str(java), '-Xms512m', '-Xmx1024m',
                                '-Xlog:gc*:file=gc.log:time,level,tags', '-jar', str(jar),
                                '--server.tomcat.accesslog.enabled=true',
                                '--server.tomcat.accesslog.directory=' + str(directory / 'access'),
                                '--server.tomcat.accesslog.pattern=%m %U %s %D'],
                               env=env, cwd=directory, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(120):
        if process.poll() is not None:
            log.close()
            raise RuntimeError('Java failed; inspect owned server.log')
        try:
            api('/health')
            return process, log
        except OSError:
            time.sleep(0.5)
    process.terminate()
    process.wait(timeout=20)
    log.close()
    raise RuntimeError('Service startup timeout')


def stop(process, log):
    process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
    log.close()


def prepare():
    WORK.mkdir(parents=True, exist_ok=True)
    tables = mysql('SHOW TABLES')
    marker = WORK / 'ownership.json'
    if tables:
        if not marker.exists():
            raise RuntimeError('Refusing existing database without local ownership marker')
        expected = json.loads(marker.read_text())['user_id']
        if mysql("SELECT id FROM users WHERE username='load_template'") != expected:
            raise RuntimeError('Database ownership check failed')
        return
    if marker.exists():
        raise RuntimeError('Stale ownership marker; inspect manually before reinitialization')
    directory = WORK / 'prepare'
    directory.mkdir(exist_ok=True)
    process, log = start(True, directory)
    try:
        password = secrets.token_urlsafe(24)
        user = api('/api/auth/register', {'username': 'load_template', 'password': password, 'nickname': '压测模板'})
        hashed = mysql("SELECT password_hash FROM users WHERE username='load_template'")
        marker.write_text(json.dumps({'user_id': user['id']}))
        users = [{'id': str(uuid.uuid5(uuid.NAMESPACE_URL, 'pixelflow-load-user-' + str(i))),
                  'username': 'load_user_%04d' % i} for i in range(500)]
        users[0] = {'id': user['id'], 'username': 'load_template'}
        statements = ['START TRANSACTION;']
        for i, item in enumerate(users[1:], 1):
            statements.append('INSERT INTO users(id,username,password_hash,nickname,role) VALUES(%s,%s,%s,%s,\'CREATOR\');' %
                              tuple(sql_text(v) for v in [item['id'], item['username'], hashed, '压测用户%04d' % i]))
        now = 1700000000
        clip = json.dumps([{'type': 0, 'width': 100, 'height': 100, 'url': BASE + '/load-fixture.png'}])
        for i in range(1, 2001):
            author = users[(i - 1) % 500]['id']
            statements.append("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time,submit_time,review_time,publish_time,comment_count) VALUES(%d,%s,%s,%s,%s,FALSE,'PUBLISHED',%d,%d,%d,%d,20);" %
                              (i, sql_text(author), sql_text('压测作品%04d' % i), sql_text('本地固定正文 #压测 ' + '内容' * 50), sql_text(clip), now+i, now+i, now+i, now+i))
            values = []
            for j in range(20):
                values.append("(%d,%s,%s,'VISIBLE',%d)" % (i, sql_text(users[(i+j) % 500]['id']), sql_text('固定评论%d' % j), now+i))
            statements.append('INSERT INTO comments(post_id,author_id,content,status,create_time) VALUES' + ','.join(values) + ';')
        for i, item in enumerate(users):
            for offset in range(1, 11):
                statements.append("INSERT INTO interactions(user_id,kind,target_id) VALUES(%s,'follows',%s);" % (sql_text(item['id']), sql_text(users[(i+offset) % 500]['id'])))
            for j in range(25):
                statements.append("INSERT INTO notifications(recipient_id,type,actor_id,post_id,read_flag,create_time) VALUES(%s,'LIKE',%s,%d,FALSE,%d);" %
                                  (sql_text(item['id']), sql_text(users[(i+1) % 500]['id']), i+1, now+j))
        statements.append('COMMIT;')
        mysql('\n'.join(statements))
        (WORK / 'credentials.json').write_text(json.dumps({'password': password, 'users': users}, ensure_ascii=False), encoding='utf-8')
        backup_env = dict(os.environ, MYSQL_PWD=os.environ['LOAD_TEST_DB_PASSWORD'])
        with open(WORK / 'baseline.sql', 'w', encoding='utf-8') as output:
            subprocess.run(['mysqldump', '--protocol=TCP', '-h', '127.0.0.1', '-u', os.environ['LOAD_TEST_DB_USER'],
                            '--no-tablespaces', '--skip-lock-tables', '--skip-add-locks', '--no-create-info',
                            '--skip-add-drop-table', '--set-gtid-purged=OFF',
                            '--ignore-table=pf_load_test.flyway_schema_history', DATABASE],
                           stdout=output, stderr=subprocess.PIPE, env=backup_env, text=True, check=True)
        print('Prepared 500 users / 2000 posts / 40000 comments')
    finally:
        stop(process, log)


def reset():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 18096))
    expected = json.loads((WORK / 'ownership.json').read_text())['user_id']
    if mysql("SELECT id FROM users WHERE username='load_template'") != expected:
        raise RuntimeError('Database ownership changed; refusing reset')
    if not (WORK / 'baseline.sql').is_file() or not (WORK / 'credentials.json').is_file():
        raise RuntimeError('Preparation incomplete; inspect the exclusively owned database')
    tables = mysql('SHOW TABLES').splitlines()
    statements = ['SET FOREIGN_KEY_CHECKS=0;']
    for table in tables:
        if not table.replace('_', '').isalnum():
            raise RuntimeError('Unexpected table name')
        if table != 'flyway_schema_history':
            statements.append('TRUNCATE TABLE `%s`;' % table)
    statements.append('SET FOREIGN_KEY_CHECKS=1;')
    mysql('\n'.join(statements) + (WORK / 'baseline.sql').read_text(encoding='utf-8'))
    counts = mysql('SELECT (SELECT COUNT(*) FROM users),(SELECT COUNT(*) FROM posts),(SELECT COUNT(*) FROM comments),(SELECT COUNT(*) FROM comment_auto_replies);')
    if counts != '500\t2000\t40000\t0':
        raise RuntimeError('Dataset mismatch: ' + counts)


def sample(pid, directory, event):
    ps = '$ids=@(%d); while($true){$p=Get-Process -ErrorAction SilentlyContinue | Where-Object {$_.Id -in $ids -or $_.ProcessName -in @("mysqld","k6")}; @{time=[DateTimeOffset]::Now.ToUnixTimeMilliseconds();processes=@($p | Select-Object Id,ProcessName,CPU,WorkingSet64,PrivateMemorySize64)} | ConvertTo-Json -Depth 4 -Compress; Start-Sleep -Seconds 5}' % pid
    with open(directory / 'resources.jsonl', 'w') as output:
        process = subprocess.Popen(['powershell', '-NoProfile', '-Command', ps], stdout=output, stderr=subprocess.DEVNULL)
        event.wait()
        process.terminate()
        process.wait()


def run(args):
    prepare()
    if args.prepare_only:
        return
    k6 = os.environ.get('LOAD_TEST_K6', str(ROOT / 'server/target/load-tools/k6-v1.4.2-windows-amd64/k6.exe'))
    scenarios = [('read-50-cache', 50, 'read', True), ('read-100-cache', 100, 'read', True),
                 ('mixed-100-cache', 100, 'mixed', True), ('read-100-no-cache', 100, 'read', False)]
    results = []
    for name, vus, mode, cache in scenarios:
        reset()
        directory = WORK / (time.strftime('%Y%m%d-%H%M%S-') + name)
        directory.mkdir()
        process, log = start(cache, directory)
        event = threading.Event()
        sampler = threading.Thread(target=sample, args=(process.pid, directory, event))
        try:
            machine = subprocess.run(['powershell', '-NoProfile', '-Command',
                '@{os=(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,TotalVisibleMemorySize);cpu=@(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors)} | ConvertTo-Json -Depth 4'], capture_output=True, text=True).stdout
            metadata = {'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                        'machine': machine, 'mysql': mysql('SELECT VERSION();'),
                        'k6': subprocess.check_output([k6, 'version'], text=True).strip(),
                        'jvm_args': ['-Xms512m', '-Xmx1024m'], 'rate_limit_enabled': False,
                        'cache_enabled': cache, 'pid': process.pid, 'database': DATABASE,
                        'jar_sha256': hashlib.file_digest(open(ROOT / 'server/target/pixelflow-server-0.1.0.jar', 'rb'), 'sha256').hexdigest(),
                        'script_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT / 'tests/load').glob('*') if p.is_file()}}
            (directory / 'environment.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
            credentials = json.loads((WORK / 'credentials.json').read_text(encoding='utf-8'))
            users = []
            for user in credentials['users'][:100]:
                token = api('/api/auth/login', {'username': user['username'], 'password': credentials['password']})
                users.append(dict(user, token=token['accessToken']))
            manifest = directory / 'manifest.json'
            manifest.write_text(json.dumps({'base': BASE, 'database': DATABASE, 'users': users, 'password': credentials['password']}), encoding='utf-8')
            env = dict(os.environ, LOAD_MANIFEST=str(manifest), VUS=str(2 if args.smoke else vus), MODE=mode,
                       SUMMARY_FILE=str(directory / 'summary.json'), DURATION='10s' if args.smoke else '300s')
            with open(directory / 'k6.log', 'w') as output:
                warm = dict(env, DURATION='10s', VUS='2', MODE='read', SUMMARY_FILE=str(directory / 'warmup.json'))
                warm_result = subprocess.run([k6, 'run', str(ROOT / 'tests/load/business.js')], env=warm, stdout=output, stderr=subprocess.STDOUT)
                if warm_result.returncode:
                    raise RuntimeError('Warmup failed; inspect k6.log before formal load')
                sampler.start()
                result = subprocess.run([k6, 'run', '--out', 'json=' + str(directory / 'points.json'), str(ROOT / 'tests/load/business.js')], env=env, stdout=output, stderr=subprocess.STDOUT)
                auth_exit_code = 0
                if name == 'read-50-cache':
                    auth = dict(env, MODE='login', SUMMARY_FILE=str(directory / 'login-summary.json'))
                    auth_result = subprocess.run([k6, 'run', str(ROOT / 'tests/load/business.js')], env=auth, stdout=output, stderr=subprocess.STDOUT)
                    auth_exit_code = auth_result.returncode
            results.append({'scenario': name, 'exit_code': result.returncode, 'login_exit_code': auth_exit_code, 'directory': str(directory), 'smoke': args.smoke})
            (directory / 'run.json').write_text(json.dumps(results[-1], indent=2))
        finally:
            event.set()
            if sampler.is_alive():
                sampler.join(timeout=10)
            stop(process, log)
    print(json.dumps(results, indent=2))
    if any(item['exit_code'] or item['login_exit_code'] for item in results):
        raise SystemExit(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--smoke', action='store_true')
    arguments = parser.parse_args()
    run(arguments)
