from dataclasses import dataclass, field
import time
import uuid
from pydantic import ValidationError


class SkillError(Exception):
    def __init__(self, code, skill):
        self.code, self.skill = code, skill
        super().__init__(f"{skill}: {code}")


@dataclass(frozen=True)
class Context:
    # Only trusted server code constructs this context; never deserialize it from a request.
    user_id: str
    scopes: frozenset[str]
    now: int
    deadline: float
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4()))

    def checkpoint(self):
        if time.monotonic() >= self.deadline:
            raise TimeoutError()


@dataclass(frozen=True)
class Definition:
    name: str
    description: str
    scope: str
    input_model: type
    output_model: type
    handler: object
    version: str = "1.0.0"


class Registry:
    def __init__(self, definitions):
        self.definitions = {}
        for definition in definitions:
            if definition.name in self.definitions:
                raise ValueError("Duplicate skill")
            self.definitions[definition.name] = definition

    def describe(self):
        return [{
            "name": d.name, "version": d.version, "description": d.description,
            "required_scopes": [d.scope], "read_only": True,
            "timeout_mode": "cooperative",
            "input_schema": d.input_model.model_json_schema(),
            "output_schema": d.output_model.model_json_schema(),
        } for d in self.definitions.values()]

    def invoke(self, name, payload, context):
        started = time.monotonic()
        if name not in self.definitions:
            raise SkillError("UNKNOWN_SKILL", name)
        definition = self.definitions[name]
        if not context.user_id or definition.scope not in context.scopes:
            raise SkillError("FORBIDDEN", name)
        try:
            context.checkpoint()
            data = definition.input_model.model_validate(payload)
        except ValidationError:
            raise SkillError("INVALID_INPUT", name) from None
        except TimeoutError:
            raise SkillError("TIMEOUT", name) from None
        if data.user_id != context.user_id:
            raise SkillError("IDENTITY_MISMATCH", name)
        if getattr(data, "snapshot", None) is not None and data.snapshot.user_id != context.user_id:
            raise SkillError("IDENTITY_MISMATCH", name)
        if getattr(data, "insight", None) is not None and data.insight.user_id != context.user_id:
            raise SkillError("IDENTITY_MISMATCH", name)
        try:
            result = definition.handler(data, context)
            context.checkpoint()
            output = definition.output_model.model_validate(result)
            if output.user_id != context.user_id:
                raise SkillError("INVALID_OUTPUT", name)
        except TimeoutError:
            raise SkillError("TIMEOUT", name) from None
        except ValidationError:
            raise SkillError("INVALID_OUTPUT", name) from None
        except SkillError:
            raise
        except Exception:
            raise SkillError("EXECUTION_FAILED", name) from None
        return {
            "skill": name, "skill_version": definition.version, "trace_id": context.trace_id,
            "elapsed_ms": round((time.monotonic()-started)*1000, 3),
            "output": output.model_dump(mode="json"),
        }
