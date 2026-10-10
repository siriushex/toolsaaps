# Compact meal portions: selected visual direction

## Decision

On 2026-09-27 the user selected **variant 2, clear food pictograms**.
Use the middle column of [the revised board](assets/2026-09-27-meal-portions/variants.png).
The large portion contains more porridge plus **two bread slices**, as explicitly requested after the initial review. The earlier same-food-only rule is superseded for this icon. Pictures represent categories, not measured servings.

Selection approves the visual direction. It is not evidence of completed implementation, validated history learning or changed therapy limits.

## Default dialog

- Title: Еда; close icon.
- Row 1: Маленькая / Средняя / Большая, picture radio buttons.
- Row 2: Быстрые / Смешанные / Жиры и белки, picture radio buttons.
- Eating soon checkbox, preserving the existing behavior and confirmation.
- One explicit action displaying proposed grams: Добавить ≈N г.
- No permanent numeric input, calorie field, duration descriptions or explanations.
- Taps update a draft only. Retain the existing final confirmation and an accessible correction path there. Closing, cancelling or selecting a different image must not send carbs or a target.

Use stable three-column tracks, equal image frames, labels below, selection border plus checkmark. Minimum 48dp touch targets, opaque theme-aware surfaces, readable contrast and enlarged-font layout. Images must remain distinct at 48/64dp. No animated food assets.

## Settings and history

Requested ranges: 7-15g / 15-40g / 40-80g. Configure range and default amount per portion; no silent maximum selection. Calorie visibility is a separate option, off by default. Hidden energy is null, not zero.

Local Copilot suggestions may use independently confirmed comparable meals and time of day after five or more usable days; elapsed days alone do not prove adequate support. Synthetic UAM and accepted model guesses are not independent training labels. Glucose, ISF/CR and insulin sensitivity affect response/absorption, not the physical carbohydrate amount. Keep these models separate.

Freeze amount/profile/settings revision when entering confirmation. AAPS receives exactly the confirmed quantity through the existing manual writer, not an AI command. Do not add a background polling loop.

## Remaining engineering decisions

- Existing manual UI and sendManualCarbs cap at `carbComputationMaxGrams.coerceIn(20.0, 60.0)`. Validate a separate manual-meal cap before supporting 80g. Do not change UAM caps or silently clamp/split an 80g meal.
- The suggested five-day learner requires offline evaluation and provenance before activation. Do not advertise it as already learning from the installed database.
- Existing Eating soon therapy behavior and safety checks are outside this visual change.

## Evidence

Design review and full history-model proposal: `/Users/mac/Andoidaps/artifacts/meal-portion-design-20260927/DESIGN.md`.
ChatGPT reviewed generic UX requirements only, with no patient records: https://chatgpt.com/c/6ab8366d-5938-83eb-b6e0-9b4f836d0d0c

## Acceptance

Six understandable image choices, one checkbox, clear confirmation amount, no white-on-white or black-on-dark labels, no therapy send on selection/cancellation, exact amount preserved through confirmation, unchanged UAM behavior, no invented calorie value, and verified phone screenshots after an eventual build/update.
