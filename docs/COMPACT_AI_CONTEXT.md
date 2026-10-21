# Pure Compact AI Context

Status: locally implemented first stage of the approved compact-context design.
The pure module remains free of storage/inference. A separate trusted opt-in
archive/CHAT adapter and encrypted receipt are in COMPACT_CHAT_ADAPTER.md; no
installed host factory enables them by default.
It does not change uploads, raw history, model settings, therapy or a running host.

## Contract

`app.ai.compact_context.prepare_compact_context` accepts already selected unique
latest `HistoryRecord` glucose versions, optional sensor-change records,
nonoverlapping `AnalysisRange` intervals, an explicit UTC-millisecond window,
timezone, as-of and snapshot sequence. The caller owns authentication, canonical
revision/tombstone selection, expiry and coverage verification. A supplied
source-binding hash is not authentication. Future/incompatible/malformed records
and duplicate identities fail closed; deletion records cannot supply glucose.

The returned frozen `PreparedCompactContext` has canonical UTF-8 `input_body`,
its SHA-256 `input_digest`, raw-input/settings `source_digest` and separate
canonical `manifest_body`. The manifest records actual resolutions, source and
frame byte counts, section hashes, cutoff/highwater and omitted chat count.
Its frame digest is external, avoiding a self-referential hash. These bytes are
immutable; this stage does not persist a per-answer receipt or bind a result.

`profile_body`, `events_body` and `facts_body` are caller-prepared canonical JSON
object/array bytes. They remain exact, mandatory, untrusted observations.
Missing sections stay explicitly unavailable; this is not a complete typed
profile exporter or automatic compactor for insulin, meals or activity. Repeated
IOB/COB states are not summed or reinterpreted as doses/carbohydrate intake.
The complete admitted question is unchanged. Older assistant turns are removed
one at a time before reducing background detail; older user turns follow.

## Glucose Semantics

Shared daily-glucose preparation supplies source/quality/context/sensor-epoch
cohorts, timestamp aliases/conflicts and continuity. The original eligible
segments, not rounded bins, supply time-weighted metrics and out-of-range
episodes under supplied historical ranges. Unknown ranges do not acquire TIR.
No unsupported gap, suspect point, source boundary or sensor change is bridged.

Each series declares its UTC start/end, width and cohort. Seven columns are
`n, covered_s, first_q, last_q, mean_q, min_q, max_q`. Value scale is0.01mmol/L.
The count is unique usable observed timestamps, not raw aliases. Mean is the
supported time-weighted mean; first/last/min/max describe observed values only.
A parallel pair of exact millisecond offsets retains the observed extrema times.
Empty observed fields are null, including bins that only have supported segments
crossing them. Edge bins are clipped to actual elapsed duration, including DST.

Ordinary resolution is10minutes. If the packet cannot fit, background older than
72hours becomes30, then60minutes. Recent72hours remain10minutes; explicitly
selected `FocusWindow` intervals retain1/5/10minutes. Every segment exposes its
actual width. Repeated chat candidates reuse each projection within one call;
there is no cross-request cache of personal data.

Conflicting/suspect observations, sensor changes and unsupported interval evidence
remain explicit. Episodes are not silently truncated. Capacity saturation refuses
the context instead of claiming a successful complete analysis.

## Bounds And Errors

- Normal canonical packet ceiling96KiB; existing native128KiB ceiling unchanged.
- Question1..4096characters, at most8192UTF-8 bytes; no truncation to fit.
- Up to100,000 input records and64MiB canonical input-record bytes.
- At most15 elapsed days, allowing14 local days across timezone transitions.
- At most256 analytical ranges/sensor changes,16 focus windows,8192 grid cells.
- Up to256 unusable timestamps, combined exceptions and episodes per collection.
- Each supplied section at most64KiB; chat at most64 turns and128KiB text total.
- Canonical JSON rejects duplicate keys, nonfinite numbers and malformed UTF-8.

Errors are fixed codes without payload text: `compact_context_invalid_input`,
`compact_context_projection_capacity`, `compact_context_budget_exceeded` or
`compact_token_accounting_failed`. Mandatory data too large for any layout is
not a successful partial view and never triggers inference or automatic replay.

Optional `TokenBudget` accepts a trusted synchronous counter and reserves tokens
for system/native history/output. The caller must account for all framing and
use the admitted model's accounting. Without that evidence the manifest says
`UNVERIFIED` when no counter is present; a supplied counter says
`COUNTED_BY_SUPPLIED_COUNTER`, never model verification. The manifest records
window/reserve/total/remainder and optional model/instruction binding. CHAT
requires that binding and counts the instructions within the reserve; see
COMPACT_TOKEN_BUDGET.md. A byte-fitting frame and synthetic test counters do not
prove token-window fit or measured token/cost savings.

## Verification

Actual pure-projector output on synthetic minute inputs with values6.0..6.9,
empty nonglucose sections and the same short question:

| Days | Input Records | Frame Bytes | Resolutions |
| --- | ---: | ---: | --- |
| 3 | 4,321 | 19,781 | 10minutes |
| 7 | 10,081 | 43,962 | 10minutes |
| 14 | 20,161 | 86,314 | 10minutes |
| 14, two sequential sources and old30-minute focus | 20,161 | 89,048 | 1/10/30minutes |

The last row preserves the requested old focus at one minute. Source boundaries
reduce supported coverage rather than merging sources. Counts include the final
right-edge guard. These are complete projector frames, not real device results,
full-profile production packets, clinical accuracy evidence or token benchmarks.

Focused regressions compare raw metrics with the established daily calculator,
preserve interior one-minute extrema and gaps, test unknown ranges, conflicts,
sensor boundaries, aliases, DST, immutable deterministic digests, mandatory
sections, UTF-8, token failures, resource saturation and bounded recomputation.

```sh
PYTHONPATH=backend uv run --no-project --python 3.12 \
  --with-requirements backend/requirements.txt \
  python -m pytest -q backend/tests
```

The full backend passes1840 tests with27 skips on the CI-matching Python3.12.
Scoped E/F lint, formatting and Python compilation pass. Two runs on Python3.13
instead exposed EPERM in the existing process-group teardown test. That unrelated
adapter was not changed; its30 cases pass on Python3.12. Retain this as a residual
environment finding rather than claiming it was fixed by context compaction.

## Remaining Integration

The opt-in adapter performs bounded selected-version reads and receipt persistence.
Actual model token/framing admission, automatic question-specific focus and full
nonglucose/profile coverage remain separately required before host activation.
Cancellation/unknown-outcome handling and native framing/echo/independent-stop
checks remain required before CHAT integration and deployment. No native thread
persistence, complete profile export, nightly model activation or live clinical
authority follows from this pure stage. See AI_DEVICE_CONTEXT.md.
