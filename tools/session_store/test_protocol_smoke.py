import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest

from test_native_build import load_helper


class ProtocolGuards(unittest.TestCase):
    def setUp(self):
        self.module = load_helper("protocol_smoke")

    def headers(self, **extra):
        return {"Host": "login.example.test:8443", "Content-Type": "application/json", **extra}

    def test_neutral_or_absent_identity_admitted(self):
        for extra in ({}, {"User-Agent": "personal-ai/0.153.4", "originator": "personal-ai"}):
            self.module.verify_headers(list(self.headers(**extra).items()))

    def test_reserved_identity_or_credentials_refused(self):
        for extra in ({"User-Agent": "other/1"}, {"originator": "other"},
                      {"Authorization": "sample"},
                      {"Cookie": "sample=value"}, {"Proxy-Authorization": "sample"},
                      {"Host": "other.example.test"}, {"Content-Type": "text/plain"}):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                self.module.verify_headers(list(self.headers(**extra).items()))

    def test_duplicate_headers_refused(self):
        with self.assertRaises(ValueError):
            self.module.verify_headers(list(self.headers().items()) + [("host", "other")])

    def test_only_exact_post_routes_and_synthetic_bodies_admitted(self):
        self.module.verify_request("POST", "/api/accounts/deviceauth/usercode",
                                   json.dumps({"client_id": self.module.PUBLIC_CLIENT_ID}).encode())
        self.module.verify_request("POST", "/api/accounts/deviceauth/token",
                                   b'{"device_auth_id":"sample-session","user_code":"SAMPLE-42"}')
        for method, path, body in (("GET", "/api/accounts/deviceauth/usercode", b"{}"),
                                   ("POST", "/oauth/token", b"{}"),
                                   ("POST", "/api/accounts/deviceauth/usercode?extra=1", b"{}"),
                                   ("POST", "/api/accounts/deviceauth/token", b"{}"),
                                   ("POST", "/api/accounts/deviceauth/token", b"{"),
                                   ("POST", "/api/accounts/deviceauth/usercode", b"x" * 2049)):
            with self.subTest(path=path, body=body[:20]), self.assertRaises(ValueError):
                self.module.verify_request(method, path, body)

    def test_initialization_identity_must_be_neutral(self):
        self.module.verify_initialize({"userAgent": "personal-ai/0.153.4 (Linux) (personal-ai; 1)"})
        for response in ({}, {"userAgent": "other/1"}, {"userAgent": "personal-ai invalid"}):
            with self.assertRaises(ValueError):
                self.module.verify_initialize(response)

    def test_device_code_response_is_exact_synthetic_protocol(self):
        response = {"type": "chatgptDeviceCode", "loginId": "2d779e88-6968-4c26-9cf5-bb04538c704c",
                    "verificationUrl": "https://login.example.test:8443/codex/device", "userCode": "SAMPLE-42"}
        self.module.verify_start(response)
        for key, value in (("type", "apiKey"), ("loginId", "bad"), ("userCode", "other"),
                           ("verificationUrl", "https://other.example.test/")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.module.verify_start(response | {key: value})

    def test_trace_admits_only_mounted_client_and_peer(self):
        trace = ('42 execve("/work/client", ["/work/client", "app-server"], 0x1) = 0\n'
                 '43 connect(7, {sa_family=AF_INET, sin_port=htons(8443), '
                 'sin_addr=inet_addr("127.0.0.1")}, 16) = -1 EINPROGRESS\n')
        self.assertEqual(self.module.verify_trace(trace), {"execs": 1, "connections": 1})
        for extra in ('44 execve("/bin/sh", ["sh"], 0x1) = 0\n',
                      '44 connect(7, {sa_family=AF_INET, sin_port=htons(443), sin_addr=inet_addr("8.8.8.8")}, 16) = 0\n',
                      '44 connect(7, {sa_family=AF_UNIX, sun_path="/run/socket"}, 10) = 0\n'):
            with self.assertRaises(ValueError):
                self.module.verify_trace(trace + extra)
        with self.assertRaises(ValueError):
            self.module.verify_trace("")

    def test_private_bounded_state_without_authorization(self):
        with tempfile.TemporaryDirectory(prefix="protocol-state-") as name:
            root = Path(name)
            file = root / "state.sqlite"
            file.write_bytes(b"synthetic")
            file.chmod(0o600)
            self.assertEqual(self.module.verify_private_state(root)["files"], 1)
            file.chmod(0o644)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)
            file.chmod(0o600)
            auth = root / "auth.json"
            auth.touch(mode=0o600)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)
            auth.unlink()
            link = root / "unexpected"
            link.symlink_to("/work/client")
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)
            link.unlink()
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root, max_bytes=1)
            root.chmod(0o755)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)

    def test_help_never_starts_a_client(self):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("protocol_smoke.py")), "--help"],
                                capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0)
        self.assertIn(b"--preflight", result.stdout)

    def test_private_dns_mapping_must_be_exact_and_readable_after_uid_drop(self):
        self.assertTrue(hasattr(self.module, "verify_name_mapping"), "name-map admission missing")
        with tempfile.TemporaryDirectory(prefix="peer-mapping-") as name:
            root = Path(name)
            for filename, content in (("hosts", "127.0.0.1 login.example.test\n"),
                                      ("nsswitch.conf", "hosts: files\n")):
                path = root / filename
                path.write_text(content)
                path.chmod(0o644)
            self.module.verify_name_mapping(root)
            (root / "hosts").chmod(0o600)
            with self.assertRaises(ValueError):
                self.module.verify_name_mapping(root)
            (root / "hosts").chmod(0o644)
            (root / "nsswitch.conf").write_text("hosts: files dns\n")
            with self.assertRaises(ValueError):
                self.module.verify_name_mapping(root)


if __name__ == "__main__":
    unittest.main()
