"""Bounded post-response provider usage; never a preflight token counter."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from .history_contract import encoded

MAX_RESPONSES = 16
MAX_RECEIPT_BYTES = 8192
REVISION = "provider-token-usage-v1"
COUNT_FIELDS = frozenset(
    {
        "totalTokens",
        "inputTokens",
        "cachedInputTokens",
        "cacheWriteInputTokens",
        "outputTokens",
        "reasoningOutputTokens",
    }
)


def counts(value):
    if value is None:
        return None
    if (
        type(value) is not dict
        or set(value) - COUNT_FIELDS
        or COUNT_FIELDS - set(value) - {"cacheWriteInputTokens"}
    ):
        raise ValueError("invalid_provider_usage")
    result = {"cacheWriteInputTokens": 0} | value
    if (
        any(type(v) is not int or not 0 <= v < 2**63 for v in result.values())
        or result["cachedInputTokens"] > result["inputTokens"]
        or result["cacheWriteInputTokens"] > result["inputTokens"]
        or result["reasoningOutputTokens"] > result["outputTokens"]
        or result["inputTokens"] + result["outputTokens"] > result["totalTokens"]
    ):
        raise ValueError("invalid_provider_usage")
    return result


def _digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


@dataclass(frozen=True)
class UsageReceipt:
    body: bytes

    def __post_init__(self):
        try:
            if (
                type(self.body) is not bytes
                or not 1 <= len(self.body) <= MAX_RECEIPT_BYTES
            ):
                raise ValueError
            value = json.loads(self.body)
            if (
                type(value) is not dict
                or set(value)
                != {
                    "revision",
                    "source",
                    "model",
                    "inputDigest",
                    "instructionsDigest",
                    "responses",
                }
                or value["revision"] != REVISION
                or value["source"] != "NATIVE_RAW_RESPONSE_COMPLETED"
                or type(value["model"]) is not str
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", value["model"])
                or any(
                    type(value[k]) is not str
                    or not re.fullmatch(r"[0-9a-f]{64}", value[k])
                    for k in ("inputDigest", "instructionsDigest")
                )
                or type(value["responses"]) is not list
                or not 1 <= len(value["responses"]) <= MAX_RESPONSES
            ):
                raise ValueError
            seen = set()
            for response in value["responses"]:
                if (
                    type(response) is not dict
                    or set(response) != {"responseDigest", "usage"}
                    or type(response["responseDigest"]) is not str
                    or not re.fullmatch(r"[0-9a-f]{64}", response["responseDigest"])
                    or response["responseDigest"] in seen
                    or counts(response["usage"]) != response["usage"]
                ):
                    raise ValueError
                seen.add(response["responseDigest"])
            if encoded(value) != self.body:
                raise ValueError
        except (KeyError, ValueError, TypeError, UnicodeError, RecursionError):
            raise ValueError("invalid_provider_usage_receipt") from None

    @classmethod
    def create(cls, model, instructions, text, responses):
        return cls(
            encoded(
                dict(
                    revision=REVISION,
                    source="NATIVE_RAW_RESPONSE_COMPLETED",
                    model=model,
                    inputDigest=_digest(text),
                    instructionsDigest=_digest(instructions),
                    responses=responses,
                )
            )
        )

    def as_dict(self):
        return json.loads(self.body)

    def verify_binding(self, model, instructions, text):
        value = self.as_dict()
        if (
            value["model"] != model
            or value["instructionsDigest"] != _digest(instructions)
            or value["inputDigest"] != _digest(text)
        ):
            raise ValueError("provider_usage_binding")


def final_usage(entry, receipt):
    if type(receipt) is not UsageReceipt:
        raise ValueError("invalid_provider_usage_receipt")
    value = receipt.as_dict()
    if (
        value["model"] != entry["model"]
        or value["instructionsDigest"] != entry["instructionsDigest"]
        or entry.get("contextReceipt") is not None
        and value["inputDigest"] != entry["contextReceipt"]["manifest"]["frameDigest"]
    ):
        raise ValueError("provider_usage_binding")
    return value
