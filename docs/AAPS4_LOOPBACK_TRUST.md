# RC1 Loopback Trust

Target: official 4.0.0-rc1 at
`d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`.

The designated phone's existing AAPS3 package trusts system and user certificate
stores inside its localhost/127.0.0.1 network-security domain only. The inspected
RC1 package omits those anchors. This is a packaged-policy difference, not a
recorded Android handshake failure or proof that a particular CA is installed.

`tools/aaps4_compat/loopback-trust.patch` adds four resource lines to retain that
narrow policy. Both literal domains keep includeSubdomains=false. No global or
debug override, new hostname, bundled certificate, pin override, client verifier,
cleartext-policy change or dosing source change is introduced. Connections outside
those domains retain the platform defaults. HTTPS and hostname validation remain
required for the Copilot route; the pre-existing loopback cleartext permission
does not authorize downgrading it. No certificate is installed automatically.

## Verification

The existing checked helper now applies admission, transport-privacy and trust
patches together and reverses only its exact unchanged combined diff. Missing
patches, unknown arguments and concurrent source edits fail closed. Its optional
`--package` mode also runs the real FullDebug app tests, instrumented-test Kotlin
compilation, FullRelease lint and unsigned release assembly with the reviewed
Android JSON isolation recipe. The default CI path keeps native/SDK tests and
then rebuilds the restored pristine SDK; it is not an upstream full APK CI build.

```sh
JAVA_HOME="$JAVA_21_HOME" AAPS_RC1_DIR="$PRISTINE_RC1_DIR" \
  python3 -m unittest discover -s tools/aaps4_compat -p 'test_*.py'
JAVA_HOME="$JAVA_21_HOME" AAPS_RC1_DIR="$PRISTINE_RC1_DIR" \
  bash tools/aaps4_compat/verify_master.sh --package
```

Policy regressions apply/reverse the actual patch to a copy of the pinned XML,
check every domain/anchor, preserve all prior policy and reject conflicting owner
edits. Real-Git recipe tests cover successful/failing packaging, missing trust
input and concurrent trust edits. Inspect the built release's binary XML separately
with the Android build-tools aapt command. XML/packaging tests are not an Android
TrustManager handshake or intended-phone acceptance.

Local combined verification on2026-10-22 passed in3m16s:526 test results,
zero failures/errors/skips, including128 app tests and398 native/SDK cases.
App tests reran; unchanged native tasks may reuse valid cached/up-to-date outputs.
Instrumented-test compilation and FullRelease assembly passed; lint has zero
errors and29 existing warnings. The140468632-byte unsigned APK has SHA-256
`fdd06460c90aeecd91d173a4b5004ba53a7098fe78187c3ca7c5e29229097d78`.
Its manifest points to resource0x7f15000b; the optimized table maps that resource
to res/8G.xml. The decoded binary XML contains exactly the two literal domains
and system/user anchors above. The checked helper restored the pristine source.
The32 tooling checks passed; source hashes remained unchanged. Pristine SDK
preparation then passed separately in11s. Copilot quality and exact-head CI are
recorded separately after they complete; none of these results signs the APK.

The subsequent full Copilot test/lint/compile/assemble gate passed in8m41s:
6651passed,six existing private-input skips,zero failures/errors. Actual pristine
SDK TLS ran6.804s without a skip. Lint retains382 existing warnings and zero errors.
The49,190,374-byte APK passes the size/retention guard with all7115 compiled app
classes retained. Exact-head CI, signing and device acceptance are separate gates.

## Remaining Migration Gates

The final matching-signed combined artifact still requires independent review.
On Android verify the actual installed CA, server certificate and SAN/hostname,
reconnect/restart behavior and authenticated connection without weakening trust.
Fresh AAPS settings export, pump compatibility and JugglucoNG clinical continuity
remain separate prerequisites. Observed broadcast dispatch is not CGM database
ingestion or post-update acceptance.

Clinical input completeness/source reconciliation and current IOB/COB/target
readiness remain incomplete. Keep native_clinical_projection_unavailable closed.
This source patch does not enable Target Manager, update a phone, change its
certificate store or affect treatment. Exact local/CI receipts are recorded only
after their checks complete.
