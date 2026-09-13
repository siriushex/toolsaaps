"""Strict Android Key Attestation validation for Copilot device enrollment.

Trust roots, release signer digests and the revocation snapshot come only from
trusted server configuration. Client headers cannot influence that policy.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime
from itertools import islice
from typing import Iterable

from asn1crypto import core, parser
from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519, ed448, padding, rsa
from cryptography.x509.verification import (Criticality, ExtensionPolicy,
                                            PolicyBuilder, Store,
                                            VerificationError)
from cryptography.x509.oid import ObjectIdentifier


ANDROID_KEY_ATTESTATION_OID = ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")
_HEX = re.compile(r"[0-9a-f]{64}")
_MAX_CHAIN_CERTIFICATES = 8
_MAX_CERTIFICATE_BYTES = 16_384
_MAX_CHAIN_BYTES = 65_536
_MAX_REVOCATION_AGE_MS = 7 * 86_400_000

_CA_EXTENSION_POLICY = (ExtensionPolicy.permit_all()
    .require_present(x509.BasicConstraints, Criticality.CRITICAL, None)
    .require_present(x509.KeyUsage, Criticality.CRITICAL, None))
_END_ENTITY_EXTENSION_POLICY = (ExtensionPolicy.permit_all()
    .may_be_present(x509.BasicConstraints, Criticality.AGNOSTIC, None)
    .may_be_present(x509.KeyUsage, Criticality.AGNOSTIC, None))


class AttestationError(Exception):
    def __init__(self):
        super().__init__("attestation_rejected")


class _SecurityLevel(core.Enumerated):
    _map = {0: "software", 1: "trusted_environment", 2: "strong_box"}


class _VerifiedBootState(core.Enumerated):
    _map = {0: "verified", 1: "self_signed", 2: "unverified", 3: "failed"}


class _KeyDescription(core.Sequence):
    _fields = [
        ("attestation_version", core.Integer),
        ("attestation_security_level", _SecurityLevel),
        ("keymaster_version", core.Integer),
        ("keymaster_security_level", _SecurityLevel),
        ("attestation_challenge", core.OctetString),
        ("unique_id", core.OctetString),
        ("software_enforced", core.Any),
        ("tee_enforced", core.Any),
    ]


class _RootOfTrust(core.Sequence):
    _fields = [
        ("verified_boot_key", core.OctetString),
        ("device_locked", core.Boolean),
        ("verified_boot_state", _VerifiedBootState),
        ("verified_boot_hash", core.OctetString, {"optional": True}),
    ]


@dataclass(frozen=True)
class AttestationTrustPolicy:
    application_id: str
    allowed_signer_sha256: frozenset[str]
    trusted_root_sha256: frozenset[str]
    revoked_certificate_sha256: frozenset[str]
    revocation_checked_ms: int
    max_revocation_age_ms: int = _MAX_REVOCATION_AGE_MS

    def __post_init__(self):
        if not re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+", self.application_id):
            raise ValueError("invalid_application_id")
        for values in (self.allowed_signer_sha256, self.trusted_root_sha256,
                       self.revoked_certificate_sha256):
            if not isinstance(values, frozenset) or not all(
                    isinstance(value, str) and _HEX.fullmatch(value) for value in values):
                raise ValueError("invalid_digest_set")
        if not self.allowed_signer_sha256 or not self.trusted_root_sha256:
            raise ValueError("trust_policy_required")
        if (type(self.revocation_checked_ms) is not int
                or type(self.max_revocation_age_ms) is not int
                or not 0 < self.max_revocation_age_ms <= _MAX_REVOCATION_AGE_MS):
            raise ValueError("invalid_revocation_policy")


@dataclass(frozen=True)
class AttestedKey:
    key_fingerprint: str
    public_key_der: bytes = field(repr=False)
    certificate_chain_sha256: str
    application_id: str


def _parse_one(encoded: bytes):
    try:
        tag_class, method, tag, header, contents, trailer = parser.parse(encoded, strict=True)
        consumed = len(header) + len(contents) + len(trailer)
        if (consumed != len(encoded) or trailer
                or parser.emit(tag_class, method, tag, contents) != encoded
                or (tag_class == 0 and method == 0
                    and core.load(encoded, strict=True).dump(force=True) != encoded)):
            raise ValueError()
        return tag_class, method, tag, contents
    except (ValueError, TypeError):
        raise AttestationError() from None


def _split_children(contents: bytes) -> list[bytes]:
    children = []
    while contents:
        try:
            # This buffer intentionally contains consecutive TLVs. Each extracted
            # child is then strictly parsed and DER-reencoded before use.
            tag_class, method, tag, header, value, trailer = parser.parse(contents, strict=False)
            size = len(header) + len(value) + len(trailer)
            if not 0 < size <= len(contents):
                raise ValueError()
            child = contents[:size]
            if (trailer or parser.emit(tag_class, method, tag, value) != child
                    or (tag_class == 0 and method == 0
                        and core.load(child, strict=True).dump(force=True) != child)):
                raise ValueError()
            children.append(child)
            contents = contents[size:]
        except (ValueError, TypeError):
            raise AttestationError() from None
    return children


def _children(encoded: bytes, outer_tag: int = 16) -> list[bytes]:
    tag_class, method, tag, contents = _parse_one(encoded)
    if (tag_class, method, tag) != (0, 1, outer_tag):
        raise AttestationError()
    children = _split_children(contents)
    if outer_tag == 17 and children != sorted(children):
        raise AttestationError()
    return children


def _single_tlv(contents: bytes) -> bytes:
    children = _split_children(contents)
    if len(children) != 1:
        raise AttestationError()
    return children[0]


def _exact(encoded: bytes, tag: int, *, method: int = 0) -> bytes:
    tag_class, actual_method, actual_tag, contents = _parse_one(encoded)
    if (tag_class, actual_method, actual_tag) != (0, method, tag):
        raise AttestationError()
    return contents


def _octets(encoded: bytes) -> bytes:
    return _exact(encoded, 4)


def _authorization_values(encoded: bytes, tag_number: int) -> list[bytes]:
    values = []
    for child in _children(encoded):
        tag_class, method, tag, contents = _parse_one(child)
        if (tag_class, method, tag) == (2, 1, tag_number):
            values.append(contents)
    return values


def _authorization_value(encoded: bytes, tag_number: int) -> bytes:
    values = _authorization_values(encoded, tag_number)
    if len(values) != 1:
        raise AttestationError()
    return values[0]


def _application_identity(encoded: bytes) -> tuple[str, str]:
    application = _octets(_single_tlv(encoded))
    values = _children(application)
    if len(values) != 2:
        raise AttestationError()
    package_set, digest_set = values
    packages = _children(package_set, outer_tag=17)
    digests = [_octets(value) for value in _children(digest_set, outer_tag=17)]
    if len(packages) != 1 or len(digests) != 1 or len(digests[0]) != 32:
        raise AttestationError()
    package_values = _children(packages[0])
    if len(package_values) != 2:
        raise AttestationError()
    try:
        package = _octets(package_values[0]).decode("ascii")
        version_field = core.Integer.load(package_values[1], strict=True)
        version = version_field.native
        if (version_field.dump(force=True) != package_values[1]
                or type(version) is not int or version < 0):
            raise ValueError()
    except (UnicodeDecodeError, ValueError, TypeError):
        raise AttestationError() from None
    return package, digests[0].hex()


def _verify_certificate_signature(certificate: x509.Certificate, issuer_key) -> None:
    try:
        if isinstance(issuer_key, rsa.RSAPublicKey):
            issuer_key.verify(certificate.signature, certificate.tbs_certificate_bytes,
                              padding.PKCS1v15(), certificate.signature_hash_algorithm)
        elif isinstance(issuer_key, ec.EllipticCurvePublicKey):
            issuer_key.verify(certificate.signature, certificate.tbs_certificate_bytes,
                              ec.ECDSA(certificate.signature_hash_algorithm))
        elif isinstance(issuer_key, (ed25519.Ed25519PublicKey, ed448.Ed448PublicKey)):
            issuer_key.verify(certificate.signature, certificate.tbs_certificate_bytes)
        else:
            raise ValueError()
    except (InvalidSignature, ValueError, TypeError):
        raise AttestationError() from None


class AndroidAttestationVerifier:
    def __init__(self, policy: AttestationTrustPolicy):
        if not isinstance(policy, AttestationTrustPolicy):
            raise ValueError("attestation_policy_required")
        self.policy = policy

    def verify(self, *, certificate_chain_der: Iterable[bytes], expected_challenge: bytes,
               now_ms: int) -> AttestedKey:
        try:
            if (type(now_ms) is not int or now_ms <= 0
                    or type(expected_challenge) is not bytes or len(expected_challenge) != 32
                    or not now_ms - self.policy.max_revocation_age_ms
                    <= self.policy.revocation_checked_ms <= now_ms):
                raise ValueError()
            chain = tuple(islice(iter(certificate_chain_der),
                                  _MAX_CHAIN_CERTIFICATES + 1))
            if not 2 <= len(chain) <= _MAX_CHAIN_CERTIFICATES or any(
                    type(item) is not bytes or not 1 <= len(item) <= _MAX_CERTIFICATE_BYTES
                    for item in chain) or sum(map(len, chain)) > _MAX_CHAIN_BYTES:
                raise ValueError()
            certificates = tuple(x509.load_der_x509_certificate(item) for item in chain)
        except Exception:
            raise AttestationError() from None

        fingerprints = tuple(hashlib.sha256(item).hexdigest() for item in chain)
        if (fingerprints[-1] not in self.policy.trusted_root_sha256
                or len(set(fingerprints)) != len(fingerprints)
                or any(value in self.policy.revoked_certificate_sha256 for value in fingerprints)):
            raise AttestationError()
        when = datetime.fromtimestamp(now_ms / 1000, tz=UTC)
        for certificate in certificates:
            if not certificate.not_valid_before_utc <= when <= certificate.not_valid_after_utc:
                raise AttestationError()
        root = certificates[-1]
        if root.subject != root.issuer:
            raise AttestationError()
        _verify_certificate_signature(root, root.public_key())

        try:
            verified = (PolicyBuilder()
                .store(Store([root]))
                .time(when)
                .max_chain_depth(_MAX_CHAIN_CERTIFICATES - 2)
                .extension_policies(
                    ca_policy=_CA_EXTENSION_POLICY,
                    ee_policy=_END_ENTITY_EXTENSION_POLICY,
                )
                .build_client_verifier()
                .verify(certificates[0], list(certificates[1:-1])))
            verified_der = tuple(certificate.public_bytes(serialization.Encoding.DER)
                                 for certificate in verified.chain)
            if verified_der != chain:
                raise ValueError()
        except (VerificationError, ValueError, TypeError):
            raise AttestationError() from None

        extension = None
        attestation_certificate = None
        for certificate in reversed(certificates[:-1]):
            try:
                extension = certificate.extensions.get_extension_for_oid(ANDROID_KEY_ATTESTATION_OID).value.value
                attestation_certificate = certificate
                break
            except x509.ExtensionNotFound:
                continue
        if extension is None or attestation_certificate is None:
            raise AttestationError()
        try:
            description = _KeyDescription.load(extension, strict=True)
            if description.dump(force=True) != extension:
                raise ValueError()
            if (description["attestation_security_level"].native not in
                    ("trusted_environment", "strong_box")
                    or description["keymaster_security_level"].native not in
                    ("trusted_environment", "strong_box")
                    or description["attestation_challenge"].native != expected_challenge):
                raise ValueError()
            software = description["software_enforced"].dump()
            tee = description["tee_enforced"].dump()
            root_encoded = _single_tlv(_authorization_value(tee, 704))
            root_of_trust = _RootOfTrust.load(root_encoded, strict=True)
            if (root_of_trust.dump(force=True) != root_encoded
                    or root_of_trust["device_locked"].native is not True
                    or root_of_trust["verified_boot_state"].native != "verified"
                    or _authorization_values(tee, 709)):
                raise ValueError()
            application_id, signer = _application_identity(
                _authorization_value(software, 709))
        except (AttestationError, ValueError, TypeError):
            raise AttestationError() from None
        if application_id != self.policy.application_id or signer not in self.policy.allowed_signer_sha256:
            raise AttestationError()
        public_key = attestation_certificate.public_key()
        if not isinstance(public_key, ec.EllipticCurvePublicKey) or not isinstance(public_key.curve, ec.SECP256R1):
            raise AttestationError()
        public_key_der = public_key.public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        return AttestedKey(
            key_fingerprint=hashlib.sha256(public_key_der).hexdigest(),
            public_key_der=public_key_der,
            certificate_chain_sha256=hashlib.sha256(b"".join(chain)).hexdigest(),
            application_id=application_id,
        )
