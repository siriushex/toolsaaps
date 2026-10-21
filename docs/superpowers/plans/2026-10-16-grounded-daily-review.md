# Grounded Daily Review Contract

Source-only next increment of the approved daily-review/admin design. No actual
model dispatch, public route, server/profile change, scheduling, phone or therapy.

## Implementation

- Protected HistoryStore preparation reads one authenticated selected report,
  requiring an explicit matching current source digest. It does not rescan history
  or claim that the supplied digest remains current forever.
- Prepare deterministic canonical input capped at 64 KiB, with at most six
  ranked candidate patterns and six current episodes/cohorts. Selected evidence,
  baselines, unknown quality and calculator/projection omissions remain explicit.
- Bind fixed prompt/schema revision, report/device source binding, complete facts
  digest and exact input digest. No client prompt/model/tools/action configuration.
- Include the exact closed result schema and an echoable digest in the canonical
  outer envelope. Bound each selected candidate to six recent eligible evidence
  windows; preserve full original denominators/metrics and explicit omissions.
  Compact baseline reason counts retain missing dates and archive-only detail
  counts without reweighting any clinical metric.
- Fixed GLUCOSE_ONLY scope discloses unimplemented meal, insulin, controller,
  alarm and physiological context rather than pretending it was analyzed.
- Closed 16 KiB answer: at most five unique known candidate/action selections,
  supplied alternative codes, exact facts/input digests and all required missing
  input codes. Facts dictate the status; the top low-exposure candidate cannot
  be hidden by a high-exposure-only response.
- Reject duplicate JSON keys, invalid scalar types, unknown/action fields,
  oversized/control/markup/link/numerical text and stale/expired source bindings.
  The no-numeral check is not a complete semantic numerical/clinical safety test.
- Render evidence/counts/times/units from frozen facts, never model arithmetic.
  Free text remains explicitly unreviewed AI interpretation. Result status is
  REFERENCES_ONLY/NOT_ADMITTED, never medical validation or model-settled proof.
- Matching source hashes are caller assertions, not autonomous freshness proofs.
  Input/view explicitly say NOT_INDEPENDENTLY_VERIFIED. Real worker admission
  still needs independent frozen-source verification, not a stored-hash echo.
- Do not register this contract with generic CHAT, a public enum, executor,
  supervisor or admission policy. Those are independently tested later stages.

## Checks

- [x] Observe missing contract regression fail before implementation.
- [x] Implement bounded preparation, closed answer validation and grounded view.
- [x] Test missing/partial/stale data, priority, injection, isolation and limits.
- [x] Run full pinned backend suite, scoped lint/compilation and source review.
- [ ] Publish explicit reviewed paths and inspect exact-head CI receipts.
