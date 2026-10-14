import json
import os
import shutil
import socketserver
import subprocess
import tempfile
from pathlib import Path
from uuid import uuid4

import pytest

from app.ai.upstream_probe import ObservedProbeProfile, parse_observed_receipt
from test_ai_egress_probe_nginx import _Gateway


class _ForgedReceipt(socketserver.BaseRequestHandler):
    def handle(self):
        self.server.requests.append(self.request.recv(2048))
        self.request.sendall(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n"
            b"X-Transport-Status: 200\r\nSet-Cookie: session=synthetic\r\n"
            b"Location: https://other.example/\r\nWWW-Authenticate: synthetic\r\n"
            b"Connection: close\r\n\r\n")


@pytest.fixture
def observed_gateway():
    binary = os.environ.get("NGINX_TEST_BINARY")
    if not binary:
        pytest.skip("set NGINX_TEST_BINARY for observed upstream TLS engine checks")
    with tempfile.TemporaryDirectory(prefix="upstream-check-", dir="/tmp") as directory:
        instance = _Gateway(Path(directory), ObservedProbeProfile(str(uuid4()), 998, 997),
                            str(Path(binary).resolve(strict=True)))
        try:
            instance.start()
            yield instance
        finally:
            instance.close()


def curl_receipt(gateway):
    args = list(gateway.profile.probe_command("chatgpt.com"))
    args[0] = shutil.which("curl")
    args[args.index("--cacert") + 1] = str(gateway.base / "tls/worker-ca.pem")
    args[-1:-1] = ["--unix-socket", str(gateway.base / "bridge/entry.sock")]
    result = subprocess.run(args, capture_output=True, timeout=12,
                            env={"PATH": "/usr/bin:/bin", "HOME": str(gateway.base)})
    assert result.returncode == 0, result.stderr.decode()[:1024]
    assert len(result.stdout) <= 192
    return result.stdout


def test_real_observed_header_is_not_spoofed_and_hides_stay_inherited(observed_gateway):
    for peer in observed_gateway.peers:
        peer.RequestHandlerClass = _ForgedReceipt
    code, response = observed_gateway.request()
    assert code == 401
    assert response.lower().count(b"x-transport-status: 401\r\n") == 1
    assert b"x-transport-status: 200" not in response.lower()
    for hidden in (b"set-cookie:", b"location:", b"www-authenticate:"):
        assert hidden not in response.lower()
    raw = curl_receipt(observed_gateway)
    assert json.loads(raw)["upstream_status"] == "401"
    assert parse_observed_receipt(raw, exit_code=0).http_status == 401


def test_real_local_gateway_refusal_is_not_an_upstream_receipt(observed_gateway):
    args = list(observed_gateway.profile.probe_command("chatgpt.com"))
    args[0] = shutil.which("curl")
    args[args.index("--cacert") + 1] = str(observed_gateway.base / "tls/worker-ca.pem")
    args[-1] = "https://chatgpt.com/oauth/token"
    args[-1:-1] = ["--unix-socket", str(observed_gateway.base / "bridge/entry.sock")]
    result = subprocess.run(args, capture_output=True, timeout=12,
                            env={"PATH": "/usr/bin:/bin", "HOME": str(observed_gateway.base)})
    assert result.returncode == 0
    assert json.loads(result.stdout) == dict(http_status="403", tls_verify="0", upstream_status="")
    with pytest.raises(ValueError, match="invalid_observed_receipt"):
        parse_observed_receipt(result.stdout, exit_code=0)
    assert not any(peer.requests for peer in observed_gateway.peers)


@pytest.mark.parametrize("certificate", ["wrong-name", "wrong-issuer"])
def test_real_upstream_tls_failure_cannot_pass_local_tls_receipt(observed_gateway, certificate):
    observed_gateway.peers[0].tls_context = observed_gateway.context(certificate)
    raw = curl_receipt(observed_gateway)
    assert json.loads(raw) == dict(http_status="502", tls_verify="0", upstream_status="502")
    with pytest.raises(ValueError, match="invalid_observed_receipt"):
        parse_observed_receipt(raw, exit_code=0)
    assert not any(peer.requests for peer in observed_gateway.peers)
