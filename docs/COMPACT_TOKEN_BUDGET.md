# Compact Context Budget Binding

Source-only hardening of the optional compact CHAT adapter. No installed host,
model, provider request, raw upload, phone, APK or therapy changes.

## Findings

The former manifest said `VERIFIED_BY_SUPPLIED_COUNTER` even when the injected
counter was only `len(bytes)`. It admitted zero for a nonempty frame and did not
record the window/reserve or bind accounting configuration to the accepted model
and instructions. These are accounting-contract defects, not evidence that a
live model exceeded its window: this adapter is not the default host path.

The locally pinned upstream source at commit
`3d2ee51ca2d5db578f328aa75e20aa22c0197c9a` explicitly describes its history token
estimate as a coarse byte-based lower bound, not tokenizer-accurate counting.
Its model catalog distinguishes configured context from maximum override and
does not identify the selected model's tokenizer. Neither the maximum override
nor that byte heuristic is admitted here as an actual model budget.

## Current Contract

- `TokenBudget` keeps an injected trusted synchronous counter. No dependency,
  network lookup, guessed tokenizer mapping or bytes-to-token ratio is added.
- Generic pure projection may be unbound. CHAT requires both exact model and
  SHA-256 of the accepted policy's UTF-8 instructions before archive access.
- The same counter measures these instructions once. The configured reserve
  must exceed that count, leaving space for native framing, reasoning and output.
  A positive remainder alone does not prove that this space is sufficient.
- Each nonempty counted input must yield an integer in 1..10,000,000. Zero,
  bool, noninteger, out-of-range values and counter exceptions fail with a fixed
  diagnostic. Neither prompt text nor exception text appears in errors.
- All frame candidates retain the existing constraint:
  `frame_count + reserved_tokens <= window_tokens`. Instructions are inside the
  reserve, not added a second time. Counter failures cannot persist a receipt.
- The manifest now says `COUNTED_BY_SUPPLIED_COUNTER`, not verified. The additive
  `tokenAccountingRevision=supplied-counter-v2` records window, reserve, total,
  remaining capacity, model and instruction digest. `inputTokens` counts the
  canonical frame only. No counter remains `UNVERIFIED` with no budget claim.
- The encrypted receipt records counted instructions and remaining reserve,
  explicitly marking native framing/reasoning/output as not independently
  verified. Existing stored receipts are readable unchanged, not relabelled.

Fixed new adapter diagnostics are `compact_chat_budget_binding` and
`compact_chat_instruction_budget`; counter failures remain
`compact_token_accounting_failed`. No public API exposes a caller-selectable
counter, model, instructions digest or budget.

## Verification And Remaining Admission

Synthetic regressions cover false verification, zero/invalid counts, missing and
mismatched binding before archive access, exhausted instruction reserve, exact
instruction/frame counting, redacted failures and transactional receipt rollback.
The final full backend passes 1907 cases with 27 existing skips and one dependency
deprecation warning. Scoped Ruff E4/E7/E9/F, formatting, compilation and diff checks
pass on all six changed Python files. No Android source changed in this stage.

The injected callable and its revision are still trusted dependencies, not proof
of correctness. Operational activation still needs authoritative model-window
and tokenizer evidence, bounded counter execution, native framing/output reserve
validation and actual protected host acceptance. No measured token savings,
provider latency, live window fit or clinical validity is claimed by these tests.
