import importlib
import importlib.util
import json
from dataclasses import FrozenInstanceError
from uuid import uuid4

import pytest

from app.ai.egress_probe import TransportProbeProfile


def api():
    assert importlib.util.find_spec("app.ai.upstream_probe") is not None, "missing_observed_transport"
    return importlib.import_module("app.ai.upstream_probe")


def test_observed_profile_keeps_original_transport_and_hides_untrusted_header():
    profile = api().ObservedProbeProfile(str(uuid4()), 998, 997)
    configuration = profile.gateway_config()
    assert configuration.count("add_header X-Transport-Status $upstream_status always;") == 2
    for header in ("Set-Cookie", "Location", "WWW-Authenticate", "X-Transport-Status"):
        assert configuration.count("proxy_hide_header " + header + ";") == 1
    assert "proxy_ssl_verify on;" in configuration
    assert "proxy_next_upstream off;" in configuration
    assert "proxy_pass_request_headers off;" in configuration
    assert "access_log off;" in configuration
    with pytest.raises(FrozenInstanceError):
        profile.uid = 0


def test_observed_command_has_only_bounded_status_output():
    profile = api().ObservedProbeProfile(str(uuid4()), 998, 997, ("1.1.1.1", "8.8.8.8"))
    args = profile.probe_command("chatgpt.com")
    output = args[args.index("--write-out") + 1]
    data = json.loads(output)
    assert set(data) == {"http_status", "tls_verify", "upstream_status"}
    assert data["upstream_status"] == "%header{x-transport-status}"
    assert args[-1] == "https://chatgpt.com/"
    assert args[1] == "--disable"
    assert "--head" in args and "--insecure" not in args and "--location" not in args
    assert "X-Transport-Status" not in TransportProbeProfile(str(uuid4()), 998, 997).gateway_config()


@pytest.mark.parametrize("status", ["200", "204", "302", "401", "403", "404", "429"])
def test_observed_final_upstream_response_is_transport_only(status):
    raw = json.dumps(dict(http_status=status, tls_verify="0", upstream_status=status)).encode()
    assert api().parse_observed_receipt(raw, exit_code=0).http_status == int(status)


@pytest.mark.parametrize("changes", [
    {"upstream_status": ""}, {"upstream_status": None}, {"upstream_status": 401},
    {"upstream_status": "200"}, {"upstream_status": "401, 401"},
    {"http_status": "103", "upstream_status": "103"},
    {"http_status": "502", "upstream_status": "502"},
    {"http_status": "504", "upstream_status": "504"},
    {"http_status": 401}, {"tls_verify": "20"}, {"tls_verify": 0}, {"extra": "value"},
])
def test_local_gateway_or_invalid_upstream_status_is_not_transport_evidence(changes):
    data = dict(http_status="401", tls_verify="0", upstream_status="401")
    data.update(changes)
    with pytest.raises(ValueError, match="invalid_observed_receipt"):
        api().parse_observed_receipt(json.dumps(data).encode(), exit_code=0)


@pytest.mark.parametrize("raw,exit_code", [
    (b'{"http_status":"401","tls_verify":"0","upstream_status":"401","upstream_status":"401"}', 0),
    (b'{"http_status":"401","http_status":"401","tls_verify":"0","upstream_status":"401"}', 0),
    (b'{"http_status":"401","tls_verify":"0","upstream_status":"401"}', 60),
    (b'{"http_status":"401","tls_verify":"0","upstream_status":"401"}', False),
    (b'{"http_status":"401","tls_verify":"0","upstream_status":"401"}', "0"),
    (b"x" * 193, 0), (b"", 0), (b"[]", 0), (b"\xff", 0), ("not-bytes", 0),
])
def test_observed_receipt_rejects_duplicate_failed_or_unbounded_input(raw, exit_code):
    with pytest.raises(ValueError, match="invalid_observed_receipt"):
        api().parse_observed_receipt(raw, exit_code=exit_code)
