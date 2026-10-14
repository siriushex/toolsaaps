import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import urlencode

from test_native_build import load_helper


class SessionExchangeGuards(unittest.TestCase):
    def setUp(self):
        self.module = load_helper("protocol_smoke")

    def test_oauth_form_is_separate_exact_admission(self):
        self.assertTrue(hasattr(self.module, "verify_exchange_request"), "exchange admission missing")
        fields = {"grant_type": "authorization_code", "code": "sample-grant",
                  "redirect_uri": self.module.ORIGIN + "/deviceauth/callback",
                  "client_id": self.module.PUBLIC_CLIENT_ID, "code_verifier": "v" * 64}
        body = urlencode(fields).encode()
        self.module.verify_exchange_request("POST", "/oauth/token", body)
        for method, path, data in (("GET", "/oauth/token", body),
                                   ("POST", "/oauth/token?extra=1", body),
                                   ("POST", "/oauth/token", body + b"&code=sample-grant"),
                                   ("POST", "/oauth/token", body + b"&extra=1"),
                                   ("POST", "/oauth/token", b"x" * 2049),
                                   ("POST", "/oauth/token", urlencode(fields | {"code": "other"}).encode()),
                                   ("POST", "/oauth/token", b"{}")):
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.module.verify_exchange_request(method, path, data)
        with self.assertRaises(ValueError):
            self.module.verify_request("POST", "/oauth/token", body)

    def test_form_headers_require_explicit_exchange_policy(self):
        headers = [("Host", "login.example.test:8443"),
                   ("Content-Type", "application/x-www-form-urlencoded")]
        with self.assertRaises(ValueError):
            self.module.verify_headers(headers)
        try:
            self.module.verify_headers(headers, form=True)
        except TypeError:
            self.fail("separate exchange header policy missing")
        with self.assertRaises(ValueError):
            self.module.verify_headers(headers + [("Authorization", "sample")], form=True)

    def test_auth_file_requires_exact_synthetic_state_and_explicit_policy(self):
        self.assertTrue(hasattr(self.module, "synthetic_auth"), "synthetic session fixture missing")
        with tempfile.TemporaryDirectory(prefix="session-exchange-") as name:
            root = Path(name)
            (root / "profile").mkdir(mode=0o700)
            path = root / "profile" / "auth.json"
            data = self.module.synthetic_auth()
            path.write_text(json.dumps(data))
            path.chmod(0o600)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)
            self.module.verify_private_state(root, synthetic_session=True)
            for altered in (data | {"unexpected": True}, data | {"OPENAI_API_KEY": "sample"},
                            data | {"last_refresh": "2000-01-01T00:00:00Z"},
                            data | {"tokens": data["tokens"] | {"refresh_token": "other"}}):
                path.write_text(json.dumps(altered))
                with self.assertRaises(ValueError):
                    self.module.verify_private_state(root, synthetic_session=True)
            path.write_text(json.dumps(data))
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root, synthetic_session=True)
            path.chmod(0o600)
            path.rename(root / "auth.json")
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root, synthetic_session=True)

    def test_synthetic_policy_requires_session_and_rejects_duplicate_json_or_orphan(self):
        self.assertTrue(hasattr(self.module, "synthetic_auth"), "synthetic session fixture missing")
        with tempfile.TemporaryDirectory(prefix="session-exchange-") as name:
            root = Path(name)
            (root / "profile").mkdir(mode=0o700)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root, synthetic_session=True)
            path = root / "profile" / "auth.json"
            data = json.dumps(self.module.synthetic_auth())
            path.write_text(data[:-1] + ',"OPENAI_API_KEY":null}')
            path.chmod(0o600)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root, synthetic_session=True)
            path.write_text(data)
            orphan = root / "profile" / ".tmpSample"
            orphan.write_text("synthetic")
            orphan.chmod(0o600)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root, synthetic_session=True)

    def test_synthetic_native_command_keeps_all_authenticated_destinations_local(self):
        command = self.module.native_command()
        self.assertIn('chatgpt_base_url="https://login.example.test:8443/backend-api"', command)
        self.assertNotIn("--model", command)
        self.assertNotIn("--dangerously-bypass-approvals-and-sandbox", command)

    def test_completion_requires_matching_id_and_actual_outcome(self):
        self.assertTrue(hasattr(self.module, "verify_exchange_completion"), "completion guard missing")
        params = {"loginId": "sample-login", "success": True, "error": None}
        self.module.verify_exchange_completion(params, "sample-login", success=True)
        self.module.verify_exchange_completion(params | {"success": False, "error": "sample failure"},
                                               "sample-login", success=False)
        for altered in (params | {"loginId": "other"}, params | {"success": False},
                        params | {"success": 1}, params | {"error": "sample failure"}):
            with self.assertRaises(ValueError):
                self.module.verify_exchange_completion(altered, "sample-login", success=True)

    def test_account_readback_must_match_synthetic_claims_not_api_key_or_other_identity(self):
        self.assertTrue(hasattr(self.module, "verify_account"), "account readback guard missing")
        data = {"account": {"type": "chatgpt", "email": "sample@example.invalid", "planType": "plus"},
                "requiresOpenaiAuth": True}
        self.module.verify_account(data, authenticated=True)
        self.module.verify_account(data | {"account": None}, authenticated=False)
        for altered in (data | {"account": {"type": "apiKey"}},
                        data | {"account": data["account"] | {"email": "other@example.invalid"}},
                        data | {"requiresOpenaiAuth": False}, data | {"extra": True}):
            with self.assertRaises(ValueError):
                self.module.verify_account(altered, authenticated=True)

    def test_exchange_receipt_is_exact_order_and_never_accepts_retry_or_fallback(self):
        self.assertTrue(hasattr(self.module, "verify_exchange_events"), "exchange ordering guard missing")
        events = [self.module.USERCODE, self.module.POLL, "/oauth/token"]
        self.module.verify_exchange_events(events)
        for altered in (events[:-1], events[::-1], events + ["/oauth/token"],
                        events + ["/backend-api/wham/config/bundle"]):
            with self.assertRaises(ValueError):
                self.module.verify_exchange_events(altered)

    def test_native_status_readback_is_exact_and_never_an_api_key_success(self):
        self.assertTrue(hasattr(self.module, "verify_status"), "status readback guard missing")
        self.module.verify_status(0, b"", b"Logged in using ChatGPT\n")
        for code, output, error in ((1, b"", b"Logged in using ChatGPT\n"),
                                     (0, b"sample", b"Logged in using ChatGPT\n"),
                                     (0, b"", b"Logged in using an API key\n"),
                                     (0, b"", b"Not logged in\n")):
            with self.assertRaises(ValueError):
                self.module.verify_status(code, output, error)


if __name__ == "__main__":
    unittest.main()
