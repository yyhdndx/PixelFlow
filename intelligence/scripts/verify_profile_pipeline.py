"""端到端验证 Java -> Python -> 分析库的真实画像链路（issue 12）。

与 ``smoke_pipeline.py`` 的 H2/SQLite 快速烟雾不同，本脚本面向 **真实 MySQL**
验证，并在同一轮里跑通：

1. 两名普通用户的真实业务事件（点击、停留、服务端点赞）；
2. 真实的 ``scheduled_maintenance`` 包装器运行，以及一次有界中断后的续跑；
3. 当前画像/历史画像经 Java 代理读取，并与 Python 本人接口对照；
4. 通过 Java 修改偏好（个性化开关、主题抑制、作者屏蔽、作品排除）后的推荐预览；
5. 服务不可用、分析库错误与“无数据”三种状态可区分；
6. 构建产物（JAR）内的迁移集合、数据库成功执行的版本与源码清单一致，含合法版本空洞。

隔离与安全约束：

* 只接受本机 MySQL（127.0.0.1/localhost/::1），拒绝指向共享或云端实例；
* 每次运行创建两个名字带随机后缀且以 ``_test`` 结尾的库（Java 业务库 +
  Python 分析库），结束后删除本次创建的库，不触碰其他库和其他人的数据；
* 身份与运维密钥均为本次运行随机生成，只放在子进程环境变量里，不写入仓库、
  状态文件或输出；摘要中账号只保留前 8 位标识。

用法（仓库根目录）::

    intelligence/.venv/Scripts/python.exe intelligence/scripts/verify_profile_pipeline.py `
        --mysql-port 3307 --mysql-user pf_test --mysql-password <本地测试库密码>

前置条件：``server/target/pixelflow-server-0.1.0.jar`` 已构建，MySQL 测试账号对
``pf_issue12_%`` 有建库/删库权限。环境准备与结果记录见
``docs/testing/画像历史偏好真实链路验收.md``。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time
import traceback
import zipfile
from contextlib import closing
from datetime import datetime
from pathlib import Path
from urllib.parse import quote

import httpx
import pymysql

ROOT = Path(__file__).resolve().parents[2]
INTELLIGENCE = ROOT / "intelligence"
SERVER = ROOT / "server"
DEFAULT_JAR = SERVER / "target" / "pixelflow-server-0.1.0.jar"
LOOPBACK = {"127.0.0.1", "localhost", "::1"}
DB_PATTERN = r"^pf_issue12_(java|analytics)_[0-9a-f]{8}_test$"
ITEM_FIELDS = {"version", "rule_version", "generated_at", "window_days", "timezone",
               "cold_start", "topics", "media", "event_counts", "explanation"}
ACCOUNT_ID = re.compile(r"\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b", re.IGNORECASE)
# Flyway 允许版本号有空洞（例如当前 V1–V7、V9），因此只比对版本集合，
# 不比对最大版本、也不拿“迁移数量”当版本号。
JAR_MIGRATION_DIR = "BOOT-INF/classes/db/mysql"
SOURCE_MIGRATION_DIR = SERVER / "src/main/resources/db/mysql"
MIGRATION_FILE = re.compile(r"^V(\d+)__[A-Za-z0-9_.-]+\.sql$")


class ReportRedactor:
    """Sanitize the whole report, including serialized responses and log/traceback text."""

    def __init__(self, *secrets):
        self.secrets = {value for value in secrets if value}

    def add(self, *values):
        self.secrets.update(value for value in values if value)

    def text(self, value):
        for secret in sorted(self.secrets, key=len, reverse=True):
            value = value.replace(secret, "[REDACTED]")
        return ACCOUNT_ID.sub(lambda match: match.group()[:8] + "…", value)

    def report(self, value):
        if isinstance(value, dict):
            return {self.text(str(key)): self.report(item) for key, item in value.items()}
        if isinstance(value, (list, tuple)):
            return [self.report(item) for item in value]
        return self.text(value) if isinstance(value, str) else value


def write_report(runtime, report, redactor):
    report = redactor.report(report)
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    (runtime / "summary.json").write_text(serialized, encoding="utf-8")
    print(serialized)


class VerificationError(RuntimeError):
    """断言失败或环境不符合隔离要求。"""


class Evidence:
    """按步骤记录断言，供最终摘要与文档引用。"""

    def __init__(self, redactor=None):
        self.rows = []
        self.redactor = redactor or ReportRedactor()

    def check(self, step, name, ok, detail=""):
        detail = self.redactor.text(detail)
        self.rows.append({"step": step, "check": name, "ok": bool(ok), "detail": detail})
        if not ok:
            raise VerificationError(f"[{step}] {name} 未通过; {detail}")

    def equality(self, step, name, actual, expected):
        # Full profile/history payloads are unnecessary for a successful comparison.
        equal = actual == expected
        detail = "一致" if equal else f"期望 {expected!r}，实际 {actual!r}"
        self.check(step, name, equal, detail)

    def step_count(self):
        return len({row["step"] for row in self.rows})


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_http(client, url, process, timeout=90.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise VerificationError(f"进程在健康检查前退出：{url}")
        try:
            if client.get(url + "/health", timeout=2).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.25)
    raise VerificationError(f"服务启动超时：{url}")


class Api:
    """带上下文的 HTTP 调用封装，失败时给出方法、路径与响应片段。"""

    def __init__(self, base_url, headers=None):
        self.base = base_url
        self.client = httpx.Client(base_url=base_url, timeout=20, trust_env=False,
                                   follow_redirects=False, headers=headers or {})

    def request(self, method, path, expected=200, **kwargs):
        response = self.client.request(method, path, **kwargs)
        if response.status_code != expected:
            snippet = response.text[:300].replace("\n", " ")
            raise VerificationError(
                f"{method} {path} 期望 {expected}，实际 {response.status_code}: {snippet}")
        return response

    def get(self, path, **kwargs):
        return self.request("GET", path, **kwargs)

    def post(self, path, **kwargs):
        return self.request("POST", path, **kwargs)

    def put(self, path, **kwargs):
        return self.request("PUT", path, **kwargs)

    def close(self):
        self.client.close()


def user_api(base_url, token):
    return Api(base_url, {"Authorization": "Bearer " + token})


def mysql_connect(args, database=None):
    return closing(pymysql.connect(
        host=args.mysql_host, port=args.mysql_port, user=args.mysql_user,
        password=args.mysql_password, database=database, connect_timeout=10,
        autocommit=True, charset="utf8mb4"))


def guarded_database(name):
    if not re.match(DB_PATTERN, name):
        raise VerificationError(f"拒绝操作非本次命名的数据库：{name}")
    return f"`{name}`"


def create_databases(args, databases, created):
    with mysql_connect(args) as db, db.cursor() as cursor:
        for name in databases:
            # A name collision must fail; never claim an existing database as ours.
            cursor.execute(f"CREATE DATABASE {guarded_database(name)} "
                           "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin")
            created.append(name)


def drop_databases(args, databases):
    if not databases:
        return
    with mysql_connect(args) as db, db.cursor() as cursor:
        for name in databases:
            cursor.execute(f"DROP DATABASE IF EXISTS {guarded_database(name)}")


def mysql_scalar(args, database, sql, parameters=()):
    with mysql_connect(args, database) as db, db.cursor() as cursor:
        cursor.execute(sql, parameters)
        row = cursor.fetchone()
        return row[0] if row else None


def mysql_rows(args, database, sql, parameters=()):
    with mysql_connect(args, database) as db, db.cursor() as cursor:
        cursor.execute(sql, parameters)
        return list(cursor.fetchall())


def _migrations(items, origin):
    """把迁移的 (文件名, 内容) 折算成 {版本号: {name, sha256}}。"""
    result = {}
    for name, content in items:
        match = MIGRATION_FILE.match(name)
        if match:
            result[str(int(match.group(1)))] = {
                "name": name,
                "sha256": hashlib.sha256(content).hexdigest(),
            }
    if not result:
        raise VerificationError(f"{origin} 内没有可识别的 MySQL 迁移文件")
    return result


def jar_migrations(jar_path):
    """本次构建产物（JAR）里真正打包的 MySQL 迁移，含文件名与内容摘要。"""
    path = Path(jar_path)
    try:
        with zipfile.ZipFile(path) as archive:
            items = [(entry.rsplit("/", 1)[-1], archive.read(entry))
                     for entry in archive.namelist()
                     if entry.startswith(JAR_MIGRATION_DIR + "/") and not entry.endswith("/")]
    except (OSError, zipfile.BadZipFile) as error:
        raise VerificationError(f"无法读取构建产物 {path.name}：{error}") from None
    return _migrations(items, f"JAR {path.name}")


def source_migrations(directory=None):
    """当前检出的迁移清单；目录不存在时返回 None，表示无从比对。"""
    path = Path(directory or SOURCE_MIGRATION_DIR)
    if not path.is_dir():
        return None
    items = [(item.name, item.read_bytes()) for item in path.iterdir() if item.is_file()]
    return _migrations(items, f"源码目录 {path.name}")


def verify_migrations(expected, applied, source=None):
    """比对 JAR 内的迁移集合、数据库成功执行的版本以及源码清单。

    ``expected`` 为 JAR 的 {版本: {name, sha256}}，``applied`` 为
    flyway_schema_history 的 (version, success) 行。返回可脱敏的诊断摘要：
    版本集合、文件名与内容摘要差异，不输出 SQL 正文。
    """
    expected_versions = set(expected)
    applied_versions = set()
    failed = []
    for version, success in applied:
        key = str(int(str(version)))
        if success:
            applied_versions.add(key)
        else:
            failed.append(key)
    missing = sorted(expected_versions - applied_versions, key=int)
    unexpected = sorted(applied_versions - expected_versions, key=int)
    stale_jar = sorted(set(source) - expected_versions, key=int) if source is not None else []
    source_mismatch = sorted(expected_versions - set(source), key=int) if source is not None else []
    # 同一版本不等于同一迁移：文件名或内容变化都必须失败，否则旧 JAR 会被判成
    # 与当前源码一致，Flyway 校验也只覆盖旧 JAR 自身。
    renamed = []
    changed = []
    if source is not None:
        for version in sorted(expected_versions & set(source), key=int):
            jar_entry, source_entry = expected[version], source[version]
            if jar_entry["name"] != source_entry["name"]:
                renamed.append({"version": version,
                                "jar": jar_entry["name"], "source": source_entry["name"]})
            elif jar_entry["sha256"] != source_entry["sha256"]:
                changed.append(version)
    problems = []
    if failed:
        problems.append(f"数据库存在失败的迁移记录 {failed}")
    if missing:
        problems.append(f"数据库缺少构建产物中的迁移 {missing}")
    if unexpected:
        problems.append(f"数据库存在构建产物之外的迁移 {unexpected}，可能连到了别的库或旧 JAR")
    if stale_jar:
        problems.append(f"构建产物缺少源码中的迁移 {stale_jar}，请重新打包后再验证")
    if source_mismatch:
        problems.append(f"构建产物含源码中不存在的迁移 {source_mismatch}，JAR 与当前检出不一致")
    if renamed:
        versions = [item["version"] for item in renamed]
        problems.append(f"同一版本迁移文件名不一致 {versions}（JAR {[i['jar'] for i in renamed]}，"
                        f"源码 {[i['source'] for i in renamed]}）")
    if changed:
        problems.append(f"同一版本迁移内容与源码不一致 {changed}，请重新打包后再验证")
    return {
        "ok": not problems,
        "reason": "; ".join(problems),
        "expected_count": len(expected),
        "applied_count": len(applied_versions),
        "expected_versions": sorted(expected, key=int),
        "applied_versions": sorted(applied_versions, key=int),
        "missing": missing,
        "unexpected": unexpected,
        "failed": failed,
        "source_not_in_jar": stale_jar,
        "jar_not_in_source": source_mismatch,
        "renamed": renamed,
        "changed_content": changed,
        "source_checked": source is not None,
    }


def check_migrations(args, database, jar_path):
    """读取本次 JAR 与数据库的迁移记录，返回诊断摘要。"""
    expected = jar_migrations(jar_path)
    applied = mysql_rows(args, database,
                         "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")
    return verify_migrations(expected, applied, source_migrations())


class Services:
    """管理本次运行的 Java/Python 子进程与日志。"""

    def __init__(self, runtime, environment, jar):
        self.runtime = runtime
        self.environment = environment
        self.jar = jar
        self.processes = []
        self._logs = []

    def _spawn(self, command, cwd, name, env=None):
        log_path = self.runtime / f"{name}.log"
        handle = log_path.open("a", encoding="utf-8", errors="replace")
        self._logs.append(handle)
        process = subprocess.Popen(command, cwd=str(cwd), env=env or self.environment,
                                   stdout=handle, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.processes.append((name, process, log_path))
        return process

    def start_java(self, port, extra_env=None):
        env = dict(self.environment)
        env.update(SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(port))
        env.update(extra_env or {})
        return self._spawn(["java", "-jar", str(self.jar)], SERVER, "java", env)

    def start_python(self, port, name="python"):
        env = dict(self.environment)
        env["PYTHONPATH"] = str(INTELLIGENCE / "src")
        return self._spawn([sys.executable, "-m", "uvicorn",
                            "pixelflow_intelligence.api.app:create_app", "--factory",
                            "--host", "127.0.0.1", "--port", str(port)],
                           INTELLIGENCE, name, env)

    def stop(self, process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)

    def logs_tail(self, name, lines=20):
        for entry_name, _process, path in self.processes:
            if entry_name == name and path.exists():
                text = path.read_text(encoding="utf-8", errors="replace").splitlines()
                return "\n".join(text[-lines:])
        return ""

    def close(self):
        for _name, process, _path in reversed(self.processes):
            self.stop(process)
        for handle in self._logs:
            try:
                handle.close()
            except OSError:
                pass


def run_wrapper(environment, state_dir, extra=(), timeout=180):
    env = dict(environment)
    env["PYTHONPATH"] = str(INTELLIGENCE / "src")
    env["PIXELFLOW_MAINTENANCE_STATE_DIR"] = str(state_dir)
    command = [sys.executable, "-m", "pixelflow_intelligence.scheduled_maintenance",
               "--state-dir", str(state_dir), *extra]
    completed = subprocess.run(command, cwd=str(INTELLIGENCE), env=env, capture_output=True,
                               text=True, timeout=timeout, encoding="utf-8", errors="replace",
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    try:
        payload = json.loads(completed.stdout.strip().splitlines()[-1])
    except (IndexError, ValueError):
        raise VerificationError("scheduled_maintenance 未输出 JSON："
                                + completed.stderr[-400:]) from None
    return completed.returncode, payload


def read_state(state_dir):
    path = Path(state_dir) / "state.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def pick_posts(feed):
    """从公开 Feed 按主题关键字挑选样例（复用种子素材，不依赖固定 ID）。"""
    def first(*keywords):
        for post in feed:
            if any(word in post["title"] for word in keywords):
                return post
        raise VerificationError(f"公开 Feed 中找不到包含 {keywords} 的作品")

    return {"food": first("咖啡", "面包", "草莓", "早餐"),
            "pet": first("猫", "狗", "宠物")}


def wait_public_feed(api, expected=10, timeout=60):
    """种子内容是 ApplicationRunner，/health 200 时它可能仍在写入，因此轮询。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        feed = api.get("/feed/?count=50&accept_video_clip=true").json()["post_list"]
        if len(feed) >= expected:
            return feed
        time.sleep(0.5)
    raise VerificationError(f"公开 Feed 在 {timeout}s 内不足 {expected} 条，种子可能未完成")


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Verify the real Java->Python profile chain on isolated MySQL")
    parser.add_argument("--mysql-host", default=os.getenv("MYSQL_TEST_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", type=int, default=int(os.getenv("MYSQL_TEST_PORT", "3306")))
    parser.add_argument("--mysql-user", default=os.getenv("MYSQL_TEST_USER", ""))
    parser.add_argument("--mysql-password", default=os.getenv("MYSQL_TEST_PASSWORD", ""))
    parser.add_argument("--jar", default=str(DEFAULT_JAR))
    parser.add_argument("--artifacts-dir", default="")
    parser.add_argument("--keep-databases", action="store_true",
                        help="跳过最终清理；故障验证阶段仍会删除本次分析库")
    parser.add_argument("--timeout", type=float, default=180.0, help="单个服务启动超时秒数")
    return parser.parse_args(argv)


def register(api, name, redactor):
    password = secrets.token_hex(12)
    redactor.add(password)
    api.post("/api/auth/register", expected=201,
             json={"username": name, "password": password, "nickname": name})
    result = api.post("/api/auth/login", json={"username": name, "password": password}).json()
    redactor.add(result["accessToken"], result["refreshToken"])
    return result


def event_body(post_id, kind, duration=None):
    body = {"eventId": secrets.token_hex(12), "postId": post_id, "type": kind,
            "occurredAt": int(time.time()), "scene": "DETAIL"}
    if duration:
        body["durationMs"] = duration
    return body


def send_events(api, post_id, kinds):
    for kind, duration in kinds:
        api.post("/api/events", json=event_body(post_id, kind, duration))


def unsuppressed(profile):
    return {key: value for key, value in profile.items() if key != "suppressed_topics"}


def preflight(args):
    """运行前检查；放在 try 内，任何失败也要留下可分享的失败摘要。"""
    if args.mysql_host not in LOOPBACK:
        raise VerificationError("只允许本机 MySQL；拒绝在共享或云端实例上运行")
    if not args.mysql_user:
        raise VerificationError("需要 --mysql-user 或 MYSQL_TEST_USER")
    jar = Path(args.jar).expanduser().resolve()
    if not jar.is_file():
        raise VerificationError(f"缺少 Java 产物：{jar}；先执行 mvn -DskipTests package")
    jar_migrations(jar)  # 构建产物不可读或没有迁移时立即失败
    return jar


def main(argv=None):
    args = parse_args(argv)

    run_id = secrets.token_hex(4)
    java_db = f"pf_issue12_java_{run_id}_test"
    analytics_db = f"pf_issue12_analytics_{run_id}_test"
    runtime = Path(args.artifacts_dir).resolve() if args.artifacts_dir else \
        SERVER / "target" / f"issue12-verify-{run_id}"
    runtime.mkdir(parents=True, exist_ok=True)
    redactor = ReportRedactor(args.mysql_password)
    evidence = Evidence(redactor)
    created = []
    services = None
    started = time.monotonic()
    started_at = datetime.now().astimezone().isoformat(timespec="seconds")
    try:
        jar = preflight(args)
        create_databases(args, [java_db, analytics_db], created)
        evidence.check("env", "只创建本次命名的 _test 库", True, f"{java_db}, {analytics_db}")

        java_port, python_port = free_port(), free_port()
        java_url = f"http://127.0.0.1:{java_port}"
        python_url = f"http://127.0.0.1:{python_port}"
        export_token, sync_token = secrets.token_hex(32), secrets.token_hex(32)
        environment = os.environ.copy()
        environment.update({
            "DB_URL": (f"jdbc:mysql://{args.mysql_host}:{args.mysql_port}/{java_db}"
                       "?connectionTimeZone=UTC&tinyInt1isBit=true"
                       "&allowPublicKeyRetrieval=true&sslMode=DISABLED"),
            "DB_USER": args.mysql_user, "DB_PASSWORD": args.mysql_password,
            "PIXELFLOW_JWT_SECRET": secrets.token_hex(32),
            "PIXELFLOW_ANALYTICS_EXPORT_TOKEN": export_token,
            "PIXELFLOW_INTELLIGENCE_TOKEN": sync_token,
            "PIXELFLOW_SEED": "true", "PIXELFLOW_ADMIN_PASSWORD": "",
            "PIXELFLOW_PUBLIC_BASE_URL": java_url,
            "JAVA_BASE_URL": java_url,
            "ANALYTICS_DB": (f"mysql://{quote(args.mysql_user)}:{quote(args.mysql_password)}"
                             f"@{args.mysql_host}:{args.mysql_port}/{analytics_db}"),
            "PIXELFLOW_INTELLIGENCE_BASE_URL": python_url,
            "PIXELFLOW_MAINTENANCE_URL": python_url,
        })
        redactor.add(export_token, sync_token, environment["PIXELFLOW_JWT_SECRET"])
        services = Services(runtime, environment, jar)
        java = services.start_java(java_port)
        with httpx.Client(trust_env=False) as probe:
            wait_http(probe, java_url, java, args.timeout)
        python = services.start_python(python_port)
        with httpx.Client(trust_env=False) as probe:
            wait_http(probe, python_url, python, args.timeout)

        mysql_version = str(mysql_scalar(args, java_db, "SELECT VERSION()"))
        evidence.check("env", "真实 MySQL 8.4",
                       mysql_version.startswith("8.4"),
                       f"MySQL {mysql_version}, 运行目录 {runtime}")
        migration_check = check_migrations(args, java_db, jar)
        evidence.check(
            "env", "Flyway 迁移集合与构建产物一致",
            migration_check["ok"],
            f"预期 {migration_check['expected_count']} 份 {migration_check['expected_versions']}，"
            f"实际 {migration_check['applied_count']} 份 {migration_check['applied_versions']}，"
            f"源码清单核对={migration_check['source_checked']}"
            + ("" if migration_check["ok"] else f"，问题：{migration_check['reason']}"))

        public = Api(java_url)
        feed = wait_public_feed(public)
        posts = pick_posts(feed)
        evidence.check("env", "种子公开作品可用于真实事件", len(feed) >= 10,
                       f"{len(feed)} 条公开作品，样例 {posts['food']['post_id']}/{posts['pet']['post_id']}")

        # ---- 1. 冷启动、身份隔离与会话边界 ----------------------------------------
        token_a = register(public, f"verify_{run_id}_a", redactor)["accessToken"]
        token_b = register(public, f"verify_{run_id}_b", redactor)["accessToken"]
        second_a = register(public, f"verify_{run_id}_a2", redactor)
        token_a2 = second_a["accessToken"]
        a, b = user_api(python_url, token_a), user_api(python_url, token_b)
        ja, jb = user_api(java_url, token_a), user_api(java_url, token_b)
        identity_a = ja.get("/api/users/me").json()["id"]
        identity_b = jb.get("/api/users/me").json()["id"]
        evidence.check("cold_start", "两账号身份不同", identity_a != identity_b,
                       f"a={identity_a[:8]}…, b={identity_b[:8]}…")

        cold = a.get("/profiles/me").json()
        evidence.check("cold_start", "新用户 Python 画像为 version=0 冷启动",
                       cold["version"] == 0 and cold["cold_start"] is True and cold["topics"] == [],
                       f"version={cold['version']}, topics={len(cold['topics'])}")
        gateway = ja.get("/api/users/me/profile").json()
        evidence.equality("cold_start", "Java 当前画像代理与 Python 一致",
                          unsuppressed(gateway), cold)
        evidence.equality("cold_start", "Java 代理附带的抑制主题为空", gateway["suppressed_topics"], [])

        empty = ja.get("/api/users/me/profile/history?count=1")
        body = empty.json()
        evidence.check("cold_start", "从未生成画像的用户历史为空而非合成 version=0",
                       body["items"] == [] and body["has_more"] is False
                       and body["next_cursor"] is None, json.dumps(body, ensure_ascii=False))
        evidence.equality("cold_start", "历史响应禁止缓存",
                          empty.headers.get("cache-control"), "no-store")
        evidence.equality("cold_start", "Python 历史与 Java 代理同为空白",
                          a.get("/profiles/me/history").json()["items"], [])

        forged = a.get(f"/profiles/me?user_id={identity_b}").json()
        evidence.equality("cold_start", "伪造 user_id 不能切换身份", forged["user_id"], identity_a)
        forged_history = ja.get(f"/api/users/me/profile/history?count=1&user_id={identity_b}").json()
        evidence.equality("cold_start", "历史代理忽略伪造 user_id", forged_history["user_id"], identity_a)

        for label, caller in (("缺失令牌", {}), ("无效令牌", {"Authorization": "Bearer invalid"}),
                              ("运维密钥", {"Authorization": "Bearer " + sync_token})):
            python_status = httpx.get(python_url + "/profiles/me", headers=caller,
                                      trust_env=False).status_code
            java_status = httpx.get(java_url + "/api/users/me/profile/history?count=1",
                                    headers=caller, trust_env=False).status_code
            evidence.check("cold_start", f"{label}被拒绝", python_status == 401 and java_status == 401,
                           f"python={python_status}, java={java_status}")

        status = user_api(python_url, sync_token).get("/internal/status").json()
        evidence.check("cold_start", "分析库尚无事件与画像",
                       status["event_count"] == 0 and status["profile_users"] == 0
                       and status["source_bound"] is False,
                       f"event_count={status['event_count']}, users={status['profile_users']}")

        # ---- 2. 真实业务事件 + 一次完整维护 --------------------------------------
        send_events(ja, posts["food"]["post_id"], [("CLICK", None), ("DWELL", 45000)])
        send_events(jb, posts["pet"]["post_id"], [("CLICK", None), ("DWELL", 60000)])
        ja.put("/api/interactions/likes", json={"targetId": posts["food"]["post_id"], "active": True})
        jb.put("/api/interactions/likes", json={"targetId": posts["pet"]["post_id"], "active": True})
        replay = event_body(posts["food"]["post_id"], "CLICK")
        ja.post("/api/events", json=replay)
        duplicated = ja.post("/api/events", json=replay)
        evidence.check("maintenance", "重复 eventId 被识别为重复而非重复计分",
                       duplicated.json()["duplicate"] is True, str(duplicated.json()))

        state_dir = runtime / "state"
        code, payload = run_wrapper(environment, state_dir)
        evidence.check("maintenance", "首次维护完整结束",
                       code == 0 and payload["status"] == "complete"
                       and payload["result"]["imported"] == 7,
                       json.dumps({key: payload[key] for key in
                                   ("status", "cursor_after", "exit_code")}, ensure_ascii=False))
        saved = read_state(state_dir)
        evidence.check("maintenance", "状态目录记录 complete 且持有互斥锁文件",
                       saved["last_status"] == "complete" and saved["refresh_after"] == ""
                       and (state_dir / ".scheduled.lock").exists(),
                       f"state={saved}, dir={state_dir}")

        profile_a = a.get("/profiles/me").json()
        profile_b = b.get("/profiles/me").json()
        evidence.check("maintenance", "同步后生成版本 1 与可解释主题偏好",
                       profile_a["version"] == 1 and profile_a["topics"]
                       and profile_a["topics"][0]["name"] == "美食"
                       and profile_b["version"] == 1 and profile_b["topics"][0]["name"] == "宠物",
                       f"a={profile_a['topics'][:1]}, b={profile_b['topics'][:1]}")
        evidence.equality("maintenance", "点击/停留/服务端点赞均计入事件统计",
                          profile_a["event_counts"], {"CLICK": 2, "DWELL": 1, "LIKE": 1})

        # ---- 3. 有界中断与断点续跑 -----------------------------------------------
        for _ in range(55):
            send_events(ja, posts["food"]["post_id"], [("CLICK", None)])
        analyzer = user_api(python_url, sync_token)
        code, payload = run_wrapper(environment, state_dir, extra=("--max-sync-pages", "1"))
        evidence.check("resume", "单页上限触发 incomplete 与退出码 2",
                       code == 2 and payload["status"] == "incomplete"
                       and payload.get("result", {}).get("reason") == "SYNC_PAGE_LIMIT"
                       and payload["result"]["imported"] == 50,
                       json.dumps(payload.get("result", {}), ensure_ascii=False))
        midway = analyzer.get("/internal/status").json()
        evidence.check("resume", "中断后游标停在已提交页而不是回退",
                       read_state(state_dir)["last_status"] == "incomplete"
                       and midway["event_cursor"] == 57,
                       f"cursor={midway['event_cursor']}, events={midway['event_count']}")

        code, payload = run_wrapper(environment, state_dir)
        evidence.check("resume", "下一次维护从已提交游标继续并完成",
                       code == 0 and payload["status"] == "complete"
                       and payload["result"]["imported"] == 5,
                       json.dumps(payload.get("result", {}), ensure_ascii=False))

        code, payload = run_wrapper(environment, state_dir)
        evidence.check("resume", "空同步不重复计分且版本不变",
                       code == 0 and payload["result"]["imported"] == 0
                       and a.get("/profiles/me").json()["version"] == 3,
                       f"imported={payload['result']['imported']}, "
                       f"version={a.get('/profiles/me').json()['version']}")

        first_sync = analyzer.post("/internal/sync").json()
        second_sync = analyzer.post("/internal/sync").json()
        final_status = analyzer.get("/internal/status").json()
        evidence.check("resume", "重复调用同步接口幂等且游标单调",
                       first_sync["imported"] == 0 and second_sync["imported"] == 0
                       and final_status["event_count"] == 62 and final_status["event_cursor"] == 62,
                       f"event_count={final_status['event_count']}, "
                       f"cursor={final_status['event_cursor']}")

        # ---- 4. 历史快照分页 ------------------------------------------------------
        snapshots_before = final_status["profile_snapshots"]
        page = ja.get("/api/users/me/profile/history?count=1").json()
        second_page = ja.get("/api/users/me/profile/history?count=1&before_version=3").json()
        third_page = ja.get(f"/api/users/me/profile/history?count=1"
                            f"&before_version={second_page['next_cursor']}").json()
        evidence.check("history", "count=1 逐页降序且末页结束",
                       [item["version"] for item in page["items"]] == [3]
                       and page["has_more"] is True and page["next_cursor"] == "3"
                       and [item["version"] for item in second_page["items"]] == [2]
                       and [item["version"] for item in third_page["items"]] == [1]
                       and third_page["has_more"] is False and third_page["next_cursor"] is None,
                       f"pages={[page['items'][0]['version'], second_page['items'][0]['version'], third_page['items'][0]['version']]}")
        evidence.equality("history", "历史摘要只暴露契约字段", set(page["items"][0]), ITEM_FIELDS)
        evidence.equality("history", "Java 代理与 Python 历史逐项一致",
                          page["items"], a.get("/profiles/me/history?count=1").json()["items"])
        evidence.check("history", "历史条目保留生成时的主题快照",
                       page["items"][0]["topics"][0]["name"] == "美食",
                       str(page["items"][0]["topics"][:1]))
        evidence.equality("history", "另一账号历史相互隔离",
                          [item["version"] for item in
                           jb.get("/api/users/me/profile/history").json()["items"]], [1])
        for query, label in (("count=0", "count=0"), ("count=abc", "count=abc"),
                             ("count=1&count=1", "重复分页参数"), ("before_version=0", "before_version=0")):
            status_code = ja.client.get("/api/users/me/profile/history?" + query).status_code
            evidence.check("history", f"Java 拒绝非法分页（{label}）", status_code == 400, str(status_code))
        evidence.check("history", "Python 拒绝非法分页",
                       a.client.get("/profiles/me/history?count=0").status_code == 422, "422")

        after_reads = analyzer.get("/internal/status").json()["profile_snapshots"]
        evidence.equality("history", "读取历史不生成版本", after_reads, snapshots_before)
        send_events(ja, posts["food"]["post_id"], [("CLICK", None)])
        run_wrapper(environment, state_dir)
        evidence.check("history", "新增更高版本不影响旧游标页",
                       [item["version"] for item in
                        ja.get("/api/users/me/profile/history?count=1"
                               "&before_version=3").json()["items"]] == [2]
                       and a.get("/profiles/me").json()["version"] == 4,
                       "v4 出现后 before_version=3 仍返回 v2")

        # ---- 5. 偏好链路 ----------------------------------------------------------
        prefs_url = "/api/recommendations/me/preferences"
        initial = ja.get(prefs_url).json()
        evidence.check("preferences", "初始偏好为默认个性化且版本 0",
                       initial["personalized"] is True and initial["version"] == 0
                       and initial["excluded_post_ids"] == [] and initial["blocked_author_ids"] == []
                       and initial["suppressed_topics"] == [],
                       json.dumps(initial, ensure_ascii=False))

        preview = a.get("/recommendations/me/preview")
        preview_body = preview.json()
        evidence.check("preferences", "预览按画像给出个性化结果",
                       preview_body["strategy"] == "personalized"
                       and preview_body["profile_version"] == 4 and preview_body["items"]
                       and len(preview_body["steps"]) == 3,
                       f"items={len(preview_body['items'])}")
        evidence.equality("preferences", "预览响应禁止缓存",
                          preview.headers.get("cache-control"), "no-store")

        excluded = preview_body["items"][0]
        ja.put("/api/interactions/not-interested",
               json={"targetId": excluded["post_id"], "active": True})
        excluded_prefs = ja.get(prefs_url).json()
        evidence.check("preferences", "作品排除写入权威偏好并使预览移除该作品",
                       excluded["post_id"] in excluded_prefs["excluded_post_ids"]
                       and excluded_prefs["version"] == 1
                       and excluded["post_id"] not in
                       {item["post_id"] for item in
                        a.get("/recommendations/me/preview").json()["items"]},
                       f"version={excluded_prefs['version']}")

        blocked_author = next(item["author_id"] for item in preview_body["items"]
                              if item["author_id"] != excluded["author_id"])
        ja.put(f"/api/recommendations/me/blocked-authors/{blocked_author}",
               json={"blocked": True})
        blocked_prefs = ja.get(prefs_url).json()
        evidence.check("preferences", "作者屏蔽生效且偏好版本递增",
                       blocked_author in blocked_prefs["blocked_author_ids"]
                       and blocked_prefs["version"] == 2
                       and all(item["author_id"] != blocked_author for item in
                               a.get("/recommendations/me/preview").json()["items"]),
                       f"blocked={blocked_author[:8]}…")

        suppression = ja.put("/api/users/me/profile/topics/美食", json={"suppressed": True}).json()
        suppressed_profile = ja.get("/api/users/me/profile").json()
        evidence.check("preferences", "主题抑制从当前画像移除权重并标注",
                       suppressed_profile["topics"] == []
                       and suppressed_profile["suppressed_topics"] == ["美食"]
                       and suppressed_profile["version"] == 4
                       and suppression["suppressed_topics"] == ["美食"],
                       json.dumps(suppressed_profile["suppressed_topics"], ensure_ascii=False))
        suppressed_preview = a.get("/recommendations/me/preview").json()
        evidence.check("preferences", "抑制后个性化主题分为 0 但推荐仍可返回",
                       suppressed_preview["items"]
                       and all(item["components"]["topic"] == 0 for item in suppressed_preview["items"]),
                       f"items={len(suppressed_preview['items'])}")
        evidence.check("preferences", "偏好修改不改写历史快照",
                       ja.get("/api/users/me/profile/history?count=1").json()["items"][0]["topics"][0]["name"]
                       == "美食", "历史 v4 仍含 美食")

        disabled = ja.put(prefs_url, json={"personalized": False}).json()
        disabled_preview = a.get("/recommendations/me/preview").json()
        evidence.check("preferences", "关闭个性化降级为热门且不删除画像",
                       disabled["personalized"] is False and disabled["version"] == 4
                       and disabled_preview["reason"] == "personalization_disabled"
                       and disabled_preview["strategy"] == "popular"
                       and disabled_preview["profile_version"] == 0
                       and a.get("/profiles/me").json()["version"] == 4,
                       f"reason={disabled_preview['reason']}")

        ja.put(prefs_url, json={"personalized": True})
        ja.put("/api/users/me/profile/topics/美食", json={"suppressed": False})
        ja.put(f"/api/recommendations/me/blocked-authors/{blocked_author}", json={"blocked": False})
        ja.put("/api/interactions/not-interested",
               json={"targetId": excluded["post_id"], "active": False})
        restored = ja.get(prefs_url).json()
        restored_preview = a.get("/recommendations/me/preview").json()
        evidence.check("preferences", "撤销后恢复且偏好版本继续递增",
                       restored["personalized"] is True and restored["suppressed_topics"] == []
                       and restored["blocked_author_ids"] == [] and restored["excluded_post_ids"] == []
                       and any(item["components"]["topic"] > 0 for item in restored_preview["items"]),
                       f"version={restored['version']}")
        evidence.check("preferences", "Python 写端点按契约返回 410",
                       a.client.put("/recommendations/me/preferences",
                                    json={"personalized": False}).status_code == 410, "410")

        # ---- 6. 会话失效与三种失败状态可区分 --------------------------------------
        refreshed = public.post("/api/auth/refresh",
                                json={"refreshToken": second_a["refreshToken"]}).json()
        redactor.add(refreshed["accessToken"], refreshed["refreshToken"])
        stale_java = httpx.get(java_url + "/api/users/me",
                               headers={"Authorization": "Bearer " + token_a2},
                               trust_env=False).status_code
        stale_python = httpx.get(python_url + "/profiles/me",
                                 headers={"Authorization": "Bearer " + token_a2},
                                 trust_env=False).status_code
        evidence.check("failures", "刷新轮换后旧会话两端均按契约失效",
                       stale_java == 401 and stale_python == 401
                       and refreshed["accessToken"] != token_a2,
                       f"java={stale_java}, python={stale_python}")

        services.stop(python)
        down = ja.client.get("/api/users/me/profile")
        down_history = ja.client.get("/api/users/me/profile/history?count=1")
        # 当前画像接口的缓存契约只约束 200（java-api.yaml），错误响应不额外断言响应头。
        evidence.check("failures", "Python 不可用时当前画像为 503 而非空数据",
                       down.status_code == 503
                       and down.json().get("code") == "ANALYTICS_UNAVAILABLE",
                       f"{down.status_code} {down.text[:80]}")
        # 历史代理契约要求“所有响应禁止缓存”，错误响应同样需要 no-store。
        evidence.check("failures", "Python 不可用时历史代理为 503 且禁止缓存",
                       down_history.status_code == 503
                       and down_history.headers.get("cache-control") == "no-store",
                       f"{down_history.status_code} {down_history.text[:80]}")

        python = services.start_python(python_port, name="python-restored")
        with httpx.Client(trust_env=False) as probe:
            wait_http(probe, python_url, python, args.timeout)
        evidence.check("failures", "Python 恢复后画像链路可用",
                       ja.client.get("/api/users/me/profile").status_code == 200, "200")

        drop_databases(args, [analytics_db])
        db_error = a.client.get("/profiles/me")
        db_error_history = a.client.get("/profiles/me/history")
        evidence.check("failures", "分析库错误返回 503 而不是 200 空数组",
                       db_error.status_code == 503 and db_error_history.status_code == 503
                       and db_error_history.headers.get("cache-control") == "no-store",
                       f"{db_error.status_code}/{db_error_history.status_code}")
        java_db_error = ja.client.get("/api/users/me/profile/history?count=1")
        evidence.check("failures", "分析库错误经 Java 代理仍为 503",
                       java_db_error.status_code == 503, str(java_db_error.status_code))

        services.stop(java)
        identity_down = a.client.get("/profiles/me")
        evidence.check("failures", "Java 不可用时 Python 身份校验失败为 503",
                       identity_down.status_code == 503, str(identity_down.status_code))

        summary = {
            "run_id": run_id,
            "ok": True,
            "started_at": started_at,
            "duration_seconds": round(time.monotonic() - started, 2),
            "environment": {
                "mysql": mysql_version,
                "migrations": migration_check,
                "java": subprocess.run(["java", "-version"], capture_output=True,
                                       text=True).stderr.splitlines()[0],
                "python": sys.version.split()[0],
                "jar": str(jar),
            },
            "databases": {"java": java_db, "analytics": analytics_db},
            "accounts": {"a": identity_a[:8] + "…", "b": identity_b[:8] + "…"},
            "checks": evidence.rows,
            "steps": evidence.step_count(),
            "artifacts": str(runtime),
        }
        summary_path = runtime / "summary.json"
        write_report(runtime, summary, redactor)
        print(f"PASS: {len(evidence.rows)} 项断言通过，覆盖 {evidence.step_count()} 个阶段；"
              f"摘要 {summary_path}")
        return 0
    except Exception as error:  # noqa: BLE001 - 统一生成可供排查的失败摘要
        failure = {
            "run_id": run_id,
            "ok": False,
            "started_at": started_at,
            "duration_seconds": round(time.monotonic() - started, 2),
            "error": f"{type(error).__name__}: {error}",
            "checks": evidence.rows,
            "logs": {name: services.logs_tail(name) for name, _process, _path in services.processes}
            if services else {},
        }
        write_report(runtime, failure, redactor)
        print(redactor.text(traceback.format_exc()))
        return 1
    finally:
        if services is not None:
            services.close()
        if not args.keep_databases:
            try:
                drop_databases(args, created)
            except Exception as error:  # noqa: BLE001 - 清理失败不应掩盖主结果
                print(redactor.text(f"WARN: 清理测试库失败：{error}"), file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
