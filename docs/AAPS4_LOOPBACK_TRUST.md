# RC1 Loopback Trust And Packaging

Pinned upstream: `d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0` (4.0.0-rc1).
The four-line resource patch and policy tests originated in PR19. This integration
combines them with the current retained-history, receiver and uploader patches;
the older PR's package is not evidence for this combined artifact.

## Scope

Trust system and user certificate stores only for literal `localhost` and
`127.0.0.1`, both with `includeSubdomains=false`. External hosts retain platform
defaults. No global/debug override, bundled CA, pin override, hostname bypass,
cleartext-policy change or dosing calculation change is introduced. The existing
loopback cleartext allowance does not authorize switching the Copilot route away
from HTTPS. This patch neither installs a CA nor proves an Android handshake.

## Combined Verification

`verify_master.sh --package` applies immutable copies of all current patches,
runs native suites plus app FullDebug tests, instrumented-test compilation,
FullRelease lint and unsigned release packaging, then reverses only its own
unchanged source changes in reverse dependency order. Missing inputs, unknown
options and concurrent edits fail closed. Default mode remains native tests only.
CI explicitly selects `--package`, then rebuilds the restored pristine SDK before
the Copilot suite. No signed APK or private configuration is uploaded to CI.

```sh
JAVA_HOME="$JAVA_21_HOME" AAPS_RC1_DIR="$PRISTINE_RC1_DIR" \
  python3 -m unittest discover -s tools/aaps4_compat -p 'test_*.py'
JAVA_HOME="$JAVA_21_HOME" AAPS_RC1_DIR="$PRISTINE_RC1_DIR" \
  bash tools/aaps4_compat/verify_master.sh --package
```

Tests check every domain and anchor, byte-exact reversal, missing trust inputs,
concurrent edits, combined task selection and unsuccessful-build cleanup. Inspect
the final binary XML and package identity separately. Packaging success does not
mean that the intended phone trusts the current private CA or server certificate.

## Device Admission

Before replacement verify the intended phone, matching signer, a fresh AAPS
settings export, selected pump/CGM source, actual JugglucoNG ingestion and the
clinical input/target migration path. Do not uninstall, clear data, disable AAPS
or restore old replay counters. An old APK is not a database downgrade plan.
After an uncertain installation outcome inspect installed state before retrying.

The current `native_clinical_projection_unavailable` guard remains closed.
Installing RC1 now would not preserve the existing complete Copilot target
integration. Finish that clinical adapter and migration acceptance before replacing
the active therapy application. Copilot's compatible legacy-route update is a
separate operation. Successful CI or a clean Combo journal cannot grant insulin
delivery completeness, historical UTC mapping or meal-time recommendation rights.
