"""Isolated, local-only authentication pilot. No default app or inference routes.

Deploy only behind TLS with access logging disabled and one API process. The
bounded in-memory admission budget is process-local, not a distributed limiter.
Database ownership/lifetime is explicit and remains with the caller.
"""
from __future__ import annotations

import asyncio
import json
import time
from collections import deque
from dataclasses import asdict
from threading import Lock

from fastapi import FastAPI, Request
from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse, Response

from .ai.client_auth import AuthError, ClientAuth


BASE = "/api/ai/v1"
POST_FIELDS = {BASE + "/auth/enroll": "code", BASE + "/auth/refresh": "refresh_token",
               BASE + "/auth/revoke": "refresh_token"}
MAX_BODY = 2048


def _error(status, code):
    headers = {"Cache-Control": "no-store"}
    if status == 401:
        headers["WWW-Authenticate"] = "Bearer"
    if status == 429:
        headers["Retry-After"] = "60"
    return JSONResponse({"error": code}, status_code=status, headers=headers)


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_key")
        result[key] = value
    return result


class _Admission:
    def __init__(self, app, requests_per_minute, body_timeout_seconds, *, post_fields=None,
                 get_paths=None, body_limits=None):
        self.app = app
        self.limit = requests_per_minute
        self.timeout = body_timeout_seconds
        self.recent = deque()
        self.lock = Lock()
        self.post_fields = POST_FIELDS if post_fields is None else post_fields
        self.get_paths = {BASE + "/capabilities"} if get_paths is None else get_paths
        self.body_limits = {} if body_limits is None else body_limits

    def _admit(self):
        with self.lock:
            now = time.monotonic()
            while self.recent and self.recent[0] <= now - 60:
                self.recent.popleft()
            if len(self.recent) >= self.limit:
                return False
            self.recent.append(now)
            return True

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        async def fail(status, code):
            await _error(status, code)(scope, receive, send)
        if not self._admit():
            return await fail(429, "rate_limited")
        path, method = scope["path"], scope["method"]
        if path not in self.post_fields and path not in self.get_paths:
            return await fail(404, "not_found")
        if method != ("POST" if path in self.post_fields else "GET"):
            return await fail(405, "method_not_allowed")
        if scope.get("query_string"):
            return await fail(400, "invalid_request")
        if sum(len(k) + len(v) for k, v in scope["headers"]) > 8192:
            return await fail(431, "headers_too_large")
        headers = {}
        for key, value in scope["headers"]:
            headers.setdefault(key.lower(), []).append(value)
        lengths = headers.get(b"content-length", [])
        if len(lengths) > 1 or (lengths and (len(lengths[0]) > 10 or not lengths[0].isdigit())):
            return await fail(400, "invalid_request")
        declared = int(lengths[0]) if lengths else None
        max_body = self.body_limits.get(path, MAX_BODY)
        if declared is not None and declared > max_body:
            return await fail(413, "request_too_large")
        if b"content-encoding" in headers:
            return await fail(415, "unsupported_encoding")
        if b"transfer-encoding" in headers and lengths:
            return await fail(400, "invalid_request")
        if method == "POST":
            if headers.get(b"content-type") not in ([b"application/json"], [b"application/json; charset=utf-8"]):
                return await fail(415, "json_required")
            body = bytearray()
            try:
                async with asyncio.timeout(self.timeout):
                    while True:
                        message = await receive()
                        if message["type"] == "http.disconnect":
                            return
                        chunk = message.get("body", b"")
                        if len(body) + len(chunk) > max_body:
                            return await fail(413, "request_too_large")
                        body.extend(chunk)
                        if not message.get("more_body", False):
                            break
            except TimeoutError:
                return await fail(408, "request_timeout")
            if declared is not None and len(body) != declared:
                return await fail(400, "invalid_request")
            try:
                payload = json.loads(body.decode("utf-8"), object_pairs_hook=_unique)
                fields = self.post_fields[path]
                expected = {fields} if isinstance(fields, str) else fields
                if not isinstance(payload, dict) or set(payload) != expected:
                    raise ValueError("invalid_shape")
                if isinstance(fields, str) and not isinstance(payload[fields], str):
                    raise ValueError("invalid_shape")
            except (ValueError, UnicodeError, RecursionError):
                return await fail(400, "invalid_request")
            state = scope.setdefault("state", {})
            if isinstance(fields, str):
                state["credential"] = payload[fields]
            else:
                state["body"] = bytes(body)
                state["payload"] = payload
        else:
            if declared not in (None, 0) or b"transfer-encoding" in headers:
                return await fail(400, "invalid_request")
            auth = headers.get(b"authorization", [])
            if len(auth) != 1 or not auth[0].startswith(b"Bearer "):
                return await fail(401, "unauthorized")
            try:
                scope.setdefault("state", {})["credential"] = auth[0][7:].decode("ascii")
            except UnicodeError:
                return await fail(401, "unauthorized")
        started = False
        async def safe_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
                message["headers"] = [(k, v) for k, v in message.get("headers", []) if k.lower() != b"cache-control"]
                message["headers"].append((b"cache-control", b"no-store"))
            await send(message)
        try:
            await self.app(scope, receive, safe_send)
        except Exception:
            # No payload, credential, SQL parameters or exception text escapes.
            if not started:
                await fail(503, "service_unavailable")
        finally:
            for key in ("credential", "body", "payload"):
                scope.get("state", {}).pop(key, None)


def create_ai_app(auth: ClientAuth, *, requests_per_minute: int = 60,
                  body_timeout_seconds: float = 5):
    if type(requests_per_minute) is not int or not 1 <= requests_per_minute <= 600:
        raise ValueError("invalid_rate_limit")
    if type(body_timeout_seconds) not in (int, float) or not 0 < body_timeout_seconds <= 10:
        raise ValueError("invalid_body_timeout")
    api = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, redirect_slashes=False)

    @api.exception_handler(AuthError)
    async def rejected(request, exc):
        return _error(401, "unauthorized")

    @api.exception_handler(Exception)
    async def unavailable(request, exc):
        return _error(503, "service_unavailable")

    async def call(method, request):
        return await run_in_threadpool(method, request.state.credential,
                                      now_ms=time.time_ns() // 1_000_000)

    @api.post(BASE + "/auth/enroll")
    async def enroll(request: Request):
        return asdict(await call(auth.enroll, request))

    @api.post(BASE + "/auth/refresh")
    async def refresh(request: Request):
        return asdict(await call(auth.refresh, request))

    @api.post(BASE + "/auth/revoke", status_code=204)
    async def revoke(request: Request):
        await call(auth.revoke, request)
        return Response(status_code=204)

    @api.get(BASE + "/capabilities")
    async def capabilities(request: Request):
        await call(auth.authenticate, request)
        return {"revision": "auth-pilot-v1", "inference_enabled": False,
                "models": [], "task_kinds": []}

    return _Admission(api, requests_per_minute, body_timeout_seconds)


def create_bound_ai_app(activation, *, clock_ms=None):
    """App-bound activation/status only; never mounts the bearer pilot."""
    if clock_ms is None:
        clock_ms = lambda: time.time_ns() // 1_000_000
    api = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, redirect_slashes=False)

    @api.exception_handler(AuthError)
    async def rejected(request, exc):
        return _error(401, "unauthorized")

    @api.exception_handler(Exception)
    async def unavailable(request, exc):
        return _error(503, "service_unavailable")

    def proof(request, *, bound=False):
        values = {}
        for field, header in (("signature", "x-copilot-signature"), ("issued_ms", "x-copilot-issued-ms"),
                              ("nonce", "x-copilot-nonce")):
            entries = request.headers.getlist(header)
            if len(entries) != 1:
                raise AuthError()
            values[field] = entries[0]
        issued = values["issued_ms"]
        if not issued.isascii() or not issued.isdecimal() or len(issued) > 19:
            raise AuthError()
        values["issued_ms"] = int(issued)
        if bound:
            entries = request.headers.getlist("x-copilot-key")
            if len(entries) != 1:
                raise AuthError()
            values["key_fingerprint"] = entries[0]
        return values

    @api.post(BASE + "/activation/start")
    async def start(request: Request):
        value = request.state.payload
        result = await run_in_threadpool(activation.start, code=value["code"],
                                         request_id=value["request_id"], now_ms=clock_ms())
        return asdict(result)

    @api.post(BASE + "/activation/complete")
    async def complete(request: Request):
        result = await run_in_threadpool(activation.complete, body=request.state.body,
                                         now_ms=clock_ms(), **proof(request))
        return asdict(result)

    @api.post(BASE + "/session/refresh")
    async def refresh(request: Request):
        result = await run_in_threadpool(activation.refresh, body=request.state.body,
                                         now_ms=clock_ms(), **proof(request, bound=True))
        return asdict(result)

    @api.get(BASE + "/session/status")
    async def status(request: Request):
        return await run_in_threadpool(activation.status, access_token=request.state.credential,
                                       now_ms=clock_ms(), **proof(request, bound=True))

    return _Admission(api, 60, 5, post_fields={
        BASE + "/activation/start": {"code", "request_id"},
        BASE + "/activation/complete": {"request_id", "certificate_chain"},
        BASE + "/session/refresh": {"request_id", "refresh_token"},
    }, get_paths={BASE + "/session/status"}, body_limits={BASE + "/activation/complete": 96_000})
