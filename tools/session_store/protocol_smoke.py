"""Synthetic-only native login protocol test; requires a prepared Linux namespace."""

import argparse
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

from offline_smoke import ALIASES, verify_isolation


ORIGIN = "https://login.example.test:8443"
USERCODE = "/api/accounts/deviceauth/usercode"
POLL = "/api/accounts/deviceauth/token"
# Public application identifier from the pinned native protocol, not a credential.
PUBLIC_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
SAMPLE_CODE = "SAMPLE-42"


def verify_name_mapping(etc: Path) -> None:
    for name, content in (("hosts", "127.0.0.1 login.example.test\n"),
                          ("nsswitch.conf", "hosts: files\n")):
        path = etc / name
        metadata = path.lstat()
        if (not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o644
                or path.read_text() != content):
            raise ValueError("synthetic file-only name mapping rejected")


def verify_headers(items: list[tuple[str, str]]) -> None:
    headers = {key.lower(): value for key, value in items}
    if len(headers) != len(items):
        raise ValueError("duplicate HTTP headers")
    if headers.get("host") != "login.example.test:8443" or headers.get("content-type") != "application/json":
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


def verify_initialize(result: dict) -> None:
    agent = result.get("userAgent", "")
    if (not agent.startswith("personal-ai/0.153.4 ") or "(personal-ai; 1)" not in agent
            or re.search("codex|skylink|argos", agent, re.I)):
        raise ValueError("initialization identity mismatch")


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


def verify_trace(trace: str) -> dict:
    executions = [line for line in trace.splitlines() if "execve(" in line]
    connections = [line for line in trace.splitlines() if "connect(" in line]
    if len(executions) != 1 or 'execve("/work/client",' not in executions[0] or not executions[0].endswith("= 0"):
        raise ValueError("unexpected child execution")
    for line in connections:
        if ("sa_family=AF_INET," not in line or "sin_port=htons(8443)" not in line
                or 'sin_addr=inet_addr("127.0.0.1")' not in line):
            raise ValueError("unexpected connection attempt")
    return {"execs": len(executions), "connections": len(connections)}


def verify_private_state(root: Path, *, max_bytes: int = 32 * 1024 * 1024) -> dict:
    total, count = 0, 0
    for path in [root, *root.rglob("*")]:
        metadata = path.lstat()
        if metadata.st_uid != os.geteuid() or path.name == "auth.json":
            raise ValueError("unexpected authorization state or owner")
        if stat.S_ISLNK(metadata.st_mode):
            if path.name not in ALIASES or os.readlink(path) != "/work/client":
                raise ValueError("unexpected state link")
        elif stat.S_ISDIR(metadata.st_mode):
            if stat.S_IMODE(metadata.st_mode) != 0o700:
                raise ValueError("nonprivate state directory")
        elif stat.S_ISREG(metadata.st_mode):
            if stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_nlink != 1:
                raise ValueError("nonprivate state file")
            total += metadata.st_size
            count += 1
        else:
            raise ValueError("unexpected state file type")
        if total > max_bytes or count > 128:
            raise ValueError("state budget exceeded")
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
            verify_headers(list(self.headers.items()))
            length = int(self.headers.get("Content-Length", "-1"))
            if not 0 <= length <= 2048 or self.headers.get("Transfer-Encoding") or len(peer.events) >= 16:
                raise ValueError("HTTP request budget exceeded")
            verify_request(self.command, self.path, self.rfile.read(length))
            peer.events.append(self.path)
            status, body = 403, b"{}"
            if self.path == USERCODE and peer.mode != "refused":
                status = 200
                body = (b"{" if peer.mode == "malformed" else
                        json.dumps({"device_auth_id": "sample-session", "user_code": SAMPLE_CODE,
                                    "interval": "1"}).encode())
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
    def __init__(self, root: Path, *, trust: bool):
        for child in ("home", "profile", "temporary"):
            (root / child).mkdir(mode=0o700)
        # Native compatibility environment names; no existing host profile is mounted.
        env = {"HOME": str(root / "home"), "CODEX_HOME": str(root / "profile"),
               "TMPDIR": str(root / "temporary"), "PATH": "/usr/bin:/bin", "RUST_LOG": "off",
               "CODEX_APP_SERVER_LOGIN_ISSUER": ORIGIN}
        if trust:
            env["SSL_CERT_FILE"] = "/work/ca.pem"
        self.trace = root / "trace"
        self.process = subprocess.Popen(
            ["/usr/bin/strace", "-f", "-qq", "-s", "256", "-o", str(self.trace),
             "-e", "trace=execve,connect", "/work/client",
             "-c", 'cli_auth_credentials_store="file"', "-c", "analytics.enabled=false",
             "-c", "check_for_update_on_startup=false", "app-server"],
            env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True, bufsize=0)
        self.selector = selectors.DefaultSelector()
        for stream in (self.process.stdout, self.process.stderr):
            os.set_blocking(stream.fileno(), False)
            self.selector.register(stream, selectors.EVENT_READ)
        self.buffer, self.total, self.messages = b"", 0, []

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
                    continue
                self.buffer += chunk
                while b"\n" in self.buffer:
                    line, self.buffer = self.buffer.split(b"\n", 1)
                    message = json.loads(line)
                    if "method" in message and ("id" in message or message["method"] not in
                            {"account/login/completed", "account/updated", "configWarning"}):
                        raise ValueError("unexpected RPC method")
                    self.messages.append(message)
                    if len(self.messages) > 128:
                        raise ValueError("RPC message budget exceeded")
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
    rpc = None
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
    return {"case": mode, "http_requests": len(peer.events),
            "trace": verify_trace(rpc.trace.read_text()), "state": verify_private_state(root)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host_namespace")
    parser.add_argument("--preflight", action="store_true")
    args = parser.parse_args()
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
    resource.setrlimit(resource.RLIMIT_CPU, (30, 30))
    resource.setrlimit(resource.RLIMIT_AS, (1024 * 1024 * 1024, 1024 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_NPROC, (64, 64))
    resource.setrlimit(resource.RLIMIT_FSIZE, (32 * 1024 * 1024, 32 * 1024 * 1024))
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
        print(json.dumps({"tls_preflight": True, "client_executed": False,
                          "private_namespace": os.readlink("/proc/self/ns/net")}))
        return
    receipts = []
    for mode in ("cancel", "terminate", "untrusted", "wrong-name", "refused", "malformed"):
        certificate = "/work/wrong.pem" if mode == "wrong-name" else "/work/peer.pem"
        key = "/work/wrong.key" if mode == "wrong-name" else "/work/peer.key"
        receipts.append(run_case(Path("/tmp") / mode, Path(certificate), Path(key), mode))
    for path in Path("/proc").glob("[0-9]*/exe"):
        try:
            if os.readlink(path) == "/work/client":
                raise ValueError("native client survived teardown")
        except FileNotFoundError:
            pass
    print(json.dumps({"synthetic_protocol": True, "external_network": False,
                      "private_namespace": os.readlink("/proc/self/ns/net"), "cases": receipts}))


if __name__ == "__main__":
    main()
