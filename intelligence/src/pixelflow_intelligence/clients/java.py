import httpx
from ..skills.models import Candidate


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
        if (not isinstance(result, dict) or not isinstance(result.get("personalized"), bool)
                or not isinstance(result.get("version"), int)
                or not isinstance(result.get("excluded_post_ids"), list)
                or not isinstance(result.get("blocked_author_ids"), list)
                or not isinstance(result.get("suppressed_topics"), list)):
            raise UpstreamError("Invalid preference snapshot")
        return result

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
