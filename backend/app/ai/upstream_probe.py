"""Pure status-only observations; not authentication or inference readiness."""
from __future__ import annotations

import json
from dataclasses import dataclass

from .egress_probe import TransportProbeProfile, TransportReceipt, parse_probe_receipt


@dataclass(frozen=True)
class ObservedProbeProfile(TransportProbeProfile):
    def gateway_config(self) -> str:
        configuration = super().gateway_config()
        hidden = "    proxy_hide_header WWW-Authenticate;"
        upstream = "            proxy_pass https://"
        if configuration.count(hidden) != 1 or configuration.count(upstream) != 2:
            raise ValueError("unsupported_gateway_template")
        configuration = configuration.replace(hidden, hidden + "\n    proxy_hide_header X-Transport-Status;")
        return configuration.replace(upstream,
            "            add_header X-Transport-Status $upstream_status always;\n" + upstream)

    def probe_command(self, host: str) -> tuple[str, ...]:
        command = list(super().probe_command(host))
        command[command.index("--write-out") + 1] = (
            '{"http_status":"%{http_code}","tls_verify":"%{ssl_verify_result}",'
            '"upstream_status":"%header{x-transport-status}"}')
        return tuple(command)


def parse_observed_receipt(raw: bytes, *, exit_code: int) -> TransportReceipt:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError
            result[key] = value
        return result

    try:
        if type(raw) is not bytes or not 0 < len(raw) <= 192:
            raise ValueError
        data = json.loads(raw.decode("ascii"), object_pairs_hook=unique)
        if (type(data) is not dict or set(data) != {"http_status", "tls_verify", "upstream_status"}
                or type(data["upstream_status"]) is not str
                or data["upstream_status"] != data["http_status"]):
            raise ValueError
        receipt = parse_probe_receipt(json.dumps({"http_status": data["http_status"],
            "tls_verify": data["tls_verify"]}).encode("ascii"), exit_code=exit_code)
        if receipt.http_status >= 500:
            raise ValueError
        return receipt
    except (ValueError, TypeError, RecursionError):
        raise ValueError("invalid_observed_receipt") from None
