#!/usr/bin/env python3
"""Create one public share fixture on an explicitly confirmed isolated loopback server."""
import argparse
import ipaddress
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise RuntimeError("Redirect refused")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--isolated-h2", action="store_true", required=True,
                        help="Confirm this is a dedicated disposable H2 server")
    args = parser.parse_args()
    endpoint = urllib.parse.urlsplit(args.base_url)
    host = endpoint.hostname or ""
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = host == "localhost"
    if not loopback or endpoint.scheme != "http" or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment:
        parser.error("Only an HTTP loopback endpoint without credentials/query/fragment is allowed")
    base = args.base_url.rstrip("/")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(path, body=None, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(base + path, data=data, headers=headers)
        with opener.open(req, timeout=15) as response:
            return json.load(response)

    def token(role):
        value = os.environ.get("SHARE_" + role + "_TOKEN")
        if value:
            return value
        user = os.environ.get("SHARE_" + role + "_USERNAME")
        password = os.environ.get("SHARE_" + role + "_PASSWORD")
        if not user or not password:
            raise RuntimeError("Provide SHARE_" + role + "_TOKEN or SHARE_" + role + "_USERNAME/PASSWORD")
        return request("/api/auth/login", {"username": user, "password": password})["accessToken"]

    author, admin = token("AUTHOR"), token("ADMIN")
    created = request("/api/posts", {"title": "Local Android share fixture", "content": "Isolated H2 HTTP fixture for Android share verification.",
                      "clips": [{"type": 0, "width": 960, "height": 640,
                                 "url": base + "/demo-assets/moment-1.jpg"}]}, author)
    post_id = created["post_id"]
    request("/api/posts/" + post_id + "/publish", {}, author)
    request("/api/admin/posts/" + post_id + "/moderation", {"status": "PUBLISHED", "reason": "Isolated Android share fixture"}, admin)
    public = request("/api/posts/" + post_id)
    if public.get("status") != "PUBLISHED":
        raise RuntimeError("Fixture did not become public")
    print(json.dumps({"postId": post_id, "shareApiUrl": base + "/",
                      "webLink": "http://localhost:5173/#/post/" + post_id}))


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        sys.exit("Fixture HTTP request failed: " + str(error.code))
    except Exception as error:
        sys.exit(str(error))
