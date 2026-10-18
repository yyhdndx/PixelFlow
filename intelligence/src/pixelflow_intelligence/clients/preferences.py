from typing import Annotated
from pydantic import BaseModel, ConfigDict, Field, model_validator

PostId = Annotated[str, Field(pattern=r"^[1-9][0-9]{0,17}$")]
AuthorId = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,36}$")]


class PreferenceSnapshot(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    user_id: str = Field(min_length=1, max_length=36)
    personalized: bool
    version: int = Field(ge=0)
    excluded_post_ids: list[PostId] = Field(max_length=1000)
    blocked_author_ids: list[AuthorId] = Field(max_length=500)
    suppressed_topics: list[str] = Field(max_length=7)

    @model_validator(mode="after")
    def unique_and_known(self):
        for values in (self.excluded_post_ids, self.blocked_author_ids, self.suppressed_topics):
            if len(values) != len(set(values)):
                raise ValueError("Duplicate preference")
        if not set(self.suppressed_topics) <= {"美食", "宠物", "旅行", "阅读", "数码", "运动", "园艺"}:
            raise ValueError("Unknown topic")
        return self
