"""Existing signed route with the actual adapter and a synthetic typed peer."""
import base64
import json
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from app.ai.job_ledger import JobLedger
from app.ai.job_service import AiJobService
from app.ai.meal_vision import MealVisionWorker, meal_vision_policy
from app.ai_server import create_bound_ai_app
from test_ai_activation import NOW, service  # noqa: F401 - shared synthetic fixture
from test_ai_job_transport import activate, signed_get
from test_ai_meal_photo_transport import submit_photo, wait_for_photo
from test_meal_vision import TypedPeer, estimate_bytes, jpeg


@pytest.mark.parametrize("valid_image", [True, False])
def test_signed_route_runs_decoder_and_deduplicates_one_food_turn(service, tmp_path, valid_image):  # noqa: F811
    peer = TypedPeer()
    ledger = JobLedger(tmp_path / "vision-jobs.sqlite")
    jobs = AiJobService(service[1], service[0].proofs, ledger,
                        lambda work: MealVisionWorker(work, clock_ms=lambda: NOW + 2, transport=peer),
                        policy=meal_vision_policy("vision-fixture-v1"), clock_ms=lambda: NOW + 2)
    image = jpeg() if valid_image else b"\xff\xd8\xffsynthetic\xff\xd9"
    body = json.dumps(dict(schemaVersion=1, mimeType="image/jpeg",
                           imageBase64=base64.b64encode(image).decode())).encode()
    try:
        with TestClient(create_bound_ai_app(service[0], meal_photo_jobs=jobs,
                                            clock_ms=lambda: NOW + 2)) as client:
            _, key, tokens = activate(client, service)
            request_id = str(uuid4())
            accepted = submit_photo(client, key, tokens, body, request_id=request_id,
                                    deadline_ms=NOW + 60_000)
            assert accepted.status_code == 202
            finished = wait_for_photo(client, key, tokens, accepted.json()["job_id"]).json()
            assert finished["state"] == ("SUCCEEDED" if valid_image else "FAILED")
            assert finished.get("result") == (json.loads(estimate_bytes()) if valid_image else None)
            duplicate = submit_photo(client, key, tokens, body, request_id=request_id,
                                     deadline_ms=NOW + 60_000)
            assert duplicate.json()["job_id"] == accepted.json()["job_id"]
            assert len(peer.calls) == peer.stops == (1 if valid_image else 0)
            capabilities = signed_get(client, key, tokens, "/api/ai/v1/capabilities").json()
            assert capabilities["task_kinds"] == ["MEAL_PHOTO"]
            assert client.post("/api/ai/v1/jobs", content=body,
                               headers={"Content-Type": "application/json"}).status_code == 404
    finally:
        ledger.engine.dispose()


def test_default_bound_app_does_not_activate_vision(service):  # noqa: F811
    with TestClient(create_bound_ai_app(service[0], clock_ms=lambda: NOW + 2)) as client:
        assert client.post("/api/ai/v1/meal-photo/jobs", content=b"{}").status_code == 404
