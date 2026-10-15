"""Synthetic-only native login protocol test; requires a prepared Linux namespace."""

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import resource
import selectors
import signal
import socket
import ssl
import stat
import subprocess
import threading
import time
import uuid
from urllib.parse import parse_qsl

from offline_smoke import ALIASES, ALIAS_PREFIX, verify_isolation


ORIGIN = "https://login.example.test:8443"
USERCODE = "/api/accounts/deviceauth/usercode"
POLL = "/api/accounts/deviceauth/token"
OAUTH = "/oauth/token"
# Public application identifier from the pinned native protocol, not a credential.
PUBLIC_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
SAMPLE_CODE = "SAMPLE-42"
MODES = ("cancel", "terminate", "untrusted", "wrong-name", "refused", "malformed")
EXCHANGE_MODES = ("exchange", "exchange-refused", "exchange-malformed", "invalid-identity")
OS_LOOKUPS = {"lsb_release": "-a", "getconf": "LONG_BIT"}


def native_command() -> list[str]:
    return ["/work/client", "-c", 'cli_auth_credentials_store="file"',
            "-c", "analytics.enabled=false", "-c", "check_for_update_on_startup=false",
            "-c", "features.plugins=false",
            "-c", f'chatgpt_base_url="{ORIGIN}/backend-api"', "app-server"]


def native_environment(root: Path, *, trust: bool) -> dict:
    # Native compatibility names, never an inherited or existing owner profile.
    env = {"HOME": str(root / "home"), "CODEX_HOME": str(root / "profile"),
           "TMPDIR": str(root / "temporary"), "PATH": "/work/empty", "RUST_LOG": "off",
           "CODEX_APP_SERVER_LOGIN_ISSUER": ORIGIN, "MALLOC_ARENA_MAX": "2"}
    if trust:
        env["SSL_CERT_FILE"] = "/work/ca.pem"
    return env


def verify_name_mapping(etc: Path) -> None:
    for name, content in (("hosts", "127.0.0.1 login.example.test\n"),
                          ("nsswitch.conf", "hosts: files\n")):
        path = etc / name
        metadata = path.lstat()
        if (not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o644
                or path.read_text() != content):
            raise ValueError("synthetic file-only name mapping rejected")


def verify_headers(items: list[tuple[str, str]], *, form: bool = False) -> None:
    headers = {key.lower(): value for key, value in items}
    if len(headers) != len(items):
        raise ValueError("duplicate HTTP headers")
    content_type = "application/x-www-form-urlencoded" if form else "application/json"
    if headers.get("host") != "login.example.test:8443" or headers.get("content-type") != content_type:
        raise ValueError("unexpected HTTP authority or content type")
    if any(key in headers for key in ("authorization", "proxy-authorization", "cookie")):
        raise ValueError("unexpected credential header")
    if headers.get("originator", "personal-ai") != "personal-ai":
        raise ValueError("unexpected originator")
    agent = headers.get("user-agent", "personal-ai/0.153.4")
    if not agent.startswith("personal-ai/0.153.4") or re.search("codex|skylink|argos", agent, re.I):
        raise ValueError("unexpected user agent")


def verify_request(method: str, path: str, body: bytes) -> None:
    if method != "POST" or path not in (USERCODE, POLL) or len(body) > 2048:
        raise ValueError("unexpected HTTP method, path or size")
    try:
        data = json.loads(body)
    except (ValueError, UnicodeError) as error:
        raise ValueError("invalid synthetic request") from error
    expected = ({"client_id": PUBLIC_CLIENT_ID} if path == USERCODE else
                {"device_auth_id": "sample-session", "user_code": SAMPLE_CODE})
    if data != expected:
        raise ValueError("unexpected synthetic request body")


def verify_exchange_request(method: str, path: str, body: bytes) -> None:
    if path in (USERCODE, POLL):
        verify_request(method, path, body)
        return
    if method != "POST" or path != OAUTH or len(body) > 2048:
        raise ValueError("unexpected exchange route or size")
    expected = {"grant_type": "authorization_code", "code": "sample-grant",
                "redirect_uri": ORIGIN + "/deviceauth/callback",
                "client_id": PUBLIC_CLIENT_ID, "code_verifier": "v" * 64}
    try:
        pairs = parse_qsl(body.decode("ascii"), strict_parsing=True, max_num_fields=8)
    except (ValueError, UnicodeError):
        raise ValueError("invalid synthetic grant form") from None
    if len(pairs) != len(expected) or dict(pairs) != expected:
        raise ValueError("unexpected synthetic grant body")


def synthetic_tokens() -> dict:
    # Unsigned test claims, never usable with a real provider. No real identity.
    claims = {"email": "sample@example.invalid", "exp": 4102444800,
              "https://api.openai.com/auth": {"chatgpt_account_id": "sample-account",
                                              "chatgpt_user_id": "sample-user",
                                              "chatgpt_plan_type": "plus"}}
    encoded = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=").decode()
    token = "eyJhbGciOiJub25lIn0." + encoded + ".sample-signature"
    return {"id_token": token, "access_token": token, "refresh_token": "sample-refresh"}


def synthetic_auth() -> dict:
    return {"auth_mode": "chatgpt", "OPENAI_API_KEY": None,
            "tokens": synthetic_tokens() | {"account_id": "sample-account"},
            "last_refresh": datetime.now(timezone.utc).isoformat()}


def unique_object(pairs: list[tuple]) -> dict:
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("duplicate synthetic session field")
    return result


def verify_synthetic_auth(path: Path) -> None:
    metadata = path.lstat()
    if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_nlink != 1
            or metadata.st_size > 4096):
        raise ValueError("synthetic session metadata rejected")
    try:
        data = json.loads(path.read_bytes(), object_pairs_hook=unique_object)
        refreshed = datetime.fromisoformat(data["last_refresh"].replace("Z", "+00:00"))
        age = (datetime.now(timezone.utc) - refreshed).total_seconds()
        expected = synthetic_auth()
        expected["last_refresh"] = data["last_refresh"]
        if not -2 <= age <= 60 or data != expected:
            raise ValueError
    except (ValueError, UnicodeError, TypeError, KeyError, AttributeError):
        raise ValueError("synthetic session content rejected") from None


def verify_initialize(result: dict) -> None:
    agent = result.get("userAgent", "")
    if (not agent.startswith("personal-ai/0.153.4 ") or "(personal-ai; 1)" not in agent
            or re.search("codex|skylink|argos", agent, re.I)):
        raise ValueError("initialization identity mismatch")


def verify_message(message: dict) -> None:
    if "method" not in message:
        return
    method = message["method"]
    if "id" in message or not isinstance(method, str):
        raise ValueError("unexpected RPC request")
    if method == "remoteControl/status/changed":
        params = message.get("params", {})
        if params.get("status") != "disabled" or params.get("environmentId") is not None:
            raise ValueError("remote control is not disabled")
    elif method not in {"account/login/completed", "account/updated", "configWarning"}:
        raise ValueError("unexpected RPC notification: " + method[:128])


def verify_start(result: dict) -> str:
    if (result.get("type") != "chatgptDeviceCode" or result.get("userCode") != SAMPLE_CODE
            or result.get("verificationUrl") != ORIGIN + "/codex/device"):
        raise ValueError("synthetic device-code response mismatch")
    try:
        value = result["loginId"]
        if str(uuid.UUID(value)) != value:
            raise ValueError("noncanonical login identity")
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError("invalid login identity") from error
    return value


def verify_exchange_completion(params: dict, login_id: str, *, success: bool) -> None:
    if (params.get("loginId") != login_id or params.get("success") is not success
            or (success and params.get("error") is not None)
            or (not success and not isinstance(params.get("error"), str))):
        raise ValueError("synthetic exchange completion mismatch")


def verify_account(result: dict, *, authenticated: bool) -> None:
    account = ({"type": "chatgpt", "email": "sample@example.invalid", "planType": "plus"}
               if authenticated else None)
    if (result != {"account": account, "requiresOpenaiAuth": True}
            or result.get("requiresOpenaiAuth") is not True):
        raise ValueError("synthetic account readback mismatch")


def verify_exchange_events(events: list[str]) -> None:
    if events != [USERCODE, POLL, OAUTH]:
        raise ValueError("synthetic exchange order or fallback mismatch")


def verify_status(code: int, output: bytes, error: bytes) -> None:
    if code != 0 or output != b"" or error != b"Logged in using ChatGPT\n":
        raise ValueError("native synthetic status readback mismatch")


def verify_trace(trace: str, *, expected_executable: str = "/work/client") -> dict:
    if expected_executable not in {"/work/client", "/usr/bin/python3"}:
        raise ValueError("unknown tracer entrypoint")
    executions = [line for line in trace.splitlines() if "execve(" in line]
    absent = []
    lookup_counts = dict.fromkeys(OS_LOOKUPS, 0)
    for line in executions:
        path = re.search(r'execve\("([^"]+)", ', line)
        if path and line.endswith("= -1 ENOENT (No such file or directory)"):
            value = path.group(1)
            for program, argument in OS_LOOKUPS.items():
                pattern = (r"/tmp/(?:" + "|".join(MODES + EXCHANGE_MODES) + r")/profile/tmp/arg0/"
                           + re.escape(ALIAS_PREFIX) + r"[A-Za-z0-9]{6}/" + re.escape(program))
                if (f'["{program}", "{argument}"]' in line
                        and (value == f"/work/empty/{program}" or re.fullmatch(pattern, value))):
                    absent.append(line)
                    lookup_counts[program] += 1
                    break
    if any(count > 16 for count in lookup_counts.values()):
        raise ValueError("OS lookup budget exceeded")
    executions = [line for line in executions if line not in absent]
    connections = [line for line in trace.splitlines() if "connect(" in line]
    missing_cache = [line for line in connections
                     if 'sa_family=AF_UNIX, sun_path="/var/run/nscd/socket"' in line
                     and line.endswith("= -1 ENOENT (No such file or directory)")]
    if len(missing_cache) > 8:
        raise ValueError("name-cache lookup budget exceeded")
    connections = [line for line in connections if line not in missing_cache]
    if (len(executions) != 1 or f'execve("{expected_executable}",' not in executions[0]
            or not executions[0].endswith("= 0")):
        raise ValueError("unexpected child execution: " + json.dumps([line[:512] for line in executions[:8]]))
    for line in connections:
        if ("sa_family=AF_INET," not in line or "sin_port=htons(8443)" not in line
                or 'sin_addr=inet_addr("127.0.0.1")' not in line):
            raise ValueError("unexpected connection attempt: " + line[:512])
    return {"execs": len(executions), "connections": len(connections), "absent_os_lookups": len(absent),
            "absent_name_cache_lookups": len(missing_cache)}


def read_trace(prefix: Path) -> str:
    files = list(prefix.parent.glob(prefix.name + ".*"))
    if not files or len(files) > 128:
        raise ValueError("missing or excessive process traces")
    total, parts = 0, []
    for file in files:
        metadata = file.lstat()
        if (not re.fullmatch(re.escape(prefix.name) + r"\.[0-9]+", file.name)
                or not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid()
                or stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_nlink != 1):
            raise ValueError(f"process trace metadata rejected: {file.name[:128]} "
                             f"mode={stat.S_IMODE(metadata.st_mode):04o} uid={metadata.st_uid} "
                             f"links={metadata.st_nlink}")
        total += metadata.st_size
        if total > 1024 * 1024:
            raise ValueError("trace byte budget exceeded")
    for file in sorted(files, key=lambda path: int(path.suffix[1:])):
        parts.append(file.read_text())
    return "".join(parts)


def verify_no_native_processes(proc: Path = Path("/proc")) -> dict:
    inspected, trusted_init = 0, 0
    for directory in proc.iterdir():
        if not directory.name.isdigit():
            continue
        try:
            fields = dict(line.split(":", 1) for line in (directory / "status").read_text().splitlines() if ":" in line)
            uids = fields.get("Uid", "").split()
            if directory.name == "1" and uids == ["0"] * 4 and fields.get("Name", "").strip() == "bwrap":
                trusted_init += 1
                continue
            if uids != ["65534"] * 4:
                raise ValueError("unexpected process identity")
            if os.readlink(directory / "exe") == "/work/client":
                raise ValueError("native client survived teardown")
            inspected += 1
        except FileNotFoundError:
            pass
        except PermissionError:
            raise ValueError("owned process is not inspectable") from None
    if not inspected:
        raise ValueError("missing process observer")
    return {"inspected": inspected, "trusted_init": trusted_init}


def verify_private_state(root: Path, *, max_bytes: int = 32 * 1024 * 1024,
                         synthetic_session: bool = False) -> dict:
    total, count, sessions = 0, 0, 0
    for path in [root, *root.rglob("*")]:
        metadata = path.lstat()
        if metadata.st_uid != os.geteuid():
            raise ValueError("unexpected authorization state or owner")
        if path.name == "auth.json":
            if not synthetic_session or path != root / "profile" / "auth.json":
                raise ValueError("unexpected authorization state or owner")
            verify_synthetic_auth(path)
            sessions += 1
        if synthetic_session and path.parent == root / "profile" and path.name.startswith(".tmp"):
            raise ValueError("synthetic session writer orphan")
        if stat.S_ISLNK(metadata.st_mode):
            if path.name not in ALIASES or os.readlink(path) != "/work/client":
                raise ValueError("unexpected state link")
        elif stat.S_ISDIR(metadata.st_mode):
            if stat.S_IMODE(metadata.st_mode) != 0o700:
                raise ValueError("nonprivate state directory")
        elif stat.S_ISREG(metadata.st_mode):
            mode = stat.S_IMODE(metadata.st_mode)
            native_identity = path == root / "profile" / "installation_id" and mode == 0o644
            if native_identity:
                try:
                    value = path.read_text(encoding="ascii") if metadata.st_size == 36 else ""
                    identity = uuid.UUID(value)
                    if str(identity) != value or identity.version != 4:
                        raise ValueError
                except ValueError:
                    raise ValueError("invalid native installation metadata") from None
            if (mode != 0o600 and not native_identity) or metadata.st_nlink != 1:
                raise ValueError(f"nonprivate state file: {str(path.relative_to(root))[:256]} "
                                 f"mode={stat.S_IMODE(metadata.st_mode):04o}")
            total += metadata.st_size
            count += 1
        else:
            raise ValueError("unexpected state file type")
        if total > max_bytes or count > 128:
            raise ValueError("state budget exceeded")
    if synthetic_session and sessions != 1:
        raise ValueError("missing synthetic session")
    return {"files": count, "bytes": total}


class Peer(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, cert: Path, key: Path, mode: str):
        super().__init__(("127.0.0.1", 8443), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        self.socket = context.wrap_socket(self.socket, server_side=True)
        self.mode, self.events, self.errors = mode, [], []
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_POST(self):
        self.connection.settimeout(2)
        peer = self.server
        try:
            exchange = peer.mode in EXCHANGE_MODES
            verify_headers(list(self.headers.items()), form=exchange and self.path == OAUTH)
            length = int(self.headers.get("Content-Length", "-1"))
            if not 0 <= length <= 2048 or self.headers.get("Transfer-Encoding") or len(peer.events) >= 16:
                raise ValueError("HTTP request budget exceeded")
            validator = verify_exchange_request if exchange else verify_request
            validator(self.command, self.path, self.rfile.read(length))
            peer.events.append(self.path)
            status, body = 403, b"{}"
            if self.path == USERCODE and peer.mode != "refused":
                status = 200
                body = (b"{" if peer.mode == "malformed" else
                        json.dumps({"device_auth_id": "sample-session", "user_code": SAMPLE_CODE,
                                    "interval": "1"}).encode())
            elif exchange and self.path == POLL:
                verifier = "v" * 64
                challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
                status, body = 200, json.dumps({"authorization_code": "sample-grant",
                                               "code_verifier": verifier, "code_challenge": challenge}).encode()
            elif exchange and self.path == OAUTH and peer.mode != "exchange-refused":
                tokens = synthetic_tokens()
                if peer.mode == "invalid-identity":
                    tokens["id_token"] = "invalid-sample-identity"
                status, body = 200, (b"{" if peer.mode == "exchange-malformed" else json.dumps(tokens).encode())
        except (ValueError, OSError):
            peer.errors.append("request rejected")
            status, body = 400, b"{}"
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.server.errors.append("unexpected method")
        self.send_error(405)


class Rpc:
    def __init__(self, root: Path, *, trust: bool, status: bool = False):
        for child in ("home", "profile", "temporary"):
            (root / child).mkdir(mode=0o700, exist_ok=status)
        env = native_environment(root, trust=trust)
        self.trace = root / ("reload-trace" if status else "trace")
        command = native_command()
        if status:
            command = command[:-1] + ["login", "status"]
        self.process = subprocess.Popen(
            ["/usr/bin/strace", "-ff", "-qq", "-s", "256", "-o", str(self.trace),
             "-e", "trace=execve,connect", *command],
            env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True, bufsize=0)
        self.selector = selectors.DefaultSelector()
        for stream in (self.process.stdout, self.process.stderr):
            os.set_blocking(stream.fileno(), False)
            self.selector.register(stream, selectors.EVENT_READ)
        self.buffer, self.total, self.messages, self.error = b"", 0, [], b""

    def send(self, method: str, params: dict, identity: int | None = None):
        data = {"method": method, "params": params}
        if identity is not None:
            data["id"] = identity
        self.process.stdin.write(json.dumps(data).encode() + b"\n")

    def wait(self, predicate, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for index, message in enumerate(self.messages):
                if predicate(message):
                    return self.messages.pop(index)
            for key, _ in self.selector.select(min(0.2, max(0, deadline - time.monotonic()))):
                chunk = os.read(key.fileobj.fileno(), 65536)
                if not chunk:
                    self.selector.unregister(key.fileobj)
                    continue
                self.total += len(chunk)
                if self.total > 512 * 1024:
                    raise ValueError("RPC output budget exceeded")
                if key.fileobj is self.process.stderr:
                    self.error = (self.error + chunk)[-2048:]
                    continue
                self.buffer += chunk
                while b"\n" in self.buffer:
                    line, self.buffer = self.buffer.split(b"\n", 1)
                    message = json.loads(line)
                    verify_message(message)
                    self.messages.append(message)
                    if len(self.messages) > 128:
                        raise ValueError("RPC message budget exceeded")
            if not self.selector.get_map() and self.process.poll() is not None:
                raise ValueError(f"native process exited {self.process.returncode}: "
                                 + self.error.decode(errors="replace"))
        raise ValueError("RPC response timeout")

    def response(self, identity: int):
        return self.wait(lambda message: message.get("id") == identity)

    def close(self):
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
        try:
            self.process.wait(timeout=4)
        except subprocess.TimeoutExpired:
            os.killpg(self.process.pid, signal.SIGKILL)
            self.process.wait(timeout=3)
        self.selector.close()
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()
        try:
            os.killpg(self.process.pid, 0)
        except ProcessLookupError:
            return
        raise ValueError("process group survived teardown")


def run_case(root: Path, cert: Path, key: Path, mode: str) -> dict:
    root.mkdir(mode=0o700)
    peer = Peer(cert, key, mode)
    peer.thread.start()
    rpc, restarted = None, False
    try:
        rpc = Rpc(root, trust=mode != "untrusted")
        rpc.send("initialize", {"clientInfo": {"name": "personal-ai", "version": "1"}}, 1)
        verify_initialize(rpc.response(1).get("result", {}))
        rpc.send("initialized", {})
        rpc.send("account/login/start", {"type": "chatgptDeviceCode"}, 2)
        start = rpc.response(2)
        if mode in {"untrusted", "wrong-name", "refused", "malformed"}:
            if "error" not in start:
                raise ValueError("negative login was accepted")
            expected = [] if mode in {"untrusted", "wrong-name"} else [USERCODE]
            if peer.events != expected:
                raise ValueError("negative login continued")
        elif mode in EXCHANGE_MODES:
            login_id = verify_start(start.get("result", {}))
            success = mode == "exchange"
            completion = rpc.wait(lambda message: message.get("method") == "account/login/completed")
            verify_exchange_completion(completion.get("params", {}), login_id, success=success)
            if success:
                update = rpc.wait(lambda message: message.get("method") == "account/updated")
                if update.get("params") != {"authMode": "chatgpt", "planType": "plus"}:
                    raise ValueError("synthetic account update mismatch")
            rpc.send("account/read", {"refreshToken": False}, 3)
            verify_account(rpc.response(3).get("result", {}), authenticated=success)
            verify_exchange_events(peer.events)
            rpc.close()
            verify_private_state(root, synthetic_session=success)
            if success:
                rpc = Rpc(root, trust=True, status=True)
                output, error = rpc.process.communicate(timeout=10)
                verify_status(rpc.process.returncode, output, error)
                restarted = True
            # Any automatic retry/refresh/fallback is outside this auth-only case.
            time.sleep(0.2)
            verify_exchange_events(peer.events)
        else:
            login_id = verify_start(start.get("result", {}))
            deadline = time.monotonic() + 5
            while peer.events.count(POLL) < 2 and time.monotonic() < deadline:
                time.sleep(0.05)
            if peer.events[:1] != [USERCODE] or peer.events.count(POLL) < 2:
                raise ValueError("native polling not observed")
            if mode == "cancel":
                rpc.send("account/login/cancel", {"loginId": str(uuid.UUID(int=1))}, 3)
                if rpc.response(3).get("result") != {"status": "notFound"}:
                    raise ValueError("unrelated cancellation accepted")
                rpc.send("account/login/cancel", {"loginId": login_id}, 4)
                if rpc.response(4).get("result") != {"status": "canceled"}:
                    raise ValueError("cancellation rejected")
                completion = rpc.wait(lambda message: message.get("method") == "account/login/completed")
                params = completion.get("params", {})
                if params.get("loginId") != login_id or params.get("success") is not False:
                    raise ValueError("cancellation completion mismatch")
                rpc.send("account/login/cancel", {"loginId": login_id}, 5)
                if rpc.response(5).get("result") != {"status": "notFound"}:
                    raise ValueError("completed cancellation replayed")
                count = len(peer.events)
                time.sleep(2.2)
                if len(peer.events) != count:
                    raise ValueError("polling survived cancellation")
        if peer.errors:
            raise ValueError("TLS peer rejected request")
    except Exception:
        # This harness can only see synthetic state. Bound failure
        # details, never repurpose this reporter for a real owner login/profile.
        if rpc is not None:
            try:
                tail = read_trace(rpc.trace)[-4096:]
            except (ValueError, OSError):
                tail = "trace unavailable"
            processes = []
            for path in Path("/proc").glob("[0-9]*/status"):
                try:
                    if os.getpgid(int(path.parent.name)) == rpc.process.pid:
                        fields = dict(line.split(":", 1) for line in path.read_text().splitlines() if ":" in line)
                        processes.append({key: fields.get(key, "").strip()
                                          for key in ("Name", "State", "Threads", "VmRSS")})
                except (FileNotFoundError, ProcessLookupError):
                    pass
            print(json.dumps({"failed_case": mode, "native_exit": rpc.process.poll(),
                              "stderr_tail": rpc.error.decode(errors="replace"),
                              "syscalls_tail": tail,
                              "processes": processes[:16]}), flush=True)
        raise
    finally:
        try:
            if rpc is not None:
                rpc.close()
        finally:
            peer.shutdown()
            peer.server_close()
            peer.thread.join(timeout=3)
            if peer.thread.is_alive():
                raise ValueError("TLS listener survived teardown")
    receipt = {"case": mode, "http_requests": len(peer.events),
               "trace": verify_trace(read_trace(root / "trace")),
               "state": verify_private_state(root, synthetic_session=mode == "exchange")}
    if mode in EXCHANGE_MODES:
        verify_exchange_events(peer.events)
        receipt["synthetic_session"] = mode == "exchange"
        receipt["restart_readback"] = restarted
        if restarted:
            receipt["restart_trace"] = verify_trace(read_trace(root / "reload-trace"))
            if receipt["restart_trace"]["connections"] != 0:
                raise ValueError("restart attempted a network connection")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host_namespace")
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--exchange", action="store_true")
    args = parser.parse_args()
    if args.preflight and args.exchange:
        raise ValueError("separate protocol modes required")
    if (os.getuid(), os.getgid(), os.getegid(), os.getgroups()) != (65534, 65534, 65534, []):
        raise ValueError("nonroot identity required")
    interfaces = {line.split(":", 1)[0].strip() for line in Path("/proc/net/dev").read_text().splitlines()
                  if ":" in line}
    verify_isolation(uid=os.geteuid(), status=Path("/proc/self/status").read_text(),
                     interfaces=interfaces, host_ns=args.host_namespace,
                     current_ns=os.readlink("/proc/self/ns/net"))
    if any(Path(path).exists() for path in ("/root", "/home", "/run", "/var")):
        raise ValueError("unexpected host state mount")
    verify_name_mapping(Path("/etc"))
    if not Path("/work/empty").is_dir() or list(Path("/work/empty").iterdir()) or os.access("/work/empty", os.W_OK):
        raise ValueError("empty nonwritable command path required")
    resource.setrlimit(resource.RLIMIT_CPU, (30, 30))
    resource.setrlimit(resource.RLIMIT_AS, (1024 * 1024 * 1024, 1024 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_NPROC, (64, 64))
    resource.setrlimit(resource.RLIMIT_FSIZE, (32 * 1024 * 1024, 32 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    os.umask(0o077)
    if args.preflight:
        for mode in ("trusted", "untrusted", "wrong-name"):
            name = "wrong" if mode == "wrong-name" else "peer"
            peer = Peer(Path(f"/work/{name}.pem"), Path(f"/work/{name}.key"), mode)
            peer.thread.start()
            context = ssl.create_default_context(cafile=None if mode == "untrusted" else "/work/ca.pem")
            accepted = False
            try:
                with socket.create_connection(("login.example.test", 8443), timeout=2) as connection:
                    with context.wrap_socket(connection, server_hostname="login.example.test"):
                        accepted = True
            except ssl.SSLCertVerificationError:
                pass
            finally:
                peer.shutdown()
                peer.server_close()
                peer.thread.join(timeout=3)
            if accepted != (mode == "trusted") or peer.thread.is_alive():
                raise ValueError("TLS preflight mismatch")
        trace = verify_trace(read_trace(Path("/tmp/tls-preflight.trace")), expected_executable="/usr/bin/python3")
        print(json.dumps({"tls_preflight": True, "client_executed": False, "trace": trace,
                          "processes": verify_no_native_processes(),
                          "private_namespace": os.readlink("/proc/self/ns/net")}))
        return
    receipts = []
    for mode in EXCHANGE_MODES if args.exchange else MODES:
        certificate = "/work/wrong.pem" if mode == "wrong-name" else "/work/peer.pem"
        key = "/work/wrong.key" if mode == "wrong-name" else "/work/peer.key"
        receipts.append(run_case(Path("/tmp") / mode, Path(certificate), Path(key), mode))
    print(json.dumps({"synthetic_protocol": True, "synthetic_exchange": args.exchange, "external_network": False,
                      "processes": verify_no_native_processes(),
                      "private_namespace": os.readlink("/proc/self/ns/net"), "cases": receipts}))


if __name__ == "__main__":
    main()
