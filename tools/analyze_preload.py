#!/usr/bin/env python3
"""解析 PixelFlow 图片预加载性能日志，产出可读报告。

用法:
    python analyze_preload.py <logfile> [<logfile2> ...]

日志来源:
    adb logcat -d -s PF_ImgTiming:D PF_Preload:D > log.txt

输出:
    - 各 DataSource 的占比与耗时分布
    - 预加载命中率（preloaded=1 的图命中内存/磁盘/网络的比例）
    - 预加载重复率
    - 多文件对比（ON vs OFF）
"""
import re
import sys
import statistics
from collections import defaultdict

# 输出统一 UTF-8，避免 Windows 控制台 GBK 乱码
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

IMG_RE = re.compile(r'source=(\w+)\s+ms=(\d+)\s+preloaded=(\d)\s+url=(.*)')
PRE_RE = re.compile(r'priority=(\d+)\s+url=(.*)')


def read_text(path):
    raw = open(path, 'rb').read()
    for enc in ('utf-8', 'utf-16', 'utf-16-le', 'utf-16-be'):
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, UnicodeError):
            continue
    return raw.decode('utf-8', errors='replace')


def parse(path):
    imgs = []       # list of (source, ms, preloaded, url)
    preloads = []   # list of (priority, url)
    for line in read_text(path).splitlines():
        if 'PF_ImgTiming' in line:
            m = IMG_RE.search(line)
            if m:
                imgs.append((m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)))
        elif 'PF_Preload' in line:
            m = PRE_RE.search(line)
            if m:
                preloads.append((int(m.group(1)), m.group(2)))
    return imgs, preloads


def pct(n, total):
    return f"{n} ({n / total * 100:.1f}%)" if total else "0"


def stats(ms_list):
    if not ms_list:
        return "—"
    ms_list = sorted(ms_list)
    mean = sum(ms_list) / len(ms_list)
    p50 = ms_list[len(ms_list) // 2]
    p95 = ms_list[min(len(ms_list) - 1, int(len(ms_list) * 0.95))]
    return f"mean={mean:.0f}ms p50={p50}ms p95={p95}ms max={ms_list[-1]}ms"


def report(name, imgs, preloads):
    total = len(imgs)
    by_source = defaultdict(list)
    for source, ms, preloaded, url in imgs:
        by_source[source].append(ms)

    preloaded_imgs = [(s, m) for s, m, p, u in imgs if p == 1]
    preloaded_by_source = defaultdict(int)
    for s, m in preloaded_imgs:
        preloaded_by_source[s] += 1

    preload_urls = defaultdict(int)
    for priority, url in preloads:
        preload_urls[url] += 1
    dup = sum(c - 1 for c in preload_urls.values() if c > 1)

    lines = [f"\n## {name}", f"显示图片总数: {total}", f"预加载事件总数: {len(preloads)}，去重后 URL 数: {len(preload_urls)}，重复预加载次数: {dup}"]
    lines.append("\n### 各 DataSource 分布")
    lines.append("| source | 数量 | 占比 | 耗时分布 |")
    lines.append("|---|---|---|---|")
    for s in sorted(by_source, key=lambda k: -len(by_source[k])):
        lines.append(f"| {s} | {pct(len(by_source[s]), total)} | — | {stats(by_source[s])} |")

    lines.append("\n### 预加载命中率（preloaded=1 的图）")
    lines.append(f"预加载过的图: {len(preloaded_imgs)} / {total} = {len(preloaded_imgs) / total * 100:.1f}%" if total else "0")
    for s, c in sorted(preloaded_by_source.items(), key=lambda kv: -kv[1]):
        lines.append(f"- {s}: {c} ({c / len(preloaded_imgs) * 100:.1f}%)" if preloaded_imgs else f"- {s}: 0")
    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    for path in sys.argv[1:]:
        imgs, preloads = parse(path)
        print(report(path, imgs, preloads))


if __name__ == '__main__':
    main()
