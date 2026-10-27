"""Real process restart + DocumentsUI acceptance against a host-owned HTTP fixture.

Only installs com.PixelFlow.app.acceptance on an emulator. Never contacts the cloud.
Build the isolated APK as documented in docs/testing/F07-部署核对与设备验收-2026-10-27.md.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import struct
import subprocess
import threading
import time
from urllib.parse import urlparse
import uuid
import xml.etree.ElementTree as ET
import zipfile
import zlib

APP = "com.PixelFlow.app.acceptance"
PORT = 18765
ORIGIN = f"http://127.0.0.1:{PORT}"


def sample_png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    rows = b"".join(b"\0" + bytes([108, 92, 196, 255]) * 128 for _ in range(128))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 128, 128, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


class Fixture:
    def __init__(self):
        self.calls = Counter()
        self.avatar = ""
        self.image = sample_png()
        self.requests = []

    def handler(self):
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def do_GET(self): self.handle_request()
            def do_POST(self): self.handle_request()
            def do_PUT(self): self.handle_request()
            def do_DELETE(self): self.handle_request()

            def handle_request(self):
                path = urlparse(self.path).path
                size = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(size) if size else b""
                fixture.calls[(self.command, path)] += 1
                authenticated = self.headers.get("Authorization") == "Bearer acceptance-access"
                fixture.requests.append({"method": self.command, "path": path, "authenticated": authenticated})
                code, value = 200, {}
                if path == "/sample-avatar.png":
                    self.send_response(200)
                    self.send_header("Content-Type", "image/png")
                    self.send_header("Content-Length", str(len(fixture.image)))
                    self.end_headers(); self.wfile.write(fixture.image)
                    return
                if path == "/api/auth/login":
                    data = json.loads(body)
                    if data != {"username": "f07_alice", "password": "acceptance-only-password"}:
                        code = 401
                    else:
                        value = {"accessToken": "acceptance-access", "refreshToken": "acceptance-refresh"}
                elif path == "/feed/":
                    value = {"status_code": 0, "has_more": 0, "next_cursor": None, "post_list": []}
                elif path.startswith("/api/") and not authenticated:
                    code = 401
                elif path == "/api/users/me":
                    value = {"id": "alice", "username": "f07_alice", "nickname": "F07 Alice", "bio": "Device acceptance fixture", "avatar": fixture.avatar}
                elif path == "/api/users/me/stats":
                    value = {"user_id": "alice", "post_count": 0, "follower_count": 0, "following_count": 0}
                elif path == "/api/media" and self.command == "POST":
                    if b"image/jpeg" not in body:
                        code = 400
                    value = {"mediaId": "11111111-1111-1111-1111-111111111111", "url": ORIGIN + "/sample-avatar.png", "contentType": "image/jpeg", "type": 0, "width": 128, "height": 128, "size": len(body)}
                elif path == "/api/users/me/avatar":
                    fixture.avatar = "" if self.command == "DELETE" else ORIGIN + "/sample-avatar.png"
                    value = {"user_id": "alice", "avatar": fixture.avatar}
                elif path in ("/api/auth/logout", "/api/events"):
                    pass
                else:
                    code = 404
                encoded = json.dumps(value).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers(); self.wfile.write(encoded)
        return Handler


class Device:
    def __init__(self, adb, serial, out):
        self.adb, self.serial, self.out = adb, serial, out

    def run(self, *args, check=True):
        return subprocess.run([str(self.adb), "-s", self.serial, *args], capture_output=True, check=check, timeout=45).stdout

    def shell(self, *args):
        return self.run("shell", *args).decode("utf-8", errors="replace").strip()

    def tree(self):
        self.shell("uiautomator", "dump", "/sdcard/f07-window.xml")
        raw = self.shell("cat", "/sdcard/f07-window.xml")
        return ET.fromstring(raw)

    def find(self, resource=None, text=None, description=None, filename=None, timeout=15, scroll=False):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            tree = self.tree()
            for node in tree.iter("node"):
                match = ((resource is None or node.get("resource-id", "").endswith(":id/" + resource))
                         and (text is None or node.get("text") == text)
                         and (description is None or node.get("content-desc") == description)
                         and (filename is None or node.get("text") == filename
                              or node.get("content-desc", "").startswith(filename + ",")))
                bounds = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
                if match and len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    return node
            if scroll:
                self.shell("input", "swipe", "540", "1500", "540", "600", "300")
            time.sleep(.2)
        raise AssertionError(f"UI node missing: resource={resource}, text={text}, description={description}, filename={filename}")

    def click(self, **selector):
        node = self.find(**selector)
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def shot(self, name):
        (self.out / name).write_bytes(self.run("exec-out", "screencap", "-p"))

    def type_text(self, resource, value):
        # A cold IME can compose/repeat the first character; verify input before submitting.
        for attempt in range(3):
            self.click(resource=resource)
            time.sleep(.4)
            self.shell("input", "keycombination", "113", "29")  # Ctrl+A
            self.shell("input", "text", value)
            try:
                self.find(resource=resource, text=value, timeout=4)
                return
            except AssertionError:
                if attempt == 2:
                    raise

    def launch_profile(self):
        self.shell("am", "start", "-n", APP + "/com.PixelFlow.app.SplashActivity")
        self.find(resource="nav_profile", timeout=25)
        # The navigation is exposed to accessibility before its entrance animation finishes.
        time.sleep(1.5)
        self.click(resource="nav_profile")
        self.find(resource="tv_user_name")

    def restart(self):
        old_pid = self.shell("pidof", APP)
        self.shell("am", "force-stop", APP)
        assert not self.run("shell", "pidof", APP, check=False).strip()
        self.launch_profile()
        new_pid = self.shell("pidof", APP)
        assert old_pid and new_pid and old_pid != new_pid
        return {"before_pid": old_pid, "after_pid": new_pid}


def wait_for(assertion, timeout=15):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if assertion(): return
        time.sleep(.15)
    raise AssertionError("HTTP fixture expectation timed out")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This isolated acceptance runner only supports an emulator")
    with zipfile.ZipFile(args.apk) as apk:
        assert apk.read("assets/api.properties").decode().strip() == "url=" + ORIGIN
    aapt = sorted((args.adb.parent.parent / "build-tools").glob("*/aapt.exe"))[-1]
    package = subprocess.check_output([str(aapt), "dump", "badging", str(args.apk)], text=True, encoding="utf-8")
    assert re.search(r"package: name='" + re.escape(APP) + "'", package), "Refuse to replace the normal application"
    args.out.mkdir(parents=True, exist_ok=True)
    fixture = Fixture()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), fixture.handler())
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    device = Device(args.adb, args.serial, args.out)
    result = {"started_utc": datetime.now(timezone.utc).isoformat(), "serial": args.serial, "application_id": APP,
              "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(), "checks": [], "passed": False}
    try:
        device.run("install", "-r", str(args.apk))
        device.shell("pm", "clear", APP)  # Only the dedicated acceptance package.
        device.run("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        device.launch_profile()
        device.click(resource="btn_edit_profile")
        device.type_text("account_username", "f07_alice")
        device.type_text("account_password", "acceptance-only-password")
        device.shell("input", "keyevent", "4")
        device.click(resource="account_submit")
        device.find(resource="tv_user_name", text="F07 Alice")
        device.shot("01-signed-in.png")
        print("PASS: login through UI", flush=True)

        restarted = device.restart()
        device.find(resource="tv_user_name", text="F07 Alice")
        assert fixture.calls[("POST", "/api/auth/login")] == 1
        result["checks"].append({"name": "force_stop_restart_restores_login", **restarted})
        device.shot("02-restored-session.png")
        print("PASS: new process restored encrypted session without another login", flush=True)

        filename = "pixelflow-acceptance-" + uuid.uuid4().hex[:8] + ".png"
        local_image = args.out / filename; local_image.write_bytes(fixture.image)
        device.run("push", str(local_image), "/sdcard/Download/" + filename)
        device.shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Download/" + filename)
        device.click(resource="iv_avatar")
        device.click(text="从相册选择")
        # Actual ACTION_OPEN_DOCUMENT / DocumentsUI, not a simulated activity result.
        try:
            device.find(filename=filename, timeout=5)
        except AssertionError:
            device.click(description="Show roots")
            device.click(text="Downloads")
        device.shot("03-system-picker.png")
        device.click(filename=filename, timeout=20)
        wait_for(lambda: fixture.calls[("PUT", "/api/users/me/avatar")] == 1)
        device.find(resource="tv_user_name", text="F07 Alice")
        assert fixture.calls[("POST", "/api/media")] == 1
        result["checks"].append({"name": "system_picker_upload_and_set", "uploads": 1, "sets": 1})
        device.shot("04-avatar-updated.png")
        print("PASS: real system picker uploaded and set avatar once", flush=True)

        device.click(resource="btn_logout", scroll=True)
        device.click(resource="button1")
        wait_for(lambda: fixture.calls[("POST", "/api/auth/logout")] == 1)
        restarted = device.restart()
        device.find(resource="btn_edit_profile", text="登录 / 注册")
        assert fixture.calls[("POST", "/api/auth/login")] == 1
        result["checks"].append({"name": "logout_then_restart_stays_guest", **restarted})
        device.shot("05-guest-after-restart.png")
        result["passed"] = True
        print("PASS: logout survives force-stop and restart", flush=True)
    except Exception as error:
        result["error"] = str(error)
        device.shot("failure.png")
        try:
            (args.out / "failure-ui.xml").write_text(ET.tostring(device.tree(), encoding="unicode"), encoding="utf-8")
        except Exception:
            pass
        raise
    finally:
        result["requests"] = fixture.requests
        (args.out / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        device.shell("am", "force-stop", APP)
        device.run("reverse", "--remove", f"tcp:{PORT}", check=False)
        server.shutdown(); server.server_close()


if __name__ == "__main__":
    main()
