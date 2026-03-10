# Replay 24h/7d: ошибки 30m/60m в окнах mealAlignmentShiftMin и isfOutlier=1

- DB: `/Users/mac/Andoidaps/AAPSPredictiveCopilot/device_dump_20260304_171109/copilot_internal/databases/copilot_recovered.db`
- Generated: 2026-03-05 15:22:04
- Evidence rows in DB: 0 (mealAligned=0, isfOutlier=0)
- Match logic: forecast ts -> nearest glucose (tol 15m for 30/60), originTs=forecastTs-horizon.
- Window logic: mealAlignment [eventTs..+240m], isfOutlier [eventTs-15m..+240m].

## 24h
- Window ts: 1772547061018..1772633461018
- Matched rows total: 2323
### 30m
- ALL: n=1162, MAE=1.137, RMSE=1.415, MARD=19.734%, Bias=-0.290
- mealAlignment windows: n=0, MAE=n/a, RMSE=n/a, MARD=n/a%, Bias=n/a
- isfOutlier windows: n=0, MAE=n/a, RMSE=n/a, MARD=n/a%, Bias=n/a
- Factor contribution (ALL):
  - IOB: score=0.248, corr=0.096, uplift=12.199%, n=1162
  - UAM: score=0.447, corr=0.121, uplift=38.762%, n=1162
  - CI: score=0.320, corr=0.111, uplift=16.771%, n=1162
- Factor contribution (mealAlignment windows):
  - IOB: n/a
  - UAM: n/a
  - CI: n/a
- Factor contribution (isfOutlier windows):
  - IOB: n/a
  - UAM: n/a
  - CI: n/a

### 60m
- ALL: n=1161, MAE=2.105, RMSE=2.619, MARD=35.571%, Bias=-0.553
- mealAlignment windows: n=0, MAE=n/a, RMSE=n/a, MARD=n/a%, Bias=n/a
- isfOutlier windows: n=0, MAE=n/a, RMSE=n/a, MARD=n/a%, Bias=n/a
- Factor contribution (ALL):
  - IOB: score=0.363, corr=0.122, uplift=18.566%, n=1161
  - UAM: score=0.569, corr=0.144, uplift=51.578%, n=1161
  - CI: score=0.089, corr=0.062, uplift=3.223%, n=1161
- Factor contribution (mealAlignment windows):
  - IOB: n/a
  - UAM: n/a
  - CI: n/a
- Factor contribution (isfOutlier windows):
  - IOB: n/a
  - UAM: n/a
  - CI: n/a


## 7d
- Window ts: 1772028661018..1772633461018
- Matched rows total: 5609
### 30m
- ALL: n=2819, MAE=1.309, RMSE=1.757, MARD=24.546%, Bias=-0.109
- mealAlignment windows: n=0, MAE=n/a, RMSE=n/a, MARD=n/a%, Bias=n/a
- isfOutlier windows: n=0, MAE=n/a, RMSE=n/a, MARD=n/a%, Bias=n/a
- Factor contribution (ALL):
  - IOB: score=0.341, corr=0.130, uplift=12.886%, n=2819
  - UAM: score=0.976, corr=0.246, uplift=51.849%, n=2819
  - CI: score=0.478, corr=0.147, uplift=24.563%, n=2819
- Factor contribution (mealAlignment windows):
  - IOB: n/a
  - UAM: n/a
  - CI: n/a
- Factor contribution (isfOutlier windows):
  - IOB: n/a
  - UAM: n/a
  - CI: n/a

### 60m
- ALL: n=2790, MAE=2.582, RMSE=3.310, MARD=50.454%, Bias=-0.147
- mealAlignment windows: n=0, MAE=n/a, RMSE=n/a, MARD=n/a%, Bias=n/a
- isfOutlier windows: n=0, MAE=n/a, RMSE=n/a, MARD=n/a%, Bias=n/a
- Factor contribution (ALL):
  - IOB: score=0.024, corr=0.014, uplift=-4.433%, n=2790
  - UAM: score=1.489, corr=0.345, uplift=74.299%, n=2790
  - CI: score=0.342, corr=0.121, uplift=15.736%, n=2790
- Factor contribution (mealAlignment windows):
  - IOB: n/a
  - UAM: n/a
  - CI: n/a
- Factor contribution (isfOutlier windows):
  - IOB: n/a
  - UAM: n/a
  - CI: n/a

