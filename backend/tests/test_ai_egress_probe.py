import importlib
import importlib.util
import json
from dataclasses import FrozenInstanceError
from uuid import uuid4

import pytest


def test_profile_module_is_available():
    assert importlib.util.find_spec("app.ai.egress_probe") is not None, "missing_transport_profile"


@pytest.fixture
def api():
    return importlib.import_module("app.ai.egress_probe")


@pytest.fixture
def profile(api):
    return api.TransportProbeProfile(str(uuid4()), 998, 997)


def test_all_nginx_temporary_paths_stay_inside_bounded_work_mount(profile):
    configuration = profile.gateway_config()
    for kind in ("client_body", "proxy", "fastcgi", "uwsgi", "scgi"):
        assert kind + "_temp_path /work/" in configuration


def test_profile_is_immutable_and_paths_are_run_bound(profile):
    assert profile.mode == "synthetic"
    assert profile.root_path.endswith("online-check-" + profile.run_id.replace("-", ""))
    with pytest.raises(FrozenInstanceError):
        profile.uid = 0


@pytest.mark.parametrize("field,value", [
    ("run_id", "../other"), ("run_id", str(uuid4()).upper()),
    ("run_id", "00000000-0000-0000-0000-000000000000"),
    ("run_id", "00000000-0000-1000-8000-000000000000"),
    ("run_id", None), ("uid", 0), ("gid", 0), ("uid", True),
    ("gid", -1), ("uid", "998"), ("gid", 2**31),
])
def test_invalid_identity_rejected_before_interpolation(api, field, value):
    args = {"run_id": str(uuid4()), "uid": 998, "gid": 997, field: value}
    with pytest.raises(ValueError, match="invalid_probe_identity"):
        api.TransportProbeProfile(**args)


@pytest.mark.parametrize("addresses", [
    (), ("1.1.1.1",), ["1.1.1.1", "8.8.8.8"],
    ("127.0.0.1", "8.8.8.8"), ("10.0.0.1", "8.8.8.8"),
    ("169.254.169.254", "8.8.8.8"), ("100.64.0.1", "8.8.8.8"),
    ("198.18.0.1", "8.8.8.8"), ("203.0.113.1", "8.8.8.8"),
    ("224.0.0.1", "8.8.8.8"), ("240.0.0.1", "8.8.8.8"),
    ("0.0.0.0", "8.8.8.8"), ("8.8.8.8", "::1"),
    ("2606:4700:4700::1111", "8.8.8.8"),
    ("1.1.1.1;include /etc/other;", "8.8.8.8"),
    ("1.1.1.1\nIPAddressAllow=any", "8.8.8.8"), (True, "8.8.8.8"),
])
def test_only_immutable_public_ipv4_pairs_accepted(api, addresses):
    with pytest.raises(ValueError, match="invalid_probe_addresses"):
        api.TransportProbeProfile(str(uuid4()), 998, 997, addresses)


def test_synthetic_and_public_upstreams_never_mix(api, profile):
    synthetic = profile.gateway_config()
    assert "server unix:/bridge/model.sock;" in synthetic
    assert "server unix:/bridge/auth.sock;" in synthetic
    assert "IPAddressDeny=any" in profile.gateway_properties()
    assert "RestrictAddressFamilies=AF_UNIX" in profile.gateway_properties()
    public = api.TransportProbeProfile(str(uuid4()), 998, 997, ("1.1.1.1", "8.8.8.8"))
    assert public.mode == "public"
    config = public.gateway_config()
    assert "server 1.1.1.1:443;" in config
    assert "server 8.8.8.8:443;" in config
    assert "/bridge/model.sock" not in config
    assert "/bridge/auth.sock" not in config
    assert "resolver " not in config
    assert "IPAddressDeny=any" in public.gateway_properties()
    assert "IPAddressAllow=1.1.1.1/32" in public.gateway_properties()
    assert "IPAddressAllow=8.8.8.8/32" in public.gateway_properties()


def test_gateway_denies_credentials_queries_bodies_and_non_head(profile):
    config = profile.gateway_config()
    for guard in ("$http_authorization", "$http_proxy_authorization", "$http_cookie",
                  "$http_content_length", "$http_transfer_encoding"):
        assert f'if ({guard} != "") {{ return 403; }}' in config
    assert 'if ($request_method != HEAD) { return 405; }' in config
    assert 'if ($request_uri != "/") { return 403; }' in config
    assert "ssl_reject_handshake on;" in config
    assert "default_server" in config
    assert "proxy_pass_request_headers off;" in config
    assert "proxy_pass_request_body off;" in config
    assert "proxy_method HEAD;" in config
    assert "proxy_hide_header Set-Cookie;" in config
    assert "proxy_hide_header Location;" in config


def test_gateway_verifies_both_fixed_tls_names_and_has_no_payload_logs(profile):
    config = profile.gateway_config()
    for host in ("chatgpt.com", "auth.openai.com"):
        assert f"proxy_ssl_name {host};" in config
        assert f"proxy_set_header Host {host};" in config
        assert f'if ($ssl_server_name != "{host}") {{ return 421; }}' in config
    assert "proxy_ssl_verify on;" in config
    assert "proxy_ssl_server_name on;" in config
    assert "proxy_ssl_trusted_certificate /tls/upstream-ca.pem;" in config
    assert "access_log off;" in config
    assert "error_log /dev/null crit;" in config
    assert "proxy_max_temp_file_size 0;" in config
    assert "proxy_buffering off;" in config
    assert all("$" not in line for line in config.splitlines() if "proxy_pass " in line)


def test_worker_and_gateway_properties_are_bounded_and_neutral(profile):
    worker = profile.worker_properties()
    gateway = profile.gateway_properties()
    assert "PrivateNetwork=yes" in worker
    assert "RuntimeMaxSec=30" in worker
    assert "MemoryMax=64M" in worker
    assert "MemoryMax=128M" in gateway
    for properties in (worker, gateway):
        assert "MemorySwapMax=0" in properties
        assert "ProtectSystem=strict" in properties
        assert "ProtectHome=yes" in properties
        assert "NoNewPrivileges=yes" in properties
        assert "CapabilityBoundingSet=" in properties
        assert "KillMode=control-group" in properties
        assert "CPUQuota=25%" in properties
        assert "User=998" in properties
        assert "Group=997" in properties
        assert not any("Bind" in p for p in properties)
    generated = "\n".join(worker + gateway) + profile.gateway_config()
    for prohibited in ("codex", "skylink", "argos"):
        assert prohibited not in generated.lower()


def test_probe_command_cannot_load_configuration_or_send_secrets(profile):
    args = profile.probe_command("chatgpt.com")
    assert args[:2] == ("/bin/probe", "--disable")
    assert args[-1] == "https://chatgpt.com/"
    for flag in ("--head", "--http1.1", "--silent", "--show-error"):
        assert flag in args
    assert args[args.index("--resolve") + 1] == "chatgpt.com:443:127.0.0.1"
    assert args[args.index("--noproxy") + 1] == "*"
    assert args[args.index("--max-time") + 1] == "10"
    assert args[args.index("--cacert") + 1] == "/tls/worker-ca.pem"
    assert args[args.index("--output") + 1] == "/dev/null"
    assert not {"--location", "--insecure", "--header", "--data", "--user"} & set(args)
    assert profile.probe_command("auth.openai.com")[-1] == "https://auth.openai.com/"


@pytest.mark.parametrize("host", ["other.example", "chatgpt.com/other", "chatgpt.com?x=1", None])
def test_probe_cannot_select_another_destination(profile, host):
    with pytest.raises(ValueError, match="invalid_probe_host"):
        profile.probe_command(host)


def test_receipt_is_transport_only_and_accepts_http_auth_refusal(api):
    receipt = api.parse_probe_receipt(b'{"http_status":"401","tls_verify":"0"}', exit_code=0)
    assert receipt.http_status == 401
    assert not hasattr(receipt, "ready")
    with pytest.raises(FrozenInstanceError):
        receipt.http_status = 200


@pytest.mark.parametrize("raw", [
    b'{"http_status":"000","tls_verify":"0"}',
    b'{"http_status":"200","tls_verify":"20"}',
    b'{"http_status":200,"tls_verify":"0"}',
    b'{"http_status":"200","tls_verify":false}',
    b'{"http_status":"200","tls_verify":"0","ready":true}',
    b'{"http_status":"200","http_status":"401","tls_verify":"0"}',
    b'{"http_status":"200"}', b'[]', b'null', b'NaN', b'\xff',
    b" " * 129, "provider error with private text", None,
])
def test_failed_or_unbounded_receipts_return_only_stable_error(api, raw):
    with pytest.raises(ValueError) as failure:
        api.parse_probe_receipt(raw, exit_code=0)
    assert str(failure.value) == "invalid_probe_receipt"


def test_receipt_matches_generated_curl_writeout(api, profile):
    args = profile.probe_command("chatgpt.com")
    template = args[args.index("--write-out") + 1]
    raw = template.replace("%{http_code}", "403").replace("%{ssl_verify_result}", "0")
    assert json.loads(raw) == {"http_status": "403", "tls_verify": "0"}
    assert api.parse_probe_receipt(raw.encode(), exit_code=0).http_status == 403


@pytest.mark.parametrize("exit_code", [-9, 1, 28, True, "0", 0.0])
def test_receipt_requires_successful_subprocess(api, exit_code):
    with pytest.raises(ValueError, match="invalid_probe_receipt"):
        api.parse_probe_receipt(b'{"http_status":"200","tls_verify":"0"}', exit_code=exit_code)


def test_receipt_rejects_nonfinal_http_status(api):
    with pytest.raises(ValueError, match="invalid_probe_receipt"):
        api.parse_probe_receipt(b'{"http_status":"101","tls_verify":"0"}', exit_code=0)


def test_gateway_bounds_client_tls_handshake(profile):
    assert "client_header_timeout 2s;" in profile.gateway_config()
