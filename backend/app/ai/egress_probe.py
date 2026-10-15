"""Pure credential-free transport profiles, not a launcher or inference boundary.

Trusted administration must prepare minimal roots, join the loopback-only relay,
verify actual namespace/cgroup enforcement and stop all invocations independently.
No network, filesystem mutation, credential lookup or endpoint wiring occurs here.
"""
from __future__ import annotations

import ipaddress
import json
import re
from dataclasses import dataclass
from uuid import RFC_4122, UUID

_HOSTS = ("chatgpt.com", "auth.openai.com")


@dataclass(frozen=True)
class TransportProbeProfile:
    run_id: str
    uid: int
    gid: int
    public_ipv4: tuple[str, str] | None = None

    def __post_init__(self):
        try:
            parsed = UUID(self.run_id) if type(self.run_id) is str else None
            if (parsed is None or str(parsed) != self.run_id
                    or parsed.version != 4 or parsed.variant != RFC_4122
                    or any(type(v) is not int or not 0 < v < 2**31 for v in (self.uid, self.gid))):
                raise ValueError
        except (ValueError, AttributeError):
            raise ValueError("invalid_probe_identity") from None
        if self.public_ipv4 is not None:
            try:
                if type(self.public_ipv4) is not tuple or len(self.public_ipv4) != 2:
                    raise ValueError
                for value in self.public_ipv4:
                    if type(value) is not str:
                        raise ValueError
                    address = ipaddress.IPv4Address(value)
                    if (str(address) != value or not address.is_global
                            or address.is_multicast or address.is_reserved):
                        raise ValueError
            except ValueError:
                raise ValueError("invalid_probe_addresses") from None

    @property
    def mode(self) -> str:
        return "synthetic" if self.public_ipv4 is None else "public"

    @property
    def root_path(self) -> str:
        return "/opt/copilot-ai/online-check-" + UUID(self.run_id).hex

    def gateway_config(self) -> str:
        addresses = self.public_ipv4
        model = f"{addresses[0]}:443" if addresses else "unix:/bridge/model.sock"
        auth = f"{addresses[1]}:443" if addresses else "unix:/bridge/auth.sock"
        return f"""daemon off;
master_process off;
worker_processes 1;
pid /work/entry.pid;
error_log /dev/null crit;
events {{ worker_connections 16; }}
http {{
    access_log off;
    server_tokens off;
    client_max_body_size 1;
    client_body_buffer_size 1k;
    client_header_buffer_size 1k;
    large_client_header_buffers 2 1k;
    client_header_timeout 2s;
    client_body_timeout 2s;
    keepalive_timeout 0;
    send_timeout 3s;
    client_body_temp_path /work/client;
    proxy_temp_path /work/proxy;
    fastcgi_temp_path /work/fastcgi;
    uwsgi_temp_path /work/uwsgi;
    scgi_temp_path /work/scgi;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache off;
    ssl_session_tickets off;
    proxy_ssl_protocols TLSv1.2 TLSv1.3;
    proxy_ssl_server_name on;
    proxy_ssl_verify on;
    proxy_ssl_verify_depth 4;
    proxy_ssl_trusted_certificate /tls/upstream-ca.pem;
    proxy_ssl_session_reuse off;
    proxy_connect_timeout 2s;
    proxy_read_timeout 3s;
    proxy_send_timeout 3s;
    proxy_buffer_size 4k;
    proxy_buffering off;
    proxy_request_buffering off;
    proxy_max_temp_file_size 0;
    proxy_cache off;
    proxy_store off;
    proxy_next_upstream off;
    proxy_pass_request_headers off;
    proxy_pass_request_body off;
    proxy_method HEAD;
    proxy_http_version 1.1;
    proxy_set_header Connection close;
    proxy_set_header Content-Length "";
    proxy_hide_header Set-Cookie;
    proxy_hide_header Location;
    proxy_hide_header WWW-Authenticate;
    upstream model_transport {{ server {model}; }}
    upstream auth_transport {{ server {auth}; }}
    server {{
        listen unix:/bridge/entry.sock ssl default_server;
        ssl_reject_handshake on;
        return 403;
    }}
{_server(_HOSTS[0], "model_transport")}
{_server(_HOSTS[1], "auth_transport")}
}}
"""

    def _properties(self, role: str, memory: int, tasks: int, runtime: int) -> tuple[str, ...]:
        private = f"rw,size=16M,mode=0700,uid={self.uid},gid={self.gid}"
        return (
            f"User={self.uid}", f"Group={self.gid}",
            f"RootDirectory={self.root_path}/{role}-root", "WorkingDirectory=/work",
            "ProtectSystem=strict", "ProtectHome=yes", "PrivateDevices=yes",
            "PrivateTmp=yes", "NoNewPrivileges=yes", "CapabilityBoundingSet=",
            "ProtectKernelTunables=yes", "ProtectKernelModules=yes", "ProtectControlGroups=yes",
            "ProtectProc=invisible", "ProcSubset=pid", "RestrictSUIDSGID=yes",
            "KillMode=control-group", "TimeoutStopSec=3", "UMask=0077",
            f"RuntimeMaxSec={runtime}", f"MemoryMax={memory}M", "MemorySwapMax=0",
            "CPUQuota=25%", f"TasksMax={tasks}", "TemporaryFileSystem=/work:" + private,
            "StandardError=null",
        )

    def gateway_properties(self) -> tuple[str, ...]:
        properties = self._properties("gateway", 128, 16, 300)
        if self.public_ipv4 is None:
            return properties + ("PrivateNetwork=yes", "RestrictAddressFamilies=AF_UNIX",
                                 "IPAddressDeny=any")
        return properties + (
            "PrivateNetwork=no", "RestrictAddressFamilies=AF_UNIX AF_INET", "IPAddressDeny=any",
            *(f"IPAddressAllow={address}/32" for address in dict.fromkeys(self.public_ipv4)),
        )

    def worker_properties(self) -> tuple[str, ...]:
        return self._properties("worker", 64, 8, 30) + (
            "PrivateNetwork=yes", "RestrictAddressFamilies=AF_UNIX AF_INET",
        )

    def probe_command(self, host: str) -> tuple[str, ...]:
        if type(host) is not str or host not in _HOSTS:
            raise ValueError("invalid_probe_host")
        return (
            "/bin/probe", "--disable", "--http1.1", "--head", "--silent", "--show-error",
            "--proto", "=https", "--proto-redir", "=https", "--max-redirs", "0",
            "--max-time", "10", "--connect-timeout", "3", "--noproxy", "*",
            "--resolve", f"{host}:443:127.0.0.1", "--cacert", "/tls/worker-ca.pem",
            "--output", "/dev/null", "--write-out",
            '{"http_status":"%{http_code}","tls_verify":"%{ssl_verify_result}"}',
            f"https://{host}/",
        )


def _server(host: str, upstream: str) -> str:
    return f"""    server {{
        listen unix:/bridge/entry.sock ssl;
        server_name {host};
        ssl_certificate /tls/entry.pem;
        ssl_certificate_key /tls/entry.key;
        if ($ssl_server_name != "{host}") {{ return 421; }}
        if ($http_host != "{host}") {{ return 421; }}
        if ($request_method != HEAD) {{ return 405; }}
        if ($request_uri != "/") {{ return 403; }}
        if ($request != "HEAD / HTTP/1.1") {{ return 403; }}
        if ($http_authorization != "") {{ return 403; }}
        if ($http_proxy_authorization != "") {{ return 403; }}
        if ($http_cookie != "") {{ return 403; }}
        if ($http_content_length != "") {{ return 403; }}
        if ($http_transfer_encoding != "") {{ return 403; }}
        location = / {{
            proxy_ssl_name {host};
            proxy_set_header Host {host};
            proxy_pass https://{upstream};
        }}
    }}"""


@dataclass(frozen=True)
class TransportReceipt:
    http_status: int


def parse_probe_receipt(raw: bytes, *, exit_code: int) -> TransportReceipt:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError
            result[key] = value
        return result

    try:
        if (type(exit_code) is not int or exit_code != 0
                or type(raw) is not bytes or not 0 < len(raw) <= 128):
            raise ValueError
        data = json.loads(raw.decode("ascii"), object_pairs_hook=unique)
        if (type(data) is not dict or set(data) != {"http_status", "tls_verify"}
                or type(data["http_status"]) is not str
                or re.fullmatch(r"[2-5][0-9]{2}", data["http_status"]) is None
                or data["tls_verify"] != "0"):
            raise ValueError
        return TransportReceipt(int(data["http_status"]))
    except (ValueError, TypeError, RecursionError):
        raise ValueError("invalid_probe_receipt") from None
