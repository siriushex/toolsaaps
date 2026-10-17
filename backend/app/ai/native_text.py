"""Pinned native text protocol. The caller supplies independently contained IO."""
from __future__ import annotations

import copy
import hashlib
import json
import re
from dataclasses import dataclass
from typing import Protocol

from .schemas import ChatResult, SchemaError


class NativeTextError(Exception):
    """Fixed diagnostic code; never includes native output or supplied context."""


class TextTransport(Protocol):
    async def send(self, value: dict) -> None: ...

    async def receive(self) -> bytes: ...


DISABLED_FEATURES = (
    "shell_tool", "view_image", "sleep_tool", "code_mode", "code_mode_only",
    "code_mode_host", "multi_agent", "multi_agent_v2", "apps", "plugins",
    "hooks", "token_budget", "current_time_reminder", "deferred_executor", "tool_suggest",
)
NATIVE_SETTINGS = tuple(f"features.{key}=false" for key in DISABLED_FEATURES) + (
    "tools.update_plan.enabled=false", "tools.experimental_request_user_input.enabled=false",
    "agents.enabled=false", 'web_search="disabled"', "mcp_servers={}",
    'model_provider="openai"', 'chatgpt_base_url="https://chatgpt.com/backend-api"',
    'cli_auth_credentials_store="file"', "analytics.enabled=false",
    "check_for_update_on_startup=false", 'sqlite_home="/tmp/state"',
    'model_catalog_json="/work/text-model.json"',
)
STARTUP_NOTICE_SHA256 = "0423500be79523998b914735593b9d7a2a3bde53601c0f60bd51ae9bac1a6ba1"
# Native user-message events can echo the full input with JSON escaping.
NATIVE_LINE_LIMIT = 6 * 131_072 + 1024


def restricted_catalog(catalog: dict, model: str) -> dict:
    if not isinstance(model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", model):
        raise ValueError("invalid_text_model")
    selected = [item for item in catalog.get("models", [])
                if isinstance(item, dict) and item.get("slug") == model]
    if len(selected) != 1:
        raise ValueError("exact_text_model_required")
    value = copy.deepcopy(selected[0])
    value.update(tool_mode="direct", experimental_supported_tools=[], shell_type="disabled",
                 apply_patch_tool_type=None, supports_search_tool=False)
    return {"models": [value]}


@dataclass(frozen=True)
class TextSessionPolicy:
    model: str
    instructions: str
    max_input_bytes: int = 131_072
    max_stream_bytes: int = 1_048_576
    max_events: int = 2048

    def __post_init__(self):
        restricted_catalog({"models": [{"slug": self.model}]}, self.model)
        if not isinstance(self.instructions, str) or not 1 <= len(self.instructions.encode()) <= 16_384:
            raise ValueError("invalid_text_instructions")
        for value, maximum in ((self.max_input_bytes, 131_072),
                               (self.max_stream_bytes, 1_048_576), (self.max_events, 2048)):
            if type(value) is not int or not 1 <= value <= maximum:
                raise ValueError("invalid_text_limit")


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_field")
        result[key] = value
    return result


class _NativeSession:
    """Shared correlation/notification checks, not a public input interface."""

    line_limit = NATIVE_LINE_LIMIT

    def __init__(self, transport, policy):
        self.transport, self.policy = transport, policy
        self._used = False
        self._bytes = self._events = 0
        self._thread = self._turn = None
        self._observed_thread = self._observed_turn = None
        self._final = None
        self._completed = False

    async def _run(self, inputs):
        if self._used:
            raise NativeTextError("text_session_already_used")
        self._used = True
        try:
            initialized = await self._request(1, "initialize", {
                "clientInfo": {"name": "personal-ai", "version": "1"},
                "capabilities": {"experimentalApi": True},
            })
            agent = initialized.get("userAgent", "")
            if (not isinstance(agent, str) or not agent.startswith("personal-ai/0.153.4 ")
                    or re.search(r"codex|skylink|argos", agent, re.I)):
                raise NativeTextError("text_identity_rejected")
            await self.transport.send({"method": "initialized", "params": {}})
            started = await self._request(2, "thread/start", {
                "model": self.policy.model, "allowProviderModelFallback": False,
                "cwd": "/work", "ephemeral": True, "environments": [], "dynamicTools": [],
                "selectedCapabilityRoots": [], "sandbox": "read-only", "approvalPolicy": "never",
                "baseInstructions": self.policy.instructions,
            })
            if (started.get("model") != self.policy.model or started.get("instructionSources")
                    or started.get("approvalPolicy") != "never" or started.get("cwd") != "/work"
                    or started.get("sandbox") != {"type": "readOnly", "networkAccess": False}):
                raise NativeTextError("text_configuration_rejected")
            self._thread = self._identifier(started["thread"]["id"])
            if self._observed_thread not in (None, self._thread):
                raise NativeTextError("text_correlation_rejected")
            response = await self._request(3, "turn/start", {
                "threadId": self._thread, "environments": [],
                "input": inputs,
            })
            self._turn = self._identifier(response["turn"]["id"])
            if self._observed_turn not in (None, self._turn):
                raise NativeTextError("text_correlation_rejected")
            while not self._completed:
                value = await self._receive()
                if "id" in value:
                    raise NativeTextError("text_unexpected_reply")
                self._notification(value)
            if self._final is None:
                raise NativeTextError("text_missing_answer")
            return self._final
        except NativeTextError:
            raise
        except (KeyError, TypeError, ValueError, UnicodeError, SchemaError, RecursionError):
            raise NativeTextError("text_protocol_rejected") from None

    async def _request(self, identifier, method, params):
        await self.transport.send({"id": identifier, "method": method, "params": params})
        while True:
            value = await self._receive()
            if "id" in value:
                if value["id"] != identifier or type(value["id"]) is not int or "method" in value:
                    raise NativeTextError("text_unexpected_reply")
                if set(value) != {"id", "result"} or not isinstance(value["result"], dict):
                    raise NativeTextError("text_rpc_failed")
                return value["result"]
            self._notification(value)

    async def _receive(self):
        raw = await self.transport.receive()
        if type(raw) is not bytes or not 1 <= len(raw) <= self.line_limit:
            raise NativeTextError("text_line_limit")
        self._bytes += len(raw)
        self._events += 1
        if self._bytes > self.policy.max_stream_bytes or self._events > self.policy.max_events:
            raise NativeTextError("text_stream_limit")
        value = json.loads(raw.decode(), object_pairs_hook=_unique,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(value, dict):
            raise NativeTextError("text_envelope_rejected")
        if "method" in value and "id" in value:
            raise NativeTextError("text_action_rejected")
        return value

    @staticmethod
    def _identifier(value):
        if not isinstance(value, str) or not 1 <= len(value) <= 128:
            raise NativeTextError("text_identifier_rejected")
        return value

    def _correlate(self, params, *, turn=False):
        thread = self._identifier(params["threadId"])
        expected = self._thread or self._observed_thread
        if expected is None or thread != expected:
            raise NativeTextError("text_correlation_rejected")
        self._observed_thread = thread
        if turn:
            if self._thread is None:
                raise NativeTextError("text_correlation_rejected")
            identifier = self._identifier(params["turnId"])
            if self._turn not in (None, identifier) or self._observed_turn not in (None, identifier):
                raise NativeTextError("text_correlation_rejected")
            self._observed_turn = identifier

    def _notification(self, value):
        if set(value) - {"method", "params", "emittedAtMs"}:
            raise NativeTextError("text_envelope_rejected")
        if "emittedAtMs" in value and (type(value["emittedAtMs"]) is not int
                                      or not 0 <= value["emittedAtMs"] < 2**63):
            raise NativeTextError("text_envelope_rejected")
        method, params = value.get("method"), value.get("params")
        if not isinstance(method, str) or not isinstance(params, dict):
            raise NativeTextError("text_envelope_rejected")
        if method == "configWarning":
            if (set(params) - {"summary", "details", "path", "range"}
                    or not isinstance(params.get("summary"), str)
                    or any(params.get(key) is not None for key in ("details", "path", "range"))
                    or hashlib.sha256(params["summary"].encode()).hexdigest() != STARTUP_NOTICE_SHA256):
                raise NativeTextError("text_configuration_warning")
        elif method == "remoteControl/status/changed":
            if params.get("status") != "disabled" or params.get("environmentId") is not None:
                raise NativeTextError("text_remote_control_rejected")
        elif method == "thread/started":
            identifier = self._identifier(params["thread"]["id"])
            if self._thread not in (None, identifier) or self._observed_thread not in (None, identifier):
                raise NativeTextError("text_correlation_rejected")
            self._observed_thread = identifier
        elif method in {"turn/started", "turn/completed"}:
            self._correlate({"threadId": params["threadId"], "turnId": params["turn"]["id"]}, turn=True)
            if method == "turn/completed":
                if self._completed or params["turn"].get("status") != "completed" or params["turn"].get("error") is not None:
                    raise NativeTextError("text_completion_rejected")
                self._completed = True
        elif method in {"item/started", "item/completed"}:
            self._correlate(params, turn=True)
            item = params["item"]
            if item.get("type") not in {"userMessage", "agentMessage", "reasoning"}:
                raise NativeTextError("text_tool_rejected")
            if method == "item/completed" and item["type"] == "agentMessage":
                if item.get("phase") in (None, "final_answer"):
                    if self._final is not None:
                        raise NativeTextError("text_duplicate_answer")
                    self._final = self._decode_answer(item.get("text"))
                elif item.get("phase") != "commentary":
                    raise NativeTextError("text_message_phase_rejected")
        elif method == "turn/moderationMetadata":
            if set(params) != {"threadId", "turnId", "metadata"}:
                raise NativeTextError("text_envelope_rejected")
            self._correlate(params, turn=True)
        elif method in {"item/agentMessage/delta", "item/reasoning/summaryTextDelta",
                        "item/reasoning/summaryPartAdded", "item/reasoning/textDelta",
                        "model/verification", "model/safetyBuffering/updated"}:
            self._correlate(params, turn=True)
            if method == "model/safetyBuffering/updated" and params.get("model") != self.policy.model:
                raise NativeTextError("text_configuration_rejected")
        elif method == "thread/settings/updated":
            self._correlate(params)
            settings = params["threadSettings"]
            if (settings.get("model") != self.policy.model or settings.get("cwd") != "/work"
                    or settings.get("approvalPolicy") != "never"
                    or settings.get("sandboxPolicy") != {"type": "readOnly", "networkAccess": False}):
                raise NativeTextError("text_configuration_rejected")
        elif method in {"thread/status/changed", "thread/tokenUsage/updated", "thread/name/updated"}:
            self._correlate(params)
        elif method not in {"account/rateLimits/updated", "skills/changed", "account/updated"}:
            raise NativeTextError("text_notification_rejected")


class NativeTextSession(_NativeSession):
    """One text-only ephemeral conversation; no image/file or tool input."""

    def __init__(self, transport: TextTransport, policy: TextSessionPolicy, *, containment_verified: bool):
        if containment_verified is not True or type(policy) is not TextSessionPolicy:
            raise ValueError("contained_text_transport_required")
        super().__init__(transport, policy)

    @staticmethod
    def _decode_answer(value):
        return ChatResult.decode({"text": value}).as_dict()

    async def run(self, text: str) -> dict[str, str]:
        if self._used:
            raise NativeTextError("text_session_already_used")
        try:
            valid = isinstance(text, str) and 1 <= len(text.encode()) <= self.policy.max_input_bytes
        except UnicodeError:
            valid = False
        if not valid:
            self._used = True
            raise NativeTextError("text_input_limit")
        return await self._run([{"type": "text", "text": text, "textElements": []}])
