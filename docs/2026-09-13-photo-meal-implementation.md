# Photo Meal Implementation

## Scope and Baseline

The approved photo meal plan is being implemented incrementally on
`codex/server-ai-jobs-20260913`, starting from `b2f613fa`.
Existing therapy settings, calibration, UAM safety rules and AAPS delivery
remain unchanged in the domain-contract stage.

The approved plan is
`docs/superpowers/plans/2026-09-08-photo-meal-nutrition-profile.md`
in the `integrated-runtime-20260811` worktree. This document records the
implementation status on the current branch, not a replacement product design.

Confirmed source baseline on 2026-09-13:

- Room is version 26. Existing meal overrides retain absorption selection and
  manually entered energy, not a complete macronutrient record.
- Overview already has carbohydrate, calorie, profile and Eating Soon inputs.
- `ManualMealSubmission` remains the only manual meal delivery path. A timeout
  is not proof that AAPS did not receive a command.
- Server AI jobs currently have a closed text-only CHAT contract. They are not
  a meal-photo endpoint and must not accept images through an implicit fallback.
- `HybridPredictionEngine` already produces announced carbohydrate contribution
  at five-minute steps. The future yellow line must use the accepted calculation
  revision, not recompute a different curve from rounded UI ISF/CR values.

## Implementation Sequence

1. **Completed: local nutrition contract.** Explicit quantities, nutrient
   provenance, reference portions, unknown values, editable revisions and
   confirmed snapshots; deterministic calculation and validation; no actions.
2. **Partially completed: bounded photo preparation, typed recognition,
   foreground coordinator and draft boundary.** The strict typed parser, bounded
   image preparation, one-shot coordinator with an injected gateway and pure
   editor for explicit mass/profile/preparation confirmation are implemented and
   tested.
   The coordinator is deliberately foreground-only, deduplicates successful
   request IDs, rejects concurrent different requests and never writes therapy.
   Camera/picker wiring, the real server photo job or personal route remain.
   Server vision requires a separately validated route and contained online
   executor; the current text-only server contract is not used as an implicit
   image fallback.
3. **Partially completed: editable food confirmation boundary.** The pure editor
   validates ingredients, grams, portion eaten, food state and profile while
   retaining estimate provenance. The Compose sheet, catalog lookup, time field,
   clear estimate status and final confirmation UI remain pending. Catalog values
   require a verified identifier/version; AI cannot certify them.
4. **Pending: durable nutrition records and reconciliation.** Revalidate the
   current Room version before creating a non-destructive migration. Persist
   confirmation before delivery, then link canonical therapy identity/revision.
   Support local-only zero-carbohydrate food without an AAPS command.
5. **Partially completed: shared food effect timeline boundary.** An immutable
   builder now consumes the accepted `announcedCarbStep` from the prediction
   runtime, carries as-of/generation/ISF/CR/curve revisions and rejects unknown
   coverage. Runtime wiring must still preserve legacy behavior, rescue
   carbohydrates and reconciliation.
6. **Pending: yellow chart layer.** The future layer will render the timeline's
   expected announced-food contribution over the next 30 minutes in delta
   mmol/L, with its own zero-based scale. This is not an absolute glucose
   forecast. Incomplete future coverage is not zero.
7. **Pending: COB/UAM reconciliation and reports.** Do not add duplicate meal
   carbohydrates or infer protein, fat and calories from UAM. Show nutrition
   completeness and provenance explicitly in local and AI reports.
8. **Pending: integration and release checks.** Full tests, lint, APK, migration
   on a database copy, signatures, photo/UI resource checks and real-device
   validation. No fabricated meals, insulin or targets on the user's AAPS.

## Nutrition Decisions

- Unknown nutrients remain null; a known zero stays zero. Partial subtotals do
  not become complete totals just because some ingredients have values.
- Source portions are explicit: per 100 g or per serving with a gram weight.
  Raw/cooked/unknown states do not cause an implicit yield conversion.
- Carbohydrate basis is explicit: total, available or unknown. Mixed bases do
  not silently become therapeutic grams. Fiber and polyols are not deducted
  twice; the user confirms therapeutic carbohydrate grams separately.
- Keep source energy in kcal or kJ with an explicit unit conversion. Do not
  replace label/catalog energy with an unlabelled 4/9/4 estimate.
- Keep the original AI estimate and isolate mutable editor lists from confirmed
  snapshots. AI provenance cannot be promoted to verified catalog provenance.
- Validation distinguishes physical mass feasibility from analytically measured
  fractions. Independent nutrient measurements need not sum exactly.

These source distinctions follow the
[USDA Foundation Foods documentation](https://fdc.nal.usda.gov/Foundation_Foods_Documentation/).
They are data-contract decisions, not a claim that a photo determines nutrients
or glucose effects precisely.

## Verification Record

Stage-specific evidence is kept outside the repository in
`/Users/mac/Andoidaps/artifacts/photo-meal-nutrition-20260913-pf8cSR`.
Focused evidence currently includes the successful Android unit test run for the
nutrition domain, strict photo parser, image preparation, foreground coordinator,
draft editor and accepted food-effect timeline builder. Independent
requirement/quality reviews and full build results are still required before
marking the feature or release complete.

The previous phone update verified package replacement, signature/hash and cold
start. Its immediate empty-state screenshot does not establish that the local
glucose database is empty. Sustained data-flow and real photo recognition have
not been verified by that installation evidence.
