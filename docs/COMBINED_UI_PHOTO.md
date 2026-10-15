# Combined Native UI And Food Photo Source

## Included

The integration retains the current owner AI/Room-history source and merges
PR #6, including its main-branch artwork dependency. It is not a replacement
with the older graphics-only APK. Clinical settings, Room schema 34, prediction,
Eating Soon, target selection and delivery commands are unchanged.

The meal dialog has a photo entry. Its foreground flow checks the signed,
per-task MEAL_PHOTO capability before offering camera/gallery. Selecting an
image is explicit consent to upload it to the configured owner AI server.
An independent FileProvider exposes only the meal camera cache directory,
not the clinical PDF directory. Input is bounded at 16 MiB/100 million pixels,
sampled, EXIF-oriented, re-encoded to JPEG <=1280 pixels and <=1 MiB without
source metadata. Temporary camera files are deleted after use/dismissal;
old abandoned camera files are cleaned on the next camera use.

The signed client uses only `/api/ai/v1/meal-photo/jobs` and correlated job
resources. It cannot send images through CHAT, external URLs or personal keys.
Capability multiplexing selects each route independently and preserves the
existing closed CHAT checks. One immutable UUID/body/deadline is submitted once;
ambiguous acceptance is unknown, not permission to replay. Only a correlated
receipt grants polling/cancellation authority. Cancellation is bounded.

The result is an uncertain editable draft: ingredient mass, eaten percentage,
preparation, profile and complete nutrient ranges. Schema-v1 nutrient facts and
declared energy are PER 100 G in the declared preparation state, not portion
totals. The user must select the mass; no midpoint is silently substituted.
Unknown totals are shown as unknown, never zero. The user enters explicit
carbohydrate grams and uses the result in the existing food form. Recording
food still requires that form's final confirmation. Nutrition estimates and
images are not persisted as a Room nutrition archive by this stage; no GI,
bolus or target is inferred from the photo.

## History Capacity

Year-scale context avoids SQLite ciphertext sorting, reduces unnecessary
payload parsing, and uses a bounded per-device in-memory aggregate. A cache hit
requires a complete SHA-256 fingerprint of every eligible encrypted envelope
and its canonical metadata in the same SQLite snapshot, not an mtime/count
shortcut. Cold scans still authenticate AEAD and payload digests. Revisions,
tombstones, future cutoffs and retention remain authoritative. SQLite JSON
functions are required; the 5-second/50M-instruction budgets are unchanged.
The scan remains O(N), so a local capacity result is not a server latency SLA.

## Server ChatGPT Boundary

`meal_vision.py` is an inactive typed adapter for an admitted contained image
worker, not a deployed image service. It provides a fixed food-only per-100g
prompt, immutable local correlation, strict output validation, one attempt,
deadline and independent-stop requirements. `meal_vision_image.py` independently
decodes/re-encodes bounded JPEGs using pinned Pillow 12.3.0. Its thread is not
an OS sandbox. The default transport is unavailable; default owner API
composition and readiness are not enabled by these files.

Pinned public native commit `3d2ee51ca2d5db578f328aa75e20aa22c0197c9a`
declares v2 UserInput Image with `type=image` and `url`; a JPEG data URI belongs
in that field, never a local file path. This is schema evidence, not proof of
a working contained image turn. A cached model catalog's image modality is
also not a live inference result or operational admission.

Before enabling the route, implement and verify the dedicated contained image
transport, bounded decoder isolation, image-sized pipes, exact model capability,
the SAME login/text SessionProfileGuard and independently confirmed process/IO
teardown. Keep model/reasoning settings and tool prohibitions. Do not widen or
tunnel through the text-only supervisor. Then perform a separately scoped
synthetic-image model test and signed client test. No personal medical photo
or history is needed for that test. Public API unavailability is a deployment
blocker, not a successful estimate.

## Verification

Backend full suite with the pinned decoder: 962 passed, 27 opt-in skips.
The separate 365-day capacity test passed on 210241 synthetic encrypted records:
cold 4.02 s, verified cache 0.97 s, restart 3.64 s, external revision 3.46 s
(macOS/Python 3.12/SQLite 3.53.4). These are local synthetic measurements.
Android focused transport: 15 passed; final focused UI/signed-client: 26 passed.
Frozen Android suite: 5667 tests, zero failures/errors, 6 private-fixture skips.
Final test/lint/assemble passed in 15m56s. Lint: zero errors, 357 warnings,
8 hints (including two platform-ExifInterface recommendations; no checks disabled).
Debug APK signature matches the prior retained device APK. An in-place update
on the intended device was verified by the installed APK hash, retained app/DB
identities, restored main chart, food-curve/form navigation, truthful unconfigured
photo state and a foreground service surviving Home/return. No meal, target,
bolus, image or sound was submitted for testing. A fresh public API check returned
HTTP503 `ai_service_not_deployed`; real image inference and end-to-end server
enrollment remain unfinished. Device artifacts remain private and local.

Phone update is `adb install -r` on the verified existing Copilot Redmi only.
No disabling the package, uninstall, data clear, therapy command or test sound.
User declined another database copy. A missing USB device blocks installation.
Rollback uses the prior locally retained APK with matching signature; no
destructive schema rollback is introduced.
