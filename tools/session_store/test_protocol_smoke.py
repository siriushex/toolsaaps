import json
from pathlib import Path
import tempfile
import subprocess
import sys
import selectors
import unittest

from test_native_build import load_helper
from unittest.mock import patch


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
        self.assertEqual(self.module.verify_trace(trace), {"execs": 1, "connections": 1, "absent_os_lookups": 0,
                                                         "absent_name_cache_lookups": 0})
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

    def test_early_native_exit_reports_bounded_diagnostic_without_waiting_for_timeout(self):
        rpc = self.module.Rpc.__new__(self.module.Rpc)
        rpc.process = subprocess.Popen([sys.executable, "-c",
                                        "import sys; sys.stderr.write('sample startup failure'); sys.exit(2)"],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.PIPE,
                                       start_new_session=True, bufsize=0)
        rpc.selector = selectors.DefaultSelector()
        for stream in (rpc.process.stdout, rpc.process.stderr):
            rpc.selector.register(stream, selectors.EVENT_READ)
        rpc.buffer, rpc.total, rpc.messages, rpc.error = b"", 0, [], b""
        try:
            with self.assertRaisesRegex(ValueError, "native process exited.*sample startup failure"):
                rpc.wait(lambda _: False, timeout=1)
        finally:
            rpc.close()

    def test_native_environment_is_closed_and_restricts_allocator_and_command_search(self):
        self.assertTrue(hasattr(self.module, "native_environment"), "native environment admission missing")
        env = self.module.native_environment(Path("/tmp/cancel"), trust=True)
        self.assertEqual(env["PATH"], "/work/empty")
        self.assertEqual(env["MALLOC_ARENA_MAX"], "2")
        self.assertEqual(env["SSL_CERT_FILE"], "/work/ca.pem")
        self.assertNotIn("SSL_CERT_FILE", self.module.native_environment(Path("/tmp/cancel"), trust=False))
        self.assertEqual(len(env), 8)

    def test_only_failed_bounded_os_lookup_is_admitted_not_an_executed_tool(self):
        trace = '42 execve("/work/client", ["/work/client", "app-server"], 0x1) = 0\n'
        failure = ('43 execve("/tmp/cancel/profile/tmp/arg0/helper-ABC123/lsb_release", '
                   '["lsb_release", "-a"], 0x1) = -1 ENOENT (No such file or directory)\n')
        with patch.object(self.module, "ALIAS_PREFIX", "helper-", create=True):
            self.assertEqual(self.module.verify_trace(trace + failure),
                             {"execs": 1, "connections": 0, "absent_os_lookups": 1,
                              "absent_name_cache_lookups": 0})
            for line in (failure.replace("= -1 ENOENT (No such file or directory)", "= 0"),
                         failure.replace("lsb_release", "other_tool"), failure * 17):
                with self.assertRaises(ValueError):
                    self.module.verify_trace(trace + line)

    def test_no_client_preflight_has_a_separate_fixed_tracer_entrypoint(self):
        trace = '42 execve("/usr/bin/python3", ["python3"], 0x1) = 0\n'
        try:
            receipt = self.module.verify_trace(trace, expected_executable="/usr/bin/python3")
        except TypeError:
            self.fail("separate preflight trace admission missing")
        self.assertEqual(receipt["execs"], 1)
        with self.assertRaises(ValueError):
            self.module.verify_trace(trace)
        with self.assertRaises(ValueError):
            self.module.verify_trace(trace, expected_executable="/bin/sh")

    def test_only_missing_name_cache_socket_is_admitted_never_a_service_connection(self):
        trace = '42 execve("/work/client", ["/work/client"], 0x1) = 0\n'
        absent = ('43 connect(3, {sa_family=AF_UNIX, sun_path="/var/run/nscd/socket"}, 110) '
                  '= -1 ENOENT (No such file or directory)\n')
        try:
            receipt = self.module.verify_trace(trace + absent)
        except ValueError:
            self.fail("missing name-cache compatibility admission")
        self.assertEqual(receipt["absent_name_cache_lookups"], 1)
        for change in (absent.replace("= -1 ENOENT (No such file or directory)", "= 0"),
                       absent.replace("/var/run/nscd/socket", "/run/other"), absent * 9):
            with self.assertRaises(ValueError):
                self.module.verify_trace(trace + change)

    def test_native_disabled_status_notification_is_not_a_tool_request(self):
        self.assertTrue(hasattr(self.module, "verify_message"), "notification admission missing")
        message = {"method": "remoteControl/status/changed", "params": {
            "status": "disabled", "serverName": "session-peer", "environmentId": None,
            "installationId": "7c491cac-03f3-46a6-96b2-75b34d27e551"}}
        self.module.verify_message(message)
        for bad in (message | {"id": 1}, message | {"method": "tool/call"},
                    message | {"params": message["params"] | {"status": "connected"}},
                    message | {"params": message["params"] | {"environmentId": "other"}}):
            with self.assertRaises(ValueError):
                self.module.verify_message(bad)

    def test_per_process_trace_is_read_without_interleaved_syscalls(self):
        self.assertTrue(hasattr(self.module, "read_trace"), "per-process trace reader missing")
        with tempfile.TemporaryDirectory(prefix="process-trace-") as name:
            prefix = Path(name) / "trace"
            for identity, content in ((12, "connect complete\n"), (8, "exec complete\n")):
                file = prefix.with_name(f"trace.{identity}")
                file.write_text(content)
                file.chmod(0o600)
            self.assertEqual(self.module.read_trace(prefix), "exec complete\nconnect complete\n")
            (prefix.parent / "trace.12").chmod(0o644)
            with self.assertRaisesRegex(ValueError, "process trace metadata rejected.*mode=0644"):
                self.module.read_trace(prefix)
            (prefix.parent / "trace.12").chmod(0o600)
            (prefix.parent / "trace.12").write_bytes(b"x" * (1024 * 1024 + 1))
            with self.assertRaises(ValueError):
                self.module.read_trace(prefix)
            (prefix.parent / "trace.12").unlink()
            (prefix.parent / "trace.12").symlink_to(prefix.parent / "trace.8")
            with self.assertRaises(ValueError):
                self.module.read_trace(prefix)

    def test_unexpected_execution_reports_only_bounded_command_context(self):
        trace = '42 execve("/work/client", ["/work/client"], 0x1) = 0\n'
        with self.assertRaisesRegex(ValueError, "unexpected child execution.*other_tool"):
            self.module.verify_trace(trace + '43 execve("/work/other_tool", ["other_tool"], 0x1) = 0\n')

    def test_native_command_disables_plugins_without_changing_model_or_permission_mode(self):
        self.assertTrue(hasattr(self.module, "native_command"), "fixed auth-only command missing")
        command = self.module.native_command()
        self.assertEqual(command[0], "/work/client")
        self.assertEqual(command[-1], "app-server")
        self.assertIn("features.plugins=false", command)
        self.assertFalse(any("model=" in value or "sandbox_mode=" in value for value in command))

    def test_getconf_lookup_may_fail_but_git_or_successful_helpers_are_refused(self):
        trace = '42 execve("/work/client", ["/work/client"], 0x1) = 0\n'
        absent = ('43 execve("/work/empty/getconf", ["getconf", "LONG_BIT"], 0x1) '
                  '= -1 ENOENT (No such file or directory)\n')
        try:
            receipt = self.module.verify_trace(trace + absent)
        except ValueError:
            self.fail("missing pinned bitness-helper failure admission")
        self.assertEqual(receipt["absent_os_lookups"], 1)
        for bad in (absent.replace("= -1 ENOENT (No such file or directory)", "= 0"),
                    absent.replace("getconf", "git"), absent * 17):
            with self.assertRaises(ValueError):
                self.module.verify_trace(trace + bad)

    def test_only_native_noncredential_installation_uuid_has_a_public_file_mode(self):
        with tempfile.TemporaryDirectory(prefix="installation-state-") as name:
            root = Path(name)
            profile = root / "profile"
            profile.mkdir(mode=0o700)
            path = profile / "installation_id"
            path.write_text("7c491cac-03f3-46a6-96b2-75b34d27e551")
            path.chmod(0o644)
            try:
                receipt = self.module.verify_private_state(root)
            except ValueError:
                self.fail("pinned noncredential installation metadata admission missing")
            self.assertEqual(receipt["files"], 1)
            path.write_text("not-a-canonical-uuid")
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)
            path.write_text("7c491cac-03f3-46a6-96b2-75b34d27e551")
            path.chmod(0o664)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)
            path.chmod(0o644)
            profile.chmod(0o755)
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)
            profile.chmod(0o700)
            path.rename(profile / "other_file")
            with self.assertRaises(ValueError):
                self.module.verify_private_state(root)


if __name__ == "__main__":
    unittest.main()
