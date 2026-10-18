# Internet APK Updates

## User flow

The drawer contains About. Opening it makes no network call. Check for update
fetches a small public manifest; a newer compatible version offers Download,
progress and Cancel. Only a verified download offers Install. Android retains
its installation permission and confirmation. If permission is absent, the
user returns from Android settings and explicitly taps Install again.
Cancelled operations cannot publish late success or failure over a newer check.
Storage write failures show a local-storage error, not a network retry prompt.
There is no background update, silent install, downgrade or database migration
introduced by this feature. Installation briefly interrupts Copilot itself,
including its alerts. AAPS is neither stopped nor updated.

Existing version1 installations do not contain the updater. Bootstrap once from
the HTTPS download page or USB; subsequent versions use About. Do not uninstall
the existing app. Version2 is0.1.1; every subsequent publication must increase
the Android versionCode and retain the installed signing identity.

## Contract and trust

- Fixed origin `https://diai.centv.ru`, manifest `/app-updates/latest.json`.
- Closed schema1: packageName, versionCode, versionName, minSdk, sizeBytes,
  sha256, apkUrl, notes. Manifest at most16KiB; APK at most200MiB.
- Exact APK URL `/app-updates/<lowercase-sha256>.apk`, no redirect, query,
  external origin, user credentials, medical payload or authenticated AI client.
- Size and SHA-256 checked while streaming; archive package/version/name/minSdk
  and complete current signing-certificate set must match. Check hash and archive
  again before offering the file to Android's verifying package installer.
- Only private `cache/app-updates/verified/` is shared by a read-only URI grant.
  Partial files are outside the FileProvider root. Cancellation does not install;
  abandoned partial files are cleaned on the next explicit download. Process
  death requires a new check/download; it cannot automatically resume installation.
- Rotation/navigation state uses the route ViewModel. No application controller,
  target, dose, alert permission or AI consent is changed by update checks.
- HTTPS/server integrity and the existing signing key are trusted. The manifest
  is not independently signed. This preserves the existing test-build signer;
  it does not claim a new hardened production signing-key deployment. Signing
  rotation and split APKs are deliberately unsupported and fail closed.

## Prepare and publish

`tools/app_updates/release.py prepare` requires the local APK, aapt, apksigner,
the independently checked expected signer SHA-256 and a new output directory.
It invokes actual APK signature verification, reads compiled metadata, hashes
bytes, and emits only a content-addressed APK, manifest and bootstrap page.
Never upload keystores, phone databases, credentials, logs or Gradle directories.

`release.py publish --staged STAGED --public /var/www/copilot-downloads/app-updates`
validates the staged manifest and copied APK, serializes publishers using flock,
rejects downgrade/replacement of a version, writes the immutable artifact and
page, then atomically replaces latest.json last. Old artifacts are retained.
Review storage capacity before releases; retention deletion is not automatic.

Include `tools/app_updates/nginx.conf` only inside the existing HTTPS host.
It exposes exactly the manifest, bootstrap page and hash-named APKs, not directory
listings, hidden temporary files, AI configurations or application data. No AI
process restart is needed. Back up only the affected nginx host before editing,
test `nginx -t`, then gracefully reload. Preserve the previous manifest outside
the public tree when updating an existing release.

Rollback: restore the saved affected host configuration, validate and reload;
or restore a previously verified manifest to stop offering the new release.
Already installed newer apps are never downgraded. Do not restore AI credentials
or phone data as part of an APK release rollback.

## Verification

Run Android updater/controller/transport/native UI tests, full Android unit tests,
lint/compile/assemble, and `python3 -m unittest discover -s tools/app_updates`.
Then independently download public manifest and APK over HTTPS, compare size,
SHA-256, package/version/signature, check content types, HEAD/GET and hidden-path
rejection, and verify nginx plus existing AI services remain active. Artifact
download is not proof of installation. Actual phone installation requires its
own receipt/user confirmation; unavailable ADB must remain explicitly reported.
