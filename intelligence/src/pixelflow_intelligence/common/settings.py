import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Settings:
    database: str = field(default="mysql://pixelflow_analytics@127.0.0.1:3306/pixelflow_analytics", repr=False)
    java_url: str = "http://127.0.0.1:8080"
    export_token: str = field(default="", repr=False)
    sync_token: str = field(default="", repr=False)
    deepseek_api_key: str = field(default="", repr=False)
    deepseek_model: str = "deepseek-flash"

    def __post_init__(self):
        url = urlsplit(self.java_url)
        if (url.scheme not in {"http", "https"} or not url.netloc or url.username
                or url.password or url.query or url.fragment or url.path not in {"", "/"}):
            raise ValueError("JAVA_BASE_URL must be an HTTP(S) origin")
        for token in (self.export_token, self.sync_token):
            if token and (len(token) < 32 or not token.isascii() or token.strip() != token):
                raise ValueError("Service tokens must contain at least 32 ASCII characters")
        if self.export_token and self.export_token == self.sync_token:
            raise ValueError("Export and synchronization tokens must be different")

    @classmethod
    def from_env(cls):
        return cls(
            database=os.getenv("ANALYTICS_DB", cls.__dataclass_fields__["database"].default),
            java_url=os.getenv("JAVA_BASE_URL", "http://127.0.0.1:8080"),
            export_token=os.getenv("PIXELFLOW_ANALYTICS_EXPORT_TOKEN", ""),
            sync_token=os.getenv("PIXELFLOW_INTELLIGENCE_TOKEN", ""),
            deepseek_api_key=os.getenv("DEEPSEEK_API_KEY", ""),
            deepseek_model=os.getenv("DEEPSEEK_MODEL", "deepseek-flash"),
        )
