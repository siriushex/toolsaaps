import base64
import json
from dataclasses import dataclass
from uuid import uuid4

from fastapi.testclient import TestClient

from app.ai_server import create_bound_ai_app
from test_ai_activation import NOW, device, service, signed
from test_ai_job_transport import activate, proof_headers, signed_get


def photo_body():
    image = b"\xff\xd8\xff\xe0" + b"synthetic" * 4 + b"\xff\xd9"
    return json.dumps({
        "schemaVersion": 1,
        "mimeType": "image/jpeg",
        "imageBase64": base64.b64encode(image).decode("ascii"),
    }, separators=(",", ":")).encode("ascii")


def photo_estimate():
    return {
        "schemaVersion": 1,
        "estimateId": "estimate-transport-1",
        "mealName": "synthetic meal",
        "ingredients": [{
            "id": "ingredient-1",
            "name": "rice",
            "massGrams": {"min": 90.0, "max": 120.0},
            "preparationState": "COOKED",
            "carbohydrateBasis": "TOTAL",
            "carbohydrates": {"min": 22.0, "max": 31.0},
            "protein": None,
            "fat": None,
            "fiber": None,
            "sugar": None,
            "polyols": None,
            "energy": None,
        }],
        "suggestedProfile": "MIXED",
        "suggestedDurationMinutes": 120,
    }


@dataclass
class PhotoWorker:
    work: object
    factory: object

    @property
    def route_revision(self):
        return self.work.route_revision

    @property
    def request_digest(self):
        return self.work.request_digest

    async def run(self):
        self.factory.seen_bytes.append(self.work.request.image_bytes)
        return photo_estimate()

    async def stop_and_confirm(self):
        self.factory.stops.append(self.work.job_id)
        return True


class PhotoFactory:
    def __init__(self):
        self.seen_bytes = []
        self.stops = []

    def __call__(self, work):
        self.work = work
        return PhotoWorker(work, self)


def submit_photo(client, key, tokens, body, *, request_id, deadline_ms, now=NOW + 2):
    from app.ai.job_policy import submit_proof_credential

    path = "/api/ai/v1/meal-photo/jobs"
    credential = submit_proof_credential(tokens["access_token"], request_id=request_id,
                                         deadline_ms=deadline_ms)
    proof = signed(key, path=path, body=body, credential=credential, now=now)
    headers = {"Content-Type": "application/json"} | proof_headers(
        proof, key, access_token=tokens["access_token"], request_id=request_id,
        deadline_ms=deadline_ms)
    return client.post(path, content=body, headers=headers)


def wait_for_photo(client, key, tokens, job_id):
    path = f"/api/ai/v1/meal-photo/jobs/{job_id}"
    for _ in range(100):
        response = signed_get(client, key, tokens, path)
        if response.json()["state"] not in {"QUEUED", "RUNNING", "CANCEL_REQUESTED"}:
            return response
    raise AssertionError("photo job did not reach terminal state")


def test_meal_photo_job_is_separate_from_chat_and_returns_typed_estimate(service, tmp_path):
    from app.ai.job_ledger import JobLedger
    from app.ai.job_policy import MEAL_PHOTO_POLICY
    from app.ai.job_service import AiJobService

    factory = PhotoFactory()
    ledger = JobLedger(tmp_path / "meal-photo.sqlite")
    jobs = AiJobService(service[1], service[0].proofs, ledger, factory,
                        policy=MEAL_PHOTO_POLICY, clock_ms=lambda: NOW + 2)
    with TestClient(create_bound_ai_app(service[0], meal_photo_jobs=jobs,
                                        clock_ms=lambda: NOW + 2)) as client:
        _, key, tokens = activate(client, service)
        capabilities = signed_get(client, key, tokens, "/api/ai/v1/capabilities")
        assert capabilities.status_code == 200
        assert capabilities.json()["task_kinds"] == ["MEAL_PHOTO"]
        body = photo_body()
        too_late = submit_photo(client, key, tokens, body, request_id=str(uuid4()),
                                deadline_ms=NOW + 60_003)
        assert too_late.status_code == 400
        request_id = str(uuid4())
        accepted = submit_photo(client, key, tokens, body, request_id=request_id,
                                deadline_ms=NOW + 60_000)
        assert accepted.status_code == 202
        finished = wait_for_photo(client, key, tokens, accepted.json()["job_id"])
        assert finished.status_code == 200
        assert finished.json()["state"] == "SUCCEEDED"
        assert finished.json()["result"] == photo_estimate()
        assert factory.seen_bytes == [b"\xff\xd8\xff\xe0" + b"synthetic" * 4 + b"\xff\xd9"]
        assert client.post("/api/ai/v1/jobs", content=body,
                           headers={"Content-Type": "application/json"}).status_code == 404
    ledger.engine.dispose()
