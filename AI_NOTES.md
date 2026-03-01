# Изменения — Этап 1: Governance baseline

## Что сделано
- Добавлен корневой `AGENTS.md` с постоянными правилами работы Codex для проекта.
- Зафиксирован процесс по контурам (архитектура, план, реализация, документация, DevOps, security).
- Добавлены обязательные опорные документы:
  - `docs/ARCHITECTURE.md`
  - `docs/INVARIANTS.md`
  - `docs/PLAN.md`
  - `docs/DEVOPS.md`
  - `docs/SECURITY_REVIEW.md`
- В `AGENTS.md` подставлены команды качества под текущий стек Android + backend.

## Почему так
- Нужна стабильная «долгая память» между threads и этапами.
- Нужен формализованный DoD и обязательные артефакты для каждого этапа.
- Нужны фиксированные правила, которые Codex читает до начала работ.

## Риски / ограничения
- Backend lint/typecheck пока не стандартизированы инструментально (есть только рекомендации).
- Документы заданы как baseline и требуют регулярной актуализации по факту изменений кода.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot && ls AGENTS.md AI_NOTES.md docs`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/backend && .venv/bin/python -m pytest -q`

# Изменения — Этап 2: Carb Absorption Profiles (human-like digestion model)

## Что сделано
- Добавлен модуль профилей усвоения углеводов:
  - `FAST` (быстрые),
  - `MEDIUM` (средние),
  - `PROTEIN_SLOW` (медленное влияние белковой/смешанной пищи).
- Загружен встроенный каталог продуктов:
  - 100 позиций fast,
  - 100 позиций medium,
  - 50 позиций protein/slow.
- Добавлена авто-классификация carb-события:
  1) явный тип из payload,
  2) поиск по названию/описанию продукта,
  3) fallback по паттерну глюкозы после события.
- Интеграция в `HybridPredictionEngine`:
  - профильный `carbCumulative` по событию для терапии в v3 и legacy.
  - расчёт остаточных carbs по окнам `now/30m/60m/120m`.
  - передача той же профильной логики в `UamEstimator`, чтобы не было расхождения терапии/UAM.
- Добавлены тесты:
  - размеры каталога и базовая классификация,
  - поведение профилей в прогнозе (быстрые vs медленные).
- Обновлены архитектурные и инвариантные документы.
- Обновлён debug APK на телефоне по USB.

## Почему так
- Одна кривая carbs для любой пищи давала систематическую ошибку по раннему/позднему пику.
- Профильный подход лучше отражает физиологию: быстрый пик для простых сахаров и растянутый вклад для сложных/белковых приёмов.
- Единая классификация для therapy и UAM снижает конфликт вкладов в прогнозе.

## Риски / ограничения
- Каталог продуктовых позиций встроенный (rule-based), без персональной адаптации по каждому блюду.
- Если в событии нет описания продукта и мало CGM-точек после еды, срабатывает fallback `MEDIUM`.
- `lintDebug` в проекте сейчас падает на ранее существующей проблеме (`SoonBlockedPrivateApi` в `MainViewModel`), не связанной с этим этапом.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.*"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
4. В приложении добавить carbs с разными `food`-описаниями (например `banana`, `buckwheat`, `chicken breast`) и сравнить траектории 5/30/60m.

# Изменения — Этап 3: Data Retention + Timestamp Sanitation

## Что сделано
- Устранён источник `timestamp=0` в телеметрии:
  - `TelemetryMetricMapper.sample(...)` теперь нормализует невалидные таймстемпы к wall-clock.
  - `BroadcastIngestRepository` отклоняет/нормализует нулевые и слишком будущие timestamps.
  - `SyncRepository` нормализует timestamps Nightscout (SGV/treatments/deviceStatus), не пишет нулевые метки.
- Добавлена периодическая очистка legacy-телеметрии с `timestamp <= 0`.
- Увеличено хранение прогнозов:
  - retention `forecasts` изменён с 7 дней на 400 дней.
- Включено историческое накопление аналитики:
  - `pattern_windows`: append snapshots + выборка latest per (`dayType`,`hour`).
  - `profile_segment_estimates`: append snapshots + выборка latest per (`dayType`,`timeSlot`).
  - `profile_estimates`: хранится `active` + timestamped snapshots (`snapshot-<ts>`).
  - добавлены cleanup-методы по `lookbackDays` для history-таблиц.

## Почему так
- Для анализа за день/неделю/месяц/год нужна длинная история и корректные timestamps.
- Нулевые timestamps и короткая retention искажали покрытие телеметрии и ретроспективный анализ.
- Runtime должен видеть только актуальные срезы, при этом БД должна сохранять историю.

## Риски / ограничения
- Рост размера БД ускорится из-за хранения snapshot history (особенно telemetry/raw и profile snapshots).
- Исторические snapshot-таблицы пока без отдельного UI-экрана истории; runtime использует latest-срезы.
- В проекте остаётся риск потери данных при смене Room schema из-за `fallbackToDestructiveMigration`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.TelemetryMetricMapperTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
4. На устройстве проверить SQL:
   - `SELECT COUNT(*) FROM telemetry_samples WHERE timestamp<=0;` -> `0`
   - через 2+ цикла analytics проверить наличие `snapshot-*` в `profile_estimates`.

# Изменения — Этап 4: COB/IOB influence for forecasts and adaptive temp target

## Что сделано
- В runtime-цикле автоматики добавлено явное влияние `COB/IOB` на прогнозы (`pred5/30/60`) через bounded horizon-aware bias:
  - `COB` сдвигает прогноз вверх,
  - `IOB` сдвигает прогноз вниз.
- Добавлено правило эффективной цели для автоматики:
  - если `cob_grams >= 20`, runtime `base target` принудительно `4.2 mmol/L`.
- `AdaptiveTempTargetController` расширен входами `cobGrams/iobUnits`:
  - используется forced-base при значимом COB,
  - добавлены контрольные bias-термы `COB(+)/IOB(-)` в control-канал.
- `AdaptiveTargetControllerRule` теперь передаёт в контроллер нормализованные `cob/iob` из телеметрии.
- Добавлены unit-тесты:
  - `AdaptiveTempTargetControllerTest` (проверка forced-base и влияния IOB),
  - `AdaptiveTargetControllerRuleTest` (trigger при значимом COB),
  - `AutomationRepositoryForecastBiasTest` (bias прогноза и clamp CI/value).

## Почему так
- Требование сценария: `COB/IOB` должны влиять не только на отображение, но и на фактическое управление.
- При значимом `COB` система должна активнее вести к более низкой цели (`4.2`) для работы с объявленными углеводами.
- Отдельный bounded bias по горизонтам сохраняет стабильность (без неограниченных сдвигов прогноза).

## Риски / ограничения
- Bias по `COB/IOB` добавляется поверх event-driven модели, поэтому в отдельных сценариях возможно частичное переучётное влияние; величины bias ограничены clamp-ами.
- Влияние зависит от качества входной телеметрии (`cob_grams`, `iob_units`) и её своевременности.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`

# Изменения — Этап 5: Yesterday ISF/CR UI + Steps/Activity telemetry support

## Что сделано
- Добавлен расчёт и вывод `реального (history-only) ISF/CR` за прошлый день в `Safety Center`:
  - отдельный блок `Yesterday real ISF/CR`;
  - считаются значения по окну `вчера 00:00..23:59` (локальная TZ);
  - показываются `ISF`, `CR`, confidence и sample counts;
  - дополнительно выводится merged-вариант (history + telemetry), если доступен.
- Добавлена сводка по физической активности на `Dashboard`:
  - шаги сегодня/вчера,
  - текущий `activity_ratio` и средний за 6 часов,
  - active minutes / distance / active calories за сегодня,
  - activity label (если приходит из источника).
- Расширен сбор телеметрии в `TelemetryMetricMapper`:
  - `distance_km`,
  - `active_minutes`,
  - `calories_active_kcal`,
  - поддержка как из key-value payload, так и из flattened Nightscout devicestatus.
- Расширено покрытие и snapshot ключей в `MainViewModel`:
  - новые метрики активности учитываются в `telemetry coverage` и `telemetry snapshot`.
- Обновлены unit-тесты `TelemetryMetricMapperTest` под новые метрики активности.

## Почему так
- Запрос требовал отдельного отображения именно «вчерашних реальных ISF/CR», а не только общего lookback-профиля.
- Для практического анализа и прогноза нужен видимый и стабильный контур данных по активности (шаги/нагрузка), который теперь отображается в Dashboard и хранится в телеметрии.

## Риски / ограничения
- Качество вчерашнего профиля зависит от плотности событий (correction/meal samples); при малом числе точек блок покажет `insufficient`.
- Локальный нативный сбор шагов через Android sensors/Health Connect в этом этапе не добавлялся; сбор идёт по входящей телеметрии (broadcast/Nightscout/devicestatus).
- `lintDebug` продолжает падать на существующей проблеме `SoonBlockedPrivateApi` в `MainViewModel` (рефлексия `networkSecurityConfigRes`), не относящейся к текущему этапу.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.TelemetryMetricMapperTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
5. Ручная проверка UI:
   - `Safety` → блок `Yesterday real ISF/CR`;
   - `Dashboard` → блок `Physical activity`;
   - `Telemetry` → наличие `distance_km/active_minutes/calories_active_kcal`.

# Изменения — Этап 6: Native local steps/activity collection (on-device sensors)

## Что сделано
- Добавлен нативный локальный сбор активности с `TYPE_STEP_COUNTER`:
  - новый агрегатор метрик `LocalActivityMetricsEstimator`:
    - `steps_count` (за день),
    - `distance_km`,
    - `active_minutes`,
    - `calories_active_kcal`,
    - `activity_ratio`.
  - новый сервисный коллектор `LocalActivitySensorCollector`:
    - регистрирует sensor listener,
    - хранит состояние baseline/active-minutes в `SharedPreferences`,
    - пишет bootstrap snapshot сразу после старта коллектора (даже до первого шага),
    - пишет canonical telemetry в `telemetry_samples` через `TelemetryMetricMapper`,
    - триггерит reactive automation каждые 5 минут.
- Добавлен runtime-permission контур:
  - `AndroidManifest.xml`: `android.permission.ACTIVITY_RECOGNITION`.
  - `MainActivity`: запрос разрешения на Android 10+ и повторный запуск коллектора после grant.
- Подключение в runtime:
  - `AppContainer` создаёт и запускает `LocalActivitySensorCollector`.
  - `BootCompletedReceiver` запускает коллектор после перезагрузки/обновления пакета.
- Добавлены unit-тесты:
  - `LocalActivityMetricsEstimatorTest` (базовый расчёт, active minutes, reset на новый день).
- Установлен debug APK на устройство по USB.
- Улучшена чистота локальной телеметрии:
  - для `source=local_sensor` отключено добавление `raw_*` дублей;
  - добавлена очистка legacy `raw_%` ключей от `local_sensor` при старте коллектора.
- Добавлена activity-диагностика в UI-сводку:
  - статус `ACTIVITY_RECOGNITION` permission,
  - метка свежести локального сенсорного потока.

## Почему так
- Теперь шаги и физнагрузка поступают не только из внешних broadcast/devicestatus, но и напрямую с устройства.
- Это закрывает сценарии, когда внешний источник недоступен, и даёт непрерывный локальный контур телеметрии для прогнозов/автоматики.

## Риски / ограничения
- `TYPE_STEP_COUNTER` обновляется только при движении; если пользователь не двигается, новых local_sensor событий не будет.
- Без `ACTIVITY_RECOGNITION` (Android 10+) коллектор не стартует (fail-safe, без поддельных значений).
- Значения `distance/calories/activity_ratio` являются оценочными и калибруются константами (stride/kcal-per-step), не персональными.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.activity.LocalActivityMetricsEstimatorTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
5. На телефоне:
   - выдать `ACTIVITY_RECOGNITION`,
   - открыть Copilot,
   - пройтись 1-2 минуты,
   - проверить `Telemetry` screen: ключи `steps_count`, `distance_km`, `active_minutes`, `calories_active_kcal`, `activity_ratio` с `source=local_sensor`.
6. Проверка очистки дублей:
   - убедиться, что `SELECT count(*) FROM telemetry_samples WHERE source='local_sensor' AND key LIKE 'raw_%'` возвращает `0`.

# Изменения — Этап 7: Local activity heartbeat (1-minute freshness)

## Что сделано
- В `LocalActivitySensorCollector` добавлен минутный heartbeat-цикл:
  - при старте сенсора запускается фоновая job, синхронизированная на границы минут;
  - раз в минуту выполняется snapshot текущего локального состояния (`steps_count`, `distance_km`, `active_minutes`, `calories_active_kcal`, `activity_ratio`) даже если новых шагов не было;
  - запись идёт в те же канонические telemetry keys (`source=local_sensor`), без `raw_*`.
- При остановке коллектора heartbeat корректно отменяется вместе с sensor listener.

## Почему так
- Ранее при отсутствии движения локальный сенсор мог не выдавать событий долгое время, из-за чего в БД появлялись “дыры” свежести.
- Минутный heartbeat делает поток данных более ровным и улучшает качество последующей аналитики (день/неделя/месяц) и диагностики “stale/fresh”.

## Риски / ограничения
- Обновление происходит, пока процесс приложения жив; при kill процесса heartbeat не работает до следующего старта приложения/сервиса.
- На некоторых устройствах aggressive battery optimizations могут ограничивать фоновые циклы.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.activity.LocalActivityMetricsEstimatorTest" --tests "io.aaps.copilot.data.repository.TelemetryMetricMapperTest"`
3. Открыть приложение на телефоне, не совершать шагов 2-3 минуты.
4. Проверить в `telemetry_samples`, что для `source=local_sensor` появляются новые минутные записи с актуальным timestamp.

# Изменения — Этап 8: hiddenapi log spam fix in TLS compatibility check

## Что сделано
- В `MainViewModel.inspectAapsTlsCompatibility()` удалён рефлексивный доступ к скрытому полю `ApplicationInfo.networkSecurityConfigRes`.
- Диагностика TLS теперь не обращается к hidden API и использует безопасный fallback:
  - `networkSecurityConfigRes = null`,
  - `likelyRejectsUserCa = targetSdk >= 24`.

## Почему так
- На Android 14+ скрытое поле блокируется, что вызывало постоянный `hiddenapi` spam в logcat.
- Этот шум мешал диагностике и мог косвенно влиять на стабильность UI-потока на слабых устройствах.

## Риски / ограничения
- Без скрытого поля нельзя точно определить, задан ли `networkSecurityConfig` у AAPS; используется консервативная эвристика по `targetSdk`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. Установить APK на устройство и открыть Copilot.
3. В `adb logcat` убедиться, что больше нет строк:
   - `hiddenapi: Accessing hidden field ... networkSecurityConfigRes`.

# Изменения — Этап 9: Health Connect activity fallback ingestion

## Что сделано
- Добавлен новый сервисный коллектор:
  - `HealthConnectActivityCollector`:
    - проверяет доступность Health Connect SDK/provider;
    - проверяет runtime-права чтения (`steps`, `distance`, `active calories`);
    - каждые 5 минут читает данные за текущий день и пишет канонические telemetry-ключи:
      - `steps_count`,
      - `distance_km`,
      - `active_minutes`,
      - `calories_active_kcal`,
      - `activity_ratio`,
      - `activity_label`.
    - триггерит reactive automation после успешной записи.
- Добавлен общий helper интенсивности:
  - `ActivityIntensity` (ratio/label из pace), используется и sensor-, и HealthConnect-коллектором.
- Обновлён lifecycle wiring:
  - `AppContainer`, `CopilotApp`, `BootCompletedReceiver`, `MainActivity` теперь запускают Health Connect коллектор.
  - `MainActivity` запрашивает permissions Health Connect через `PermissionController` при доступном SDK.
- UI-сводка активности расширена строкой:
  - `Health Connect stream: ...` с возрастом последней точки или подсказкой про разрешения.
- Добавлена зависимость:
  - `androidx.health.connect:connect-client:1.1.0-alpha12`.
- Добавлены unit-тесты:
  - `ActivityIntensityTest`.

## Почему так
- Локальный `TYPE_STEP_COUNTER` остаётся базой, но на части устройств/сценариев он даёт редкие события.
- Health Connect даёт второй стабильный контур данных по активности, что улучшает полноту телеметрии и последующий анализ.

## Риски / ограничения
- Для Health Connect нужны отдельные runtime permissions и установленный провайдер Health Connect.
- Если права не выданы, коллектор работает в безопасном режиме (не пишет данные) и оставляет статус в audit.
- Используется версия `alpha` клиента, возможны API-изменения при будущих обновлениях.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.activity.ActivityIntensityTest" --tests "io.aaps.copilot.domain.activity.LocalActivityMetricsEstimatorTest"`
3. Установить билд на устройство и открыть приложение.
4. Разрешить Health Connect доступ к шагам/дистанции/калориям (если диалог показан).
5. Проверить `Dashboard -> Physical activity`:
   - строка `Health Connect stream` должна показывать свежий timestamp/age.

# Изменения — Этап 10: Source-aware activity selection and Health Connect status in UI

## Что сделано
- `MainViewModel.latestTelemetryByKey(...)` изменён на source-aware выбор для накопительных activity-метрик:
  - `steps_count`, `distance_km`, `active_minutes`, `calories_active_kcal`:
    - приоритетно выбирается максимум за текущие сутки,
    - fallback на максимум по доступному окну.
- Это устраняет ситуацию, когда более частые точки `local_sensor` с низким значением могли визуально “перекрывать” более полные значения из `health_connect`.
- В `Physical activity` блок добавлена детальная строка статуса Health Connect:
  - используется последний audit-event `health_connect_activity_status`,
  - отображаются причины (`permission_missing`, `sdk_unavailable`, и т.д.) и недостающие permissions.
- `AutomationRepository.resolveLatestTelemetry(...)` синхронизирован по той же логике для накопительных activity-ключей, чтобы downstream-аналитика и автоматика получали корректные значения.

## Почему так
- Для day-cumulative метрик простая стратегия “последний timestamp wins” неустойчива при нескольких источниках.
- Нужна корректная унификация `local_sensor + health_connect` без потери реальных дневных значений.

## Риски / ограничения
- Накопительные ключи агрегируются внутри текущих суток по локальной TZ; при смене TZ в течение дня возможны пограничные эффекты.
- Для `activity_ratio` оставлена стратегия “последняя свежая точка”, т.к. это текущая динамика, а не суточный накопитель.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
3. Установить app на устройство (`:app:installDebug`) и открыть `Dashboard -> Physical activity`.
4. Проверить, что строка `Health Connect stream` показывает явный статус (включая недостающие permissions при отсутствии доступа).

# Изменения — Этап 11: Adaptive controller anti-false-hypo gating

## Что сделано
- В `AdaptiveTempTargetController` усилена логика safety-канала, чтобы избежать ложного повышения temp target при очевидно высокой прогнозной траектории:
  - добавлен weighted low-bound показатель `PctrlLow` (confidence-weighted по CI 5/30/60),
  - добавлен флаг `safetySuppressedByHighTrajectory`, когда все `pred5/pred30/pred60` заметно выше цели (`Tb + 1.0`),
  - `force-high` теперь отключается при `safetySuppressedByHighTrajectory=true`,
  - `hypo-guard` использует `PctrlLow` вместо простого `Pmin` (уменьшение ложных срабатываний от одиночного широкого CI).
- В debug-поля добавлены:
  - `PctrlLow`,
  - `safetySuppressedByHighTrajectory`.

## Почему так
- Ранее safety-ветка могла поднимать target на основании только минимальной границы CI, даже при устойчиво высоком центральном прогнозе.
- Это давало контринтуитивные temp targets выше base при сценариях близких к гипер-направлению.

## Риски / ограничения
- При экстремально шумных CI возможны пограничные переключения между safety/control режимами.
- Порог suppression (`+1.0 mmol/L`) выбран консервативно и может потребовать доп. калибровки по реальным логам.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest"`
2. Проверить новый тест `testSafetySuppressedWhenTrajectoryClearlyHigh_evenIfLowerBoundDips`.
3. В audit/rule reasons контроллера смотреть `PctrlLow` и `safetySuppressedByHighTrajectory`.

# Изменения — Этап 12: Hourly real ISF/CR calculation (yesterday view)

## Что сделано
- Расширен `ProfileEstimator`:
  - добавлен `estimateHourly(...)`,
  - добавлен тип `HourlyProfileEstimate(hour, isf, cr, confidence, isfSamples, crSamples)`.
- Алгоритм hourly использует ту же базовую логику выборки/фильтрации, что и основной профиль:
  - ISF из correction-сценариев с фильтрацией UAM/meal overlap,
  - CR из meal/carb bolus-связок,
  - trim-outliers + median per hour.
- В `Safety Center -> Yesterday real ISF/CR` добавлены почасовые строки:
  - `Hourly history-only (real)` по 24 часам (только часы с данными),
  - `Hourly merged (OpenAPS+history)` для сравнения.
- Добавлен unit-тест:
  - `profileEstimator_buildsHourlyRealIsfCr` в `PatternAndProfileTest`.

## Почему так
- Пользовательский запрос требовал видеть реальный ISF/CR не только агрегированно, но и по часам.
- Почасовой разрез упрощает выявление временных зон чувствительности и ошибок профиля.

## Риски / ограничения
- При малом количестве событий в конкретном часу оценки могут быть шумными; confidence и sample-count выводятся рядом.
- Пока это отображение рассчитывается on-the-fly в UI для вчерашнего окна и не сохраняется отдельной таблицей.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
3. На телефоне открыть `Safety Center` и проверить блок `Yesterday real ISF/CR`:
   - агрегированные строки `History-only`/`Merged`,
   - почасовые строки `Hourly ...`.

## Проверка Health Connect (текущее состояние)
- По USB-аудиту статус остаётся:
  - `health_connect_activity_status = permission_missing`
  - missing: `READ_STEPS`, `READ_DISTANCE`, `READ_ACTIVE_CALORIES_BURNED`
- `telemetry_samples` с `source=health_connect` пока пуст.

# Изменения — Этап 13: Full ISF/CR deep analysis UI + HC postponed

## Что сделано
- Расширен `ProfileEstimator`:
  - добавлен `estimateHourlyByDayType(...)` для расчёта ISF/CR в разрезе `WEEKDAY/WEEKEND` по каждому часу.
- В `MainViewModel` добавлен новый расширенный блок расчёта:
  - `buildIsfCrDeepLines(...)`.
  - Считает окна: `1d / 3d / 7d / 14d / 30d` (history-only и merged).
  - Для 7 дней дополнительно считает:
    - hourly history-only,
    - hourly merged,
    - hourly history-only by day type (`WEEKDAY/WEEKEND`).
- В `Safety Center` добавлен новый UI-блок:
  - `Full ISF/CR analysis`.
- Добавлены тесты:
  - `profileEstimator_buildsHourlyByDayType` в `PatternAndProfileTest`.
- По запросу пользователя временно отложен Health Connect:
  - отключён автозапуск HC-коллектора в `AppContainer` (feature gate `healthConnectEnabled=false`);
  - отключён запрос HC-permissions в `MainActivity`.

## Почему так
- Нужен не только базовый ISF/CR, а полноценный аналитический разрез по времени и типу дня.
- Приоритет текущего этапа — качество расчёта ISF/CR; Health Connect не должен мешать и показывать лишние permission-потоки.

## Риски / ограничения
- Почасовые оценки чувствительны к плотности событий; confidence и sample-count выводятся рядом.
- Deep-analysis считается в UI при обновлении состояния; это дороже по CPU, чем чтение готовых агрегатов из отдельной таблицы.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
3. На телефоне открыть `Safety Center`:
   - блок `Yesterday real ISF/CR`,
   - блок `Full ISF/CR analysis`.
4. Убедиться, что при старте приложения не появляется запрос Health Connect permissions (HC отложен).

# Изменения — Этап 14: IOB freshness fix (negative IOB support)

## Что сделано
- Исправлена фильтрация `iob_units` в телеметрии:
  - `TelemetryMetricMapper` теперь принимает `iob_units` в диапазоне `-30..+30` U (раньше было `0..30`).
- Исправлена legacy-cleanup логика в `BroadcastIngestRepository`:
  - удаление out-of-range для `iob_units` переведено на диапазон `-30..+30`.
- Исправлена свежесть расчетного UAM в `AutomationRepository`:
  - `uam_calculated_carbs_grams/rise15/rise30/delta5` теперь пишутся на каждом цикле,
  - при отсутствии UAM-сигнала пишется `0`, чтобы UI не показывал устаревшие значения.
- Добавлены unit-тесты в `TelemetryMetricMapperTest`:
  - `keepsNegativeIobWithinAllowedRange`,
  - `dropsIobOutsideExtendedNegativeRange`.
- Обновлен debug APK на устройстве по USB и проверено в live DB:
  - `iob_units` снова пишется каждую минуту вместе с `raw_iob`,
  - stale-проблема по IOB устранена.

## Почему так
- В реальных AAPS данных `IOB` может быть отрицательным (например, при отрицательном basal IOB).
- Из-за старого диапазона отрицательные значения отбрасывались и UI/алгоритмы использовали устаревший `iob_units`.
- Это искажало контур прогноза/контроллера и давало неактуальные значения в Telemetry Coverage.
- Аналогично для UAM, поля `delta/rise/carbs` могли “зависать” на старом сигнале, если новый сигнал отсутствовал.

## Риски / ограничения
- Сильно отрицательные выбросы по-прежнему фильтруются (< -30 U).
- В audit всё ещё есть `automation_cycle_skipped` из-за конкурирующих триггеров (reactive + minute-cycle), но цикл регулярно завершается и данные обновляются.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.TelemetryMetricMapperTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
3. На телефоне проверить БД:
   - `SELECT key,valueDouble,datetime(timestamp/1000,'unixepoch','localtime') FROM telemetry_samples WHERE key IN ('iob_units','raw_iob') ORDER BY timestamp DESC LIMIT 10;`
   - ожидание: свежие `iob_units` с отрицательными значениями, timestamp близко к текущему.
4. Проверить UAM freshness:
   - `SELECT key,valueDouble,datetime(timestamp/1000,'unixepoch','localtime') FROM telemetry_samples WHERE key LIKE 'uam_calculated_%' ORDER BY timestamp DESC LIMIT 12;`
   - ожидание: на каждом цикле есть свежие `uam_calculated_*`; при отсутствии активного UAM значения `delta/rise/carbs = 0`.

# Изменения — Этап 15: Reactive automation debounce and cycle stability

## Что сделано
- Добавлен debounce для `WorkScheduler.triggerReactiveAutomation(...)`:
  - повторный reactive enqueue подавляется в окне `45s`;
  - метод теперь возвращает `Boolean` (scheduled/debounced).
- Обновлен `LocalDataBroadcastReceiver`:
  - логирует `broadcast_reactive_automation_enqueued` только при реальном enqueue;
  - при подавлении логирует `broadcast_reactive_automation_skipped` с `reason=debounced`.
- Обновлен `LocalNightscoutServer`:
  - добавлен локальный debounce reactive-trigger `45s`;
  - разделены audit-события:
    - `local_nightscout_reactive_automation_enqueued`,
    - `local_nightscout_reactive_automation_skipped` (`reason=debounced`).
- `automation_cycle_skipped` переведен на уровень `INFO` (это ожидаемая конкуренция, не ошибка).

## Почему так
- До фикса reactive enqueue запускался слишком часто из нескольких источников и создавал лишние конкурентные старты цикла.
- Это давало много `automation_cycle_skipped` и повышало шум/нагрузку без выигрыша по данным.
- Debounce оставляет реактивность, но убирает дубли.

## Риски / ограничения
- В burst-сценариях часть reactive-триггеров намеренно пропускается в окне debounce; актуальность сохраняется за счет минутного цикла и периодического worker.
- При экстремально высокой частоте входящих событий возможны редкие `automation_cycle_skipped`, но они не критичны и не влияют на целостность данных.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:installDebug`
2. На телефоне подождать 2-3 минуты активного потока данных.
3. В БД проверить аудит:
   - `broadcast_reactive_automation_enqueued` и `broadcast_reactive_automation_skipped(reason=debounced)`;
   - `local_nightscout_reactive_automation_enqueued/skipped`.
4. Проверить свежесть ключей:
   - `iob_units`, `cob_grams`, `activity_ratio`, `steps_count`, `uam_calculated_flag` должны иметь age ~0-1m.
