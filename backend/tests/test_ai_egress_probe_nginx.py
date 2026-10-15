"""Optional real-engine tests; all TLS peers use local filesystem sockets only."""
import os
import shutil
import socket
import socketserver
import ssl
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from app.ai.egress_probe import TransportProbeProfile, parse_probe_receipt


def _certificate(root, name, issuer=None, names=()):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    issuer_cert, issuer_key = issuer if issuer else (None, key)
    now = datetime.now(timezone.utc)
    builder = (x509.CertificateBuilder().subject_name(subject)
               .issuer_name(issuer_cert.subject if issuer else subject)
               .public_key(key.public_key()).serial_number(x509.random_serial_number())
               .not_valid_before(now - timedelta(minutes=1))
               .not_valid_after(now + timedelta(hours=1))
               .add_extension(x509.BasicConstraints(ca=issuer is None, path_length=None), True)
               .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), False)
               .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(
                   issuer_key.public_key()), False)
               .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False,
                   key_encipherment=True, data_encipherment=False, key_agreement=False,
                   key_cert_sign=issuer is None, crl_sign=issuer is None,
                   encipher_only=False, decipher_only=False), True))
    if names:
        builder = builder.add_extension(x509.SubjectAlternativeName(
            [x509.DNSName(value) for value in names]), False)
    cert = builder.sign(issuer_key, hashes.SHA256())
    cert_path, key_path = root / f"{name}.pem", root / f"{name}.key"
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    key_path.chmod(0o600)
    return cert, key


class _Peer(socketserver.UnixStreamServer):
    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(2)
        try:
            return self.tls_context.wrap_socket(connection, server_side=True), address
        except ssl.SSLError:
            connection.close()
            raise

    def handle_error(self, request, client_address):
        self.errors += 1


class _Handler(socketserver.BaseRequestHandler):
    def handle(self):
        raw = b""
        while b"\r\n\r\n" not in raw and len(raw) <= 2048:
            block = self.request.recv(2048)
            if not block:
                return
            raw += block
        self.server.requests.append(raw)
        time.sleep(self.server.delay)
        self.request.sendall(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n"
                             b"Set-Cookie: session=synthetic\r\nLocation: https://other.example/\r\n"
                             b"Connection: close\r\n\r\n")


class _Gateway:
    def __init__(self, base, profile, nginx):
        self.base, self.profile, self.nginx = base, profile, nginx
        for name in ("bridge", "tls", "work", "logs"):
            (base / name).mkdir()
        self.entry_ca = _certificate(base / "tls", "worker-ca")
        self.upstream_ca = _certificate(base / "tls", "upstream-ca")
        self.other_ca = _certificate(base / "tls", "other-ca")
        hosts = ("chatgpt.com", "auth.openai.com")
        _certificate(base / "tls", "entry", self.entry_ca, hosts)
        _certificate(base / "tls", "peer", self.upstream_ca, hosts)
        _certificate(base / "tls", "wrong-name", self.upstream_ca, ("other.example",))
        _certificate(base / "tls", "wrong-issuer", self.other_ca, hosts)
        self.peers, self.threads = [], []
        self.process = None

    def context(self, name):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.base / "tls" / f"{name}.pem",
                                self.base / "tls" / f"{name}.key")
        return context

    def configuration(self, profile):
        config = profile.gateway_config()
        # Rebase filesystem references only; all routing/TLS/HTTP policy is unchanged.
        for prefix in ("bridge", "tls", "work"):
            config = config.replace(f"/{prefix}/", str(self.base / prefix) + "/")
        path = self.base / "entry.conf"
        path.write_text(config)
        return path

    def start(self):
        for role in ("model", "auth"):
            peer = _Peer(str(self.base / "bridge" / f"{role}.sock"), _Handler)
            peer.tls_context = self.context("peer")
            peer.requests, peer.errors, peer.delay = [], 0, 0
            thread = threading.Thread(target=peer.serve_forever,
                                      kwargs={"poll_interval": 0.05}, daemon=True)
            thread.start()
            self.peers.append(peer)
            self.threads.append(thread)
        path = self.configuration(self.profile)
        self.process = subprocess.Popen([self.nginx, "-p", str(self.base) + "/", "-c", str(path)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={"PATH": "/usr/bin:/bin", "HOME": str(self.base)})
        deadline = time.monotonic() + 3
        while not (self.base / "bridge/entry.sock").exists():
            assert self.process.poll() is None, self.process.communicate(timeout=1)[1][:2048]
            assert time.monotonic() < deadline, "gateway_start_deadline"
            time.sleep(0.01)

    def request(self, host="chatgpt.com", method="HEAD", target="/", headers=(), sni=None):
        context = ssl.create_default_context(cafile=str(self.base / "tls/worker-ca.pem"))
        raw = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        raw.settimeout(5)
        try:
            raw.connect(str(self.base / "bridge/entry.sock"))
            with context.wrap_socket(raw, server_hostname=sni or host) as connection:
                request = f"{method} {target} HTTP/1.1\r\nHost: {host}\r\n"
                connection.sendall((request + "".join(h + "\r\n" for h in headers) + "\r\n").encode())
                response = b""
                while b"\r\n\r\n" not in response:
                    block = connection.recv(4096)
                    if not block:
                        break
                    response += block
                    assert len(response) <= 8192
                return int(response.split(b" ", 2)[1]), response
        finally:
            raw.close()

    def close(self):
        if self.process is not None:
            self.process.terminate()
            self.process.communicate(timeout=5)
        for peer in self.peers:
            peer.shutdown()
            peer.server_close()
        for thread in self.threads:
            thread.join(timeout=3)
            assert not thread.is_alive()


@pytest.fixture
def gateway():
    binary = os.environ.get("NGINX_TEST_BINARY")
    if not binary:
        pytest.skip("set NGINX_TEST_BINARY for real-engine Unix-socket TLS checks")
    binary = str(Path(binary).resolve(strict=True))
    # Unix socket paths have a small kernel limit; pytest's normal path can exceed it.
    with tempfile.TemporaryDirectory(prefix="net-check-", dir="/tmp") as directory:
        base = Path(directory)
        instance = _Gateway(base, TransportProbeProfile(str(uuid4()), 998, 997), binary)
        try:
            instance.start()
            yield instance
        finally:
            instance.close()


def test_real_gateway_verifies_tls_and_strips_all_extra_headers(gateway):
    code, response = gateway.request(headers=("X-Trace: synthetic",))
    assert code == 401
    assert b"set-cookie:" not in response.lower()
    assert b"location:" not in response.lower()
    assert len(gateway.peers[0].requests) == 1
    request = gateway.peers[0].requests[0].lower()
    assert request.startswith(b"head / http/1.1\r\n")
    assert b"host: chatgpt.com\r\n" in request
    assert b"x-trace" not in request
    assert gateway.request(host="auth.openai.com")[0] == 401
    assert len(gateway.peers[1].requests) == 1


@pytest.mark.parametrize("options", [
    {"method": "GET"}, {"method": "POST"}, {"method": "CONNECT"},
    {"target": "/?x=1"}, {"target": "/oauth/token"}, {"target": "/responses"},
    {"target": "//"}, {"target": "/%2e"}, {"target": "https://chatgpt.com/"},
    {"headers": ("Authorization: Bearer synthetic",)},
    {"headers": ("Cookie: session=synthetic",)},
    {"headers": ("Proxy-Authorization: Basic synthetic",)},
    {"headers": ("Content-Length: 0",)},
    {"headers": ("Transfer-Encoding: chunked",)},
])
def test_real_gateway_rejects_before_upstream(gateway, options):
    assert gateway.request(**options)[0] in (400, 403, 405)
    assert not any(peer.requests for peer in gateway.peers)


def test_real_gateway_rejects_unknown_and_crossed_tls_names(gateway):
    assert gateway.request(host="auth.openai.com", sni="chatgpt.com")[0] == 421
    with pytest.raises(ssl.SSLError):
        gateway.request(host="other.example")
    assert gateway.request(host="other.example", sni="chatgpt.com")[0] == 403
    assert not any(peer.requests for peer in gateway.peers)


@pytest.mark.parametrize("certificate", ["wrong-name", "wrong-issuer"])
def test_real_gateway_rejects_upstream_name_or_ca_mismatch(gateway, certificate):
    gateway.peers[0].tls_context = gateway.context(certificate)
    assert gateway.request()[0] == 502
    assert not any(peer.requests for peer in gateway.peers)


def test_real_gateway_bounds_stalled_upstream(gateway):
    gateway.peers[0].delay = 4
    started = time.monotonic()
    assert gateway.request()[0] == 504
    assert time.monotonic() - started < 5


def test_real_curl_command_emits_transport_receipt_without_auth(gateway):
    args = list(gateway.profile.probe_command("chatgpt.com"))
    args[0] = shutil.which("curl")
    assert args[0] is not None
    args[args.index("--cacert") + 1] = str(gateway.base / "tls/worker-ca.pem")
    args[-1:-1] = ["--unix-socket", str(gateway.base / "bridge/entry.sock")]
    result = subprocess.run(args, capture_output=True, timeout=12,
                            env={"PATH": "/usr/bin:/bin", "HOME": str(gateway.base)})
    assert result.returncode == 0, result.stderr[:1024]
    assert parse_probe_receipt(result.stdout, exit_code=result.returncode).http_status == 401
    assert result.stderr == b""


def test_public_numeric_profile_compiles_without_contacting_upstreams(gateway):
    profile = TransportProbeProfile(str(uuid4()), 998, 997, ("1.1.1.1", "8.8.8.8"))
    path = gateway.configuration(profile)
    result = subprocess.run([gateway.nginx, "-t", "-p", str(gateway.base) + "/", "-c", str(path)],
                            capture_output=True, timeout=5)
    assert result.returncode == 0, result.stderr[:2048]
    assert not any(peer.requests for peer in gateway.peers)


def test_real_gateway_bounds_incomplete_tls_handshake(gateway):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(4)
        connection.connect(str(gateway.base / "bridge/entry.sock"))
        started = time.monotonic()
        assert connection.recv(1) == b""
        assert time.monotonic() - started < 4
    assert not any(peer.requests for peer in gateway.peers)
