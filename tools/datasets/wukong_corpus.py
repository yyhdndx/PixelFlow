"""Prepare a bounded, private research corpus from the official WuKong archive.

No credentials, SQL execution, image downloads, embeddings or public posts are created here.
Run with Python 3.11+. Raw outputs must stay outside Git and outside public web directories.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import ipaddress
import json
import re
import time
import unicodedata
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ARCHIVE_URL = "https://drive.usercontent.google.com/download?id=15778VALmEHoC6SSpqaHiQU-VSkD7aXZV&export=download&confirm=t"
SOURCE_URL = "https://wukong-dataset.github.io/wukong-dataset/download.html"
TERMS_URL = "https://wukong-dataset.github.io/wukong-dataset/terms_of_use.html"
DATASET = "noah-wukong-100m"
LICENSE = "CC-BY-NC-SA-4.0 + WuKong additional terms (research only; no redistribution)"
RULE_VERSION = "wukong-caption-v1"
TOPICS = {
    "美食": ("美食", "咖啡", "面包", "早餐", "烘焙", "蛋糕", "甜点", "菜谱", "火锅", "草莓"),
    "宠物": ("猫咪", "小猫", "小狗", "宠物", "金毛", "柴犬", "喵星人"),
    "旅行": ("旅行", "旅游", "风景", "山水", "瀑布", "日落", "海边", "古镇", "雪山"),
    "阅读": ("阅读", "图书馆", "书籍", "读书", "书单", "文学"),
    "数码": ("键盘", "编程", "电脑", "数码", "相机", "摄影", "耳机", "处理器"),
    "运动": ("篮球", "骑行", "运动", "跑步", "足球", "健身", "游泳", "登山"),
    "园艺": ("多肉", "植物", "花卉", "园艺", "盆栽", "花丛", "盆景", "养花"),
}


class RangeArchive(io.RawIOBase):
    """Seekable HTTP archive with strict range/byte limits; never fetch all 12GB."""
    def __init__(self, url=ARCHIVE_URL, max_bytes=32 * 1024 * 1024):
        self.url, self.max_bytes = url, max_bytes
        self.position = self.downloaded = 0
        self.size = None
        self.block_size = 256 * 1024
        self.cache = collections.OrderedDict()
        self._fetch(0, 0)

    def readable(self): return True
    def seekable(self): return True
    def tell(self): return self.position

    def _fetch(self, start, end):
        wanted = end - start + 1
        if self.downloaded + wanted > self.max_bytes:
            raise ValueError("Archive download budget exhausted")
        # Include the range in the cache key: some HTTP caches ignore the Range header.
        url = self.url + "&pf_range=" + str(start) + "-" + str(end)
        request = urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}", "Accept-Encoding": "identity"})
        with urllib.request.urlopen(request, timeout=30) as response:
            expected = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers.get("Content-Range", ""))
            if response.status != 206 or not expected or tuple(map(int, expected.groups()[:2])) != (start, end):
                raise ValueError("Server did not honor bounded Range request")
            total = int(expected.group(3))
            if self.size is not None and total != self.size:
                raise ValueError("Archive size changed during sampling")
            self.size = total
            data = response.read(wanted + 1)
            self.downloaded += len(data)
            if len(data) != wanted:
                raise ValueError("Truncated or oversized archive range")
            return data

    def seek(self, offset, whence=0):
        position = offset if whence == 0 else self.position + offset if whence == 1 else self.size + offset
        if not 0 <= position <= self.size:
            raise ValueError("Invalid archive seek")
        self.position = position
        return position

    def read(self, size=-1):
        size = self.size - self.position if size < 0 else min(size, self.size - self.position)
        if size > self.max_bytes:
            raise ValueError("Refusing an unbounded archive read")
        result = bytearray()
        while size:
            block = self.position // self.block_size
            if block not in self.cache:
                start = block * self.block_size
                self.cache[block] = self._fetch(start, min(self.size - 1, start + self.block_size - 1))
                if len(self.cache) > 8:
                    self.cache.popitem(last=False)
            data = self.cache[block]
            begin = self.position % self.block_size
            take = min(size, len(data) - begin)
            result.extend(data[begin:begin + take])
            self.position += take
            size -= take
        return bytes(result)


def normalize(row, member, row_number):
    caption = row.get("caption")
    source = row.get("url")
    if not isinstance(caption, str) or not isinstance(source, str):
        return None
    if len(caption.encode("utf-8")) > 65535: return None
    text = " ".join(unicodedata.normalize("NFKC", caption).split())
    if not 6 <= len(text) <= 5000 or not re.search(r"[\u4e00-\u9fff]", text):
        return None
    if any(unicodedata.category(c).startswith("C") for c in text):
        return None
    # Basic screening for this research sample, not an assertion of comprehensive moderation.
    if re.search(r"(?:\b1[3-9]\d{9}\b|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|裸聊|色情|成人视频|招嫖)", text):
        return None
    source = source.strip()
    if len(source) > 4096 or any(c.isspace() or ord(c) < 32 for c in source):
        return None
    try:
        parsed = urllib.parse.urlsplit(source)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
            return None
        if parsed.port not in (None, 80, 443): return None
        host = parsed.hostname.rstrip(".").lower()
        if "." not in host or host.endswith((".localhost", ".local", ".internal")): return None
        try:
            if not ipaddress.ip_address(host).is_global: return None
        except ValueError:
            pass
    except ValueError:
        return None
    topics = [name for name, terms in TOPICS.items() if any(term in text for term in terms)] or ["其他"]
    return {
        "source_dataset": DATASET, "source_file": member, "source_row": row_number,
        "source_record_id": hashlib.sha256(f"{DATASET}\n{member}\n{row_number}".encode()).hexdigest(),
        "content_hash": hashlib.sha256((source + "\n" + text).encode()).hexdigest(),
        "source_url": source, "original_caption": caption, "title": text[:120], "content": text,
        "media_type": "image", "width": None, "height": None,
        "clips": [{"type": 0, "url": source, "width": None, "height": None}],
        "topics": topics, "rule_version": RULE_VERSION,
        "visibility": "RESEARCH_ONLY", "resource_status": "UNVERIFIED",
        "published_at": None, "author_id": None, "license": LICENSE,
    }


def sql_text(value):
    if value is None: return "NULL"
    return "CONVERT(X'" + str(value).encode("utf-8").hex() + "' USING utf8mb4)"


def sample(output: Path, count: int):
    if not 1 <= count <= 10000: raise ValueError("count must be in 1..10000")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()): raise ValueError("Output directory must be empty")
    rows, seen, scanned = [], set(), 0
    archive = RangeArchive()
    with zipfile.ZipFile(archive) as zipped:
        members = sorted((n for n in zipped.namelist() if re.search(r"wukong_100m_\d+\.csv$", n)),
                         key=lambda n: int(re.search(r"(\d+)\.csv$", n).group(1)))
        if len(members) < 4: raise ValueError("Unexpected archive layout")
        selected = [members[i * len(members) // 4] for i in range(4)]
        for part, member in enumerate(selected):
            quota = count // 4 + (part < count % 4)
            if quota == 0: continue
            scan_limit = max(10000, quota * 40)
            accepted = 0
            with zipped.open(member) as raw, io.TextIOWrapper(raw, encoding="utf-8-sig", newline="") as text:
                reader = csv.DictReader(text)
                if not {"url", "caption"}.issubset(reader.fieldnames or []):
                    raise ValueError("Expected url and caption CSV columns")
                for row_number, raw_row in enumerate(reader, 1):
                    scanned += 1
                    row = normalize(raw_row, member, row_number)
                    if row is not None and row["topics"] != ["其他"] and row["content_hash"] not in seen:
                        seen.add(row["content_hash"]); rows.append(row); accepted += 1
                    if accepted >= quota: break
                    if row_number >= scan_limit: raise ValueError("Sample scan limit reached")
            if accepted != quota: raise ValueError("Insufficient valid rows")
    data = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows).encode("utf-8")
    (output / "records.jsonl").write_bytes(data)
    report = {"dataset": DATASET, "source_url": SOURCE_URL, "archive_url": ARCHIVE_URL,
              "terms_url": TERMS_URL, "license": LICENSE, "sample_rule": "First valid unique captions matching the project's seven topic rules from four evenly spaced CSV shards; not a random or representative evaluation set",
              "rule_version": RULE_VERSION, "requested": count, "accepted": len(rows), "scanned": scanned,
              "archive_size": archive.size, "downloaded_bytes": archive.downloaded, "source_files": selected,
              "records_sha256": hashlib.sha256(data).hexdigest(), "created_at": int(time.time()),
              "topics": dict(collections.Counter(topic for row in rows for topic in row["topics"])),
              "image_downloads": 0, "resources_verified": 0, "public_posts_created": 0}
    (output / "manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, default=1000)
    args = parser.parse_args()
    print(json.dumps(sample(args.output, args.count), ensure_ascii=False))
