# Fixed-window replay 24h (same period) — IOB/UAM/CI contribution

- Generated at: 2026-03-05 12:57:56
- Window: 1772616120665 .. 1772702520665 (24h)
- Before DB: `/Users/mac/Andoidaps/tmp_live/copilot_before_importfix_20260305.db`
- After DB: `/Users/mac/Andoidaps/tmp_live/copilot_post_install_20260305.db`

## Therapy events in window
- Before: carbs=14, correction_bolus=0, temp_target=254
- After: carbs=15, correction_bolus=11, temp_target=254

## Before
- 5m: n=1202, MAE=0.405, RMSE=0.524, MARD=6.33%, Bias=-0.176
  - IOB: score=0.723, corr=0.179, uplift=55.28%, n=1202
  - UAM: n/a
  - CI: score=0.788, corr=0.213, uplift=39.53%, n=1202
- 30m: n=1199, MAE=1.412, RMSE=1.830, MARD=21.82%, Bias=-0.356
  - IOB: score=1.036, corr=0.258, uplift=54.19%, n=1199
  - UAM: n/a
  - CI: score=0.008, corr=-0.004, uplift=4.85%, n=1199
- 60m: n=1207, MAE=2.524, RMSE=3.278, MARD=38.18%, Bias=-0.660
  - IOB: score=1.803, corr=0.420, uplift=72.01%, n=1207
  - UAM: n/a
  - CI: score=0.698, corr=-0.223, uplift=-21.75%, n=1207

## After
- 5m: n=1202, MAE=0.405, RMSE=0.524, MARD=6.33%, Bias=-0.176
  - IOB: score=0.723, corr=0.179, uplift=55.28%, n=1202
  - UAM: n/a
  - CI: score=0.788, corr=0.213, uplift=39.53%, n=1202
- 30m: n=1199, MAE=1.412, RMSE=1.830, MARD=21.82%, Bias=-0.356
  - IOB: score=1.036, corr=0.258, uplift=54.19%, n=1199
  - UAM: n/a
  - CI: score=0.008, corr=-0.004, uplift=4.85%, n=1199
- 60m: n=1207, MAE=2.524, RMSE=3.278, MARD=38.18%, Bias=-0.660
  - IOB: score=1.803, corr=0.420, uplift=72.01%, n=1207
  - UAM: n/a
  - CI: score=0.698, corr=-0.223, uplift=-21.75%, n=1207

## Delta (After - Before)
- 5m: ΔMAE=+0.000, ΔMARD=+0.00 pp, ΔBias=+0.000, Δn=0
  - IOB: Δscore=+0.000, Δcorr=+0.000, Δuplift=+0.00%
  - UAM: n/a
  - CI: Δscore=+0.000, Δcorr=+0.000, Δuplift=+0.00%
- 30m: ΔMAE=+0.000, ΔMARD=+0.00 pp, ΔBias=+0.000, Δn=0
  - IOB: Δscore=+0.000, Δcorr=+0.000, Δuplift=+0.00%
  - UAM: n/a
  - CI: Δscore=+0.000, Δcorr=+0.000, Δuplift=+0.00%
- 60m: ΔMAE=+0.000, ΔMARD=+0.00 pp, ΔBias=+0.000, Δn=0
  - IOB: Δscore=+0.000, Δcorr=+0.000, Δuplift=+0.00%
  - UAM: n/a
  - CI: Δscore=+0.000, Δcorr=+0.000, Δuplift=+0.00%
