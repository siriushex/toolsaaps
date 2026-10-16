# Grounded Daily Review Contract

Source-only preparation, strict answer checking and an evidence-linked view.
No model, provider route, scheduler, report-result persistence, public endpoint,
server deployment, phone or therapy action is enabled.

## Trusted Entry

HistoryStore.prepare_daily_glucose_review reads a selected authenticated archive
revision using the existing owner/device boundary. The caller supplies a matching
source digest; NOT_CHECKED, CHANGED, missing, expired or inconsistent reports
cannot prepare work. The pure prepare_review helper accepts trusted archive
objects, not arbitrary client/model JSON or a new ingestion authority.

The matching digest is only a caller-supplied binding assertion. Neither helper
independently discovers new uploads or certifies continued freshness. Source
digests include reproducibility cuts; simply recalculating with another as-of
time is not a standalone content-freshness algorithm. A real worker still needs
independent frozen-source membership/revision verification before dispatch and
publication. Passing the stored hash back is not that verification. Input and
output explicitly say NOT_INDEPENDENTLY_VERIFIED; no autonomous fresh badge.

The separate internal check_daily_glucose_report_source now performs a protected
point-in-time replay of retained history without a supplied hash. It is not yet
consumed here or by a worker; old input/view labels deliberately remain unchanged.
See [DAILY_SOURCE_VERIFICATION.md](DAILY_SOURCE_VERIFICATION.md).

## Frozen Model Input

DailyReviewWork holds immutable canonical bytes and fixed prompt/schema/digest
bindings. input_body contains an envelope with input, packetDigest and the exact
closed resultSchema. The digest hashes canonical input, which itself binds the
result-schema digest. The checker verifies the entire canonical envelope and
exact source schema, not a client-supplied replacement. No self-referential hash
is required, and the model actually receives the digest/schema it must echo/use.
Model/reasoning configuration and tool/action switches cannot appear in input.

- Limit the complete envelope to 64 KiB, with at most six ranked candidates,
  six cohorts and six current episodes. Priority matches deterministic low-first
  exposure ranking. Missing and omitted evidence is not normal glucose.
- Every selected pattern includes up to six most recent eligible windows,
  including its current-day window. This does not select only positive windows.
  Full qualifying-day and eligible-window counts remain original facts. Each
  projected pattern and the packet declare omitted window counts; the aggregate
  counts windows omitted inside selected candidates, not all omitted patterns.
- Preserve baseline metrics/deltas, eligible counts and exact missing dates.
  Aggregate exclusion reasons by code/count; keep original day-detail count
  explicitly archive-only. No baseline metric is recomputed or reweighted.
- Retain analytical ranges, quality-separated coverage, missing time and source/
  context limitations. Do not merge source cohorts or replace range with a
  temporary target. Complete original facts/references remain in the archive.
- Scope is GLUCOSE_ONLY/DEVICE_DECLARED, completeness UNKNOWN. Meals, insulin,
  controller decisions, alarms and physiological context are explicitly not
  analyzed. No raw notes, logs, clinical profile or credentials enter this packet.
- Selection omissions, unavailable comparisons or current-context limitations
  force LIMITED_DATA. Otherwise existing candidates require FINDINGS; absence
  of deterministic candidates with supported context is NO_SUPPORTED_PATTERN,
  never a clinical normal-day certificate. The highest-ranked candidate must be
  acknowledged, so a high-only answer cannot hide the top low exposure.

## Closed Answer And View

validate_review accepts at most 16 KiB of strict UTF-8 JSON. Reject duplicate
keys, invalid/nonfinite types, extra/action fields, wrong facts/packet hashes,
unknown or duplicate candidates/actions/alternatives, hidden/invented missing
inputs, wrong status, expired work or an explicitly changed source assertion.
At most five findings may select only supplied IDs and review actions. All
required missing-input codes must remain visible.

The summary and interpretation fields are bounded single-line plain text. Reject
Unicode numerals, controls, markup/code delimiters and explicit link forms. This
is not complete semantic detection of numerical claims or unsafe medical prose;
spelled-out claims and unsupported clinical conclusions cannot be certified by
a schema/character check. Schema and reference success are not clinical safety.

GroundedDailyReview obtains counts, units, times, evidence and status from the
frozen projection, not model arithmetic. It always declares REFERENCES_ONLY,
UNREVIEWED_AI_INTERPRETATION and NOT_ADMITTED. It is neither provider settled-
success evidence nor authority for publication, therapy or the existing CHAT
path. There is no mapper from its review action to a treatment command.

## Separate Remaining Gates

- Bounded missing-domain adapters and consumption/rechecking of the independent
  frozen-source result, without treating an older MATCHED object as current.
- Dedicated DAILY_REVIEW policy/transport admission using the trusted model and
  reasoning, empty tools/actions, shared profile ownership and exact stop/IO.
- Encrypted work/results, durable opt-in logical-day reservation, restart/
  uncertain-outcome reconciliation and interactive-first scheduling. No replay
  after possible model submission, and no dispatch caused by UI navigation.
- Actual synthetic model quality/safety evaluation and reviewed publication
  admission, then protected device/admin projections and real acceptance.

Tests use synthetic facts and synthetic replies only. No token/cost-saving,
medical-accuracy, live-provider, host-capacity or phone claim follows from them.
