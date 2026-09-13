import hashlib
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.x509.oid import NameOID, ObjectIdentifier

from app.ai.android_attestation import (ANDROID_KEY_ATTESTATION_OID,
                                        AttestationError,
                                        AttestedKey,
                                        AttestationTrustPolicy,
                                        AndroidAttestationVerifier)
from app.ai.client_auth import AuthError, ClientAuth


APP_ID = "io.aaps.predictivecopilot"
CHALLENGE = bytes(range(32))
NOW = int(datetime(2025, 1, 2, tzinfo=UTC).timestamp() * 1000)


def _length(size):
    if size < 128:
        return bytes([size])
    encoded = size.to_bytes((size.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(encoded)]) + encoded


def _der(tag, content):
    return bytes([tag]) + _length(len(content)) + content


def _integer(value, tag=0x02):
    if value < 0:
        raise ValueError("test helper only encodes non-negative values")
    encoded = value.to_bytes(max(1, (value.bit_length() + 7) // 8), "big")
    if encoded[0] & 0x80:
        encoded = b"\x00" + encoded
    return _der(tag, encoded)


def _sequence(*values):
    return _der(0x30, b"".join(values))


def _set(*values):
    return _der(0x31, b"".join(sorted(values)))


def _octets(value):
    return _der(0x04, value)


def _explicit(tag, value):
    encoded_tag = []
    while True:
        encoded_tag.append(tag & 0x7F)
        tag >>= 7
        if not tag:
            break
    return b"\xbf" + bytes([part | (0x80 if index else 0)
                               for index, part in reversed(list(enumerate(encoded_tag)))]) + _length(len(value)) + value


def _key_description(*, package=APP_ID, signer_digest=b"s" * 32,
                     challenge=CHALLENGE, security_level=1, device_locked=True,
                     boot_state=0, application_id_location="software",
                     extra_package=None, extra_signer_digest=None):
    packages = [_sequence(_octets(package.encode("ascii")), _integer(1))]
    if extra_package is not None:
        packages.append(_sequence(_octets(extra_package.encode("ascii")), _integer(1)))
    signer_digests = [_octets(signer_digest)]
    if extra_signer_digest is not None:
        signer_digests.append(_octets(extra_signer_digest))
    application_id = _sequence(
        _set(*packages),
        _set(*signer_digests),
    )
    root_of_trust = _sequence(
        _octets(b"synthetic-verified-boot-key"),
        _der(0x01, b"\xff" if device_locked else b"\x00"),
        _integer(boot_state, tag=0x0A),
        _octets(b"synthetic-boot-hash"),
    )
    software_values = []
    tee_values = [_explicit(704, root_of_trust)]
    if application_id_location in ("software", "both"):
        software_values.append(_explicit(709, _octets(application_id)))
    if application_id_location in ("tee", "both"):
        tee_values.append(_explicit(709, _octets(application_id)))
    return _sequence(
        _integer(4),
        _integer(security_level, tag=0x0A),
        _integer(4),
        _integer(security_level, tag=0x0A),
        _octets(challenge),
        _octets(b""),
        _sequence(*software_values),
        _sequence(*tee_values),
    )


def _certificate(subject, issuer, public_key, issuer_key, *, ca, extension=None,
                 path_length=None, unknown_critical_extension=False):
    now = datetime(2025, 1, 1, tzinfo=UTC)
    builder = (x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(public_key)
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=ca, path_length=path_length), critical=True))
    if ca:
        builder = builder.add_extension(x509.KeyUsage(
            digital_signature=False, content_commitment=False, key_encipherment=False,
            data_encipherment=False, key_agreement=False, key_cert_sign=True,
            crl_sign=True, encipher_only=False, decipher_only=False), critical=True)
    if extension is not None:
        builder = builder.add_extension(
            x509.UnrecognizedExtension(ANDROID_KEY_ATTESTATION_OID, extension), critical=False)
    if unknown_critical_extension:
        builder = builder.add_extension(
            x509.UnrecognizedExtension(ObjectIdentifier("1.3.6.1.4.1.55555.1"), b"\x05\x00"),
            critical=True,
        )
    return builder.sign(issuer_key, hashes.SHA256())


def _chain(*, attestation_on_intermediate=False, root_path_length=None,
           leaf_unknown_critical_extension=False, **description):
    root_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    root_subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic Android Root")])
    root = _certificate(
        root_subject, root_subject, root_key.public_key(), root_key,
        ca=True, path_length=root_path_length,
    )
    device_key = ec.generate_private_key(ec.SECP256R1())
    if attestation_on_intermediate:
        attestation_certificate = _certificate(
            x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic Attested Key")]),
            root.subject,
            device_key.public_key(),
            root_key,
            ca=True,
            extension=_key_description(**description),
        )
        transport_key = ec.generate_private_key(ec.SECP256R1())
        leaf = _certificate(
            x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic Added Leaf")]),
            attestation_certificate.subject,
            transport_key.public_key(),
            device_key,
            ca=False,
            unknown_critical_extension=leaf_unknown_critical_extension,
        )
        chain = [
            leaf.public_bytes(serialization.Encoding.DER),
            attestation_certificate.public_bytes(serialization.Encoding.DER),
            root.public_bytes(serialization.Encoding.DER),
        ]
    else:
        leaf = _certificate(
            x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic Device Key")]),
            root.subject,
            device_key.public_key(),
            root_key,
            ca=False,
            extension=_key_description(**description),
            unknown_critical_extension=leaf_unknown_critical_extension,
        )
        chain = [
            leaf.public_bytes(serialization.Encoding.DER),
            root.public_bytes(serialization.Encoding.DER),
        ]
    public_key_der = device_key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return chain, public_key_der


def _policy(chain, signer_digest=b"s" * 32):
    return AttestationTrustPolicy(
        application_id=APP_ID,
        allowed_signer_sha256=frozenset({signer_digest.hex()}),
        trusted_root_sha256=frozenset({hashlib.sha256(chain[-1]).hexdigest()}),
        revoked_certificate_sha256=frozenset(),
        revocation_checked_ms=NOW,
    )


def test_attestation_accepts_root_pinned_hardware_key_and_exact_app_identity():
    chain, public_key_der = _chain()
    attested = AndroidAttestationVerifier(_policy(chain)).verify(
        certificate_chain_der=chain, expected_challenge=CHALLENGE, now_ms=NOW)
    assert attested.public_key_der == public_key_der
    assert attested.key_fingerprint == hashlib.sha256(public_key_der).hexdigest()
    assert attested.application_id == APP_ID


@pytest.mark.parametrize("description", [
    {"application_id_location": "tee"},
    {"application_id_location": "both"},
    {"extra_package": "io.aaps.predictivecopilot.shared"},
    {"extra_signer_digest": b"x" * 32},
])
def test_attestation_rejects_non_software_or_ambiguous_application_identity(description):
    chain, _ = _chain(**description)
    with pytest.raises(AttestationError, match="^attestation_rejected$"):
        AndroidAttestationVerifier(_policy(chain)).verify(
            certificate_chain_der=chain, expected_challenge=CHALLENGE, now_ms=NOW)


def test_attestation_returns_key_from_nearest_root_extension_certificate():
    chain, attested_public_key_der = _chain(attestation_on_intermediate=True)
    leaf_public_key_der = x509.load_der_x509_certificate(chain[0]).public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)

    attested = AndroidAttestationVerifier(_policy(chain)).verify(
        certificate_chain_der=chain, expected_challenge=CHALLENGE, now_ms=NOW)

    assert attested.public_key_der == attested_public_key_der
    assert attested.public_key_der != leaf_public_key_der


def test_attestation_stops_reading_after_bounded_chain_prefix():
    chain, _ = _chain()

    def overlong_chain():
        yield from chain
        for _ in range(7):
            yield chain[-1]
        raise AssertionError("verifier consumed beyond the bounded rejection prefix")

    with pytest.raises(AttestationError, match="^attestation_rejected$"):
        AndroidAttestationVerifier(_policy(chain)).verify(
            certificate_chain_der=overlong_chain(),
            expected_challenge=CHALLENGE,
            now_ms=NOW,
        )


@pytest.mark.parametrize("chain_options", [
    {"attestation_on_intermediate": True, "root_path_length": 0},
    {"leaf_unknown_critical_extension": True},
])
def test_attestation_rejects_invalid_certificate_path_constraints(chain_options):
    chain, _ = _chain(**chain_options)
    with pytest.raises(AttestationError, match="^attestation_rejected$"):
        AndroidAttestationVerifier(_policy(chain)).verify(
            certificate_chain_der=chain, expected_challenge=CHALLENGE, now_ms=NOW)


@pytest.mark.parametrize("description", [
    {"challenge": b"wrong" * 6},
    {"package": "io.aaps.copilot"},
    {"signer_digest": b"x" * 32},
    {"security_level": 0},
    {"device_locked": False},
    {"boot_state": 1},
])
def test_attestation_rejects_untrusted_runtime_or_application_claims(description):
    chain, _ = _chain(**description)
    with pytest.raises(AttestationError, match="^attestation_rejected$"):
        AndroidAttestationVerifier(_policy(chain)).verify(
            certificate_chain_der=chain, expected_challenge=CHALLENGE, now_ms=NOW)


def test_attestation_rejects_unpinned_or_revoked_certificates():
    trusted_chain, _ = _chain()
    untrusted_chain, _ = _chain()
    policy = _policy(trusted_chain)
    verifier = AndroidAttestationVerifier(policy)
    with pytest.raises(AttestationError):
        verifier.verify(certificate_chain_der=untrusted_chain, expected_challenge=CHALLENGE, now_ms=NOW)
    revoked = replace(policy, revoked_certificate_sha256=frozenset({
        hashlib.sha256(trusted_chain[0]).hexdigest()}))
    with pytest.raises(AttestationError):
        AndroidAttestationVerifier(revoked).verify(
            certificate_chain_der=trusted_chain, expected_challenge=CHALLENGE, now_ms=NOW)


def test_attested_enrollment_binds_only_verified_key_and_session(tmp_path):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    chain, public_key_der = _chain()
    verifier = AndroidAttestationVerifier(_policy(chain))
    owner = str(uuid4())
    code = auth.issue_enrollment(owner_id=owner, now_ms=NOW)
    try:
        with pytest.raises(AuthError):
            auth.enroll_attested(code, certificate_chain_der=chain,
                                 expected_challenge=b"bad", verifier=verifier, now_ms=NOW + 1)
        tokens = auth.enroll_attested(code, certificate_chain_der=chain,
                                      expected_challenge=CHALLENGE, verifier=verifier, now_ms=NOW + 2)
        key_fingerprint = hashlib.sha256(public_key_der).hexdigest()
        bound = auth.authenticate_attested(tokens.access_token, key_fingerprint=key_fingerprint,
                                           now_ms=NOW + 3)
        assert bound.public_key_der == public_key_der
        assert bound.owner_id == owner
        with pytest.raises(AuthError):
            auth.authenticate_attested(tokens.access_token, key_fingerprint="0" * 64,
                                       now_ms=NOW + 3)
        auth.revoke_attested_device(owner_id=owner, key_fingerprint=key_fingerprint, now_ms=NOW + 4)
        with pytest.raises(AuthError):
            auth.authenticate_attested(tokens.access_token, key_fingerprint=key_fingerprint,
                                       now_ms=NOW + 5)
    finally:
        auth.engine.dispose()


def test_attestation_database_does_not_store_chain_or_challenge(tmp_path):
    auth = ClientAuth(tmp_path / "identity.sqlite")
    chain, _ = _chain()
    code = auth.issue_enrollment(owner_id=str(uuid4()), now_ms=NOW)
    try:
        auth.enroll_attested(code, certificate_chain_der=chain, expected_challenge=CHALLENGE,
                             verifier=AndroidAttestationVerifier(_policy(chain)), now_ms=NOW + 1)
    finally:
        auth.engine.dispose()
    stored = (tmp_path / "identity.sqlite").read_bytes()
    assert CHALLENGE not in stored
    assert chain[0] not in stored
    assert chain[1] not in stored


def test_attested_enrollment_rejects_counterfeit_verifier_result(tmp_path):
    class CounterfeitVerifier:
        def verify(self, **_):
            return AttestedKey(
                key_fingerprint="a" * 64,
                public_key_der=b"counterfeit-public-key",
                certificate_chain_sha256="b" * 64,
                application_id=APP_ID,
            )

    auth = ClientAuth(tmp_path / "identity.sqlite")
    code = auth.issue_enrollment(owner_id=str(uuid4()), now_ms=NOW)
    try:
        with pytest.raises(AuthError):
            auth.enroll_attested(code, certificate_chain_der=[], expected_challenge=CHALLENGE,
                                 verifier=CounterfeitVerifier(), now_ms=NOW + 1)
        # A rejected verifier must not consume a one-time enrollment code.
        assert auth.enroll(code, now_ms=NOW + 2).access_token
    finally:
        auth.engine.dispose()
