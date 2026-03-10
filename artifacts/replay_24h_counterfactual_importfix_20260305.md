# Counterfactual replay 24h (engine recompute) — IOB/UAM/CI

- Generated at: 2026-03-06 18:53:59
- Window: 1772616120665 .. 1772702520665 (24h)
- Before DB: `/Users/mac/Andoidaps/tmp_live/copilot_before_importfix_20260305.db`
- After DB: `/Users/mac/Andoidaps/tmp_live/copilot_post_install_20260305.db`

## Therapy events in window
- Before: carbs=14, correction_bolus=0, temp_target=254
- After: carbs=15, correction_bolus=11, temp_target=254

## Before
- 5m: n=289, MAE=0.365, RMSE=0.498, MARD=5.708%, Bias=-0.021
  - IOB: score=0.015, corr=-0.007, uplift=7.596%, n=289
  - UAM: score=0.209, corr=-0.1, uplift=-7.012%, n=49
  - CI: score=0.979, corr=0.266, uplift=38.371%, n=289
- 30m: n=286, MAE=1.567, RMSE=1.975, MARD=24.725%, Bias=-0.166
  - IOB: score=0.151, corr=-0.069, uplift=-7.909%, n=286
  - UAM: score=0.182, corr=-0.104, uplift=-4.776%, n=49
  - CI: score=0.081, corr=-0.041, uplift=-6.096%, n=286
- 60m: n=280, MAE=3.063, RMSE=3.705, MARD=48.489%, Bias=-0.464
  - IOB: score=0.063, corr=-0.029, uplift=-7.944%, n=280
  - UAM: score=0.188, corr=0.066, uplift=15.976%, n=49
  - CI: score=0.616, corr=-0.21, uplift=-17.742%, n=280

## After
- 5m: n=289, MAE=0.365, RMSE=0.498, MARD=5.704%, Bias=-0.021
  - IOB: score=0.014, corr=-0.007, uplift=7.794%, n=289
  - UAM: score=0.209, corr=-0.1, uplift=-7.012%, n=49
  - CI: score=0.97, corr=0.266, uplift=37.174%, n=289
- 30m: n=289, MAE=1.582, RMSE=1.992, MARD=24.779%, Bias=-0.196
  - IOB: score=0.149, corr=-0.071, uplift=-7.117%, n=289
  - UAM: score=0.182, corr=-0.104, uplift=-4.776%, n=49
  - CI: score=0.069, corr=-0.04, uplift=-4.584%, n=289
- 60m: n=286, MAE=3.141, RMSE=3.796, MARD=48.893%, Bias=-0.597
  - IOB: score=0.027, corr=-0.017, uplift=-3.822%, n=286
  - UAM: score=0.188, corr=0.066, uplift=15.976%, n=49
  - CI: score=0.732, corr=-0.236, uplift=-21.302%, n=286

## Delta (After - Before)
- 5m: ΔMAE=0.0, ΔMARD=-0.004 pp, ΔBias=+0.0, Δn=0
  - IOB: Δscore=0.0, Δcorr=+0.0, Δuplift=+0.198%
  - UAM: Δscore=+0.0, Δcorr=+0.0, Δuplift=+0.0%
  - CI: Δscore=-0.009, Δcorr=0.0, Δuplift=-1.197%
- 30m: ΔMAE=+0.015, ΔMARD=+0.054 pp, ΔBias=-0.03, Δn=3
  - IOB: Δscore=-0.002, Δcorr=-0.002, Δuplift=+0.792%
  - UAM: Δscore=+0.0, Δcorr=+0.0, Δuplift=+0.0%
  - CI: Δscore=-0.011, Δcorr=+0.001, Δuplift=+1.512%
- 60m: ΔMAE=+0.079, ΔMARD=+0.403 pp, ΔBias=-0.133, Δn=6
  - IOB: Δscore=-0.036, Δcorr=+0.011, Δuplift=+4.123%
  - UAM: Δscore=+0.0, Δcorr=+0.0, Δuplift=+0.0%
  - CI: Δscore=+0.116, Δcorr=-0.026, Δuplift=-3.56%

_Counterfactual replay note: forecasts were recomputed offline with HybridPredictionEngine from glucose+therapy history on each 5m cycle._