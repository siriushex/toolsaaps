# Meal icon assets

Selected direction: middle column of `variants.png`, flat food pictograms.
Generated on 2026-09-27 with the image-generation tool using that board as a
reference. The final transparent 1536x1024 source is `icon-sheet.png`.
Source SHA-256: `cb84a34588aac031dbd574006854c51b957a5b9277be1a80f2ce10ff47e82885`.

Layout, left to right:
- Top: small, medium, large porridge portions; large includes two bread slices.
- Bottom: juice/sugar, mixed meal, salmon/avocado/cheese.

`prepare-icons.swift` extracts six 512px cells, trims transparent margins,
centers each visible image with padding, and downsamples to 192x192 PNG with
alpha. Runtime assets are in `android-app/app/src/main/res/drawable-nodpi/`
with `meal_portion_*` and `meal_profile_*` names. Compose displays them at 64dp.
No runtime cropping, network image fetching, or image generation is needed.

Reproduce from the repository root on macOS:

```sh
rtk swift docs/superpowers/specs/assets/2026-09-27-meal-portions/prepare-icons.swift docs/superpowers/specs/assets/2026-09-27-meal-portions/icon-sheet.png android-app/app/src/main/res/drawable-nodpi
```

Images are category illustrations, not measurements of the displayed food.
Actual grams come from saved portion settings and explicit confirmation.
