# Local V3 Entries Adapter

## Boundary

Copilot serves authenticated status, lastModified and entries over its existing
loopback TLS listener. SGV and MBG support create, get-by-id, PATCH, soft deletion,
chronological first load, latest SGV and revision history. AAPS source is unchanged.

This is wire persistence, not clinical ingestion. Current glucose sources,
prediction and therapy remain unchanged. Other collections return501 with empty
permissions; only entries advertises crud. RC1 ApiPermissions.isFull() requires
all collections, so the whole-app uploader is NOT admitted by this partial status.
There is no READY transition, device migration or command-delivery claim.

## Contract

- Exact upstream reference: nightscout/AndroidAPS 4.0.0-rc1,
  d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0.
- Entry POST has a bare mutation receipt; PATCH/DELETE wrap it in result.
  Reads and status/lastModified also use result.
- History uses weak ETag `W/"<last-returned-revision>"`, never the collection head
  on a truncated page. Empty history retains its incoming cursor.
- First load uses sort=date, date$gt and limit, finishes equal-time groups, and
  emits no modification ETag. Acquisition time is not an edit watermark.
  Concurrent first load still needs catch-up from a pre-load modification cursor.
- Latest uses sort$desc=date and type=sgv. The bounded scan covers1000 documents
  and1MiB stored bodies. If manual entries hide older SGVs needed for the requested
  page, refuse507 instead of returning a falsely complete page.
- RC1 updateSvg omits date/utcOffset. PATCH merges omitted fields and preserves
  date, type, device and utcOffset. Supplied srvModified is a CAS precondition;
  identical retries are no-ops. Without it, PATCH/DELETE serialize against the
  latest document in one Room transaction.
- Read-only documents cannot change. Tombstones retain time/body, expose
  isValid=false in history and cannot be recreated.
- lastModified uses foods; permissions use singular food.

## Identity And Validation

Explicit identifiers are retained. Otherwise SHA-256 covers local owner, device,
type and acquisition time, never glucose value. Missing producer without explicit
identity is refused, not replaced with server time/random IDs. Conflicting POSTs
return409. This identity rule is only for entries, not meals or insulin.

The parser requires one <=64KiB JSON object, valid UTF-8, bounded depth, no duplicate
keys, known fields, finite unquoted numbers and valid metadata. Conflicting
dateString/date, unknown units/filters, duplicate query parameters and overflowing
limits are refused. Server metadata is checked separately. isValid=false requires
DELETE. No glucose clamping or unit guessing occurs. MBG/calibration fields are
wire evidence only, not applied sensor calibration.

No patient payload or credentials are logged. Responses are no-store with closed
connections. Raw body bytes are validated before replacement decoding can occur.
Capacity failures cannot purge history. Room remains36 with no new migration.

## Stock SDK Test

tools/aaps4_compat/prepare.sh verifies the exact pristine upstream SHA, then builds
the actual JVM SDK with an external test driver. A Gradle init script adds a test
source directory; it does not edit tracked AAPS files. Use Java21 for preparation,
Java17 for Copilot:

```sh
AAPS_RC1_DIR=/absolute/pristine/checkout JAVA_HOME=/path/to/java21 bash tools/aaps4_compat/prepare.sh
cd android-app
AAPS_INTEROP_JAVA_HOME=/path/to/java21 AAPS_INTEROP_CLASSPATH="$PWD/build/aaps4-interop/classpath.txt" JAVA_HOME=/path/to/java17 ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The real SDK uses the Copilot TLS listener/Room store: refresh, status, create and
retry, paging, latest, PATCH without date, delete and lastModified. A temporary
trust store contains the synthetic test CA; certificate/hostname checks remain
enabled. Access credentials are passed only in the child environment, not logs.
The bounded child process and temporary files are cleaned up. CI requires this
test instead of silently skipping an unavailable SDK fixture.

This is not Android CA trust, worker scheduling or pump acceptance. Other typed
collections, clinical projection/source arbitration, storage notifications,
paired signed Client Control, initial-load recovery and retention UX remain gates.
Do not migrate the device or advertise full stock compatibility on this result.
