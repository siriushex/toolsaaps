"""Synthetic images and typed peers only; no native/provider/profile operation."""
import asyncio
import base64
from dataclasses import FrozenInstanceError, replace
from io import BytesIO
import json
import threading
from uuid import uuid4

import pytest

from app.ai.job_executor import JobExecutor
from app.ai.job_ledger import JobLedger
from app.ai.job_policy import CHAT_POLICY, request_digest
from app.ai.job_service import JobIdentity, JobWork
from app.ai.meal_photo import MAX_RESULT_BYTES, MealPhotoRequest
from app.ai.meal_vision import (
    FOOD_ONLY_PROMPT, NATIVE_VISION_BLOCKERS, MealVisionError, MealVisionReply,
    MealVisionWorker, meal_vision_policy,
)
from app.ai import meal_vision_image as images


def jpeg(*, size=(32, 24), mode="RGB", metadata=False):
    Image = pytest.importorskip("PIL.Image")
    with Image.new(mode, size) as image, BytesIO() as output:
        options = {}
        if metadata:
            exif = Image.Exif()
            exif[0x010E] = "image-metadata-sentinel"
            options = dict(exif=exif, comment=b"image-metadata-sentinel",
                           icc_profile=b"image-metadata-sentinel")
        image.save(output, format="JPEG", **options)
        return output.getvalue()


def estimate_bytes():
    return json.dumps({
        "schemaVersion": 1, "estimateId": "estimate-1", "mealName": "rice",
        "ingredients": [{
            "id": "food-1", "name": "rice", "massGrams": {"min": 80, "max": 120},
            "preparationState": "COOKED", "carbohydrateBasis": "UNKNOWN",
            "carbohydrates": None, "protein": None, "fat": None, "fiber": None,
            "sugar": None, "polyols": None, "energy": None,
        }], "suggestedProfile": None, "suggestedDurationMinutes": None,
    }, separators=(",", ":")).encode()


def work(image=None, *, accepted=1000, deadline=60_000):
    raw = jpeg() if image is None else image
    body = json.dumps(dict(schemaVersion=1, mimeType="image/jpeg",
                           imageBase64=base64.b64encode(raw).decode())).encode()
    request = MealPhotoRequest.decode(body)
    policy = meal_vision_policy("vision-fixture-v1")
    digest = request_digest(policy, request_id=str(uuid4()), deadline_ms=deadline,
                            request=request, body=body)
    return JobWork(str(uuid4()), request, digest, policy.route_revision, deadline,
                   policy, JobIdentity(str(uuid4()), "a" * 64), accepted)


class TypedPeer:
    def __init__(self, *, output=None, stopped=True):
        self.output = output if output is not None else estimate_bytes()
        self.stopped = stopped
        self.calls = []
        self.stops = 0

    async def estimate(self, turn):
        self.calls.append(turn)
        return MealVisionReply(turn.binding, self.output)

    async def stop_and_confirm(self):
        self.stops += 1
        return self.stopped


@pytest.mark.parametrize("mode", ["L", "RGB", "CMYK"])
def test_normalizer_decodes_fresh_rgb_without_metadata(mode):
    Image = pytest.importorskip("PIL.Image")
    raw = jpeg(mode=mode, metadata=True)
    clean = images.normalize_meal_jpeg(raw)
    assert (clean.width, clean.height) == (32, 24)
    assert b"image-metadata-sentinel" not in clean.jpeg
    assert "jpeg=" not in repr(clean)
    with Image.open(BytesIO(clean.jpeg)) as decoded:
        decoded.load()
        assert decoded.mode == "RGB"
        assert not decoded.getexif()
        assert not set(decoded.info) & {"exif", "icc_profile", "comment", "xmp"}


@pytest.mark.parametrize("raw", [b"", b"not-an-image", b"\xff\xd8\xffbad\xff\xd9",
                                  b"\xff\xd8\xff" + b"x" * (1024 * 1024) + b"\xff\xd9"])
def test_malformed_images_are_rejected_without_diagnostics(raw):
    with pytest.raises(images.MealVisionImageError) as error:
        images.normalize_meal_jpeg(raw)
    assert str(error.value) in {"vision_image_rejected", "vision_decoder_unavailable"}
    assert "bad" not in str(error.value)


def test_other_format_truncation_and_dimensions_are_rejected():
    raw = jpeg()
    with pytest.raises(images.MealVisionImageError):
        images.normalize_meal_jpeg(raw[:len(raw) // 2] + b"\xff\xd9")
    for width, height in [(1601, 24), (32, 1601), (65535, 65535)]:
        changed = bytearray(raw)
        sof = changed.index(b"\xff\xc0")
        changed[sof + 5:sof + 7] = height.to_bytes(2, "big")
        changed[sof + 7:sof + 9] = width.to_bytes(2, "big")
        with pytest.raises(images.MealVisionImageError):
            images.normalize_meal_jpeg(bytes(changed))
    Image = pytest.importorskip("PIL.Image")
    with Image.new("RGB", (4, 4)) as image, BytesIO() as out:
        image.save(out, format="PNG")
        with pytest.raises(images.MealVisionImageError):
            images.normalize_meal_jpeg(b"\xff\xd8\xff" + out.getvalue() + b"\xff\xd9")


def test_pixel_budget_and_encoded_output_budget(monkeypatch):
    monkeypatch.setattr(images, "MAX_IMAGE_PIXELS", 32 * 24 - 1)
    with pytest.raises(images.MealVisionImageError, match="dimensions"):
        images.normalize_meal_jpeg(jpeg())
    monkeypatch.setattr(images, "MAX_IMAGE_PIXELS", 1600 * 1600)
    Image = pytest.importorskip("PIL.Image")
    with Image.effect_noise((256, 256), 100) as image, BytesIO() as out:
        image.save(out, format="JPEG", quality=5)
        raw = out.getvalue()
    monkeypatch.setattr(images, "MAX_IMAGE_BYTES", len(raw) + 32)
    with pytest.raises(images.MealVisionImageError, match="output_limit"):
        images.normalize_meal_jpeg(raw)


def test_optional_decoder_missing_version_or_permissive_config_fail_closed(monkeypatch):
    import builtins
    original = builtins.__import__

    def missing(name, *args, **kwargs):
        if name == "PIL":
            raise ImportError("decoder-missing")
        return original(name, *args, **kwargs)

    raw = b"\xff\xd8\xffbad\xff\xd9"
    with monkeypatch.context() as patch:
        patch.setattr(builtins, "__import__", missing)
        with pytest.raises(images.MealVisionImageError, match="decoder_unavailable"):
            images.normalize_meal_jpeg(raw)
    PIL = pytest.importorskip("PIL")
    with monkeypatch.context() as patch:
        patch.setattr(PIL, "__version__", "unsupported")
        with pytest.raises(images.MealVisionImageError, match="configuration"):
            images.normalize_meal_jpeg(raw)
    ImageFile = pytest.importorskip("PIL.ImageFile")
    monkeypatch.setattr(ImageFile, "LOAD_TRUNCATED_IMAGES", True)
    with pytest.raises(images.MealVisionImageError, match="configuration"):
        images.normalize_meal_jpeg(raw)


def test_prompt_uses_per_100g_facts_not_portion_totals():
    assert "PER 100 GRAMS" in FOOD_ONLY_PROMPT
    assert "not totals for the portion" in FOOD_ONLY_PROMPT
    assert "estimated grams for that ingredient portion" not in FOOD_ONLY_PROMPT


def test_unconnected_native_state_is_explicit_and_has_no_fallback():
    current = work(b"\xff\xd8\xffsynthetic\xff\xd9")
    worker = MealVisionWorker(current, clock_ms=lambda: 2000)
    with pytest.raises(MealVisionError, match="transport_unconnected"):
        asyncio.run(worker.run())
    assert len(NATIVE_VISION_BLOCKERS) == 4
    assert asyncio.run(worker.stop_and_confirm()) is True
    with pytest.raises(MealVisionError, match="already_used"):
        asyncio.run(worker.run())


def test_one_immutable_food_turn_and_typed_result(caplog):
    async def scenario():
        peer = TypedPeer()
        worker = MealVisionWorker(work(jpeg(metadata=True)), clock_ms=lambda: 2000, transport=peer)
        assert await worker.run() == json.loads(estimate_bytes())
        assert peer.stops == len(peer.calls) == 1
        turn = peer.calls[0]
        assert turn.prompt == FOOD_ONLY_PROMPT == worker.work.policy.system_prompt
        assert b"image-metadata-sentinel" not in turn.image.jpeg
        assert "history" not in vars(turn)
        with pytest.raises(FrozenInstanceError):
            turn.image.jpeg = b"changed"
        with pytest.raises(FrozenInstanceError):
            turn.binding.model = "changed"
        with pytest.raises(MealVisionError, match="already_used"):
            await worker.run()
        assert await worker.stop_and_confirm() is True
        assert peer.stops == 1
    asyncio.run(scenario())
    assert caplog.text == ""


@pytest.mark.parametrize("change", [
    lambda w: replace(w, policy=CHAT_POLICY),
    lambda w: replace(w, route_revision="wrong-route"),
    lambda w: replace(w, policy=replace(w.policy, allow_tools=True)),
    lambda w: replace(w, policy=replace(w.policy, system_prompt="custom")),
    lambda w: replace(w, request_digest="wrong"),
    lambda w: replace(w, deadline_ms=100_000),
    lambda w: replace(w, identity=None),
    lambda w: replace(w, request=replace(w.request, schema_version=True)),
    lambda w: replace(w, request=replace(w.request, image_bytes=bytearray(w.request.image_bytes))),
])
def test_work_policy_and_identity_are_bound(change):
    with pytest.raises(ValueError):
        MealVisionWorker(change(work()), clock_ms=lambda: 2000, transport=TypedPeer())


@pytest.mark.parametrize("field,value", [
    ("job_id", "00000000-0000-0000-0000-000000000001"),
    ("request_digest", "b" * 64), ("route_revision", "another-route"),
    ("model", "another-model"), ("deadline_ms", 55_000),
    ("identity", JobIdentity("00000000-0000-0000-0000-000000000001", "b" * 64)),
])
def test_reply_identity_mismatch_stops_and_never_retries(field, value):
    class WrongPeer(TypedPeer):
        async def estimate(self, turn):
            reply = await super().estimate(turn)
            return replace(reply, binding=replace(turn.binding, **{field: value}))
    peer = WrongPeer()
    worker = MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer)
    with pytest.raises(MealVisionError, match="reply_rejected"):
        asyncio.run(worker.run())
    assert peer.stops == len(peer.calls) == 1


@pytest.mark.parametrize("output", [
    b"", b"x" * (MAX_RESULT_BYTES + 1), b'{"text":"advice"}',
    b'{"schemaVersion":1,"schemaVersion":1}', b'{"mass":NaN}',
    b'[]', b'null', b'\xff', b'```json\n{}\n```', {"text": "wrong-type"},
])
def test_invalid_outputs_are_not_estimates(output, caplog):
    peer = TypedPeer(output=output)
    worker = MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer)
    with pytest.raises(MealVisionError):
        asyncio.run(worker.run())
    assert peer.stops == len(peer.calls) == 1
    assert not caplog.text


@pytest.mark.parametrize("modify", [
    lambda p: p.update(therapy={"action": "forbidden"}),
    lambda p: p.update(ingredients=[]),
    lambda p: p["ingredients"][0].update(massGrams={"min": 120, "max": 80}),
    lambda p: p["ingredients"][0].update(carbohydrates={"min": -1, "max": 5}),
    lambda p: p.update(suggestedProfile="FAST", suggestedDurationMinutes=360),
    lambda p: p.update(suggestedProfile={}),
    lambda p: p["ingredients"][0].update(preparationState=[]),
])
def test_food_schema_semantics_are_validated(modify):
    output = json.loads(estimate_bytes())
    modify(output)
    peer = TypedPeer(output=json.dumps(output).encode())
    with pytest.raises(MealVisionError):
        asyncio.run(MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer).run())
    assert peer.stops == 1


def test_ambiguous_submit_and_raw_exception_are_never_retried_or_logged(caplog):
    class BrokenPeer(TypedPeer):
        async def estimate(self, turn):
            self.calls.append(turn)
            raise RuntimeError("raw-output-sentinel")
    peer = BrokenPeer()
    worker = MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer)
    with pytest.raises(MealVisionError, match="^vision_estimation_failed$"):
        asyncio.run(worker.run())
    with pytest.raises(MealVisionError, match="already_used"):
        asyncio.run(worker.run())
    assert len(peer.calls) == peer.stops == 1
    assert "raw-output-sentinel" not in caplog.text


def test_no_chat_runner_fallback_even_if_injected():
    class TextOnly:
        calls = 0

        async def run(self, *args):
            self.calls += 1
            raise AssertionError("text-route-called")

        async def stop_and_confirm(self):
            return True
    peer = TextOnly()
    with pytest.raises(MealVisionError):
        asyncio.run(MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer).run())
    assert peer.calls == 0


def test_result_is_held_until_independent_stop_and_cancellation_is_drained():
    async def scenario():
        stopping, release = asyncio.Event(), asyncio.Event()

        class SlowStop(TypedPeer):
            async def stop_and_confirm(self):
                self.stops += 1
                stopping.set()
                await release.wait()
                return True
        peer = SlowStop()
        worker = MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer)
        task = asyncio.create_task(worker.run())
        await stopping.wait()
        assert not task.done()
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert peer.stops == 1
    asyncio.run(scenario())


def test_cancelled_or_timed_out_submission_still_stops_once():
    async def scenario(cancel):
        entered = asyncio.Event()

        class SlowPeer(TypedPeer):
            async def estimate(self, turn):
                self.calls.append(turn)
                entered.set()  # Includes interrupted native creation/send.
                await asyncio.Future()
        peer = SlowPeer()
        current = work(deadline=2050 if not cancel else 60_000)
        worker = MealVisionWorker(current, clock_ms=lambda: 2000, transport=peer)
        task = asyncio.create_task(worker.run())
        await entered.wait()
        if cancel:
            task.cancel()
        with pytest.raises(asyncio.CancelledError if cancel else MealVisionError):
            await task
        assert peer.stops == len(peer.calls) == 1
        assert await worker.stop_and_confirm() is True
    asyncio.run(scenario(True))
    asyncio.run(scenario(False))


def test_cancelled_normalization_is_joined_without_image_submission(monkeypatch):
    entered, release = threading.Event(), threading.Event()
    normalize = images.normalize_meal_jpeg

    def paused(raw):
        entered.set()
        assert release.wait(5)
        return normalize(raw)
    monkeypatch.setattr("app.ai.meal_vision.normalize_meal_jpeg", paused)

    async def scenario():
        peer = TypedPeer()
        worker = MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer)
        task = asyncio.create_task(worker.run())
        assert await asyncio.to_thread(entered.wait, 5)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert not peer.calls and not peer.stops
        assert await worker.stop_and_confirm() is True
    try:
        asyncio.run(scenario())
    finally:
        release.set()


def test_late_result_or_stop_cannot_extend_deadline():
    async def scenario():
        now = [2000]

        class LateStop(TypedPeer):
            async def stop_and_confirm(self):
                now[0] = 60_000
                return await super().stop_and_confirm()
        peer = LateStop()
        with pytest.raises(MealVisionError, match="deadline"):
            await MealVisionWorker(work(), clock_ms=lambda: now[0], transport=peer).run()
        assert peer.stops == 1
    asyncio.run(scenario())


@pytest.mark.parametrize("stopped", [False, None, 1])
def test_executor_retains_claim_and_discards_result_for_unknown_stop(tmp_path, stopped):
    current = work()
    ledger = JobLedger(tmp_path / "vision-jobs.sqlite")
    job = ledger.reserve(owner_id=current.identity.owner_id, request_id=str(uuid4()),
                         kind="MEAL_PHOTO", digest=current.request_digest,
                         route_revision=current.route_revision, deadline_ms=current.deadline_ms,
                         now_ms=current.accepted_ms)
    peer = TypedPeer(stopped=stopped)
    worker = MealVisionWorker(replace(current, job_id=job.id), clock_ms=lambda: 2000, transport=peer)
    try:
        outcome = asyncio.run(JobExecutor(ledger, clock_ms=lambda: 2000).execute(
            job.owner_id, job.id, worker))
        assert outcome.job.state == "RUNNING" and outcome.result is None
        assert ledger.get(job.owner_id, job.id).state == "RUNNING"
        assert peer.stops == 1
    finally:
        ledger.engine.dispose()


@pytest.mark.parametrize("error", [RuntimeError("stop-sentinel"), asyncio.CancelledError()])
def test_failed_independent_stop_is_not_confirmation(error):
    class FailedStop(TypedPeer):
        async def stop_and_confirm(self):
            self.stops += 1
            raise error
    peer = FailedStop()
    worker = MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer)
    with pytest.raises(MealVisionError, match="stop_unconfirmed"):
        asyncio.run(worker.run())
    assert asyncio.run(worker.stop_and_confirm()) is False
    assert peer.stops == 1


def test_external_stop_joins_active_estimate_and_prevents_result():
    async def scenario():
        entered = asyncio.Event()
        exited = asyncio.Event()

        class SlowPeer(TypedPeer):
            async def estimate(self, turn):
                self.calls.append(turn)
                entered.set()
                try:
                    await asyncio.Future()
                finally:
                    exited.set()
        peer = SlowPeer()
        worker = MealVisionWorker(work(), clock_ms=lambda: 2000, transport=peer)
        task = asyncio.create_task(worker.run())
        await entered.wait()
        assert await worker.stop_and_confirm() is True
        assert exited.is_set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert peer.stops == 1
    asyncio.run(scenario())
