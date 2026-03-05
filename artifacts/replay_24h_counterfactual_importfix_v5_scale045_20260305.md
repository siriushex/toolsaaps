# Counterfactual replay 24h (engine recompute) — IOB/UAM/CI

- Generated at: 2026-03-05 13:54:54
- Window: 1772616120665 .. 1772702520665 (24h)
- Before DB: `/Users/mac/Andoidaps/tmp_live/copilot_before_importfix_20260305.db`
- After DB: `/Users/mac/Andoidaps/tmp_live/copilot_post_cleanupcheck_20260305.db`

## Therapy events in window
- Before: carbs=14, correction_bolus=0, temp_target=254
- After: carbs=16, correction_bolus=11, temp_target=254

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
- 5m: n=289, MAE=0.374, RMSE=0.551, MARD=5.916%, Bias=-0.018
  - IOB: score=0.037, corr=-0.027, uplift=2.877%, n=289
  - UAM: score=0.134, corr=-0.075, uplift=-4.987%, n=49
  - CI: score=0.808, corr=0.209, uplift=47.153%, n=289
- 30m: n=289, MAE=1.667, RMSE=2.121, MARD=26.317%, Bias=-0.188
  - IOB: score=0.358, corr=-0.126, uplift=-15.989%, n=289
  - UAM: score=0.05, corr=-0.082, uplift=-0.829%, n=49
  - CI: score=0.027, corr=-0.024, uplift=-2.058%, n=289
- 60m: n=289, MAE=3.231, RMSE=3.926, MARD=50.07%, Bias=-0.615
  - IOB: score=0.159, corr=-0.069, uplift=-9.124%, n=289
  - UAM: score=0.025, corr=-0.017, uplift=3.391%, n=49
  - CI: score=0.759, corr=-0.244, uplift=-21.434%, n=289

## Delta (After - Before)
- 5m: ΔMAE=+0.009, ΔMARD=+0.208 pp, ΔBias=+0.004, Δn=0
  - IOB: Δscore=+0.022, Δcorr=-0.02, Δuplift=-4.719%
  - UAM: Δscore=-0.075, Δcorr=+0.026, Δuplift=+2.025%
  - CI: Δscore=-0.17, Δcorr=-0.058, Δuplift=+8.782%
- 30m: ΔMAE=+0.1, ΔMARD=+1.592 pp, ΔBias=-0.022, Δn=3
  - IOB: Δscore=+0.207, Δcorr=-0.057, Δuplift=-8.079%
  - UAM: Δscore=-0.132, Δcorr=+0.022, Δuplift=+3.948%
  - CI: Δscore=-0.054, Δcorr=+0.017, Δuplift=+4.038%
- 60m: ΔMAE=+0.168, ΔMARD=+1.581 pp, ΔBias=-0.151, Δn=9
  - IOB: Δscore=+0.096, Δcorr=-0.04, Δuplift=-1.18%
  - UAM: Δscore=-0.163, Δcorr=-0.083, Δuplift=-12.585%
  - CI: Δscore=+0.144, Δcorr=-0.034, Δuplift=-3.693%

_Counterfactual replay note: forecasts were recomputed offline with HybridPredictionEngine from glucose+therapy history on each 5m cycle._