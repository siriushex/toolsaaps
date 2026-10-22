# Meal Basal Accounting

## Scope
Continue PR18 after c108942b passed all six push/PR checks. Add research-only
accounting of historical basal delivery with the existing insulin kernel.
Do not infer delivered basal from a command, net scalar IOB, or current schedule.
Do not change production forecasts, onset presentation, targets or doses.

## Design
- Explicit immutable intervals contain observed delivered rate and contemporaneous
  scheduled rate, source/interval revisions and insulin-kernel identity. Rates are
  U/hour, intervals are half-open, and suspension is zero delivered rate.
- Require contiguous nonoverlapping coverage through the frozen prediction anchor
  and the entire actual kernel support, including onset shift and duration scaling.
  Reject gaps, duplicates, future/late evidence, incompatible kernels and budgets.
- Integrate the existing piecewise-linear cumulative kernel exactly at its knots;
  do not replace continuous basal with a bolus at an interval endpoint.
- Preserve actual, scheduled, positive-deviation and negative-deviation components
  and their remaining tails. Net cancellation cannot erase positive exposure.
- Compare exactly aligned basal/net components only when their declared reference
  kernel matches. Numeric agreement is not upstream completeness or a safety pass.
- Expose the result in the existing on-demand assessment. Do not inject historical
  basal as hypothetical future insulin: observed trend already contains unknown
  basal effects. Historical residual refitting is a separate required stage.

## Checks
- [x] Failing-first projection/integration and zero-net physical-tail regressions;
      final focused suite99passed, zero failures/errors/skips.
- [x] Frozen full Android quality in6m37s:6694passed/six existing skips, no
      failures/errors. Real pristine SDK TLS2.749s; lint zero errors and382 existing
      warnings/eight hints. APK49,222,537bytes; all7144 app classes retained.
- [ ] Reviewed source/tests/docs commit on PR18; exact-head Verify and JUnit receipt.

## Remaining Release Gates
No live recommended meal time is admitted by this stage. Full causal delivery and
profile ingestion, historical residual accounting, future-controller effects,
simultaneous uncertainty and prospective personal-forecast validation remain
required. A research APK alone is not a reason to replace the published build.
