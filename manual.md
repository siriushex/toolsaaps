# AAPS Predictive Copilot — подробный manual

Документ описывает текущую реализацию в проекте `AAPSPredictiveCopilot` на уровне формул, правил и потока данных.

## 1. Назначение программы

`AAPS Predictive Copilot` — отдельное Android-приложение, которое:
- собирает локальные и Nightscout/AAPS данные;
- строит локальные прогнозы глюкозы (горизонты `5m/30m/60m`);
- считает паттерны, реальный ISF/CR, UAM и активности;
- автоматически управляет `temp target` через Nightscout/AAPS transport при прохождении safety-проверок;
- ведет полный аудит и историю для анализа за день/неделю/месяц/год.

## 2. Архитектура и контуры

### 2.1 Android контур
- Ingest: Broadcast (`xDrip/AAPS`) + локальные датчики шагов.
- Storage: Room (`copilot.db`).
- Predict: `HybridPredictionEngine` (legacy v2 + enhanced v3).
- Rules: `AdaptiveTargetController`, `PostHypo`, `Pattern`, `Segment`.
- Safety: `SafetyPolicy` + runtime лимиты/cooldown.
- Actions: Nightscout API primary + local broadcast fallback.
- Scheduler: WorkManager periodic + reactive one-shot.

### 2.2 Backend контур
- Опциональный cloud forecast merge.
- Аналитика/инсайты.
- Не обходит локальные safety-ограничения.

## 3. Источники данных

### 3.1 Broadcast ingest
Принимаются каналы `com.eveningoutpost.dexdrip.*`, `info.nightscout.androidaps.*`, `info.nightscout.client.*`, `app.aaps.*`.

Пайплайн:
1. Приём Intent.
2. Нормализация payload (включая вложенный JSON).
3. Парсинг glucose / therapy / telemetry.
4. Санитизация диапазонов и timestamp.
5. Запись в Room.
6. Reactive automation trigger.

Защита sender:
- `strictBroadcastSenderValidation=false` по умолчанию.
- В strict режиме разрешаются только доверенные каналы/пакеты.

### 3.2 Nightscout sync
Чтение:
- `entries`, `treatments`, `devicestatus`.

Запись:
- `Temporary Target`;
- `Carb Correction`.

### 3.3 Local activity
`TYPE_STEP_COUNTER` -> каждую минуту heartbeat snapshot:
- `steps_count`, `distance_km`, `active_minutes`, `calories_active_kcal`, `activity_ratio`.

## 4. Единицы и нормализация

Внутренние единицы:
- glucose: `mmol/L`;
- delta/ROC: `mmol/L per 5m`;
- insulin: `U`;
- carbs/COB/UAM carbs: `g`;
- temp target: `mmol/L`.

Ключевые clamp:
- glucose прогноз: `[2.2 .. 22.0] mmol/L`;
- safety temp target hard bounds: `[4.0 .. 10.0]`;
- adaptive controller target: `[4.0 .. 9.0]`;
- therapy cumulative contribution: `[-6.0 .. +6.0] mmol/L`.

## 5. Основной цикл автоматики

Функция: `AutomationRepository.runAutomationCycle()`.

Порядок:
1. `bootstrap/connect/sync/import/recalculate analytics`.
2. Подтянуть `glucose`, `therapy`, `telemetry`.
3. Рассчитать локальный прогноз.
4. Опционально merge cloud forecast.
5. Гарантировать наличие горизонта 30m.
6. Применить calibration bias по последним ошибкам.
7. Применить `COB/IOB` bias к forecast.
8. Записать forecast в БД (retention ~400 дней).
9. Рассчитать calculated UAM + inferred UAM.
10. Сформировать `RuleContext` и выполнить rule engine.
11. Проверить cooldown/idempotency/safety.
12. Отправить action через Nightscout/fallback.
13. Если adaptive не сработал, отправить `adaptive keepalive` не реже чем раз в 30 минут.

### 5.1 Source of truth и конфликт источников
Runtime всегда читает данные из локальной Room БД, а не напрямую из канала.
Это означает:
- источники (`broadcast`, `nightscout sync`, локальные сенсоры) сначала нормализуются и пишутся в БД;
- решение строится по последним валидным записям в БД;
- при одновременном приходе данных приоритет задается фактически временем записи/временной меткой, а не жестким `source priority`.

Практическое правило:
- если по одному параметру есть расхождение каналов, в контур пойдет самое свежее валидное значение после санитизации.

### 5.2 Decision cycle и анти-дубль запуска
- `PeriodicWorkRequest` (15 минут) — страховочный плановый запуск.
- Основной near-real-time контур — `reactive one-shot` после ingest.
- Реактивный запуск защищен debounce `45 секунд`.
- UAM inference/экспорт выполняется в 5-мин bucket cadence.
- Отправки защищены idempotency key и dedup (включая UAM `id+seq`).

## 6. Прогнозирование глюкозы

## 6.1 Режимы
- Legacy: `local-hybrid-v2`.
- Enhanced: `local-hybrid-v3` (в проекте включен по умолчанию).

## 6.2 Legacy (v2)
Для горизонта `h`:

`pred(h) = clamp(G_now + trendDelta(h) + therapyDelta(h), 2.2..22.0)`

Где:
- `trendDelta(h)` — blended slope + acceleration с ограничением `maxAbs = 0.55*n + 0.7`, `n=h/5`.
- `therapyDelta(h)` — вклад carbs (+) и insulin (-) через cumulative curves.

## 6.3 Enhanced (v3) — текущий основной

### Шаг 1. Подготовка
- Dedup CGM по timestamp.
- Сортировка.

### Шаг 2. Kalman (state = G, v)
`KalmanGlucoseFilterV3`:
- state: `g (mmol/L)`, `v (mmol/L/min)`.
- Адаптивные шумы:
  - `sigmaZ` (measurement noise) через NIS EWMA;
  - `sigmaA` (process accel noise) через volNorm + NIS.
- Clamp ROC: `rocPer5 = clamp(v*5, -1.2, +1.2)`.
- Warm-up: минимум `3` апдейта.

Если warm-up недостаточный:
- `G_now_used = G_now_raw`;
- `rocPer5_used = 0.65*shortSlope + 0.35*longSlope`.

### Шаг 3. ISF/CR/CSF
Из истории therapy/glucose:
- ISF: по correction-bolus падению;
- CR: по meal_bolus/carb-bolus парам;
- fallback: `ISF=2.3`, `CR=10`.

`CSF = clamp(ISF/CR, 0.05 .. 0.40)`.

### Шаг 4. Therapy step-series (announced)
Для шагов `j=1..12` (по 5 минут):
- carbs step:
  `+ carbs_g * CSF * max(0, carbCum(ageB)-carbCum(ageA))`
- insulin step:
  `- insulin_u * ISF * max(0, insCum(ageB)-insCum(ageA))`

Потом cumulative clamp:
- `therapyCumRaw[j] = sum(stepRaw[1..j])`
- `therapyCumClamped[j] = clamp(therapyCumRaw[j], -6..+6)`
- `therapyStep[j] = therapyCumClamped[j]-therapyCumClamped[j-1]`

### Шаг 5. UAM (Level A + optional virtual meal fit)
`UamEstimator`:
1. Находит prev CGM с `dt in [2..15]`.
2. `s_obs_per5 = (G_now-G_prev)/(dt/5)`.
3. Вычитает therapy slope за интервал `[prev, now]`.
4. `dev0 = s_obs_per5 - therapySlopePastPer5`.
5. `uci0 = clamp(max(0, dev0), 0, uciMax)`.

`uciMax = 30.0 * CSF * (5/60)`.

История `uci0` в 5-мин buckets (60 минут), затем:
- `kMax = (uci0-uciMaxHist)/stepsMax`
- `kMin = (uci0-uciMinHist)/stepsMin`
- `k = min(kMax, -kMin/3)`
- fallback: `k=-uci0/12`

На шаге `j`:
- `uciSlope = max(0, uci0 + j*k)`
- `uciCap = max(0, uci0*(1 - j/36))`
- `uci(j) = min(uciSlope, uciCap)`

Если включен virtual meal fit:
- LS-поиск `t*` и `C*`;
- confidence threshold: `0.55`;
- при успехе шаги берутся из event-based carb cumulative.

### Шаг 6. Residual trend AR(1)

`residualRoc0 = rocPer5_used - therapyStep[1] - uamStep[1]`

Анти-двойной учет:
- если `uci0 >= 0.10`, тогда `residualRoc0 = min(0, residualRoc0)`.

`ResidualArModel`:
- history window: 24 buckets (120 минут).
- если sample < 8 -> fallback:
  - `mu=0`,
  - `phi = exp(-ln(2)*5/20)`,
  - `sigmaE=0.10`.
- иначе weighted AR(1) fit:
  - `mu` clamp `[-0.30..0.30]`, и `mu<=0` при active UAM;
  - `phi` clamp `[0.0..0.97]`;
  - `sigmaE` clamp `[0.05..0.60]`.

Forecast:
- `trendStepRaw[j] = mu + phi^(j-1) * (residualRoc0 - mu)`.

Trend clamp на 60m:
- `trend60Raw = sum(trendStepRaw[1..12])`
- `trend60Clamped = clamp(trend60Raw, -maxAbs(12), +maxAbs(12))`
- `scale = trend60Clamped/trend60Raw`
- `trendStep[j] = trendStepRaw[j] * scale`

### Шаг 7. Path simulation

`G[0] = G_now_used`

Для `j=1..12`:

`G[j] = clamp(G[j-1] + trendStep[j] + therapyStep[j] + uamStep[j], 2.2..22.0)`

Выход:
- `pred5 = G[1]`
- `pred30 = G[6]`
- `pred60 = G[12]`

### Шаг 8. CI
База (legacy logic):
`ciBase = base(h) + volatility*gain(h) + intervalPenalty`, clamp `[0.30..3.2]`.

Добавки v3:
- `ciAddUam = clamp(1.0*sqrt(n)*uci0, 0..0.8)`
- `ciAddRoc = clamp(0.35*sqrt(n)*|residualRoc0|, 0..0.6)`
- `ciAddKF = clamp(0.90*sqrt(n)*kfSigmaG, 0..0.6)` (если KF warmed)
- `ciAddAR = clamp(0.70*sqrt(n)*sigmaE, 0..0.7)`

`ciHalf = clamp(ciBase + ciAddUam + ciAddRoc + ciAddKF + ciAddAR, 0.30..3.2)`

`ciLow = clamp(pred - ciHalf, 2.2..22.0)`

`ciHigh = clamp(pred + ciHalf, 2.2..22.0)`

## 7. Профили инсулина и углеводов

### 7.1 Инсулин
Профили:
- `NOVORAPID` (default),
- `HUMALOG`,
- `APIDRA`,
- `FIASP`,
- `LYUMJEV`.

Каждый профиль задан piecewise cumulative curve `insCum(ageMinutes)`.

### 7.2 Углеводы
Классы:
- `FAST`,
- `MEDIUM`,
- `PROTEIN_SLOW`.

Кривые усвоения — piecewise cumulative `carbCum(type, ageMinutes)`.

Каталог:
- fast: 100,
- medium: 100,
- protein: 50.

Приоритет классификации carb-события:
1. explicit type в payload,
2. food text catalog,
3. glucose pattern fallback,
4. default medium.

## 8. UAM inference (отдельный контур)

`UamInferenceEngine` работает в 5-мин buckets (CGM может быть каждую минуту).

Пайплайн:
1. Минутные CGM сглаживаются Kalman.
2. Строится 5-мин grid (до 120 минут).
3. Считается residual по интервалам:
   - `dG_obs - dG_therapy - dG_virtual_uam`.
4. `r_pos=max(0,r)` -> `gAbs = r_pos / CSF_uam`.

`CSF_uam = CSF / multiplier`, где
- NORMAL: `multiplier=learnedMultiplier` (0.8..1.6),
- BOOST: `multiplier=2.0*learnedMultiplier` (1.5..3.0 effective clamp).

Детектор события:
- NORMAL: threshold `2.0 g/5m`, `M-of-N=3/4`.
- BOOST: threshold `1.2 g/5m`, `M-of-N=2/3`.

State machine:
- `SUSPECTED -> CONFIRMED -> FINAL` (или `MERGED`).

Подтверждение:
- confidence >= `0.45` (NORMAL) / `0.35` (BOOST),
- age >= `10` мин.

Ограничения:
- snack quantization: min/max/step (`15..60` с шагом `5` по умолчанию),
- anti-duplicate с manual carbs и manual COB.

### 8.1 Сопоставление терминов с OpenAPS/AAPS
- OpenAPS `deviation` (по смыслу) соответствует вашей положительной остаточной части (`dev0/uci0`) после вычитания ожидаемой терапии.
- AAPS `CSF = ISF/IC` по смыслу совпадает с `CSF = ISF/CR` в модели Copilot (при условии согласованных единиц).
- Логика `UAM taper down` отражена через `uciCap` (линейный спад к нулю) и `k`-динамику.

### 8.2 Важные оговорки по CSF/COB
- `CSF_uam` через multiplier — сильный рычаг чувствительности inference.
- Чрезмерно быстрые изменения `CSF/CSF_uam` могут давать неинтуитивный `remaining COB`.
- Практическая рекомендация для эксплуатации: менять multiplier постепенно и смотреть аудит `uam_inferred_*` + прогнозные ошибки, а не делать резкие скачки настроек.

### 8.3 Anti-loop для inferred carbs
Чтобы избежать самоподкрепления:
- экспортируемые carbs всегда тегируются `UAM_ENGINE|id|seq|...`;
- перед отправкой выполняется fetch+dedup по remote entries;
- при близких manual carbs событие должно объединяться/блокироваться (MERGED/skip), а не запускать новую цепочку экспорта.

## 9. Экспорт inferred carbs в AAPS/Nightscout

Флаги:
- `enableUamExportToAaps`;
- `dryRunExport`;
- mode: `OFF | CONFIRMED_ONLY | INCREMENTAL`.

Тег для распознавания своих записей:

`UAM_ENGINE|id=<uuid>|seq=<n>|ver=1|mode=<NORMAL/BOOST>|`

Дедупликация:
1. Fetch entries за последние 6 часов.
2. Парсинг note -> `UamTag`.
3. Если `id+seq` уже есть, повторно не отправлять.

Backdate:
- используется `ingestionTs`;
- clamp: не старше `exportMaxBackdateMin` (по умолчанию 180 мин).

## 10. Rule Engine

Приоритеты по умолчанию:
- `AdaptiveTargetController.v1`: 120
- `PostHypoReboundGuard.v1`: 100
- `PatternAdaptiveTarget.v1`: 50
- `SegmentProfileGuard.v1`: 40

Логика арбитража:
- если верхний rule `TRIGGERED`, lower-priority правила не отправляют action;
- если adaptive `NO_MATCH/BLOCKED`, возможен fallback на другие rules.

## 11. Adaptive Temp Target Controller

Контроллер: `AdaptiveTempTargetController`.

Вход:
- `baseTarget`, `current glucose`, `pred5/30/60`, `CI 5/30/60`, `uamActive`, `previousI`, `previousTempTarget`, `COB`, `IOB`.

Выход:
- `newTempTarget` (4..9), `duration=30`, `updatedI`, `reason`, debug fields.

### 11.1 Control core
- confidence-weighted fusion `Pctrl` из pred5/30/60 через inverse-variance веса CI.
- PI:
  - `Kp=0.35`, `Ki=0.02`,
  - `I clamp [-200..+200]`,
  - `deltaT clamp [-2.0..+2.0]`.

### 11.2 Safety channels (priority)
- `safety_force_high`:
  - near-term/hypo-risk по CI low,
  - target -> 9.0.
- `safety_hypo_guard`:
  - повышает target пропорционально риску гипо.

### 11.3 COB/IOB influence
- Если `COB >= 20g`, effective base target принудительно `4.2`.
- Дополнительные control bias:
  - `+ cobBias`;
  - `- iobBias`.
- IOB relief может слегка поднять effective base в верхнюю сторону (ограниченно).

### 11.4 High-glucose guard
Если прогноз сильно выше base и гипо-риска нет:
- ограничивает максимально допустимый temp target,
- чтобы не уводить цель вверх при гипергликемии.

### 11.5 Важно
Rate limit изменения target внутри контроллера отключен (по требованию): новое вычисленное значение может отправляться сразу.

### 11.6 Нюанс SMB/High Temp Target в AAPS
Copilot отправляет только `temp target`, а фактическая SMB-политика определяется настройками AAPS.
Практически это значит:
- высокий temp target может менять агрессивность петли в AAPS;
- в конкретной конфигурации AAPS при high TT SMB может работать иначе.
Рекомендация: интерпретировать результат контроллера вместе с текущей SMB-конфигурацией AAPS.

## 12. Safety Policy

Глобальные проверки перед отправкой действия:
- `kill_switch`;
- `stale_data`;
- `rate_limit_6h` (для non-temp_target);
- `target_out_of_bounds`;
- `duration_out_of_bounds`.

`Kill switch` блокирует авто-действия, ручные команды остаются доступны.

## 13. COB/IOB влияние на прогнозы

После базового предикта применяется bias:
- COB поднимает forecast,
- IOB снижает forecast,
- горизонто-зависимые коэффициенты для `5/30/60`.

Итоговый bias clamp:
- общий bias `[-4.0 .. +3.0]` mmol.

## 14. Keepalive обновление temp target

Если adaptive в этом цикле не отправил temp target:
- Copilot отправляет `adaptive_keepalive_30m` не реже чем раз в `30 минут`,
- чтобы контур не простаивал.

Условия keepalive:
- `kill_switch=false`,
- данные свежие,
- сенсор не заблокирован,
- нет недавно отправленного auto temp target.

## 15. ISF/CR расчеты

`ProfileEstimator` считает:
- Active merged ISF/CR (история + telemetry по merge-mode);
- Calculated history-only ISF/CR;
- сегменты по `WEEKDAY/WEEKEND` и `NIGHT/MORNING/AFTERNOON/EVENING`;
- hourly ISF/CR и hourly by day type;
- UAM-aware фильтрацию correction-сэмплов.

### 15.1 ISF
Коррекционные bolus-события:
- берется baseline glucose до bolus,
- ищется минимум в окне после bolus,
- `drop / units` -> sample,
- фильтруются кейсы с carbs рядом и UAM рядом.

### 15.2 CR
Источники:
- прямые `meal_bolus (grams/units)`;
- pairing `carbs` с ближайшим bolus.

### 15.3 Статистика
- медиана sample после trim outliers;
- confidence из количества sample;
- отдельные sample-count для history и telemetry.

## 16. Telemetry coverage и ключи

Канонические ключи (основные):
- `iob_units`, `cob_grams`, `carbs_grams`, `insulin_units`,
- `future_carbs_grams`, `dia_hours`,
- `steps_count`, `distance_km`, `active_minutes`, `calories_active_kcal`, `activity_ratio`,
- `temp_target_low_mmol`, `temp_target_high_mmol`, `temp_target_duration_min`,
- `isf_value`, `cr_value`, `uam_value`.

Плюс runtime/diagnostic UAM ключи:
- `uam_calculated_*`,
- `uam_inferred_*`.

## 17. База данных (Room)

Таблицы:
- `glucose_samples`
- `therapy_events`
- `forecasts`
- `rule_executions`
- `action_commands`
- `sync_state`
- `audit_logs`
- `baseline_points`
- `pattern_windows`
- `profile_estimates`
- `profile_segment_estimates`
- `telemetry_samples`
- `uam_inference_events`

Schema version: `9`.

Retention:
- `forecasts`: ~400 дней;
- `uam events`: ~14 дней;
- analytics snapshot history зависит от `lookbackDays`.

## 18. Планировщик и фон

`WorkScheduler`:
- periodic `SyncAndAutomateWorker` каждые 15 минут;
- periodic `DailyAnalysisWorker` раз в сутки;
- reactive one-shot worker с debounce 45 секунд (после ingest).

Важно:
- у `PeriodicWorkRequest` минимальный интервал 15 минут, но точное время запуска ОС не гарантирует;
- поэтому основной рабочий контур для актуализации — реактивный one-shot;
- periodic worker — fallback/страховка.

## 19. Локальный Nightscout server

Встроенный loopback HTTPS сервер:
- URL: `https://127.0.0.1:<port>`;
- дефолтный порт `17580` (может auto-shift, если занят);
- маршруты:
  - `/api/v1/status.json`
  - `/api/v1/entries/sgv.json`
  - `/api/v1/treatments.json`
  - `/api/v1/devicestatus.json`
  - `/socket.io`.

Используется для локального обмена с AAPS/NS-клиентом без внешнего NS.

## 20. Экран Safety Center — что означает каждая опция

Глобальные:
- `Kill switch` — блок только авто-контур.
- `Base target mmol/L` — базовая цель.
- `Max auto-actions in 6h` — лимит частоты.
- `Stale data max minutes` — максимум возраста данных.

Post-hypo:
- `Hypo threshold`;
- `Delta threshold`;
- `Temp target`;
- `Duration`;
- `Lookback`.

Pattern analytics:
- min samples/day thresholds;
- low/high rate triggers;
- lookback days.

UAM inference/export:
- enable inference;
- boost;
- export mode;
- dry-run;
- snack limits;
- anti-duplicate windows;
- absorption/threshold/confirmation/export intervals.

Adaptive controller:
- runtime always ON;
- safety profile;
- rule priority;
- stale/action limits;
- max step;
- preview (`f30`, `f60`, `error`, `next target`, `reason`, `confidence`).

Safety инварианты (runtime):
- auto temp target всегда в `4..9 mmol/L` на уровне adaptive controller;
- глобальные hard bounds для temp target `4..10 mmol/L` на уровне safety policy;
- `kill switch` блокирует только авто-контур;
- при stale data/sensor blocked авто-действия блокируются;
- ручные команды не блокируются kill switch.

## 21. Ручные действия

Доступны ручные команды `temp target` / `carbs` для проверки outbound каналов.

Идемпотентность:
- manual idempotency keys с префиксом `manual:`.

## 22. Аудит и диагностика

Где смотреть:
- `Audit Log` экран;
- таблица `audit_logs`;
- `rule_executions`;
- `action_commands` (status: `PENDING/SENT/FAILED`).

Ключевые adaptive события:
- `adaptive_controller_evaluated`
- `adaptive_controller_triggered`
- `adaptive_controller_blocked`
- `adaptive_controller_fallback_to_rules`
- `adaptive_keepalive_sent` / `adaptive_keepalive_failed`

## 23. Проверка корректности данных

Мини-чеклист:
1. Проверить свежесть `glucose_samples`.
2. Проверить, что `telemetry_samples.timestamp > 0`.
3. Проверить ключи `iob_units`, `cob_grams`, `temp_target_*`, `uam_*`.
4. Проверить forecast `5/30/60` и CI.
5. Проверить `rule_executions` + `action_commands`.
6. Проверить отсутствие дублей по `idempotencyKey`.
7. Проверить sensor quality:
`bg` не должен скачком уходить в экстремальные low/high без подтверждения следующими точками.
8. При подозрении на CGM-ошибку:
проверить, что авто-контур ушел в blocked/защитное поведение, а не продолжил агрессивные действия.
9. Проверить, что UAM export не создает повторные carbs при уже существующих `UAM_ENGINE` тегах.

## 24. Быстрые SQL-запросы для анализа

### 24.1 Данные за сутки
```sql
SELECT datetime(timestamp/1000, 'unixepoch', 'localtime') AS ts, mmol
FROM glucose_samples
WHERE timestamp >= strftime('%s','now','-1 day')*1000
ORDER BY timestamp;
```

### 24.2 Покрытие телеметрии
```sql
SELECT key, COUNT(*) cnt, MAX(timestamp) last_ts
FROM telemetry_samples
GROUP BY key
ORDER BY cnt DESC;
```

### 24.3 Прогнозы по горизонтам
```sql
SELECT horizon_minutes, COUNT(*) cnt, MIN(ts) min_ts, MAX(ts) max_ts
FROM forecasts
GROUP BY horizon_minutes;
```

### 24.4 История действий
```sql
SELECT datetime(timestamp/1000, 'unixepoch', 'localtime') ts, type, status, idempotency_key
FROM action_commands
ORDER BY timestamp DESC
LIMIT 200;
```

### 24.5 Rule срабатывания
```sql
SELECT rule_id, state, COUNT(*) cnt
FROM rule_executions
GROUP BY rule_id, state
ORDER BY rule_id, state;
```

## 25. Ограничения текущей версии

- Heart Rate может быть нестабильным в зависимости от источника и разрешений.
- Cloud override опционален; локальная логика должна быть самодостаточной.
- Local Nightscout зависит от корректного TLS setup/доверия сертификату в AAPS.
- Room использует `fallbackToDestructiveMigration`, поэтому при несовместимых миграциях возможна потеря локальной истории.
- Слишком агрессивные настройки `CSF_uam`/boost могут искажать COB/UAM интерпретацию.
- Поведение SMB при high temp target определяется конфигурацией AAPS и должно проверяться отдельно.
- Ошибочные CGM low/high (sensor artifacts) остаются практическим риском и требуют operational контроля качества сенсора.

## 26. Где в коде смотреть расчеты

- Прогноз v3: `domain/predict/HybridPredictionEngine.kt`
- Kalman v3: `domain/predict/KalmanGlucoseFilterV3.kt`
- AR trend: `domain/predict/ResidualArModel.kt`
- UAM estimator (predictor): `domain/predict/UamEstimator.kt`
- UAM inference/export: `domain/predict/UamInferenceEngine.kt`, `data/repository/UamExportCoordinator.kt`
- Adaptive controller: `domain/rules/AdaptiveTempTargetController.kt`, `AdaptiveTargetControllerRule.kt`
- Safety policy: `domain/safety/SafetyPolicy.kt`
- Automation loop: `data/repository/AutomationRepository.kt`
- Telemetry mapping: `data/repository/TelemetryMetricMapper.kt`
- ISF/CR estimator: `domain/predict/ProfileEstimator.kt`
- Local NS server: `service/LocalNightscoutServer.kt`
- Scheduler: `scheduler/WorkScheduler.kt`

## 27. Риски и меры (операционный минимум)

| Риск | Где проявляется | Мера |
| --- | --- | --- |
| Непредсказуемый периодический фон | WorkManager periodic | Делать ставку на reactive one-shot, periodic считать fallback |
| CGM артефакты (ложные low/high) | Ingest -> Rules | Усиленный sensor quality check, блок автоканала при сомнительном потоке |
| Неинтуитивный remaining COB | CSF/COB/UAM tuning | Менять multiplier плавно, анализировать `uam_inferred_*` и forecast ошибки |
| Повторный экспорт inferred carbs | UAM export | Tag-based dedup (`id+seq`) + fetch before post + anti-nearby merge |
| Нежелательное влияние high temp target на SMB | AAPS execution semantics | Проверять конфигурацию AAPS SMB/High TT и трактовать действия Copilot в этом контексте |

## 28. QA-валидация перед эксплуатацией

Минимальный набор:
1. Проверка калибровки CI: доля попаданий факта в CI на горизонтах `5/30/60`.
2. Проверка 1-мин и 5-мин потоков CGM (warm-up KF, dt-фильтр, 5-мин grid).
3. Сценарии `meal spike`, `post-hypo rebound`, `exercise`, `dawn`.
4. Проверка anti-double-count: при active UAM residual не должен давать дополнительный положительный рост.
5. Проверка export safety:
нет дублей `UAM_ENGINE id+seq`, корректный backdate clamp, корректный dry-run/off режим.

## 29. Короткий словарь для `i`-подсказок в UI

Ниже формулировки в стиле «что это» + «на что влияет», которые используются в `i`-иконках для ключевых параметров.

| Параметр | Простое объяснение | Влияние на сахар/компенсацию |
| --- | --- | --- |
| `Base target` | Главная цель сахара, к которой система старается привести прогноз. | Ниже цель -> агрессивнее снижение; выше цель -> мягче и безопаснее от гипо. |
| `Temp target` | Временная цель на ограниченное время. | Меняет агрессивность контура в ближайшие 30–60 минут. |
| `ISF` | На сколько в среднем падает сахар от `1U` инсулина. | Больше ISF -> инсулин «сильнее»; меньше ISF -> для того же эффекта нужно больше инсулина. |
| `CR` | Сколько граммов углеводов покрывает `1U` инсулина. | Меньше CR -> нужен более крупный болюс на ту же еду; больше CR -> меньший болюс. |
| `CSF` | Перевод углеводов в ожидаемый рост сахара (`ISF/CR`). | Используется в COB/UAM и прогнозе постпрандиального роста. |
| `IOB` | Активный инсулин, который еще действует. | Высокий IOB увеличивает риск снижения сахара в ближайшее время. |
| `COB` | Углеводы, которые еще не усвоились полностью. | Высокий COB повышает риск роста сахара в ближайшие 30–120 минут. |
| `UAM` | Необъявленные углеводы/эффект, найденные по фактическому росту сахара. | Добавляет «скрытый» углеводный вклад в прогноз, когда еда не была внесена вручную. |
| `DIA` | Длительность активного действия инсулина. | Чем больше DIA, тем длиннее «хвост» IOB и медленнее спад инсулинового вклада. |
| `CI` | Доверительный интервал прогноза (зона неопределенности). | Широкий CI = выше неопределенность, автоматика должна быть осторожнее. |
| `Confidence` | Уверенность модели в текущем расчете (ISF/CR/UAM/forecast). | Низкий confidence включает fallback и уменьшает влияние «смелых» решений. |
| `Sensor quality` | Оценка надежности потока CGM (шум, разрывы, артефакты). | Плохое качество расширяет CI и может блокировать авто-действия. |
| `Stale data` | Устаревшие данные (слишком давно не было обновления). | При stale авто-решения ограничиваются или блокируются safety-контуром. |
| `Activity ratio / steps` | Текущая физнагрузка по шагам/активности. | При росте нагрузки система повышает гипо-защиту и корректирует чувствительность. |
| `Kill switch` | Мгновенно отключает только авто-контур. | Ручные команды остаются доступны, автоматические отправки блокируются. |

### Источники терминов и базовой логики
- AndroidAPS docs: <https://androidaps.readthedocs.io/en/latest/>
- OpenAPS docs (autotune/deviation/BGI/ISF concepts): <https://openaps.readthedocs.io/en/latest/docs/Customize-Iterate/autotune.html>

Примечание:
- Формулировки в UI намеренно короткие и практичные.
- Для принятия терапевтических решений в первую очередь используйте рекомендации лечащей команды.
