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
from contextlib import asynccontextmanager
from dataclasses import asdict
from threading import Lock
from uuid import UUID

from fastapi import FastAPI, Request
from starlette.concurrency import run_in_threadpool
from starlette.responses import JSONResponse, Response

from .ai.client_auth import AuthError, ClientAuth
from .ai.job_policy import submit_proof_credential
from .ai.job_service import JobServiceError


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
                 get_paths=None, body_limits=None, detail_prefixes=(), protected_paths=(),
                 bound_auth=None, clock_ms=None):
        self.app = app
        self.limit = requests_per_minute
        self.timeout = body_timeout_seconds
        self.recent = deque()
        self.lock = Lock()
        self.post_fields = POST_FIELDS if post_fields is None else post_fields
        self.get_paths = {BASE + "/capabilities"} if get_paths is None else get_paths
        self.body_limits = {} if body_limits is None else body_limits
        self.detail_prefixes = tuple(detail_prefixes)
        self.protected_paths = set(protected_paths)
        self.bound_auth = bound_auth
        self.clock_ms = clock_ms or (lambda: time.time_ns() // 1_000_000)

    def _detail(self, path):
        for prefix in self.detail_prefixes:
            if path.startswith(prefix):
                value = path[len(prefix):]
                try:
                    if value and "/" not in value and str(UUID(value)) == value:
                        return True
                except ValueError:
                    pass
        return False

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
        try:
            canonical_path = path.encode("ascii")
        except UnicodeError:
            return await fail(400, "invalid_request")
        if scope.get("raw_path", canonical_path) != canonical_path:
            return await fail(400, "invalid_request")
        detail = self._detail(path)
        if path not in self.post_fields and path not in self.get_paths and not detail:
            return await fail(404, "not_found")
        allowed = {"GET", "DELETE"} if detail else ({"POST"} if path in self.post_fields else {"GET"})
        if method not in allowed:
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
        protected = path in self.protected_paths or detail
        if protected:
            auth = headers.get(b"authorization", [])
            keys = headers.get(b"x-copilot-key", [])
            proof_headers = [headers.get(name, []) for name in (
                b"x-copilot-signature", b"x-copilot-issued-ms", b"x-copilot-nonce")]
            if (len(auth) != 1 or not auth[0].startswith(b"Bearer ")
                    or len(keys) != 1 or any(len(values) != 1 for values in proof_headers)):
                return await fail(401, "unauthorized")
            try:
                credential = auth[0][7:].decode("ascii")
                key_fingerprint = keys[0].decode("ascii")
                device = await run_in_threadpool(self.bound_auth, credential,
                    key_fingerprint, self.clock_ms())
            except AuthError:
                return await fail(401, "unauthorized")
            except Exception:
                return await fail(503, "service_unavailable")
            state = scope.setdefault("state", {})
            state["credential"] = credential
            state["key_fingerprint"] = key_fingerprint
            state["bound_device"] = device
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
                payload = json.loads(body.decode("utf-8"), object_pairs_hook=_unique,
                                     parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
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
            if not protected:
                auth = headers.get(b"authorization", [])
                if len(auth) != 1 or not auth[0].startswith(b"Bearer "):
                    return await fail(401, "unauthorized")
                try:
                    scope.setdefault("state", {})["credential"] = auth[0][7:].decode("ascii")
                except UnicodeError:
                    return await fail(401, "unauthorized")
            try:
                async with asyncio.timeout(self.timeout):
                    while True:
                        message = await receive()
                        if message["type"] == "http.disconnect":
                            return
                        if message["type"] != "http.request" or message.get("body", b""):
                            return await fail(400, "invalid_request")
                        if not message.get("more_body", False):
                            break
            except TimeoutError:
                return await fail(408, "request_timeout")
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
            for key in ("credential", "body", "payload", "key_fingerprint", "bound_device"):
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


def create_bound_ai_app(activation, *, jobs=None, meal_photo_jobs=None, clock_ms=None):
    """App-bound identity with optional injected jobs; never mounts bearer-only auth."""
    if clock_ms is None:
        clock_ms = lambda: time.time_ns() // 1_000_000

    @asynccontextmanager
    async def lifespan(app):
        services = tuple(item for item in (jobs, meal_photo_jobs) if item is not None)
        for service in services:
            await service.start()
        try:
            yield
        finally:
            for service in reversed(services):
                await service.close()

    api = FastAPI(docs_url=None, redoc_url=None, openapi_url=None,
                  redirect_slashes=False, lifespan=lifespan)

    @api.exception_handler(AuthError)
    async def rejected(request, exc):
        return _error(401, "unauthorized")

    @api.exception_handler(Exception)
    async def unavailable(request, exc):
        return _error(503, "service_unavailable")

    @api.exception_handler(JobServiceError)
    async def job_rejected(request, exc):
        return _error(exc.status, exc.code)

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
        result = await run_in_threadpool(activation.status, access_token=request.state.credential,
                                         now_ms=clock_ms(), **proof(request, bound=True))
        if jobs is not None or meal_photo_jobs is not None:
            result["inference_enabled"] = any(service.inference_ready for service in
                                               (jobs, meal_photo_jobs) if service is not None)
        return result

    post_fields = {
        BASE + "/activation/start": {"code", "request_id"},
        BASE + "/activation/complete": {"request_id", "certificate_chain"},
        BASE + "/session/refresh": {"request_id", "refresh_token"},
    }
    get_paths = {BASE + "/session/status"}
    body_limits = {BASE + "/activation/complete": 96_000}
    protected_paths = set()

    job_services = tuple(item for item in (jobs, meal_photo_jobs) if item is not None)
    detail_prefixes = []
    if job_services:
        get_paths.add(BASE + "/capabilities")
        protected_paths.add(BASE + "/capabilities")

        def request_metadata(request):
            request_ids = request.headers.getlist("x-copilot-request-id")
            deadlines = request.headers.getlist("x-copilot-deadline-ms")
            if len(request_ids) != 1 or len(deadlines) != 1:
                raise JobServiceError(400, "invalid_request")
            deadline = deadlines[0]
            if not deadline.isascii() or not deadline.isdecimal() or len(deadline) > 19:
                raise JobServiceError(400, "invalid_request")
            return request_ids[0], int(deadline)

        async def authorize(job_service, request, *, method, path, body=b"", submit=False):
            values = proof(request, bound=True)
            request_id = deadline_ms = None
            proof_credential = request.state.credential
            if submit:
                request_id, deadline_ms = request_metadata(request)
                try:
                    proof_credential = submit_proof_credential(
                        request.state.credential, request_id=request_id,
                        deadline_ms=deadline_ms)
                except ValueError:
                    raise JobServiceError(400, "invalid_request") from None
            device = await run_in_threadpool(job_service.verify_request,
                preauthorized=request.state.bound_device,
                access_token=request.state.credential,
                key_fingerprint=values.pop("key_fingerprint"), method=method,
                path=path, body=body, proof_credential=proof_credential,
                now_ms=clock_ms(), **values)
            return device, request_id, deadline_ms

        @api.get(BASE + "/capabilities")
        async def capabilities(request: Request):
            await authorize(job_services[0], request, method="GET",
                            path=BASE + "/capabilities")
            capabilities_by_kind = {service.policy.kind: service.capabilities()
                                     for service in job_services}
            if len(capabilities_by_kind) == 1:
                return next(iter(capabilities_by_kind.values()))
            return {
                "revision": "server-codex-ai-capabilities-r1",
                "inference_enabled": any(item["inference_enabled"]
                                          for item in capabilities_by_kind.values()),
                "task_kinds": list(capabilities_by_kind),
                "input_modalities": [item["input_modalities"][0]
                                      for item in capabilities_by_kind.values()],
                "output_modalities": [item["output_modalities"][0]
                                       for item in capabilities_by_kind.values()],
                "routes": capabilities_by_kind,
            }

        def register_job_routes(job_service, path):
            detail_prefixes.append(path + "/")
            if job_service.policy.kind == "CHAT":
                post_fields[path] = {"text"}
            else:
                post_fields[path] = {"schemaVersion", "mimeType", "imageBase64"}
            body_limits[path] = job_service.max_http_body_bytes
            protected_paths.add(path)

            async def submit_job(request: Request):
                device, request_id, deadline_ms = await authorize(job_service, request,
                    method="POST", path=path, body=request.state.body, submit=True)
                return await job_service.submit(device, request_id=request_id,
                    deadline_ms=deadline_ms, body=request.state.body, now_ms=clock_ms())

            async def get_job(job_id: str, request: Request):
                job_path = path + "/" + job_id
                device, _, _ = await authorize(job_service, request, method="GET",
                                               path=job_path)
                return await job_service.get(device, job_id, now_ms=clock_ms())

            async def cancel_job(job_id: str, request: Request):
                job_path = path + "/" + job_id
                device, _, _ = await authorize(job_service, request, method="DELETE",
                                               path=job_path)
                return await job_service.cancel(device, job_id, now_ms=clock_ms())

            api.add_api_route(path, submit_job, methods=["POST"], status_code=202,
                              name=f"submit_{job_service.policy.kind.lower()}")
            api.add_api_route(path + "/{job_id}", get_job, methods=["GET"],
                              name=f"get_{job_service.policy.kind.lower()}")
            api.add_api_route(path + "/{job_id}", cancel_job, methods=["DELETE"],
                              name=f"cancel_{job_service.policy.kind.lower()}")

        if jobs is not None:
            register_job_routes(jobs, BASE + "/jobs")
        if meal_photo_jobs is not None:
            register_job_routes(meal_photo_jobs, BASE + "/meal-photo/jobs")

    return _Admission(api, 60, 5, post_fields=post_fields, get_paths=get_paths,
        body_limits=body_limits, detail_prefixes=tuple(detail_prefixes),
        protected_paths=protected_paths,
        bound_auth=job_services[0].preauthorize if job_services else None,
        clock_ms=clock_ms)
