"""Single-owner facts maintenance; no inference, command or public authority."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from sqlalchemy.exc import SQLAlchemyError

from .client_auth import MAX_BACKGROUND_DEVICES, AuthError, ClientAuth
from .daily_schedule import due_report_date
from .history_contract import HistoryError, canonical_uuid, valid_time
from .history_store import HistoryStore
from .job_executor import _complete_critical

POLL_SECONDS = 60
MAX_ATTEMPTS = 48
MAX_BACKOFF_MS = 30 * 60_000
READ_FAILURES = (HistoryError, ValueError, OSError, SQLAlchemyError)


@dataclass
class _Attempt:
    generation: int
    day: str
    count: int = 0
    next_ms: int = 0
    complete: bool = False


class DailyFactsMaintenance:
    def __init__(self, auth, store, *, owner_id, clock_ms, storage_check):
        canonical_uuid(owner_id)
        if (
            not isinstance(auth, ClientAuth)
            or not isinstance(store, HistoryStore)
            or not callable(clock_ms)
            or not callable(storage_check)
        ):
            raise TypeError("daily_facts_dependencies_required")
        self.auth, self.store, self.owner_id = auth, store, owner_id
        self.clock_ms, self.storage_check = clock_ms, storage_check
        self._attempts, self._inspection_after = {}, {}
        self._last_ms, self._busy = 0, False
        self.state = "STOPPED"
        self.last_failure = None

    def _now(self):
        now = self.clock_ms()
        valid_time(now)
        if now < self._last_ms:
            raise ValueError("daily_facts_clock_regression")
        self._last_ms = now
        return now

    def _authorize(self, device):
        current = self.auth.authorize_attested_session(
            session_id=device.session_id,
            key_fingerprint=device.key_fingerprint,
            now_ms=self._now(),
        )
        if (current.owner_id, current.session_id, current.key_fingerprint) != (
            self.owner_id,
            device.session_id,
            device.key_fingerprint,
        ):
            raise AuthError()

    def _inspect(self, device):
        self.storage_check()
        self._authorize(device)
        setting = self.store.get_daily_facts_schedule(
            self.owner_id,
            device.key_fingerprint,
            now_ms=self._now(),
        )
        self._authorize(device)
        return setting

    def _produce(self, device):
        self.storage_check()
        self._authorize(device)
        result = self.store.produce_scheduled_daily_facts(
            self.owner_id,
            device.key_fingerprint,
            ranges=None,
            now_ms=self._now(),
        )
        self._authorize(device)
        self.storage_check()
        return result

    async def _call(self, operation, *args, **kwargs):
        # Cancellation cannot stop SQLite work. Drain before releasing API roots.
        result, cancelled = await _complete_critical(
            asyncio.to_thread(operation, *args, **kwargs)
        )
        if cancelled:
            raise asyncio.CancelledError
        return result

    async def tick(self, stop=None):
        if self._busy:
            raise RuntimeError("daily_facts_busy")
        self._busy = True
        try:
            self.last_failure = None
            if stop is not None and stop.is_set():
                return
            self._now()
            await self._call(self.storage_check)
            devices = await self._call(
                self.auth.list_authorized_attested_sessions,
                owner_id=self.owner_id,
                now_ms=self._now(),
            )
            if len(devices) > MAX_BACKGROUND_DEVICES:
                raise ValueError("background_device_limit")
            active = {(d.session_id, d.key_fingerprint) for d in devices}
            self._attempts = {k: v for k, v in self._attempts.items() if k in active}
            self._inspection_after = {
                k: v for k, v in self._inspection_after.items() if k in active
            }
            for device in devices:
                if stop is not None and stop.is_set():
                    break
                key = device.session_id, device.key_fingerprint
                now = self._now()
                if now < self._inspection_after.get(key, 0):
                    continue
                try:
                    setting = await self._call(self._inspect, device)
                except AuthError:
                    self._attempts.pop(key, None)
                    continue
                except READ_FAILURES:
                    self.last_failure = "SCHEDULE_UNAVAILABLE"
                    self._inspection_after[key] = self._now() + MAX_BACKOFF_MS
                    continue
                if setting is None or not setting.enabled:
                    self._attempts.pop(key, None)
                    continue
                day = due_report_date(setting.zoneId, self._now())
                if day is None:
                    continue
                attempt = self._attempts.get(key)
                if attempt is None or (attempt.generation, attempt.day) != (
                    setting.generation,
                    day,
                ):
                    attempt = self._attempts[key] = _Attempt(setting.generation, day)
                if (
                    attempt.complete
                    or attempt.count >= MAX_ATTEMPTS
                    or now < attempt.next_ms
                ):
                    continue
                if stop is not None and stop.is_set():
                    break
                attempt.count += 1
                attempt.next_ms = now + min(
                    MAX_BACKOFF_MS, 60_000 * 2 ** min(attempt.count - 1, 5)
                )
                try:
                    result = await self._call(self._produce, device)
                except AuthError:
                    self._attempts.pop(key, None)
                    continue
                except READ_FAILURES:
                    self.last_failure = "FACTS_UNAVAILABLE"
                    continue
                if (
                    result.schedule == setting
                    and result.report is not None
                    and result.report.facts.current.window.reportDate == day
                    and result.state in ("FACTS_READY", "UNCHANGED")
                ):
                    attempt.complete = True
        finally:
            self._busy = False

    async def run(self, stop):
        try:
            while not stop.is_set():
                try:
                    await self.tick(stop)
                    self.state = "DEFERRED" if self.last_failure else "READY"
                except READ_FAILURES:
                    self.last_failure = "OWNER_UNAVAILABLE"
                    self.state = "DEFERRED"
                if stop.is_set():
                    break
                try:
                    await asyncio.wait_for(stop.wait(), timeout=POLL_SECONDS)
                except TimeoutError:
                    pass
        finally:
            self.state = "STOPPED"
