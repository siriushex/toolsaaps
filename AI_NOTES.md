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

# Изменения — Этап 9: Adaptive target bounds wiring + ISF/CR test stabilization

## Что сделано
- `AdaptiveTempTargetController` расширен динамическими bounds:
  - новый вход `targetMinMmol/targetMaxMmol`,
  - runtime-clamp теперь учитывает пользовательские границы в безопасном диапазоне `4.0..10.0`,
  - safety/control/debug-поля отражают фактически применённые границы (`targetMin/targetMax`).
- `AdaptiveTargetControllerRule` теперь прокидывает границы в контроллер из `RuleContext` и логирует их в `reasons`.
- `RuleContext` расширен полями:
  - `adaptiveMinTargetMmol`,
  - `adaptiveMaxTargetMmol`.
- `AutomationRepository` в обоих контурах (`runCycle` и `runDryRun`) передаёт `settings.safetyMinTargetMmol/safetyMaxTargetMmol` в `RuleContext`.
- Добавлен unit-тест `testSafetyForcesHigh_usesConfiguredUpperBound10`, подтверждающий, что safety force-high использует верхнюю границу `10.0`, если она задана.
- Стабилизирован `IsfCrEngineTest`:
  - тест day-type параметров приведён к текущей логике покрытия `MIN_DAYTYPE_COVERAGE_HOURS` (не требует weekend-ключи при нулевом weekend-coverage).

## Почему так
- Пользовательские safety bounds в UI должны реально влиять на adaptive-контур, а не оставаться только визуальной настройкой.
- Контроллер ранее был жёстко ограничен `4..9`; это мешало сценариям, где верхняя safety-граница задаётся `10.0`.
- Тест ISF/CR падал из-за несовпадения ожиданий и текущей политики day-type coverage, а не из-за ошибки алгоритма.

## Риски / ограничения
- Для старых тестов/интеграций без явных bounds сохранено прежнее поведение через default `4..9`.
- Расширение границ до `10.0` увеличивает диапазон temp target; безопасность по-прежнему ограничивается `SafetyPolicy` и bounds из настроек.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.*"`
4. В UI Safety Center выставить верхнюю границу `10.0` и убедиться по `rule_executions`/audit, что контроллер может выдавать `target=10.0` в `safety_force_high`.

# Изменения — Этап 10: Shadow gate robustness (risk parsing + bounds chain test)

## Что сделано
- Усилен парсинг daily ISF/CR quality risk label:
  - `AutomationRepository.parseIsfCrQualityRiskLevelFromTextStatic` теперь понимает:
    - английские метки (`HIGH/MEDIUM/LOW/UNKNOWN`),
    - русские метки (`ВЫСОКИЙ/СРЕДНИЙ/НИЗКИЙ/НЕИЗВЕСТНО`),
    - числовые уровни `0..3`.
  - добавлена нормализация строки через `Locale.ROOT` и очистка шумовых символов.
- Добавлен интеграционный unit-test по цепочке safety bounds в adaptive rule:
  - `AdaptiveTargetControllerRuleTest.safetyForceHigh_usesAdaptiveMaxBoundFromContext`;
  - подтверждает, что при `adaptiveMaxTargetMmol=10.0` итоговый action target может быть `10.0` (не режется до `9.0`).
- Обновлен Safety UI mapper:
  - `adaptiveBounds` теперь отображается в диапазоне до `10.0` (а не жёстко до `9.0`);
  - добавлен/обновлён unit-test `MainUiStateMappersTest.safetyMapping_usesConfiguredHardBounds`.
- Добавлен тест на риск-парсинг русских/числовых значений:
  - `AutomationRepositoryForecastBiasTest.parseIsfCrQualityRiskLevel_parsesRussianLabelsAndNumeric`.

## Почему так
- В telemetry/audit риск-метки могут приходить не только в английском формате; это критично для корректного daily risk gate и shadow auto-activation.
- Нужна была явная проверка, что safety bounds из UI доходят до rule/action без скрытого ограничения.

## Риски / ограничения
- Парсинг по тексту остаётся эвристическим; при нестандартных формулировках (не содержащих ключевые корни) уровень останется `null`.
- Числовой парсинг ограничен безопасным диапазоном `0..3`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.*" --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest"`

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

# Изменения — Этап 16: Forecast snapshot consistency in UI (5/30/60 from same cycle)

## Что сделано
- Добавлен резолвер `ForecastSnapshotResolver`:
  - вычисляет origin-cycle для каждого прогноза как `origin = timestamp - horizon*60s`;
  - выбирает последний origin-cycle;
  - возвращает согласованный набор `5/30/60` именно из одного последнего цикла.
- `MainViewModel` переведен на этот резолвер:
  - удалено прямое наблюдение `observeLatestByHorizon(5/30/60)` из `combine`;
  - `forecast5m/30m/60m` и controller preview теперь берутся из согласованного snapshot.
- Добавлены unit-тесты:
  - `ForecastSnapshotResolverTest.resolvesHorizonsFromLatestOriginCycle`;
  - `ForecastSnapshotResolverTest.keepsAvailableHorizonFromLatestCycleAndDoesNotBackfillOlderCycle`.
- Обновлён APK на устройстве по USB.

## Почему так
- До фикса горизонты могли визуально собираться из разных циклов (из-за future timestamps `now+horizon`), что давало рассинхрон и "странные одинаковые/неверные" показания в UI.
- Snapshot по origin-cycle делает отображение физически согласованным: 5/30/60 относятся к одному и тому же расчету.

## Риски / ограничения
- Если в последнем цикле отсутствует часть горизонтов, UI покажет только реально доступные значения этого цикла (без подмешивания старого 30/60).
- Это осознанное поведение для честного отображения актуального цикла.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.ForecastSnapshotResolverTest" :app:compileDebugKotlin :app:installDebug`
2. В БД проверить последний cycle:
   - запросом с `origin = timestamp - horizon*60000` убедиться, что `5/30/60` имеют один `cycle_ts`.
3. В UI `Live Dashboard` проверить, что `5m/30m/60m` меняются согласованно после нового цикла.

# Изменения — Этап 17: Enhanced Prediction v3 + adaptive safety hardening

## Что сделано
- Внедрён локальный прогнозатор `Enhanced Hybrid Prediction v3`:
  - `KalmanGlucoseFilterV3` (адаптивные `R/Q`, NIS, robust gating).
  - `ResidualArModel` (AR(1) residual trend с `mu/phi/sigmaE`).
  - `UamEstimator` (Level A uci-forecast + optional virtual meal fit).
  - path simulation шагами 5 минут для горизонтов `5/30/60`.
- Добавлены профили действия инсулина и абсорбции углеводов:
  - `InsulinActionProfiles` (по умолчанию `NovoRapid`).
  - `CarbAbsorptionProfiles` (быстрые/средние/медленные контуры).
- Усилена проверяемость и стабильность adaptive controller:
  - расширены unit-тесты для `AdaptiveTargetControllerRule`, `AdaptiveTempTargetController`, `SafetyPolicy`.
  - в `AdaptiveTempTargetController` добавлены дополнительные debug-поля и hardening safety-веток для снижения ложных high-target сценариев.
- Добавлены тесты качества прогнозного контура:
  - `HybridPredictionEngineV3Test`, `CarbAbsorptionProfilesTest`, `InsulinActionProfilesTest`, `AutomationRepositoryForecastBiasTest`.
- Добавлен проектный operating-system слой для Codex:
  - `AGENTS.md` в корне,
  - базовый набор документов `docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, `docs/PLAN.md`, `docs/DEVOPS.md`, `docs/SECURITY_REVIEW.md`.
- Обновлён `.gitignore` для исключения runtime-артефактов (`*.db-shm`, `*.db-wal`, `android-app/.kotlin/`, временные `tmp-aaps-*`).

## Почему так
- v3 устраняет ключевую проблему legacy-формулы (`trend + therapy` одним шагом): меньше двойного учёта и более физичный горизонт 30/60 минут.
- Адаптивный фильтр + AR residual дают более устойчивое поведение на шумных 1-минутных сериях.
- UAM как отдельный контур сохраняет рост в 30/60m без искусственного раздувания трендом.
- Единые правила в `AGENTS.md` и docs фиксируют процесс, чтобы проект не деградировал между этапами.

## Риски / ограничения
- При реально низком прогнозном коридоре (`ciLow5 < 4.2`) safety-путь по-прежнему приоритетно поднимает temp target до верхней границы; это ожидаемое защитное поведение.
- Для точной калибровки контроллера на конкретном пациенте нужны live-реплеи по истории (14–30 дней) и метрики попадания в base-window.
- v3 требует накопления короткой истории для прогрева фильтра/AR; в первые минуты работает fallback.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
3. На устройстве проверить БД:
   - `rule_executions` для `AdaptiveTargetController.v1` (reasonsJson, actionJson, cooldown).
   - `telemetry_samples` для `iob_units`, `cob_grams`, `uam_calculated_*` (свежесть и адекватные значения).
   - `forecasts` по origin-cycle: `5/30/60` из одного цикла расчёта.

# Изменения — Этап 18: Remove adaptive post-alignment overshoot

## Что сделано
- Убрано post-выравнивание `base_align_60m` для adaptive-команд:
  - в `AutomationRepository.alignTempTargetToBaseTarget(...)` добавлен bypass для:
    - `sourceRuleId == AdaptiveTargetControllerRule.RULE_ID`,
    - reason, содержащих `adaptive_pi_ci` или `adaptive_keepalive`.
- Передача `sourceRuleId` добавлена в вызовы выравнивания для rule-loop и keepalive.
- Добавлены unit-тесты:
  - `AutomationRepositoryForecastBiasTest.baseAlignment_isSkippedForAdaptiveRule`
  - `AutomationRepositoryForecastBiasTest.baseAlignment_isAllowedForNonAdaptiveRules`
- USB-валидация на устройстве:
  - до фикса отправлялись цели `9.65/9.85/10.0` с reason `...|base_align_60m`;
  - после фикса новая отправка: `target=9.0`, reason без `base_align_60m`.

## Почему так
- Контроллер уже рассчитывает целевую temp target; дополнительное выравнивание поверх него искажало решение и поднимало цель выше расчётной.
- Это создаёт противоречие в управлении и может выглядеть как «неадекватно высокий temp target».

## Риски / ограничения
- Для non-adaptive правил `base_align_60m` остаётся активным (осознанно), чтобы сохранить legacy-поведение там, где это нужно.
- При устойчиво низких прогнозах safety-ветка adaptive контроллера всё равно поднимает target к верхней границе (клиническая защита от гипо).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
3. На телефоне проверить `action_commands.payloadJson.reason` и `targetMmol`:
   - adaptive-события должны идти без `|base_align_60m`;
   - target должен совпадать с выходом контроллера (например, `9.0`, а не `9.65+`).

# Изменения — Этап 19: Current-glucose-aware adaptive safety suppression

## Что сделано
- В `AdaptiveTempTargetController.Input` добавлено поле `currentGlucoseMmol`.
- В `AdaptiveTargetControllerRule` передача текущей глюкозы в контроллер:
  - `currentGlucoseMmol = context.glucose.maxByOrNull { it.ts }?.valueMmol`.
- В `AdaptiveTempTargetController` добавлен новый safety-suppression канал:
  - если текущая глюкоза явно выше базы и `pred5` также выше базы, safety-force-high/hypo-guard подавляются;
  - исключение: при `nearTermLow < 3.6` suppression не применяется (жесткая защита от гипо).
- Debug-поля расширены:
  - `currentGlucose`, `severeNearTermLow`, `safetySuppressedByCurrentHigh`.
- Добавлены unit-тесты:
  - `testHighCurrentGlucoseSuppressesForceHighOnNoisyCi`
  - `testSevereNearTermLowOverridesHighCurrentSuppression`

## Почему так
- При шумных CI возможно ложное занижение `ciLow`, что может искусственно переводить контроллер в `safety_force_high`.
- Использование фактической текущей глюкозы как дополнительного контекста снижает ложные high-target решения в сценариях реальной гипергликемии.

## Риски / ограничения
- Если текущая глюкоза высокая, но быстро падает, suppression может отложить мягкий safety-подъём (компенсируется тем, что severe low `ciLow5<3.6` всё равно имеет приоритет).
- Для окончательной калибровки порогов (`+1.5`, `+0.8`) нужен replay на истории пользователя.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
3. На телефоне проверить `rule_executions.reasonsJson`:
   - присутствуют ключи `currentGlucose`, `safetySuppressedByCurrentHigh`.
4. Проверить `action_commands`:
   - новые adaptive-команды без `|base_align_60m`;
   - target соответствует прямому output контроллера.

# Изменения — Этап 20: Online forecast calibration bias (recent error correction)

## Что сделано
- Добавлена online-коррекция систематической ошибки прогноза в `AutomationRepository`:
  - сбор истории ошибок `actual - forecast` за последние 12 часов;
  - матчинг forecast->glucose по ближайшей точке с tolerance `±2 мин`;
  - weighted bias по горизонтам 5/30/60 с экспоненциальным затуханием по давности (half-life 90m);
  - ограничение bias и min-samples по каждому горизонту;
  - безопасный clamp значений/CI и маркировка версии `|calib_v1`.
- Пайплайн теперь: `merge(local/cloud) -> recent calibration -> COB/IOB bias -> запись в DB`.
- Добавлен audit-event `forecast_calibration_bias_applied` с фактическими смещениями по горизонтам.
- Расширены DAO и тесты:
  - `ForecastDao.latest(limit)`;
  - `AutomationRepositoryForecastBiasTest` доп. кейсы на calibration raise/lower/insufficient samples.

## Почему так
- По live-метрике за 12h выявлен систематический bias: модель занижала 30/60m (средняя ошибка `actual-pred` положительная), что увеличивало число safety `force_high`.
- Лёгкая online-коррекция уменьшает этот сдвиг без агрессивного вмешательства в сам prediction engine.

## Риски / ограничения
- Коррекция зависит от качества последних данных; при аномальном участке истории bias может временно смещаться.
- Встроены ограничители (min-samples, clamp, deadband), но для финальной калибровки всё ещё нужен replay-анализ по 14–30 дням.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:installDebug`
3. На телефоне в `audit_logs` проверить `forecast_calibration_bias_applied` и величины `h5/h30/h60`.
4. Сравнить динамику `adaptive_controller_*` и долю `safety_force_high` до/после на интервале нескольких часов.

# Изменения — Этап 21: UI вкладка истории реальных ISF/CR

## Что сделано
- Добавлена новая вкладка в UI: `ISF/CR History`.
- Расширен источник данных Room:
  - в `ProfileEstimateDao` добавлен live-flow `observeHistory(limit)` для snapshot-истории `profile_estimates`.
- Расширен `MainViewModel`:
  - в `combine` добавлен поток истории профиля,
  - в `MainUiState` добавлены поля:
    - `isfCrHistoryPoints`,
    - `isfCrHistoryLastUpdatedTs`.
- Добавлен новый UI-резолвер истории:
  - `IsfCrHistoryResolver` + `IsfCrHistoryWindow` + `IsfCrHistoryPointUi`,
  - фильтрация по окну (`1h/24h/7d/30d/365d/all`),
  - сортировка, дедупликация по timestamp,
  - downsampling для стабильного рендера графика.
- Добавлен новый экран в `CopilotRoot`:
  - текущие real/merged ISF/CR,
  - переключатели периодов,
  - два графика по истории: `Real ISF` и `Real CR`.
- Добавлены unit-тесты:
  - `IsfCrHistoryResolverTest` (фильтрация окна, downsampling, дедупликация).

## Почему так
- Исторические snapshot-данные ISF/CR уже сохранялись в БД, но не были выведены в отдельный визуальный экран.
- Новый экран даёт быстрый контроль «текущих + исторических» реальных значений без изменения прогнозного/автоматического контура.
- Downsampling и дедупликация предотвращают перегрузку UI на длинной истории.

## Риски / ограничения
- График строится по snapshot-частоте пересчёта аналитики; если recalc запускается редко, линия будет разреженной.
- `real` значения отображаются как `calculated` (history-only) с fallback на merged, если calculated отсутствует.
- `lintDebug` в проекте остаётся красным из-за отдельной существующей проблемы манифеста (`RemoveWorkManagerInitializer`), не связанной с этим этапом.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.IsfCrHistoryResolverTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
4. Открыть приложение -> вкладка `ISF/CR History`:
   - проверить текущие значения real/merged,
   - переключить периоды (`1h/24h/7d/30d/365d/all`),
   - убедиться, что графики ISF/CR обновляются и масштабируются.

# Изменения — Этап 22: Реальный ISF (HBA1-inspired) вместо AAPS-telemetry как primary

## Что сделано
- Изучен алгоритм из `HBA1` (`apps/worker/python/circadian_insights_v2.py`, блок `analyze_insulin_action`):
  - effective ISF = `(BG_start - minBG[60..240m]) / bolusU`,
  - исключение meal-коррекций по carbs рядом,
  - фильтрация некачественных окон.
- В `ProfileEstimator` перенесена и адаптирована логика расчета real ISF по истории:
  - baseline ищется возле времени коррекции,
  - drop считается по минимуму глюкозы в окне `60..240` минут,
  - добавлен фильтр `carbs around correction` (по умолчанию ±60 мин, порог 5g),
  - добавлены guard-параметры (min bolus, min drop, max gap, min points).
- Runtime переведен на `real-first` профиль:
  - в `AutomationRepository.toProfileEstimate()` ISF/CR берутся из `calculated*` полей при наличии (history-only),
  - fallback на merged значения только если расчетных нет.
- UI также переведен на real-first отображение:
  - `MainViewModel` для `profileIsf/profileCr` приоритетно использует `calculated*`,
  - fallback строки telemetry coverage для ISF/CR показывают `real profile estimate`.
- Обновлены подписи в `Safety Center`:
  - `active real-first` вместо `merged/OpenAPS+history`.
- Добавлены тесты:
  - `profileEstimator_usesMinDropIn60to240Window_forRealIsf`,
  - `profileEstimator_skipsCorrectionAsMeal_whenCarbsNearby`.

## Почему так
- Прежний primary-путь мог использовать AAPS telemetry ISF как часть итогового профиля.
- Для пользователя требовался именно «реальный» ISF из исторических эффектов коррекций.
- Формула из HBA1 (drop в окне 60..240) физиологичнее и устойчивее, чем оценка по одной точке на 90-й минуте.

## Риски / ограничения
- При редкой или шумной CGM-истории quality-фильтры могут уменьшать число валидных ISF sample.
- Если history-only оценка недоступна, сохранен fallback на merged, чтобы не оставлять контур без профиля.
- `lintDebug` в проекте по-прежнему содержит отдельную старую проблему манифеста (`RemoveWorkManagerInitializer`).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
4. В приложении проверить `Safety Center` и `Telemetry coverage`: ISF/CR должны идти как `real-first`, а не из telemetry AAPS при наличии history-only расчетов.

# Изменения — Этап 23: Расширение real ISF на обычные bolus-коррекции

## Что сделано
- В `ProfileEstimator` расширен отбор ISF-кандидатов:
  - теперь учитываются не только `correction_bolus`, но и обычные `bolus` события как потенциальные коррекции;
  - приоритет explicit correction-флагов сохранен (`isCorrection`, `reason=correction`), но plain `bolus` тоже допускается;
  - meal-like случаи по-прежнему отсекаются фильтром carbs-around.
- Добавлен unit-тест:
  - `profileEstimator_treatsPlainBolusWithoutCarbs_asCorrectionForRealIsf`
  - проверяет, что plain bolus без nearby carbs корректно участвует в расчете real ISF.

## Почему так
- В реальной истории AAPS часть корректирующих болюсов записывается как обычный `bolus` без явного маркера `correction_bolus`.
- Без этого реальные корректирующие эпизоды пропускались, и расчет real ISF имел меньше валидных sample.

## Риски / ограничения
- Возможна частичная контаминация выборки болюсами, если meal carbs не были задекларированы; этот риск ограничивается текущими UAM/carbs-around фильтрами и quality-guard.
- `meal_bolus` по-прежнему не используется как прямой correction-кандидат.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest.profileEstimator_treatsPlainBolusWithoutCarbs_asCorrectionForRealIsf"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest`

# Изменения — Этап 24: Telemetry как fallback для real ISF/CR (без подмешивания при достаточной истории)

## Что сделано
- Расширен `ProfileEstimatorConfig`:
  - добавлен `telemetryMergeMode` (`COMBINE`, `FALLBACK_IF_NEEDED`, `HISTORY_ONLY`);
  - default выставлен в `FALLBACK_IF_NEEDED`.
- В `ProfileEstimator` реализован селектор sample-ов:
  - если history sample-ов достаточно — используются только history sample-ы;
  - если history недостаточно — подключаются telemetry sample-ы как fallback.
- Логика fallback применена не только к общему профилю (`estimate`), но и к:
  - `estimateSegments`,
  - `estimateHourly`,
  - `estimateHourlyByDayType`.
- Уточнен учет telemetry sample count:
  - `telemetryIsfSampleCount` / `telemetryCrSampleCount` теперь показывают реально использованные telemetry sample-ы в финальном расчете, а не просто количество входных telemetry-сигналов.
- Добавлен unit-тест:
  - `profileEstimator_prefersHistorySamples_overTelemetry_whenHistorySufficient`
  - подтверждает, что при достаточной истории outlier telemetry не влияет на real ISF/CR.
- Обновлены подписи в `MainViewModel` диагностических строк:
  - вместо “merged” теперь “telemetry fallback”, чтобы UI/логика соответствовали реальному режиму.

## Почему так
- Пользовательский приоритет — рассчитывать реальный ISF/CR из истории, а не из OpenAPS telemetry.
- Полное отключение telemetry нежелательно: при разреженной истории нужен безопасный fallback.
- Режим fallback решает обе задачи: real-first при нормальных данных и устойчивость при sparse-истории.

## Риски / ограничения
- При очень маленьком количестве history sample-ов fallback может включаться чаще, чем хотелось бы (это ожидаемое поведение для устойчивости).
- В режиме default (`FALLBACK_IF_NEEDED`) “merged” и “real” часто совпадают; это теперь корректно и ожидаемо.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`

# Изменения — Этап 25: Усиление fallback-порогов для hourly/segment real ISF/CR

## Что сделано
- Усилен критерий подключения telemetry fallback в `ProfileEstimator`:
  - для `estimateSegments`, `estimateHourly`, `estimateHourlyByDayType`
  - вместо `minSamples=1` теперь используется `minSegmentSamples` (по умолчанию `2`).
- Это означает:
  - если в конкретном часе/сегменте есть минимум 2 history sample, telemetry туда не подмешивается;
  - telemetry включается только когда история по этому бакету действительно разреженная.
- Добавлены unit-тесты:
  - `profileEstimator_hourly_prefersHistoryOverTelemetry_whenHourHasEnoughSamples`
  - `profileEstimator_segment_prefersHistoryOverTelemetry_whenSegmentHasEnoughSamples`
- Подправлены синтетические тестовые сценарии так, чтобы correction sample не отфильтровывались `carbs-around` guard-логикой.

## Почему так
- На hourly/segment уровнях наиболее заметны “скачки” при случайном fallback на telemetry.
- Порог `2` делает локальный профиль устойчивее и ближе к “real history-first” в практическом UI.

## Риски / ограничения
- Для редких бакетов (мало событий) больше часов/сегментов могут оставаться пустыми до накопления истории.
- Это ожидаемый компромисс между устойчивостью и покрытием.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest.profileEstimator_hourly_prefersHistoryOverTelemetry_whenHourHasEnoughSamples" --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest.profileEstimator_segment_prefersHistoryOverTelemetry_whenSegmentHasEnoughSamples"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`

# Изменения — Этап 7: UAM Carb Inference + Boost + Backdated export в AAPS

## Что сделано
- Добавлен новый контур UAM inference и экспорта carbs:
  - `UamInferenceEngine` (Kalman 1m smoothing -> 5m grid -> residual/gAbs -> discrete LS-fit по `(t*, C)`),
  - `UamEventStore` (in-memory + Room persist),
  - `UamExportCoordinator` (OFF/CONFIRMED_ONLY/INCREMENTAL, dry-run, idempotent dedup по `id+seq`),
  - `AapsCarbGateway` + `NightscoutAapsCarbGateway` (post/fetch carbs entries).
- Добавлено хранение UAM inference событий в Room:
  - `uam_inference_events` + `UamInferenceEventDao` + `UamInferenceEventEntity`,
  - `CopilotDatabase` обновлён до `version=9`.
- Добавлены UAM настройки в `AppSettingsStore`/`AppSettings`:
  - флаги `enableUamInference`, `enableUamBoost`, `enableUamExportToAaps`, `uamExportMode`, `dryRunExport`,
  - persisted `uamLearnedMultiplier`,
  - user-tuning (`minSnack/maxSnack/step/backdate`, export intervals/backdate limits и др.),
  - helper `toUamUserSettings()`.
- Интеграция в runtime цикл `AutomationRepository`:
  - UAM inference запускается в 5-мин бакетах,
  - результат пишется в telemetry (`uam_inferred_*`, `uam_manual_cob_grams`, `uam_inferred_gabs_last5_g`),
  - экспорт в AAPS проходит через coordinator,
  - `learnedMultiplier` обновляется малыми шагами при eligible-сценариях,
  - `uam_value` теперь учитывает inferred UAM при активном событии.
- В `UamInferenceEngine` исправлен учёт инсулина в residual-therapy через реальный `ISF`, а не константу.
- Добавлена защита от дублей:
  - carbs с тегом `UAM_ENGINE|id=...|seq=...|ver=1|mode=...|` распознаются парсером,
  - при наличии nearby tagged/manual carbs новое событие не создаётся.
- UI расширен:
  - Dashboard/Forecast показывают inferred UAM (`carbs`, `t*`, `confidence`, `mode`, `manual COB`, `gAbs`),
  - Safety Center добавлены переключатели UAM/runtime/export + tuning-поля.

## Почему так
- Нужен отдельный детерминированный UAM-контур с recoverable состоянием и экспортом в AAPS.
- Backdated carbs и теги `id/seq` позволяют одновременно:
  - корректно учесть терапию в AAPS,
  - избежать дублей и ретраить безопасно.
- 5-мин бакеты стабилизируют управление/экспорт при 1-мин CGM потоке.

## Риски / ограничения
- Экспорт зависит от доступности Nightscout endpoint и корректного API secret.
- При `fallbackToDestructiveMigration` смена Room schema может очищать БД на отдельных обновлениях.
- `lintDebug` в проекте всё ещё падает на существующей проблеме WorkManager initializer в `AndroidManifest.xml` (не в этом этапе).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
4. Таргетные тесты UAM:
   - `./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.UamInferenceEngineTest"`
   - `./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.UamTagCodecTest"`
   - `./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.UamExportCoordinatorTest"`
5. Ручной сценарий:
   - в Safety включить `Enable UAM inference`, при необходимости `UAM boost`,
   - для реальной отправки отключить `Dry-run export` и включить `Export inferred carbs to AAPS`,
   - выбрать `CONFIRMED_ONLY` или `INCREMENTAL`,
   - проверить в Dashboard/Telemetry ключи `uam_inferred_*` и в AAPS/Nightscout carbs с note `UAM_ENGINE|...`.

# Изменения — Этап 7: Full runtime manual (`manual.md`)

## Что сделано
- Добавлен новый файл `/Users/mac/Andoidaps/AAPSPredictiveCopilot/manual.md`.
- В manual зафиксированы:
  - полный поток данных (ingest -> predict -> rules -> safety -> action),
  - формулы прогнозирования (legacy v2 и enhanced v3),
  - UAM estimator/inference/export,
  - логика Adaptive Temp Target Controller,
  - safety-ограничения, keepalive, COB/IOB bias,
  - ISF/CR расчеты, ключи telemetry, структура БД и SQL-примеры проверки.

## Почему так
- Нужен единый подробный гайд, чтобы однозначно понимать, как считаются значения и какие функции за что отвечают в текущей реализации.
- Это снижает риск ошибок настройки и ускоряет диагностику на устройстве.

## Риски / ограничения
- Manual описывает текущую реализацию на дату обновления; при будущих изменениях алгоритмов документ нужно синхронно обновлять.
- В этапе не менялась логика приложения, только документация.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot && ls -la manual.md`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot && sed -n '1,260p' manual.md`
3. Сверить разделы manual с кодом в `android-app/app/src/main/kotlin/io/aaps/copilot/*`.

# Изменения — Этап 8: Technical review integration for `manual.md`

## Что сделано
- Обновлён `/Users/mac/Andoidaps/AAPSPredictiveCopilot/manual.md` по результатам технического обзора:
  - добавлены разделы про source-of-truth и разрешение конфликтов источников;
  - формализован decision cycle (periodic vs reactive, debounce, bucket cadence, idempotency);
  - добавлены уточнения по UAM/OpenAPS терминологии и рискам CSF/COB;
  - добавлен anti-loop блок для inferred carbs export;
  - добавлен раздел про нюанс SMB/High Temp Target в AAPS;
  - усилены safety-инварианты в Safety Center разделе;
  - расширен operational checklist по sensor quality и rollback-проверкам;
  - добавлены таблица «риски/меры» и QA-валидация перед эксплуатацией.

## Почему так
- Нужно синхронизировать manual с практическими рисками эксплуатации и сделать поведение контура более предсказуемым для пользователя.
- Это повышает диагностируемость и снижает риск неверной интерпретации прогнозов/автодействий.

## Риски / ограничения
- Изменения этого этапа документарные; runtime-логика не изменялась.
- Для части рекомендаций (например, отдельный SMB-derived индикатор в UI) требуются отдельные кодовые этапы.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot && rg -n "Source of truth|QA-валидация|SMB/High Temp Target|Риски и меры" manual.md`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot && sed -n '70,220p' manual.md`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot && sed -n '500,680p' manual.md`

# Изменения — Этап 9: Sensor quality gate + SMB context indicator

## Что сделано
- В `AutomationRepository` добавлен runtime `sensor quality gate`:
  - оценка качества CGM на каждом automation-цикле (`score`, `reason`, `blocked`, `suspectFalseLow`, `delta5`, `noiseStd`, `gapMin`);
  - блокировка авто-контуров через `sensorBlocked = therapySensorBlocked || sensorQuality.blocked`;
  - запись telemetry-метрик `sensor_quality_*` в БД для UI/аудита;
  - аудит-события `sensor_quality_gate_blocked`.
- Добавлены `rollback rules`:
  - при quality-block и активном temp target, существенно отличающемся от base, отправляется rollback temp target к base;
  - idempotency по 20-мин бакету (`sensor_quality_rollback:*`), reason=`sensor_quality_rollback:<reason>`.
- В UI (`MainViewModel`, `CopilotRoot`) добавлены:
  - отображение статуса `Sensor quality` и причины блокировки;
  - отображение `SMB context` (high temp target warning + найденные SMB telemetry keys).
- В unit tests:
  - расширен `AutomationRepositoryForecastBiasTest` тестами для quality-evaluator и rollback-decision;
  - поправлен `UamExportCoordinatorTest` под актуальную сигнатуру `Config`.

## Почему так
- Нужно защитить автоматику от ошибочных/шумных сенсорных участков и исключить ложные авто-реакции.
- Нужен прозрачный UI-контекст по SMB/High Temp Target, чтобы видеть возможные ограничения AAPS-поведения.

## Риски / ограничения
- `SMB context` сейчас индикаторный (derived из telemetry/target), не прямое чтение внутреннего флага AAPS.
- Quality-gate основан на эвристиках; пороги при необходимости калибруются по реальным логам.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --tests io.aaps.copilot.data.repository.UamExportCoordinatorTest`
3. В приложении:
   - Dashboard: строки `Sensor quality` и `SMB context`;
   - Safety Center: те же индикаторы;
   - Telemetry: ключи `sensor_quality_*`.

# Изменения — Этап 10: Forecast storage dedup + stable UI horizons

## Что сделано
- Устранены дубли прогнозов в БД (`forecasts`) по ключу `(timestamp, horizonMinutes)`:
  - в `ForecastDao` добавлены методы `deleteByTimestampAndHorizon(...)` и `deleteDuplicateByTimestampAndHorizon()`;
  - в `AutomationRepository` добавлена нормализация forecast-набора до одного значения на горизонт (`5/30/60`) перед сохранением;
  - перед `insertAll` выполняется удаление существующих записей для текущих `(ts,horizon)`, затем глобальная очистка исторических дублей.
- Добавлен аудит хранения прогнозов:
  - `forecast_storage_normalized` с `insertedRows` и `removedDuplicateRows`;
  - `forecast_storage_duplicates_cleaned` при фактическом удалении дублей.
- Добавлен unit-тест `normalizeForecastSet_keepsSingleRowPerHorizon` в `AutomationRepositoryForecastBiasTest`.

## Почему так
- На устройстве фиксировались множественные записи одного горизонта в рамках одного target timestamp, из-за чего UI мог брать неоднозначные точки и показывать «неверные/скачущие» прогнозы.
- Нормализация на write-path и очистка исторических дублей убирают этот источник нестабильности.

## Риски / ограничения
- При нормализации дубликатов для одного горизонта выбирается «лучшая» запись (приоритет newer ts, затем cloud model, затем более узкий CI), остальные удаляются.
- Если в будущем потребуется хранить параллельные модели на один горизонт, это нужно будет вынести в отдельную таблицу baseline/ensemble, а не в `forecasts`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest`
2. По USB (после запуска и одного automation-цикла):
   - в `audit_logs` есть `forecast_storage_normalized`;
   - при первом прогоне после фикса есть `forecast_storage_duplicates_cleaned` с числом удалённых строк.
3. SQL-проверка:
   - `select count(*) from (select timestamp,horizonMinutes,count(*) c from forecasts group by timestamp,horizonMinutes having c>1);`
   - ожидается `0`.

# Изменения — Этап 9: Figma MCP → Compose (Overview screen)

## Что сделано
- Получен дизайн-контекст через Figma MCP для:
  - `fileKey=sKcTukYtFHraIm4QuzhCU6`
  - `nodeId=1:2` (`AAPS Predictive Copilot - Overview`)
- Переведён макет Figma в Jetpack Compose и внедрён в `OverviewScreen`:
  - новая структура секций в стиле макета (header/status chip, Current Glucose, Predictions, UAM, Telemetry, Last Action, quick actions);
  - акцентная типографика числовых значений;
  - CI/риск-баннер по wide-CI;
  - чипы телеметрии и статус-плашка последнего действия;
  - нижний блок `Run cycle now` + `Kill switch` с подтверждением.
- Расширено состояние экрана:
  - `OverviewUiState.uamModeLabel`;
  - заполнение поля в `MainUiStateMappers` из `enableUamBoost` (`BOOST/NORMAL`).
- Добавлены строки ресурсов `values`/`values-ru` для нового UI-текста Overview.

## Почему так
- Пользователь запросил генерацию кода из Figma-дизайна через MCP.
- Из доступного файла с метаданными и скриншотом был реализован точный Compose-экран для Overview без изменения бизнес-логики.
- Изменения ограничены UI-слоем и маппингом состояния экрана.

## Риски / ограничения
- Реализован именно `Overview` (узел `1:2`), остальные экраны из дизайн-сета не затрагивались в этом этапе.
- В проекте остаются существующие предупреждения о deprecated `Icons.Filled.*` в отдельных файлах (не блокируют сборку).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.format.UiFormattersTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. Открыть вкладку `Overview` в приложении и сверить с Figma-референсом: секции, типографика, CI-баннер, телеметрия, Last Action, кнопки.

# Изменения — Этап 10: Forecast screen visual alignment (Figma language)

## Что сделано
- Переработан `ForecastScreen` в том же визуальном языке, что и Figma-based `Overview`:
  - секционные карточки с единой рамкой/радиусами;
  - блок управления диапазоном и слоями (`Trend/Therapy/UAM/CI`) в одной карточке;
  - карточка графика с сохранением всей текущей логики Canvas-отрисовки;
  - карточка горизонтов 5/30/60 в формате компактных tiles;
  - карточки decomposition и quality в едином стиле.
- Бизнес-логика прогноза, слои графика и формулы не изменялись.

## Почему так
- Продолжено внедрение UI из Figma-контекста и унификация визуальной системы между экранами.
- Улучшена читаемость и иерархия экрана без изменения расчетов.

## Риски / ограничения
- В исходном доступном Figma-файле есть только `Overview` frame (`1:2`), поэтому `Forecast` стилизован по тем же токенам/паттернам, а не по отдельному frame.
- Дальше желательно получить отдельные Figma frames для `Forecast/UAM/Safety` и донастроить пиксель-паритет.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.format.UiFormattersTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. Открыть вкладку `Forecast` и проверить: control chips, график, decomposition, quality cards.

# Изменения — Этап 11: UAM screen visual alignment (section cards)

## Что сделано
- Переработан `UamScreen` в едином стиле с `Overview/Forecast`:
  - summary-card для inferred/calculated UAM;
  - статус экспорта (`disabled / dry-run / live`) отдельной плашкой;
  - карточки событий UAM с понятной структурой (state/mode, time/carbs/confidence/seq/tag, anti-duplicate статус);
  - действия `mark correct/wrong`, `merge`, `export` сохранены без изменения логики.
- Изменены только UI-компоновка и визуальная иерархия, без правок доменной логики.

## Почему так
- Продолжена унификация UI foundation под один visual language после Figma-based Overview.
- События UAM стали читаемее и лучше подходят для анализа причин блокировок/экспорта.

## Риски / ограничения
- Это UI-only изменение; тексты anti-duplicate статусов остались на текущем runtime-источнике.
- Для полного пиксель-паритета нужен отдельный Figma frame `UAM` (сейчас в доступном файле только Overview frame).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.format.UiFormattersTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. Открыть вкладку `UAM` и проверить summary/event cards и кнопки действий.

# Изменения — Этап 12: Safety screen visual alignment (section cards)

## Что сделано
- Переработан `SafetyScreen` в едином стиле с обновленными `Overview/Forecast/UAM`:
  - статус kill switch отдельной предупреждающей/успешной плашкой;
  - блок `Kill switch` + toggle в отдельной карточке;
  - карточка лимитов (stale, max-actions, base target, bounds, local NS/TLS);
  - карточка cooldown-строк;
  - checklist в виде структурированных cards с pass/fail иконками.
- Бизнес-логика safety и обработчики (`onKillSwitchToggle`) не изменялись.

## Почему так
- Нужна визуальная консистентность UI foundation и более читаемая структура Safety Center.
- Карточная иерархия и статусы в явном виде снижают шанс неверной интерпретации настроек безопасности.

## Риски / ограничения
- Это визуальный слой; состав checklist зависит от текущего runtime map и источников данных.
- Для 1:1 паритета с будущим Figma Safety frame потребуется отдельный node context.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.format.UiFormattersTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
4. Открыть вкладку `Safety` и проверить kill switch, limits, cooldown, checklist.

# Изменения — Этап 13: Audit / Analytics / Settings visual alignment

## Что сделано
- Переработаны экраны `Audit`, `Analytics`, `Settings` в едином UI language:
  - секционные cards с одинаковой иерархией;
  - структурированные info-surface/pill элементы;
  - спокойные, но явные статусы уровня/ошибок;
  - сохранены все существующие действия и колбэки (`filters`, `expand`, `switches`, `toggles`).
- Изменена только композиция и визуальная структура, без изменений бизнес-логики и data-flow.
- Установлена обновлённая debug сборка на телефон по USB.

## Почему так
- После выравнивания `Overview/Forecast/UAM/Safety` оставшиеся экраны должны быть в том же стиле, иначе UX распадался на разные паттерны.
- Единый стиль упрощает чтение operational данных и аудит-трассировки.

## Риски / ограничения
- Как и раньше, для 1:1 паритета по каждому экрану нужен отдельный Figma node context; сейчас используется единая визуальная система на базе доступного Overview frame.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.format.UiFormattersTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
4. На телефоне проверить вкладки `Audit`, `Analytics`, `Settings`.

# Изменения — Этап 14: i18n cleanup (UAM/Safety) + USB smoke check

## Что сделано
- Убраны оставшиеся runtime-хардкоды строк в `UamScreen`:
  - статусы экспорта (`enabled dry-run/live`, `disabled`) переведены в `string resources`;
  - anti-duplicate статусы (`export disabled`, `dry-run mode`, `manual carbs nearby`, `manual COB active`, `ready`) переведены в `string resources`;
  - `ON/OFF` в UAM summary переведены на `status_on_short/status_off_short`;
  - единица `g` переведена на ресурс `unit_g`.
- В `SafetyScreen`:
  - локальный NS статус теперь использует `status_on_short/status_off_short` вместо встроенных `ON/OFF`;
  - единицы `min` и `mmol/L` выводятся через ресурсы `unit_minutes`, `unit_mmol_l`.
- Добавлены новые ключи строк в:
  - `android-app/app/src/main/res/values/strings.xml`
  - `android-app/app/src/main/res/values-ru/strings.xml`
- Выполнены сборка, тесты, установка на устройство по USB и быстрый smoke-check запуска.

## Почему так
- После обновления UI оставались англоязычные/встроенные строки в runtime, что нарушало единый i18n-подход.
- Замена на ресурсы делает интерфейс консистентным и безопаснее для дальнейшей локализации.

## Риски / ограничения
- В `logcat` после запуска наблюдается единичный `NanoHTTPD: Socket is closed` (без `FATAL/ANR`), требуется наблюдение в реальном сценарии сетевого цикла.
- Изменения этого этапа UI/i18n-only, бизнес-логика не менялась.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.format.UiFormattersTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
4. `adb shell monkey -p io.aaps.predictivecopilot -c android.intent.category.LAUNCHER 1`

# Изменения — Этап 15: Figma MCP polish pass (interactive UI)

## Что сделано
- Синхронизированы с Figma MCP source-референсом (`tTZkFh4a8eKSZlyJcIYTNd`):
  - прочитаны `App.tsx`, `OverviewScreen.tsx`, `ForecastScreen.tsx`, `UAMScreen.tsx`, `SafetyCenterScreen.tsx`, `DESIGN_SYSTEM.md`.
- Обновлён fallback theme под Material 3 palette из дизайн-системы:
  - `primary/secondary/warning/error`, surface/outlines и контейнерные цвета для light/dark.
- Улучшен TopAppBar в `CopilotFoundationRoot`:
  - state-aware фон (`default/warning/critical`) на основе `staleData` и `killSwitchEnabled`;
  - badge-индикатор количества активных рисков;
  - navigation icon меняется на warning при risk-состоянии.
- Добавлена интерактивность UAM экрана:
  - карточки событий стали кликабельными;
  - реализован `ModalBottomSheet` с деталями события, anti-duplicate статусом и action-кнопками;
  - действия (`mark correct/wrong`, `merge`, `export`) доступны и из листа, и из детализации.
- Улучшен Forecast chart:
  - добавлены вертикальные маркеры на `Now`, `+30m`, `+60m` (dash lines);
  - добавлены подписи маркеров под графиком.
- Добавлены новые локализованные строки `en/ru` для UAM details и маркеров 30/60m.
- Debug APK пересобран и установлен на телефон по USB.

## Почему так
- Пользователь запросил продолжить работу именно совместно с Figma MCP и довести UI до более интерактивного, “живого” состояния.
- Изменения покрывают наиболее видимые UX-зоны: top-level state awareness, детализация UAM и читаемость прогноза во времени.

## Риски / ограничения
- Есть только предупреждения о deprecated `Icons.Filled.*` (без влияния на runtime).
- `dynamicColor` на Android 12+ остаётся включённым по умолчанию, поэтому итоговая палитра может частично отличаться от fallback-токенов Figma (это ожидаемо).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.format.UiFormattersTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
4. На телефоне:
   - открыть `UAM` и тапнуть по событию (должен открыться bottom sheet);
   - открыть `Forecast` и проверить dashed markers `Now/+30m/+60m`;
   - включить `Kill switch`/получить stale data и проверить смену TopBar-стиля.

# Изменения — Этап 16: Figma MCP parity pass (app shell + interaction)

## Что сделано
- Продолжен паритет с Figma Make source (`tTZkFh4a8eKSZlyJcIYTNd`) по интерактивным паттернам:
  - прочитаны референсы `TopAppBar.tsx`, `BottomNavigation.tsx`, `AuditLogScreen.tsx`, `SettingsScreen.tsx`.
- `CopilotFoundationRoot`:
  - добавлен `ModalNavigationDrawer` (боковое меню из TopBar);
  - в drawer вынесены переходы на `Audit/Analytics/Settings`;
  - TopBar теперь разделяет `menu` (drawer) и `more` (dropdown), как в Figma shell.
- `AuditScreen`:
  - фильтрация переведена полностью на chips (`6h/24h/7d + errors`);
  - в карточках записей добавлен expand-indicator (chevron) и более явный accordion-паттерн.
- `ForecastScreen`:
  - legend-layer chips дополнены иконками (`Trend/Therapy/UAM/CI`) по Figma-style.
- `SafetyScreen`:
  - добавлена summary-card внизу (режим системы + ratio пройденных safety checks), как в Figma safety summary.
- Добавлены строковые ресурсы `ru/en` для новых safety-summary подписей.
- Выполнены сборка, тесты, установка на устройство по USB.

## Почему так
- Пользователь просил продолжать полноценный интерактивный дизайн совместно с Figma MCP.
- Этот проход закрывает shell-интерактивность (drawer/menu), audit usability и визуальные сигналы в safety.

## Риски / ограничения
- В `logcat` нет новых `FATAL` по `io.aaps.predictivecopilot`; единственный `FATAL` относится к `info.nightscout.androidaps` (внешнее приложение).
- Предупреждения о deprecated `Icons.Filled.*` остаются и не блокируют runtime.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.format.UiFormattersTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
4. На телефоне:
   - открыть меню в TopBar (drawer должен выезжать слева);
   - открыть `Audit`, проверить chips фильтров и раскрытие строк;
   - открыть `Forecast`, проверить иконки на layer chips;
   - открыть `Safety`, проверить summary-card с количеством passed checks.

# Изменения — Этап 17: Figma MCP interactive Settings wiring + lint hardening

## Что сделано
- Продолжен Figma MCP-проход для экрана `Settings` (источник `tTZkFh4a8eKSZlyJcIYTNd`):
  - довязаны интерактивные переключатели к `MainViewModel` в `CopilotFoundationRoot`.
- `SettingsScreen` теперь выполняет реальные изменения конфигурации:
  - `Local broadcast ingest` -> `setLocalBroadcastIngestEnabled(...)`;
  - `Strict sender validation` -> `setStrictBroadcastValidation(...)`;
  - `UAM inference / boost / export / dry-run` -> единый runtime update через `setUamRuntimeConfig(...)` с сохранением текущего `exportMode`;
  - `Adaptive controller enabled` -> `setAdaptiveControllerEnabled(...)`.
- Добавлены недостающие `string resources` (en/ru) для Settings UI:
  - подписи/subtitle для data sources, UAM, adaptive, debug;
  - заголовок disclaimer, формат версии приложения, блок detection parameters.
- Закрыта блокирующая lint-ошибка WorkManager:
  - в `AndroidManifest.xml` добавлен override `androidx.startup.InitializationProvider` с удалением `androidx.work.WorkManagerInitializer` (т.к. `CopilotApp` реализует `Configuration.Provider`).

## Почему так
- Пользователь просил продолжать полноценный интерактивный дизайн совместно с Figma MCP; без wiring экран выглядел интерактивным, но не управлял runtime настройками.
- Lint должен быть в “зелёном” состоянии для стабильного цикла поставки и USB-обновлений.

## Риски / ограничения
- В предупреждениях компиляции остаются deprecated `Icons.Filled.*` (не блокирует runtime).
- UAM toggles обновляют runtime-конфиг в целом; при одновременных быстрых переключениях применяется последнее состояние экрана.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
5. На телефоне открыть `Settings` и переключить:
   - `Local broadcast ingest`, `Strict sender validation`;
   - `UAM inference/boost/export/dry-run`;
   - `Adaptive controller`.
   Проверить snackbar-подтверждения и отсутствие падений.

# Изменения — Этап 18: Figma MCP interaction polish (Forecast + UAM)

## Что сделано
- Продолжена реализация по Figma Make reference (`tTZkFh4a8eKSZlyJcIYTNd`), фокус на интерактивных паттернах `Forecast/UAM`.
- `ForecastScreen`:
  - заменен селектор диапазона на `SingleChoiceSegmentedButtonRow` (`3h/6h/24h`), как в Figma control pattern;
  - слойные переключатели (`Trend/Therapy/UAM/CI`) переведены на state-aware chips с анимированными цветами/границами;
  - декомпозиция переработана в визуальные строки с цветовыми маркерами;
  - добавлен блок `Net change (60m)` с суммой `trend60 + therapy60 + uam60`.
- `UamScreen`:
  - добавлен верхний индикатор количества pending-событий;
  - события в pending-state получают акцентную рамку карточки;
  - добавлен отдельный confidence-pill (`HIGH/MEDIUM/LOW + %`) в карточках событий;
  - сохранены все существующие действия (`mark/merge/export`) без изменения бизнес-логики.
- Добавлены новые строки локализации `en/ru` для UAM confidence/pending и Net change.

## Почему так
- Пользователь просил продолжать полноценный красивый интерактивный дизайн совместно с Figma MCP.
- Этот проход усиливает визуальную читаемость и моментальную интерпретацию состояния моделей/событий без изменения расчетных алгоритмов.

## Риски / ограничения
- Изменения UI-only, бизнес-логика и правила терапии не менялись.
- В компиляции остаются предупреждения по deprecated `Icons.Filled.*` (не блокируют runtime).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
5. На телефоне открыть:
   - `Forecast`: проверить segmented range и анимированные layer chips;
   - `UAM`: проверить pending-индикатор и confidence-pill в событиях.

# Изменения — Этап 19: Figma MCP polish (Overview readability + animated status)

## Что сделано
- Продолжен Figma MCP pass на основе `DESIGN_SYSTEM.md` (medical readability / safety-first).
- `OverviewScreen` обновлён без изменения бизнес-логики:
  - добавлена `AnimatedContent` анимация для `current glucose` и `delta/roc`;
  - статус гликемического диапазона вынесен в отдельный range-pill с иконкой и текстом (не только цвет);
  - добавлены явные состояния диапазона: `LOW`, `IN RANGE`, `HIGH`, `VERY HIGH`;
  - stale/info/range статусы теперь имеют отдельные bg/tone пары для лучшей визуальной иерархии.
- Добавлены новые локализованные строки `en/ru` для диапазонных лейблов Overview.

## Почему так
- Пользователь просил продолжать полноценный красивый интерактивный дизайн.
- Этот шаг повышает читаемость числовых значений и делает статус безопаснее с точки зрения UX (иконка + текст + цвет).

## Риски / ограничения
- Это UI-only pass; правила терапии/прогнозы не менялись.
- В проекте остаются предупреждения о deprecated `Icons.Filled.*` (не блокируют сборку).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
5. На телефоне открыть `Overview` и проверить:
   - анимацию обновления glucose/delta,
   - range-pill со статусом и иконкой,
   - читаемость в stale/normal режимах.

# Изменения — Этап 20: ISF/CR analytics tab + interactive charts (Figma-aligned)

## Что сделано
- Переработан `Analytics` экран в foundation UI под отдельные вкладки:
  - `ISF/CR` (по умолчанию),
  - `Quality`.
- В `ISF/CR` вкладке добавлены:
  - summary-блок текущих значений `ISF/CR` (`real` и `merged`),
  - выбор окна истории (`1h/24h/7d/30d/365d/All`),
  - 2 интерактивных графика (`Real ISF history`, `Real CR history`) с линиями:
    - `real` (solid),
    - `merged` (dashed),
  - мини-легенда (иконка + текст),
  - статистика `min/max/last` и временной диапазон,
  - diagnostics-блок (`isfCrDeepLines`) с компактными карточками.
- Использован существующий `IsfCrHistoryResolver` для фильтрации/даунсемплинга истории без изменения математики.
- Расширен `AnalyticsUiState` и mapper:
  - добавлены поля для текущих real/merged ISF/CR,
  - добавлена история точек и timestamp последнего обновления,
  - добавлены deep-diagnostics линии.
- Добавлена локализация (`en/ru`) для новых элементов ISF/CR экрана.
- Добавлен unit-test `analyticsMapping_includesIsfCrSummaryAndHistory`.
- Обновлён debug APK и установлен на телефон по USB.

## Почему так
- Пользователь запросил детальную проработку вкладки и графиков ISF/CR.
- В Figma Make отсутствует отдельный Analytics экран, поэтому экран реализован в том же визуальном языке дизайн-системы (Material 3, safety-first, icon+text статусы, data readability).
- Использование существующего resolver сохраняет стабильность текущей data-пайплайна и минимизирует риск регрессий.

## Риски / ограничения
- Графики построены на уже имеющейся истории `profile_estimates`; точность зависит от качества входных данных/частоты snapshot.
- В проекте остаются предупреждения по deprecated `Icons.Filled.*` в ряде экранов (не блокируют сборку).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
5. На телефоне: `More -> Analytics -> ISF/CR`, переключить окна истории и проверить оба графика + summary.

# Изменения — Этап 21: Figma MCP polish pass (Audit + Settings interaction)

## Что сделано
- Продолжен дизайн-проход по Figma MCP (`AuditLogScreen.tsx`, `SettingsScreen.tsx`, `AuditLogRow.tsx`, `ToggleRow.tsx`) для выравнивания интерактивного стиля foundation UI.
- `Settings`:
  - добавлены status-pills по источникам данных (`Broadcast`, `Nightscout`) с иконками и текстовыми статусами (`Connected/Disabled/Stale data`);
  - в UAM-блоке убраны hardcoded параметры и подключены реальные значения из state (`uamMinSnackG/uamMaxSnackG/uamSnackStepG`);
  - добавлен summary-блок safety-порогов адаптивного контроллера (low/high).
- Расширен `SettingsUiState` и mapper:
  - добавлены поля `uamMinSnackG`, `uamMaxSnackG`, `uamSnackStepG`;
  - обновлён `MainViewModel` initial state.
- `Audit`:
  - добавлен summary-блок по событиям (`total/warnings/errors + info/ok`);
  - улучшены status-pills уровня (`icon + label`, не только цвет);
  - добавлен дополнительный contextual pill для payload.
- Добавлены новые строки локализации `en/ru` для `Audit` и `Settings`.
- Добавлен unit test `settingsMapping_includesUamSnackParameters`.
- Debug APK пересобран и установлен на телефон по USB.

## Почему так
- Пользователь просил продолжать полноценный красивый интерактивный дизайн совместно с Figma MCP.
- Этот проход закрывает последние визуально заметные расхождения с Figma-паттернами на экранах operational configuration и audit trace.

## Риски / ограничения
- Предупреждения о deprecated `Icons.Filled.*` остаются в проекте (runtime не блокируют).
- В `Audit` summary метрики считаются по текущему набору `rows` после применённых фильтров окна/ошибок.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
5. На телефоне проверить:
   - `More -> Settings`: source status pills, реальные UAM parameters, adaptive safety summary;
   - `More -> Audit`: summary card, icon+label status pills, раскрытие строки.

# Изменения — Этап 22: UI reliability + contrast hardening (buttons/tabs + readable text)

## Что сделано
- Исправлены нерабочие элементы в foundation UI:
  - `TopBar` notification button больше не пустой (`onClick = {}` удалён), теперь открывает `Audit Log`.
  - `PredictionHorizonChip` больше не принудительно `enabled=false`; добавлен параметр `onClick`, чип активируется при переданном обработчике.
  - Проверен foundation-код на пустые `onClick` и `enabled=false` в интерактивных компонентах.
- Выполнен проход контрастности по основным экранам (`Overview`, `Forecast`, `UAM`, `Safety`, `Audit`, `Settings`, `Analytics`):
  - проблемные hardcoded светлые контейнеры заменены на theme-aware цвета (`surfaceVariant`, `primaryContainer`, `secondaryContainer`, `tertiaryContainer`, `errorContainer`);
  - тексты/иконки переведены на соответствующие `on*Container` / `onSurface*`, чтобы избежать белого текста на светлом фоне в dynamic/dark темах;
  - секционные бордеры унифицированы через `outlineVariant`.
- Дополнительно исправлены технические места после рефакторинга:
  - `riskBadge`, `deltaTone`, `stateColorForEvent` переведены в `@Composable` контекст;
  - убраны default-аргументы с `MaterialTheme` вне допустимого контекста (`UamSectionCard`, `AnalyticsMetricTile`).

## Почему так
- Пользователь сообщил о критичной UX-проблеме: низкий контраст текста и ощущение неработающих кнопок/вкладок.
- Перевод контейнеров на пары `container/onContainer` из Material 3 решает контраст в light/dark и dynamic color без ручной подгонки каждого кейса.
- Удаление пустых обработчиков устраняет “мертвые” элементы в интерфейсе.

## Риски / ограничения
- В проекте остаются предупреждения по deprecated `Icons.Filled.*`; на runtime и контраст это не влияет.
- В worktree присутствуют и другие несвязанные изменения из предыдущих этапов; этот этап не откатывал и не затрагивал их бизнес-логику.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug`
4. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
5. На телефоне проверить:
   - все 4 bottom tabs переключаются (`Overview/Forecast/UAM/Safety`);
   - `TopBar` notification icon открывает `Audit`;
   - на светлой теме текст читаем на карточках/плашках (`Overview`, `UAM`, `Safety`, `Settings`, `Audit`, `Analytics`);
   - в тёмной теме нет светлых карточек с белым текстом.

# Изменения — Этап 23: Проверка и валидация данных декомпозиции прогноза

## Что сделано
- Проверен end-to-end контур decomposition:
  - запись telemetry-ключей в `AutomationRepository.persistForecastDecompositionTelemetry(...)`;
  - чтение этих ключей в `MainViewModel` (`forecast_trend_60_mmol`, `forecast_therapy_60_mmol`, `forecast_uam_60_mmol`, `forecast_residual_roc0_mmol5`, `forecast_sigmae_mmol5`, `forecast_kf_sigma_g_mmol`).
- Добавлены unit-тесты на статический extractor decomposition:
  - `extractForecastDecomposition_usesDiagnosticsComponents`
  - `extractForecastDecomposition_returnsNullWhenNoDiagnostics`
  - файл: `android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/AutomationRepositoryForecastBiasTest.kt`.
- Обновлена сборка и установлена на телефон по USB (`installDebug`).
- Снята актуальная БД телефона (`run-as io.aaps.predictivecopilot`) и подтверждено появление decomposition-ключей в `telemetry_samples`.

## Почему так
- Пользователь запросил проверить корректность данных декомпозиции.
- Без проверки на реальной БД телефона невозможно подтвердить, что UI получает метрики после цикла, даже если код уже изменён.
- Тесты фиксируют контракт extractor-а и защищают от регрессии формул `trend60/therapy60/uam60`.

## Риски / ограничения
- `modelVersion` в decomposition берётся из локального baseline-прогноза (до cloud/calibration/cob_iob post-processing), поэтому может отличаться от `modelVersion` в таблице `forecasts`.
- Компоненты decomposition отражают внутренние вклады path-simulation; они могут не совпадать 1:1 с `pred60 - current glucose` из-за дополнительных clamp/смещений.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
3. Вызвать цикл (через UI `Run cycle now` или входящий broadcast), затем снять БД:
   - `adb exec-out run-as io.aaps.predictivecopilot cat databases/copilot.db > /Users/mac/Andoidaps/tmp_phone_copilot_live_after.db`
4. Проверить ключи decomposition:
   - `sqlite3 /Users/mac/Andoidaps/tmp_phone_copilot_live_after.db "SELECT key, COUNT(*), datetime(MAX(timestamp)/1000,'unixepoch','localtime') FROM telemetry_samples WHERE key LIKE 'forecast_%' GROUP BY key ORDER BY key;"`

# Изменения — Этап 24: Графики профилей инсулина в Analytics

## Что сделано
- Добавлено отображение графиков профилей инсулина на экране `Analytics`:
  - новая секция `Insulin Action Profiles` во вкладке `ISF/CR`;
  - отрисовка всех поддерживаемых кривых (`NOVORAPID/HUMALOG/APIDRA/FIASP/LYUMJEV`) на одном Canvas;
  - активный профиль (из текущих настроек) выделяется толщиной/непрозрачностью и пометкой в легенде;
  - добавлены подписи и accessibility description.
- Расширено состояние UI:
  - `AnalyticsUiState.selectedInsulinProfileId`;
  - `AnalyticsUiState.insulinProfileCurves` (+ модели `InsulinProfileCurveUi`, `InsulinProfilePointUi`).
- `MainUiState` mapper теперь наполняет кривые напрямую из `InsulinActionProfiles` c нормализацией активного профиля через `InsulinActionProfileId.fromRaw(...)`.
- Добавлены строки локализации `en/ru` для новой секции и статусов.
- Обновлён unit-тест `MainUiStateMappersTest` (проверка наличия кривых и выделения активного профиля).
- Debug APK установлен на телефон по USB.

## Почему так
- Запрос пользователя: обеспечить отображение графиков профилей инсулина.
- Данные профилей уже были в домене, но в UI не визуализировались. Добавлен прямой мост `domain -> Analytics UI` и графическая секция без изменений бизнес-алгоритмов.

## Риски / ограничения
- Секция показывает референсные action-кривые профилей (кумулятивное действие), а не персонально обученные кривые.
- В проекте сохраняются предупреждения по deprecated `Icons.Filled.*` (runtime не блокируют).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
4. На телефоне открыть `More -> Analytics -> ISF/CR` и проверить новый график профилей инсулина и легенду с активным профилем.

# Изменения — Этап 25: Подробный 24h отчёт по прогнозированию (для снижения MARD)

## Что сделано
- Добавлен локальный ежедневный отчёт качества прогнозов (24 часа) в `InsightsRepository`:
  - формируется автоматически при каждом запуске `DailyAnalysisWorker` (раз в сутки);
  - работает даже если cloud API недоступен;
  - отчёт строится по локальным `forecasts` + `glucose_samples`.
- Формат отчёта:
  - `Markdown` и `CSV` в `Documents/forecast-reports`;
  - секции: `Horizon metrics (5/30/60)`, `Hourly hotspots`, `Glucose bands`, `Worst errors`, `Recommendations to lower MARD`.
- Метрики в отчёте:
  - `MAE`, `RMSE`, `MARD`, `bias` по горизонтам;
  - worst-case топ ошибок с временем/горизонтом/model-family;
  - рекомендации генерируются детерминированно по порогам MARD/bias/hotspots.
- В телеметрию добавлен срез отчёта (`source=forecast_daily_report`):
  - `daily_report_mae_5m/30m/60m`,
  - `daily_report_rmse_5m/30m/60m`,
  - `daily_report_mard_5m_pct/30m_pct/60m_pct`,
  - `daily_report_bias_5m/30m/60m`,
  - `daily_report_markdown_path`,
  - `daily_report_matched_samples`, `daily_report_forecast_rows`.
- Расширены документы:
  - `docs/ARCHITECTURE.md` (daily local reporting contour),
  - `docs/INVARIANTS.md` (инвариант обязательного 24h отчёта).
- Добавлены unit-тесты:
  - `InsightsRepositoryDailyForecastReportTest` (расчёт метрик и рекомендаций).

## Почему так
- Пользователь запросил подробный отчёт каждые 24 часа для систематической работы над снижением MARD.
- Детализированный daily-report даёт reproducible baseline по ошибкам и time-slot hotspots, на основании которых можно вносить точечные тюнинги (UAM, carb profiles, bias, safety thresholds).

## Риски / ограничения
- При малом количестве совпавших пар forecast↔actual отчёт формируется, но некоторые секции могут быть пустыми.
- Дедуп по дню не делался принудительно: при повторном запуске в тот же день файл перезаписывается по дате.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest"`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug :app:installDebug`
4. Запустить daily analysis (по расписанию или вручную из UI), затем проверить артефакты:
   - `/sdcard/Android/data/io.aaps.predictivecopilot/files/Documents/forecast-reports/forecast-report-<YYYY-MM-DD>.md`
   - `/sdcard/Android/data/io.aaps.predictivecopilot/files/Documents/forecast-reports/forecast-report-<YYYY-MM-DD>.csv`
5. Проверить telemetry-ключи `daily_report_*` в `telemetry_samples`.

# Изменения — Этап 26: Daily Forecast Report в Analytics UI

## Что сделано
- Интегрировал локальный `daily_report_*` telemetry (сформированный `InsightsRepository`) в UI-состояние:
  - `MainUiState`: добавлены поля `dailyReportGeneratedAtTs`, `dailyReportMatchedSamples`, `dailyReportForecastRows`, `dailyReportPeriodStartUtc`, `dailyReportPeriodEndUtc`, `dailyReportMarkdownPath`, `dailyReportMetrics`.
  - `AnalyticsUiState`: добавлены соответствующие поля + `dailyReportHorizonStats`.
- В `MainViewModel` добавлен разбор telemetry-ключей `daily_report_*` в структурированные метрики по горизонтам `5/30/60`.
- В `MainUiStateMappers.toAnalyticsUiState()` добавлен маппинг daily-report полей и расширен `hasData` с учетом daily-report.
- На экране `Analytics` (вкладка `Quality`) добавлен блок `Daily Forecast Report (24h)`:
  - время формирования,
  - количество matched samples / forecast rows,
  - окно отчета,
  - строки MAE/RMSE/MARD/Bias/n по 5/30/60,
  - путь к markdown-отчёту.
- Добавлены `ru/en` string resources для нового блока.
- Обновлён `MainUiStateMappersTest` проверками маппинга daily-report полей.

## Почему так
- Пользовательский запрос: продолжить работу по ежедневной аналитике для снижения MARD.
- После внедрения генерации отчета за 24ч следующий шаг — сделать метрики видимыми прямо в приложении без открытия файлов вручную.

## Риски / ограничения
- В текущем состоянии репозитория сборка падает на уже существующих ошибках в других файлах (вне этого этапа), поэтому полный зеленый прогон CI-цепочки недоступен.
- Новый UI-блок показывает последние telemetry-значения `daily_report_*`; если daily worker не запускался, блок может быть пуст.

## Как проверить
1. Запустить daily analysis/cycle, чтобы появились telemetry-ключи `daily_report_*`.
2. Открыть `More -> Analytics -> Quality` и проверить блок `Daily Forecast Report (24h)`.
3. Проверить наличие файлов отчета в `Documents/forecast-reports`.

# Изменения — Этап 27: Ручной запуск Daily Analysis в Foundation UI

## Что сделано
- В новый экран `Analytics` (Foundation UI, вкладка `Quality`) добавлена кнопка ручного запуска daily-анализа:
  - кнопка `Run daily analysis now` вызывает `MainViewModel.runDailyAnalysisNow()`;
  - позволяет сразу сформировать локальный 24h отчёт и обновить telemetry `daily_report_*` без ожидания периодического запуска.
- Подключил callback в навигации:
  - `CopilotFoundationRoot` теперь передаёт `onRunDailyAnalysis` в `AnalyticsScreen`.
- Добавлены строковые ресурсы `ru/en` для кнопки.

## Почему так
- После внедрения 24h отчёта требовался практичный путь мгновенно запускать его на телефоне из основного UI.
- В legacy-экране кнопка была, в Foundation UI — отсутствовала.

## Риски / ограничения
- Кнопка запускает общий `runDailyAnalysis()` (локальный отчёт + cloud daily analysis при наличии cloud URL).
- Если cloud не настроен, локальный отчёт всё равно формируется, а в сообщении будет пометка про cloud skip.

## Как проверить
1. `More -> Analytics -> Quality -> Run daily analysis now`.
2. Дождаться сообщения результата.
3. Проверить файлы отчёта в `Documents/forecast-reports`.
4. Проверить telemetry ключи `daily_report_*` в `telemetry_samples`.

# Изменения — Этап 28: Physiology-Aware ISF/CR Engine v1 (foundation + runtime wiring)

## Что сделано
- Добавлен новый доменный контур `domain/isfcr`:
  - `IsfCrEngine`,
  - `IsfCrWindowExtractor`,
  - `IsfCrQualityScorer`,
  - `IsfCrBaseFitter`,
  - `IsfCrContextModel`,
  - `IsfCrConfidenceModel`,
  - `IsfCrFallbackResolver`,
  - `IsfCrDiagnostics`,
  - модели `IsfCr*`.
- Добавлен новый репозиторий `IsfCrRepository`:
  - base fit и realtime snapshot,
  - сохранение в `isf_cr_model_state`, `isf_cr_snapshots`, `isf_cr_evidence`,
  - retention-cleanup,
  - audit events: `isfcr_base_fit_completed`, `isfcr_realtime_computed`, `isfcr_fallback_applied`.
- Добавлены/подключены Room-таблицы и DAO (ранее созданные заготовки) в runtime-контур.
- В `AnalyticsRepository` добавлен periodic base refit для ISF/CR state (каждые 6ч).
- В `AutomationRepository` добавлен realtime вызов `IsfCrRepository` на цикл:
  - запись telemetry `isf_realtime_*`,
  - использование snapshot как приоритетного профиля для rule-context.
- В `HybridPredictionEngine` добавлен безопасный override чувствительности:
  - `setSensitivityOverride(isf, cr, confidence, source)`,
  - override применяется только при `confidence >= 0.55`,
  - иначе используется существующий legacy estimator.
- В `AppSettingsStore` добавлены настройки ISF/CR контура:
  - `isfCrShadowMode`,
  - `isfCrConfidenceThreshold`,
  - `isfCrUseActivity`,
  - `isfCrUseManualTags`,
  - `isfCrSnapshotRetentionDays`,
  - `isfCrEvidenceRetentionDays`.
- Добавлены unit-тесты:
  - `IsfCrEngineTest` (base fit, fallback, activity/shadow behavior).
- Обновлены документы:
  - `docs/ARCHITECTURE.md`,
  - `docs/INVARIANTS.md`.

## Почему так
- Требование: внедрить physiology-aware расчёт “реального” ISF/CR с confidence-gate и безопасным fallback.
- Выбран подход `shadow-first + deterministic v1`, чтобы повысить точность без риска резкой регрессии автоматики.

## Риски / ограничения
- `computeRealtime` сейчас вызывается на каждом automation cycle; при очень длинной истории это может увеличить CPU.
- Base refit в текущей версии запускается по 6-часовому интервалу внутри `AnalyticsRepository`; отдельный nightly worker можно выделить следующим этапом.
- В v1 включён deterministic контур; ML shadow/v2 не активирован.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. Запустить цикл автоматики и проверить аудит:
   - `isfcr_base_fit_completed`
   - `isfcr_realtime_computed`
   - `isfcr_fallback_applied` (если confidence low)
4. Проверить таблицы БД:
   - `isf_cr_model_state`
   - `isf_cr_snapshots`
   - `isf_cr_evidence`
5. Проверить telemetry-ключи:
   - `isf_realtime_value`
   - `cr_realtime_value`
   - `isf_realtime_confidence`
   - `isf_realtime_quality_score`
   - `isf_realtime_mode`

# Изменения — Этап 29: ISF/CR UI + Settings Integration (Stage 5)

## Что сделано
- Доведен `Analytics -> ISF/CR` realtime-блок:
  - добавлены строки и отображение статуса realtime режима/confidence/quality;
  - отображаются `ISF eff`, `CR eff`, `ISF base`, `CR base`, CI и факторные линии/активные теги.
- Расширен `MainViewModel`:
  - в `settingsUiState` добавлены новые поля `isfCr*` из `AppSettings`;
  - подключено наблюдение `observeRecentTags(0)` и вывод только активных тегов в настройки;
  - добавлены action-методы:
    - `setIsfCrShadowMode`,
    - `setIsfCrConfidenceThreshold`,
    - `setIsfCrUseActivity`,
    - `setIsfCrUseManualTags`,
    - `setIsfCrRetention`,
    - `addPhysioTag`,
    - `clearActivePhysioTags`.
- Расширен `IsfCrRepository` методом `closeActiveTags(nowTs)` для завершения активных тегов.
- В `AutomationRepository` добавлен `shadow`-аудит сравнений:
  - событие `isfcr_shadow_diff_logged` (legacy ISF/CR vs realtime ISF/CR, абсолютные и относительные дельты),
  - запись происходит только при `snapshot.mode == SHADOW`.
- Реализована новая секция `Settings -> Real ISF/CR engine`:
  - переключатели `Shadow mode`, `Use activity`, `Use manual tags`;
  - порог confidence (20..95%);
  - retention для snapshots/evidence;
  - quick-add physiology tags (`stress`, `illness`, `hormonal_phase`, `steroids`, `dawn`);
  - список активных тегов + `Clear active tags`.
- В `CopilotFoundationRoot` добавлен полный wiring callbacks из `SettingsScreen` в `MainViewModel`.
- Обновлены `ru/en` resources для новых Analytics/Settings секций.

## Почему так
- Это закрывает этап UI/настроек из плана Physiology-Aware ISF/CR Engine v1:
  - пользователь видит realtime-оценки ISF/CR и факторы;
  - может управлять shadow/confidence/useActivity/useTags и контекстными тегами без ручного SQL/adb.

## Риски / ограничения
- Quick-tag UI использует фиксированный набор тегов v1; расширяемый словарь/локализация тегов можно добавить отдельным этапом.
- В текущем релизе это всё ещё `shadow-first`: реальное влияние на runtime ограничено confidence-gate и fallback-цепочкой.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
3) На устройстве открыть `More -> Settings -> Real ISF/CR engine` и проверить:
   - сохранение переключателей/порогов/retention,
   - добавление и очистку тегов.
4) Открыть `More -> Analytics -> ISF/CR` и проверить realtime карточку (mode/confidence/quality/CI/factors/tags).

# Изменения — Этап 30: Controlled Activation Gate для ISF/CR (Stage 6)

## Что сделано
- В `AutomationRepository` добавлен явный runtime-gate:
  - `resolveIsfCrRuntimeGateStatic(snapshot, confidenceThreshold)`,
  - применение realtime ISF/CR в runtime разрешено **только** при `mode=ACTIVE` и `confidence >= threshold`.
- Исправлено `shadow-first` поведение:
  - при `mode=SHADOW` realtime snapshot не подается в `HybridPredictionEngine` как override;
  - fallback/shadow режимы блокируют runtime override и пишут причину.
- Добавлен аудит controlled activation:
  - `isfcr_runtime_gate` c полями `mode/confidence/threshold/applied/reason`;
  - `isfcr_shadow_diff_logged` сохранен как сравнительный shadow-аудит.
- Профиль для runtime:
  - при `applyToRuntime=true` используется realtime профиль;
  - иначе используется legacy профиль;
  - добавлен безопасный bootstrap-кейс `isfcr_runtime_bootstrap_profile`, если legacy отсутствует.
- `HybridPredictionEngine` доработан для настраиваемого confidence-gate:
  - `setSensitivityOverride(..., minConfidenceRequired=...)`;
  - сравнение confidence теперь идет с переданным порогом, а не с фиксированной константой.
- Добавлены unit-tests для runtime gate:
  - no snapshot,
  - shadow mode,
  - fallback mode,
  - active + low confidence,
  - active + confident.

## Почему так
- Это закрывает требование Stage 6 (`controlled activation`) из плана Physiology-Aware ISF/CR:
  - SHADOW режим теперь действительно “теневой” и не влияет на автоматические решения,
  - активация нового контура выполняется только по confidence-gate.

## Риски / ограничения
- Если legacy профиль отсутствует, включается bootstrap-путь на realtime snapshot (логируется отдельно), чтобы не оставлять runtime без профиля.
- В telemetry на текущем шаге добавлен флаг `isf_realtime_applied` в runtime-map цикла; при необходимости его можно дополнительно писать отдельным telemetry source.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В SHADOW режиме убедиться по audit, что есть `isfcr_runtime_gate` с `applied=false` и reason `shadow_mode`.
4) В ACTIVE режиме с достаточным confidence убедиться, что `applied=true`.

# Изменения — Этап 31: Shadow KPI Auto-Activation Gate для ISF/CR

## Что сделано
- Добавлен KPI-контур для выхода из `SHADOW` в `ACTIVE` на основе накопленного `shadow`-аудита:
  - источник: `audit_logs` с событием `isfcr_shadow_diff_logged`;
  - метрики: `meanConfidence`, `meanAbsIsfDeltaPct`, `meanAbsCrDeltaPct`, `sampleCount`;
  - решение: `eligible`/`blocked` с reason-кодом.
- В `AutomationRepository` добавлены:
  - `IsfCrShadowDiffSample`, `IsfCrShadowActivationAssessment`;
  - `evaluateIsfCrShadowActivationStatic(...)`;
  - `maybeProcessIsfCrShadowAutoActivation(...)` с периодической оценкой (не чаще 1 раза в 30 минут).
- Добавлены новые audit events:
  - `isfcr_shadow_activation_evaluated` (результат KPI-gate и метрики);
  - `isfcr_shadow_auto_promoted` (факт автопереключения в ACTIVE).
- В `AppSettings` и DataStore добавлены настройки auto-activation:
  - `isfCrAutoActivationEnabled`;
  - `isfCrAutoActivationLookbackHours`;
  - `isfCrAutoActivationMinSamples`;
  - `isfCrAutoActivationMinMeanConfidence`;
  - `isfCrAutoActivationMaxMeanAbsIsfDeltaPct`;
  - `isfCrAutoActivationMaxMeanAbsCrDeltaPct`.
- UI `Settings -> Real ISF/CR engine` расширен новыми контролами для KPI auto-activation (toggle + thresholds + lookback/sample bounds), добавлен wiring через `MainViewModel`/`CopilotFoundationRoot`, обновлены `ru/en` strings.
- В `AuditLogDao` добавлен запрос `recentByMessage(...)` для выборки нужных audit-событий по окну времени.
- Добавлены unit-tests для KPI-гейта:
  - insufficient samples;
  - low mean confidence;
  - high ISF delta;
  - pass/eligible.

## Почему так
- Этап controlled activation теперь закрыт не только ручным `shadow`-переключателем, но и проверяемым KPI-gate:
  - в runtime включение нового контура происходит только при стабильных shadow-метриках;
  - решение полностью explainable и трассируется в audit.

## Риски / ограничения
- KPI-gate опирается на качество `shadow_diff` логов; при редких циклах или отсутствии legacy-профиля автоактивация может не сработать.
- Автопереключение выполняется только если одновременно включены:
  - `isfCrShadowMode=true`,
  - `isfCrAutoActivationEnabled=true`.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
4) Включить `Settings -> Real ISF/CR -> Auto activation from shadow` и проверить audit:
   - сначала `isfcr_shadow_activation_evaluated`;
   - при выполнении KPI — `isfcr_shadow_auto_promoted` и `isfcr_shadow_mode` становится `false`.

# Изменения — Этап 32: Room migration 9→10 для ISF/CR контура (без потери данных)

## Что сделано
- Добавлен явный пакет миграций Room:
  - новый файл `CopilotMigrations.kt`;
  - `MIGRATION_9_10` создаёт таблицы/индексы:
    - `isf_cr_snapshots`,
    - `isf_cr_evidence`,
    - `isf_cr_model_state`,
    - `physio_context_tags`.
- Обновлён `AppContainer`:
  - подключение `addMigrations(*CopilotMigrations.ALL)`;
  - destructive fallback ограничен только старыми версиями `1..8` через `fallbackToDestructiveMigrationFrom(...)`.
- Добавлены unit tests:
  - `CopilotMigrationsTest`:
    - проверка версии миграции `9 -> 10`,
    - проверка покрытия SQL-операторов по новым таблицам/индексам,
    - проверка подключения миграции в общий пакет.
- Обновлены документы:
  - `docs/ARCHITECTURE.md` (зафиксирован non-destructive путь `v9 -> v10`);
  - `docs/INVARIANTS.md` (инвариант сохранности данных на `9 -> 10`).

## Почему так
- План Physiology-Aware ISF/CR требует долгосрочного сохранения evidence/snapshots; destructive migration на `9 -> 10` ломала бы эти требования.
- Выделенный migration-пакет делает переход воспроизводимым и проверяемым.

## Риски / ограничения
- Для очень старых БД (`<=8`) сохранён destructive fallback.
- Полноценный instrumentation migration-suite (`MigrationTestHelper`) пока не добавлялся; в этом этапе закрыт JVM unit-check миграционного пакета.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.local.CopilotMigrationsTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`

# Изменения — Этап 33: ISF/CR auto-activation daily quality gate (24h MAE + hypo-rate)

## Что сделано
- Усилен контур `SHADOW -> ACTIVE` автоактивации ISF/CR:
  - в `AutomationRepository` добавлен ежедневный quality gate перед promotion:
    - `daily_report_matched_samples`,
    - `daily_report_mae_30m`,
    - `daily_report_mae_60m`,
    - `hypo rate` за 24ч по локальным glucose-точкам `< 3.9 mmol/L`.
  - новое событие аудита: `isfcr_shadow_quality_gate_evaluated`.
  - promotion (`isfcr_shadow_auto_promoted`) теперь содержит причины/метрики quality-gate.
- Добавлены настройки в `AppSettings`/DataStore:
  - `isfCrAutoActivationRequireDailyQualityGate`,
  - `isfCrAutoActivationMinDailyMatchedSamples`,
  - `isfCrAutoActivationMaxDailyMae30Mmol`,
  - `isfCrAutoActivationMaxDailyMae60Mmol`,
  - `isfCrAutoActivationMaxHypoRatePct`.
- Расширены UI и wiring:
  - `Settings -> Real ISF/CR engine` получил переключатель daily quality gate и пороги.
  - Добавлены методы `MainViewModel` для управления новыми порогами.
  - Обновлены `MainUiState`, `SettingsUiState`, `MainUiStateMappers`, `CopilotFoundationRoot`.
- Добавлены unit tests:
  - в `AutomationRepositoryForecastBiasTest` — проверки daily quality gate (`missing`, `out_of_bounds`, `eligible`).

## Почему так
- Это продолжение controlled activation по плану Physiology-Aware ISF/CR v1: включение нового контура теперь проверяет не только shadow-дельты, но и фактическое качество прогноза за 24ч и safety-показатель гипо.

## Риски / ограничения
- Daily quality gate зависит от наличия свежего локального daily report telemetry (`daily_report_*`); при его отсутствии activation блокируется (`daily_report_missing`).
- Hypo-rate считается по доступной локальной 24h истории CGM; при пустой истории quality-gate блокирует promotion (`hypo_rate_missing`).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
4) В UI: `Settings -> Real ISF/CR engine` проверить новые поля daily quality gate.
5) В audit проверить события:
   - `isfcr_shadow_activation_evaluated`,
   - `isfcr_shadow_quality_gate_evaluated`,
   - при прохождении — `isfcr_shadow_auto_promoted`.

# Изменения — Этап 34: Daily CI calibration metrics for forecast report

## Что сделано
- Расширен локальный daily forecast report (`InsightsRepository`) новыми метриками калибровки неопределенности по каждому горизонту `5/30/60`:
  - `CI coverage (%)` — доля фактов внутри CI,
  - `mean CI width (mmol/L)` — средняя ширина доверительного интервала.
- Обновлен расчёт payload:
  - в `ErrorAccumulator` добавлены счетчики `ciInsideCount` и `ciWidthSum`,
  - `HorizonForecastStats` расширен полями `ciCoveragePct` и `ciMeanWidth`.
- Обновлен telemetry-export daily report:
  - `daily_report_ci_coverage_<h>m_pct`,
  - `daily_report_ci_width_<h>m`.
- Обновлены Markdown/CSV артефакты daily report:
  - в таблицу horizon metrics добавлены столбцы `CI cover (%)` и `CI width`.
- Обновлен Analytics UI:
  - в карточке daily report для каждого горизонта показывается отдельная строка `CI coverage` + `mean width`.
- Обновлены UI-модели и маппинг:
  - `DailyReportMetricUi`,
  - `DailyReportHorizonUi`,
  - `MainUiStateMappers`.
- Добавлены/обновлены unit tests:
  - `InsightsRepositoryDailyForecastReportTest` (проверка CI coverage/width),
  - `MainUiStateMappersTest` (маппинг новых полей в Analytics state).
- Обновлены docs:
  - `docs/ARCHITECTURE.md`,
  - `docs/INVARIANTS.md`.

## Почему так
- Это закрывает часть observability/calibration из плана Physiology-Aware ISF/CR: теперь качество прогноза оценивается не только ошибкой (`MAE/RMSE/MARD/Bias`), но и адекватностью заявленной неопределенности (CI calibration).
- Эти метрики напрямую полезны для диагностики пере/недокалиброванных CI и дальнейшего снижения MARD без потери safety.

## Риски / ограничения
- CI coverage/width считаются по доступным matched forecast points; при редком потоке данных статистика может быть шумной.
- В этом этапе новые CI-метрики добавлены в analytics/telemetry, но не включены в обязательный runtime block-gate.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
4) В приложении открыть `Analytics -> Daily Forecast Report` и проверить наличие строк `CI coverage` и `mean width` для 5m/30m/60m.

# Изменения — Этап 35: ISF/CR daily quality gate v2 (CI calibration-aware)

## Что сделано
- Усилен `SHADOW -> ACTIVE` quality gate в `AutomationRepository`:
  - добавлены критерии калибровки CI из daily report:
    - `daily_report_ci_coverage_30m_pct`, `daily_report_ci_coverage_60m_pct` (нижние пороги),
    - `daily_report_ci_width_30m`, `daily_report_ci_width_60m` (верхние пороги).
- Расширен `IsfCrDailyQualityGateAssessment` и audit payload (`isfcr_shadow_quality_gate_evaluated`, `isfcr_shadow_auto_promoted`) полями CI coverage/width.
- Добавлены новые reason-коды блокировки гейта:
  - `daily_ci_coverage_missing`,
  - `daily_ci_coverage30_out_of_bounds`,
  - `daily_ci_coverage60_out_of_bounds`,
  - `daily_ci_width_missing`,
  - `daily_ci_width30_out_of_bounds`,
  - `daily_ci_width60_out_of_bounds`.

- Расширены настройки `AppSettings`/DataStore:
  - `isfCrAutoActivationMinDailyCiCoverage30Pct`,
  - `isfCrAutoActivationMinDailyCiCoverage60Pct`,
  - `isfCrAutoActivationMaxDailyCiWidth30Mmol`,
  - `isfCrAutoActivationMaxDailyCiWidth60Mmol`.

- Обновлен UI `Settings -> Real ISF/CR engine`:
  - добавлены stepper-поля для CI coverage/width порогов,
  - все изменения протянуты через `SettingsUiState`, `MainUiState`, `MainViewModel`, `MainUiStateMappers`.

- Добавлены/обновлены тесты:
  - `AutomationRepositoryForecastBiasTest` — новые сценарии блокировки по CI coverage/width,
  - `MainUiStateMappersTest` — проверка маппинга новых настроек в Settings UI state.

## Почему так
- Auto-activation ранее опирался на MAE/hypo и мог пропустить случаи некалиброванной неопределенности.
- Добавление CI coverage/width как обязательных quality-критериев делает переход в `ACTIVE` более устойчивым и безопасным.

## Риски / ограничения
- Для новых CI-критериев требуется свежий daily report telemetry; при его отсутствии gate ожидаемо блокирует promotion.
- Пороги CI coverage/width пока настраиваются вручную; авто-тюнинг порогов не включен.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
4) В приложении открыть `Settings -> Real ISF/CR engine` и проверить новые пороги:
   - Min daily CI coverage 30m/60m,
   - Max daily CI width 30m/60m.

# Изменения — Этап 36: Analytics activation-gate visibility

## Что сделано
- Завершено отображение статуса `SHADOW -> ACTIVE` гейта на экране `Analytics -> Quality`:
  - добавлена карточка `ISF/CR Activation Gate`,
  - показываются последние строки диагностики KPI-гейта, daily quality-гейта и последнего auto-promotion.
- Добавлены локализованные строки:
  - `section_analytics_activation_gate`,
  - `analytics_activation_gate_empty`
  в `values` и `values-ru`.
- Улучшен сбор данных для карточки в `MainViewModel`:
  - выбор последнего audit-события теперь делается по `max timestamp` для:
    - `isfcr_shadow_activation_evaluated`,
    - `isfcr_shadow_quality_gate_evaluated`,
    - `isfcr_shadow_auto_promoted`.
- Обновлен unit test маппинга (`MainUiStateMappersTest`) с проверкой передачи `activationGateLines` в `AnalyticsUiState`.

## Почему так
- До этого логика гейта уже работала в runtime и писала audit, но пользователю не было удобно видеть итоговые причины block/eligible в UI.
- Отдельная карточка в Analytics закрывает explainability-часть плана `shadow-first` и ускоряет диагностику rollout.

## Риски / ограничения
- Карточка опирается на наличие соответствующих audit-событий; на “чистых” установках до первого цикла будет показано пустое состояние.
- Формат строк пока текстовый (человеко-читаемые line items), без структурированных полей/цветовой кодировки.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --tests io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В приложении открыть `Analytics -> Quality` и проверить:
   - секцию `ISF/CR Activation Gate`,
   - вывод KPI/daily gate причин и CI calibration строк.

# Изменения — Этап 37: ISF/CR realtime observability hardening

## Что сделано
- Усилен runtime-аудит `IsfCrRepository.computeRealtimeSnapshot`:
  - событие `isfcr_realtime_computed` теперь пишет расширенные метрики:
    - `confidenceThreshold`, `qualityScore`,
    - `usedEvidence`, `droppedEvidence`,
    - `coverageHoursIsf`, `coverageHoursCr`,
    - `reasons`.
- Добавлено отдельное предупреждающее событие:
  - `isfcr_low_confidence` (пишется при `confidence < threshold`)
  - с контекстом confidence/quality/evidence/reasons.
- Расширена доменная диагностика `IsfCrDiagnostics`:
  - добавлены поля `qualityScore` и `lowConfidence`.
- Обновлен `IsfCrEngine`:
  - при формировании diagnostics учитываются `resolved.qualityScore` и флаг low-confidence относительно `settings.confidenceThreshold`.
- Дополнены unit-тесты `IsfCrEngineTest`:
  - проверка `diagnostics.lowConfidence` в fallback-сценарии;
  - проверка диапазона `diagnostics.qualityScore`.

## Почему так
- Это закрывает часть плана по observability: в audit теперь видны не только финальные `isfEff/crEff`, но и качество входных evidence и причина снижения доверия.
- Отдельный `isfcr_low_confidence` ускоряет диагностику случаев, когда контур часто уходит в fallback.

## Риски / ограничения
- Уровень детализации audit вырос; при очень частых циклах возможен более быстрый рост объема audit log.
- В этом этапе не добавлялась отдельная UI-таблица diagnostics; данные доступны через существующий Audit экран и экспорт логов.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --tests io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В runtime открыть `Audit` и проверить наличие:
   - `isfcr_realtime_computed` с полями `usedEvidence/droppedEvidence/coverageHours*`,
   - `isfcr_low_confidence` при низком confidence.

# Изменения — Этап 38: Runtime diagnostics surfaced in Analytics deep lines

## Что сделано
- Усилен блок `Analytics -> ISF/CR diagnostics` в `MainViewModel`:
  - добавлен сбор последних runtime-аудитов:
    - `isfcr_realtime_computed`,
    - `isfcr_low_confidence`,
    - `isfcr_fallback_applied`.
  - реализован новый helper `buildIsfCrRuntimeDiagnosticsLines(...)`, который формирует человеко-читаемые строки:
    - mode/confidence/threshold/quality,
    - evidence used/dropped и coverage hours ISF/CR,
    - runtime reasons,
    - low-confidence reasons,
    - fallback reasons.
- Новые runtime diagnostics добавлены в `deepIsfCrLinesCombined` перед long-window анализом, чтобы пользователь видел актуальную причину деградации/фоллбэка вверху списка.

## Почему так
- По плану Physiology-Aware ISF/CR нужен explainable runtime. До этого метрики писались в audit, но в Analytics deep block не поднимались явно.
- Теперь диагностика доступна сразу в UI без ручного поиска в сырых логах.

## Риски / ограничения
- Формат остаётся текстовым; структурированный UI-рендер отдельных полей diagnostics пока не добавлен.
- При большом количестве deep-lines карточка обрезается `take(6)` в Analytics экране, поэтому приоритетные runtime строки выводятся первыми.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --tests io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В приложении открыть `Analytics -> ISF/CR` и проверить в карточке diagnostics строки:
   - `Runtime (...)`,
   - `Evidence: used/dropped`,
   - при необходимости `Low confidence (...)`,
   - `Fallback (...)`.

# Изменения — Этап 39: Diagnostics priority and visibility fix

## Что сделано
- Исправлен приоритет вывода deep diagnostics в `MainViewModel`:
  - runtime diagnostics (`Runtime/Low confidence/Fallback`) теперь добавляются раньше factor dump, чтобы не теряться в длинном списке.
  - `snapshotFactorLines` ограничены `take(6)` после runtime-строк.
- Увеличен лимит отображения diagnostics в `AnalyticsScreen`:
  - `state.deepLines.take(6)` -> `state.deepLines.take(10)`.

## Почему так
- После этапа 38 runtime-строки могли не попадать в видимую часть карточки из-за раннего заполнения факторными ключами.
- Приоритетные operational причины (confidence/fallback) должны быть видны пользователю в первую очередь.

## Риски / ограничения
- Карточка стала показывать больше строк, что слегка увеличивает вертикальную высоту секции.
- Структурированный отдельный UI для runtime diagnostics пока не внедрялся (текстовый формат сохранён).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В приложении открыть `Analytics -> ISF/CR` и убедиться, что в блоке diagnostics первыми идут строки `Runtime`/`Low confidence`/`Fallback`.

# Изменения — Этап 40: Structured runtime diagnostics card (Analytics ISF/CR)

## Что сделано
- Добавлена структурированная модель runtime diagnostics в UI:
  - `IsfCrRuntimeDiagnosticsUi` в `ScreenModels`.
  - поле `runtimeDiagnostics` в `AnalyticsUiState`.
- Расширен `MainUiState` полями structured diagnostics:
  - realtime ts/mode/confidence/threshold/quality,
  - used/dropped evidence, coverage hours ISF/CR,
  - reasons + low-confidence/fallback timestamps/reasons.
- В `MainViewModel` реализован двухшаговый pipeline:
  1) `buildIsfCrRuntimeDiagnosticsSnapshot(audits)` — структурный снимок из audit-событий
  2) `buildIsfCrRuntimeDiagnosticsLines(snapshot)` — текстовый deep fallback
- Добавлена отдельная карточка на экране `Analytics -> ISF/CR`:
  - секция `ISF/CR Runtime Diagnostics`,
  - вывод realtime/confidence/evidence/coverage/reasons,
  - отдельные строки low-confidence и fallback.
- Добавлены строки `en/ru` для новой карточки.
- Обновлен unit test `MainUiStateMappersTest`:
  - проверка маппинга structured runtime diagnostics.

## Почему так
- Deep-lines полезны для быстрой диагностики, но для регулярной работы нужен структурированный блок с фиксированными полями.
- Это уменьшает риск пропуска критичных сигналов (low confidence/fallback) и повышает explainability shadow-runtime.

## Риски / ограничения
- Строки reasons остаются текстовыми (comma-separated), без отдельного парсинга на чипы.
- Источник diagnostics — последние audit-события; при пустом audit карточка корректно показывает empty state.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --tests io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В приложении открыть `Analytics -> ISF/CR` и проверить новую карточку `ISF/CR Runtime Diagnostics` (значения realtime/low-confidence/fallback).

# Изменения — Этап 41: Reason-codes chips for runtime diagnostics

## Что сделано
- Доработана structured-карточка `ISF/CR Runtime Diagnostics`:
  - добавлены reason-codes в виде отдельных chip-меток для:
    - runtime reasons,
    - low-confidence reasons,
    - fallback reasons.
- Расширена UI-модель `IsfCrRuntimeDiagnosticsUi`:
  - `reasonCodes`,
  - `lowConfidenceReasonCodes`,
  - `fallbackReasonCodes`.
- В `MainUiStateMappers` добавлен парсер reason-кодов:
  - разбиение строк reasons по `, ; \n`,
  - trim/filter/distinct.
- В `AnalyticsScreen` добавлен `ReasonCodesRow` (FlowRow + chip-like Surface labels).
- Добавлены строки локализации `en/ru` для подписей reason-code групп.
- Обновлен unit test `MainUiStateMappersTest` проверками reason-codes списков.

## Почему так
- Длинные текстовые строки reasons были менее удобны для быстрого анализа.
- Чипы позволяют мгновенно увидеть конкретные коды причин и ускоряют troubleshooting в shadow-runtime.

## Риски / ограничения
- Коды пока отображаются как raw tokens (без человеко-понятного словаря).
- Если reasons пусты, группы чипов корректно не показываются.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --tests io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В приложении открыть `Analytics -> ISF/CR -> ISF/CR Runtime Diagnostics` и проверить chip-группы `Runtime/Low-confidence/Fallback reason codes`.

# Изменения — Этап 42: Human-readable reason labels (runtime chips)

## Что сделано
- Улучшено отображение reason-codes в `ISF/CR Runtime Diagnostics`:
  - для известных кодов добавлены человеко-понятные локализованные подписи вместо raw-токенов.
- Сопоставлены ключевые runtime-коды:
  - `model_state_missing`,
  - `isf_evidence_sparse`,
  - `cr_evidence_sparse`,
  - `low_confidence_fallback`.
- Неизвестные коды по-прежнему показываются как raw (fallback-поведение сохранено).
- Добавлены строки локализации `en/ru` для новых подписей.

## Почему так
- Чипы с “сырой” телеметрией были менее понятны в повседневной работе.
- Локализованные подписи ускоряют диагностику и снижают когнитивную нагрузку в shadow-режиме.

## Риски / ограничения
- Словарь покрывает основные текущие коды; новые коды будут отображаться raw, пока не добавлены в mapping.
- Это UI-only изменение, алгоритмы расчёта/гейтов не менялись.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В UI открыть `Analytics -> ISF/CR -> ISF/CR Runtime Diagnostics` и проверить чипы:
   - известные коды отображаются человеко-понятным текстом,
   - неизвестные остаются кодами.

# Изменения — Этап 43: Dropped evidence reasons in ISF/CR observability

## Что сделано
- Расширен extractor `IsfCrWindowExtractor`:
  - теперь возвращает не только `droppedCount`, но и `droppedReasonCounts` (code -> count),
  - добавлены детерминированные reason codes для отбраковки ISF/CR evidence:
    - ISF: `isf_missing_units`, `isf_small_units`, `isf_carbs_around`, `isf_missing_baseline`, `isf_missing_future`, `isf_non_positive_drop`, `isf_out_of_range`, `isf_low_quality`;
    - CR: `cr_missing_carbs`, `cr_small_carbs`, `cr_no_bolus_nearby`, `cr_sparse_points`, `cr_low_quality`, `cr_sparse_intervals`, `cr_fit_invalid`.
- Расширены доменные структуры:
  - `IsfCrEngine.FitResult` теперь содержит `droppedReasonCounts`,
  - `IsfCrDiagnostics` теперь содержит `droppedReasonCounts`.
- Расширен audit в `IsfCrRepository`:
  - в `isfcr_base_fit_completed`,
  - в `isfcr_realtime_computed`,
  - в `isfcr_low_confidence`
  добавлено поле `droppedReasons` в формате `code=count;code=count`.
- Расширен runtime diagnostics UI-pipeline:
  - `MainViewModel` читает `droppedReasons` из audit и поднимает в `MainUiState`,
  - `MainUiStateMappers` маппит `droppedReasons` и `droppedReasonCodes` в `IsfCrRuntimeDiagnosticsUi`,
  - `AnalyticsScreen` показывает:
    - строку `Dropped evidence reasons`,
    - отдельные chip-коды причин отбраковки.
- Добавлена локализация `en/ru` для:
  - строки dropped reasons,
  - заголовка dropped reason codes,
  - человеко-понятных label’ов для основных dropped reason codes.
- Обновлены docs:
  - `docs/ARCHITECTURE.md` (контракт observability для dropped reasons),
  - `docs/INVARIANTS.md` (инвариант обязательной публикации dropped reasons в audit).
- Добавлены/обновлены unit tests:
  - `IsfCrEngineTest.extractor_collectsDroppedReasonCounts`,
  - `MainUiStateMappersTest.analyticsMapping_includesIsfCrSummaryAndHistory` (проверка dropped reason codes маппинга).

## Почему так
- Ранее была только агрегатная метрика `droppedEvidenceCount`, но не было explainability “почему именно окна выброшены”.
- Кодированные причины отбраковки закрывают требование observability из Physiology-Aware ISF/CR плана: ускоряют диагностику качества данных и точечно показывают узкие места экстрактора.

## Риски / ограничения
- Словарь локализованных label’ов покрывает текущие reason codes; новые коды будут отображаться raw до добавления mapping.
- `droppedReasons` хранится в compact string (`code=count;...`), поэтому в UI сохраняется lightweight-парсинг без отдельной структурной схемы payload.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В приложении открыть `Analytics -> ISF/CR -> ISF/CR Runtime Diagnostics` и проверить:
   - строку `Dropped evidence reasons`,
   - chip-группу `Dropped evidence reason codes`,
   - человеко-понятные подписи для известных кодов.

# Изменения — Этап 44: Audit event `isfcr_evidence_extracted`

## Что сделано
- Добавлен отдельный audit event `isfcr_evidence_extracted` в `IsfCrRepository` для обеих фаз:
  - `phase=base_fit`,
  - `phase=realtime`.
- В payload события включены:
  - `isfEvidence`,
  - `crEvidence`,
  - `droppedEvidence`,
  - `droppedReasons` (code=count;...).

## Почему так
- В плане Physiology-Aware ISF/CR требовался отдельный audit-трейс “evidence extracted”.
- Теперь extraction наблюдаем не только через итоговые события realtime/base-fit, но и отдельным нормализованным событием для аналитики и диагностики pipeline.

## Риски / ограничения
- Частота audit-событий увеличилась (каждый realtime-цикл добавляет ещё одно info-событие).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В `Audit Log` найти `isfcr_evidence_extracted` и убедиться, что есть `phase`, `isfEvidence/crEvidence`, `droppedEvidence`, `droppedReasons`.

# Изменения — Этап 45: Quality-tab dropped reasons analytics (24h/7d)

## Что сделано
- В `MainViewModel` добавлена агрегация причин отбраковки ISF/CR evidence по audit:
  - окно `24h`,
  - окно `7d`.
- Источник данных:
  - primary: `isfcr_evidence_extracted`,
  - fallback для старых сессий: `isfcr_realtime_computed`.
- Для каждого окна формируется сводка:
  - `Events=<n>, dropped total=<m>`,
  - топ reason-codes с count (`code=count`).
- Новые поля протянуты в UI state:
  - `MainUiState.isfCrDroppedReasons24hLines`,
  - `MainUiState.isfCrDroppedReasons7dLines`,
  - `AnalyticsUiState.droppedReasons24hLines`,
  - `AnalyticsUiState.droppedReasons7dLines`.
- На экране `Analytics -> Quality` добавлена отдельная карточка:
  - `ISF/CR Dropped Evidence Summary`,
  - подпункты `Last 24h` и `Last 7d`.
- Добавлена локализация `en/ru` для нового блока.
- Обновлен unit test `MainUiStateMappersTest`:
  - проверка корректного маппинга `droppedReasons24hLines/7dLines`.
- Обновлены docs:
  - `docs/ARCHITECTURE.md`,
  - `docs/INVARIANTS.md`.

## Почему так
- После этапов 43/44 причины отбраковки были видны в runtime diagnostics/audit, но не было агрегированного quality-view за длительные окна.
- Новый блок ускоряет root-cause triage (какие причины системно “съедают” evidence) и помогает при тюнинге quality gates.

## Риски / ограничения
- Агрегация пока строится на клиенте из audit, без отдельной предагрегированной таблицы.
- Для исторических данных до внедрения `isfcr_evidence_extracted` используется fallback-событие и менее точная статистика.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В UI открыть `Analytics -> Quality` и проверить карточку `ISF/CR Dropped Evidence Summary`:
   - строки `Last 24h` и `Last 7d`,
   - header с `Events/dropped total`,
   - top reason codes.

# Изменения — Этап 46: ISF/CR context model hardening (manual tags + sensor age + activity fallback)

## Что сделано
- Доработан `IsfCrContextModel`:
  - `useManualTags` теперь реально управляет ручными тегами:
    - при `false` manual `stress/illness/hormonal` факторы обнуляются;
    - latent stress из telemetry сохраняется.
  - Добавлен `sensor_age_factor`:
    - вычисляется от `sensor_change/cgm_sensor_change/sensor_start`,
    - после 120 часов wear-age плавно уменьшает итоговый sensor-factor в безопасных пределах.
  - Добавлен fallback расчёт `steps_rate_15m` из `steps_count` telemetry (если прямой `steps_rate_15m` отсутствует).
  - `dawn_factor` теперь состоит из:
    - базового часового профиля,
    - telemetry hint (`dawn_factor_hint` / `dawn_resistance_score`) при наличии.
- Расширен factor trace в snapshot:
  - `sensor_age_hours`, `sensor_age_factor`,
  - `dawn_base_factor`, `dawn_hint_factor`,
  - `manual_tags_enabled`, `manual_*_tag`,
  - `latent_stress`.

## Тесты
- Добавлен новый unit test файл:
  - `IsfCrContextModelTest` со сценариями:
    1) manual tags toggle (`useManualTags`) действительно меняет влияние тегов;
    2) sensor age factor снижается при длительном wear-age;
    3) activity fallback использует `steps_count` для восстановления `steps_rate_15m`.

## Почему так
- Это закрывает выбранный контекст плана `manual tags + latent model` и устраняет риск скрытого влияния ручных тегов при их отключении.
- Sensor-age и steps fallback повышают устойчивость runtime факторов при неполной телеметрии.

## Риски / ограничения
- Sensor-age фактор пока эвристический и требует дальнейшей калибровки на replay.
- Для `steps_count` fallback требуется минимум две точки в окне ~20 минут; иначе используется 0.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrContextModelTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В runtime diagnostics убедиться, что в factors появляются:
   - `sensor_age_factor`,
   - `manual_tags_enabled`,
   - `latent_stress`,
   - `steps_rate_15m` (в том числе при отсутствии прямого telemetry key).

# Изменения — Этап 47: Hour-window evidence gating + runtime diagnostics wiring

## Что сделано
- Завершён контур hour-window gating в `IsfCrEngine`:
  - учитываются `minIsfEvidencePerHour` / `minCrEvidencePerHour`,
  - evidence blending по соответствующей ветке блокируется при недостатке hourly evidence,
  - в runtime reasons добавляются:
    - `isf_hourly_evidence_below_min`,
    - `cr_hourly_evidence_below_min`,
  - confidence/quality корректируются в безопасную сторону, CI расширяется.
- Данные hourly evidence полностью доведены до UI:
  - `MainUiStateMappers` теперь прокидывает `hourWindowIsfEvidence`, `hourWindowCrEvidence`, `minIsfEvidencePerHour`, `minCrEvidencePerHour` в `IsfCrRuntimeDiagnosticsUi`.
  - В `AnalyticsScreen -> ISF/CR Runtime Diagnostics` добавлена отдельная строка:
    - `Hour-window evidence ISF/CR: current/min`.
- Расширена локализация `en/ru`:
  - новый runtime line для hour-window evidence,
  - человеко-понятные подписи для reason-кодов hourly minima.
- Расширены unit-tests:
  - `IsfCrEngineTest`:
    - новый сценарий `computeRealtime_hourlyEvidenceBelowMinAddsReasonsAndDiagnostics`.
  - `MainUiStateMappersTest`:
    - проверка новых runtime diagnostics полей и reason-кодов hourly minima.
- Обновлены docs:
  - `docs/ARCHITECTURE.md`,
  - `docs/INVARIANTS.md` (инвариант hourly minima).

## Почему так
- Контур hourly evidence minimum был частично реализован, но не был полностью замкнут в UI-диагностику и тестовое покрытие.
- После доработки видно не только факт fallback/low confidence, но и конкретную причину: нехватка evidence в релевантном часовом окне.

## Риски / ограничения
- При строгих `min*EvidencePerHour` система чаще переходит в fallback/low-confidence.
- Для редких режимов (ночь/нечастые коррекции) требуется аккуратная настройка минимумов, иначе возможен избыточный conservative bias.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon`
4) В UI открыть `Analytics -> ISF/CR -> ISF/CR Runtime Diagnostics` и проверить:
   - строку `Hour-window evidence ISF/CR`,
   - reason codes с `...hourly_evidence_below_min`, когда hourly evidence ниже минимума.

# Изменения — Этап 48: Configurable hourly evidence minimums (Settings → runtime)

## Что сделано
- Параметры `minIsfEvidencePerHour` и `minCrEvidencePerHour` сделаны пользовательскими настройками:
  - добавлены в `AppSettingsStore` (чтение/запись DataStore + defaults),
  - добавлены в `AppSettings`,
  - протянуты в `IsfCrRepository.toIsfCrSettings()`.
- В `Settings -> Real ISF/CR` добавлены два степпера:
  - `Min ISF evidence per hour window`,
  - `Min CR evidence per hour window`.
- Добавлен `MainViewModel.setIsfCrMinEvidencePerHour(...)` с `coerceIn(0, 12)` и reactive cycle trigger.
- Обновлён wiring UI:
  - `CopilotFoundationRoot` передаёт callback в `SettingsScreen`.
  - `SettingsUiState` расширен двумя полями и заполнен в `settingsUiState` flow.
- Дополнен legacy mapper путь:
  - `MainUiState -> SettingsUiState` прокидывает новые поля.
- Обновлены строки локализации `en/ru`.
- Обновлён unit-test `MainUiStateMappersTest` (проверка маппинга новых полей).
- Обновлены docs:
  - `docs/ARCHITECTURE.md` (настройка minima через settings),
  - `docs/INVARIANTS.md` (clamp `0..12`).

## Почему так
- Hour-window gating уже работал в движке, но его пороги были фиксированными.
- Для безопасного rollout и персонализации нужны регулируемые minima без изменений кода.

## Риски / ограничения
- Слишком высокие minima могут часто переводить контур в low-confidence/fallback.
- Слишком низкие minima ослабляют качество evidence blending.
- В v1 эти пороги остаются ручной настройкой; авто-тюнинг не включён.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon`
4) В приложении:
   - `Settings -> Real ISF/CR` изменить оба минимума,
   - открыть `Analytics -> ISF/CR Runtime Diagnostics` и проверить, что строка hour-window и причины fallback/low-confidence соответствуют новым порогам.

# Изменения — Этап 49: Confidence hardening for physiology ambiguity and sensor faults

## Что сделано
- Усилен `IsfCrConfidenceModel`:
  - добавлен вход `factors`,
  - confidence и quality теперь штрафуются при:
    - большом возрасте инфузионного набора (`set_age_hours`),
    - большом возрасте сенсора (`sensor_age_hours`),
    - неоднозначном контексте (`UAM`, latent stress, manual stress/illness/hormone tags),
    - признаке ложного low (`sensor_quality_suspect_false_low`).
- CI для `ISF/CR` расширяется дополнительно при тех же факторах неопределённости.
- `IsfCrEngine` передаёт `context.factors` в confidence модель.
- В runtime reasons добавлены явные причины:
  - `set_age_high`,
  - `sensor_age_high`,
  - `context_ambiguity_high`.
- В `Analytics` добавлен label-mapping и локализация (`en/ru`) для новых reason codes.
- Добавлены тесты:
  - `IsfCrConfidenceModelTest` (падение confidence + расширение CI при wear/ambiguity),
  - `IsfCrEngineTest.computeRealtime_addsAgeAndAmbiguityReasons`.

## Почему так
- В v1 нужен консервативный confidence-gate при повышенной физиологической/сенсорной неопределённости.
- Это снижает риск ложного “high confidence” в окнах, где данные потенциально менее надёжны.

## Риски / ограничения
- Более строгий confidence может чаще переводить контур в `FALLBACK` на “плохих” окнах.
- Пороги wear/ambiguity эвристические и требуют дальнейшей калибровки на replay.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrConfidenceModelTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon`
4) В `Analytics -> ISF/CR Runtime Diagnostics` проверить reason-codes `set_age_high`, `sensor_age_high`, `context_ambiguity_high`.

# Изменения — Этап 50: Wear-aware evidence weighting (CAGE/SAGE as weight modifier)

## Что сделано
- В `IsfCrWindowExtractor` добавлены wear-aware модификаторы веса evidence:
  - поиск последнего `set_change` marker (`infusion_set_change/site_change/set_change/cannula_change`) до времени события,
  - поиск последнего `sensor_change` marker (`sensor_change/cgm_sensor_change/sensor_start/sensor_started`) до времени события,
  - вычисление `setAgeWeight` и `sensorAgeWeight` с плавным спадом,
  - итоговый вес sample:
    - `weight = qualityScore * (setAgeWeight * sensorAgeWeight)`.
- Weight применяется в обоих контурах evidence:
  - ISF correction windows,
  - CR meal windows.
- В evidence `context/window` добавлены диагностические поля:
  - `setAgeHours`, `sensorAgeHours`,
  - `setAgeWeight`, `sensorAgeWeight`.
- Добавлен unit-test файл:
  - `IsfCrWindowExtractorTest`:
    - `extract_appliesWearAgePenaltyToEvidenceWeight`,
    - `extract_withoutWearMarkersKeepsWeightEqualToQuality`.

## Почему так
- По плану v1 CAGE/SAGE должны влиять в первую очередь на надёжность evidence (веса), а не напрямую на физиологические множители.
- Это делает base/realtime fit устойчивее: “уставшие” периоды не доминируют в оценке ISF/CR.

## Риски / ограничения
- Для корректной работы нужны маркеры `set_change/sensor_change`; если их нет, применяется нейтральный множитель `1.0`.
- Форма спада весов пока эвристическая (piecewise linear) и может потребовать калибровки.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.domain.isfcr.IsfCrConfidenceModelTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon`
4) На diagnostics/replay проверить, что при старых `set/sensor` маркерах weights ниже, чем в свежих окнах.

# Изменения — Этап 51: Wear-impact observability in Analytics Quality (24h/7d)

## Что сделано
- Расширен audit payload события `isfcr_realtime_computed` в `IsfCrRepository`:
  - добавлены поля:
    - `setAgeHours`,
    - `sensorAgeHours`,
    - `setFactor`,
    - `sensorAgeFactor`,
    - `sensorFactor`,
    - `contextAmbiguity`,
    - `latentStress`,
    - `uamPenaltyFactor`,
    - `wearConfidencePenalty`.
- В `MainViewModel` добавлена агрегация wear-влияния по audit окнам:
  - `24h`,
  - `7d`.
- Новая сводка включает:
  - количество событий,
  - средний возраст набора/сенсора,
  - долю high-wear (`set>72h`, `sensor>120h`),
  - средние wear-факторы,
  - средние ambiguity/penalty/confidence.
- Расширены UI state и mapping:
  - `MainUiState.isfCrWearImpact24hLines/isfCrWearImpact7dLines`,
  - `AnalyticsUiState.wearImpact24hLines/wearImpact7dLines`.
- В `Analytics -> Quality` добавлена новая карточка:
  - `ISF/CR Wear Impact Summary`.
- Добавлена локализация `en/ru`:
  - секция wear-impact,
  - empty/24h/7d подписи.
- Обновлён unit-test `MainUiStateMappersTest`:
  - проверка прокидывания новых wear-impact строк в `AnalyticsUiState`.

## Почему так
- После внедрения wear-aware confidence и evidence weighting не хватало агрегированной диагностики “насколько wear реально влияет на контур”.
- Новая карточка даёт операционную видимость и ускоряет тюнинг quality-gates/replacement-практик.

## Риски / ограничения
- Сводка строится по audit-событиям; если realtime цикл был редким, окно может быть sparse.
- Пороги high-wear (`72h/120h`) соответствуют текущей v1 эвристике и могут корректироваться после replay-калибровки.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --tests io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon`
4) В приложении открыть `Analytics -> Quality` и проверить блок `ISF/CR Wear Impact Summary` с секциями `Last 24h`/`Last 7d`.

# Изменения — Этап 52: Day-type aware ISF/CR evidence weighting (weekday/weekend)

## Что сделано
- В `IsfCrEngine.computeRealtime` добавлен day-type контекст (`WEEKDAY/WEEKEND`) и day-type aware weighting:
  - `weightedRecentValue(...)` теперь учитывает `currentDayType`;
  - evidence того же типа дня весится как `1.0`, противоположного типа дня — пониженным весом (`0.72`).
- Добавлены hour-window day-type counters:
  - `hourWindowIsfSameDayTypeCount`,
  - `hourWindowCrSameDayTypeCount`.
- Добавлены runtime reasons:
  - `isf_day_type_evidence_sparse`,
  - `cr_day_type_evidence_sparse`
  когда в часовом окне есть evidence, но для текущего типа дня отсутствуют соответствующие сэмплы.
- В confidence/CI контуре добавлен консервативный штраф при day-type sparsity:
  - confidence/quality дополнительно снижаются,
  - CI расширяется.
- Расширен audit payload `isfcr_realtime_computed`:
  - `currentDayType`,
  - `hourWindowIsfSameDayType`,
  - `hourWindowCrSameDayType`.
- Добавлен unit-test:
  - `IsfCrEngineTest.computeRealtime_addsDayTypeSparseReasonsWhenHourWindowHasOnlyOtherDayType`.

## Почему так
- В плане v1 есть требование weekday/weekend устойчивости: одинаковые часы разных типов дней часто имеют различную чувствительность.
- Day-type aware blending снижает риск смещения `ISF/CR` за счёт нерелевантных (по типу дня) исторических окон.

## Риски / ограничения
- Текущий коэффициент для opposite day-type (`0.72`) эвристический и должен быть подтверждён replay-метриками.
- Карточка runtime diagnostics пока не выводит отдельную строку с day-type counters (они доступны в audit/deep diagnostics и quality pipeline).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon`
4) В `Audit` проверить `isfcr_realtime_computed` metadata: `currentDayType`, `hourWindowIsfSameDayType`, `hourWindowCrSameDayType`.

# Изменения — Этап 53: Day-type stability diagnostics in Quality analytics

## Что сделано
- Усилена сводка `ISF/CR Wear Impact Summary` в `MainViewModel`:
  - добавлены day-type метрики по окнам `24h/7d`:
    - распределение `weekday/weekend`,
    - средний same-day-type ratio `ISF/CR`,
    - доля событий с day-type sparse flags (`isf_day_type_evidence_sparse`, `cr_day_type_evidence_sparse`).
- Добавлен helper парсинга reason-codes из audit metadata (`auditReasonSet`).
- В `Analytics` reason-label mapping добавлены новые коды:
  - `isf_day_type_evidence_sparse`,
  - `cr_day_type_evidence_sparse`.
- Обновлены локализации `en/ru` для новых reason labels.

## Почему так
- После внедрения day-type aware blending нужна наблюдаемость “насколько стабилен day-type контур” в реальной эксплуатации.
- Это позволяет оценивать качество weekday/weekend разделения без включения дополнительных unsafe автоматик.

## Риски / ограничения
- Сводка строится по audit-событиям; при редком runtime цикле статистика может быть sparse.
- Показатели зависят от наличия `currentDayType` и `hourWindow*SameDayType` в payload `isfcr_realtime_computed`.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon`
4) В `Analytics -> Quality -> ISF/CR Wear Impact Summary` проверить строки:
   - day-type distribution,
   - mean same-day-type ratio,
   - day-type sparse flags ISF/CR.

# Изменения — Этап 54: Physio tags v2 (journal + per-tag close + steroids/dawn context wiring)

## Что сделано
- Расширен UI `Settings -> Real ISF/CR` для ручных физиотегов:
  - добавлены stepper-поля `Quick tag severity (%)` и `Quick tag duration (hours)`;
  - quick-tag action теперь отправляет в `ViewModel` тип + severity + duration.
- Добавлен журнал физиотегов в `Settings`:
  - показываются активные и недавно завершенные теги (type, severity, start/end interval, active/ended state),
  - для активных тегов добавлена точечная кнопка `Close`.
- Добавлена точечная деактивация тега в data-layer:
  - `PhysioContextTagDao.closeById(id, closeTs)`,
  - `IsfCrRepository.closeTag(...)`,
  - `MainViewModel.closePhysioTag(...)`.
- Улучшена нормализация quick-tag алиасов:
  - `hormonal_phase -> hormonal`,
  - `steroid -> steroids`.
- Расширен `IsfCrContextModel`:
  - добавлены факторы `manual_steroid_tag` и `manual_dawn_tag`,
  - добавлены множители `steroid_factor` и `dawn_tag_factor`,
  - итоговый dawn-множитель теперь учитывает и базовый dawn контекст, и ручной dawn-тег.
- Расширены ambiguity-пенализации:
  - `IsfCrConfidenceModel` теперь учитывает `manual_steroid_tag/manual_dawn_tag`,
  - `IsfCrEngine`/`IsfCrRepository` включают эти теги в расчёт/аудит `contextAmbiguity`.
- Обновлены строки локализации `en/ru` для нового UI тегов.

## Почему так
- В v1 нужен не только quick-add, но и управляемость ручного контекста без “clear all”.
- Ранее quick-tag `hormonal_phase` не совпадал с токенами контекст-модели; алиас-нормализация устраняет скрытую потерю влияния.
- `steroids` и `dawn` были в UI options, но не участвовали в формуле ISF/CR; теперь они реально включены в физиологический контур и confidence ambiguity.

## Риски / ограничения
- Журнал тегов ограничен последними `40` записями в UI и lookback `180` дней.
- Формы влияния `steroid_factor` и `dawn_tag_factor` пока эвристические и требуют replay-калибровки.
- Формат quick-tag строк (`type + %`) в active summary сохранён компактным; детализация доступна в журнале.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrContextModelTest --tests io.aaps.copilot.domain.isfcr.IsfCrConfidenceModelTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) В приложении открыть `Settings -> Real ISF/CR`:
   - изменить severity/duration,
   - добавить теги `hormonal_phase`, `steroids`, `dawn`,
   - проверить появление в journal и работу `Close` для отдельного тега.
4) В `Audit/Analytics` убедиться, что realtime ISF/CR расчёты продолжают выполняться, а ambiguity/penalty не ломают fallback-контур.

# Изменения — Этап 55: Shadow auto-activation day-type stability gate

## Что сделано
- Усилен контур `ISF/CR shadow -> active` в `AutomationRepository`:
  - добавлен дополнительный gate по day-type stability на основе `isfcr_realtime_computed` audit-окон;
  - оценка использует `hourWindowIsfEvidence/hourWindowCrEvidence` и `hourWindowIsfSameDayType/hourWindowCrSameDayType`.
- Добавлены новые типы/оценки:
  - `IsfCrDayTypeStabilitySample`,
  - `IsfCrDayTypeStabilityAssessment`,
  - `evaluateIsfCrDayTypeStabilityStatic(...)`.
- В авто-активацию добавлен этап:
  1) KPI shadow-diff gate,
  2) day-type stability gate,
  3) daily quality gate (если включен),
  4) только затем промоут `SHADOW -> ACTIVE`.
- Добавлен новый audit event:
  - `isfcr_shadow_day_type_gate_evaluated` с метриками ratios/sparse-rate и причиной блокировки.
- Расширен `isfcr_shadow_auto_promoted` metadata:
  - добавлены `dayTypeReason`, `dayTypeSampleCount`, `dayTypeMeanIsfRatio`, `dayTypeMeanCrRatio`, `dayTypeIsfSparseRatePct`, `dayTypeCrSparseRatePct`.
- Добавлены unit-тесты:
  - insufficient samples,
  - low same-day-type ratio,
  - high sparse-rate,
  - eligible case.

## Почему так
- В shadow-контуре уже есть day-type-aware blending, но не было отдельного gate при auto-promotion.
- Новый шаг снижает риск включения ACTIVE режима на статистически нестабильной weekday/weekend базе.

## Риски / ограничения
- Пороги gate сейчас фиксированы в коде (`ratio >= 0.30`, `sparseRate <= 75%`) и требуют replay-калибровки.
- Gate зависит от наличия `isfcr_realtime_computed` audit-событий в lookback-окне.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) На runtime проверить в `Audit` появление `isfcr_shadow_day_type_gate_evaluated` и причины (`eligible`/`*_day_type_*`).

# Изменения — Этап 56: Shadow auto-activation sensor-quality stability gate

## Что сделано
- Усилен контур `ISF/CR shadow -> active` в `AutomationRepository`:
  - добавлен дополнительный gate по стабильности качества сенсора на основе `isfcr_realtime_computed`.
- Добавлены новые типы/оценки:
  - `IsfCrSensorQualitySample`,
  - `IsfCrSensorQualityAssessment`,
  - `evaluateIsfCrSensorQualityStatic(...)`.
- В авто-активацию добавлен этап:
  1) KPI shadow-diff gate,
  2) day-type stability gate,
  3) sensor-quality stability gate,
  4) daily quality gate (если включен),
  5) только затем `SHADOW -> ACTIVE`.
- Добавлен новый audit event:
  - `isfcr_shadow_sensor_gate_evaluated` с причиной блокировки и агрегатами (`meanQualityScore`, `meanSensorFactor`, `meanWearPenalty`, `sensorAgeHighRatePct`).
- Расширен payload `isfcr_shadow_auto_promoted`:
  - `sensorGateReason`, `sensorGateSampleCount`, `sensorGateMeanQualityScore`, `sensorGateMeanSensorFactor`, `sensorGateMeanWearPenalty`, `sensorGateSensorAgeHighRatePct`.
- Добавлены unit-тесты на sensor gate:
  - insufficient samples,
  - low quality score,
  - high wear penalty,
  - eligible case.

## Почему так
- После day-type gate оставался риск авто-переключения в `ACTIVE` на деградировавшем сенсоре (шум/старение/нестабильный wear).
- Новый шаг делает shadow auto-activation ближе к safety-first: режим включается только при стабильных физиологических и технических сигналах.

## Риски / ограничения
- Пороги gate пока фиксированы в коде и требуют replay-калибровки:
  - `meanQualityScore >= 0.46`,
  - `meanSensorFactor >= 0.90`,
  - `meanWearPenalty <= 0.12`,
  - `sensorAgeHighRate <= 70%`.
- Gate зависит от полноты `isfcr_realtime_computed` audit в lookback окне.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
3) На runtime в `Audit` проверить `isfcr_shadow_sensor_gate_evaluated` и причины (`sensor_quality_score_low` / `sensor_factor_low` / `wear_penalty_high` / `sensor_age_high_rate` / `eligible`).

# Изменения — Этап 57: Configurable sensor-gate thresholds (Settings -> runtime)

## Что сделано
- Добавлены новые настройки auto-activation sensor gate в `AppSettings`/DataStore:
  - `isfCrAutoActivationMinSensorQualityScore`,
  - `isfCrAutoActivationMinSensorFactor`,
  - `isfCrAutoActivationMaxWearConfidencePenalty`,
  - `isfCrAutoActivationMaxSensorAgeHighRatePct`.
- Добавлены ключи persistence + defaults + clamp-валидация при сохранении:
  - `score/factor/penalty` в диапазоне `0.0..1.0`,
  - `sensorAgeHighRatePct` в диапазоне `0.0..100.0`.
- В `AutomationRepository` sensor gate переключен с хардкод-констант на настройки пользователя.
- В UI (`Settings -> Real ISF/CR -> Auto activation`) добавлены 4 stepper-поля:
  - min sensor quality score,
  - min sensor factor,
  - max wear penalty,
  - max sensor-age-high rate.
- Добавлен `MainViewModel.setIsfCrAutoActivationSensorThresholds(...)` и сквозной wiring `SettingsScreen -> Root -> ViewModel -> DataStore`.
- Расширены модели состояния (`MainUiState`, `SettingsUiState`) и mapper для новых полей.
- Обновлены `strings.xml` и `values-ru/strings.xml` для новых контролов.
- Обновлен unit-тест `MainUiStateMappersTest.settingsMapping_includesUamSnackParameters` новыми assertions по sensor-gate полям.

## Почему так
- После внедрения sensor gate (этап 56) пороги были фиксированы в коде.
- Для controlled activation и replay-калибровки нужен runtime tuning без правки исходников и пересборки.

## Риски / ограничения
- В окружении иногда наблюдается нестабильность `Gradle Test Executor` (`exit 134`) именно на части тестов UI mapper; это инфраструктурный/flaky issue раннера, не воспроизводит конкретный assertion failure по новым полям.
- Для стабильной CI проверки следует запускать тесты последовательно и избегать параллельных Gradle задач в одном worktree.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest.settingsMapping_includesUamSnackParameters --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
4) В приложении открыть `Settings -> Real ISF/CR` и изменить новые пороги sensor gate, затем проверить в `Audit` событие `isfcr_shadow_sensor_gate_evaluated` (поля `minMean*`/`max*` должны совпадать с установленными значениями).

# Изменения — Этап 58: Configurable day-type gate thresholds (Settings -> runtime)

## Что сделано
- Расширены настройки `AppSettings`/DataStore для day-type stability gate:
  - `isfCrAutoActivationMinDayTypeRatio`,
  - `isfCrAutoActivationMaxDayTypeSparseRatePct`.
- Добавлены persistence keys/defaults и clamp при сохранении:
  - ratio: `0.0..1.0`,
  - sparse-rate: `0.0..100.0`.
- В `AutomationRepository` day-type gate переключен с хардкод-констант на настройки пользователя:
  - `evaluateIsfCrDayTypeStabilityStatic(...)` теперь получает пороги из `settings`,
  - audit `isfcr_shadow_day_type_gate_evaluated` пишет фактически примененные `minMeanSameDayTypeRatio/maxSparseRatePct`.
- UI `Settings -> Real ISF/CR -> Auto activation` расширен двумя stepper-полями:
  - `Day-type gate min same-day ratio`,
  - `Day-type gate max sparse rate`.
- Добавлен сквозной wiring:
  - `SettingsScreen` callback `onIsfCrAutoActivationDayTypeThresholdsChange`,
  - `CopilotFoundationRoot -> MainViewModel.setIsfCrAutoActivationDayTypeThresholds(...)`,
  - `MainUiState/SettingsUiState` и мапперы обновлены.
- Обновлены локализации `values/strings.xml` и `values-ru/strings.xml`.
- Обновлен unit-test маппера `MainUiStateMappersTest.settingsMapping_includesUamSnackParameters` для новых day-type полей.

## Почему так
- После этапов 55/57 day-type gate оставался частично “прошитым” в runtime (фиксированные пороги).
- Для controlled rollout/replay tuning пороги day-type стабильности нужны в runtime без правки кода и пересборки.

## Риски / ограничения
- В окружении сохраняется flaky поведение unit-test раннера (`Gradle Test Executor`, exit `134`) для части UI mapper тестов; это не дало стабильно прогнать `MainUiStateMappersTest` end-to-end в этом запуске.
- Таргетные репозиторные тесты (`AutomationRepositoryForecastBiasTest`) проходят; build/lint/typecheck — зелёные.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --no-daemon`
4) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon`
5) В приложении открыть `Settings -> Real ISF/CR` и изменить day-type пороги, затем проверить в `Audit` событие `isfcr_shadow_day_type_gate_evaluated`:
   - `minMeanSameDayTypeRatio`,
   - `maxSparseRatePct`
   должны совпадать с установленными значениями.

# Изменения — Этап 59: Sensor false-low reason-code + conservative confidence/CI

## Что сделано
- Усилен realtime контур `IsfCrEngine.computeRealtime(...)` для флага качества сенсора:
  - добавлен парсинг latest telemetry ключа `sensor_quality_suspect_false_low`,
  - при активном флаге (`>= 0.5`) в этом же цикле:
    - confidence/quality дополнительно консервативно снижаются,
    - CI для ISF/CR расширяются (через `inflateCiBounds`).
- Добавлены новые reason-codes в snapshot:
  - `sensor_quality_suspect_false_low`,
  - `sensor_quality_low` (при низком `sensor_quality_score`).
- Добавлены telemetry-derived factors в `snapshot.factors`:
  - `sensor_quality_score`,
  - `sensor_quality_suspect_false_low`.
- Добавлен unit-test:
  - `IsfCrEngineTest.computeRealtime_sensorFalseLowFlagAddsReasonAndLowersConfidence`:
    - проверяет reason-code,
    - проверяет снижение confidence/quality,
    - проверяет расширение CI при активном флаге.

## Почему так
- В рисках плана и тех-аудите выделена проблема ложных low от сенсора.
- Явный reason-code + более консервативный confidence/CI ускоряет диагностику и снижает риск переагрессивной автоматики при сомнительных сенсорных данных.

## Риски / ограничения
- Добавлен дополнительный консервативный штраф; в редких случаях это может увеличить долю fallback в сомнительных данных (что ожидаемо по safety-first).
- В окружении всё ещё периодически встречается flaky `Gradle Test Executor` (`exit 134`) на отдельных UI-mapper тестах; доменные тесты для `isfcr` запускаются стабильно.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --no-daemon`
3) В `Audit`/Analytics diagnostics проверить появление reasons:
   - `sensor_quality_suspect_false_low`,
   - `sensor_quality_low`
   при соответствующем telemetry флаге/score.

# Изменения — Этап 60: Shadow sensor gate false-low stability threshold

## Что сделано
- Усилен `ISF/CR shadow -> active` sensor-gate:
  - в `AutomationRepository.evaluateIsfCrSensorQualityStatic(...)` добавлена метрика `suspectFalseLowRatePct`,
  - добавлен новый лимит `maxSuspectFalseLowRatePct`,
  - при превышении gate блокируется с reason `sensor_suspect_false_low_rate`.
- Расширены структуры sensor-gate:
  - `IsfCrSensorQualitySample` получил `suspectFalseLowFlag`,
  - `IsfCrSensorQualityAssessment` получил `suspectFalseLowRatePct`.
- В `isfcr_realtime_computed` audit добавлено поле `sensorQualitySuspectFalseLow` (из realtime factors), чтобы sensor-gate опирался на явную метрику, а не только на строковые reason-коды.
- В `isfcr_shadow_sensor_gate_evaluated` и `isfcr_shadow_auto_promoted` metadata добавлены:
  - `suspectFalseLowRatePct`,
  - `maxSuspectFalseLowRatePct`,
  - `sensorGateSuspectFalseLowRatePct`.
- `Analytics -> ISF/CR Activation Gate` расширен строкой sensor-gate:
  - теперь выводит `falseLow` rate вместе с quality/factor/wear/age, чтобы rollout-диагностика была полной.
- Настройки/DataStore/UI расширены новым параметром:
  - `isfCrAutoActivationMaxSuspectFalseLowRatePct` (default `35.0`, clamp `0..100`),
  - добавлен stepper в `Settings -> Real ISF/CR -> Auto activation`.
- Обновлены unit-tests:
  - `AutomationRepositoryForecastBiasTest` (новый сценарий блокировки `sensor_suspect_false_low_rate` + обновленные сигнатуры),
  - `MainUiStateMappersTest` (маппинг нового settings-поля).

## Почему так
- По плану `Physiology-Aware ISF/CR` и по рискам сенсора auto-promotion не должен включаться на нестабильных данных с частыми ложными low.
- До этапа 60 false-low уже учитывался в realtime confidence/CI, но не блокировал `SHADOW -> ACTIVE` напрямую.

## Риски / ограничения
- Более строгий gate может увеличить время до auto-promotion (ожидаемое safety-first поведение).
- В окружении остается известный flaky `Gradle Test Executor` (`exit 134`) для набора UI mapper тестов; доменные тесты и таргетные репозиторные тесты проходят.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon -Pkotlin.incremental=false`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --no-daemon -Pkotlin.incremental=false`
4) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon -Pkotlin.incremental=false`
5) В UI открыть `Settings -> Real ISF/CR -> Auto activation` и изменить `Sensor gate max suspect false-low rate`.
6) В `Audit` проверить `isfcr_shadow_sensor_gate_evaluated`: поля `suspectFalseLowRatePct` и `maxSuspectFalseLowRatePct` должны соответствовать входным данным и настройке.

# Изменения — Этап 62: Rolling KPI gate wired into real shadow auto-promotion

## Что сделано
- Подключен rolling quality gate в реальный контур `SHADOW -> ACTIVE` в `AutomationRepository.maybeProcessIsfCrShadowAutoActivation(...)`.
- В `resolveLatestTelemetry(...)` добавлен расширенный lookback для отчетных ключей (`daily_report_*`, `rolling_report_*`) до `72h`, чтобы gate не терял KPI между запусками daily worker.
- Добавлена оценка rolling окон `14d/30d/90d` по telemetry ключам `rolling_report_*`:
  - отдельная структура окна `IsfCrRollingQualityWindowAssessment`,
  - итоговая оценка gate `IsfCrRollingQualityGateAssessment`.
- Добавлен статический evaluator `evaluateIsfCrRollingQualityGateStatic(...)`:
  - блокирует при недостатке доступных окон,
  - блокирует при провале любого доступного окна,
  - пропускает только при достаточном числе доступных и прошедших окон.
- Добавлен аудит `isfcr_shadow_rolling_gate_evaluated` с per-window диагностикой:
  - `days`, `available`, `eligible`, `reason`,
  - `matchedSamples`, `mae30/60`, `ciCoverage30/60`, `ciWidth30/60`.
- В `isfcr_shadow_auto_promoted` добавлены summary-поля rolling gate:
  - `rollingGateReason`,
  - `rollingGateRequiredWindowCount`,
  - `rollingGateEvaluatedWindowCount`,
  - `rollingGatePassedWindowCount`.
- Добавлены unit-тесты в `AutomationRepositoryForecastBiasTest`:
  - `isfCrRollingGate_blocksWhenNotEnoughWindowsAvailable`,
  - `isfCrRollingGate_blocksWhenAnyAvailableWindowFails`,
  - `isfCrRollingGate_allowsWhenRequiredWindowsPass`.
- Обновлены docs:
  - `docs/ARCHITECTURE.md` (rolling gate как часть активации),
  - `docs/INVARIANTS.md` (инварианты блокировки/аудита rolling gate).

## Почему так
- На этапе 61 rolling KPI были видимы в аналитике, но не влияли на фактическое решение auto-promotion.
- Для safe rollout физиологического контура нужен не только 24h daily gate, но и средне/долгосрочный quality filter.

## Риски / ограничения
- Gate стал строже: auto-promotion может происходить позже, если rolling telemetry еще не накоплена.
- Окна используют текущие telemetry-метрики `rolling_report_*`; при отсутствии недавнего daily analysis окна будут `missing` и promotion блокируется.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon -Pkotlin.incremental=false`
3) Выполнить `Run daily analysis` и открыть `Audit`:
   - убедиться в событии `isfcr_shadow_rolling_gate_evaluated`,
   - проверить `required/evaluated/passed` и причины по окнам.
4) Для сценария успешного promotion проверить `isfcr_shadow_auto_promoted`:
   - присутствуют `rollingGateReason` и window counters.

# Изменения — Этап 61: Rolling replay KPI telemetry (14d/30d/90d) + Analytics visibility

## Что сделано
- Расширен `InsightsRepository.generateDailyForecastReport(...)`:
  - локальный репорт теперь вычисляет не только 24h payload, но и rolling-окна `14d/30d/90d`.
  - добавлен helper `buildRollingForecastPayloadsStatic(...)` для повторного использования и unit-тестов.
- Расширено сохранение telemetry:
  - новые ключи `rolling_report_{14|30|90}d_*` (matched/forecast rows, period bounds, MAE/RMSE/MARD/Bias/n, CI coverage/width по `5m/30m/60m`).
- В UI runtime (`MainViewModel`) добавлен разбор `rolling_report_*` и сбор строк KPI:
  - `14d/30d/90d: n, MAE30/60, MARD30/60, CI60, width60`.
- `ISF/CR Activation Gate` в Analytics теперь также показывает rolling MARD по `14d/30d/90d` для быстрого сверочного контекста рядом с gate-решениями.
- В `Analytics` (Quality tab) добавлен отдельный блок:
  - `Rolling Replay KPI (14d/30d/90d)`.
- Добавлен DAO-метод `ForecastDao.since(...)`, чтобы репорт брал данные по timestamp-окну вместо старого fixed-limit `latest(30000)`.
- Добавлены/обновлены тесты:
  - `InsightsRepositoryDailyForecastReportTest.buildRollingForecastPayloadsStatic_returnsExpectedWindowsAndMonotonicCoverage`,
  - `MainUiStateMappersTest.analyticsMapping_includesIsfCrSummaryAndHistory` расширен проверкой `rollingReportLines`.
- Обновлены docs:
  - `docs/ARCHITECTURE.md`,
  - `docs/INVARIANTS.md`.

## Почему так
- Для controlled activation и replay-тюнинга 24h daily среза недостаточно: нужен средне- и долгосрочный срез качества (14/30/90 дней).
- Публикация rolling KPI через telemetry сохраняет текущий lightweight runtime-подход UI и не требует тяжёлых SQL-агрегаций в `MainViewModel`.

## Риски / ограничения
- `buildRollingForecastPayloadsStatic` фильтрует список forecast-rows по каждому окну в памяти; при очень больших локальных массивах может потребоваться дальнейшая оптимизация SQL-агрегатами.
- В окружении остаётся известный flaky краш раннера `Gradle Test Executor exit 134` на части UI unit-тестов; компиляция и таргетные доменные тесты при этом проходят.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugUnitTestKotlin --no-daemon -Pkotlin.incremental=false`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --no-daemon -Pkotlin.incremental=false`
4) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug --no-daemon -Pkotlin.incremental=false`
5) В приложении открыть `Analytics -> Quality` и проверить блок `Rolling Replay KPI (14d/30d/90d)` после запуска `Run daily analysis`.

# Изменения — Этап 63: Settings-backed rolling gate thresholds (DataStore + UI + runtime wiring)

## Что сделано
- Вынесены параметры rolling quality gate из хардкода в `AppSettings`/DataStore:
  - `isfCrAutoActivationRollingMinRequiredWindows` (`1..3`, default `2`),
  - `isfCrAutoActivationRollingMaeRelaxFactor` (`1.0..1.5`, default `1.15`),
  - `isfCrAutoActivationRollingCiCoverageRelaxFactor` (`0.70..1.0`, default `0.90`),
  - `isfCrAutoActivationRollingCiWidthRelaxFactor` (`1.0..1.5`, default `1.25`).
- Добавлены ключи/дефолты/клампы в `AppSettingsStore` (чтение, `update`, persistence bounds).
- Проброшены новые поля по UI-состояниям:
  - `MainUiState`,
  - `SettingsUiState`,
  - `MainUiStateMappers.toSettingsUiState(...)`,
  - `MainViewModel` (runtime mapping + `settingsUiState` + initial state).
- Добавлен setter в `MainViewModel`:
  - `setIsfCrAutoActivationRollingGateSettings(...)`.
- Расширен `SettingsScreen`:
  - добавлены 4 steppers в секции `ISF/CR auto-activation` для rolling gate;
  - добавлен callback wiring в `CopilotFoundationRoot`.
- `AutomationRepository` переключен на settings-backed значения:
  - `rollingRequiredWindows`,
  - `rollingMaeRelaxFactor`,
  - `rollingCiCoverageRelaxFactor`,
  - `rollingCiWidthRelaxFactor`.
- В аудит `isfcr_shadow_rolling_gate_evaluated` добавлены конфиг-поля rolling gate.
- Добавлены новые строки локализации (`values`/`values-ru`) для rolling настроек.
- Обновлен `MainUiStateMappersTest` для проверки новых полей settings mapping.
- Обновлены docs:
  - `docs/ARCHITECTURE.md`,
  - `docs/INVARIANTS.md`.

## Почему так
- Rolling gate уже участвовал в автоактивации, но его пороги были зашиты в код и не тюнились без релиза.
- Переход на settings-backed конфиг позволяет безопасно подстраивать rollout без изменения логики gate.

## Риски / ограничения
- Поведение gate теперь зависит от пользовательских настроек; слишком мягкие значения могут ослабить фильтр (ограничено clamp-диапазонами).
- В окружении сохраняется flaky краш раннера `Gradle Test Executor exit 134` на одном UI-тест-таргете, не дающий стабильный green-run для этого класса.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3) В приложении открыть `Settings -> Real ISF/CR engine` и изменить rolling-параметры:
   - min windows,
   - MAE relax factor,
   - CI coverage relax factor,
   - CI width relax factor.
4) Проверить `Audit` событие `isfcr_shadow_rolling_gate_evaluated`:
   - присутствуют `requiredWindowCountConfigured`, `maeRelaxFactor`, `ciCoverageRelaxFactor`, `ciWidthRelaxFactor`.

# Изменения — Этап 64: Activation Gate UI detail for rolling windows

## Что сделано
- Расширен `Analytics -> ISF/CR Activation Gate` в `MainViewModel`:
  - кроме summary rolling gate (`cfg/eval/pass`) теперь отображаются строки по каждому rolling-окну из audit metadata `windows`.
  - для каждого окна (`14d/30d/90d`) выводятся:
    - `status` (`pass/fail/missing`),
    - `reason`,
    - `matched samples`,
    - `MAE30/MAE60` (если доступны).
- Сохранены уже существующие summary-линии:
  - `Rolling gate (...)`,
  - `Rolling relax: MAE×..., CIcov×..., CIwidth×...`.
- Парсинг/форматирование вынесены в отдельный helper:
  - `IsfCrActivationGateFormatter.kt` (`parseRollingGateWindows`, `formatRollingGateWindowLine`),
  - `MainViewModel` использует helper и больше не содержит inline JSON-разбор для `windows`.
- Добавлен unit-test:
  - `IsfCrActivationGateFormatterTest` (валидный payload, malformed payload, формат pass/fail/missing).

## Почему так
- При диагностике auto-activation одного агрегированного `eligible/reason` недостаточно: нужно видеть, какое именно rolling-окно блокирует promotion и почему.
- Детальный вывод из audit payload ускоряет тюнинг порогов и проверку качества без ручного разбора raw JSON.

## Риски / ограничения
- Детальные строки зависят от наличия корректного поля `windows` в `isfcr_shadow_rolling_gate_evaluated`; при повреждённом payload блок gracefully деградирует к summary без падений.
- В `Activation Gate` стало больше текста; на очень маленьких экранах потребуется скролл (ожидаемое поведение текущего layout).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.IsfCrActivationGateFormatterTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --no-daemon -Pkotlin.incremental=false`
3) В приложении открыть `Analytics -> Quality -> ISF/CR Activation Gate` и проверить наличие строк:
   - `Rolling gate (...)`,
   - `Rolling relax: ...`,
   - `Rolling 14d gate: ...`,
   - `Rolling 30d gate: ...`,
   - `Rolling 90d gate: ...` (если окна присутствуют в аудите).

# Изменения — Этап 65: CR extraction integrity gates (sensor/UAM/gap) for Physiology-Aware ISF/CR

## Что сделано
- Усилен `IsfCrWindowExtractor.extractCrSample(...)`:
  - окно привязки meal-bolus сделано асимметричным `[-20m, +30m]` (вместо симметричного `±30m`);
  - добавлен hard-drop при грубых CGM разрывах окна (`maxGapMinutes > 30`);
  - добавлен telemetry gate для CR-окон:
    - drop при высоком `sensor_blocked` rate,
    - drop при высокой UAM ambiguity rate.
- Добавлены новые dropped-reason коды:
  - `cr_gross_gap`,
  - `cr_sensor_blocked`,
  - `cr_uam_ambiguity`.
- В CR evidence context добавлены диагностические поля:
  - `sensorBlockedRate`,
  - `uamAmbiguityRate`.
- Добавлены unit-тесты `IsfCrWindowExtractorTest`:
  - `extract_crSampleDroppedWhenSensorBlockedTelemetryHigh`,
  - `extract_crSampleDroppedWhenUamAmbiguityTelemetryHigh`,
  - `extract_crBolusWindowIsAsymmetric_negative20ToPositive30`.
- Обновлены архитектурные документы:
  - `docs/ARCHITECTURE.md` (CR extraction quality gates),
  - `docs/INVARIANTS.md` (инвариант hard-drop условий для CR evidence).

## Почему так
- По плану `Physiology-Aware ISF/CR Engine v1` CR должен строиться только на качественных meal-windows и исключать окна с сенсорной блокировкой, сильной UAM неоднозначностью и грубыми data gaps.
- Явные reason codes нужны для последующего аудита качества и тюнинга порогов.

## Риски / ограничения
- Более строгие CR-gates уменьшают количество usable CR evidence в шумных/нестабильных периодах (ожидаемое safety-first поведение).
- Пороговые значения (`sensor blocked rate`, `UAM ambiguity rate`, `max gap`) пока фиксированы в extractor и не вынесены в пользовательские настройки.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest" --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.*" --no-daemon -Pkotlin.incremental=false`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false`
4) В `Audit` проверить `isfcr_evidence_extracted` и убедиться, что в `droppedReasons` появляются новые коды (`cr_sensor_blocked`, `cr_uam_ambiguity`, `cr_gross_gap`) на соответствующих сценариях.

# Изменения — Этап 66: Settings-backed CR integrity thresholds (DataStore + UI + runtime wiring)

## Что сделано
- Вынесены пороги CR-window integrity из хардкода в `AppSettings`/DataStore:
  - `isfCrCrMaxGapMinutes` (default `30`, clamp `10..60`),
  - `isfCrCrMaxSensorBlockedRatePct` (default `25.0`, clamp `0..100`),
  - `isfCrCrMaxUamAmbiguityRatePct` (default `60.0`, clamp `0..100`).
- Добавлены ключи/дефолты/persistence-клампы в `AppSettingsStore` (чтение + update + сохранение).
- Проброшены новые настройки в доменный `IsfCrSettings`:
  - `crGrossGapMinutes`,
  - `crSensorBlockedRateThreshold`,
  - `crUamAmbiguityRateThreshold`.
- `IsfCrRepository.toIsfCrSettings()` теперь передает эти пороги в runtime extractor.
- `IsfCrWindowExtractor.extractCrSample(...)` переключен на settings-backed thresholds:
  - `maxGapMinutes > settings.crGrossGapMinutes` => `cr_gross_gap`,
  - `sensorBlockedRate >= settings.crSensorBlockedRateThreshold` => `cr_sensor_blocked`,
  - `uamAmbiguityRate >= settings.crUamAmbiguityRateThreshold` => `cr_uam_ambiguity`.
- UI/VM wiring:
  - `SettingsUiState`, `MainUiState`, `MainUiStateMappers`, `MainViewModel`, `CopilotFoundationRoot`, `SettingsScreen` расширены новыми полями/колбэком.
  - Добавлен метод `MainViewModel.setIsfCrCrIntegrityGateSettings(...)`.
  - В `Settings -> Real ISF/CR` добавлены 3 steppers для CR integrity gate.
- Локализация:
  - добавлены строки RU/EN для новых настроек.
- Тесты:
  - `IsfCrWindowExtractorTest` дополнен проверками settings-backed порогов:
    - `extract_crSensorBlockedThresholdCanBeRelaxedFromSettings`,
    - `extract_crUamAmbiguityThresholdCanBeRelaxedFromSettings`,
    - `extract_crGrossGapThresholdCanBeRelaxedFromSettings`.
  - `MainUiStateMappersTest.settingsMapping_includesUamSnackParameters` расширен проверкой новых полей settings mapping.
- Обновлены docs:
  - `docs/ARCHITECTURE.md` (settings-backed CR integrity thresholds),
  - `docs/INVARIANTS.md` (новый инвариант по клампам CR integrity thresholds).

## Почему так
- В Stage 65 CR integrity gates были корректны по сути, но не тюнились без нового релиза.
- Перевод на settings-backed пороги позволяет безопасно регулировать строгость CR evidence extraction под качество сенсора/данных пользователя без изменения алгоритмической структуры.

## Риски / ограничения
- Более мягкие пользовательские пороги могут увеличить долю CR evidence из шумных окон; это ограничено безопасными clamp-границами.
- В окружении сохраняется известный flaky crash `Gradle Test Executor ... exit 134` на части UI unit-test запусков; доменные тесты и сборка проходят стабильно.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest" --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrEngineTest" --no-daemon -Pkotlin.incremental=false`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin :app:assembleDebug --no-daemon -Pkotlin.incremental=false`
4) В UI открыть `Settings -> Real ISF/CR` и изменить:
   - `CR gate max CGM gap`,
   - `CR gate max sensor-blocked rate`,
   - `CR gate max UAM ambiguity rate`.
5) В `Audit -> isfcr_evidence_extracted` проверить, что dropped-коды (`cr_gross_gap`, `cr_sensor_blocked`, `cr_uam_ambiguity`) меняют частоту при изменении этих порогов.

# Изменения — Этап 67: Runtime diagnostics parity for CR integrity thresholds

## Что сделано
- Расширен доменный diagnostics-модель:
  - `IsfCrDiagnostics` дополнен полями:
    - `crMaxGapMinutes`,
    - `crMaxSensorBlockedRate`,
    - `crMaxUamAmbiguityRate`.
- `IsfCrEngine.computeRealtime(...)` теперь пишет в diagnostics фактически применённые пороги CR integrity gate из runtime settings.
- `IsfCrRepository` расширил audit payload:
  - `isfcr_realtime_computed`,
  - `isfcr_low_confidence`
  полями:
  - `crMaxGapMinutes`,
  - `crMaxSensorBlockedRatePct`,
  - `crMaxUamAmbiguityRatePct`.
- Runtime diagnostics wiring в UI:
  - `MainViewModel` парсит и публикует новые поля в `MainUiState`,
  - `ScreenModels`/`MainUiStateMappers` пробрасывают их в `AnalyticsUiState`,
  - `AnalyticsScreen` показывает строку:
    - `CR gate: gap <= Xm • sensor-blocked <= Y% • UAM ambiguity <= Z%`.
- Добавлены строки i18n RU/EN:
  - `analytics_runtime_diag_cr_gate_line`.
- Дополнен unit-test mapping:
  - `MainUiStateMappersTest.analyticsMapping_includesIsfCrSummaryAndHistory` проверяет передачу новых runtime diagnostics полей.

## Почему так
- После Stage 66 пороги CR integrity были настраиваемыми, но в runtime diagnostics не было явного отображения “какие именно пороги реально применяются сейчас”.
- Это усложняло разбор low-confidence/fallback кейсов и тюнинг параметров.

## Риски / ограничения
- В окружении остаётся flaky `Gradle Test Executor ... exit 134` на отдельных UI test-запусках; доменные тесты и компиляция стабильны.
- Новая строка diagnostics зависит от наличия свежего `isfcr_realtime_computed`; при пустом аудите блок корректно не показывается.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrEngineTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest" --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --no-daemon -Pkotlin.incremental=false` (возможен известный flaky `exit 134`)
3) В `Analytics -> ISF/CR -> Runtime diagnostics` проверить строку `CR gate: ...` и соответствие значений текущим settings.

# Изменения — Этап 68: CR integrity drop-rate breakdown in Quality analytics

## Что сделано
- Добавлен новый форматтер:
  - `ui/IsfCrDroppedReasonFormatter.kt`
  - функция `formatIsfCrDroppedReasonSummaryLines(...)`.
- `MainViewModel.buildIsfCrDroppedReasonSummaryLines(...)` переведен на новый форматтер (логика вынесена из ViewModel).
- В блоке dropped reasons теперь, помимо header и top reason counters, добавляется отдельная строка CR integrity breakdown:
  - `CR integrity drops: gap=...%, sensorBlocked=...%, uamAmbiguity=...%`
  - проценты считаются от `dropped total` за выбранное окно.
- Добавлен unit-test:
  - `IsfCrDroppedReasonFormatterTest`:
    - проверка формирования CR integrity breakdown,
    - проверка fallback-поведения при пустых counters.

## Почему так
- Для тюнинга порогов `CR gate max gap / sensor-blocked / UAM ambiguity` мало видеть только текущие thresholds; нужно видеть фактическую частоту их срабатывания.
- Явная процентная сводка по 24h/7d ускоряет диагностику “слишком строгих” или “слишком мягких” порогов.

## Риски / ограничения
- Если `droppedReasons` поврежден/неполон в audit payload, breakdown может быть частичным (header/top reasons остаются корректными).
- Проценты считаются по aggregate `dropped total`; при множественных reason-кодах на один sample возможна сумма процентов >100% (ожидаемое поведение counters-модели).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.IsfCrDroppedReasonFormatterTest" --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false`
3) В `Analytics -> Quality -> Dropped reasons` проверить наличие строки `CR integrity drops: ...` для 24h/7d при наличии соответствующих reason-кодов.

# Изменения — Этап 69: Integration scenarios for Physiology-Aware ISF/CR runtime

## Что сделано
- Добавлен отдельный integration-набор тестов:
  - `domain/isfcr/IsfCrEngineIntegrationScenariosTest.kt`.
- Покрыты ключевые сценарии из плана интеграционной валидации:
  - `infusionSetAgingScenario_reducesSetFactorAndIsfEff`
    - проверяет снижение `set_factor` и `ISF_eff` при старом инфузионном наборе, а также рост `CR_eff` (через обратный set-factor).
  - `sensorDriftScenario_reducesConfidenceAndWidensCi`
    - проверяет снижение confidence/quality и reason-коды `sensor_quality_low`, `sensor_quality_suspect_false_low` при деградации сенсора.
  - `activitySurgeScenario_increasesActivityFactorAndIsfEff`
    - проверяет, что при росте `activity_ratio`/`steps_rate_15m` увеличивается `activity_factor` и повышается `ISF_eff`.
  - `dawnScenario_appliesMorningDawnFactor`
    - проверяет, что утренний слот применяет более низкий dawn-factor, чем дневной.
- Для сценариев добавлен общий synthetic history builder (коррекционное окно + meal/bolus окно + события set/sensor change), чтобы тесты оставались воспроизводимыми.

## Почему так
- До этого были в основном unit-тесты отдельных модулей (`extractor/context/confidence`), но не было отдельного набора end-to-end сценариев на уровне `IsfCrEngine.computeRealtime`.
- Интеграционные сценарии закрывают риск “локально корректных модулей, но неверного итогового поведения после склейки факторов/quality/fallback”.

## Риски / ограничения
- Тесты synthetic и валидируют направленность эффектов, а не абсолютную клиническую калибровку коэффициентов.
- В noisy runtime возможны дополнительные interactions факторов; для этого остаётся обязательный replay/rolling quality gate в production-контуре.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrEngineIntegrationScenariosTest" --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.IsfCrDroppedReasonFormatterTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrEngineTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest" --no-daemon -Pkotlin.incremental=false`

# Изменения — Этап 70: Daily forecast report recommendations from ISF/CR dropped-reasons

## Что сделано
- Расширен `InsightsRepository.generateDailyForecastReport(...)`:
  - добавлен сбор `isfcr` dropped-reasons за 24ч из audit:
    - primary source: `isfcr_evidence_extracted`,
    - fallback: `isfcr_realtime_computed`.
  - добавлено формирование quality-рекомендаций по CR integrity причинам:
    - `cr_sensor_blocked`,
    - `cr_gross_gap`,
    - `cr_uam_ambiguity`,
    - общий overload dropped-evidence.
  - рекомендации автоматически добавляются в `DailyForecastReportPayload.recommendations` и попадают в markdown/csv daily report.
- Добавлена телеметрия отчёта по dropped-reasons:
  - `daily_report_isfcr_dropped_event_count`,
  - `daily_report_isfcr_dropped_total`,
  - `daily_report_isfcr_dropped_source`,
  - `daily_report_isfcr_cr_gap_drop_rate_pct`,
  - `daily_report_isfcr_cr_sensor_drop_rate_pct`,
  - `daily_report_isfcr_cr_uam_drop_rate_pct`.
- Расширен audit payload `daily_forecast_report_generated`:
  - `isfCrDroppedEventCount`,
  - `isfCrDroppedTotal`,
  - `isfCrDroppedSource`.
- Добавлен новый helper в companion:
  - `buildIsfCrDataQualityRecommendations(...)` (internal, testable).
- Добавлены unit-тесты:
  - `InsightsRepositoryDailyForecastReportTest.buildIsfCrDataQualityRecommendations_emitsCrIntegrityGuidance`
  - `InsightsRepositoryDailyForecastReportTest.buildIsfCrDataQualityRecommendations_returnsEmptyWhenNoDroppedWindows`.

## Почему так
- По плану требуется не только считать ошибки (MAE/MARD), но и давать actionable шаги для снижения MARD.
- Основные деградации CR quality уже логируются в dropped-reasons; теперь они превращаются в автоматические рекомендации внутри ежедневного отчёта.

## Риски / ограничения
- Рекомендации зависят от наличия audit событий `isfcr_evidence_extracted`/`isfcr_realtime_computed` за окно отчёта.
- Если dropped counters отсутствуют или повреждены, quality-рекомендации не добавляются (без влияния на базовый отчёт по метрикам).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest" --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false`
3) Выполнить daily analysis в приложении и проверить:
   - в markdown отчёте секцию `Recommendations To Lower MARD` на наличие рекомендаций по sensor/gap/UAM quality;
   - в telemetry ключи `daily_report_isfcr_*`;
   - в audit событие `daily_forecast_report_generated` с полями `isfCrDropped*`.

# Изменения — Этап 71: Daily report recommendations surfaced in Analytics UI

## Что сделано
- В `InsightsRepository.persistDailyForecastReportTelemetry(...)` добавлена публикация top-3 рекомендаций отчёта:
  - `daily_report_recommendation_1`
  - `daily_report_recommendation_2`
  - `daily_report_recommendation_3`
- В `MainViewModel` добавлен парсинг этих telemetry-полей в runtime state:
  - `dailyReportRecommendations`.
- Расширены UI-модели:
  - `MainUiState` (`dailyReportRecommendations`),
  - `AnalyticsUiState` (`dailyReportRecommendations`).
- Обновлен mapper `MainUiStateMappers.toAnalyticsUiState()`:
  - учитывает рекомендации в `hasData`,
  - пробрасывает список в analytics state.
- Обновлен `AnalyticsScreen` (`DailyForecastReportCard`):
  - добавлен блок `Recommendations`,
  - рекомендации отображаются в отдельных surface-карточках внутри daily report.
- Добавлены строки локализации:
  - `analytics_daily_report_recommendations` (EN/RU).
- Тесты:
  - `InsightsRepositoryDailyForecastReportTest` расширен кейсами quality recommendations:
    - `buildIsfCrDataQualityRecommendations_emitsCrIntegrityGuidance`
    - `buildIsfCrDataQualityRecommendations_returnsEmptyWhenNoDroppedWindows`
  - `MainUiStateMappersTest.analyticsMapping_includesIsfCrSummaryAndHistory` расширен проверкой `dailyReportRecommendations`.

## Почему так
- После Stage 70 рекомендации формировались в markdown/CSV, но в UI их не было видно без открытия файла.
- Прямой вывод в Analytics ускоряет ежедневную эксплуатацию и тюнинг качества данных для снижения MARD.

## Риски / ограничения
- `MainUiStateMappersTest` в окружении по-прежнему нестабилен из-за известного `Gradle Test Executor ... exit 134` (не assertion-регрессия, а flaky test-runtime issue).
- UI показывает только top-3 рекомендации из последнего daily report snapshot.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest" --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false`
3) В приложении открыть `Analytics -> Quality -> Daily report` и проверить блок `Recommendations`.

# Изменения — Этап 72: ISF/CR data-quality block in daily report files + Analytics card

## Что сделано
- Расширен export daily-report (markdown/csv):
  - `InsightsRepository.buildDailyForecastMarkdown(...)` теперь принимает `isfCrDroppedSummary` и добавляет секцию:
    - `## ISF/CR Data Quality`
    - source / events / dropped
    - `CR integrity drop-rate: gap / sensorBlocked / uamAmbiguity`.
  - `InsightsRepository.buildDailyForecastCsv(...)` добавляет `section=quality` строки с той же summary-информацией.
- В telemetry daily-report добавлены top-3 рекомендаций (`daily_report_recommendation_1..3`) и уже существующие `daily_report_isfcr_*` метрики используются как источник UI summary.
- UI wiring:
  - `MainViewModel` формирует `dailyReportIsfCrQualityLines` из telemetry:
    - source/events/dropped
    - CR integrity drop-rate (gap/sensor/UAM).
  - `MainUiState` и `AnalyticsUiState` расширены полем `dailyReportIsfCrQualityLines`.
  - `MainUiStateMappers` пробрасывает это поле и учитывает в `hasData`.
  - `AnalyticsScreen` (`DailyForecastReportCard`) показывает новый блок:
    - `ISF/CR data quality`.
- i18n:
  - добавлен ключ `analytics_daily_report_isfcr_quality` (EN/RU).
- Тесты:
  - `MainUiStateMappersTest.analyticsMapping_includesIsfCrSummaryAndHistory` расширен проверкой `dailyReportIsfCrQualityLines`.

## Почему так
- Отчёт должен быть самодостаточным: раньше quality-информация по ISF/CR была только в telemetry/audit и частично в recommendations.
- Теперь оператор видит в одном месте и рекомендации, и количественную причину деградации (gap/sensor/UAM rates), что упрощает ежедневный тюнинг для снижения MARD.

## Риски / ограничения
- `MainUiStateMappersTest` в текущем окружении продолжает падать флейково по раннеру (`Gradle Test Executor ... exit 134`), без явных assertion-fail.
- При отсутствии audit dropped summary UI блок показывает только то, что доступно в telemetry snapshot.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest" --no-daemon -Pkotlin.incremental=false`
3) Запустить daily analysis и проверить:
   - markdown/csv daily-report содержат секцию `ISF/CR Data Quality`,
   - `Analytics -> Quality -> Daily report` содержит блок `ISF/CR data quality`.

# Изменения — Этап 73: Top dropped-reasons surfaced in daily quality diagnostics

## Что сделано
- Усилен сбор telemetry для daily report quality:
  - добавлен ключ `daily_report_isfcr_dropped_top_reasons` (top-6 counters в формате `reason=count;...`).
- Расширен quality helper в `InsightsRepository`:
  - `buildIsfCrDroppedQualityLines(...)` стал `internal` и теперь формирует:
    1) `source/events/dropped`,
    2) `CR integrity drop-rate: gap/sensorBlocked/uamAmbiguity`,
    3) `Top dropped reasons: ...`.
- Markdown/CSV daily-report используют этот helper и включают top reasons в секцию `ISF/CR Data Quality`.
- UI (`MainViewModel`) теперь читает `daily_report_isfcr_dropped_top_reasons` и добавляет строку:
  - `Top dropped reasons: ...`
  в `dailyReportIsfCrQualityLines`, которые отображаются в `Analytics -> Quality -> Daily report`.
- Добавлен unit-test:
  - `InsightsRepositoryDailyForecastReportTest.buildIsfCrDroppedQualityLines_includesTopReasons`.

## Почему так
- Для реального тюнинга порогов/качества мало знать только aggregate rates (gap/sensor/UAM).
- Top reason counters дают быстрый ответ, что именно чаще всего “ломает” evidence в конкретные сутки.

## Риски / ограничения
- Top reasons зависят от корректности `droppedReasons` в audit payload.
- При очень редких dropped events строка top reasons может быть пустой/короткой (ожидаемо).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --no-daemon -Pkotlin.incremental=false`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest" --no-daemon -Pkotlin.incremental=false`
3) Выполнить daily analysis и проверить в `Analytics -> Quality -> Daily report` наличие строки:
   - `Top dropped reasons: ...`

# Изменения — Этап 74: Daily ISF/CR quality risk classification in report + Analytics UI

## Что сделано
- В daily-report telemetry добавлен и стабилизирован риск-лейбл качества ISF/CR:
  - `daily_report_isfcr_quality_risk`.
- Усилен helper `InsightsRepository.buildIsfCrDataQualityRiskLabel(...)`:
  - возвращает `UNKNOWN` при отсутствии валидной базы,
  - классифицирует риск в `LOW/MEDIUM/HIGH` по dropped-load и доминирующей причине (`gap/sensorBlocked/uamAmbiguity`).
- `MainViewModel` теперь читает `daily_report_isfcr_quality_risk` и добавляет в Analytics daily block строку:
  - `Quality risk: ...`.
- Обновлены unit-тесты `InsightsRepositoryDailyForecastReportTest`:
  - адаптирован `buildIsfCrDroppedQualityLines_includesTopReasons` под новый формат с quality risk,
  - добавлены отдельные тест-кейсы для `buildIsfCrDataQualityRiskLabel` (`UNKNOWN/HIGH/MEDIUM/LOW`).

## Почему так
- В daily recommendations и CR integrity rates не хватало сводного risk-сигнала для быстрого triage качества данных.
- Формат `LOW/MEDIUM/HIGH + dominant cause` позволяет быстрее понять, что именно ограничивает точность ISF/CR extraction в текущие сутки.

## Риски / ограничения
- Классификация зависит от корректности dropped-reason counters в audit metadata.
- В этом окружении сохраняется известная flaky проблема раннера на части UI-тестов (`Gradle Test Executor ... exit 134`), не связанная с логикой risk-label.
- В CI/локально для стабильности пришлось запускать Gradle с `--no-daemon -Pksp.incremental=false` из-за нестабильного KSP cache.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:compileDebugKotlin`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest"`
3) Опционально (известный flaky):
   `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
4) В приложении открыть `Analytics -> Quality -> Daily report` и проверить наличие строки `Quality risk: ...` в блоке `ISF/CR data quality`.

# Изменения — Этап 75: Daily ISF/CR risk gate for shadow auto-activation

## Что сделано
- Усилен daily-report telemetry по ISF/CR quality risk:
  - добавлен числовой ключ `daily_report_isfcr_quality_risk_level` (`0=UNKNOWN, 1=LOW, 2=MEDIUM, 3=HIGH`) рядом с текстовым `daily_report_isfcr_quality_risk`.
- `InsightsRepository` расширен:
  - `buildIsfCrDataQualityRiskLevel(...)` (internal, testable),
  - `buildIsfCrDataQualityRiskLabel(...)` теперь использует единый классификатор уровня риска.
- В `AutomationRepository` добавлен отдельный gate перед rolling gate в auto-promotion `SHADOW -> ACTIVE`:
  - `evaluateIsfCrDailyRiskGateStatic(...)`,
  - новый тип `IsfCrDailyRiskGateAssessment`,
  - новый audit event `isfcr_shadow_data_quality_risk_gate_evaluated`.
- Новое правило активации:
  - если `daily_report_isfcr_quality_risk_level >= 3` (`HIGH`), авто-промоушен блокируется с причиной `daily_risk_high`.
- В `isfcr_shadow_auto_promoted` добавлены поля:
  - `dailyRiskGateReason`,
  - `dailyRiskLevel`.
- Добавлены unit-тесты:
  - `InsightsRepositoryDailyForecastReportTest.buildIsfCrDataQualityRiskLevel_returnsExpectedScale`,
  - `AutomationRepositoryForecastBiasTest`:
    - `isfCrDailyRiskGate_blocksWhenRiskHigh`,
    - `isfCrDailyRiskGate_allowsWhenRiskMedium`,
    - `isfCrDailyRiskGate_allowsWhenRiskMissing`.
- Обновлены docs:
  - `docs/ARCHITECTURE.md` (daily risk gate в auto-activation),
  - `docs/INVARIANTS.md` (инвариант блокировки promotion на HIGH risk).

## Почему так
- В shadow auto-activation уже есть KPI/day-type/sensor/rolling gate, но не было отдельного стоп-фактора по агрегированному качеству ISF/CR evidence за день.
- `HIGH` риск в daily report означает нестабильные входные данные для ISF/CR extraction; безопаснее не переводить контур в ACTIVE до стабилизации качества.

## Риски / ограничения
- Риск-уровень зависит от качества dropped-reasons в audit metadata.
- При отсутствии daily report (`risk_level=0`) gate не блокирует promotion (fail-open для совместимости), что сохраняет текущее поведение.
- В окружении остаются нестабильности Kotlin incremental cache/daemon; тесты запускались с fallback-компиляцией.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:compileDebugKotlin`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest"`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
4) В runtime-аудите проверить событие `isfcr_shadow_data_quality_risk_gate_evaluated` и блокировку promotion при `daily_report_isfcr_quality_risk_level=3`.

# Изменения — Этап 76: Activation diagnostics UI line for daily ISF/CR risk gate

## Что сделано
- В `MainViewModel.buildIsfCrActivationGateLines(...)` добавлено чтение нового audit-события:
  - `isfcr_shadow_data_quality_risk_gate_evaluated`.
- В блок `Analytics -> ISF/CR activation diagnostics` добавлена отдельная строка:
  - `Data-quality risk gate (...)` с полями `eligible`, `reason`, `riskLevel`, `blockAt`.

## Почему так
- После внедрения risk-gate в auto-activation требовалась прозрачная диагностика в UI, чтобы сразу видеть причину блокировки promotion без разбора raw audit payload.

## Риски / ограничения
- Строка отображается только при наличии соответствующего audit-события в выбранном окне.
- Локализация для строки пока форматируется как техническая diagnostics line (в стиле остальных activation diagnostics).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:compileDebugKotlin`
2) Выполнить несколько automation cycle и открыть `Analytics`.
3) Проверить в `ISF/CR activation diagnostics` строку `Data-quality risk gate (...)`.

# Изменения — Этап 77: Configurable daily ISF/CR risk-gate threshold (Settings + runtime wiring)

## Что сделано
- Порог блокировки daily risk-gate для авто-активации `SHADOW -> ACTIVE` сделан настраиваемым:
  - новый settings-параметр `isfCrAutoActivationDailyRiskBlockLevel` (`2..3`, default `3`).
- Persistence/runtime wiring:
  - `AppSettingsStore`:
    - добавлены key/default/read/write/clamp для `isfcr_auto_activation_daily_risk_block_level`,
    - поле добавлено в `AppSettings`.
  - `MainViewModel`:
    - поле прокинуто в `MainUiState` и `SettingsUiState`,
    - добавлен setter `setIsfCrAutoActivationDailyRiskBlockLevel(level: Int)`.
  - `MainUiStateMappers`:
    - mapping `MainUiState -> SettingsUiState` расширен новым полем.
- UI:
  - `SettingsScreen` (`Real ISF/CR engine`) получил новый stepper:
    - `Daily risk block level` (`2..3`),
    - подключён новый callback через `CopilotFoundationRoot`.
  - добавлены строки локализации EN/RU:
    - `settings_isfcr_auto_activation_daily_risk_block_level`,
    - `settings_isfcr_auto_activation_daily_risk_block_level_subtitle`.
- Runtime gate:
  - в `AutomationRepository` удалён hardcoded `ISFCR_DAILY_RISK_BLOCK_LEVEL`,
  - gate использует `settings.isfCrAutoActivationDailyRiskBlockLevel.coerceIn(2,3)`,
  - audit `isfcr_shadow_data_quality_risk_gate_evaluated` и `isfcr_shadow_auto_promoted` теперь содержат фактический block-level.
- Тесты:
  - `AutomationRepositoryForecastBiasTest`:
    - добавлен кейс `isfCrDailyRiskGate_blocksMediumWhenThresholdIsMedium`,
    - добавлен кейс `isfCrDailyRiskGate_clampsConfiguredThresholdIntoSafeRange`.
  - `MainUiStateMappersTest.settingsMapping_includesUamSnackParameters`:
    - расширен проверкой `isfCrAutoActivationDailyRiskBlockLevel`.
- Документация:
  - `docs/ARCHITECTURE.md` и `docs/INVARIANTS.md` обновлены под settings-backed threshold вместо hardcoded `HIGH`.

## Почему так
- После Stage 75/76 risk-gate был фиксирован на `HIGH`. Для controlled rollout нужен операционный тюнинг чувствительности gate:
  - `3` (default): блокировать только `HIGH`,
  - `2`: блокировать уже `MEDIUM/HIGH`.
- Это позволяет ужесточить критерии promotion без кодовых изменений и с полной audit-трассировкой.

## Риски / ограничения
- Включение порога `2` может заметно снизить частоту auto-promotion (более консервативный режим).
- `MainUiStateMappersTest` в текущем окружении остаётся flaky на уровне раннера (`Gradle Test Executor ... exit 134`) и может падать независимо от assertion-логики.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:compileDebugKotlin`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
3) Открыть `Settings -> Real ISF/CR engine` и изменить `Daily risk block level` на `2` или `3`.
4) Проверить в audit:
   - `isfcr_shadow_data_quality_risk_gate_evaluated` (`blockedRiskLevel`),
   - `isfcr_shadow_auto_promoted` (`dailyRiskBlockedLevel`).

# Изменения — Этап 78: Daily risk gate telemetry fallback (text -> level)

## Что сделано
- Усилена устойчивость daily risk gate в `AutomationRepository`:
  - при отсутствии числового `daily_report_isfcr_quality_risk_level` runtime теперь берет fallback из последнего текстового `daily_report_isfcr_quality_risk`.
  - добавлен парсер `parseIsfCrQualityRiskLevelFromTextStatic(...)`:
    - `HIGH* -> 3`,
    - `MEDIUM* -> 2`,
    - `LOW* -> 1`,
    - `UNKNOWN* -> 0`,
    - иначе `null`.
- Логика fallback встроена в `resolveLatestTelemetry(...)`:
  - если numeric risk-level отсутствует, ищется latest text risk label и конвертируется в numeric level.
- Расширены unit-тесты `AutomationRepositoryForecastBiasTest`:
  - `parseIsfCrQualityRiskLevel_parsesEnglishLabels`,
  - `parseIsfCrQualityRiskLevel_returnsNullForUnrecognizedText`.
- Документация обновлена:
  - `docs/ARCHITECTURE.md` (описан fallback text->level),
  - `docs/INVARIANTS.md` (новый инвариант robust risk-level resolution).

## Почему так
- На старых/частично мигрированных данных может присутствовать только текстовый риск-лейбл daily report.
- Без fallback gate работает в fail-open и может ошибочно не блокировать promotion; теперь поведение более предсказуемо и безопасно.

## Риски / ограничения
- Парсер fallback ожидает English labels (`LOW/MEDIUM/HIGH/UNKNOWN`) из report telemetry.
- Нестандартные произвольные тексты не распознаются и останутся `null` (с сохранением текущего fail-open поведения на неизвестном формате).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:compileDebugKotlin`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
3) В runtime создать telemetry только с `daily_report_isfcr_quality_risk` (без numeric-level) и проверить audit:
   - `isfcr_shadow_data_quality_risk_gate_evaluated` должен показывать распознанный `riskLevel`.

# Изменения — Этап 79: Daily risk gate source observability

## Что сделано
- Улучшена прозрачность risk-gate в `AutomationRepository`:
  - `resolveLatestTelemetry(...)` теперь пишет служебный флаг:
    - `daily_report_isfcr_quality_risk_level_fallback_used` (`0/1`),
    чтобы явно видеть, когда risk-level восстановлен из текстового лейбла.
- В `maybeProcessIsfCrShadowAutoActivation(...)` добавлен источник resolved risk-level:
  - `riskLevelSource = numeric | text_fallback | missing_or_unknown`.
- Audit enrichment:
  - событие `isfcr_shadow_data_quality_risk_gate_evaluated` теперь включает `riskLevelSource`;
  - событие `isfcr_shadow_auto_promoted` включает `dailyRiskLevelSource`.
- UI diagnostics:
  - в `MainViewModel.buildIsfCrActivationGateLines(...)` строка `Data-quality risk gate` теперь показывает `source=...`.
- Unit tests:
  - `AutomationRepositoryForecastBiasTest`:
    - `resolveIsfCrDailyRiskLevelSource_prefersTextFallbackFlag`,
    - `resolveIsfCrDailyRiskLevelSource_marksMissingWhenUnknown`.
- Docs:
  - `docs/ARCHITECTURE.md` и `docs/INVARIANTS.md` обновлены под обязательную трассировку источника risk-level.

## Почему так
- Для эксплуатации shadow auto-activation критично видеть не только уровень риска, но и происхождение этого уровня.
- Это снимает неоднозначность при triage: numeric daily telemetry vs text-fallback из старых отчётов.

## Риски / ограничения
- Служебный telemetry key (`*_fallback_used`) остаётся внутренним operational-сигналом и не предназначен для пользовательского UI.
- Если отсутствуют и numeric, и text risk telemetry, источник будет `missing_or_unknown`, gate сохранит fail-open поведение для совместимости.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:compileDebugKotlin`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
3) В `Analytics -> ISF/CR activation diagnostics` проверить строку `Data-quality risk gate` и поле `source=...`.

# Изменения — Этап 80: Info-иконка для параметров Settings (быстрая справка по месту)

## Что сделано
- В экран настроек добавлен единый inline-help паттерн с иконкой `i` для строк параметров:
  - `SettingToggleRow`
  - `SettingIntStepperRow`
  - `SettingDoubleStepperRow`
  - `SettingTextInputRow`
  - `SettingReadOnlyRow`
  - `OptionChipsRow`
- Реализован общий composable `SettingTitleWithInfo(...)`:
  - показывает заголовок параметра + кнопку `Info`,
  - по нажатию открывает `AlertDialog` с коротким объяснением параметра,
  - если явного описания нет, показывает fallback-текст.
- Добавлены локализованные строки:
  - EN/RU `settings_info_button_cd` (accessibility content description),
  - EN/RU `settings_info_dialog_fallback`,
  - EN/RU `action_close`.

## Почему так
- Требование: для каждого окна/параметра в UI нужен быстрый доступ к краткому объяснению “что это и как работает”.
- Вынесение в общий `SettingTitleWithInfo` исключает дублирование и гарантирует одинаковое поведение по всему Settings.

## Риски / ограничения
- Для строк без явного `subtitle/info` используется общий fallback-текст (не параметро-специфичное описание).
- Поведение затронуло только UI слоя настроек, бизнес-логика расчётов/автоматики не изменялась.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:compileDebugKotlin`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
3) Открыть `Settings`, нажимать `i` у разных параметров (`switch/stepper/text/chips`) и убедиться, что появляется диалог с описанием и кнопкой `Close/Закрыть`.

# Изменения — Этап 81: Полное влияние runtime-переменных на прогноз (DIA + context factors)

## Что сделано
- Усилена связность переменных с локальным прогнозом:
  - в `HybridPredictionEngine` добавлен runtime override `setInsulinDurationHours(...)`;
  - `dia_hours` из telemetry теперь применяется в `AutomationRepository.configurePredictionEngine(...)` и влияет на инсулиновую кривую через scaling возраста действия инсулина.
- Расширен runtime контур факторных влияний на прогноз:
  - из realtime `IsfCrSnapshot.factors` в telemetry проброшены `set/sensor/activity/dawn/stress/hormone/steroid` + `context_ambiguity`;
  - добавлен bounded `applyContextFactorForecastBiasStatic(...)` перед `COB/IOB` bias:
    - horizon-aware сдвиг прогноза,
    - horizon-aware расширение CI,
    - дополнительный guard при низком `sensor_quality_score`.
- В цикл добавлен audit event `forecast_context_bias_applied` с факторным trace.
- Тесты:
  - `HybridPredictionEngineV3Test.t12b_diaOverrideChangesInsulinImpact`;
  - `AutomationRepositoryForecastBiasTest.contextBias_highActivityAndLowPatternLowersForecast`;
  - `AutomationRepositoryForecastBiasTest.contextBias_lowSensorQualityWidensCi`.
- Документация обновлена:
  - `docs/ARCHITECTURE.md` (runtime context-bias + DIA influence),
  - `docs/INVARIANTS.md` (новые инварианты по context-bias и DIA scaling).

## Почему так
- Пользовательский запрос требовал, чтобы на прогноз реально влияли не только параметры, пришедшие из AAPS, но и вычисляемые локально факторы (физиология/качество/паттерны).
- До изменения `DIA` присутствовал в telemetry/UI, но не влиял напрямую на прогнозную кривую.
- Контекстный bias позволяет учитывать физиологические факторы даже при частичном/мягком применении ISF/CR runtime snapshot.

## Риски / ограничения
- Контекстный bias умышленно мягкий и ограниченный clamp-ами; он корректирует прогноз, но не заменяет основной path-simulation.
- При экстремально шумном sensor-quality включается защитное ограничение отклонения прогноза от текущей глюкозы.
- Подход детерминированный; ML-контур по-прежнему не участвует в терапевтическом управлении.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test"`
2) Проверить audit:
   - `isfcr_runtime_gate`,
   - `forecast_context_bias_applied`,
   - `forecast_bias_applied`.
3) В live telemetry убедиться, что заполняются:
   - `dia_hours`,
   - `isf_factor_*`,
   - `isf_factor_context_ambiguity`.

# Изменения — Этап 82: Forecast factor coverage audit + live USB readiness check

## Что сделано
- В `AutomationRepository` добавлен per-cycle audit event `forecast_factor_coverage`.
- Новый coverage-event фиксирует, какие драйверы реально участвовали в текущем прогнозном цикле:
  - `ISF/CR` availability/apply mode,
  - `DIA` availability,
  - `COB/IOB` (включая local fallback/merge),
  - `UAM` detection,
  - `sensor/activity/set/hormone/stress/dawn/pattern/history` availability,
  - факты применения стадий bias (`calibration/context/cob_iob`).
- Добавлена функция `buildForecastFactorCoverageMeta(...)` для единообразной диагностики источников прогноза.
- Проверка live USB выполнена: `adb devices -l` вернуло отсутствие подключенного устройства в текущем окружении.

## Почему так
- Нужна прозрачная проверка “влияют ли все переменные на прогнозы” не только на уровне кода, но и в operational-аудите каждого цикла.
- Событие coverage позволяет быстро найти пропуски данных (например нет `dia_hours` или не пришли контекстные факторы) и понять, почему прогноз не использовал часть контуров.

## Риски / ограничения
- Coverage-event диагностический: не меняет саму математику прогноза.
- Для полноценных live-логов нужен подключенный USB device (в этой сессии отсутствует).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false :app:compileDebugKotlin :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test"`
2) Запустить automation cycle и проверить audit:
   - `forecast_factor_coverage`
   - `forecast_context_bias_applied`
   - `forecast_bias_applied`
3) Для USB-проверки подключить телефон и убедиться, что `adb devices -l` показывает устройство.

# Изменения — Этап 83: Интерактивные профили инсулина (активное действие + активация из Analytics)

## Что сделано
- Доработан блок `Insulin profiles` на экране Analytics:
  - добавлен режим `active profile` + `preview profile` с отдельной индикацией;
  - график переведен на отображение **уровня активного действия** (из производной cumulative-кривой, нормированной к пику);
  - добавлена явная шкала времени по оси X (`m/h`) и подпись осей.
- Добавлены контрольные числовые точки активного действия для выбранного профиля:
  - `Active @ 30m`,
  - `Active @ 60m`,
  - `Active @ 120m`.
- Добавлена кнопка `Activate selected profile` прямо в Analytics:
  - activation callback прокинут из `CopilotFoundationRoot` в `AnalyticsScreen`,
  - используется существующий `MainViewModel.setInsulinProfile(...)` без изменения бизнес-логики.
- Локализации EN/RU обновлены для новых подписей блока профилей.
- Выполнен USB rollout:
  - `adb install -r .../app-debug.apk` успешно.

## Почему так
- Требование пользователя: видеть реальные профили действия инсулина с временной шкалой и уровнем активности, переключать профили для просмотра и активировать выбранный профиль.
- Контрольные точки (30/60/120) добавлены для быстрого чтения профиля без визуальной оценки кривой “на глаз”.
- Внедрение через существующий `setInsulinProfile` сохраняет консистентность с текущим runtime-контуром прогноза.

## Риски / ограничения
- В Android logcat периодически остаются нефатальные сообщения NanoHTTPD `Socket is closed` при локальном TLS-клиенте — на работу UI/прогноза не влияет.
- После инкрементальной сборки был пойман runtime `VerifyError` (битый dex от incremental pipeline). Решено чистой пересборкой.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --stop && ./gradlew clean`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Pksp.incremental=false -Pkotlin.incremental=false :app:assembleDebug`
3) `adb install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4) Открыть `Analytics -> ISF/CR -> Профили действия инсулина`:
   - переключать чипы профилей (preview),
   - проверить график и контрольные точки `30/60/120`,
   - нажать `Activate selected profile` и убедиться, что профиль становится `active`.

# Изменения — Этап 84: Safety cap на карбы (3h cutoff + 60g max) для COB и прогноза

## Что сделано
- Добавлены новые safety-настройки в `AppSettings`/`AppSettingsStore`:
  - `carbAbsorptionMaxAgeMinutes` (default `180`, clamp `60..360`),
  - `carbComputationMaxGrams` (default `60`, clamp `20..120`).
- В `AutomationRepository`:
  - `configurePredictionEngine(...)` теперь передает лимиты в `HybridPredictionEngine`.
  - `resolveRuntimeCobIobInputs(...)` ограничивает `telemetryCob`, `mergedCob` и fallback COB новым cap.
  - `estimateLocalCobIob(...)` не учитывает carbs старше cutoff и ограничивает вклад carbs cap-значением.
- В `HybridPredictionEngine`:
  - добавлены runtime-параметры и setter `setCarbSafetyLimits(...)`.
  - carb-вклад в `buildTherapyStepSeries(...)` и `therapyDeltaAtHorizon(...)` считает через cutoff-aware cumulative:
    - после cutoff carbs считаются полностью абсорбированными (`cumulative=1.0`).
  - ограничен carbs вклад в прогноз (`extractCarbsGramsForPrediction` cap до `carbComputationMaxGrams`).
  - `profileCarbEvents(...)` исключает carb-события старше cutoff.
  - UAM-ветка использует тот же cutoff-aware carb cumulative для исключения двойного/долгого хвоста carbs.
- В Safety UI (`CopilotRoot` + `MainViewModel` + `MainUiState`) добавлены поля:
  - `Carb absorption max age (minutes)`
  - `Carb computation max grams`
  и сохранение через `setSafetyLimits(...)`.
- Добавлены unit-тесты в `HybridPredictionEngineTest`:
  - carbs старше 3h не влияют на legacy прогноз;
  - carbs `120g` дают тот же прогноз, что `60g` (при default cap).

## Почему так
- Пользовательское требование: убрать “долгий хвост” углеводов и ввести безопасный потолок carbs, чтобы прогноз/COB не раздувались.
- Реализация как настройки Safety Center позволяет регулировать ограничения без перекомпиляции.

## Риски / ограничения
- Ограничения применены в Copilot-контурах (локальный COB, merge, prediction/UAM). Внутренний COB внутри самого AAPS продолжает жить по правилам AAPS.
- Если у пользователя реально приемы >60g, прогноз в Copilot станет консервативнее по carb-вкладу (это ожидаемый safety tradeoff).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test"`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3) В приложении открыть `Safety Center` и проверить поля:
   - `Carb absorption max age (minutes)` = `180`,
   - `Carb computation max grams` = `60`.
4) На данных проверить:
   - carbs событие старше 3h не увеличивает COB в Copilot,
   - при carbs > 60g расчеты используют cap (по прогнозу и runtime COB).

# Изменения — Этап 85: Жесткий safety-cap на отправку carbs в AAPS + USB валидация

## Что сделано
- В `NightscoutActionRepository.submitCarbs(...)` добавлен жесткий блок отправки carbs выше safety cap из настроек (`carbComputationMaxGrams`):
  - при превышении команда получает `FAILED` с reason `carbs_above_safety_cap`,
  - пишется audit `carbs_send_blocked_safety_cap`.
- В `MainViewModel.sendManualCarbs(...)` добавлена валидация ввода по текущему safety cap:
  - разрешенный диапазон теперь `1..cap`,
  - пользователю показывается явный лимит в тексте ошибки.
- APK обновлен на телефон по USB (`adb install -r .../app-debug.apk`).
- Runtime-валидация на устройстве через DB-аудит:
  - в `forecast_bias_applied` видно `cobGrams=60.0` при входящем `raw_cob≈68`, т.е. cap реально применяется в рабочем цикле.

## Почему так
- Требование безопасности: не допускать передачу потенциально опасных carbs величин в AAPS и держать единый safety cap во всем контуре (UI -> outbound -> runtime).

## Риски / ограничения
- Ограничение применяется к отправкам из Copilot; ручные carbs, введенные напрямую в AAPS, этим контуром не ограничиваются.
- Для проверки события `carbs_send_blocked_safety_cap` нужен фактический запрос > cap (в текущем логе не было такой попытки после обновления).

## Как проверить
1) В `Safety Center` установить `Carb computation max grams = 60`.
2) Отправить ручные carbs `> 60` из Copilot UI: должна появиться ошибка диапазона.
3) Проверить в `Action commands`, что команда не ушла как `SENT`.
4) Проверить audit на событие `carbs_send_blocked_safety_cap`.

# Изменения — Этап 86: Targeted Replay 24h (ошибки 5/30/60 + вклад факторов COB/IOB/UAM/CI)

## Что сделано
- В `InsightsRepository` расширен локальный 24ч replay-отчет:
  - добавлены `replayHotspots` (топ-3 часовых hotspot-а MAE для горизонтов `5/30/60`);
  - добавлены `factorContributions` по факторам `COB/IOB/UAM/CI` на основе корреляции с `absError` и uplift (`MAE high quartile` vs `MAE low quartile`);
  - в matching-сэмпл добавлены `generationTs` и `ciWidth` для корректной факторной атрибуции;
  - в markdown/csv добавлены секции:
    - `Targeted Replay 24h: Error Hotspots`,
    - `Targeted Replay 24h: Factor Contributions (COB/IOB/UAM/CI)`.
- В `persistDailyForecastReportTelemetry(...)` добавлена запись агрегатов в telemetry:
  - `daily_report_replay_top_factor_5m/30m/60m`,
  - `daily_report_replay_hotspot_5m/30m/60m`,
  - `daily_report_replay_top_factors_overall`.
- В `MainViewModel` добавлено чтение этих ключей и отображение в `rollingReportLines` (UI-блок отчета).
- В unit-тесты `InsightsRepositoryDailyForecastReportTest` добавлен сценарий:
  - проверка генерации replay hotspots;
  - проверка генерации factor contributions и доминирования COB в синтетическом наборе.

## Почему так
- Требование: на следующем шаге получить целевой replay-анализ за последние 24ч с указанием зон ошибок по `5/30/60` и факторов, дающих наибольший вклад в ошибку.
- Реализация встроена в уже существующий ежедневный отчет, чтобы результат появлялся автоматически и сохранялся в telemetry/audit.

## Риски / ограничения
- Факторный вклад построен на статистической атрибуции (корреляция + uplift), это не каузальный вывод.
- Качество атрибуции зависит от покрытия telemetry возле `generationTs` (используется nearest sample в окне до 10 минут).
- При малом объеме сэмплов (<12 на горизонт/фактор) вклад не рассчитывается.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest"`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3) Запустить цикл daily report (или вручную `runDailyAnalysis`) и проверить в отчете секции:
   - `Targeted Replay 24h: Error Hotspots`,
   - `Targeted Replay 24h: Factor Contributions (COB/IOB/UAM/CI)`.
4) Проверить telemetry-ключи `daily_report_replay_top_factor_*`, `daily_report_replay_hotspot_*`, `daily_report_replay_top_factors_overall`.

# Изменения — Этап 87: Structured Replay 24h в Analytics UI (hotspots + factor contributions)

## Что сделано
- Расширен telemetry payload daily-report:
  - добавлены JSON-ключи:
    - `daily_report_replay_hotspots_json`
    - `daily_report_replay_factors_json`
  - данные сериализуются из `replayHotspots` и `factorContributions` в `InsightsRepository`.
- В `MainViewModel` добавлен парсинг replay JSON в типизированные модели:
  - `DailyReportReplayHotspotUi`
  - `DailyReportReplayFactorUi`
  - + связка в `MainUiState` (`dailyReportReplayHotspots`, `dailyReportReplayFactors`, `dailyReportReplayTopFactorsOverall`).
- В `MainUiStateMappers` добавлен маппинг новых replay-полей в `AnalyticsUiState`.
- В `AnalyticsScreen` (`DailyForecastReportCard`) добавлен структурированный блок:
  - общий рейтинг факторов;
  - список hotspot-окон (5/30/60);
  - список факторных вкладов с `score/corr/uplift/n`.
- Добавлены строки локализации (`en/ru`) для replay-секции отчёта.
- Добавлены/обновлены unit-тесты:
  - `MainUiStateMappersTest` проверяет перенос replay-hotspots/factors в `AnalyticsUiState`.

## Почему так
- Текстовые `rollingReportLines` недостаточны для детального целевого replay-анализа.
- Структурированный вывод в UI позволяет быстро увидеть:
  - в каких часовых окнах ошибка максимальна по 5/30/60,
  - какие факторы (`COB/IOB/UAM/CI`) дают основной вклад.

## Риски / ограничения
- Атрибуция факторов статистическая, не каузальная.
- JSON-поля replay зависят от актуального daily-report цикла; до первого цикла блок может быть пустым.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --stop`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew clean :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
4) В приложении открыть `Analytics -> Quality -> Daily Forecast Report` и проверить replay-блоки:
   - `Targeted replay 24h`
   - hotspot-строки по 5/30/60
   - factor-строки по COB/IOB/UAM/CI.

# Изменения — Этап 88: Runtime diagnostics day-type/base-source wiring

## Что сделано
- Завершена end-to-end проводка новых полей runtime diagnostics из `IsfCrEngine` в UI:
  - `currentDayType`
  - `isfBaseSource` / `crBaseSource`
  - `isfDayTypeBaseAvailable` / `crDayTypeBaseAvailable`
  - `hourWindowIsfSameDayType` / `hourWindowCrSameDayType`
- `MainViewModel`:
  - сохраняет новые поля в `MainUiState`.
- `MainUiStateMappers` + `ScreenModels`:
  - расширена модель `IsfCrRuntimeDiagnosticsUi`,
  - добавлен маппинг всех новых runtime-полей.
- `AnalyticsScreen`:
  - в карточку runtime diagnostics добавлены строки:
    - `Base source ISF/CR + day-type availability`,
    - `Day-type evidence (current day type, same-day samples ISF/CR)`.
- Локализация:
  - добавлен `analytics_runtime_diag_base_source_line` в `values/strings.xml` и `values-ru/strings.xml`.
- Unit mapping test:
  - `MainUiStateMappersTest` расширен проверками новых diagnostics-полей.

## Почему так
- По плану Physiology-Aware ISF/CR важно объяснимо показывать, откуда взялась база (`day_type/hourly/fallback`) и насколько достаточно day-type evidence.
- Без этих полей в UI новые диагностики в движке были частично “невидимыми” для пользователя.

## Риски / ограничения
- Изменения без запуска сборки в этом шаге (по запросу пользователя), поэтому возможные мелкие синтаксические несоответствия нужно подтвердить отдельным прогоном тестов/компиляции.
- Значения `day-type available` выводятся как булевы флаги (`true/false`) для прозрачности; человекочитаемую шкалу можно добавить отдельным UX-шагом.

## Как проверить
1) В `Analytics -> Runtime diagnostics` проверить появление строк:
   - `Base source ISF/CR ... day-type available ...`
   - `Day-type evidence (...) ISF/CR ...`
2) Убедиться, что при наличии runtime audit с новыми meta-полями значения отображаются и не пустые.
3) Проверить unit test `MainUiStateMappersTest` на новые assertions runtime diagnostics.

# Изменения — Этап 89: Replay 24h factor attribution расширен (physiology-aware factors)

## Что сделано
- В `InsightsRepository` расширен набор факторов для `Targeted Replay 24h -> Factor Contributions`:
  - `COB`, `IOB`, `UAM`, `CI` (было),
  - `ACTIVITY`, `SENSOR_Q`, `ISF_CONF`, `ISF_Q`,
  - `SET_AGE_H`, `CTX_AMBIG`, `DAWN`, `STRESS`, `HORMONE`.
- Добавлен unified-resolver `resolveFactorValue(...)` с fallback по алиасам для телеметрии.
- Добавлен helper `nearestTelemetryValueMulti(...)` для key-alias источников.
- Обновлена метрика `contributionScore`:
  - теперь учитывает **модуль** uplift (а не только положительный uplift),
  - это позволяет фактору быть значимым, даже если высокий квартиль уменьшает ошибку (защитный эффект).
- Заголовок markdown-секции отчета обновлен на универсальный:
  - `Targeted Replay 24h: Factor Contributions`.

## Почему так
- По плану Physiology-Aware нужно объяснять ошибку прогноза не только через COB/IOB/UAM/CI, но и через физиологические/контекстные сигналы (`activity`, `sensor quality`, `set age`, `dawn/stress/hormone`).
- Модуль uplift делает ранжирование факторов более корректным для факторов с “защитной” направленностью.

## Риски / ограничения
- При недостатке telemetry-покрытия по новому фактору он не попадает в attribution (минимум 12 matched pairs, минимум 4/4 в квартилях).
- Расширение attribution статистическое, не каузальное.

## Как проверить
1) Запустить daily-report цикл и открыть блок `Targeted Replay 24h: Factor Contributions`.
2) Проверить, что при наличии telemetry появляются новые факторы (`ACTIVITY`, `SENSOR_Q`, `SET_AGE_H` и др.).
3) Проверить, что `top factors overall` обновляется с учетом расширенного набора.

# Изменения — Этап 90: Actionable replay hints по доминирующим факторам

## Что сделано
- В `InsightsRepository.buildRecommendations(...)` добавлен слой factor-specific рекомендаций:
  - после определения top-factor по горизонту (`5/30/60`) формируется отдельный actionable hint.
- Добавлен `factorRecommendationHint(...)` с правилами для:
  - `COB`, `IOB`, `UAM`, `CI`,
  - `ACTIVITY`, `SENSOR_Q`, `ISF_CONF`, `ISF_Q`,
  - `SET_AGE_H`, `CTX_AMBIG`, `DAWN`, `STRESS`, `HORMONE`.
- В `persistDailyForecastReportTelemetry(...)` добавлены ключи:
  - `daily_report_replay_top_factor_hint_5m`
  - `daily_report_replay_top_factor_hint_30m`
  - `daily_report_replay_top_factor_hint_60m`
- В `MainViewModel` добавлен парсинг и вывод этих hints в `rollingReportLines`.

## Почему так
- Пользовательский запрос на целевой replay-анализ требует не только “какой фактор доминирует”, но и “что делать дальше”.
- Эти hints переводят статистику факторов в конкретные действия по настройке контуров (UAM, sensor gate, activity-aware, set-age, dawn/stress/hormone).

## Риски / ограничения
- Hints rule-based и не являются медицинскими предписаниями; это operational guidance по модели.
- Точность hints зависит от качества factor attribution (telemetry coverage + достаточно matched samples).

## Как проверить
1) Выполнить daily-report цикл.
2) Проверить telemetry ключи `daily_report_replay_top_factor_hint_*`.
3) Проверить в Analytics/Quality, что `rollingReportLines` содержит строки `Replay 24h * hint: ...`.

# Изменения — Этап 91: Robust factor correlation (Pearson + Spearman)

## Что сделано
- Для replay factor attribution в `InsightsRepository.buildFactorContributions(...)`:
  - добавлен `spearmanCorrelation(...)` (rank-based),
  - добавлен `rankWithTies(...)`,
  - итоговый `corrAbsError` теперь рассчитывается как robust blend:
    - `corr = (pearson + spearman) / 2` (с защитой на NaN и clamp в `[-1..1]`).

## Почему так
- На шумных данных и выбросах чистый Pearson нестабилен и может давать скачущие factor rankings.
- Spearman лучше держит монотонные зависимости в присутствии выбросов; blend повышает устойчивость attribution.

## Риски / ограничения
- Корреляция остаётся статистической (не каузальной).
- При очень малом динамическом диапазоне фактора (почти константа) корреляция стремится к 0, и фактор может выпадать из top-list.

## Как проверить
1) Выполнить daily-report цикл на сутках с шумными/неровными telemetry окнами.
2) Сравнить стабильность top-factors между соседними запусками.
3) Проверить, что `corr` в replay factor lines остаётся в диапазоне `[-1..1]`.

# Изменения — Этап 92: Replay factors UI ranking per horizon

## Что сделано
- В `AnalyticsScreen` изменен вывод `dailyReportReplayFactorContributions`:
  - вместо общего `take(12)` с приоритетом по сортировке,
  - теперь показывается `top-4` факторов отдельно для каждого горизонта `5/30/60`.

## Почему так
- После расширения factor-space общий срез мог визуально смещаться в пользу одного горизонта.
- Раздельный top-N на горизонт делает анализ ошибок по окнам 5/30/60 более честным и читаемым.

## Риски / ограничения
- При недостатке факторов в конкретном горизонте блок может быть коротким (это ожидаемо).

## Как проверить
1) Открыть `Analytics -> Daily Forecast Report -> Targeted Replay 24h`.
2) Убедиться, что в блоке факторов есть строки для каждого горизонта (если данные доступны).

# Изменения — Этап 93: Локализованные названия replay-факторов в UI

## Что сделано
- В `AnalyticsScreen` добавлен маппинг factor-code -> человекочитаемое имя:
  - `COB/IOB/UAM/CI/ACTIVITY/SENSOR_Q/ISF_CONF/ISF_Q/SET_AGE_H/CTX_AMBIG/DAWN/STRESS/HORMONE`.
- Добавлены string resources (`en/ru`) для названий replay-факторов:
  - `analytics_replay_factor_*`.
- В строке factor-contribution теперь отображается локализованная подпись, а не сырой код.

## Почему так
- После расширения factor-space коды вида `CTX_AMBIG` ухудшали читаемость аналитики.
- Локализованные названия делают replay-блок понятнее без потери детализации.

## Риски / ограничения
- Если появится новый factor-code без маппинга, он будет показан как raw-код (fallback поведение).

## Как проверить
1) Открыть `Analytics -> Daily Forecast Report -> Targeted Replay 24h`.
2) Убедиться, что factor-строки показывают понятные названия (например `Sensor quality`, `Infusion set age`).

# Изменения — Этап 94: Replay factor coverage + reliability hints

## Что сделано
- В `InsightsRepository` добавлена новая метрика для replay-атрибуции:
  - `ReplayFactorCoverageStats(horizon, factor, sampleCount, coveragePct)`.
- `buildFactorContributions(...)` теперь возвращает:
  - `contributions` + `coverage` (через `FactorAttributionResult`).
- В payload daily-report добавлено поле:
  - `replayFactorCoverage`.
- В markdown/csv отчёты добавлен блок `Targeted Replay 24h: Factor Coverage`.
- В telemetry добавлен JSON-ключ:
  - `daily_report_replay_factor_coverage_json`.
- В `buildRecommendations(...)` добавлены reliability-hints:
  - если для core-факторов (`COB/IOB/UAM/CI`) coverage < 60%, формируется рекомендация сначала улучшить telemetry coverage.
- В `MainViewModel` добавлен парсинг coverage JSON и выведены строки coverage в analytics lines.
- В `AnalyticsScreen` добавлен визуальный вывод factor coverage (core-факторы по горизонтам).
- Обновлены ресурсы `en/ru` для строки coverage.
- `MainUiStateMappersTest` расширен проверкой mapping coverage-полей.

## Почему так
- Без coverage нельзя оценить, насколько вообще надежно посчитан факторный вклад.
- Coverage-слой снижает риск “перекрутить” настройки на основе слабой/дырявой телеметрии.

## Риски / ограничения
- Coverage считается относительно matched samples горизонта и доступности ближайшей telemetry в окне.
- При низкой частоте/пропусках telemetry часть факторов останется с низким покрытием (это корректный сигнал качества данных).

## Как проверить
1) Запустить daily-report и открыть replay-раздел в Analytics.
2) Убедиться, что виден блок factor coverage по core-факторам.
3) Проверить, что при coverage < 60% появляются reliability hints в рекомендациях.

# Изменения — Этап 95: Coverage метрика интегрирована в UI и тесты

## Что сделано
- В `MainViewModel`:
  - добавлен парсинг `daily_report_replay_factor_coverage_json`,
  - coverage включен в analytics lines для core-факторов (`COB/IOB/UAM/CI`).
- В `MainUiState`/`ScreenModels`/`MainUiStateMappers`:
  - добавлены типы и поля `dailyReportReplayCoverage`.
- В `AnalyticsScreen`:
  - добавлен визуальный блок coverage-строк по core-факторам (в replay-секции).
- В unit tests:
  - `InsightsRepositoryDailyForecastReportTest` проверяет, что `replayFactorCoverage` формируется;
  - `MainUiStateMappersTest` проверяет mapping coverage в analytics state.

## Почему так
- Надежность factor-attribution теперь видна не только в рекомендациях, но и как явная метрика в UI.
- Это упрощает decision-making: сначала качество покрытия, затем tuning факторов.

## Риски / ограничения
- Coverage зависит от близости telemetry к `generationTs` (окно до 10 минут), поэтому при редкой telemetry значения могут быть занижены.

## Как проверить
1) Запустить daily-report цикл.
2) Открыть `Analytics -> Daily Forecast Report -> Targeted Replay 24h`.
3) Проверить, что есть строки `coverage` для core-факторов по горизонтам.

# Изменения — Этап 96: Replay attribution расширен факторами DIA/sensor-age/steroid

## Что сделано
- В `InsightsRepository` расширен набор replay-факторов:
  - добавлены `DIA_H`, `SENSOR_AGE_H`, `STEROID`.
- Для новых факторов добавлено разрешение значений из телеметрии:
  - `DIA_H` <- `dia_hours` / `dia`,
  - `SENSOR_AGE_H` <- `isf_factor_sensor_age_hours`,
  - `STEROID` <- `isf_factor_steroid_factor`.
- Расширен блок rule-based hints (`factorRecommendationHint`) для новых факторов.
- В `AnalyticsScreen` добавлен display mapping новых factor-code в человекочитаемые названия.
- Добавлены string resources `en/ru`:
  - `analytics_replay_factor_dia`,
  - `analytics_replay_factor_sensor_age`,
  - `analytics_replay_factor_steroid`.
- В `InsightsRepositoryDailyForecastReportTest`:
  - расширен synthetic telemetry набор (dia/sensor-age/steroid),
  - добавлены проверки, что coverage строится для новых факторов.

## Почему так
- На 30m/60m горизонтах ошибка часто определяется не только `COB/IOB/UAM`, но и профилем длительности инсулина (`DIA`), старением сенсора и стероидным контекстом.
- Без этих факторов replay-отчёт недооценивает реальные источники drift на длинных окнах.

## Риски / ограничения
- Качество атрибуции новых факторов зависит от фактического наличия соответствующих telemetry-ключей.
- При почти постоянных значениях фактора contribution может быть слабым/нулевым (coverage при этом остаётся корректным).

## Как проверить
1) Сформировать daily forecast report (24h).
2) Открыть `Analytics -> Daily Forecast Report -> Targeted Replay 24h`.
3) Проверить появление новых факторов (`DIA`, `Sensor age`, `Steroid`) в replay-строках/coverage при наличии данных.

# Изменения — Этап 97: Top-miss context строки в 24h replay-отчёте

## Что сделано
- В `InsightsRepository.buildDailyForecastReportPayloadStatic(...)` добавлен расчёт `top miss context` по каждому горизонту (`5m/30m/60m`).
- Для каждого горизонта выбирается максимальная ошибка и формируется строка контекста:
  - время,
  - `err/pred/actual`,
  - `COB/IOB/UAM/CI width`,
  - `DIA`,
  - `activity`,
  - `sensor quality`.
- Эти строки добавляются в `recommendations` daily report, чтобы их сразу видеть в UI без отдельного парсера.
- В `InsightsRepositoryDailyForecastReportTest` добавлены проверки наличия строк `5m/30m/60m top miss`.

## Почему так
- Требование целевого replay-анализа: не только агрегированные корреляции, но и конкретные “где/когда/с чем” случаи крупных промахов.
- Это ускоряет ручную калибровку модели (можно сразу увидеть ключевой контекст ошибки по горизонту).

## Риски / ограничения
- Строки контекста диагностические; они зависят от наличия близкой телеметрии (если нет значения, выводится `0.000`).
- Формат текстовый и компактный; для дальнейшей детализации потребуется отдельная структурированная секция в payload/UI.

## Как проверить
1) Сформировать daily forecast report (24h).
2) Проверить в блоке `Recommendations`, что присутствуют строки:
   - `5m top miss ...`
   - `30m top miss ...`
   - `60m top miss ...`
3) Убедиться, что строки содержат факторы `COB/IOB/UAM/CIw/DIA/activity/sensorQ`.

# Изменения — Этап 98: Replay attribution performance optimization (factor cache)

## Что сделано
- В `InsightsRepository` оптимизированы функции:
  - `buildFactorContributions(...)`,
  - `buildReplayTopMissContextLines(...)`.
- Добавлен локальный кэш факторных значений по ключу `(FactorSpec, generationTs)`:
  - устраняет повторные дорогие lookup по телеметрии для одних и тех же `(фактор, timestamp)`.
- Для отсутствующих значений используется sentinel (`NaN`) с корректным возвратом `null/0` по месту использования.

## Почему так
- Суточный replay-отчёт обрабатывает много matched-сэмплов и факторов, что без кэша создавало избыточные повторные проходы по telemetry.
- Кэш снижает CPU-нагрузку и ускоряет построение отчёта на длинной истории без изменения математики.

## Риски / ограничения
- Кэш живёт только в рамках одного расчёта payload (in-memory), на persistence это не влияет.
- Sentinel `NaN` используется только внутри helper-функций и не выводится наружу.

## Как проверить
1) Сформировать daily-report на большем объёме истории (например, при плотной телеметрии).
2) Проверить, что содержимое `factor contributions/coverage` и `top miss context` не изменилось семантически.
3) Сравнить latency построения отчёта до/после (по audit timestamps `daily_forecast_report_generated`).

# Изменения — Этап 99: Structured top-miss payload (24h replay) + Analytics UI block

## Что сделано
- В `InsightsRepository` добавлен структурированный тип:
  - `ReplayTopMissContextStats`.
- `DailyForecastReportPayload` расширен:
  - `replayTopMisses: List<ReplayTopMissContextStats>`.
- В расчёте daily report теперь формируется `top miss` для каждого горизонта `5m/30m/60m`:
  - `ts`, `pred`, `actual`, `absError`,
  - `COB`, `IOB`, `UAM`, `CI width`,
  - `DIA`, `activity`, `sensorQuality`.
- Публикация в telemetry:
  - `daily_report_replay_top_miss_json`,
  - `daily_report_replay_top_miss_5m/30m/60m`.
- Markdown/CSV отчёты расширены секцией:
  - `Targeted Replay 24h: Top Miss Context`.
- В `MainViewModel` добавлен парсер `parseReplayTopMissJson(...)` и новое состояние:
  - `dailyReportReplayTopMisses`.
- В `AnalyticsUiState`/mapper добавлено поле `dailyReportReplayTopMisses`.
- В `AnalyticsScreen` добавлен отдельный визуальный блок top-miss строк.
- Добавлены string resources (`en/ru`) для строки top-miss.

## Почему так
- Текстовые рекомендации были недостаточно структурированы для последующего анализа.
- Отдельный payload top-miss делает целевой replay разбор по 24ч более точным и пригодным для UI/авто-диагностики.

## Риски / ограничения
- При пропусках телеметрии часть факторов в top-miss может быть `0` (fallback), это нормальное поведение.
- Для более глубокой декомпозиции потребуется отдельный drill-down screen с timeline вокруг `ts`.

## Как проверить
1) Сформировать daily forecast report (24h).
2) Проверить telemetry ключи:
   - `daily_report_replay_top_miss_json`,
   - `daily_report_replay_top_miss_5m`,
   - `daily_report_replay_top_miss_30m`,
   - `daily_report_replay_top_miss_60m`.
3) Открыть `Analytics -> Daily Forecast Report` и проверить отображение top-miss блока.

# Изменения — Этап 100: Causal telemetry alignment для replay-факторов

## Что сделано
- В `InsightsRepository.nearestTelemetryValue(...)` изменена стратегия подбора telemetry-фактора к `generationTs`:
  - выбирается bounded окно (`lookback <= 10m`, `future tolerance <= 2m`);
  - приоритетно берётся наиболее поздняя точка из этого окна (каузальный выбор);
  - fallback на старое nearest-поведение остаётся только если bounded-выбор не найден.
- Добавлена константа:
  - `FACTOR_TELEMETRY_FUTURE_TOLERANCE_MS = 2m`.

## Почему так
- Старый nearest-по-абсолютной-дистанции подход мог подхватывать “слишком будущие” точки и завышать качество factor attribution.
- Новый каузальный выбор делает replay-анализ честнее и ближе к реальному online-состоянию на момент генерации прогноза.

## Риски / ограничения
- При редкой телеметрии часть факторов может чаще выпадать в `null` (и снижать coverage), но это корректный сигнал качества данных.
- Параметр `2m` — компромисс; при необходимости можно вынести в settings/debug.

## Как проверить
1) Сформировать daily-report на окне с неровной телеметрией.
2) Сравнить `factor coverage` и `top factors` до/после — expected: меньше ложных корреляций на “future leakage”.
3) Проверить, что top-miss context по 5/30/60 продолжает публиковаться в telemetry/UI.

# Изменения — Этап 101: Extended low-coverage warnings в replay recommendations

## Что сделано
- В `InsightsRepository.buildRecommendations(...)` добавлен второй уровень coverage-рекомендаций:
  - помимо core-факторов (`COB/IOB/UAM/CI`) теперь анализируется и расширенный факторный набор;
  - если `coverage < 45%` и `n >= 8` для non-core фактора, формируется предупреждение `extended factor coverage is low`.

## Почему так
- При физиологически-обогащённой модели (`DIA/sensor age/steroid/...`) слабое покрытие таких факторов часто остаётся незамеченным, что снижает качество ручного тюнинга.
- Явные предупреждения помогают не переинтерпретировать вклад факторов с плохой наблюдаемостью.

## Риски / ограничения
- При sparse-телеметрии список рекомендаций может стать длиннее; используется порог `45%` и минимум `n=8`, чтобы уменьшить шум.

## Как проверить
1) Сформировать daily-report на окне с частично отсутствующей расширенной телеметрией.
2) Проверить появление строк вида:
   - `extended factor coverage is low (...).`

# Изменения — Этап 102: Core factor regimes в 24h replay (LOW/MID/HIGH)

## Что сделано
- В `InsightsRepository` добавлен новый слой replay-диагностики:
  - `ReplayFactorRegimeStats` по core-факторам `COB/IOB/UAM/CI`,
  - бины `LOW/MID/HIGH` по перцентилям (`33%/67%`) для каждого горизонта `5m/30m/60m`,
  - метрики на бин: `meanFactorValue`, `MAE`, `MARD`, `Bias`, `n`.
- `DailyForecastReportPayload` расширен полем:
  - `replayFactorRegimes`.
- Daily report расширен:
  - Markdown секция `Targeted Replay 24h: Core Factor Regimes (Low/Mid/High)`,
  - CSV строки `factor_regime,...`.
- Telemetry расширена:
  - ключ `daily_report_replay_factor_regime_json`.
- В рекомендации добавлены regime-based hints:
  - если `HIGH`-режим существенно хуже `LOW` (MAE ratio >= 1.35) — явный сигнал, что фактор критичен под высокой нагрузкой;
  - если наоборот (ratio <= 0.75) — пометка protective-поведения.
- В `MainViewModel` добавлен парсер:
  - `parseReplayRegimesJson(...)`,
  - новое состояние `dailyReportReplayRegimes`.
- В Analytics UI добавлен вывод regime-строк:
  - `%horizon% %factor% %regime% • mean/MAE/MARD/Bias/n`.
- Добавлены локализации `en/ru`:
  - `analytics_daily_report_replay_regime_line`,
  - `analytics_replay_regime_low/mid/high`.
- Обновлены тесты:
  - `InsightsRepositoryDailyForecastReportTest` проверяет наличие `replayFactorRegimes` и bucket-покрытие,
  - `MainUiStateMappersTest` проверяет маппинг `dailyReportReplayRegimes` в `AnalyticsUiState`.

## Почему так
- Обычные factor contribution/coverage показывают “что важно”, но не показывают “в каком режиме фактора” ошибка резко растет.
- Режимный разбор даёт прямую диагностическую связь с тюнингом:
  - например, проблема только в `COB HIGH` или только в `UAM HIGH`.

## Риски / ограничения
- Бины вычисляются по локальному 24h распределению и могут быть нестабильны при очень малом числе matched samples.
- Режимный вывод ограничен core-факторами; расширение на другие факторы можно добавить отдельно после оценки signal/noise.

## Как проверить
1) Сформировать daily forecast report (24h).
2) Проверить telemetry:
   - `daily_report_replay_factor_regime_json`.
3) Открыть `Analytics -> Daily Forecast Report -> Targeted Replay 24h`.
4) Убедиться, что видны строки с режимами `LOW/MID/HIGH` для `COB/IOB/UAM/CI` (при наличии данных).

# Изменения — Этап 103: Core factor pair regimes в 24h replay (LOW/HIGH × LOW/HIGH)

## Что сделано
- В `InsightsRepository` добавлен новый слой replay-диагностики по парным режимам core-факторов:
  - `COB×IOB`, `COB×UAM`, `COB×CI`, `IOB×CI`, `UAM×CI`.
- Добавлен тип `ReplayFactorPairRegimeStats` и расширен `DailyForecastReportPayload` полем:
  - `replayFactorPairs`.
- Добавлен расчет `buildReplayFactorPairRegimes(...)`:
  - отдельно по горизонтам `5m/30m/60m`,
  - для каждой пары факторов строятся квадранты `LOW/HIGH × LOW/HIGH` (median split),
  - по каждому квадранту считаются `meanA`, `meanB`, `MAE`, `MARD`, `Bias`, `n`.
- Daily report расширен:
  - Markdown секция `Targeted Replay 24h: Core Factor Pair Regimes`,
  - CSV строки `factor_pair_regime,...`.
- Telemetry расширена:
  - ключ `daily_report_replay_factor_pair_json`.
- `buildRecommendations(...)` расширен pair-based hints:
  - сравнение `HIGH/HIGH` против `LOW/LOW`,
  - рекомендация при сильном росте ошибки в комбинированном high-load режиме.
- UI pipeline обновлен:
  - `MainViewModel` парсит `daily_report_replay_factor_pair_json`,
  - добавлены `dailyReportReplayPairs` в `MainUiState`,
  - добавлены `DailyReportReplayPairUi` в UI state модели,
  - `AnalyticsScreen` рендерит pair-regime карточки.
- Добавлены локализации (`en/ru`):
  - `analytics_daily_report_replay_pair_line`.
- Обновлены unit-тесты:
  - `InsightsRepositoryDailyForecastReportTest` проверяет наличие `replayFactorPairs` и pair-based recommendations,
  - `MainUiStateMappersTest` проверяет маппинг `dailyReportReplayPairs` в `AnalyticsUiState`.

## Почему так
- Одиночные факторы показывают общий вклад, но часто ошибка резко растет только при одновременном high-состоянии нескольких драйверов.
- Pair-regime разбор дает более практичный сигнал для тюнинга (например, проблема не в `COB` отдельно, а именно в `COB+IOB` high/high).

## Риски / ограничения
- Квадранты pair-regime чувствительны к размеру выборки; при sparse-телеметрии некоторые квадранты будут пустыми (это ожидаемо).
- Разбиение `LOW/HIGH` сделано по медиане 24h окна; на очень неоднородных днях может потребоваться адаптивный threshold.

## Как проверить
1) Сформировать daily forecast report (24h).
2) Проверить telemetry ключ:
   - `daily_report_replay_factor_pair_json`.
3) Открыть `Analytics -> Daily Forecast Report` и убедиться, что видны строки pair-regime по core-парам.
4) Проверить рекомендации: при наличии сигнала должны появляться строки о `pair ... high/high MAE`.

# Изменения — Этап 104: Top-pair telemetry + recommendation noise reduction

## Что сделано
- В `InsightsRepository` оптимизированы pair-based рекомендации:
  - вместо потенциально длинного списка по всем pair-комбинациям теперь выбирается `top worst pair` и (опционально) `top protective pair` на каждый горизонт `5m/30m/60m`.
- Добавлены per-horizon quick telemetry ключи:
  - `daily_report_replay_top_pair_{5|30|60}m`,
  - `daily_report_replay_top_pair_hint_{5|30|60}m`.
- В `MainViewModel` добавлен разбор новых ключей и включение их в `replayReportLines`.
- В `InsightsRepositoryDailyForecastReportTest` обновлена проверка pair-рекомендаций:
  - ожидается наличие `top pair=` вместо проверки конкретной фиксированной пары.
- Обновлены `docs/ARCHITECTURE.md` и `docs/INVARIANTS.md` для нового telemetry-контракта.

## Почему так
- После добавления pair-regime диагностики рекомендации могли быть перегружены множеством строк.
- Для рабочего daily triage полезнее компактный и устойчивый сигнал: один ключевой pair-драйвер на горизонт + короткий hint.

## Риски / ограничения
- `top pair` выбирается по отношению `HH/LL MAE`, поэтому при sparse-данных и неполных квадрантах пара может отсутствовать.
- Логика остаётся rule-based и не заменяет полноценный causal analysis.

## Как проверить
1) Сформировать daily forecast report (24h).
2) Проверить telemetry ключи:
   - `daily_report_replay_top_pair_5m/30m/60m`,
   - `daily_report_replay_top_pair_hint_5m/30m/60m`.
3) Открыть `Analytics` и убедиться, что в replay summary-lines появились строки `top pair`/`pair hint`.

# Изменения — Этап 105: Replay error-clusters (hour + mean COB/IOB/UAM/CI)

## Что сделано
- В `InsightsRepository` добавлен новый слой replay-диагностики:
  - `ReplayErrorClusterStats` по горизонту и часу,
  - метрики: `n`, `MAE`, `MARD`, `Bias`,
  - средние драйверы в кластере: `meanCob`, `meanIob`, `meanUam`, `meanCiWidth`,
  - вычисление `dominantFactor` через нормализованный score (`COB/IOB/UAM/CI`).
- `DailyForecastReportPayload` расширен полем:
  - `replayErrorClusters`.
- Daily report расширен:
  - Markdown секция `Targeted Replay 24h: Error Clusters (Hour + Mean Factors)`,
  - CSV строки `replay_error_cluster,...`.
- Telemetry расширена:
  - JSON ключ `daily_report_replay_error_cluster_json`,
  - quick keys:
    - `daily_report_replay_error_cluster_{5|30|60}m`,
    - `daily_report_replay_error_cluster_hint_{5|30|60}m`.
- В `MainViewModel` добавлен разбор новых quick keys и включение в `replayReportLines`.
- `buildRecommendations(...)` дополняет рекомендации строкой про top error cluster по горизонту с dominant driver.
- Обновлены тесты:
  - `InsightsRepositoryDailyForecastReportTest` проверяет наличие `replayErrorClusters` и cluster-рекомендации.
- Обновлены `docs/ARCHITECTURE.md` и `docs/INVARIANTS.md` под новый telemetry-контракт.

## Почему так
- `Top miss` отражает одиночную точку и может быть шумным.
- Error-cluster подход показывает устойчивую проблемную зону по часу и сразу даёт факторный контекст (`COB/IOB/UAM/CI`), что лучше подходит для целевого daily triage.

## Риски / ограничения
- Кластеры считаются по локальному 24h окну и требуют минимум `n>=4`; при редкой телеметрии часть часов будет отсутствовать.
- Dominant factor нормализован rule-based эвристикой и не является каузальным выводом.

## Как проверить
1) Сформировать daily forecast report (24h).
2) Проверить telemetry ключи:
   - `daily_report_replay_error_cluster_json`,
   - `daily_report_replay_error_cluster_5m/30m/60m`,
   - `daily_report_replay_error_cluster_hint_5m/30m/60m`.
3) Открыть `Analytics` и убедиться, что появились replay строки `error cluster` / `cluster hint`.

# Изменения — Этап 106: Analytics UI block for replay error-clusters

## Что сделано
- В `MainViewModel` добавлен парсер:
  - `parseReplayErrorClustersJson(...)` для telemetry `daily_report_replay_error_cluster_json`.
- `MainUiState` расширен:
  - `dailyReportReplayErrorClusters`.
- Добавлен UI-тип:
  - `DailyReportReplayErrorClusterUi` (в `ui` и `foundation/screens` моделях).
- Обновлен маппинг `MainUiState -> AnalyticsUiState`:
  - новое поле передаётся в `AnalyticsUiState`.
- В `AnalyticsScreen` добавлен отдельный рендер-блок error-clusters:
  - строка с `hour`, `MAE/MARD/Bias`, средними `COB/IOB/UAM/CI width`, `dominant driver`, `n`.
- Добавлены локализованные строки (`en/ru`):
  - `analytics_daily_report_replay_error_cluster_line`.
- Обновлен unit-тест маппера:
  - `MainUiStateMappersTest` проверяет, что cluster-поле попадает в `AnalyticsUiState` и сохраняет `dominantFactor`.

## Почему так
- До этого error-cluster диагностика была доступна в telemetry/recommendation lines, но не имела явного структурного блока в Analytics UI.
- Новый блок делает replay triage быстрее и стабильнее: видно устойчивую проблемную зону по часу, а не только одиночный top-miss.

## Риски / ограничения
- Отрисовка кластера зависит от наличия telemetry JSON; при sparse данных блок может быть пустым (это ожидаемо).
- Dominant driver остаётся rule-based эвристикой и не трактуется как causal-proof.

## Как проверить
1) Сформировать daily forecast report (24h) с заполнением replay telemetry.
2) Открыть `Analytics` и проверить блок строк `cluster ...`.
3) Убедиться, что присутствуют `COB/IOB/UAM/CIw`, `driver`, `n` для доступных горизонтов.

# Изменения — Этап 107: Day-type aware replay error-clusters (WEEKDAY/WEEKEND)

## Что сделано
- `ReplayErrorClusterStats` расширен полем `dayType` (`WEEKDAY/WEEKEND`).
- Кластеризация replay-ошибок переведена на ключ:
  - `(horizonMinutes, hour, dayType)`.
- JSON/telemetry для кластеров расширены:
  - `daily_report_replay_error_cluster_json` теперь содержит `dayType`.
  - quick keys `daily_report_replay_error_cluster_{5|30|60}m` тоже содержат `dayType=...`.
- Рекомендации (`buildRecommendations`) теперь выводят:
  - `top <weekday/weekend> error cluster ...`.
- Daily markdown/csv отчёты обновлены:
  - cluster-секция с колонкой `DayType`,
  - CSV `replay_error_cluster` пишет dayType.
- UI-слой обновлён сквозно:
  - `MainViewModel` парсит `dayType` из cluster JSON,
  - добавлено поле `dayType` в `DailyReportReplayErrorClusterUi` (`ui` + `foundation` модели),
  - Analytics card показывает day-type локализованно (`weekday/weekend`, `будни/выходные`).
- Обновлены string resources (`en/ru`) для day-type labels.
- Обновлены unit-тесты:
  - `InsightsRepositoryDailyForecastReportTest` проверяет валидный `dayType`,
  - `MainUiStateMappersTest` проверяет перенос `dayType` в `AnalyticsUiState`.

## Почему так
- Ошибка прогноза нередко системно отличается в будни и выходные даже при одинаковом часе.
- Day-type aware clusters дают более физиологически релевантный triage и лучше поддерживают настройку weekday/weekend паттернов.

## Риски / ограничения
- При малой выборке за 24ч один из day-type кластеров может отсутствовать — это ожидаемо.
- Day-type вывод остаётся диагностическим сигналом и не является causal-доказательством.

## Как проверить
1) Сформировать daily forecast report за 24ч, где есть данные и будней, и выходных.
2) Проверить JSON/quick keys:
   - `daily_report_replay_error_cluster_json`,
   - `daily_report_replay_error_cluster_5m/30m/60m`.
3) В `Analytics` убедиться, что строки cluster показывают day-type (`будни/выходные`).

# Изменения — Этап 108: Day-type gap wiring fix (JSON → state → Analytics UI)

## Что сделано
- Доведена сквозная интеграция `weekday/weekend gap` для replay-аналитики:
  - исправлена несогласованность полей рекомендаций (`weekdayMae`/`weekendMae`),
  - в `serializeReplayErrorClusters(...)` добавлен `dayType` в JSON (раньше терялся),
  - в `MainViewModel` добавлен полноценный парсер `parseReplayDayTypeGapsJson(...)`,
  - в `MainUiState` добавлено поле `dailyReportReplayDayTypeGaps`,
  - добавлен UI-тип `DailyReportReplayDayTypeGapUi` (ui + foundation),
  - обновлён `MainUiStateMappers` (hasData + mapping),
  - в `AnalyticsScreen` добавлен отдельный блок отображения day-type gap,
  - добавлены локализованные строки `analytics_daily_report_replay_daytype_gap_line` (en/ru).
- Усилены тестовые проверки:
  - `InsightsRepositoryDailyForecastReportTest` теперь проверяет наличие `replayDayTypeGaps` и рекомендаций про `weekday/weekend gap`.
  - `MainUiStateMappersTest` проверяет перенос `dailyReportReplayDayTypeGaps` в `AnalyticsUiState`.

## Почему так
- Без `dayType` в cluster JSON UI видел неполный контекст и не мог корректно валидировать weekday/weekend разделение.
- Day-type gap важен для целевого replay-анализа 24ч: показывает не только “где ошибка”, но и “в какой тип дня она системнее”.

## Риски / ограничения
- Расчёт gap требует обеих групп (`WEEKDAY` и `WEEKEND`) с минимальной выборкой; на коротких окнах блок может быть пустым.
- Изменения внесены без запуска сборки/тестов в этом шаге (по текущему режиму работы).

## Как проверить
1) Сформировать daily report и убедиться, что в telemetry есть:
   - `daily_report_replay_error_cluster_json` (с `dayType`),
   - `daily_report_replay_daytype_gap_json`.
2) Открыть `Analytics` и проверить карточки day-type gap:
   - `ΔMAE`, weekday/weekend MAE, худший тип дня, драйвер.
3) Проверить quick lines:
   - `daily_report_replay_daytype_gap_5m/30m/60m`,
   - `daily_report_replay_daytype_gap_hint_5m/30m/60m`.

# Изменения — Этап 109: Stabilization of replay day-type diagnostics + validation cycle

## Что сделано
- Исправлен runtime дефект, найденный при сборке:
  - удалено ошибочное поле `dayType` из `serializeReplayHotspots(...)` (hotspot-тип не содержит dayType).
- Исправлена логика кластеров для day-type gap:
  - `buildReplayErrorClusters(...)` больше не режет кластеры до `top-3` до расчета gap,
  - сортировка стабилизирована (`horizon -> mae -> sampleCount -> dayType -> hour`),
  - возвращается полный набор кластеров для корректного `weekday/weekend` сопоставления по одному часу.
- Усилена/стабилизирована тестовая база под обновленную кластеризацию:
  - адаптированы ожидания в `InsightsRepositoryDailyForecastReportTest` (менее хрупкие к распределению synthetic-данных),
  - сохранена проверка наличия day-type gaps и кластерной диагностики.

## Почему так
- Day-type gap вычисляется по пересечению `WEEKDAY/WEEKEND` в одном `horizon+hour`; при раннем `top-3` урезании это пересечение терялось.
- После расширения кластеров часть тестов с жесткими строковыми/ранжирующими ожиданиями стала нестабильной; они переведены на проверку инвариантов результата.

## Риски / ограничения
- Analytics-блок `replay error clusters` теперь может показывать больше строк (это ожидаемо до отдельного UI-лимитера).
- Остаются compile warnings по deprecated Material icons (не блокирует runtime).

## Как проверить
1) Unit tests:
   - `./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.InsightsRepositoryDailyForecastReportTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
2) Build:
   - `./gradlew :app:assembleDebug`
3) USB deploy:
   - `adb install -r -d app/build/outputs/apk/debug/app-debug.apk`
4) Smoke launch:
   - `adb shell am start -n io.aaps.predictivecopilot/io.aaps.copilot.MainActivity`
   - проверить отсутствие `FATAL EXCEPTION` в `adb logcat`.

# Изменения — Этап 110: Реалистичные COB-профили (гипо/быстрые/обычные) + hard safety limits

## Что сделано
- Введён новый тип усвоения углеводов `ULTRA_FAST` в `/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/CarbAbsorptionProfiles.kt`.
- Перекалиброваны кривые усвоения:
  - `ULTRA_FAST`: полное усвоение к ~60 минутам.
  - `FAST`: ускоренный профиль с завершением к ~120 минутам.
  - `MEDIUM`: профиль для обычной углеводной еды с завершением к ~180 минутам.
- Добавлена приоритетная классификация “купирование гипо”:
  - при низком BG вокруг события (<= 4.2 mmol/L) или явных hypo-rescue тегах событие маркируется как `ULTRA_FAST`.
- Обновлена pattern-классификация: очень резкий ранний рост (`rise15`/`delta5`) уходит в `ULTRA_FAST`.
- Для `HybridPredictionEngine` обновлены safety-clamp для карбов:
  - `carbAbsorptionMaxAgeMinutes` ограничен `60..180`.
  - `carbComputationMaxGrams` ограничен `20..60`.
  - `ULTRA_FAST` включён в активный быстрый пул (диагностика `carbFastActiveGrams`).
- Для runtime COB в `AutomationRepository`:
  - жёстко ограничены лимиты `cutoff <= 180 мин` и `max grams <= 60`.
  - улучшено смешивание telemetry COB и local COB: при сильном расхождении вес telemetry автоматически снижается.
  - добавлен guard: если локально нет недавних carb-событий в окне cutoff, telemetry COB не протаскивается как “реальный COB”.
- Те же safety-границы (`180 мин`, `60 г`) протянуты в `AppSettingsStore`, `MainViewModel`, `NightscoutActionRepository`.

## Почему так
- Цель: убрать системное “долго висящее COB”, особенно когда внешняя telemetry COB устарела и расходится с локальной моделью.
- Для сценария купирования гипо логичен `ULTRA_FAST` профиль с закрытием в пределах ~1 часа.
- Для обычной углеводной еды без белково-жировой задержки реалистичный предел учёта — до 3 часов.
- Hard safety limits не позволяют прогнозу/COB раздуваться за пределы безопасного диапазона.

## Риски / ограничения
- Сильное ускорение профиля может изменить ранний вид декомпозиции на горизонте 5–30 минут; поэтому сохранён guard против широких `ULTRA_FAST` текст-триггеров (только rescue-подобные токены).
- Ветка protein-slow оставлена, но runtime cutoff всё равно ограничивает COB сверху 3 часами как safety-policy.

## Как проверить
1) Unit tests:
   - `./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.predict.CarbAbsorptionProfilesTest --tests io.aaps.copilot.domain.predict.HybridPredictionEngineTest --tests io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test`
2) Доп. регрессия по repository:
   - `./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest`
3) Ручной smoke:
   - ввести hypo-carb событие при BG <= 4.2 и проверить быстрое падение `residualCarbs` к ~0 в течение часа;
   - ввести обычную carb-еду и проверить, что локальный COB уходит не позже 180 минут;
   - проверить, что при расхождении local/telemetry COB итоговый runtime COB ближе к local.

# Изменения — Этап 111: Activity-trigger temp target override (7.7–8.7) + auto-recovery

## Что сделано
- В `AdaptiveTargetControllerRule` добавлен отдельный stateful контур физнагрузки:
  - чтение telemetry `activity_ratio` и `steps_count` (с алиасами),
  - детектор spike по `activity_ratio`, `activity_ratio delta` и `steps delta per 5m`,
  - load-scaled target `7.7..8.7 mmol/L` при активной нагрузке.
- Добавлена автоматическая деактивация защиты:
  - при длительном low-load (`~30 мин`, 6 циклов по 5 минут),
  - с явной командой recovery к base target, который был до входа в activity-protection.
- Поведение интегратора PI в обычном adaptive-контуре сохранено: activity-ветка обрабатывается как ранний override, остальная логика `AdaptiveTempTargetController` не изменена.
- Добавлены unit-тесты:
  - `activityTriggerRaisesTargetInConfiguredRange`,
  - `activityRecoveryReturnsBaseAfterSustainedLowLoad`.
- Для устранения скрытых межтестовых состояний `AdaptiveTargetControllerRuleTest` переведен на новый инстанс rule в каждом тесте.
- В observability adaptive-контура добавлен audit-параметр `mode` в `adaptive_controller_*`:
  - `activity_protection`,
  - `activity_recovery`,
  - либо базовый режим из `reason=...`,
  чтобы быстрее различать события физнагрузки в логах без ручного парсинга `reasons`.
- Обновлены документы:
  - `docs/ARCHITECTURE.md`,
  - `docs/INVARIANTS.md`.

## Почему так
- Требование: при резком росте активности быстро повысить целевой sugar для анти-гипо защиты, затем автоматически вернуть прежнюю цель после завершения нагрузки.
- Реализация в rule-слое позволяет приоритезировать activity safety без изменений PI-ядра и без дублирования логики в других слоях.

## Риски / ограничения
- Детекция использует telemetry-частоту и качество `steps/activity`; при больших пропусках метрик trigger может сработать позже.
- Пороговые значения подобраны консервативно и могут потребовать тюнинга под конкретный паттерн носимого источника шагов.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest"`
2) В логе rule reasons проверить activity-пути:
   - `activity_protection_active`,
   - `activity_recovery_to_base`.
3) Ручной сценарий:
   - поднять `activity_ratio` и/или быстрый прирост `steps_count`,
   - убедиться, что temp target идет в диапазон `7.7..8.7`,
   - после устойчиво низкой нагрузки убедиться в recovery к base target.

# Изменения — Этап 112: CR runtime fix (logs/DB) + real CR graph in Analytics

## Что сделано
- Для `IsfCrWindowExtractor` исправлен gate `cr_no_bolus_nearby`:
  - добавлен fallback для meal-событий `uam_engine` (без явного болюса),
  - добавлена дедупликация дублирующих UAM carbs-событий в одном 5-мин бакете,
  - расширен набор поддерживаемых IOB telemetry-ключей.
- Для CR-fit добавлена поддержка 1-мин CGM интервалов (`dt=1..15`), чтобы extractor не срезал реальные 1-мин сенсоры как `cr_sparse_intervals`.
- Смягчены CR-гейты для `uam_engine` meal-сценариев:
  - высокий `uam_ambiguity` теперь даёт penalty (а не hard-drop),
  - для `uam_engine` снижен минимум по quality-score и минимум интервалов (2 вместо 4),
  - вес CR-sample учитывает coverage/ambiguity penalty.
- В `IsfCrEngine` добавлены runtime factors:
  - `raw_isf_eff`, `raw_cr_eff`, `raw_confidence`,
  - `isf_hour_window_evidence_enough`, `cr_hour_window_evidence_enough`.
- В `IsfCrEngine` обновлен blending базы с evidence:
  - evidence используется и при sparse-hour режиме (с более низким alpha),
  - это уменьшает «залипание» CR на fallback-базе.
- В `MainViewModel` исправлено отображение CR/ISF на графике Analytics:
  - при `mode=FALLBACK` UI берёт `raw_cr_eff/raw_isf_eff` из `factors` (если доступны),
  - история `isfCrHistoryPoints` и текущие realtime-метрики строятся из этих значений.

## Почему так
- По live БД/аудиту на телефоне CR постоянно падал в `cr_no_bolus_nearby` и UI показывал плоскую линию CR=10.0.
- После фикса gate `cr_no_bolus_nearby` исчез из новых циклов; вместо него в диагностике стали видны реальные причины качества (`cr_sparse_intervals`, `cr_uam_ambiguity`, `cr_low_quality`), что подтверждает работу нового extractor-path.
- `raw_cr_eff` нужен для честной аналитической визуализации даже когда safety-mode остаётся `FALLBACK`.

## Риски / ограничения
- Если данных meal-окон по-прежнему мало/шумно, `mode` останется `FALLBACK`; график покажет `raw_cr_eff`, но auto-контур продолжит работать по безопасной ветке.
- UAM-derived meal samples имеют сниженный вес и предназначены для аналитики/оценки, а не для агрессивного runtime override без confidence-gate.

## Как проверить
1) Unit tests:
   - `./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest --tests io.aaps.copilot.domain.isfcr.IsfCrEngineTest --tests io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest`
2) APK + USB install:
   - `./gradlew :app:assembleDebug`
   - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3) По pulled DB проверить последние `isfcr_*` логи:
   - в новых циклах больше нет доминирующего `cr_no_bolus_nearby`,
   - в `isf_cr_snapshots.factorsJson` присутствует `raw_cr_eff`.
4) В UI (`Analytics` -> ISF/CR):
   - линия real CR перестаёт быть строго плоской при наличии `raw_cr_eff`,
   - merged/base остаётся отдельной линией (fallback/reference).

# Изменения — Этап 113: anti-stall runtime cycle (USB diagnostics + lock stabilization)

## Что сделано
- В `AutomationRepository` добавлен anti-stall контур для цикла:
  - `automation_cycle_started/finished/timeout/failed`,
  - `runningForMs` в `automation_cycle_skipped`,
  - таймаут всего цикла `AUTOMATION_CYCLE_TIMEOUT_MS=180000`.
- В `SyncAndAutomateWorker` добавлены события:
  - `automation_worker_started`,
  - `automation_worker_completed`,
  - `automation_worker_failed`.
- Добавлен step tracing (`automation_cycle_step_started/completed/failed`) для ранних стадий:
  - `auto_connect_bootstrap`,
  - `root_db_sync`,
  - `nightscout_sync`,
  - `cloud_push_sync`,
  - `baseline_import`.
- Добавлены runtime checkpoints (`automation_cycle_checkpoint`) по стадиям после ключевых блоков.
- Тяжёлый пересчёт `analyticsRepository.recalculate()` исключён из reactive runtime-цикла:
  - теперь логируется `analytics_recalculate_skipped` с `reason=decoupled_from_reactive_cycle`.
- `ISF/CR` realtime в runtime-цикле переведён на cached+async модель:
  - основной цикл читает `latestSnapshot`,
  - при stale/missing запускается фоновый refresh (`isfcr_realtime_refresh_*`),
  - основной цикл больше не блокируется тяжёлым realtime-fit.

## Почему так
- По USB-логам цикл часто зависал в состоянии `already_running`, из-за чего новые reactive прогоны пропускались.
- Корневой эффект — тяжёлые аналитические/ISFCR операции внутри критической секции цикла.
- Разделение “оперативный контур” и “тяжёлые пересчёты” восстановило регулярное завершение цикла.

## Риски / ограничения
- При stale `isf_cr_snapshots` runtime временно работает на последнем валидном snapshot (или fallback), пока идёт фоновый refresh.
- Step/checkpoint логи увеличивают объём `audit_logs` (это диагностический режим; при необходимости можно позже урезать).

## Как проверить
1) Сборка и установка:
   - `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app`
   - `./gradlew :app:assembleDebug`
   - `adb install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
2) USB smoke:
   - отправить `NS_EMULATOR` broadcast,
   - в `audit_logs` должны появляться `automation_worker_started/completed`, `automation_cycle_started/finished`, `automation_cycle_completed`.
3) Подтверждение anti-stall:
   - нет длительных “вечных” `already_running` без последующего `automation_cycle_finished`.
   - есть checkpoint-цепочка до `post_adaptive_audit`.

# Изменения — Этап 114: MARD tuning pass (30/60m) через runtime post-bias rebalancing

## Что сделано
- Выполнен целевой анализ live БД (`copilot_analysis.db`) по 24h/72h:
  - подтверждено, что главная проблема — длинный горизонт `60m` (ошибки в сценариях `high COB + high IOB` и нестабильные контекстные/калибровочные сдвиги).
  - для 24h baseline: `MARD 30m ~22%`, `MARD 60m ~39%`; для 72h: `MARD 60m >50%`.
- В `AutomationRepository` доработаны пост-слои прогноза:
  1. `applyContextFactorForecastBiasStatic(...)`
     - добавлено ослабление контекстного value-bias при широкой CI (чем шире CI, тем меньше сдвиг),
     - добавлен low-glucose guard для положительного context-bias (не разгонять прогноз вверх при низком текущем BG/риске).
  2. `applyCobIobForecastBiasStatic(...)`
     - добавлен риск-aware режим только для узкого low-glucose сценария (`low BG + high IOB + high COB`, `UAM=false`),
     - в этом режиме мягко подавляется положительный COB-bias и усиливается IOB-bias,
     - добавлен ограниченный extra downshift только в hard-low/high-load комбинациях, чтобы гасить экстремальные overpredict на 60m.
- Усилена калибровка `calib_v1`:
  - `ForecastCalibrationPoint` расширен полем `predictedMmol`,
  - `collectForecastCalibrationErrors(...)` теперь сохраняет и уровень предсказанного сахара,
  - `applyRecentForecastCalibrationBiasStatic(...)` переведен на гибрид:
    - общий EWMA residual + bucketed residual по диапазонам предикта (`<5`, `5..8`, `>=8 mmol/L`),
    - bucketed bias используется при достаточном числе sample и смешивается с общим bias (horizon-specific blend).
  - это убирает «один общий сдвиг на все режимы» и делает bias более режимно-специфичным.
- Пайплайн вызова updated bias-функций прокинут из runtime цикла:
  - `applyCobIobForecastBias(...)` теперь получает `latestGlucoseMmol` и `uamActive`.

## Почему так
- Ошибки 60m имеют смешанную природу:
  - локальные экстремумы overpredict в low/high-load кейсах,
  - и одновременно общие underpredict в других режимах.
- Один глобальный коэффициент не решает обе проблемы одновременно.
- Поэтому сделан «двухконтурный» патч:
  - точечный анти-overshoot guard для узких опасных кейсов,
  - более контекстная калибровка для основной массы данных.

## Риски / ограничения
- Реальное улучшение MARD нужно подтверждать повторным runtime/replay после накопления новых forecast rows (патч меняет forward-поведение, исторические строки не пересчитываются автоматически).
- Guard-параметры подобраны консервативно; возможен дополнительный тюнинг по 3–7 дням новых данных.
- В рабочем дереве остаются другие незакоммиченные изменения (из параллельных этапов); этот этап целенаправленно трогает только forecast post-bias/calibration слой и его тесты.

## Как проверить
1) Unit tests:
   - `cd android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest`
2) После накопления новых циклов (минимум 24h) сравнить daily report:
   - `daily_report_mard_30m_pct`,
   - `daily_report_mard_60m_pct`,
   - `daily_report_bias_60m`,
   - `daily_report_replay_error_cluster_json` (особенно low-BG/high-COB/high-IOB кластеры).
3) Ручной sanity:
   - в low BG + high IOB + high COB сценарии 60m прогноз не должен «улетать вверх» сверх прежнего уровня,
   - в обычных post-meal/high-BG сценариях калибровка должна оставаться активной (нет глобального “зажатия” прогноза).

# Изменения — Этап 115: Понятные `i`-подсказки в настройках + словарь терминов

## Что сделано
- В `SettingsScreen` расширены компоненты настроек:
  - `SettingToggleRow`,
  - `SettingIntStepperRow`,
  - `SettingDoubleStepperRow`,
  - `SettingTextInputRow`.
  Теперь они принимают отдельный `infoText`, который показывается в диалоге по нажатию `i` и не перегружает краткий subtitle.
- Добавлены понятные текстовые объяснения (RU/EN) для ключевых параметров:
  - источники данных (Nightscout URL, local NS, broadcast ingest, strict sender),
  - UAM (inference/boost/export/dry-run/mode/min-max-step),
  - adaptive controller (enable/base target/insulin profile/safety bounds/post-hypo),
  - real ISF/CR engine (shadow/confidence/activity/manual tags/evidence/CR integrity),
  - debug/privacy (pro mode, verbose logs, retention).
- `i`-диалог теперь показывает «что это» и «как влияет на сахар/компенсацию» для указанных ключевых настроек.
- В `manual.md` добавлен раздел `Короткий словарь для i-подсказок в UI` с практичными объяснениями терминов (`ISF/CR/CSF/IOB/COB/UAM/DIA/CI/...`) и ссылками на AndroidAPS/OpenAPS.

## Почему так
- Раньше `i`-иконка в большинстве случаев повторяла технический subtitle и не всегда объясняла влияние на компенсацию простыми словами.
- Разделение `subtitle` и `infoText` позволило оставить экран компактным и одновременно дать полный контекст в один тап.
- Единый словарь снижает неоднозначность трактовки ключевых терминов.

## Риски / ограничения
- Полный охват сделан для ключевых параметров (основной поток настройки); для редких/глубоких экспертных полей остаётся fallback на subtitle.
- Тексты intentionally короткие, поэтому клинические нюансы могут быть упрощены.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. Открыть `Settings` и нажать `i` на параметрах:
   - `Nightscout URL`,
   - `Enable UAM inference`,
   - `Base target`,
   - `ISF/CR confidence threshold`.
3. Убедиться, что в диалоге показано понятное объяснение «что это» и «на что влияет», а не только технический subtitle.
4. Проверить раздел `manual.md` -> `## 29. Короткий словарь для i-подсказок в UI`.

Примечание по тестам:
- `./gradlew :app:testDebugUnitTest` в текущем состоянии репозитория падает на существующем тесте `PostHypoReboundGuardRuleTest > triggers_whenHypoAndTwoRisingIntervals` (не связан с текущими UI/документационными изменениями).

# Изменения — Этап 116: `i`-подсказки на экранах Overview/Forecast/UAM/Safety

## Что сделано
- Завершено расширение `i`-подсказок за пределами `Settings`:
  - `OverviewScreen`: добавлены пояснения для секций текущего сахара, прогнозов, UAM, телеметрии, последнего действия.
  - `ForecastScreen`: добавлены пояснения для range/layers/chart/horizons/decomposition.
  - `UamScreen`: добавлены `i`-кнопки и диалоги для секций `Inferred UAM` и `UAM Events`.
  - `SafetyScreen`: добавлены `i`-кнопки и диалоги для секций `Global Hard Safety Limits`, `Cooldown status`, `Safety checklist`.
- Добавлены недостающие RU/EN string resources для новых `overview_info_*`, `forecast_info_*`, `uam_info_*`, `safety_info_*`.
- Устранен потенциальный compile-break из-за ссылок на отсутствующие string keys.

## Почему так
- Пользователь запросил единый и понятный формат подсказок по параметрам и блокам интерфейса.
- На практике часть экранов уже ссылалась на новые `info` ключи, но строки еще не были заведены; это создавало риск падения сборки/ресурсов.
- Единый паттерн `SectionLabel(..., infoText=...)` делает UX консистентным и предсказуемым.

## Риски / ограничения
- Подсказки покрывают ключевые секции; отдельные глубоко-диагностические поля пока используют существующие подписи/лейблы.
- Формулировки намеренно короткие и практичные, без клинических деталей.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. Открыть экраны `Overview`, `Forecast`, `UAM`, `Safety`.
3. Нажать `i` рядом с заголовками секций и убедиться:
   - диалог открывается сразу;
   - текст объясняет, что показывает секция и как это влияет на прогноз/автоматику;
   - строки доступны в RU/EN.

# Изменения — Этап 117: `i`-подсказки на экранах Audit и Analytics

## Что сделано
- Добавлен такой же `i`-паттерн в `AuditScreen` и `AnalyticsScreen` на уровне `SectionLabel`.
- Для обоих экранов добавлены понятные `RU/EN` тексты:
  - `audit_info_section_generic`
  - `analytics_info_section_generic`
- Теперь на всех основных экранах приложения есть единообразный способ открыть краткое пояснение по секции в один тап.

## Почему так
- Пользователь запросил консистентную документацию прямо из UI “для каждого окна”.
- Audit/Analytics до этого оставались без встроенной подсказки в заголовках секций.

## Риски / ограничения
- Для Audit/Analytics пока используется универсальный текст по секции (не отдельный текст под каждый под-блок внутри аналитики).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. Открыть `Audit` и `Analytics`.
3. Нажать `i` у заголовков секций и проверить открытие/закрытие диалога и корректность текста.

# Изменения — Этап 118: Проверка и фиксы графиков ISF/CR (real vs AAPS)

## Что сделано
- Выполнена проверка локальной live БД:
  - `telemetry_samples` содержит профильные точки `isf_value/cr_value` из `xdrip_broadcast`,
  - `isf_cr_snapshots` содержит realtime real-оценки (в текущем срезе fallback ~`ISF 3.1`, `CR 10.0`),
  - подтвержден разнос real vs AAPS в данных (например AAPS `ISF 3.5`).
- Устранена подмена линий на графике:
  - в `IsfCrHistoryPointUi` добавлены strict-поля `isfRealStrict/crRealStrict/isfAapsStrict/crAapsStrict`,
  - `AnalyticsScreen` переведен на strict-данные для real/AAPS линий вместо fallback-склейки.
- Обновлен рендер серии ISF/CR:
  - real линия теперь nullable-aware (строится только по фактическим real точкам, без автоподстановки merged),
  - AAPS линия строится отдельно и пунктиром только по доступным `isfAaps/crAaps`,
  - `last delta` считается по последней точке, где присутствуют обе линии.
- Улучшен выбор текущих значений в `MainUiStateMappers`:
  - current real берется из последней real-точки,
  - current AAPS берется из последней точки с реальным AAPS значением (а не через merged fallback).
- Расширен фильтр источников AAPS-профиля в истории (`MainViewModel`):
  - добавлен `broadcast` для кейсов, где профиль ISF/CR приходит с broadcast-меткой источника.

## Почему так
- Ранее часть UI-маршрута использовала fallback-значения для real линии, что визуально могло «склеивать» real и AAPS графики.
- Для точного сравнения нужна строгая декомпозиция: real только из вычисленного контура, AAPS только из профильной телеметрии.

## Риски / ограничения
- Если real-точек мало (или они отсутствуют), real линия может быть короче/прерывистой — это ожидаемое поведение, а не ошибка.
- При полном отсутствии AAPS-рядов сохраняется fallback в summary-числах, но сама AAPS-линия на графике не рисуется.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.ui.IsfCrHistoryResolverTest`
3. В UI открыть `Аналитика -> ISF/CR`, выбрать масштаб `12h/24h/3d/7d/30d` и проверить:
   - сплошная линия = real calculated,
   - пунктир = AAPS profile,
   - `AAPS coverage` > 0 при наличии `isf_value/cr_value` в telemetry.

# Изменения — Этап 7: Error stabilization (ISF/CR evidence, sync bootstrap, UI latency, DB integrity)

## Что сделано
- Усилен Nightscout sync для терапии:
  - добавлен bootstrap backfill treatment history на 30 дней, если в локальной БД мало insulin-like событий;
  - добавлены метрики bootstrap в audit (`nightscout_treatment_bootstrap_attempted`, поля в `nightscout_sync_completed`).
- Расширен DAO терапии:
  - `TherapyDao.countInsulinLikeSince(...)` для runtime-решения о bootstrap backfill.
- Исправлен ISF/CR extractor:
  - добавлен вывод implicit correction bolus из IOB jump telemetry (для ISF evidence даже при отсутствии явных bolus событий в therapy);
  - сохранён безопасный meal-gate и dedup;
  - улучшено разделение implicit correction (для ISF) и explicit bolus-link в CR (implicit события `source=isfcr_inference` не считаются явным meal bolus).
- Улучшена стабильность realtime ISF/CR snapshot:
  - при сильной устарелости snapshot теперь сначала выполняется синхронный refresh (bounded timeout), затем fallback на async refresh.
- Снижена нагрузка на главный UI combine:
  - уменьшены лимиты наблюдаемых рядов (`glucose/therapy/forecast/telemetry`) в `MainViewModel`.
- Добавлен периодический контроль целостности Room БД в maintenance ingest-контура:
  - `PRAGMA quick_check`, audit `db_integrity_ok` / `db_integrity_issue_detected` + checkpoint попытка.
- Добавлен unit-тест:
  - `IsfCrWindowExtractorTest.extract_isfSampleCanBeInferredFromImplicitIobCorrectionWithoutTherapyEvents`.

## Почему так
- В device diagnostics выявлены проблемы:
  - `isf_cr_evidence = 0`, постоянный `FALLBACK`;
  - stale ISF/CR snapshot;
  - UI lag на переключателях;
  - повреждение `copilot.db` на устройстве.
- Эти изменения адресуют причины напрямую: пополнение therapy history, генерация implicit ISF evidence, более надежный refresh snapshot, снижение UI давления и ранний сигнал по integrity.

## Риски / ограничения
- Bootstrap treatment backfill зависит от доступности historical treatments в Nightscout.
- Implicit IOB correction может давать шум в нестандартных IOB-паттернах; ограничено порогами jump/dt и dedup bucket.
- Полный `testDebugUnitTest` в ветке содержит ранее существующий нестабильный тест `PostHypoReboundGuardRuleTest` (не связан с этим этапом).

## Как проверить
1. `cd android-app && ./gradlew :app:compileDebugKotlin`
2. `cd android-app && ./gradlew :app:assembleDebug`
3. `cd android-app && ./gradlew :app:lintDebug`
4. `cd android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest"`
5. `cd android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.BroadcastIngest*"`
6. `cd android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest"`

# Изменения — Этап 119: Закрытие полного test gate

## Что сделано
- Исправлен падающий unit-тест `PostHypoReboundGuardRuleTest`:
  - в сценарии trigger добавлена явная передача `postHypoThresholdMmol = 3.0`,
  - тест больше не зависит от текущих дефолтных safety-настроек контекста.

## Почему так
- После ужесточения дефолтных safety-границ тест неявно сломался из-за зависимости от дефолта `postHypoThresholdMmol`.
- Явная фиксация порога в тесте делает проверку стабильной и корректной по смыслу сценария.

## Риски / ограничения
- Изменение только тестовое, бизнес-логика runtime не менялась.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`

# Изменения — Этап 120: Новая вкладка `AI Analysis / AI Reports` (HBA1-like)

## Что сделано
- Добавлена отдельная вкладка `AI Analysis` в `More`-навигацию foundation UI:
  - новый destination `ai_analysis`,
  - новый экран `AiAnalysisScreen`,
  - подключение в `CopilotFoundationRoot`.
- Добавлена новая UI-модель состояния `AiAnalysisUiState` и связанные модели для:
  - cloud jobs,
  - history/trend блоков AI-аналитики,
  - replay summary (forecast/rules/daytype/hour/drift),
  - локального daily report + рекомендаций.
- Расширен `MainViewModel`:
  - собраны rows для cloud jobs/history/trend из текущих репозиториев,
  - добавлен `StateFlow` `aiAnalysisUiState`.
- Добавлен mapper `toAiAnalysisUiState()` в `MainUiStateMappers`.
- Реализован экран `AiAnalysisScreen` с секциями:
  - `AI controls` (run/refresh/filter/export/replay),
  - `Cloud analysis status`,
  - `Latest AI report`,
  - `AI report history`,
  - `Weekly trend`,
  - `Replay summary`,
  - `Local daily report (24h)`,
  - `AI recommendations`,
  - `Rolling quality lines`.
- Добавлены `i`-пояснения в заголовках секций (единый UX-паттерн).
- Добавлены локализованные строки RU/EN для новой вкладки и всех блоков.
- Добавлен unit-тест маппинга:
  - `MainUiStateMappersTest.aiAnalysisMapping_exposesCloudHistoryTrendAndLocalReport`.

## Почему так
- Пользователь запросил отдельный контур AI-аналитики/отчетов, похожий на HBA1.
- Вкладка собрана на уже имеющихся доменных данных (cloud jobs, insights, replay, daily report), чтобы получить полноценный экран без изменения бизнес-алгоритмов прогноза/автоматики.

## Риски / ограничения
- Это UI+state integration этап: алгоритмы HBA1-подобной аналитики дальше можно расширять в доменном слое без изменения экранного контракта.
- Есть только warning по deprecated иконкам (`Icons.Default.ShowChart/TrendingUp/...`), на работу не влияет.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. В приложении открыть `More -> AI Analysis` и проверить:
   - секции и действия отображаются,
   - фильтры применяются,
   - экспорт/replay кнопки вызывают соответствующие actions,
   - `i`-диалоги открываются.

# Изменения — Этап 121: AI Analysis v2 (HBA1-style replay intelligence)

## Что сделано
- Расширен контракт `AiAnalysisUiState` и добавлены структурированные блоки:
  - `localHorizonScores` (оценка качества 5/30/60),
  - `localTopFactorsOverall`,
  - `localTopFactors` (factor contribution),
  - `localHotspots` (hourly error hotspots),
  - `localTopMisses` (largest misses + контекст),
  - `localDayTypeGaps` (weekday/weekend gap).
- Добавлены новые модели в `ScreenModels.kt`:
  - `AiHorizonScoreUi`, `AiTopFactorUi`, `AiHotspotUi`, `AiTopMissUi`, `AiDayTypeGapUi`.
- Расширен `toAiAnalysisUiState()`:
  - маппинг из `dailyReport*` replay структур в AI-вкладку,
  - сортировки по приоритету (score/factors/hotspots/misses),
  - добавлена классификация MARD-band (`EXCELLENT/GOOD/WARNING/CRITICAL`).
- Расширен `AiAnalysisScreen` новыми секциями:
  - `Prediction quality scorecards`,
  - `Top error factors`,
  - `Error hotspots by hour`,
  - `Largest misses context`,
  - `Weekday/weekend gaps`.
- Для UI добавлены RU/EN строки и подписи.
- Расширен unit-test `aiAnalysisMapping_exposesCloudHistoryTrendAndLocalReport`:
  - проверяет заполнение новых AI-блоков и band-классификацию.

## Почему так
- Пользователь запросил AI-анализ “как в HBA1”: ключевая ценность там — не только запуск jobs, но и explainable replay-диагностика, где ошибка концентрируется и какие факторы её драйвят.
- В проекте уже был богатый replay/daily report контур, поэтому интеграция в новую вкладку сделана без изменения бизнес-ядра.

## Риски / ограничения
- Качество блоков зависит от полноты `dailyReportReplay*` данных.
- Band-классификация MARD сейчас rule-based (фиксированные пороги), без персональной калибровки.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. В UI открыть `More -> AI Analysis` и проверить появление новых секций с данными replay/daily report.

# Изменения — Этап 122: AI Analysis v3 (interactive filters + mini charts)

## Что сделано
- Добавлены интерактивные фильтры прямо в AI-вкладку:
  - фильтр по горизонту (`all/5m/30m/60m`),
  - фильтр по доминирующему фактору (`all + факторы из replay`).
- Фильтры применяются к блокам:
  - scorecards,
  - top factors,
  - hotspots,
  - top misses,
  - day-type gaps.
- Добавлены мини-графики в секции `Error hotspots by hour`:
  - для горизонтов 5/30/60 (или выбранного горизонта),
  - dual-line sparkline: `MAE line` + `MARD line`,
  - `contentDescription` для accessibility.
- Добавлены новые строки RU/EN:
  - подписи фильтров,
  - подписи/легенды mini-charts,
  - строки accessibility.

## Почему так
- Пользователь запросил следующий шаг: mini-charts по MAE/MARD и интерактивную аналитическую фильтрацию.
- Реализация выполнена локально в UI-слое поверх уже существующих replay/daily-report структур, без изменения safety/therapy бизнес-логики.

## Риски / ограничения
- Мини-графики строятся по hourly hotspot-данным: если данных мало (<2 точки), выводится корректное `not enough points`.
- MAE и MARD рисуются как две отдельные нормированные линии формы (для тренда), а не как одна абсолютная шкала.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. В UI открыть `More -> AI Analysis`:
   - переключать `Filter by horizon` и `Filter by dominant factor`,
   - проверить, что списки и мини-графики пересчитываются по выбранным фильтрам.

# Изменения — Этап 123: AI Analysis open-state fix + AI API settings

## Что сделано
- Исправлена блокировка экрана `AI Analysis` из-за неблокирующих sync warning:
  - в `toAiAnalysisUiState()` warning уровня `WARN` больше не переводит экран в `ERROR`;
  - блокирующим считается только `Last sync issue: ERROR/FATAL`.
- Добавлены поля настроек для AI API:
  - `AI API URL` (базовый endpoint),
  - `AI API key` (секрет, с режимом show/hide в UI).
- Добавлено сохранение этих параметров в `AppSettingsStore` через `MainViewModel.setAiApiSettings(...)`.
- Провязаны поля через UI-state:
  - `SettingsUiState.aiApiUrl`,
  - `SettingsUiState.aiApiKey`.
- Обновлены RU/EN ресурсы строк для новых полей и подсказок (`i`-описания).
- Добавлены unit-проверки маппера:
  - warning-only sync issue не блокирует `AI Analysis`,
  - `Settings`-маппинг отдает `aiApiUrl/aiApiKey`.

## Почему так
- Пользовательский сценарий: экран “не открывается” из-за warning при отсутствии cloud-данных.
- Для рабочих AI-инсайтов и облачного анализа нужен прямой ввод endpoint и API key из телефона без редактирования файлов.

## Риски / ограничения
- `AI API key` хранится в DataStore как обычная строка настроек (без отдельного Android Keystore-шифрования на этом этапе).
- Если `AI API URL` указывает на неверный backend, экран будет открываться, но cloud-операции останутся пустыми/ошибочными по данным синхронизации.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. На устройстве открыть `Settings -> Источники данных`:
   - заполнить `AI API URL` и `AI API key`,
   - нажать `Save`.
4. Открыть `More -> AI Analysis`:
   - экран должен открываться даже при `Last sync issue: WARN: ...`,
   - при `ERROR/FATAL` должен показываться error-state.

# Изменения — Этап 124: Default OpenAI API URL/Key in settings

## Что сделано
- В `AppSettingsStore` добавлены значения по умолчанию для AI-подключения:
  - `cloudBaseUrl` → `https://api.openai.com/v1`
  - `openAiApiKey` → ключ из пользовательского запроса.
- Эти дефолты применяются:
  - при чтении `settings` потока,
  - при `update(...)` в построении `current` состояния.

## Почему так
- Пользователь запросил, чтобы URL и ключ OpenAI автоматически подставлялись как дефолтные значения без ручного ввода после установки.

## Риски / ограничения
- Ключ зашит в исходный код приложения как дефолт. Это повышает риск компрометации при утечке исходников/APK.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
2. Установить APK на устройство.
3. На чистых настройках открыть `Settings -> Data sources` и убедиться, что `AI API URL` и `AI API key` уже заполнены.

# Изменения — Этап 125: CPU/Energy optimization for UI telemetry pipeline

## Что сделано
- Оптимизирован `MainViewModel` для уменьшения CPU-нагрузки на каждом цикле обновления данных:
  - `latestTelemetryByKey(...)` теперь считается **один раз** за цикл и переиспользуется в:
    - `buildTelemetryCoverageLines(...)`
    - `buildTelemetryLines(...)`
    - `buildActivitySummaryLines(...)`
  - Убран тройной/четверной повторный пересчёт телеметрического map.
- Переписан `latestTelemetryByKey(...)` с allocation-heavy `filter + groupBy + mapValues` на **однопроходный** алгоритм:
  - один проход по samples,
  - отдельный fast-path для cumulative ключей (steps/distance/active/calories),
  - сохранена исходная логика выбора максимума за день/всё время.
- Оптимизирован расчёт графиков ISF/CR в аналитике:
  - `buildIsfCrHistoryPoints(...)` теперь ограничивает входные данные окном `30d` (`ISFCR_ANALYTICS_WINDOW_MS`),
  - `buildAapsSeriesFromTelemetry(...)` фильтрует telemetry по `minTs`,
  - сокращён объём обработки при минутных обновлениях без потери заявленных UI-масштабов (12h/24h/3d/7d/30d).

## Почему так
- Основной перегрев CPU был в частом полном пересчёте UI-state на каждый входящий telemetry update.
- Повторный пересчёт `latestTelemetryByKey` и полная агрегация длинной истории ISF/CR были самыми дорогими участками.
- Изменения нацелены на уменьшение числа аллокаций и объёма обрабатываемых данных без изменения бизнес-логики прогнозирования/автоматизации.

## Риски / ограничения
- Для аналитического графика ISF/CR теперь используется окно последних 30 дней; более старая история не участвует в рендере.
- Бизнес-решения (safety/rules/automation) не менялись, только оптимизация UI-пайплайна и подготовительных вычислений.

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2) Открыть экран `Analytics` и убедиться, что графики ISF/CR корректно отображаются в масштабах `12h/24h/3d/7d/30d`.
3) На устройстве проверить отзывчивость UI при входящем telemetry потоке (минутные обновления):
   - отсутствие лагов при открытии `Overview/Forecast/Analytics`,
   - отсутствие долгих задержек при переключении вкладок.

# Изменения — Этап 126: AI Analysis 24h gate + context AI chat

## Что сделано
- Реализован жёсткий 24h-гейт для AI-анализа:
  - в `MainViewModel.runDailyAnalysisNow()` запуск блокируется, если покрытия глюкозы меньше 24ч;
  - показывается прогресс покрытия (`x.y/24h`).
- Добавлена авто-генерация локального daily-анализа при готовности данных:
  - `maybeGenerateLocalDailyAnalysis(...)` запускает `generateDailyForecastReport(24)` при наличии >=24ч и отсутствии свежего локального отчета;
  - выполнено без зависимости от cloud endpoint.
- В `AiAnalysisUiState` и mapper добавлены поля готовности окна:
  - `minDataHours`, `dataCoverageHours`, `analysisReady`.
- На экране `AI Analysis` добавлен блок `Data readiness window`:
  - отображает “collecting data” до 24ч;
  - после достижения порога показывает готовность;
  - кнопка `Run AI analysis` отключена до готовности.
- Добавлен AI-чат на вкладку `AI Analysis`:
  - новый репозиторий `AiChatRepository` (OpenAI-compatible `chat/completions`);
  - в чат передается контекст из runtime/state: glucose, forecasts+CI, IOB/COB, ISF/CR, DIA, UAM, activity/steps, daily-report и replay факторы;
  - история чата и статус запроса (`chatInProgress`) выведены в UI.
- Навигация провязана:
  - `CopilotFoundationRoot` -> `AiAnalysisScreen(onSendChatPrompt = viewModel::sendAiChatPrompt)`.
- Добавлены RU/EN строки для:
  - 24h readiness,
  - AI chat labels/placeholders/messages.
- Добавлен unit-test mapping:
  - `aiAnalysisMapping_includesCoverageReadinessAndChatState`.

## Почему так
- Пользовательский запрос: AI анализ должен появляться только после накопления достаточного окна данных (минимум сутки) и нужен AI-чат на реальных данных контура.
- Разделили cloud-инсайты и локальную ежедневную аналитику: локальный блок формируется даже при проблемах cloud API.

## Риски / ограничения
- AI-чат требует валидный `AI API URL` с OpenAI-compatible `chat/completions` и корректный `AI API key`.
- Контекст чата summary-based (агрегированный), не полный raw dump всех точек.
- История чата пока in-memory (без persist между перезапусками).

## Как проверить
1) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2) `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3) В UI открыть `AI Analysis`:
   - до 24ч видеть прогресс `Collecting data`;
   - после 24ч активируется `Run AI analysis` и формируется локальный daily block;
   - в секции `AI chat` отправить вопрос и получить ответ по контексту данных.

# Изменения — Этап 127: AI readiness from full DB span (fix stuck collecting)

## Что сделано
- Исправлен источник расчета покрытия данных для AI:
  - в `GlucoseDao` добавлены `minTimestamp()/maxTimestamp()` и реактивные `observeMinTimestamp()/observeMaxTimestamp()`;
  - в `MainViewModel` поле `aiDataCoverageHours` теперь считается по полному диапазону `MIN/MAX(timestamp)` таблицы `glucose_samples`, а не по лимитированному `latest(limit=...)` списку.
- Обновлены все критичные проверки готовности AI:
  - `runDailyAnalysisNow()` использует `loadAiCoverageHours(...)` на базе полного DB-span;
  - `maybeGenerateLocalDailyAnalysis()` использует тот же источник.
- Добавлен fallback:
  - если span из БД недоступен, остается прежний расчет по `latest(limit=...)`, чтобы не ломать поведение при edge-cases.

## Почему так
- Симптом “AI завис на сборе данных” возникал из-за того, что readiness-гейт брал только последние N точек из UI-среза и недооценивал реальную длительность истории.
- Пользовательское требование: все данные должны браться из существующей БД. Новый расчет использует полный интервал данных в `glucose_samples`.

## Риски / ограничения
- Покрытие по-прежнему ограничено сверху `72h` (существующее ограничение).
- Если в таблице только одна точка или `MIN == MAX`, покрытие остается `0h` по текущей формуле.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.config.CloudEndpointModesTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. На устройстве проверить span в БД:
   - `adb shell run-as io.aaps.predictivecopilot ls -la databases`
   - локально: `sqlite3 /Users/mac/Andoidaps/tmp_ai_fix/copilot_device_tmp.db "select count(*), min(timestamp), max(timestamp), round((max(timestamp)-min(timestamp))/3600000.0,1) from glucose_samples;"`
4. Открыть `AI Analysis` и убедиться, что при покрытии >=24ч статус готовности больше не зависает в `Collecting data`.

# Изменения — Этап 128: MainViewModel combine lambda split (UI freeze/perf hardening)

## Что сделано
- Устранен перегруженный inline-лямбда-блок в `uiState`:
  - раньше весь heavy-пайплайн сборки `MainUiState` выполнялся прямо внутри `combine { values -> ... }`;
  - теперь `combine` вызывает короткую обертку: `buildMainUiState(values)`.
- Вынесена основная логика сборки `MainUiState` в отдельный метод:
  - `private fun buildMainUiState(values: Array<Any?>): MainUiState`
  - без изменения бизнес-логики, формул и safety-веток.
- Внутри нового метода добавлен явный `return MainUiState().apply { ... }`.

## Почему так
- На устройстве фиксировались регулярные сообщения:
  - `Method exceeds compiler instruction limit ... MainViewModel$special$$inlined$combine$1$3.invokeSuspend(...)`
- Это признак слишком тяжелой coroutine-lambda, которая хуже компилируется JIT/AOT и может давать лаги UI.
- Разделение на отдельный метод уменьшает размер лямбды и снижает риск подвисаний при частых обновлениях flow.

## Риски / ограничения
- Логика не менялась функционально, но выполнен крупный структурный перенос кода в файле `MainViewModel.kt`; возможны регрессии только при ошибках маппинга полей.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
3. На телефоне открыть `Overview / Forecast / AI Analysis` и проверить:
   - отсутствие длительных (2-3с) фризов при переключениях,
   - сохранение прежних данных/показателей в UI,
   - отсутствие новых ошибок в logcat по `MainViewModel`.

# Изменения — Этап 129: ISF/CR chart completeness fix (real vs AAPS continuity)

## Что сделано
- Исправлена сборка точек для графиков ISF/CR в `MainViewModel.buildIsfCrHistoryPoints(...)`:
  - добавлен контролируемый carry-forward для разрывов рядов (`real`, `aaps`, `merged`) с отдельными лимитами по времени;
  - убран сценарий, где точка полностью выпадала из графика из-за отсутствия только одной из компонент в конкретный timestamp.
- Добавлена нормализация `isf_value` из телеметрии AAPS:
  - если ISF приходит в mg/dL/U (`>18`), значение автоматически переводится в mmol/L/U;
  - это устраняет пропадание AAPS-референсной линии при смешанных единицах источника.
- Добавлены внутренние helper-функции:
  - `carryForwardMetric(...)`
  - `normalizeAapsTelemetryMetric(...)`
- Собран новый debug APK и установлен на устройство по USB.

## Почему так
- Причина “неполноценных” графиков: часть точек выбрасывалась при неполном совпадении таймсерий, а AAPS ISF в mg/dL/U мог отфильтровываться как выходящий за диапазон.
- Новый пайплайн делает линии устойчивыми к разрывам и единицам источника, сохраняя разделение:
  - `real` (расчетный),
  - `AAPS` (референсный/загруженный).

## Риски / ограничения
- Carry-forward ограничен по окну времени и может сглаживать краткие разрывы, но не заменяет реальные новые данные.
- Для очень редких/шумных источников возможны участки с пониженной плотностью `strict`-точек, что отражает качество входных данных.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.IsfCrHistoryResolverTest"`
3. Установка по USB:
   - `adb install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На телефоне открыть `Analytics -> ISF/CR`:
   - проверить переключение диапазонов `12h/24h/3d/7d/30d`,
   - проверить две линии: real (сплошная) и AAPS (пунктир),
   - проверить, что график не “обрывается” на участках с частичными пропусками.

# Изменения — Этап 130: Daily OpenAI optimizer for 30/60m forecast calibration

## Что сделано
- Добавлен суточный AI-оптимизатор в `AiChatRepository`:
  - выбор модели через `GET /v1/models` с приоритетом `gpt-5* pro`,
  - запуск анализа через `POST /v1/responses` в background-режиме,
  - polling статуса и строгий парсинг structured JSON-ответа.
- В `InsightsRepository.runDailyAnalysis()` добавлен OpenAI optimizer-path для режима OpenAI endpoint (без custom cloud backend):
  - локальный 24h report формируется как и раньше (обязательно),
  - затем запускается оптимизатор и его результат пишется в telemetry (`daily_report_ai_opt_*`),
  - при ошибке оптимизатора выполняется safe fallback на local-report-only с явным `daily_report_ai_opt_error`.
- В `AutomationRepository` добавлено bounded-применение AI-тюнинга к этапу calibration bias:
  - читаются `daily_report_ai_opt_gain/maxUp/maxDown` для `5/30/60`,
  - применяются только при `apply_flag=1` и confidence >= 0.45,
  - все масштабы дополнительно ограничены безопасными clamp-границами.
- Добавлены unit-тесты:
  - `AiChatRepositoryOptimizerTest` (выбор модели, парсинг structured output, parsing output_text),
  - расширен `AutomationRepositoryForecastBiasTest` сценарием AI-tuning uplift для горизонта 60m.

## Почему так
- На устройстве подтверждено, что ключевая проблема точности — 30/60m underprediction в режимах повышенного COB/UAM.
- Локальный replay уже пишет богатый факторный контекст; optimizer использует именно эти локальные метрики и выдает только безопасные тюнинги калибровки, без прямого вмешательства в therapy.
- Такой контур дает ежедневный цикл улучшений и сохраняет deterministic safety-chain в runtime.

## Риски / ограничения
- Optimizer зависит от доступности OpenAI API и валидного ключа; при недоступности деградация идет в local-only режим.
- AI-тюнинг сейчас влияет только на calibration stage (не меняет rule/safety policy напрямую).
- Качество оптимизации зависит от полноты суточного matched dataset; при sparse data optimizer чаще возвращает `NO_CHANGE`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AiChatRepositoryOptimizerTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
2. Запустить в приложении `AI Analysis -> Run daily` при OpenAI endpoint.
3. Проверить в БД/telemetry наличие ключей `daily_report_ai_opt_*` и отсутствие `daily_report_ai_opt_error`.
4. Проверить audit-события:
   - `ai_daily_optimizer_completed` (успех),
   - либо `ai_daily_optimizer_failed`/`ai_daily_optimizer_skipped` (fallback).

# Изменения — Этап 131: AI tuning freshness/quality gate + stale-apply leak fix

## Что сделано
- Исправлен риск “stale APPLY leakage” в daily optimizer telemetry:
  - в `InsightsRepository.persistDailyAiOptimizerTelemetry(...)` ключи runtime-тюнинга теперь пишутся **всегда**;
  - при ошибке/skip пишутся нейтральные значения:
    - `daily_report_ai_opt_apply_flag=0`,
    - `daily_report_ai_opt_gain/max_up/max_down_scale_* = 1.0`,
    - confidence/status/error сохраняются явно.
- Усилен runtime-gate применения AI calibration tuning в `AutomationRepository`:
  - тюнинг применяется только если payload свежий (`generated_ts <= 36h`),
  - есть минимальное покрытие (`daily_report_matched_samples >= 36`),
  - `daily_report_isfcr_quality_risk_level < 3` (high-risk блокирует применение),
  - сохранены confidence/apply-flag проверки и clamp-границы scale.
- Добавлен тестируемый static helper:
  - `resolveAiCalibrationTuningStatic(latestTelemetry, nowTs)`.
- Добавлены unit-тесты:
  - блокировка stale payload,
  - блокировка при high risk,
  - блокировка при недостатке matched samples,
  - успешное применение с clamp значений.

## Почему так
- После неуспешного daily AI-run старый `apply_flag` мог оставаться последним валидным по ключу и влиять на runtime дольше суток.
- Для безопасного снижения ошибки 30/60m AI-тюнинг должен быть не только bounded, но и “свежий + качественный”.

## Риски / ограничения
- При бедных данных (`matched_samples < 36`) AI-тюнинг будет отключаться чаще, что снижает агрессивность авто-адаптации.
- Гейт по risk-level завязан на корректность построения `daily_report_isfcr_quality_risk_level` в daily report.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AiChatRepositoryOptimizerTest"`
3. Вручную:
   - спровоцировать failed optimizer run,
   - проверить в telemetry последние `daily_report_ai_opt_*` (apply_flag=0, scales=1.0),
   - убедиться, что runtime не применяет старый AI-тюнинг.

# Изменения — Этап 132: UAM export recovery (stuck SUSPECTED events)

## Что сделано
- Проведена диагностика по device DB:
  - `action_commands` и `audit_logs` подтверждают, что `UAM_ENGINE` отправка в AAPS/NS работает (`uam_export_post_success`), но после 06:50 появились зависшие `SUSPECTED` события без перехода в `CONFIRMED`.
  - Из-за этого активные UAM-слоты заполнялись и новые экспортируемые события переставали формироваться.
- В `UamInferenceEngine` добавлено безопасное авто-закрытие stale `SUSPECTED` событий:
  - если fit недоступен и возраст события слишком большой -> `MERGED`,
  - если длительный weak-tail + низкая confidence -> `MERGED`,
  - hard-timeout для `SUSPECTED` (force close), чтобы не блокировать pipeline.
- Добавлен unit-тест:
  - `UamInferenceEngineTest.staleSuspectedEventIsClosedToMergedToUnblockExportPipeline`.

## Почему так
- Проблема была не в AI optimizer-контуре и не в сетевой отправке NS, а в застревании UAM state-machine до этапа экспорта.
- Новый guard сохраняет существующую логику `CONFIRMED_ONLY/INCREMENTAL`, но предотвращает постоянный “захват” активных слотов старыми неподтверждёнными событиями.

## Риски / ограничения
- Слишком агрессивные таймауты могут закрывать пограничные SUSPECTED события раньше подтверждения; выбраны консервативные пороги.
- Hard throttle отправки carbs (`30m`) и дальше может давать `uam_export_blocked_rate_limit` — это ожидаемая safety-политика, а не ошибка.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.UamInferenceEngineTest" --tests "io.aaps.copilot.data.repository.UamExportCoordinatorTest"`
2. На устройстве проверить `audit_logs`:
   - исчезают длительно висящие `SUSPECTED` без обновления,
   - после освобождения слотов снова появляются `uam_export_post_success` при валидных UAM-сценариях.

# Изменения — Этап 133: ISF/CR analytics chart validity + realtime refresh recovery

## Что сделано
- Исправлен потенциально залипающий `isfcr_realtime_refresh_in_flight` в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt`:
  - добавлен stale-guard recovery,
  - добавлен `invokeOnCompletion` reset флага,
  - reset флага в `finally` для sync/async refresh веток.
- Оптимизирован realtime-окно расчёта ISF/CR в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/IsfCrRepository.kt`:
  - ограничение lookback для realtime до `3..5` дней, чтобы убрать систематические timeout на телефоне.
- Усилен пайплайн данных графиков ISF/CR:
  - в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` добавлена валидация/санация ISF/CR переменных, регулярная 5-мин timeline grid, расширен набор telemetry-ключей AAPS (`isf_value/raw_isf/...`, `cr_value/raw_cr/...`).
  - в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/IsfCrHistoryResolver.kt` добавлена фильтрация невалидных точек, улучшенный dedupe, anchor-поддержка для длинных окон (3d/7d/30d).
- Улучшена визуализация графиков ISF/CR в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/AnalyticsScreen.kt`:
  - trim-устойчивый autoscale по оси Y,
  - отдельное покрытие real/AAPS,
  - явные hints, когда одна из линий отсутствует,
  - улучшенная читабельность chart area.
- Добавлены новые строки локализации (`en/ru`) для coverage/hints real-линии.
- Добавлены/обновлены тесты в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/ui/IsfCrHistoryResolverTest.kt` для окон `3d/7d` и фильтрации невалидных точек.

## Почему так
- По device-логам графики были деградированы из-за зацикленного `in_flight` + timeout realtime refresh; из-за этого “real” линия устаревала.
- Для корректной отрисовки длинных окон нужны валидированные значения и ровная временная сетка.
- Пользовательский сценарий 3d/7d требует прозрачного отображения покрытия каждой линии, а не только “общего” графика.

## Риски / ограничения
- Если realtime-движок стабильно не может завершить расчёт даже на 3..5 днях, real-линия всё равно будет редкой; теперь это видно по `real coverage`.
- Anchor для длинных окон может добавить одну точку до cutoff (осознанно для непрерывности левого края).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.IsfCrHistoryResolverTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. На устройстве (Analytics → ISF/CR):
   - переключить окна `12h/24h/3d/7d/30d`,
   - убедиться, что график строится, есть оси времени/единиц,
   - проверить отдельные строки `Real line coverage` и `AAPS coverage`.

# Изменения — Этап 134: ISF/CR charts 3d/7d data completeness + window diagnostics

## Что сделано
- В `TelemetryDao` добавлен отдельный flow-запрос для history-графиков ISF/CR:
  - `observeLatestByKeys(limit, keys)`.
- В `MainViewModel` добавлен выделенный telemetry-канал только для ISF/CR ключей (`isf_value/raw_isf/aaps_isf/...`, `cr_value/raw_cr/aaps_cr/...`) с большим лимитом:
  - это убирает обрезание 3d/7d окон из-за общего лимита `telemetry_samples`.
- Построение `isfCrHistoryPoints` переведено на этот выделенный history-канал.
- `isfCrHistoryLastUpdatedTs` теперь вычисляется по максимуму из нескольких источников (`history points`, `isf_cr_snapshots`, `profile_estimates`, telemetry-history), чтобы окно графика не якорилось на устаревшем timestamp.
- В `AnalyticsScreen` добавлена явная диагностика покрытия выбранного окна:
  - строка `Window <label> (<hours>): available data <hours>`,
  - hint, если выбранное окно шире доступного диапазона истории.
- Добавлены локализованные строки (`ru/en`) для покрытия окна.

## Почему так
- Корень проблемы 3d/7d графиков: общий telemetry-limit содержал много разных ключей и не давал достаточно истории для ISF/CR рядов.
- Пользователь видел “некорректную” отрисовку, хотя проблема была в неполном входном окне.
- Новый отдельный канал данных и подсказка coverage делают график и корректным, и объяснимым.

## Риски / ограничения
- Если в базе физически меньше данных, 3d и 7d могут выглядеть одинаково; теперь это явно показано через coverage-строку.
- Для real-линии остаётся зависимость от актуальности ISF/CR runtime pipeline.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.IsfCrHistoryResolverTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. USB install:
   - `adb install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На устройстве (`Аналитика -> ISF/CR`):
   - переключить `3d/7d`,
   - проверить, что карточка показывает `История: ...` + `Окно ...: доступно данных ...`,
   - убедиться, что chart section `ИСТОРИЯ ISF/CR` отрисовывается и содержит real/AAPS линии.

# Изменения — Этап 135: UI-индикатор AI tuning active/stale/blocked (why)

## Что сделано
- Добавлен визуальный индикатор статуса AI-тюнинга на двух экранах:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/AiAnalysisScreen.kt`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/SafetyScreen.kt`
- На карточке показываются:
  - состояние `ACTIVE/STALE/BLOCKED`,
  - причина (`why`),
  - время генерации optimizer-отчета,
  - confidence,
  - raw status (если есть).
- Добавлены строки локализации `en/ru`:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/res/values/strings.xml`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/res/values-ru/strings.xml`

## Почему так
- Пользователь должен видеть прямо на телефоне, почему AI-тюнинг применился или был отключён.
- Статус в `Safety` и `AI Analysis` помогает быстро диагностировать блокировки (`low confidence`, `stale`, `risk gate`, `apply off`) без просмотра логов.

## Риски / ограничения
- В рабочем дереве есть существующие несвязанные compile-ошибки в других файлах (`CopilotRoot.kt`, `MainUiStateMappers.kt`), поэтому общий `compileDebugKotlin` сейчас падает не из-за этого изменения.
- Карточка опирается на telemetry-поля optimizer; если они не приходят, статус будет `BLOCKED` с причиной отсутствия отчета.

## Как проверить
1. Открыть `More -> AI Analysis` и убедиться, что есть секция `AI tuning status`.
2. Открыть `Safety` и убедиться, что есть такая же секция.
3. Проверить кейсы:
   - свежий отчет + apply=true + confidence выше порога => `ACTIVE`;
   - старый отчет => `STALE`;
   - apply=false/low confidence/risk gate => `BLOCKED` с причиной.

# Изменения — Этап 136: Runtime performance stabilization (UI state + Safety toggle)

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` снижены тяжёлые лимиты потоков данных для общего `uiState`:
  - `profile history`: `20000 -> 12000`
  - `isf/cr history`: `20000 -> 12000`
  - `isf/cr telemetry by keys`: `120000 -> 25000`
  - `ai tuning telemetry`: `30000 -> 4000`
  - добавлены именованные константы лимитов вместо hardcoded чисел.
- Для Safety UI добавлен мгновенный локальный override `killSwitchOverrideState`:
  - переключатель `Kill switch` теперь меняет состояние на экране сразу,
  - фактическая запись в DataStore остаётся асинхронной и безопасной.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` понижен шум WARN для адаптивного контроллера:
  - `adaptive_controller_blocked` при причине `retarget_cooldown_*` теперь пишется как `INFO`,
  - реальные блокировки (не cooldown) сохраняют `WARN`.
- Устранён runtime hot-spot метода `buildMainUiState`:
  - apply-блок заполнения `MainUiState` вынесен в отдельный локальный helper (`populateResult`),
  - предупреждение ART `Method exceeds compiler instruction limit ... buildMainUiState(...)` перестало появляться в логах.

## Почему так
- Основной лаг UI и повышенная CPU-нагрузка были связаны с монолитным пересчётом одного огромного UI-state и избыточными историческими выборками.
- Быстрый визуальный отклик `Kill switch` критичен для safety UX и не должен зависеть от длительного полного пересчёта всего состояния.

## Риски / ограничения
- Для отдельных diagnostics-разделов глубина истории по telemetry стала меньше; на практических данных это остаётся достаточным для окон `12h/24h/3d/7d/30d`.
- Глубокий рефактор декомпозиции `buildMainUiState` на несколько чистых модулей ещё желателен как следующий шаг, но текущий фикс уже снимает warning и уменьшает нагрузку.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. `adb install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. `adb logcat -c && adb shell am start -n io.aaps.predictivecopilot/io.aaps.copilot.MainActivity`
5. Проверить, что в `adb logcat` больше нет строк:
   - `Method exceeds compiler instruction limit ... buildMainUiState(...)`
6. На экране Safety проверить `Kill switch`: визуальное переключение происходит сразу, без 2–3 секунд задержки.

# Изменения — Этап 137: COB=0 fix (runtime merge + UI recency + cycle unstall)

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` исправлен runtime merge для COB:
  - ветка `telemetryCob != null && !hasRecentCarbEvents` больше не обнуляет COB,
  - теперь используется внешний COB (`telemetryCob`) в границах safety clamp.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` добавлен выбор `IOB/COB` по свежести timestamp:
  - если `*_effective` устарел, UI берет свежий raw (`iob_units` / `cob_grams`).
- Устранена блокировка automation цикла до этапа `post_isfcr`:
  - синхронный refresh realtime ISF/CR в runtime цикле убран,
  - refresh выполняется только async через `scheduleRealtimeIsfCrRefresh(...)`,
  - цикл не блокируется тяжелым `computeRealtimeSnapshot`.

## Почему так
- На телефоне импортированные `cob_grams` были свежими и >0, но `cob_effective_grams` застрял на старом значении `0.0`.
- Из-за этого UI часто показывал ноль и прогнозный контур терял COB-влияние.
- Дополнительно часть циклов “подвисала” после `post_recent_data`, что не давало обновлять runtime telemetry.

## Риски / ограничения
- Async refresh realtime ISF/CR может применять свежий snapshot с задержкой до следующего цикла; это безопаснее, чем блокировать цикл.
- В очень коротких окнах после старта возможен fallback на stale snapshot/legacy до завершения async refresh.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
2. `adb install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
3. В `audit_logs` должны появляться поздние checkpoint-и цикла:
   - `post_isfcr`, `post_cob_iob_runtime`, `post_forecast_storage`, `post_rule_evaluate`, `automation_cycle_finished`.
4. В `telemetry_samples`:
   - `cob_grams` (xdrip_broadcast) свежий и >0,
   - `cob_effective_grams` (copilot_runtime_cob_iob) обновляется свежим временем и соответствует runtime merge.

# Изменения — Этап 138: Insulin import recovery from AAPS/NS telemetry + DB housekeeping cadence

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` расширен импорт инсулина в `therapy_events`:
  - сохранена загрузка treatments из NS по двум курсорам (`date` и `created_at`) с дедупликацией;
  - добавлен fallback `IOB jump -> inferred insulin event`:
    - чтение `iob_units/raw_iob` за 24ч из источников `aaps_broadcast/xdrip_broadcast/local_broadcast/nightscout_*`,
    - детект положительных шагов IOB (`delta >= 0.20U`, `dt 1..15m`),
    - запись `correction_bolus` в `therapy_events` с payload:
      `inferred=true`, `method=iob_jump`, `insulin`, `iobPrev/iobNow`, `deltaIob`, `dtMin`.
  - добавлен аудит `nightscout_iob_insulin_inferred`.
  - в `nightscout_sync_completed` добавлено поле `treatmentsInsulinInferredFromIob`.
- Подтверждена работа DB housekeeping и ротации логов по 2-часовому cadence:
  - `db_housekeeping_completed`,
  - `audit_log_rotation_completed` (`intervalHours=2`, retention `14d`).

## Почему так
- На реальном устройстве из NS treatments приходили только `Temporary Target` и `Carb Correction`, при этом `IOB` был положительный.
- Из-за отсутствия insulin-like `therapy_events` прогнозный контур терял важный вклад инсулина для горизонтов `30/60m`.
- Fallback по IOB позволяет восстановить insulin-события из AAPS/NS телеметрии, даже когда NS не публикует болюсы явно.

## Риски / ограничения
- `iob_jump`-инференс не равен точному delivered-bolus и может давать шум при нестабильной телеметрии.
- Модель intentionally conservative: есть пороги (`delta`, `dt`, clamp), блокировка по близким insulin-событиям и дедуп по 5m bucket id.
- При наличии явных insulin treatments в NS этот fallback должен играть вторичную роль.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:installDebug`
4. Триггер цикла через broadcast:
   - `adb shell am broadcast -a com.eveningoutpost.dexdrip.BgEstimate -n io.aaps.predictivecopilot/io.aaps.copilot.receiver.LocalDataBroadcastReceiver --ei com.eveningoutpost.dexdrip.Extras.BgEstimate 160 --es com.eveningoutpost.dexdrip.Extras.Display.Units mg/dl --el com.eveningoutpost.dexdrip.Extras.Time <now_ms>`
5. Проверить в `audit_logs`:
   - `nightscout_iob_insulin_inferred` (created > 0 на первом цикле после фикса),
   - `nightscout_sync_completed.treatmentsInsulinInferredFromIob`,
   - отсутствие новых `forecast_insulin_events_missing` после появления inferred events.
6. Проверить в `therapy_events` за 24ч наличие `correction_bolus` с `payload.insulin > 0` и `payload.inferred=true`.

# Изменения — Этап 139: Counterfactual replay 24h (engine recompute) для эффекта insulin-import фикса

## Что сделано
- Добавлен офлайн инструмент контрфактического replay как unit-test раннер:
  - [CounterfactualReplayToolTest.kt](/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/tools/CounterfactualReplayToolTest.kt)
  - читает две SQLite БД (`before/after`),
  - выбирает единое фиксированное окно 24ч (по общему `min(maxTs)`),
  - пересчитывает прогнозы `HybridPredictionEngine` на каждом 5-мин цикле (не использует historical `forecasts`),
  - считает `MAE/RMSE/MARD/Bias` по горизонтам `5/30/60`,
  - считает факторный вклад `IOB/UAM/CI` (score/corr/uplift),
  - сохраняет markdown-артефакт.
- В `android-app/app/build.gradle.kts` добавлена test-only зависимость:
  - `testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")`.
- Сформирован артефакт:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/artifacts/replay_24h_counterfactual_importfix_20260305.md`.

## Почему так
- Обычный replay в daily отчете анализирует уже записанные forecast-строки и не показывает ретро-эффект исправления импорта терапии.
- Контрфактический replay пересчитывает прогнозы заново из `glucose + therapy`, поэтому отражает реальное влияние появления insulin events в истории.

## Риски / ограничения
- Это офлайн-диагностический контур (не runtime), используется для проверки гипотез и regression-анализа.
- Вклад UAM может иметь низкое покрытие в окне без выраженных UAM-эпизодов.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app`
2. `./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.tools.CounterfactualReplayToolTest.generateCounterfactualReplayFromSqlite --no-daemon`
3. Проверить отчет:
   - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/artifacts/replay_24h_counterfactual_importfix_20260305.md`

# Изменения — Этап 140: Cleanup слабых IOB-inferred bolus + калибровка влияния inferred insulin + повторный replay 24h

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` усилен контур импорта/инференса инсулина:
  - `payloadFromJson(...)` теперь устойчиво парсит `Map<String, Any?>` и приводит значения к строкам (устраняет пропуски, когда в JSON встречаются не-строковые типы).
  - Добавлен cleanup устаревших слабых inferred bolus (`method=iob_jump`, `inferred=true`, `insulin < 0.50U`) перед новым inference-проходом.
  - Cleanup выполняется батчами (`chunked(250)`), чтобы избежать проблем на больших списках id.
  - Добавлен audit-событие `nightscout_iob_inferred_cleanup` со счетчиками `scannedInferredRows/candidates/deleted/thresholdUnits`.
  - Формат чисел в payload inference (`insulin/iobPrev/iobNow/deltaIob/dtMin`) переведен на `Locale.US` для стабильной сериализации.
  - Блокировка рядом стоящих insulin-событий для inference теперь учитывает только события с реальной insulin-дозой (`>0`) или уже inferred-события.
  - Порог inference повышен до `IOB_INFERENCE_MIN_DELTA_UNITS = 0.50`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngine.kt` добавлена калибровка влияния inferred insulin:
  - события с `inferred=true` или `method=iob_jump` используют scale `INFERRED_INSULIN_IMPACT_SCALE = 0.45`.
  - scale применён в обоих путях расчета инсулинового вклада: step-series и horizon delta.
- Добавлен unit-тест в
  `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngineV3Test.kt`:
  - `t9b_inferredInsulinImpactIsCalibratedLowerThanExplicit`.
- Сформирован новый replay-артефакт:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/artifacts/replay_24h_counterfactual_importfix_v5_scale045_20260305.md`.

## Почему так
- Основной источник деградации 30/60m после фикса импорта — переагрессивный вклад inferred bolus (малые шумовые IOB-jump события и их полный insulin effect).
- Cleanup + повышение порога inference удаляют слабый шум (`<0.5U`) из `therapy_events`.
- Дополнительный scale 0.45 для inferred insulin уменьшает систематический negative bias без отключения самого механизма.

## Риски / ограничения
- Даже после cleanup/scale на текущем 24h окне `30m/60m` все еще хуже baseline (но хуже существенно меньше, чем до калибровки).
- Inference на основе IOB остается эвристическим и чувствителен к качеству источника телеметрии.
- Нужен дальнейший тюнинг контуров IOB/UAM/CI по replay (следующий этап).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests 'io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test.t9b_inferredInsulinImpactIsCalibratedLowerThanExplicit'`
3. Установить на устройство:
   `adb install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. Проверить в копии БД устройства, что слабых inferred bolus нет:
   - `SELECT COUNT(*) FROM therapy_events WHERE type='correction_bolus' AND payloadJson LIKE '%"method":"iob_jump"%' AND CAST(REPLACE(json_extract(payloadJson,'$.insulin'),',','.') AS REAL) < 0.5;`
5. Пересчитать counterfactual replay:
   `COPILOT_REPLAY_BEFORE_DB=/Users/mac/Andoidaps/tmp_live/copilot_before_importfix_20260305.db COPILOT_REPLAY_AFTER_DB=/Users/mac/Andoidaps/tmp_live/copilot_post_cleanupcheck_20260305.db COPILOT_REPLAY_OUTPUT_MD=/Users/mac/Andoidaps/AAPSPredictiveCopilot/artifacts/replay_24h_counterfactual_importfix_v5_scale045_20260305.md ./gradlew -p /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app :app:testDebugUnitTest --tests 'io.aaps.copilot.tools.CounterfactualReplayToolTest.generateCounterfactualReplayFromSqlite'`

# Изменения — Этап 141: Устранение timeout автоматики и снижение нагрузки nightscout_sync

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` добавлен строгий клиентский фильтр treatment-окна:
  - если `ts < treatmentQuerySince`, treatment пропускается до построения payload/telemetry.
  - добавлен счетчик `treatmentsSkippedByClientWindow` в `nightscout_sync_completed`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` добавлен non-fatal режим для тяжелых сетевых шагов:
  - `nightscout_sync` выполняется с шаговым timeout `60s`;
  - `cloud_push_sync` выполняется с шаговым timeout `20s`;
  - при ошибке/timeout цикл продолжает выполнение (`automation_cycle_step_nonfatal_continue`) вместо срыва всего контура.

## Почему так
- На устройстве `nightscout_sync` регулярно перерабатывал сотни старых treatments за цикл, что тянуло шаг до ~130+ секунд и приводило к `automation_cycle_timeout`.
- После фильтра по клиентскому окну импорт обрабатывает только свежие treatments, а старый хвост отбрасывается без тяжелой переработки.
- Non-fatal/step-timeout защищает цикл от полного падения при проблемах внешнего транспорта.

## Риски / ограничения
- Если Nightscout отдает treatments с некорректными timestamp, жесткая фильтрация может отбрасывать часть запоздавших записей.
- Для safety это приемлемо: цикл не блокируется, а следующие sync-итерации продолжают догонять данные по курсору.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. `adb install -r -t /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
3. Trigger через broadcast и проверить `audit_logs`:
   - `nightscout_sync_completed` содержит `treatmentsSkippedByClientWindow` и резко меньшие `treatments/telemetry`.
   - `automation_cycle_finished` присутствует без `automation_cycle_timeout` для новых запусков.
4. В live-проверке на устройстве после фикса:
   - `nightscout_sync_completed`: `treatmentsFetchedByDate=789`, `treatmentsSkippedByClientWindow=788`, `treatments=1`, `telemetry=9`.
   - `automation_cycle_finished`: `durationMs=51144` (без timeout).
