"""Probe public image headers with pinned DNS and bounded reads. No image files are saved."""
import argparse
import concurrent.futures
import functools
import hashlib
import http.client
import ipaddress
import json
import socket
import ssl
import time
import urllib.parse
from pathlib import Path


@functools.lru_cache(maxsize=2048)
def resolve(host, port):
    addresses = list(dict.fromkeys(row[4][0] for row in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)))
    if not addresses or any(not ipaddress.ip_address(address).is_global for address in addresses):
        raise ValueError("Non-public destination")
    return addresses[0]


class PinnedHTTPS(http.client.HTTPSConnection):
    def __init__(self, host, address, port):
        super().__init__(host, port=port, timeout=4, context=ssl.create_default_context())
        self.address = address

    def connect(self):
        sock = socket.create_connection((self.address, self.port), self.timeout)
        try:
            self.sock = self._context.wrap_socket(sock, server_hostname=self.host)
        except Exception:
            sock.close()
            raise


def probe(url):
    result = {"resource_status": "UNREACHABLE", "http_status": None, "content_type": None,
              "checked_at": int(time.time()), "check_method": "HTTP_HEADER_AND_PREFIX", "error": None}
    try:
        for _ in range(4):
            parsed = urllib.parse.urlsplit(url)
            if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError("Invalid URL")
            port = parsed.port or (443 if parsed.scheme == "https" else 80)
            if port not in {80, 443}: raise ValueError("Unsupported port")
            host = parsed.hostname.encode("idna").decode("ascii")
            address = resolve(host, port)
            connection = PinnedHTTPS(host, address, port) if parsed.scheme == "https" else http.client.HTTPConnection(address, port, timeout=4)
            try:
                path = urllib.parse.quote(parsed.path or "/", safe="/%:@!$&'()*+,;=-._~")
                if parsed.query: path += "?" + urllib.parse.quote(parsed.query, safe="%/:@!$&'()*+,;=?-._~")
                # Bounded GET works on hosts that reject HEAD. Close after headers/1KiB.
                connection.request("GET", path, headers={"Host": host, "Range": "bytes=0-1023", "User-Agent": "PixelFlow-Course-Research/1.0", "Accept-Encoding": "identity"})
                response = connection.getresponse()
                result["http_status"] = response.status
                if response.status in {301, 302, 303, 307, 308}:
                    location = response.getheader("Location")
                    if not location: break
                    url = urllib.parse.urljoin(url, location)
                    continue
                content_type = (response.getheader("Content-Type") or "").split(";", 1)[0].lower().strip()
                result["content_type"] = content_type[:100]
                if response.status in {200, 206} and content_type in {"image/jpeg", "image/png", "image/webp", "image/gif"}:
                    prefix = response.read(1024)
                    signature = prefix.startswith((b"\xff\xd8\xff", b"\x89PNG\r\n\x1a\n", b"GIF87a", b"GIF89a")) or (prefix[:4] == b"RIFF" and prefix[8:12] == b"WEBP")
                    if signature: result["resource_status"] = "REACHABLE"
                    else: result["error"] = "NOT_IMAGE_SIGNATURE"
                else:
                    result["error"] = "HTTP_OR_CONTENT_TYPE"
                break
            finally:
                connection.close()
        else:
            result["error"] = "REDIRECT_LIMIT"
    except ValueError:
        result["resource_status"] = "BLOCKED"
        result["error"] = "INVALID_OR_NON_PUBLIC_URL"
    except Exception as error:
        result["error"] = type(error).__name__
    return result


def check(records_path, output):
    raw = records_path.read_bytes()
    rows = [json.loads(line) for line in raw.decode("utf-8").splitlines()]
    if not 1 <= len(rows) <= 10000: raise ValueError("Invalid corpus size")
    statuses = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(probe, row["source_url"]): row["source_record_id"] for row in rows}
        for future in concurrent.futures.as_completed(futures):
            statuses[futures[future]] = future.result()
            if len(statuses) % 100 == 0: print(json.dumps({"checked": len(statuses)}), flush=True)
    counts = {}
    for value in statuses.values(): counts[value["resource_status"]] = counts.get(value["resource_status"], 0) + 1
    result = {"records_sha256": hashlib.sha256(raw).hexdigest(), "count": len(rows), "counts": counts,
              "note": "REACHABLE means HTTP success + image MIME + first 1KiB signature at check time; not full decoding, dimensions, permission to redistribute, or a future availability guarantee.", "records": statuses}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"count": len(rows), "counts": counts}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--records", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    check(args.records, args.output)
