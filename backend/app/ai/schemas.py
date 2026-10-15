"""Closed text-only DTOs for the optional server AI transport."""
from __future__ import annotations

import json
from dataclasses import dataclass


MAX_TEXT_CHARS = 4096
MAX_TEXT_BYTES = 8192
MAX_RESULT_CHARS = 8192
MAX_RESULT_BYTES = 16_384
MAX_RESPONSE_BYTES = 65_536


class SchemaError(Exception):
    pass


def _unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError()
        value[key] = item
    return value


def _bounded_text(value, *, chars, size, error):
    try:
        if (not isinstance(value, str) or not value or len(value) > chars
                or len(value.encode("utf-8")) > size):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise SchemaError(error) from None
    return value


@dataclass(frozen=True)
class ChatRequest:
    text: str

    @classmethod
    def decode(cls, body: bytes) -> "ChatRequest":
        try:
            if type(body) is not bytes:
                raise ValueError()
            value = json.loads(body.decode("utf-8"), object_pairs_hook=_unique,
                               parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
            if not isinstance(value, dict) or set(value) != {"text"}:
                raise ValueError()
            text = _bounded_text(value["text"], chars=MAX_TEXT_CHARS,
                                 size=MAX_TEXT_BYTES, error="invalid_request")
            return cls(text)
        except SchemaError:
            raise
        except (ValueError, UnicodeError, RecursionError):
            raise SchemaError("invalid_request") from None


@dataclass(frozen=True)
class ChatResult:
    text: str

    @classmethod
    def decode(cls, value: object) -> "ChatResult":
        try:
            if not isinstance(value, dict) or set(value) != {"text"}:
                raise ValueError()
            return cls(_bounded_text(value["text"], chars=MAX_RESULT_CHARS,
                                     size=MAX_RESULT_BYTES, error="invalid_result"))
        except SchemaError:
            raise
        except (ValueError, UnicodeError):
            raise SchemaError("invalid_result") from None

    def as_dict(self) -> dict[str, str]:
        return {"text": self.text}
