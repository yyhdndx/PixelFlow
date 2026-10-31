"""Read bounded image headers to prepare valid clip sizes for the course demo."""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
import struct
import time
import urllib.parse
from pathlib import Path
from check_resources import PinnedHTTPS, resolve


def dimensions(data):
    if data.startswith(b'\x89PNG\r\n\x1a\n') and len(data) >= 24:
        return struct.unpack('>II', data[16:24])
    if data[:6] in (b'GIF87a', b'GIF89a') and len(data) >= 10:
        return struct.unpack('<HH', data[6:10])
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        kind = data[12:16]
        if kind == b'VP8X' and len(data) >= 30:
            return (1 + int.from_bytes(data[24:27], 'little'), 1 + int.from_bytes(data[27:30], 'little'))
        if kind == b'VP8 ' and len(data) >= 30 and data[23:26] == b'\x9d\x01\x2a':
            return tuple(n & 0x3fff for n in struct.unpack('<HH', data[26:30]))
        if kind == b'VP8L' and len(data) >= 25 and data[20] == 0x2f:
            bits = int.from_bytes(data[21:25], 'little')
            return ((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1)
    if data.startswith(b'\xff\xd8'):
        i = 2
        while i < len(data):
            if data[i] != 255: return None
            while i < len(data) and data[i] == 255: i += 1
            if i >= len(data): return None
            marker = data[i]; i += 1
            if marker in (0xd9, 0xda): return None
            if marker in (0x01, 0xd8) or 0xd0 <= marker <= 0xd7: continue
            if i + 2 > len(data): return None
            size = int.from_bytes(data[i:i+2], 'big')
            if size < 2: return None
            if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf):
                if size < 8 or i + 7 > len(data): return None
                h, w = struct.unpack('>HH', data[i+3:i+7])
                return w, h
            i += size
    return None


def probe_dimensions(url):
    started = time.monotonic()
    try:
        for _ in range(4):
            p = urllib.parse.urlsplit(url)
            if p.scheme not in ('http', 'https') or not p.hostname or p.username or p.password:
                raise ValueError('Invalid URL')
            port = p.port or (443 if p.scheme == 'https' else 80)
            if port not in (80, 443): raise ValueError('Invalid port')
            host = p.hostname.encode('idna').decode('ascii')
            address = resolve(host, port)
            conn = PinnedHTTPS(host, address, port) if p.scheme == 'https' else http.client.HTTPConnection(address, port, timeout=4)
            try:
                path = urllib.parse.quote(p.path or '/', safe="/%:@!$&'()*+,;=-._~")
                if p.query: path += '?' + urllib.parse.quote(p.query, safe="%/:@!$&'()*+,;=?-._~")
                conn.request('GET', path, headers={'Host': host, 'Range': 'bytes=0-524287', 'Accept-Encoding': 'identity', 'User-Agent': 'PixelFlow-Course-Research/1.0'})
                response = conn.getresponse()
                if response.status in (301,302,303,307,308):
                    location = response.getheader('Location')
                    if not location: raise ValueError('Missing redirect')
                    url = urllib.parse.urljoin(url, location)
                    continue
                if response.status not in (200,206): raise ValueError('Image unavailable')
                if (response.getheader('Content-Type') or '').split(';')[0].lower() not in ('image/jpeg','image/png','image/webp','image/gif'):
                    raise ValueError('Not image MIME')
                data = bytearray()
                while len(data) < 524288 and time.monotonic() - started < 20:
                    block = response.read(min(4096, 524288-len(data)))
                    if not block: break
                    data.extend(block)
                    size = dimensions(data)
                    if size:
                        w,h = size
                        if not (1 <= w <= 16384 and 1 <= h <= 16384 and w*h <= 50_000_000):
                            raise ValueError('Invalid dimensions')
                        return {'width':w, 'height':h, 'checked_at':int(time.time())}
                raise ValueError('No supported dimensions in bounded header')
            finally:
                conn.close()
        raise ValueError('Redirect limit')
    except Exception as exc:
        return {'error':type(exc).__name__}


def prepare(records, checks, output):
    raw = records.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    statuses = json.loads(checks.read_text(encoding='utf-8'))
    if statuses['records_sha256'] != digest: raise ValueError('Checksum mismatch')
    rows = [json.loads(line) for line in raw.decode('utf-8').splitlines()]
    if not 1 <= len(rows) <= 10000: raise ValueError('Invalid count')
    selected = [r for r in rows if statuses['records'][r['source_record_id']]['resource_status']=='REACHABLE' and len(r['source_url']) <= 2048]
    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(probe_dimensions,r['source_url']):r['source_record_id'] for r in selected}
        for future in concurrent.futures.as_completed(futures):
            results[futures[future]] = future.result()
            if len(results)%100==0: print(json.dumps({'dimensions_checked':len(results)}),flush=True)
    report = {'records_sha256':digest, 'count':len(results), 'usable':sum('width' in x for x in results.values()), 'records':results}
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='records'}),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--records',type=Path,required=True)
    parser.add_argument('--checks',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    prepare(args.records,args.checks,args.output)
