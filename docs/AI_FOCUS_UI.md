# Approved Focus AI Workspace

The owner selected variant1 Focus: report first, four persistent top tabs, existing
global app navigation unchanged. Native Compose, not a WebView/mock provider.

## Views

- Review uses the existing ClinicalReportUiState and original consent, exact
  disclosure, guarded unknown-outcome retry, cancellation and PDF ticket/share
  actions. One selected24h/7d/30d summary replaces repeated expanded summaries.
  Mean, in-range and variability values retain their existing calculated source.
  Full totals/quality/meal-window details remain reachable from that summary.
- There is no raw glucose day-series in this UI state. The compact chart depicts
  existing below/in/above-range percentages only when finite and consistent;
  no synthetic/interpolated glucose curve or substituted temporary target.
- Chat has its own lazy supplied-message list and a pinned composer. Draft and
  attachments remain owned by the existing state/callbacks. Blank/busy send is
  disabled; discussing a finding appends to an existing draft and never sends.
  Photo/file/recording/voice actions retain their existing channel implementation
  and gates; this UI does not activate image recognition or grant upload access.
- Recommendations preserve their source/period and review meaning. No new
  automatic target/dose/ISF or therapeutic action is offered.
- Archive searches supplied records and opens the complete selected report,
  including observations, recommendations, source, time, status and any error.
  It does not invent a saved PDF or imply historical data is current.
  The newest supplied successful, nonempty, error-free saved review is previewed
  directly on opening Reports; search still covers all supplied records and
  failed runs remain inspectable. Failed/inconsistent results cannot feed the
  recommendations panel. A success string is not medical validation or proof
  that the new protected daily-review service is active.
  Discussing advice preserves its displayed source/date; discussing a report
  preserves its summary, observations, recommendations and status in an editable
  draft. Raw diagnostic errors remain in details and are not copied into chat.
  Neither action uploads or sends that draft.

Only the active panel is composed. SaveableStateHolder retains independent scroll
and search/period state across tab visits; draft remains in the existing model.
The daily-analytics load/error state cannot cover a usable chat or local report.
Diagnostics retains all prior cloud job, filter, replay, tuning, CSV and PDF
tools. Opening a tab or diagnostics does not initiate analysis, upload or refresh.

## Interaction And Accessibility

Tab changes are immediate; no outgoing invisible page can intercept touches.
The existing native owl/press motion is finite and uses the app lifecycle,
power-saving and reduced-motion policy. Busy semantics remain indeterminate,
not a fictitious completion percentage. Historical report values do not recolor
the owl as though they were live CGM. The main menu owl remains unchanged.

Icon actions have accessible labels/tooltips and48dp targets. Long reports and
large font can scroll without clipping. Compact headings belong to panels,
not hero typography. Repeated chat messages are the only framed items; the
workspace/panel structure is not a stack of nested cards.

Clinical PDF picker/ticket effects remain above the tab composition lifetime.
A confirmed-send UI latch must also survive tab disposal until source state
invalidates it; switching away must not enable a second submission. Pending
consent itself is not silently persisted as approval across process death.

## Verification Boundary

Native Robolectric semantics and raster tests cover navigation, draft/send,
daily-load isolation, archive/search, period/details, small/large-font layout and
clinical action guards. Full Android unit/lint/compile/assemble and instrumented
test-source compilation are separate gates. Synthetic screenshots are not actual
Redmi acceptance or evidence of real provider/model correctness. A built APK or
published source does not prove installation, enrollment or completed analysis.

No server, medical data, credentials or therapy configuration changes accompany
this UI stage. Existing backend daily result/schedule and photo admission remain
separate unfinished work, not enabled by the new navigation.
