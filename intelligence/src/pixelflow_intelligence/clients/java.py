import httpx
from ..skills.models import Candidate
from .preferences import PreferenceSnapshot


class UpstreamError(Exception):
    pass


class AuthenticationError(Exception):
    pass


class JavaClient:
    def __init__(self, settings):
        self.settings = settings

    def _get(self, path, token, params=None):
        try:
            with httpx.Client(base_url=self.settings.java_url, timeout=5,
                              follow_redirects=False, trust_env=False) as client:
                response = client.get(path, headers={"Authorization": "Bearer " + token}, params=params)
                if response.status_code in (401, 403):
                    raise AuthenticationError()
                response.raise_for_status()
                return response.json()
        except (httpx.HTTPError, ValueError) as error:
            raise UpstreamError("Java service unavailable or invalid response") from error

    def events(self, after):
        if not self.settings.export_token:
            raise UpstreamError("Analytics export is not configured")
        return self._get("/api/internal/analytics/events", self.settings.export_token,
                         {"after": after, "count": 50})

    def me(self, token):
        result = self._get("/api/users/me", token)
        if (not isinstance(result, dict) or not isinstance(result.get("id"), str)
                or not result.get("enabled") or not 1 <= len(result["id"]) <= 36):
            raise AuthenticationError()
        return result["id"]

    def preferences(self, token):
        result = self._get("/api/recommendations/me/preferences", token)
        try:
            return PreferenceSnapshot.model_validate(result).model_dump()
        except ValueError:
            raise UpstreamError("Invalid preference snapshot") from None

    def public_candidates(self, token):
        # This endpoint filters PUBLISHED posts and enabled authors on Java's side.
        result = self._get("/feed/", token, {"count": 50, "accept_video_clip": "true"})
        try:
            if not isinstance(result, dict) or result.get("status_code") != 0:
                raise ValueError()
            rows = result["post_list"]
            if not isinstance(rows, list) or len(rows) > 50:
                raise ValueError()
            candidates = []
            for row in rows:
                if row["status"] != "PUBLISHED" or not isinstance(row["clips"], list):
                    raise ValueError()
                types = [clip["type"] for clip in row["clips"]]
                if any(type(value) is not int or value not in (0, 1) for value in types):
                    raise ValueError()
                candidates.append(Candidate.model_validate({
                    "post_id": row["post_id"], "author_id": row["author"]["user_id"],
                    "title": row["title"], "media_type": "video" if 1 in types else "image",
                    "like_count": row["like_count"], "create_time": row["create_time"],
                    "status": row["status"], "author_enabled": True,
                }).model_dump())
            if len({row["post_id"] for row in candidates}) != len(candidates):
                raise ValueError()
            return candidates
        except (KeyError, TypeError, ValueError):
            raise UpstreamError("Invalid public candidate snapshot") from None

    def public_posts(self, token, pages: int = 3):
        """PUBLISHED 作品全文快照（title+content），游标翻页硬上限 50*pages 篇。

        可见性由 Java 侧保证：/feed/ 只返回 PUBLISHED 且作者启用，游客/用户
        看到的均为公开内容，Agent 因此无法引用草稿或待审核作品。
        """
        if not 1 <= pages <= 5:
            raise UpstreamError("Invalid page count")
        posts, seen, cursor = [], set(), None
        try:
            for _ in range(pages):
                params = {"count": 50, "accept_video_clip": "true"}
                if cursor is not None:
                    params["cursor"] = cursor
                result = self._get("/feed/", token, params)
                if not isinstance(result, dict) or result.get("status_code") != 0:
                    raise ValueError()
                rows = result["post_list"]
                if not isinstance(rows, list) or len(rows) > 50:
                    raise ValueError()
                for row in rows:
                    if row["status"] != "PUBLISHED":
                        raise ValueError()
                    post_id = str(row["post_id"])
                    if post_id in seen:
                        raise ValueError()
                    seen.add(post_id)
                    author = row["author"]
                    posts.append({
                        "post_id": post_id, "title": str(row["title"] or ""),
                        "content": str(row.get("content") or ""),
                        "author_id": str(author["user_id"]),
                        "author_name": str(author.get("nickname") or "")[:64],
                        "create_time": int(row["create_time"]),
                    })
                if not result.get("has_more") or not rows:
                    break
                cursor = result["next_cursor"]
                if cursor is None:
                    break
        except (KeyError, TypeError, ValueError):
            raise UpstreamError("Invalid public post snapshot") from None
        return posts
