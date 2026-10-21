# Combined UI And Food Photo Release

User selected server ChatGPT login for food-photo estimation on2026-10-15.
Integrate PR6 (7f1aafc2) and its PR5 artwork with the current b32f7f3d owner-AI
and Room-history implementation, not an APK downgrade to the older UI branch.
No main merge, therapy command or automatic alert-setting change is implied.

## Work Boundaries

- Controller: isolated UI merge, signed IMAGE/MEAL_ESTIMATE client, camera/picker
  and explicit food-only estimate review, truthful readiness, shared docs/gates.
- Capacity worker: history_store/context and synthetic regressions only. Preserve
  encryption, exact coverage, original retention, isolation and5s/50M budgets.
- Vision worker: separate contained MEAL_PHOTO worker/protocol and synthetic
  checks. No clinical context, CHAT tunnel, personal-key fallback or real call.

Photo estimates retain nutrient ranges and unknowns. A selected image is not a
confirmed meal, mass, laboratory GI or insulin/target instruction. Review must
require explicit quantity and carbohydrate confirmation before the existing meal
submission path can be used. Opening/reviewing a photo never sends therapy.
Runtime capabilities must advertise actually admitted IMAGE/MEAL_ESTIMATE support;
text readiness or an enabled automatic-analysis switch is insufficient.

Protected server deployment/enrollment and a real model invocation require an
exact admitted runtime/image/network/profile lifecycle, not a synthetic test.
Keep that limitation visible rather than using fake output or a silent fallback.

## Verification

1. New photo concurrency/body-binding, signed route/capability/receipt limits,
   invalid-image, no-CHAT-fallback, cancellation and explicit-review tests.
2. Native motion/resource/gesture and combined Android full test/lint/APK gates.
3. Backend focused/full synthetic contracts and year-scale reader load probe.
4. Review exact source diff, publish feature-only, inspect exact-head CI.
5. Verify correct Redmi/signature; in-place install without disable/clear-data,
   inspect actual UI/data/service/errors/resources. No test food/target/sound.
