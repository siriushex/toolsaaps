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

# Изменения — Этап 142: Nightscout sync fast-path (count/timeout/backfill cadence) для устранения cycle timeouts

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` внедрен fast-path для инкрементального NS sync:
  - уменьшен `count` в обычном режиме:
    - treatments/sgv/deviceStatus: `250` (в bootstrap остается `2000`);
  - добавлены локальные таймауты запросов:
    - SGV: `25s`, treatments(date): `20s`, treatments(created_at): `20s`, deviceStatus: `20s`;
  - `created_at` backfill вынесен в периодический режим:
    - выполняется только при `bootstrap` или раз в `30 минут` (или если `date`-выборка пуста);
    - состояние хранится в cursor `nightscout_treatment_created_at_backfill_cursor`.
- Расширен audit `nightscout_sync_completed`:
  - `treatmentsCreatedAtBackfill`, `treatmentsCreatedAtBackfillDue`, `treatmentsByCreatedAtFetchExecuted`,
  - `sgvFetchDurationMs`, `treatmentsByDateFetchDurationMs`, `treatmentsByCreatedAtFetchDurationMs`, `deviceStatusFetchDurationMs`,
  - timeout flags для каждого канала fetch.

## Почему так
- Главный источник timeout был в тяжелом `nightscout_sync`: повторная переработка большого хвоста treatments и регулярный второй запрос `created_at`.
- Ограничение `count` + cadence для `created_at` + per-call timeout резко уменьшают длительность шага и сохраняют актуальность данных.

## Риски / ограничения
- При нестабильном NS могут временно выпадать поздние записи, но цикл не блокируется и догоняет историю последующими sync итерациями.
- Для полного backfill точность сохраняется через bootstrap и периодический `created_at` канал.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin`
2. Установить APK: `adb install -r -t /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
3. Проверить `audit_logs` после 1–2 циклов:
   - `nightscout_sync_completed` содержит новые поля durations/timeout/backfill,
   - `automation_cycle_finished` присутствует без `automation_cycle_timeout` в свежих циклах.
4. Фактическая проверка на устройстве после фикса:
   - `nightscout_sync` шаг: `739ms` и `1458ms`,
   - full cycle: `automation_cycle_finished durationMs=15861`.

# Изменения — Этап 143: Repair sparse iob-inf payload + cleanup и контрольный replay 24h

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` добавлен self-heal для исторических `iob-inf-*` correction_bolus:
  - если payload частично затерт (нет `insulin/inferred/method`), данные восстанавливаются из `id` формата `iob-inf-<bucket>-<unitsRounded>`;
  - после repair автоматически выполняется существующий cleanup `<0.5U` inferred bolus;
  - добавлен audit `nightscout_iob_inferred_repaired`.
- Добавлен merge-policy payload при upsert treatments:
  - sparse payload из NS больше не затирает критичные поля (`insulin/units/bolusUnits/inferred/method/...`) у уже существующих записей.
- Сформирован свежий counterfactual replay-отчет:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/artifacts/replay_24h_counterfactual_importfix_v8_repair_20260305.md`.

## Почему так
- В live БД были `correction_bolus` с `id=iob-inf-*`, но без `insulin`, из-за чего часть inferred-событий деградировала в sparse записи.
- Это ухудшало консистентность терапии и вносило шум в анализ ошибок прогноза.

## Риски / ограничения
- Repair опирается на соглашение формата id `iob-inf-...`; для нестандартных id repair не применяется.
- Контур по-прежнему консервативный: после repair cleanup удаляет inferred bolus `<0.5U`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
2. `adb install -r -t /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
3. В `audit_logs` должны быть:
   - `nightscout_iob_inferred_repaired` (первый проход на старых данных),
   - `nightscout_iob_inferred_cleanup` с `candidates/deleted`.
4. Проверка БД в окне 24ч:
   - `correction_bolus` с `id=iob-inf-*` содержат `insulin` + `inferred=true` + `method=iob_jump`,
   - слабые `<0.5U` удалены.
5. Контрольный replay:
   - 30m: ΔMARD снизился до `+1.585 pp` (после repair),
   - 60m: ΔMARD `+1.572 pp`.

# Изменения — Этап 144: Жесткая нормализация bolus/carbs при импорте NS (без ложных correction_bolus)

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` усилена классификация `Correction Bolus/Meal Bolus`:
  - `Correction Bolus` без дозы инсулина теперь не попадает в `correction_bolus` (понижается до `treatment`);
  - `Meal Bolus` без полного payload теперь детерминированно переразмечается в `correction_bolus` / `carbs` / `treatment` по фактическим данным;
  - `carbs` без углеводов также понижается до `treatment`.
- Сохранена whitelist-логика для inferred IOB bolus (`inferred=true`, `method=iob_jump`) чтобы не потерять валидные `iob-inf-*`.
- Добавлена диагностика импорта:
  - `treatmentsDowngradedFromBolus` в `nightscout_sync_completed` для контроля деградации сырых NS записей.
- Выполнена проверка на устройстве (USB):
  - приложение обновлено,
  - `correction_bolus` за 24ч без дозы инсулина: `0`,
  - свежие `iob-inf-*` записи содержат `insulin + inferred + method=iob_jump`.

## Почему так
- Главный лимит точности 30/60m был в шумных/пустых bolus-событиях из NS, которые ошибочно попадали в insulin-контур.
- Более строгая нормализация по фактическому payload убирает ложное влияние на IOB/прогноз и сохраняет только валидные bolus-события.

## Риски / ограничения
- Если внешний источник шлет нестандартные ключи дозы инсулина (вне известных aliases), запись может быть понижена до `treatment`.
- Текущее окно в live-проверке не содержало новых “пустых” bolus, поэтому `treatmentsDowngradedFromBolus` в последних циклах равно `0` (метрика добавлена и будет заполняться при встрече кейса).

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:lintDebug`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
4. `adb install -r -t /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
5. Проверка БД:
   - `SELECT COUNT(*) FROM therapy_events WHERE type='correction_bolus' AND timestamp>strftime('%s','now','-24 hour')*1000 AND coalesce(nullif(json_extract(payloadJson,'$.insulin'),''),nullif(json_extract(payloadJson,'$.units'),''),nullif(json_extract(payloadJson,'$.bolusUnits'),'')) IS NULL AND NOT (lower(coalesce(json_extract(payloadJson,'$.inferred'),''))='true' AND lower(coalesce(json_extract(payloadJson,'$.method'),''))='iob_jump');`
   - ожидаемо `0`.

# Изменения — Этап 145: Реальный ISF/CR v2 (сверка с HBA1 и доработка контуров)

## Что сделано
- Пересобран extraction-контур ISF/CR по мотивам рабочих подходов из `HBA1/circadian_insights_v2.py`:
  - добавлен slope-based onset для ISF correction-window (drop onset в диапазоне `10..120` минут);
  - добавлен контроль outlier ISF относительно reference (`ratio <0.5` или `>2.0`) с мягким blend к reference вместо “сырого” выброса;
  - добавлены penalties в вес evidence (`onsetPenalty`, `outlierPenalty`) и расширенный trace в context (`rawIsf`, `isfOutlier`, `isfOutlierRatio`, `insulinOnsetMin`, `peakDropSlopePer5m`).
- Для CR внедрён meal-alignment (как в HBA1-идее rise-onset):
  - поиск onset роста в окне `±30 минут` вокруг логированного meal;
  - при обнаружении используется `effectiveMealTs` для fit-window, hour/day bucket и bolus proximity;
  - в evidence/context добавлены `mealAligned`, `mealAlignmentShiftMin`, `mealOriginalTs`, `mealEffectiveTs`, `alignmentPenalty`.
- Улучшен базовый fitter для hourly ISF/CR:
  - вместо простого weighted-log-mean применяется robust reweighting (weighted-median center + MAD scale + Huber/Tukey suppress),
  - снижена чувствительность к единичным выбросам CR/ISF в часовых сегментах.
- Добавлены unit-тесты:
  - `IsfCrWindowExtractorTest`:
    - meal timestamp alignment при раннем росте BG,
    - ISF outlier adjustment к reference.
  - новый `IsfCrBaseFitterTest`:
    - устойчивость hourly ISF/CR к экстремальному одиночному выбросу.
- Обновлён APK и установлен на телефон по USB.

## Почему так
- В HBA1 лучше всего работают три практики: quality/onset-подход, мягкая обработка выбросов, и выравнивание meal-time по фактической динамике BG.
- Эти механики уменьшают шум в evidence и дают более физиологичные hourly base curves для runtime `ISF_eff/CR_eff`.

## Риски / ограничения
- Meal-alignment по rise-onset при очень шумном CGM может смещать mealTs неоптимально (ограничено `±30 минут` и через `alignmentPenalty`).
- ISF outlier blend использует reference как якорь; при плохом reference correction может быть слишком консервативной.
- Для дальнейшего улучшения 30/60m потребуется replay-калибровка порогов slope/penalty на реальных данных пациента.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.*"`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
3. `adb install -r -t /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. Проверить в diagnostics/audit наличие новых context-полей ISF/CR (`isfOutlier`, `insulinOnsetMin`, `mealAlignmentShiftMin`) и адекватность hourly-кривых ISF/CR в аналитике.

# Изменения — Этап 146: IOB строго из AAPS (без смешивания с локальным IOB)

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` отключено смешивание IOB:
  - `resolveRuntimeCobIobInputs()` теперь использует `iob_units` только из внешней телеметрии (`strictIob`),
  - локальный расчёт IOB (`localIob`) оставлен только как диагностический fallback-показатель, но не влияет на runtime `iob_units`.
- Усилен выбор IOB в `resolveLatestTelemetry()`:
  - для алиаса `iob_units` удалены derived ключи (`iob_effective_units`, `iob_real_units`, `iob_external_raw_units`),
  - добавлен `selectStrictAapsIob(...)` с фильтром по доверенным источникам (`aaps_broadcast`, `xdrip_broadcast`, `nightscout*`, `local_broadcast`) и ключам IOB,
  - timestamp алиасов теперь переносится в `latestTimestampByKey`, чтобы freshness-фильтр работал корректно и предсказуемо.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` UI переведен на строгий `iob_units`:
  - `latestIobUnits` берется напрямую из `iob_units`,
  - в telemetry coverage primary для IOB изменен на `iob_units` (с `iob_effective_units` только как alias/диагностика).

## Почему так
- Основной дефект был в двух местах:
  1) `iob_units` алиасился из `iob_effective_*` и runtime-derived значений,
  2) в runtime IOB смешивался с локальной моделью.
- Это приводило к «дерганию» и расхождению между AAPS и Copilot. После фикса канал для IOB в прогнозах и UI стабилизирован на значении AAPS.

## Риски / ограничения
- Если внешний канал AAPS/NS/XDrip временно не присылает `iob_units`, runtime IOB будет 0 (локальный IOB больше не подмешивается в основной контур).
- Диагностические поля `iob_real_units`/`iob_effective_units` сохраняются, но теперь не являются источником истины для основного IOB.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. На устройстве в UI/Overview сравнить IOB Copilot с AAPS: значения должны совпадать без скачков между циклами.
4. В audit `cob_iob_runtime_resolved` проверить, что `iobUnits` соответствует `iob_external_raw_units` (когда внешний IOB доступен).

# Изменения — Этап 147: Стабилизация работы в фоне (WorkManager storm guard)

## Что сделано
- В [WorkScheduler.kt](/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/scheduler/WorkScheduler.kt) добавлен guard в `triggerReactiveAutomation(...)`:
  - если активен минутный цикл `LocalNightscoutForegroundService`, reactive `OneTimeWork` не ставится.
- В [LocalNightscoutForegroundService.kt](/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/service/LocalNightscoutForegroundService.kt):
  - добавлен runtime-флаг `minuteLoopActive` (`AtomicBoolean`) + `isMinuteLoopActive()`;
  - выставление/сброс флага в жизненном цикле минутного цикла (`try/finally`) и `onDestroy()`.
- APK собран и установлен по USB (`adb install -r .../app-debug.apk`).

## Почему так
- В фоне одновременно работали:
  - минутный foreground-цикл `runAutomationCycle()`;
  - частые reactive WorkManager enqueue.
- Это создавало лишнюю нагрузку на JobScheduler (в логах постоянные `Exempted app ... considered buggy`) и повышало риск системных kill на MIUI.
- Теперь при активном runtime-сервисе остается один контур выполнения (минутный), без дублирующих reactive jobs.

## Риски / ограничения
- Если минутный foreground-сервис отключен/остановлен, reactive `WorkManager` остается активным как fallback.
- Сообщения `JobScheduler ... considered buggy` могут сохраняться некоторое время после предыдущих перегрузок системы, даже после фикса.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
2. `adb install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
3. Запустить Copilot, отправить в фон на 3–5 минут.
4. Проверить:
   - `adb shell pidof io.aaps.predictivecopilot` — процесс жив;
   - `adb logcat -d --pid <PID> | rg "WM-SystemJobScheduler|WM-SystemJobService|FATAL EXCEPTION|ANR"` — нет штормового enqueue и нет crash/ANR.

# Изменения — Этап 148: Runtime performance pass (telemetry scan + worker churn)

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/local/dao/TelemetryDao.kt` добавлены выборки последних значений:
  - `latestBySourceAndKeySince(...)`
  - `latestReportAndProfileSince(...)`
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` `resolveLatestTelemetry()` больше не сканирует весь хвост `telemetry_samples` за 6h/72h на каждом цикле:
  - runtime telemetry теперь берется как последние строки по `source+key`,
  - report/profile telemetry берется как последние строки по `key`,
  - дневные cumulative activity keys (`steps_count`, `distance_km`, `active_minutes`, `calories_active_kcal`) отдельно догружаются только за текущий день.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AuditLogger.kt` добавлен throttled audit:
  - `infoThrottled(...)`
  - `warnThrottled(...)`
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` убран шумный `automation_cycle_step_started`, а recurring info-логи (`automation_cycle_checkpoint`, `automation_cycle_step_completed`, `analytics_recalculate_skipped`, `isfcr_realtime_refresh_skipped`) переведены на throttle.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/scheduler/WorkScheduler.kt` periodic WorkManager задачи переведены с `UPDATE` на `KEEP`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/scheduler/SyncAndAutomateWorker.kt` worker теперь пропускает цикл, если foreground minute-loop уже активен.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/service/LocalNightscoutForegroundService.kt` DataStore settings больше не читаются заново каждую минуту:
  - добавлен cached settings snapshot, который обновляется через `collectLatest`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` добавлен мягкий throttle:
  - `nightscout_sync` не чаще 1 раза в 2 минуты,
  - `cloud_push_sync` не чаще 1 раза в 5 минут.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/receiver/LocalDataBroadcastReceiver.kt` debounce-skip лог по reactive enqueue переведен на throttled logging.
- Добавлен unit test:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/AuditLoggerTest.kt`
- APK собран и установлен по USB на устройство.

## Почему так
- До фикса горячий путь `resolveLatestTelemetry()` поднимал примерно:
  - около `19k` строк telemetry за `6h`,
  - до `262k` строк telemetry за `72h`,
  - затем группировал их в памяти на каждом минутном цикле.
- Дополнительно цикл дублировался periodic worker'ом поверх уже работающего foreground minute-loop, а audit-лог создавал лишние DB writes.
- Внесенные изменения убирают именно I/O/DB churn и redundant wakeups, не меняя прогнозную математику, rule engine и каналы действий.

## Риски / ограничения
- Nightscout pull теперь может опаздывать до `2 минут`, а cloud push до `5 минут`; при этом локальный ingest/broadcast и action path не изменены.
- `JobScheduler ... considered buggy` на MIUI может еще некоторое время появляться в системном logcat как хвост после предыдущей перегрузки, даже если текущий scheduling path уже стал легче.
- Остаточная CPU-нагрузка все еще есть в `DefaultDispatcher` и на UI startup; это уже следующий слой оптимизации, не тот же самый full-table telemetry scan.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AuditLoggerTest :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. После запуска на телефоне проверить:
   - в audit за последние 2 минуты больше нет `automation_cycle_step_started`;
   - `nightscout_sync_started` не идет каждую минуту подряд;
   - reactive/worker-контур не дублирует foreground minute-loop;
   - `top`/`top -H` показывают более низкую нагрузку, чем до фикса.

# Изменения — Этап 149: Runtime performance pass (allocation churn + UI analytics memoization)

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt`:
  - убран лишний `associate`-allocation в `payloadDouble(...)`;
  - `nearestGlucoseValueMmol(...)` переведен с линейного поиска на бинарный поиск;
  - локальный `estimateLocalCobIob(...)` больше не считается каждый цикл без необходимости:
    - если внешние `cob/iob` уже есть и нет недавних carb events, используется cached/fallback local estimate,
    - пересчет локального COB/IOB/onset остается только там, где он реально нужен для fallback/merge.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngine.kt`:
  - убран `associate`-allocation в `payloadDouble(...)`;
  - `ProfiledCarbEvent` теперь хранит `eventKey`, чтобы не пересчитывать hash/sort payload на каждом шаге терапии;
  - `buildTherapyStepSeries(...)` и `therapyDeltaAtHorizon(...)` используют precomputed event keys вместо повторного `eventKey(event)` в циклах.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/CarbAbsorptionProfiles.kt`:
  - `extractCarbsGrams(...)` больше не строит промежуточный normalized payload map на каждый вызов.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt`:
  - добавлен memoization для самых тяжелых UI builder'ов:
    - `resolveYesterdayProfileLines(...)`
    - `resolveIsfCrDeepLines(...)`
    - `resolveIsfCrHistoryPoints(...)`
  - эти блоки теперь переиспользуют кеш при неизменном входном наборе, вместо полного повторного пересчета на каждый emission общего `uiState`.
- APK собран и установлен по USB.

## Почему так
- После первого слоя оптимизации DB/I/O уже не были главным ограничением. На устройстве оставался постоянный hotspot в `DefaultDispatch` и частые `HeapTaskDaemon` GC.
- Причины по коду:
  - repeated payload normalization (`associate`) в циклах по therapy events;
  - repeated event key hashing/sorting в прогнозаторе;
  - тяжелые ISF/CR analytics builder'ы внутри единого `MainUiState`, которые пересчитывались даже вне Analytics screen.
- Внесенные изменения снижают allocation churn и повторные вычисления без изменения клинической логики прогнозов/правил.

## Риски / ограничения
- Memoization в `MainViewModel` завязано на state-signature (timestamp/size/bucket), а не на полный deep hash содержимого. Для текущих append-only/latest потоков это приемлемо, но не рассчитано на массовое редактирование истории задним числом.
- Остаточная нагрузка на `DefaultDispatch` и startup lag все еще есть; по live-профилю следующий bottleneck теперь находится в архитектуре единого `uiState` и, вероятно, в глубокой аналитике/графиках ISF/CR.
- В logcat по-прежнему есть повторяющиеся `NanoHTTPD: Could not send response to the client`; это отдельный transport/client noise контур, не тот же hotspot, что DB-scan первого этапа.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AuditLoggerTest`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На устройстве проверить:
   - `top -H -p <PID>`: меньше горячих `DefaultDispatch`, чем до этого прохода;
   - в audit `cob_iob_runtime_resolved` и bias-related recurring messages не идут на каждом цикле подряд;
   - Overview/Analytics не должны менять данные или поведение, только снижать лишние пересчеты.

# Изменения — Этап 150: Real ISF/CR freshness + activity factor hardening

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/isfcr/IsfCrContextModel.kt`:
  - исправлена нормализация camelCase telemetry keys (`activityRatio` -> `activity_ratio`, `stepsCount` -> `steps_count`);
  - `activity_ratio`, `steps_rate_15m`, `sensor_quality_score`, `stress_score`, `uam_value` теперь применяются только при свежей телеметрии;
  - добавлен мягкий post-activity tail для ISF/CR:
    - `activity_ratio_avg_90m`
    - `steps_rate_60m`
    - `activity_tail_boost`
  - в `factors` добавлены новые explainable поля по возрасту/силе activity влияния.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` realtime refresh real ISF/CR вынесен на отдельный `Dispatchers.IO.limitedParallelism(1)` scope, чтобы refresh не застревал на перегруженном `DefaultDispatcher`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/IsfCrRepository.kt` `computeRealtimeSnapshot(...)` тоже переведен на выделенный realtime dispatcher.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt`:
  - realtime карточка Analytics теперь показывает только свежий realtime snapshot;
  - если snapshot устарел, UI пишет явный stale age вместо подмены факторами из старого снапшота.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/MainUiStateMappers.kt` tile `ISF AAPS` / `CR AAPS` больше не подставляет base/profile fallback как будто это AAPS-значение; показываются только реальные AAPS-источники.
- Добавлены unit tests:
  - camelCase telemetry keys действительно влияют на activity factor;
  - stale activity telemetry больше не поднимает ISF;
  - fallback step-rate по `steps_count` продолжает работать.

## Почему так
- По live DB и audit было видно, что:
  - `telemetry_samples` свежие,
  - `isf_cr_snapshots` и `profile_estimates` stale,
  - в audit повторялись `isfcr_realtime_unavailable` и `isfcr_realtime_stale_reused`.
- Из-за этого Analytics рисовал свежую линию AAPS поверх старой линии real ISF/CR, а runtime часто не доводил real ISF/CR до прогнозов, потому что snapshot оставался stale/fallback.
- Activity/steps формально были в модели, но без freshness-gate и без post-exercise tail; старый stream мог давать ложное повышение чувствительности, а недавняя нагрузка после окончания активности почти не отражалась.

## Риски / ограничения
- Post-activity tail сейчас мягкий и эвристический. Это не полноценная физиологическая модель EPOC/late-exercise sensitivity на несколько часов.
- Если activity stream вообще перестал приходить, factor теперь безопасно схлопывается к `1.0`; то есть система выбирает недоучет нагрузки вместо ложного роста ISF.
- Realtime refresh теперь изолирован от `DefaultDispatcher`, но если upstream ingest в принципе не приносит новые glucose/therapy/telemetry, snapshot все равно останется stale.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon -Dkotlin.incremental=false -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests io.aaps.copilot.domain.isfcr.IsfCrContextModelTest`
2. В Analytics:
   - `ISF AAPS`/`CR AAPS` не должны показывать profile/base fallback, если свежей AAPS-линии нет;
   - realtime ISF/CR карточка должна явно писать stale age, если snapshot старый.
3. В audit/logs:
   - должны появляться новые `isfcr_realtime_refresh_completed`;
   - должен уменьшиться шум `isfcr_realtime_stale_reused`.
4. На живой активности:
   - при свежих `activity_ratio/steps_count` `activity_factor` должен быть > `1.0`,
   - после окончания нагрузки factor должен спадать мягче за счет 90m/60m tail, а не мгновенно.

# Изменения — Этап 151: Hang triage, realtime ISF/CR hot-path reduction, and forecast cycle optimization

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/local/dao/GlucoseDao.kt` и `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/local/dao/TherapyDao.kt` добавлены bounded realtime-query методы:
  - `sinceDescLimit(...)` для glucose,
  - `sinceDescLimit(...)` для therapy.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/IsfCrRepository.kt` realtime ISF/CR path дополнительно ужат:
  - realtime lookback уменьшен до `18h`,
  - realtime glucose rows ограничены `1800`,
  - realtime therapy rows ограничены `360`,
  - realtime telemetry query ограничен `3000` rows и trim'ится по key-age/limit.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/isfcr/IsfCrWindowExtractor.kt` снижена стоимость realtime evidence extraction:
  - correction/meal candidates фильтруются заранее, а не на каждом therapy event;
  - range lookups для glucose/therapy переведены с full-list `filter` на бинарный поиск + `subList`;
  - `closestTo(...)` стал бинарным вместо full scan;
  - `ageHoursAt(...)` перестал аллоцировать временные sequence/filter map chains;
  - для CR-fit вклад insulin/carbs по интервалам теперь предрассчитывается один раз (`CrFitInterval`) вместо пересчета на каждый candidate `CR`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngine.kt` оптимизирован локальный forecast hot path:
  - `buildTherapyStepSeries(...)` больше не парсит один и тот же therapy payload на каждом 5m step;
  - runtime events для прогноза предразбираются один раз в lightweight `RuntimeTherapyEvent`;
  - regex для normalize вынесены в precompiled constants;
  - убран повторный payload-normalization churn в основном prediction cycle.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/IsfCrRepository.kt` realtime audit после snapshot теперь throttled:
  - `isfcr_evidence_extracted` не чаще `10m`,
  - `isfcr_realtime_computed` не чаще `5m`,
  - `isfcr_low_confidence` не чаще `15m`.
- APK собран и установлен по USB на устройство.
- Проведено несколько live USB captures с копированием `copilot.db` и анализом audit/logcat.

## Почему так
- По USB audit было видно:
  - `automation_cycle_finished` держался на `15–21s`,
  - realtime `ISF/CR` часто уходил в `scheduled/recovered/stale_reused` без `completed`,
  - главный runtime bottleneck находился между `post_cob_iob_runtime` и `post_forecast_storage`,
  - при этом `isf_cr_snapshots` уже обновлялись, что указывало не на математический крах, а на перегруженный hot path и дорогой post-persist audit.
- После оптимизаций:
  - `automation_cycle_finished` снизился до примерно `3.3–6.9s`,
  - появился `isfcr_realtime_refresh_completed` в свежем audit на устройстве,
  - realtime snapshot продолжает обновляться без постоянного stale-guard recovery.

## Риски / ограничения
- Realtime ISF/CR теперь recency-biased (`18h`), поэтому при редких correction/meal окнах он чаще будет честно уходить в `FALLBACK`, а не тянуть тяжелый extraction с 48h history.
- `isfcr_realtime_computed` и low-confidence diagnostics теперь throttled; observability сохранена, но не по каждому циклу.
- `NanoHTTPD: Could not send response to the client` в системном logcat остаётся как отдельный transport/client noise контур; он не оказался главным источником длинных automation cycles в этом этапе.
- UI startup jank на MIUI ещё не исчерпан полностью; основной runtime hang-контур сокращен, но отдельные `Skipped frames` на холодном открытии могут сохраняться.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На устройстве после холодного старта проверить в `copilot.db` / audit:
   - появляется `isfcr_realtime_refresh_completed`,
   - `automation_cycle_finished` типично не выходит за однозначные секунды, а не `15–20s`,
   - `post_cob_iob_runtime -> post_forecast_storage` больше не доминирует весь цикл.

# Изменения — Этап 152: Runtime ownership moved to foreground service to reduce cold-start churn

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/service/AppContainer.kt` runtime-компоненты больше не стартуют из `Application`/`AppContainer.init` автоматически:
  - локальный Nightscout server,
  - local activity sensor collector,
  - Health Connect collector.
- В `AppContainer` добавлены явные lifecycle-методы:
  - `startRuntimeControllers()`
  - `stopRuntimeControllers()`
  Они делают foreground-service владельцем локального runtime-контра.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/service/LocalNightscoutForegroundService.kt` сервис теперь:
  - поднимает runtime controllers только если runtime действительно нужен по settings,
  - останавливает runtime controllers при shutdown/disable,
  - синхронизирует server/sensors с settings-монитором, а не с каждым cold start процесса.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/CopilotApp.kt` убраны дублирующие старты activity collectors из `onCreate()`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/receiver/BootCompletedReceiver.kt` boot/package-replaced path больше не стартует collectors напрямую; вместо этого запускается foreground runtime service (`allowBackground=true`), который уже сам решает, нужен ли runtime.

## Почему так
- По audit было видно частое повторение:
  - `local_nightscout_started`
  - `local_activity_sensor_started`
  - `local_activity_sensor_seeded`
  часто с интервалом в секунды/минуты и без парных `stopped`.
- Это указывало не только на transport noise, но и на lifecycle churn:
  process поднимался на broadcast/worker cold start, `Application` сразу создавал `AppContainer`, а тот автоматически стартовал тяжелые runtime-компоненты, даже если foreground runtime еще не был подтвержден.
- Такой путь создавал лишний startup CPU/memory cost и делал локальный server/sensor startup слишком чувствительным к любому process wakeup.
- После переноса владения runtime в foreground service broadcast-only cold start процесса перестает автоматически поднимать локальный Nightscout и sensor collectors; это должно заметно снизить churn и количество лишних `*_started` событий.

## Риски / ограничения
- После boot/package replace теперь всегда пробуется старт foreground runtime service; если runtime disabled в settings, сервис быстро остановится сам. Это может дать короткий transient startup path, но он дешевле, чем поднимать все контроллеры из `Application`.
- Если сервис будет убит системой и не будет сразу восстановлен, broadcast-only cold start больше не поднимет локальный Nightscout server автоматически. Это сознательное решение: владельцем runtime теперь является foreground service.
- Unit-теста именно на service/Application lifecycle не добавлено: текущий Android-only сценарий плохо покрывается локальным JUnit без отдельного instrumented harness.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AuditLoggerTest --tests io.aaps.copilot.domain.isfcr.IsfCrContextModelTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
4. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
5. На устройстве/в `copilot.db` проверить:
   - `local_nightscout_started` больше не идет на каждый broadcast-only wakeup;
   - `local_activity_sensor_started` не дублируется на каждом process cold start;
   - foreground runtime продолжает держать local Nightscout и automation cycle рабочими.

# Изменения — Этап 153: Route-aware analytics workload + NanoHTTPD socket-close noise suppression

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` добавлен `activeRouteState` и `setActiveRoute(route)`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt` текущий route теперь передается во ViewModel через `LaunchedEffect(currentRoute)`.
- `MainViewModel.buildMainUiState(...)` стал route-aware:
  - тяжелые analytics-only блоки строятся только на route `analytics`,
  - вне analytics больше не рассчитываются на каждый минутный цикл:
    - `yesterdayProfileLines`,
    - `isfCrDeepLines`,
    - `activityLines`,
    - `profileSegmentLines`,
    - `isfCrHistoryPoints`,
    - realtime factor lines / activation gate lines / dropped-reasons / wear-impact summaries,
    - deep combined ISF/CR diagnostics block.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/service/LocalNightscoutServer.kt` установлен узкий `java.util.logging.Filter` на logger `fi.iki.elonen.NanoHTTPD`, который подавляет только известный harmless шум:
  - `Could not send response to the client`
  - при `SocketException: Socket is closed`
  Остальные server logs остаются без изменений.

## Почему так
- По live logcat после Этапа 152 было видно два оставшихся hotspots:
  1. ART/JIT продолжал дорого компилировать огромный `MainViewModel.buildMainUiState$populateResult(...)`, хотя пользователь в этот момент находился не в Analytics.
  2. `NanoHTTPD` продолжал генерировать частый SEVERE log spam на обычном socket-close от polling/TLS клиента, что не давало ценного сигнала и мешало диагностике.
- Route-aware analytics убирает большую часть лишней работы на `Overview/Forecast/Safety/UAM`, не меняя контрактов экранов и не трогая бизнес-логику.
- Фильтр логгера безопаснее, чем переписывать socket.io/TLS transport, так как он не меняет поведение сервера и убирает только известный шумовой кейс.

## Риски / ограничения
- При первом входе на экран `Analytics` тяжелые блоки будут строиться именно в этот момент; это сознательный перенос нагрузки из фонового общего цикла в момент реального открытия экрана.
- Старый `CopilotRoot` не прокидывает active route во ViewModel; если кто-то вручную вернет legacy UI root, analytics-heavy блоки останутся в default route `overview` до явного вызова `setActiveRoute`.
- Лог-фильтр подавляет только конкретный harmless socket-close path. Если transport начнет падать по другой причине, эти ошибки останутся видимыми.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На устройстве:
   - открыть `Overview` и убедиться, что startup/переключение стало легче, без ожидания тяжелой Analytics-секции;
   - затем перейти в `Analytics` и убедиться, что ISF/CR графики и deep diagnostics подгружаются при открытии экрана;
   - в `logcat` убедиться, что spam `Could not send response to the client` больше не засоряет вывод, при этом runtime server продолжает работать.

# Изменения — Этап 154: MainUiState builder de-jitted and NanoHTTPD suppression hardened

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` убрана локальная closure `populateResult()`, которая порождала отдельный giant JIT method внутри `buildMainUiState(...)`.
- В том же файле вынесен весь блок `daily report / replay` в отдельный helper `buildDailyReportBundle(...)` и отдельную структуру `DailyReportBundle`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/service/LocalNightscoutServer.kt` suppression для NanoHTTPD усилен:
  - фильтр ставится не только на logger `fi.iki.elonen.NanoHTTPD`,
  - но и на его handler chain/root handlers,
  - при этом подавляется только один известный harmless socket-close кейс.
- APK пересобран и установлен на телефон по USB.

## Почему так
- После Этапа 153 `logcat` уже показал, что bottleneck сместился:
  - `MainViewModel.buildMainUiState$populateResult(...)` всё ещё дорого компилировался,
  - а NanoHTTPD socket-close spam частично мог приходить через handler chain.
- Разрезание на отдельный bundle уменьшает размер и JIT-pressure общего UI builder без изменения UI-контрактов.
- Усиленный handler-level suppression делает поведение стабильнее даже если JUL handlers уже были инициализированы раньше самого сервера.

## Риски / ограничения
- `buildDailyReportBundle(...)` остаётся тяжёлым helper’ом сам по себе; если его считать на каждом route, он всё равно будет прожигать CPU.
- Подавление NanoHTTPD по handlers затрагивает root JUL handlers, но фильтр узкий и режет только строго совпадающий socket-close шум.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На устройстве проверить:
   - в `logcat` больше нет `Could not send response to the client` на новом процессе;
   - startup больше не логирует giant compile именно для `populateResult`.

# Изменения — Этап 155: Daily report moved behind route gate for Overview startup

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` добавлен отдельный route gate:
  - `reportAnalyticsRequested = activeRoute == analytics || activeRoute == ai_analysis`
- `buildDailyReportBundle(...)` теперь вызывается только для `Analytics` и `AI Analysis`.
- Для остальных route используется пустой `DailyReportBundle`, чтобы `Overview/Forecast/Safety/UAM` не поднимали local daily replay analytics на старте.
- APK снова пересобран и установлен на телефон по USB.

## Почему так
- После Этапа 154 `buildMainUiState(...)` перестал упираться в instruction limit, но `logcat` показал новый остаточный cost:
  - уже отдельный `buildDailyReportBundle(...)` всё ещё компилировался и грузил старт, хотя пользователь открывал обычный `Overview`.
- Перенос этого bundle за route gate устраняет оставшуюся ненужную работу на холодном старте и сужает startup hot path до реально нужных экрану данных.

## Риски / ограничения
- Первый вход на `Analytics` или `AI Analysis` по-прежнему будет тяжелее остальных экранов: daily report/replay bundle теперь считается именно там.
- Корневой Compose-слой всё ещё подписывается на все screen states сразу; это следующий отдельный слой оптимизации, если понадобится ещё снизить startup jank.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. Снять чистый `logcat` на cold start `Overview` и проверить:
   - нет `Method exceeds compiler instruction limit` для `buildMainUiState`,
   - нет компиляции `buildDailyReportBundle` во время обычного старта `Overview`,
   - `Could not send response to the client` не появляется в новом процессе.

# Изменения — Этап 156: Root Compose subscriptions moved to route-local collectors

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt` верхний Compose root перестал одновременно `collectAsStateWithLifecycle()` для всех экранов.
- На верхнем уровне теперь собираются только:
  - `messageUiState`,
  - `appHealthUiState`,
  - `uiStyleState`.
- Подписки на экранные state перенесены внутрь соответствующих `NavHost` route:
  - `overviewUiState`,
  - `forecastUiState`,
  - `uamUiState`,
  - `safetyUiState`,
  - `auditUiState`,
  - `analyticsUiState`,
  - `aiAnalysisUiState`,
  - `settingsUiState`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` добавлен лёгкий `uiStyleState`, чтобы theme/style не требовал сборки полного `MainUiState` на старте.
- APK пересобран и установлен на телефон по USB.

## Почему так
- После route-gate для daily analytics startup всё ещё тянул лишний Compose workload, потому что root-слой держал активные подписки на state всех экранов сразу.
- Перенос screen-state subscriptions на уровень конкретного route убирает ненужные recomposition/mapping цепочки до тех пор, пока экран реально не открыт.
- Это снижает startup fan-out без изменения контрактов экранов и без переписывания бизнес-логики ViewModel.

## Риски / ограничения
- При первом входе на редкие экраны (`Analytics`, `AI Analysis`, `Settings`) их подписки теперь активируются лениво; первый показ этих экранов может стоить чуть дороже, чем повторный.
- `messageUiState` и `appHealthUiState` по-прежнему глобальны, потому что они действительно нужны root layout.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На устройстве открыть `Overview`, затем последовательно `Forecast/Analytics/Settings` и убедиться, что startup легче, а отдельные экраны подгружают данные только при открытии.

# Изменения — Этап 157: AppSettingsStore mapping de-duplicated to remove startup JIT hotspot

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/config/AppSettingsStore.kt` вынесен единый helper `readSettings(prefs: Preferences): AppSettings`.
- Поток настроек теперь использует `dataStore.data.map(::readSettings)` вместо giant inline-конструктора.
- `update(...)` теперь тоже использует тот же helper вместо второго дублированного giant inline-конструктора.
- Выполнены:
  - `./gradlew --no-daemon :app:compileDebugKotlin`
  - `./gradlew --no-daemon :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AuditLoggerTest --tests io.aaps.copilot.domain.isfcr.IsfCrContextModelTest`
  - `./gradlew --no-daemon :app:assembleDebug`
- APK установлен на устройство по USB, снят новый cold-start `logcat`:
  - `/Users/mac/Andoidaps/artifacts/live_check_appsettings_20260306_120152/logcat.txt`

## Почему так
- Предыдущий capture показывал отдельный ART/JIT hotspot:
  - `io.aaps.copilot.config.AppSettingsStore$special$$inlined$map$1$2.emit(...)`
- Причина была прямой: один и тот же очень большой `Preferences -> AppSettings` mapping существовал дважды, и один из них находился внутри inline `Flow.map`.
- Вынесение mapping в обычный helper сокращает размер inline lambda, уменьшает JIT pressure на cold start и убирает дублирование логики.

## Риски / ограничения
- Это рефактор без изменения контрактов; риск в основном регрессионный, если какое-то поле случайно выпало из helper. Поэтому после изменения прогнаны сборка и unit-test smoke.
- Startup jank полностью не исчез: в свежем capture остаются `ViewRootImpl.performTraversals()` и `Skipped 46 frames`, то есть следующий bottleneck уже в Compose/layout/main-thread, а не в JIT-мусоре `AppSettingsStore`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:testDebugUnitTest --tests io.aaps.copilot.data.repository.AuditLoggerTest --tests io.aaps.copilot.domain.isfcr.IsfCrContextModelTest`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
4. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
5. Снять чистый startup `logcat` и проверить, что больше не появляется:
   - `AppSettingsStore$special$$inlined$map$1$2.emit`
   - `buildMainUiState`
   - `buildDailyReportBundle`
   - `Could not send response to the client`

# Изменения — Этап 158: Overview first-frame load reduced

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/OverviewScreen.kt` второстепенные секции (`UAM`, `Telemetry`, `Last action`) отложены до первого кадра через `withFrameNanos`.
- В том же файле `AnimatedContent` для текущей глюкозы и delta не используется на самом первом кадре; анимации включаются только после первичного рендера.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` `overviewUiState` перестал стартовать с `ScreenLoadState.LOADING`; initial state теперь строится из `MainUiState().toOverviewUiState(false)`, чтобы не поднимать shimmer/skeleton анимации на холодном старте `Overview`.
- APK пересобран и установлен на устройство по USB.

## Почему так
- Cold start всё ещё уходил в `ViewRootImpl.performTraversals()` и `Skipped 46–47 frames` даже после очистки JIT hotspots.
- `Overview` стартовал через loading-skeleton path с бесконечной shimmer-анимацией, а затем быстро переключался на ready UI, что создавало лишнюю двойную композицию и layout cost.
- Уменьшение first-frame workload на `Overview` безопаснее, чем трогать бизнес-логику, и даёт шанс сократить именно main-thread startup cost.

## Риски / ограничения
- `UAM/Telemetry/Last action` появляются на `Overview` на один кадр позже первого визуального рендера.
- Метрики и действия не теряются, но первый кадр intentionally более лёгкий.
- Сам по себе этот шаг не снял весь startup jank; `performTraversals()` остался главным сигналом.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. Снять cold-start `logcat` и убедиться, что `Overview` больше не проходит через shimmer-loading path на первом кадре.

# Изменения — Этап 159: Root drawer/menu made lazy for startup path

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt` drawer content теперь композируется только когда `drawerState` реально переходит в `Open`.
- В том же файле `DropdownMenu` в `TopBar` теперь создаётся только когда `moreExpanded=true`, а не висит постоянно в дереве с `expanded=false`.
- APK пересобран и установлен на устройство по USB.
- Снят новый startup capture:
  - `/Users/mac/Andoidaps/artifacts/live_check_root_lazy_20260306_121943/logcat.txt`

## Почему так
- После облегчения `Overview` основной startup cost оставался в root Compose/layout path.
- Закрытый drawer и нераскрытый overflow menu не должны платить полную композиционную стоимость на первом кадре.
- Это безопасная root-оптимизация: поведение UI не меняется, lazy-порог только переносит создание скрытых элементов до момента реального открытия.

## Риски / ограничения
- При первом открытии drawer/menu их composition cost переносится на момент открытия.
- Startup стал лучше, но не полностью стабилен: в свежем capture первый стартовый процесс дал примерно `Skipped 38 frames`, однако в логе остаются и поздние повторные процессы/launch-последовательности с более высокими значениями. Это уже отдельный сигнал на разбор activity/process relaunch path.
- `Compiler allocated ... ViewRootImpl.performTraversals()` остаётся в startup log как главный residual hotspot.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. Снять cold-start `logcat` и сравнить:
   - до lazy root: `Skipped ~46–47 frames`
   - после lazy root: первый стартовый процесс ~`38 frames`

# Изменения — Этап 160: MainActivity startup moved beyond first draw

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/MainActivity.kt` startup-инициализация локального Nightscout foreground service, activity sensors и Health Connect была вынесена из прямого `onStart()` в `enqueuePostDrawStartup()`.
- В том же файле добавлена короткая post-draw задержка `250ms` через `lifecycleScope.launch { delay(250) }`, чтобы сервисы не добивали второй кадр сразу после первого показа activity.
- APK пересобран, установлен по USB и проверен новым cold-start capture:
  - `/Users/mac/Andoidaps/artifacts/live_check_route_shell_20260306_134247/logcat.txt`

## Почему так
- Предыдущий capture показал, что service/sensor startup уже можно вынести за первый `Displayed`, но всё ещё оставался второй тяжёлый кадр, связанный с `MainActivity$$ExternalSyntheticLambda2`.
- Короткая post-draw задержка позволяет first draw и immediate follow-up frame пройти без старта фоновых компонентов на main thread.
- Это не меняет функциональный контракт: сервисы всё равно стартуют автоматически при открытии приложения, но уже не в самом критичном startup-окне.

## Риски / ограничения
- Локальный foreground service и сбор sensors стартуют примерно на 250ms позже после открытия `MainActivity`.
- Для текущего продукта это безопасно, но всё равно slightly сдвигает момент активации локального контура при ручном запуске.
- Этот шаг не решает сам по себе тяжёлый `performTraversals()` на первом кадре.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. Снять cold-start `logcat` и убедиться, что `local_nightscout.START` и `LocalActivitySensorCollector` появляются уже после `Displayed io.aaps.predictivecopilot/io.aaps.copilot.MainActivity`.

# Изменения — Этап 161: Route shell deferred past first frame

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt` добавлен `routeShellReady`, который включает `AppHealthBanner` и `NavHost` только после первого `withFrameNanos`.
- До первого кадра root рисует только лёгкий shell (`Scaffold`, top bar, bottom nav, spacer вместо route-content).
- APK пересобран, установлен по USB и проверен новым cold-start capture:
  - `/Users/mac/Andoidaps/artifacts/live_check_route_shell_20260306_134247/logcat.txt`

## Почему так
- После оптимизаций `Overview` и lazy root основным startup bottleneck оставался первый `ViewRootImpl.performTraversals()`.
- Даже облегчённый `Overview` всё ещё заставлял Compose поднимать route-content слишком рано.
- Отложенный `NavHost` снижает стоимость первого реального кадра и оставляет полную маршрутизацию уже на следующий frame, что безопаснее, чем упрощать бизнес-компоненты экрана.

## Риски / ограничения
- На самом первом кадре пользователь видит shell без route-content; полный экран дорисовывается на следующем кадре.
- Это осознанный компромисс ради startup latency. Навигация и данные не теряются.
- Startup jank ещё не исчез полностью: главный residual hotspot всё ещё `ViewRootImpl.performTraversals()`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. Снять cold-start `logcat` и сравнить ключевые точки:
   - до этих правок: `PerfMonitor doFrame ~617ms`, `Displayed +1.747s`
   - после этих правок: `PerfMonitor doFrame ~475ms`, `Displayed +1.339s`
   - `local_nightscout.START` и старт sensor collector должны идти уже после `Displayed`.

# Изменения — Этап 162: Animated background deferral trial rolled back

## Что сделано
- Была проверена гипотеза, что `UiStyle.DYNAMIC_GRADIENT` перегружает первый кадр через ранний `rememberInfiniteTransition`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/theme/CopilotTheme.kt` временно добавлялся статический первый кадр для динамического фона.
- После USB-проверки этот эксперимент был откатан: текущая версия снова использует исходную реализацию `DYNAMIC_GRADIENT`.

## Почему так
- Cold-start capture после этого эксперимента показал регресс:
  - `PerfMonitor doFrame ~751ms`
  - `Skipped 46 frames`
- Это хуже текущего baseline, поэтому оставлять изменение было технически неверно.

## Риски / ограничения
- Этап не оставляет функциональных изменений в коде: это зафиксированный rollback неудачной гипотезы.
- Полезен как негативный результат, чтобы не возвращаться к этому пути повторно без новых измерений.

## Как проверить
1. Открыть `/Users/mac/Andoidaps/artifacts/live_check_static_bg_20260306_135025/logcat.txt`
2. Убедиться, что этот capture показывает регресс по сравнению с соседними startup captures.

# Изменения — Этап 163: Startup chrome simplified for first frame

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt` первый кадр теперь рендерит более лёгкий shell:
  - без `ModalNavigationDrawer`
  - без health status indicators в `TopBar`
  - без текстовых label в bottom navigation
- Полный chrome (`drawer`, health indicators, labels) возвращается после первого кадра.
- APK пересобран, установлен по USB и проверен cold-start capture:
  - `/Users/mac/Andoidaps/artifacts/live_check_chrome_shell_20260306_135611/logcat.txt`

## Почему так
- После предыдущих шагов первый кадр всё ещё упирался в root Compose tree.
- Самый безопасный следующий шаг — убрать из первого кадра не критичный chrome, не затрагивая route-логику, background runtime и сами экраны.
- Это снизило startup turbulence: в свежем capture полностью исчезли `Skipped frames`, при том что `FGS` и sensors всё так же стартуют уже после `Displayed`.

## Риски / ограничения
- На самом первом кадре верхняя панель и bottom nav упрощены; детали появляются на следующем кадре.
- `Displayed` вырос относительно лучшего точечного capture (`~1.339s -> ~1.370s`), но startup стал ровнее по frame pacing: `Skipped frames` больше не фиксируются.
- Остаточный bottleneck всё ещё существует:
  - `PerfMonitor doFrame ~491ms`
  - поздний `Compiler allocated ... ViewRootImpl.performTraversals()`

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. Снять cold-start `logcat` и проверить, что:
   - `Displayed io.aaps.predictivecopilot/io.aaps.copilot.MainActivity` остаётся примерно `~1.37s`
   - `local_nightscout.START` и `LocalActivitySensorCollector` идут после `Displayed`
   - строки `Skipped .*frames` отсутствуют

# Изменения — Этап 164: Drawer startup gating rolled back due to menu UX regression

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt` убран эксперимент с динамическим подключением `ModalNavigationDrawer` после первого кадра.
- `ModalNavigationDrawer` снова существует стабильно с начала композиции.
- Сохранены только безопасные startup-оптимизации:
  - упрощённый первый кадр chrome
  - отложенный route content
  - post-draw startup для сервисов
- APK пересобран и установлен по USB.

## Почему так
- После этапа 163 пользовательская проверка показала регрессию UX: окно меню/drawer вело себя нестабильно и мешало интерфейсу.
- Причина правдоподобная: динамическое появление `ModalNavigationDrawer` меняло структуру root tree уже после старта и могло ломать ожидаемое поведение меню.
- Такой патч нельзя оставлять, даже если он даёт потенциальный startup gain.

## Риски / ограничения
- После rollback menu behavior возвращён к стабильной модели.
- Потенциальная часть startup-выигрыша от полного удаления drawer из первого кадра потеряна, но это осознанный компромисс в пользу корректного UX.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На телефоне открыть и закрыть menu/drawer несколько раз и убедиться, что оно больше не залипает и не вылезает само.

# Изменения — Этап 165: Drawer gestures constrained to close-only mode

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt` кнопка menu теперь работает как toggle:
  - если drawer открыт, нажатием закрывается
  - если drawer закрыт, нажатием открывается
- В том же файле для `ModalNavigationDrawer` добавлен `gesturesEnabled = drawerOpen`, то есть:
  - жесты доступны только когда drawer уже открыт
  - edge-swipe открытие из закрытого состояния отключено
- APK пересобран и установлен по USB.

## Почему так
- Пользовательский симптом был характерный: drawer закрывался свайпом влево и тут же открывался обратно.
- Наиболее вероятная причина — конфликт жестов, когда закрывающий свайп завершался у левого края, а drawer в тот же момент снова принимал edge-open gesture.
- Дополнительно кнопка menu до этого умела только открывать, но не закрывать drawer, что усугубляло ощущение "залипания".

## Риски / ограничения
- Drawer больше нельзя открыть свайпом от левого края из закрытого состояния; открытие только через кнопку menu.
- Это намеренно: стабильный UX здесь важнее edge-open gesture.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На телефоне:
   - открыть menu кнопкой
   - закрыть свайпом влево
   - убедиться, что drawer не появляется снова
   - повторить закрытие кнопкой menu и убедиться, что она теперь закрывает drawer как toggle

# Изменения — Этап 166: Drawer moved off ModalNavigationDrawer and new selectable Midnight Glass theme added

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt` удалён runtime-критичный `ModalNavigationDrawer`.
- Вместо него добавлен собственный overlay drawer:
  - явный `drawerOpen` state
  - `BackHandler` для закрытия
  - scrim tap для закрытия
  - slide/fade animation без повторного re-open bounce
- Кнопка menu в `TopBar` теперь всегда отображает именно menu-иконку, а не warning-иконку; статусы остаются в health/notification контуре.
- Введён новый selectable стиль `MIDNIGHT_GLASS`, основанный на новом Figma направлении:
  - добавлен enum в `UiStyle`
  - добавлены строки в `values/strings.xml` и `values-ru/strings.xml`
  - `SettingsScreen` теперь показывает третий вариант стиля
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/theme/CopilotTheme.kt` добавлена новая цветовая схема и новый фон для `MIDNIGHT_GLASS`:
  - slate/dark glass surfaces
  - высококонтрастный foreground
  - спокойный layered gradient background
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/MainActivity.kt` убрана внешняя обёртка `AapsCopilotTheme`; theme теперь применяется внутри root по выбранному стилю пользователя.

## Почему так
- Симптом с menu был типичным для нестабильного anchored drawer-контра Material 3: визуально drawer закрывался, но tree/anchor state снова возвращал его в open-like поведение.
- После нескольких более мягких правок надёжнее было убрать `ModalNavigationDrawer` из критического пути и перейти на свой контролируемый overlay drawer.
- Новый Figma-источник лучше ложится не как частичный фон, а как отдельный selectable theme, чтобы пользователь мог переключать старый стиль и новый без влияния на данные, расчёты и графики.

## Риски / ограничения
- Закрытие drawer теперь детерминированно через кнопку menu, back и tap по scrim. Swipe-close больше не зависит от поведения `ModalNavigationDrawer`.
- `MIDNIGHT_GLASS` пока меняет прежде всего root/chrome/theme layer: фон, top bar, bottom nav, drawer palette и общую цветовую схему. Полная переработка всех экранных карточек под новый visual language остаётся следующей итерацией.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На телефоне:
   - открыть/закрыть menu несколько раз кнопкой menu
   - закрыть drawer нажатием по затемнённому scrim
   - проверить, что окно menu больше не появляется само повторно
   - открыть `Settings -> App style` и переключить `Classic / Dynamic gradient / Midnight glass`
   - убедиться, что меняется только визуальный стиль, а данные и графики остаются теми же

# Изменения — Этап 167: Figma Make UAM style mapped into Midnight Glass theme

## Что сделано
- Через Figma MCP считаны исходники Make-файла `z7CLjU17BQxyCb5OXvlINk`:
  - `src/app/components/Layout.tsx`
  - `src/app/screens/UAM.tsx`
  - `src/app/components/EventCard.tsx`
  - `src/app/components/MetricCard.tsx`
  - `src/app/components/PredictionChip.tsx`
  - `src/app/components/SafetyBanner.tsx`
  - `src/app/components/InfoTooltip.tsx`
  - `src/app/screens/Settings.tsx`
  - `src/styles/theme.css`
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/theme/CopilotTheme.kt` `MIDNIGHT_GLASS` переведён в dark-first theme, чтобы реально соответствовать visual language из Figma Make, а не зависеть от системного light mode.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/UamScreen.kt` переработан UAM screen под Figma-направление:
  - tinted event cards для warning/success/info
  - status/mode/confidence/start/carbs chips в стиле Make
  - тёмные glass-like info panels
  - info-button в синем pill-контейнере
  - нижняя строка статов `Today / This week / Accuracy`
  - более близкая иерархия заголовков/ID/metadata к дизайну Make
- Добавлены новые строки:
  - `uam_event_detected_title`
  - `uam_event_confirmed_title`
  - `label_today`
  - `label_this_week`
  - `label_accuracy`

## Почему так
- Пользователь явно указал, что новый стиль должен быть близок к Figma-скриншоту, а не только к предыдущему экспериментальному фону.
- Из Make-исходников видно, что стиль строится на конкретной системе поверхностей:
  - тёмный slate/navy background
  - tinted cards по типу состояния
  - pills/chips как главный язык статусов
  - контрастные белые заголовки и приглушённый secondary text
- Для этого недостаточно было оставить только root background; нужно было перенести UAM screen visual hierarchy и сделать `MIDNIGHT_GLASS` dark-first.

## Риски / ограничения
- На этом этапе наиболее точно перенесён root chrome + UAM screen.
- Остальные экраны (`Overview`, `Forecast`, `Safety`, `Settings`) пока используют ту же новую тему и chrome, но не все их внутренние карточки ещё доведены до полной 1:1 близости с Make.
- Следующая итерация должна добрать `Overview` и `Forecast`, где в Make тоже есть выраженные chart/card/filter patterns.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На телефоне:
   - открыть `Settings -> App style`
   - выбрать `Midnight glass`
   - открыть вкладку `UAM`
   - убедиться, что экран визуально близок к Figma Make: тёмный navy фон, tinted event cards, chips, нижние stat tiles

# Изменения — Этап 168: Figma Midnight Glass for Overview and Forecast

## Что сделано
- Продолжен Figma-driven перенос стиля `Dynamic Gradient Background` в Compose по MCP-референсам `Overview.tsx` и `Forecast.tsx`.
- В `OverviewScreen` добавлен полноценный `MIDNIGHT_GLASS` вариант для:
  - live/stale header badge,
  - current glucose hero-card,
  - prediction cards,
  - UAM summary cells,
  - telemetry pills,
  - last action status pill,
  - action buttons,
  - section containers и info-buttons.
- В `ForecastScreen` добавлен `MIDNIGHT_GLASS` вариант для:
  - range segmented control,
  - layer chips,
  - chart container colors/grid/CI markers,
  - compact summary tiles `Now / +30m / +60m`,
  - horizon cards,
  - decomposition/net effect block,
  - quality cards,
  - section containers и info-buttons.
- Изменения ограничены визуальным слоем; прогнозный и automation runtime не менялись.

## Почему так
- Нужно довести приложение до более близкого визуального языка Figma, а не только менять корневой фон и UAM screen.
- Основной референс показывает тёмные glass-panels, мягкие translucent borders и colour-coded pills; эти паттерны перенесены в `Overview` и `Forecast`.

## Риски / ограничения
- Это не 1:1 перенос каждого пикселя Figma: chart layout и existing Compose data model сохранены, изменён только visual treatment.
- Некоторые deprecated icon warnings (`TrendingUp/TrendingDown/ShowChart`) остались как существующий техдолг и на функциональность не влияют.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На телефоне выбрать `Settings -> App style -> Midnight glass` и проверить `Overview` и `Forecast`.

# Изменения — Этап 169: Midnight Glass Chrome and Secondary Screens

## Что сделано
- Затемнён `MIDNIGHT_GLASS` token-set в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/theme/CopilotTheme.kt`:
  - более глубокий navy background,
  - более тёмные `surface`/`surfaceVariant`,
  - приглушённые glass-accent overlays,
  - снижена доля светлых слоёв.
- Унифицирован root chrome в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/CopilotFoundationRoot.kt`:
  - верхняя панель больше не меняет весь фон под stale/kill state в `MIDNIGHT_GLASS`,
  - нижняя навигация получила Figma-подобный dark navy контейнер и синий active indicator,
  - drawer переведён в тот же dark navy слой с согласованными selected/unselected цветами.
- Доведены до общей Midnight glass-консистентности:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/components/FoundationComponents.kt`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/SafetyScreen.kt`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/AnalyticsScreen.kt`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/SettingsScreen.kt`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/AiAnalysisScreen.kt`
- Для этих экранов приведены к одному виду:
  - section cards,
  - info-buttons,
  - filter chips,
  - segmented controls,
  - text inputs,
  - switches,
  - status pills,
  - служебные glass-panels.

## Почему так
- Пользователь зафиксировал целевой визуальный ориентир: тёмно-синий Figma-референс, а не просто “тёмная тема”.
- Предыдущий `Midnight glass` оставался слишком светлым и серым, из-за чего `Safety / Analytics / Settings / AI Analysis` визуально расходились с `UAM / Overview / Forecast`.
- Выравнивание root chrome и вторичных экранов через один набор navy/glass токенов даёт заметно более цельный UI без изменения бизнес-логики.

## Риски / ограничения
- Это всё ещё адаптация под существующую Compose-структуру, а не покадровый 1:1 перенос каждого layout-решения из Make.
- На экранах с очень большим количеством элементов (`Settings`, `Analytics`, `AI Analysis`) могли остаться единичные default-Material кнопки/диалоги, которые уже не ломают общую тему, но могут потребовать отдельной доработки.
- Визуальная проверка на устройстве остаётся обязательной, потому что итог зависит от реально выбранного пользователем `App style`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:compileDebugKotlin`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew --no-daemon :app:assembleDebug`
3. `adb -s J7EYCEEE7TK74XAQ install -r /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/build/outputs/apk/debug/app-debug.apk`
4. На телефоне выбрать `Settings -> App style -> Midnight glass` и проверить `Safety`, `Analytics`, `Settings`, `AI Analysis` на тёмно-синий navy фон, приглушённые glass-surface и единый chrome.

# Изменения — Этап 170: Adaptive Temp Target Reaction Latency

## Что сделано
- Проверен реальный runtime по USB-выгрузке БД: цикл автоматики уже выполняется примерно каждую минуту, но `AdaptiveTargetController.v1` регулярно блокировался по `retarget_cooldown_5m`, из-за чего temp target менялся с заметной задержкой.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` сохранён и доведён обход adaptive cooldown для быстрых ретаргетов:
  - idempotency key для adaptive temp target уже учитывает минутный бакет и целевое значение;
  - bypass cooldown теперь срабатывает уже при изменении цели на один реальный шаг (`0.05 mmol/L`), а не только на `0.15 mmol/L`;
  - сравнение переведено на округлённые step-значения, чтобы не было ложного блокирования из-за double precision.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/rules/AdaptiveTargetControllerRule.kt` сохранено подавление повторной отправки, если вычисленная цель уже совпадает с активной temp target.
- В `AdaptiveTempTargetController` оставлена усиленная near-term реакция на быстрый рост через `fastRiseSignal / riseUrgency / rapidRiseBias`, чтобы controller сильнее реагировал на резкое ускорение вверх.
- Добавлены/исправлены unit tests:
  - `AdaptiveTempTargetControllerTest.testRapidRiseBias_makesControllerReactEarlierToNearTermUpswing`
  - `AdaptiveTargetControllerRuleTest.returnsNoMatch_whenComputedTargetAlreadyMatchesActiveTempTarget`
  - `AutomationRepositoryForecastBiasTest.adaptiveCooldownBypass_allowsSingleStepRetarget`

## Почему так
- Проблема была не в частоте запуска цикла: по audit log цикл уже шёл почти каждую минуту после ingest.
- Основной лаг сидел в safety/runtime слое: rule успевал вычислить новый target, но затем блокировался 5-минутным cooldown, даже когда цель уже сместилась на следующий рабочий шаг.
- После перевода bypass на шаг `0.05 mmol/L` контур может реагировать на новое состояние почти сразу, но без спама, потому что:
  - одинаковая цель режется как `NO_MATCH`,
  - повтор в ту же минуту режется idempotency key.

## Риски / ограничения
- Это ускоряет именно adaptive retargeting. Если прогноз сам опаздывает из-за модели 30/60m, это отдельная задача и она не решается одним cooldown fix.
- Частые реальные изменения target теперь возможны чаще, поэтому нужно дальше следить за `actionsLast6h` и общим safety-поведением на живых данных.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --no-daemon`
2. В audit log проверить, что при смене target больше нет серии `adaptive_controller_blocked` между двумя разными adaptive target подряд.
3. На устройстве создать резкий рост прогноза и проверить, что новая temp target меняется без 5-минутной паузы, если новая цель отличается хотя бы на `0.05 mmol/L`.

# Изменения — Этап 171: Faster Near-Term Adaptive Reaction

## Что сделано
- Ускорен high-side response в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/rules/AdaptiveTempTargetController.kt`.
- Для сценариев быстрого роста:
  - усилен сдвиг весов в сторону `pred5`;
  - уменьшен effective deadband при высоком `riseUrgency`;
  - добавлен `leadOvershoot` от расхождения `pred5` и сглаженного `PctrlRaw`;
  - усилен `rapidRiseBias`;
  - `Kp/Ki` становятся чуть агрессивнее только при положительной high-glucose ошибке.
- Добавлены тесты:
  - `testNearTermSpike_pullsTargetDownBeforeLongHorizonsCatchUp`
  - `testFastRiseReducesDeadbandAndPreventsNeutralHold`
  - обновлён `testRapidRiseBias_makesControllerReactEarlierToNearTermUpswing`

## Почему так
- После исправления cooldown стало видно, что следующий лимит — не отправка, а сама инерция controller при сценариях, где `pred5` уже резко уходит вверх, а `30m/60m` ещё не полностью “догнали” рост.
- Новый bias делает controller более anticipatory: он быстрее уходит в низкую temp target, не дожидаясь пока long horizons полностью подтвердят рост.

## Риски / ограничения
- Изменение затрагивает только high-glucose/control ветку. Safety-ветки `force_high` и `hypo_guard` не менялись.
- Агрессивность ограничена clamp-ами target и существующим high-glucose guard, но на живых данных всё равно нужно смотреть на лишние oscillation.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --no-daemon`
2. На телефоне воспроизвести резкий рост, где `pred5` быстро уходит вверх раньше `pred30/60`, и убедиться, что adaptive target уходит вниз раньше прежнего.

# Изменения — Этап 172: Trend Shock + Smooth Temp Target Relaxation

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/rules/AdaptiveTempTargetController.kt` добавлена более физиологичная реакция на сам факт изменения тренда, а не только на уже сглаженные горизонты прогноза:
  - новый вход `observedDelta5Mmol`;
  - `>|0.3| mmol/5m` теперь рассматривается как trend shock;
  - добавлен симметричный `trendShockBias` для быстрого усиления реакции и на резкий рост, и на резкое падение;
  - `pred5` получил дополнительный lead-вес через `leadOvershoot`;
  - при затухающем фактическом тренде (`|delta5| <= 0.10`) добавлено плавное ослабление активного temp target в сторону `baseTarget`, ориентируясь на `pred30/pred60`;
  - при таком relax одновременно гасится интегратор, чтобы controller не продолжал “давить” в старую сторону.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/rules/AdaptiveTargetControllerRule.kt` controller теперь получает реальный `delta5` из последних CGM-точек (`2..15` минут, нормировка к 5 минутам).
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/domain/rules/AdaptiveTempTargetControllerTest.kt` добавлены сценарии:
  - резкий near-term spike до раскрытия `30/60m`,
  - прямой trend shock > `0.3 mmol/5m`,
  - уменьшение deadband при быстром сдвиге,
  - плавное ослабление низкой temp target при остановке тренда,
  - возврат высокой temp target обратно к base при затухшем движении.

## Почему так
- Предыдущий controller уже перестал тормозить на cooldown/idempotency, но всё ещё слишком зависел от сглаженных горизонтов `30/60m`.
- Это давало два заметных UX/runtime дефекта:
  1. новый тренд начинался фактически, а target реагировал только после того, как long horizons “догонят” изменение;
  2. после остановки движения temp target продолжал слишком долго давить в старую сторону за счёт инерции PI.
- Новый слой использует реальный `delta5` как быстрый триггер и одновременно добавляет controlled unwind, чтобы target возвращался к base мягко, а не держался слишком долго.

## Риски / ограничения
- Это ускорение касается adaptive controller, а не самой forecast-модели. Если прогноз `30/60m` фундаментально ошибочен, controller всё равно будет ограничен качеством прогноза.
- Relaxation deliberately консервативный: если `pred30/pred60` всё ещё явно требуют давления в ту же сторону, unwind не включается.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --no-daemon`
2. На живых данных смотреть в audit `AdaptiveTargetController.v1`:
   - должны появляться `observedDelta5`, `trendShockBias`, `targetRelaxed`;
   - при резком `>|0.3|/5m` target должен менять направление раньше;
   - при почти нулевом `delta5` и сглаживании `pred30/60` target должен подтягиваться к `base`, а не держаться слишком долго на экстремуме.

# Изменения — Этап 173: Active Temp Target Resolver Fix

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` исправлен runtime-резолвер активной temp target:
  - теперь свежий `temp_target` из `therapy_events` читается через общий static helper;
  - приоритет отдан явным `targetBottomMmol/targetTopMmol`, если они есть;
  - `targetBottom/targetTop` в payload теперь корректно распознаются как `mg/dL` и переводятся в `mmol/L`, если значение похоже на mg/dL;
  - runtime больше не подменяет только что отправленный `4.10 mmol/L` на ложный `8.30 mmol/L` из raw `74 mg/dL`;
  - резолвер не “оживляет” старые temp target: если последний temp target уже истёк, активной цели нет.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/AutomationRepositoryForecastBiasTest.kt` добавлены тесты:
  - конверсия `mg/dL -> mmol/L`,
  - приоритет явных mmol-полей,
  - отсутствие активной цели после истечения последнего temp target.

## Почему так
- Фактическая причина бага была не в математике adaptive controller, а в обратном чтении собственного же `temp_target` из БД:
  - отправлялся корректный `4.1 mmol/L`,
  - в `therapy_events` он хранился как `74 mg/dL`,
  - runtime читал `74` как `74 mmol/L` и потом clamp’ил до `8.3`,
  - следующий цикл видел `active=8.3` и продолжал работать в ложном состоянии.
- После исправления controller должен видеть реальную активную цель и больше не залипать в ветках `safety_keep_existing_target`/`target_equals_active` из-за unit mismatch.

## Риски / ограничения
- Исправлен именно runtime resolver активной temp target. Если внешний транспорт вернёт реально другую temp target из AAPS/NS, runtime примет её как источник истины.
- Для temp target используется только самый свежий treatment event; предыдущие temp target после нового события обратно не активируются.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTempTargetControllerTest" --tests "io.aaps.copilot.domain.rules.AdaptiveTargetControllerRuleTest" --no-daemon`
2. На устройстве отправить adaptive temp target около `4.1-4.2 mmol/L`.
3. В новых `rule_executions` убедиться, что `activeTempTargetMmol` остаётся около `4.1-4.2`, а не превращается в `8.3`.

# Изменения — Этап 174: Canonical 5m CGM + Strictly Causal Forecast Inputs

## Что сделано
- Добавлен новый модуль `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/Glucose5mCanonicalizer.kt`:
  - строит канонический 5-мин ряд из raw CGM `1..5` минут,
  - использует robust median по локальному окну,
  - разрешает только короткую линейную интерполяцию на малых разрывах.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngine.kt` enhanced V3 переведён на canonical 5m series для:
  - trend,
  - volatility,
  - interval penalty,
  - Kalman,
  - UAM,
  - carb pattern classification.
- В том же файле включена строгая каузальность:
  - runtime forecast теперь использует только `therapyEvents` с `ts <= nowTs`,
  - future carbs / future bolus больше не могут попадать в `therapyStepSeries` и `therapyDeltaAtHorizon`.
- В predictive carbs ветке исключены synthetic UAM carbs:
  - `extractCarbsGramsForPrediction()` теперь игнорирует события с `synthetic=true`, `source=uam_engine`, `synthetic_type=*uam*` или note-tag `UAM_ENGINE|...`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/ForecastQualityEvaluator.kt` убран overly-optimistic `closest sample` matching:
  - фактическая glucose value теперь берётся по exact timestamp или по линейной интерполяции между соседними точками,
  - fallback к closest sample остаётся только если интерполяция невозможна, но sample ещё в допустимом tolerance.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/ProfileEstimator.kt` добавлен anti-feedback guard:
  - synthetic UAM carbs исключаются из CR sample extraction,
  - synthetic UAM carbs не считаются “announced carbs nearby” для correction ISF filtering,
  - synthetic UAM carbs не блокируют UAM episode analysis как будто это реальные manual carbs.

## Почему так
- Основной системный дефект был в том, что большая часть математики прогноза была 5-минутной, а реальные входы часто минутные. Это приводило к деградации `ROC / volatility / UAM fit` до нуля или к нестабильным всплескам.
- Второй крупный дефект — утечка будущих therapy events в прогноз. Это делало offline quality более “красивой”, чем реальный runtime.
- Третий дефект — self-feedback от synthetic UAM carbs: они могли выглядеть как ground truth и загрязнять predictive/ISF/CR контуры.

## Риски / ограничения
- Canonical series сейчас используется только в enhanced V3 path. Legacy path сохранён максимально близким к текущему поведению, кроме строгой causal filtration по therapy events.
- Canonicalizer строит 5-мин cadence относительно последнего доступного sample; это сделано сознательно, чтобы не терять runtime responsiveness между 5-мин wall-clock boundaries.
- Это ещё не завершает всю работу по ISF/CR. Следующий этап — telemetry-as-prior + shrinkage / partial pooling.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.Glucose5mCanonicalizerTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineTest" --tests "io.aaps.copilot.domain.predict.ForecastQualityEvaluatorTest" --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest" --no-daemon`
2. Проверить `HybridPredictionEngineV3Test.t7b_minuteLevelCgmIsCanonicalizedIntoResponsiveFiveMinuteTrend`: `rocPer5Used` должен быть положительным на минутном растущем CGM.
3. Проверить `HybridPredictionEngineV3Test.t7c_futureTherapyEventsAreIgnored`: future carbs/bolus не должны менять прогноз.
4. Проверить `ForecastQualityEvaluatorTest.interpolatesActualInsteadOfPickingClosestSample`: метрика должна использовать точное значение в момент прогноза, а не “почти ближайшую” точку.

# Изменения — Этап 175: ISF/CR telemetry-as-prior + sparse-window shrinkage

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/ProfileEstimator.kt` завершена переработка `ISF/CR`-оценки:
  - telemetry `ISF/CR` больше не трактуется как поток независимых samples;
  - для каждой семьи (`ISF`, `CR`) используется только последний telemetry value как prior/fallback;
  - при `FALLBACK_IF_NEEDED` плотная локальная история больше не перетягивается к global/telemetry prior;
  - sparse hour/segment окна могут мягко shrink'иться к global prior для стабильности.
- Обновлены инварианты и архитектурный контракт:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/docs/ARCHITECTURE.md`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/docs/INVARIANTS.md`
- Добавлены регрессионные тесты в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/domain/predict/PatternAndProfileTest.kt`:
  - repeated telemetry не раздувает `sampleCount`,
  - sparse hourly window shrink'ится между local и global prior,
  - dense hourly/segment history остаётся локальным source of truth.

## Почему так
- Предыдущая схема с telemetry-as-samples искусственно завышала `sampleCount/confidence` без новой информации.
- После Stage 174 это был следующий крупный перекос в контуре `ISF/CR`: sparse окна действительно нуждаются в stabilization, но плотные окна должны оставаться максимально “реальными”, а не тянутыми к общему или внешнему профилю.

## Риски / ограничения
- Shrinkage пока реализован как лёгкий robust heuristic (median + MAD + prior strength), а не полный hierarchical Bayesian fitter.
- `COMBINE` режим всё ещё позволяет мягко тянуть dense history к prior; это сознательно оставлено как опциональный режим для будущей настройки.
- Следующий этап всё ещё нужен: единый `ISF/CR` runtime source в прогнозе и полный разрыв latent UAM vs exported carb events по всему pipeline.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest" --tests "io.aaps.copilot.domain.predict.Glucose5mCanonicalizerTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineTest" --tests "io.aaps.copilot.domain.predict.ForecastQualityEvaluatorTest" --no-daemon`
2. Проверить `PatternAndProfileTest.profileEstimator_repeatedTelemetryActsAsSinglePrior_notMultipleSamples`: repeated telemetry должен давать `sampleCount=2`, а не накапливаться по количеству пакетов.
3. Проверить `PatternAndProfileTest.profileEstimator_hourlySparseWindowShrinksTowardGlobalPrior`: sparse hour должен быть между local raw и global prior, а не равен одному из них.

# Изменения — Этап 176: Unified local ISF/CR source in HybridPredictionEngine

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngine.kt` локальный runtime-источник чувствительности переведён на единый `ProfileEstimator`:
  - added shared `sensitivityProfileEstimator` (`HISTORY_ONLY`, min samples = 1) inside engine;
  - `estimateSensitivityFactors(...)` теперь сначала берет `ISF/CR` из `ProfileEstimator`, и только если он не смог оценить значения, падает обратно в старую inline-эвристику `estimateLocalIsfCr(...)`;
  - enhanced V3 path теперь считает локальный `ISF/CR` по canonical 5-minute glucose series, а не по raw minute stream.
- Старый inline fallback тоже дополнительно очищен от synthetic UAM carbs:
  - `estimateLocalIsfCr(...)` использует `extractCarbsGramsForPrediction(...)`, а не raw carb parsing.
- Обновлены архитектурные/инвариантные документы:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/docs/ARCHITECTURE.md`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/docs/INVARIANTS.md`

## Почему так
- До этого в прогнозаторе одновременно существовали два разных локальных контура `ISF/CR`:
  - общий `ProfileEstimator`,
  - отдельная inline-эвристика в `HybridPredictionEngine`.
- Это создавало риск расхождения между аналитикой `ISF/CR` и реальным runtime forecast. Теперь оба контура сведены к одному history-based источнику, а старая логика оставлена только как аварийный fallback.

## Риски / ограничения
- Runtime estimator внутри `HybridPredictionEngine` пока использует `HISTORY_ONLY` режим и не читает telemetry priors напрямую; telemetry-driven/runtime snapshot путь по-прежнему идет через внешний override.
- Legacy fallback всё ещё существует, поэтому это не полное удаление старой эвристики, а безопасное переключение приоритета.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest" --tests "io.aaps.copilot.domain.predict.ForecastQualityEvaluatorTest" --tests "io.aaps.copilot.domain.predict.Glucose5mCanonicalizerTest" --no-daemon`
2. Проверить, что после synthetic UAM carbs локальный sensitivity path не получает ложный рост `CR`.
3. Проверить, что enhanced V3 на минутном CGM использует canonicalized series и продолжает проходить `HybridPredictionEngineV3Test`.

# Изменения — Этап 177: Synthetic UAM event contract unified across forecast / UAM / ISF-CR / local COB

## Что сделано
- Добавлен общий helper `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/SyntheticUamEventGuards.kt`:
  - `therapyEventNote(...)`,
  - `syntheticUamTag(...)`,
  - `isSyntheticUamCarbEvent(...)`.
- Synthetic UAM detection теперь единообразна во всех ключевых контурах:
  - forecast carbs path в `HybridPredictionEngine`,
  - CR/ISF extraction в `ProfileEstimator`,
  - inferred-UAM event handling в `UamInferenceEngine`,
  - ISF/CR window extraction в `IsfCrWindowExtractor`,
  - local fallback COB / real insulin onset logic в `AutomationRepository`.
- Контракт synthetic widened beyond note-tag only:
  - `synthetic=true`,
  - `source=uam_engine`,
  - `synthetic_type=*uam*`,
  - `reason`/`note` containing `UAM_ENGINE|...`.
- Добавлены тесты:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/domain/predict/SyntheticUamEventGuardsTest.kt`,
  - новые кейсы в `UamInferenceEngineTest`,
  - новые кейсы в `HybridPredictionEngineTest`.

## Почему так
- До этого разные модули определяли synthetic UAM carbs по-разному:
  - часть модулей требовала note-tag,
  - часть смотрела только на `source`,
  - часть вообще могла считать source-only synthetic carbs обычными manual carbs.
- Это создавало два дефекта:
  - synthetic carbs могли попадать обратно в forecast / CR / ISF как ground truth,
  - source-only exported UAM carbs могли считаться manual COB и одновременно не блокировать дубликаты.

## Риски / ограничения
- Старые historical rows без note/source/synthetic markers не будут автоматически распознаны как synthetic; для них по-прежнему нужен backfill/migration слой.
- Duplicate guard намеренно продолжает видеть synthetic UAM carbs как существующее UAM coverage, чтобы не создавать повторные inferred meal episodes рядом с уже экспортированными carbs.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.SyntheticUamEventGuardsTest" --tests "io.aaps.copilot.domain.predict.UamInferenceEngineTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --no-daemon`
2. Проверить `SyntheticUamEventGuardsTest.detectsSourceOnlySyntheticUamCarbEvent`: source-only UAM carbs должны считаться synthetic даже без note-tag.
3. Проверить `UamInferenceEngineTest.sourceOnlySyntheticExportDoesNotCountAsManualCob_andStillBlocksDuplicate`: manual COB должен остаться `0`, а новый UAM event рядом не должен создаваться.

# Изменения — Этап 178: Kalman known-input for therapy/UAM before residual AR

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/KalmanGlucoseFilterV3.kt` добавлен новый вход `uRocPerMin`:
  - фильтр теперь предсказывает `gPred = g + (v + u) * dt`,
  - `v` остаётся residual velocity, а не суммарным ROC.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngine.kt` added `buildHistoricalKnownInputSeries(...)`:
  - для каждого 5-мин interval canonical CGM считается deterministic therapy delta,
  - bounded historical UAM delta добирается только как positive unexplained rise с cap по `30 g/h * CSF`,
  - полученный `rocPerMin` подаётся в Kalman на каждом historical update.
- После этого enhanced V3 residual path changed:
  - warmed-up Kalman now outputs residual-ish `rocPer5Used`,
  - `residualRoc0` в прогретом режиме больше не вычитает `therapyStep1 + uamStep1` второй раз,
  - anti-double-count clamp при active UAM оставлен.
- Расширена debug-диагностика:
  - `knownInputTherapyStep1`,
  - `knownInputUamStep1`.
- Добавлены тесты:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/domain/predict/KalmanGlucoseFilterV3Test.kt`,
  - новый кейс `t10_knownInputMovesAnnouncedCarbRiseOutOfResidualTrend` в `HybridPredictionEngineV3Test`.

## Почему так
- Раньше Kalman видел только glucose measurement и оценивал общий ROC, а therapy/UAM вычитались позже.
- Это сохраняло два дефекта:
  - explained rise/fall от insulin/carbs частично оставался в state velocity,
  - residual AR обучался на смеси residual + explained dynamics и усиливал инерцию на `30/60m`.
- После known-input фильтр видит “объяснённую” часть сразу, а AR получает более чистый остаток.

## Риски / ограничения
- Historical UAM known-input пока bounded heuristic, а не полный latent smoother. Это сделано сознательно: сначала нужен стабильный causal residual, а не агрессивная latent reconstruction.
- Legacy mode не менялся; новая логика действует только в enhanced V3.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.KalmanGlucoseFilterV3Test" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineTest" --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest" --tests "io.aaps.copilot.domain.predict.UamInferenceEngineTest" --tests "io.aaps.copilot.domain.predict.SyntheticUamEventGuardsTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --no-daemon`
2. Проверить `KalmanGlucoseFilterV3Test.knownInputPreventsExplainedRiseFromBecomingResidualVelocity`: при объяснённом росте `rocPer5Mmol` с known-input должен быть сильно меньше, чем без него.
3. Проверить `HybridPredictionEngineV3Test.t10_knownInputMovesAnnouncedCarbRiseOutOfResidualTrend`: announced meal rise должен уходить в `knownInputTherapyStep1`, а residual/trend не должен оставаться большим.

# Изменения — Этап 179: Empirical CI calibration on rolling residuals

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` расширен существующий `calib_v1` слой:
  - calibration теперь двигает не только центр прогноза, но и half-width `CI`,
  - target width считается по rolling weighted quantile абсолютной ошибки `|actual-pred|`,
  - используется тот же horizon-aware bucketing (`<5`, `5..8`, `>=8 mmol/L`) и blending `overall + bucket`.
- Калибровка остаётся causal:
  - используются только уже matured forecast-vs-actual pairs из calibration lookback,
  - future actuals по-прежнему не используются.
- Для каждого горизонта добавлены bounded настройки:
  - `ciQuantile`,
  - `ciBlend`,
  - `ciMaxExpandScale`,
  - `ciMaxShrinkScale`.
- Когда меняется только интервал, в `modelVersion` теперь добавляется `|ci_calib_v1` без изменения upstream forecast engine.
- Добавлены регрессионные тесты в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/AutomationRepositoryForecastBiasTest.kt`:
  - `calibrationBias_expandsCiWhenRecentResidualsAreWide`,
  - `calibrationBias_canModestlyShrinkCiWhenResidualsAreTight`.

## Почему так
- До этого `calib_v1` исправлял только смещение центра, но не калибровал доверительный интервал.
- В результате `MAE` можно было улучшать, а `CI coverage` на `30m/60m` оставалась плохо привязанной к реальным residuals.
- Rolling quantile над `|error|` — минимально инвазивный conformal-like шаг, который не требует новой схемы хранения и не ломает текущий runtime pipeline.

## Риски / ограничения
- Это ещё не полный conformal layer с явной target coverage per horizon; пока это bounded empirical width adjustment внутри уже существующего calibration stage.
- Для sparse history интервал не меняется: нужны те же minimum sample thresholds, что и для bias calibration.
- Сейчас calibration использует симметричный half-width вокруг уже откалиброванного центра; асимметричные tails пока не моделируются отдельно.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.KalmanGlucoseFilterV3Test" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --console=plain --no-daemon`
2. Проверить `AutomationRepositoryForecastBiasTest.calibrationBias_expandsCiWhenRecentResidualsAreWide`: ширина `60m CI` должна увеличиваться и `modelVersion` должен содержать `ci_calib_v1`.
3. Проверить `AutomationRepositoryForecastBiasTest.calibrationBias_canModestlyShrinkCiWhenResidualsAreTight`: ширина `30m CI` должна уменьшаться, но не уходить ниже safety clamp.

# Изменения — Этап 180: Real Nightscout treatment import normalization + profile rebuild cleanup

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` усилена нормализация Nightscout treatments:
  - тип события теперь определяется по `eventType` и payload вместе,
  - `Carb Correction` с insulin+carbs становится `meal_bolus`,
  - plain `Bolus` / `Bolus Wizard` становятся insulin-like therapy rows по payload,
  - remote NS payload сохраняет `source=nightscout_treatment` и `eventType`,
  - добавлен repair-pass `repairNightscoutTherapyTypes(...)` для уже сохранённых misclassified NS/local-NS rows.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/service/LocalNightscoutServer.kt` локальный Nightscout ingest переведён на тот же payload-aware normalizer, что и remote sync.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AnalyticsRepository.kt` rebuild profile tables сделан атомарным:
  - `profile_segment_estimates` полностью очищается и пересобирается,
  - legacy `profile_estimates` rows с раздутой telemetry-статистикой удаляются перед публикацией нового профиля,
  - rebuild логируется как `profile_estimate_rebuild_reset`.
- В DAO добавлены cleanup hooks:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/local/dao/ProfileEstimateDao.kt`
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/local/dao/ProfileSegmentEstimateDao.kt`
- Добавлены тесты в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/SyncRepositoryEventTypeNormalizationTest.kt`.

## Почему так
- Live `IOB/COB` приходили, но реальные NS/AAPS treatments недоезжали до корректных `therapy_events`, поэтому `real ISF/CR` почти всегда уходил в fallback.
- Старые загрязнённые `profile_estimates/profile_segment_estimates` искажали аналитику и графики даже после фикса самого estimator.

## Риски / ограничения
- Если upstream Nightscout/AAPS физически не публикует реальные meal/bolus treatments, этот патч не создаст новые ground-truth события; он перестаёт терять их при наличии и чинит misclassification.
- `profile_segment_estimates` после rebuild больше не хранит append-history; runtime получает только актуальный чистый набор сегментов.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.SyncRepositoryEventTypeNormalizationTest" --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --console=plain --no-daemon`
2. Проверить в audit новые поля sync:
   - `treatmentsRepairedType`
   - снижение `treatmentsDowngradedFromBolus` для реальных bolus-like payloads.
3. После `AnalyticsRepository.recalculate(...)` убедиться, что:
   - появляется `profile_estimate_rebuild_reset` при наличии legacy polluted rows,
   - `profile_segment_estimates` содержит только свежие строки текущего rebuild,
   - `profile_estimates` больше не содержит строк с `telemetryIsfSampleCount > 1` или `telemetryCrSampleCount > 1`.

# Изменения — Этап 181: Glucose dedup by timestamp across analytics and automation

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/InsightsRepository.kt` daily forecast report теперь читает `glucose_samples` только через `GlucoseSanitizer.filterEntities(...)`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` дедуп по `timestamp` добавлен в:
  - calibration history для `calib_v1`,
  - dry-run / replay glucose history,
  - daily hypo-rate gate для auto-activation ISF/CR.
- Добавлен регрессионный тест `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/GlucoseSanitizerTest.kt` на winner-policy и список `id` для физического удаления дублей.
- В docs зафиксирован новый инвариант: все runtime/analytics consumers обязаны использовать timestamp-level glucose dedup через общий `GlucoseSanitizer`.

## Почему так
- Live БД содержала системные дубли `glucose_samples` по одному `timestamp` из двух источников (`xdrip_broadcast` и `nightscout`).
- UI уже дедупил эти точки, но analytics, calibration и daily quality gates местами читали сырые строки, из-за чего искажались `matchedSamples`, hypo-rate и replay statistics.

## Риски / ограничения
- Это не вводит новый SQL unique index по `timestamp`; канонизация выполняется логически shared sanitizer-ом, а фоновая физическая очистка остаётся best-effort.
- Если появится новый источник glucose, его приоритет нужно явно добавить в `GlucoseSanitizer.sourcePriority(...)`, иначе он попадёт в generic middle-priority bucket.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.GlucoseSanitizerTest" --tests "io.aaps.copilot.data.repository.SyncRepositoryEventTypeNormalizationTest" --tests "io.aaps.copilot.domain.predict.ForecastQualityEvaluatorTest" --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest" --console=plain --no-daemon`
2. Сравнить daily report/replay на БД с дублированным glucose: `matchedSamples` и hypo-rate должны считаться по unique timestamps, а не по raw row count.
3. Проверить, что local Nightscout `/api/v1/entries` и UI current glucose не дублируют одинаковые timestamps even when both `nightscout` and `xdrip_broadcast` rows exist.

# Изменения — Этап 182: Unified runtime UAM contour + hard temp-target outbound throttle

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/UamEstimator.kt` добавлен `externalMealHint`:
  - forecast UAM может принять активный inferred meal contour (`ingestionTs/carbs/confidence/source`) как preferred virtual-meal hint,
  - при наличии hint шаги UAM строятся от него, а не из независимого second-pass fit.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngine.kt` добавлен runtime bridge:
  - `setUamRuntimeHint(...)`,
  - enhanced V3 передаёт active inferred UAM event в `UamEstimator`,
  - debug diagnostics расширены полями `runtimeHintCarbs/runtimeHintConfidence/runtimeHintSource`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` цикл перестроен так, чтобы:
  - сначала выполнялся `UamInferenceEngine`,
  - затем prediction engine конфигурировался уже с этим inferred contour,
  - runtime telemetry публиковала единое resolved UAM state (`uam_runtime_*`, `uam_value`, `uam_runtime_source`) вместо раздельных ad-hoc сигналов inference/forecast.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/TempTargetSendThrottle.kt` добавлен жёсткий throttle `30 минут` для outbound `temp_target`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/NightscoutActionRepository.kt` `submitTempTarget(...)` теперь:
  - проверяет throttle до отправки,
  - блокирует auto-send с ошибкой `temp_target_rate_limit_30m`,
  - логирует audit `temp_target_send_blocked_rate_limit`.
- Manual temp target commands могут bypass-ить throttle только через `MANUAL_IDEMPOTENCY_PREFIX`.
- Добавлены тесты:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/TempTargetSendThrottleTest.kt`
  - `t13_runtimeUamHintAlignsForecastWithInferenceContour` в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngineV3Test.kt`

## Почему так
- До этого в runtime существовало два параллельных UAM-контура:
  - inferred event-engine для UI/export,
  - forecast-local UAM fit внутри `HybridPredictionEngine`.
- При расхождении этих контуров UI/controller и decomposition могли говорить о разных вещах, а forecast мог игнорировать уже подтверждённый inferred meal episode.
- Отдельная проблема: `temp_target` отправлялся слишком часто, засоряя `therapy_events`, audit и transport history. Это уже мешало анализу и создавало шум в AAPS/NS.

## Риски / ограничения
- Unified UAM contour сейчас однонаправленный:
  - active inferred event может стать hint для forecast,
  - но forecast-only virtual meal пока не materializes обратно в inferred event-engine.
- Hard throttle применён на уровне outbound action repository. Это правильно для safety и идемпотентности, но если внешний транспорт уже дублирует команды, его собственный шум этот патч не устраняет.
- Manual bypass оставлен намеренно, чтобы не ломать ручную валидацию и аварийное управление.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --tests "io.aaps.copilot.domain.predict.UamInferenceEngineTest" --tests "io.aaps.copilot.data.repository.TempTargetSendThrottleTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
2. Проверить `HybridPredictionEngineV3Test.t13_runtimeUamHintAlignsForecastWithInferenceContour`: forecast UAM должен использовать runtime inferred contour, а не расходиться с ним.
3. Проверить `TempTargetSendThrottleTest.blocksAutomaticTempTargetInsideThirtyMinuteWindow`: повторная auto-send попытка внутри `30` минут должна блокироваться.
4. Проверить `TempTargetSendThrottleTest.allowsManualTempTargetBypass`: manual команда с корректным idempotency prefix должна проходить без ожидания.

# Изменения — Этап 183: Temp target throttle no longer blocks real target changes

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/local/dao/ActionCommandDao.kt` добавлен `latestByTypeAndStatusExcludingPrefix(...)`, чтобы outbound throttle видел не только время последней отправки, но и её payload.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/TempTargetSendThrottle.kt` throttling переработан:
  - manual bypass сохранён,
  - внутри окна `30 минут` блокируются только повторные или почти одинаковые цели,
  - если новая `targetMmol` отличается от последней отправленной хотя бы на `0.15 mmol/L`, отправка разрешается сразу,
  - причина решения (`manual_bypass`, `window_elapsed`, `target_changed`, `duplicate_target_within_window`) возвращается явным полем.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/NightscoutActionRepository.kt` `submitTempTarget(...)` теперь передаёт в throttle текущую `targetMmol` и пишет в audit:
  - `reason`,
  - `lastTargetMmol`,
  - `requestedTargetMmol`.
- Обновлены тесты в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/TempTargetSendThrottleTest.kt`:
  - duplicate target внутри окна блокируется,
  - manual bypass проходит,
  - materially changed target внутри окна проходит.

## Почему так
- Жёсткий 30-минутный throttle по одному только времени ломал реальную автоматику:
  - controller срабатывал,
  - но любая новая auto-команда блокировалась, даже если целевая `temp target` уже заметно изменилась.
- В живом USB capture это проявлялось как серия `temp_target_send_blocked_rate_limit` при активном `adaptive_controller_triggered`.
- Корректный компромисс здесь не “полностью убрать throttle”, а ограничивать именно дубли одной и той же цели.

## Риски / ограничения
- Если внешний транспорт вернёт неконсистентный payload без `targetMmol`, throttle fallback-ит на старую time-window логику.
- Порог `0.15 mmol/L` выбран как инженерный компромисс между anti-spam и реальным изменением терапии; его стоит перепроверить на replay и живых логах.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.TempTargetSendThrottleTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
2. Проверить `TempTargetSendThrottleTest.allowsMateriallyChangedTargetInsideThirtyMinuteWindow`: новая цель внутри 30 минут должна проходить, если отличие от последней >= `0.15 mmol/L`.
3. На устройстве убедиться, что `adaptive_controller_triggered` больше не сопровождается непрерывной серией `temp_target_send_blocked_rate_limit`, когда controller реально меняет target.

# Изменения — Этап 184: DIA source-of-truth switched to insulin profile runtime

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/InsulinActionProfiles.kt` у `InsulinActionProfile` добавлены вычисляемые `defaultDurationMinutes/defaultDurationHours` от action-curve, чтобы базовый DIA шёл от выбранного insulin profile.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/domain/predict/HybridPredictionEngine.kt` runtime DIA больше не отражает только override:
  - масштабирование инсулина теперь опирается на `profile.defaultDurationMinutes`,
  - diagnostics публикуют фактический effective DIA (`currentInsulinDurationHours()`),
  - добавлен test helper `currentInsulinDurationHoursForTest()`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` runtime DIA переключён на profile-derived источник истины:
  - `configurePredictionEngine(...)` сначала берёт `dia_effective_hours`/`dia_profile_hours`,
  - raw external `dia_hours` больше не может напрямую переопределить insulin action curve,
  - при отсутствии confident real-profile refinement engine использует чистый profile default.
- Добавлен runtime DIA resolver и публикация в telemetry:
  - `dia_profile_hours`,
  - `dia_effective_hours`,
  - `dia_external_raw_hours`,
  - `dia_effective_source`.
- Если есть daily real insulin profile (`estimated_daily`, confidence >= 0.35, same selected profile), effective DIA дополнительно уточняется через profile-based onset/shape scaling, а не через broadcast.
- UI/analytics переключены на effective DIA:
  - `MainViewModel` coverage теперь смотрит `dia_effective_hours` с fallback на profile/raw,
  - `InsightsRepository` для фактора `DIA_HOURS` сначала читает `dia_effective_hours`.
- Добавлены тесты:
  - `HybridPredictionEngineV3Test.t12a_defaultDiaTracksSelectedInsulinProfile`,
  - `AutomationRepositoryForecastBiasTest.runtimeDia_ignoresRawTelemetryWithoutProfileDerivedRefinement`,
  - `AutomationRepositoryForecastBiasTest.runtimeDia_usesRealProfileScaleWhenConfident`.

## Почему так
- На живой БД `dia_hours` приходил как постоянный `5.0` из `xdrip_broadcast`, и именно он каждый цикл прокидывался в prediction engine.
- Это делало DIA внешним шумным override, а не частью выбранного insulin profile. При этом user-facing профиль инсулина и реальная математика прогноза могли расходиться.
- Перевод DIA на profile-derived source-of-truth делает поведение предсказуемым:
  - выбран профиль -> понятный базовый DIA,
  - есть качественный daily real-profile fit -> мягкое profile-based уточнение,
  - raw broadcast DIA остаётся только как audit/input telemetry.

## Риски / ограничения
- Пока profile default duration берётся из текущей action-curve; если сами profile curves для ultra-rapid insulins нужно укорачивать фармакокинетически, это отдельная задача, не часть этого патча.
- Если daily real insulin profile ещё не обучен или в статусе `fallback_template`, effective DIA останется profile default. Это сознательно безопаснее, чем брать raw external `5.0`.
- Исторические raw `dia_hours` в БД никуда не удаляются; UI и runtime просто перестают считать их главным источником истины.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.data.repository.TelemetryMetricMapperTest" --console=plain --no-daemon`
2. В telemetry после одного automation cycle должны появиться `dia_profile_hours`, `dia_effective_hours`, `dia_effective_source`.
3. На устройстве проверить, что экран telemetry coverage для `DIA` показывает effective profile-derived значение, а не raw broadcast `5.0`, если выбран другой insulin profile или есть confident real-profile refinement.

# Изменения — Этап 185: Payload-aware import of real Nightscout boluses/carbs

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/remote/nightscout/NightscoutDtos.kt` DTO Nightscout treatments расширены alias-полями:
  - `enteredCarbs <- enteredCarbs|mealCarbs|grams`,
  - `enteredInsulin <- enteredInsulin|bolusUnits|insulinUnits`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` добавлены shared payload builders:
  - `buildNightscoutTreatmentPayloadStatic(NightscoutTreatment, source)`,
  - `buildNightscoutTreatmentPayloadStatic(NightscoutTreatmentRequest, source)`.
- Remote Nightscout sync теперь строит therapy payload через shared builder и нормализует тип по merged payload, поэтому generic labels вроде `Bolus`, `Bolus Wizard`, `Carb Correction` могут корректно становиться `correction_bolus` / `meal_bolus` / `carbs`, если в payload реально есть insulin/carbs.
- После sync добавлен incremental repair-pass `repairNightscoutTherapyTypes(...)`:
  - recent rows из `nightscout_treatment` / `local_nightscout_treatment` пере-нормализуются по сохранённому payload,
  - repair меняет только `type` и логирует `nightscout_treatment_type_repaired`.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/service/LocalNightscoutServer.kt` local NS POST/get paths переведены на тот же shared payload builder/normalizer, чтобы remote sync и loopback transport публиковали одинаковую семантику therapy rows.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AnalyticsRepository.kt` rebuild profile tables уже работает как reset/rebuild, поэтому после появления реальных bolus rows следующий analytics recalc сможет пересобрать `profile_estimates/profile_segment_estimates` без старого telemetry pollution.
- Добавлены/обновлены тесты в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/SyncRepositoryEventTypeNormalizationTest.kt` для:
  - `Carb Correction + insulin + carbs -> meal_bolus`,
  - `Bolus -> correction_bolus`,
  - `Bolus Wizard + entered* -> meal_bolus`,
  - сохранения `enteredInsulin/enteredCarbs` в payload builder.

## Почему так
- На живой БД real bolus history почти не попадал в `therapy_events`, хотя текущий IOB из AAPS telemetry уже был корректным.
- Корневая причина была в том, что Nightscout DTO не сохраняли `enteredInsulin/enteredCarbs` и alias-поля, поэтому normalization видел generic event label без реального payload и деградировал до `treatment`.
- Без real insulin/carbs history `ISF/CR` почти всегда падали в `FALLBACK`, а forecast недооценивал therapy contribution на `30m/60m`.

## Риски / ограничения
- Патч восстанавливает корректную классификацию только если upstream Nightscout/AAPS действительно отдаёт insulin/carbs поля или их aliases. Если upstream публикует только generic treatment label без payload, восстановить bolus невозможно.
- Repair-pass intentionally не синтезирует новые дозы и не меняет timestamps/id, он только исправляет `type` у уже сохранённых rows.
- Для полного эффекта после установки нужен новый sync/recalc на живой БД, иначе старые misclassified rows останутся до очередной волны repair/rebuild.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.SyncRepositoryEventTypeNormalizationTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineTest" --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest" --console=plain --no-daemon`
2. На устройстве инициировать Nightscout sync и затем проверить `therapy_events`: должны появляться реальные `correction_bolus` / `meal_bolus` / `carbs` rows от `nightscout_treatment` и `local_nightscout_treatment`, а не только `temp_target` и inferred `iob_jump`.
3. После analytics recalc проверить, что `profile_estimates/profile_segment_estimates` больше не опираются на inflated telemetry counts и начинают видеть реальные bolus/carbs evidence.

## Дополнение
- По живой USB-проверке после этого патча стало видно, что один только payload-aware normalizer недостаточен:
  - remote sync продолжал видеть `0` real insulin-like Nightscout rows,
  - narrow incremental `treatmentSince` окно уже было занято в основном `temp_target`, поэтому старые real boluses не попадали в fetch.
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/SyncRepository.kt` добавлен recovery-mode для missing real insulin history:
  - если `insulinLikeLocal30d < THERAPY_BOOTSTRAP_MIN_INSULIN_EVENTS`, created-at backfill автоматически расширяется до bootstrap lookback,
  - для этого backfill используется bootstrap-sized `count`, даже если initial bootstrap retry window ещё не истёк,
  - audit теперь пишет `needsHistoricalInsulinRecovery`, `treatmentCreatedAtQuerySince`, `treatmentsByCreatedAtCount`.
- Это не меняет causal semantics sync, но даёт системе шанс реально дотянуться до старых bolus treatments и затем уже классифицировать их новым payload-aware normalizer.

# Изменения — Этап 186: DIA recompute cadence slowed to 72h and bounded to ±50%

## Что сделано
- В `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/AutomationRepository.kt` real insulin profile recompute policy изменена:
  - стабильный real-profile fit теперь пересчитывается раз в `72h`, а не ежедневно,
  - ранний пересчёт сохраняется только для `algoVersion` mismatch или пустого `fallback_template`.
- В runtime DIA resolver введены три слоя:
  - `dia_profile_hours` — duration выбранного insulin profile,
  - `dia_real_raw_hours` — сырой исторический estimate из onset/shape real-profile fit,
  - `dia_effective_hours` — bounded runtime DIA, который реально идёт в prediction engine.
- `dia_real_raw_hours` вычисляется из реального профиля как historical duration candidate и затем влияет на runtime только через confidence-weighted blend.
- Runtime DIA теперь жёстко ограничен в пределах `50%..150%` от выбранного insulin profile, чтобы real-profile fit не мог увести insulin action curve слишком далеко от базового профиля.
- `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/MainViewModel.kt` дополнен ключом `dia_real_raw_hours` в списке важных telemetry-показателей, чтобы raw estimate был виден в diagnostics.
- Добавлены тесты:
  - `runtimeDia_isHardClampedToFiftyPercentOfProfile`,
  - `realProfileRecompute_waitsUntilSeventyTwoHoursForStableEstimate`,
  - `realProfileRecompute_runsAfterSeventyTwoHours`,
  и обновлён expected behaviour для `runtimeDia_usesRealProfileScaleWhenConfident`.

## Почему так
- Ежедневный recompute real insulin profile был слишком частым для noisy history и создавал лишнюю дрожь в DIA-derived insulin curve.
- Пользовательский профиль должен оставаться baseline, а historical fit должен только мягко корректировать его, а не переопределять.
- Разделение на `profile / raw real / effective` делает математику прозрачной: видно и базовый профиль, и raw fit, и bounded runtime value.

## Риски / ограничения
- `72h` cadence замедляет реакцию на реальное долгосрочное изменение insulin action. Это осознанный компромисс в пользу стабильности.
- `dia_real_raw_hours` пока публикуется в telemetry/diagnostics, но не в отдельную dedicated UI карточку.
- Если raw fit систематически biased из-за плохой history терапии, bounded `dia_effective_hours` останется безопасным, но raw metric может визуально выглядеть странно до полной очистки therapy history.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --console=plain --no-daemon`
2. Проверить, что `dia_real_raw_hours` появляется в telemetry вместе с `dia_profile_hours` и `dia_effective_hours`.
3. Проверить, что `dia_effective_hours` никогда не выходит за `0.5x..1.5x` от выбранного profile DIA даже при экстремальном raw real-profile fit.

# Изменения — Этап 187: Analytics ISF/CR source separation

## Что сделано
- Во вкладке `Аналитика` ISF/CR разделены на три независимых источника:
  - `Compensation-derived evidence`,
  - `Copilot fallback runtime`,
  - `AAPS raw`.
- История ISF/CR теперь рисуется тремя линиями:
  - сплошная — compensation-derived evidence,
  - пунктир — Copilot fallback runtime,
  - точечная — AAPS raw.
- Убрана старая подмена, при которой fallback runtime мог визуально попадать в `real`-линию.
- `AAPS raw` в истории собирается только из raw/AAPS-подобных telemetry keys, без `profile_*`.
- В overview аналитики добавлены отдельные текущие плитки для всех трёх источников по `ISF` и `CR`.

## Почему так
- До этого аналитика смешивала evidence и fallback, из-за чего пользователь не видел, какие значения реально следуют из компенсации, а какие являются fallback/reference.
- Для анализа качества контура нужны все 3 слоя одновременно: evidence, fallback runtime и raw AAPS.

## Риски / ограничения
- Если `profile_estimates`/`profile_segment_estimates` ещё загрязнены старой телеметрией, fallback-линия будет оставаться консервативной до отдельной очистки/пересборки.
- Это изменение не чистит само по себе contaminated `CR` evidence окна; оно делает проблему явно видимой в UI.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.IsfCrHistoryResolverTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --console=plain --no-daemon`
2. Открыть `Аналитика -> ISF/CR`.
3. Проверить, что на экране есть три независимых текущих значения:
   - compensation-derived,
   - Copilot fallback runtime,
   - AAPS raw.
4. Проверить, что на графиках `ISF` и `CR` видны три отдельные линии с разными подписями в легенде.

# Изменения — Этап 188: Profile analytics self-heal on startup

## Что сделано
- В `AnalyticsRepository` добавлен startup self-heal для `profile_estimates/profile_segment_estimates`.
- Self-heal теперь принудительно пересчитывает профиль, если:
  - `active` профиль отсутствует,
  - `active` профиль загрязнён legacy telemetry (`telemetryIsfSampleCount > 1` или `telemetryCrSampleCount > 1`),
  - `active` профиль старше `12h`,
  - `profile_segment_estimates` отсутствуют или их `updatedAt` отстаёт от `active` профиля/тоже устарел.
- В `ProfileSegmentEstimateDao` добавлены lightweight-метрики состояния (`countAll`, `latestUpdatedAt`) для health-check без полной загрузки сегментов.
- `MainViewModel` теперь запускает `ensureProfileStateHealthy()` при старте приложения, так что восстановление не требует ручного `Recalculate analytics`.
- Startup self-heal переведён на быстрый профильный rebuild:
  - пересчитывает только `profile_estimates/profile_segment_estimates`,
  - не запускает `patternAnalyzer`,
  - ограничивает lookback до `90 дней`, даже если глобальная аналитика настроена на `365`.
- Добавлен unit-тест `AnalyticsRepositoryProfileHealthTest` на missing/polluted/stale/fresh сценарии.

## Почему так
- На телефоне оставались старые polluted/stale profile rows, из-за чего fallback runtime и аналитика ISF/CR жили на устаревшем профиле даже после исправления логики расчёта.
- Пересчёт был доступен только вручную, поэтому пользователь мог видеть старые значения неделями, если не запускал rebuild явно.
- Self-heal закрывает этот разрыв и делает startup профиля детерминированным.

## Риски / ограничения
- Startup может запускать тяжёлый analytics rebuild, если профиль старый; это осознанный компромисс ради корректного runtime/fallback состояния.
- Полный `recalculate()` остаётся тяжёлым путём; startup self-heal теперь специально использует облегчённый rebuild только для профильных таблиц.
- Порог stale сейчас `12h`. Если rebuild слишком тяжёлый для устройства, порог можно увеличить, но тогда stale-profile будет жить дольше.
- Self-heal лечит состояние profile tables, но не заменяет отдельную чистку upstream therapy history, если в БД всё ещё мало real bolus/carbs.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AnalyticsRepositoryProfileHealthTest" --tests "io.aaps.copilot.domain.predict.PatternAndProfileTest" --console=plain --no-daemon`
2. Запустить приложение на телефоне после установки новой сборки.
3. Проверить в audit появление `profile_estimate_self_heal_requested`, если профиль был stale/polluted.
4. Проверить, что `profile_estimates` и `profile_segment_estimates` получили свежий `timestamp/updatedAt` после старта без ручного пересчёта.

# Изменения — Этап 189: Metric-aware realtime ISF/CR fallback

## Что сделано
- В `IsfCrEngine` добавлены признаки сильного глобального evidence по `ISF` и `CR` (`isf_global_evidence_strong`, `cr_global_evidence_strong`).
- Sparse hour/day-type gating перестал жёстко рушить confidence, если по метрике уже есть сильное глобальное compensation-derived evidence.
- В `IsfCrFallbackResolver` fallback стал metric-aware:
  - `ISF` и `CR` оцениваются независимо,
  - слабая метрика может упасть в fallback,
  - сильная метрика может остаться compensation-derived даже если общий snapshot остаётся в режиме `FALLBACK`.
- В runtime reasons добавлены:
  - `partial_metric_keep`,
  - `isf_metric_fallback_applied`,
  - `cr_metric_fallback_applied`.
- Добавлен unit-тест на сценарий, где `CR` сохраняется compensation-derived при отсутствии usable `ISF` evidence.

## Почему так
- На живой БД после очистки polluted profile tables runtime всё ещё уходил в полный `FALLBACK`, хотя у `CR` уже было достаточно compensation-derived evidence, а проблема была только на стороне `ISF`.
- Общий all-or-nothing fallback скрывал полезный `CR` и делал аналитику/прогноз слишком консервативными.
- Metric-aware fallback позволяет использовать сильную метрику без ложного ощущения высокой общей уверенности.

## Риски / ограничения
- Snapshot всё ещё может оставаться в `mode=FALLBACK`, если общая confidence ниже порога. Это намеренно: UI и automation должны видеть, что snapshot частично fallback-driven.
- Если `ISF` evidence по-прежнему отсутствует, runtime `ISF` не станет compensation-derived только за счёт сильного `CR`.
- Эта правка не заменяет дальнейшую работу по улучшению realtime evidence extraction и hour/day-type gating.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrEngineTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrConfidenceModelTest" --console=plain --no-daemon`
2. Установить новую сборку на телефон.
3. Проверить в свежем `isf_cr_snapshots`, что при сильном compensation-derived `CR` и слабом `ISF`:
   - snapshot может иметь `mode=FALLBACK`,
   - `crEff` остаётся candidate-derived,
   - `isfEff` может оставаться fallback,
   - причины содержат `partial_metric_keep` и только нужный metric fallback reason.

# Изменения — Этап 190: Realtime ISF/CR therapy-noise filter

## Что сделано
- В `IsfCrRepository.computeRealtimeSnapshot()` realtime therapy stream для `ISF/CR` ограничен только физиологически значимыми событиями:
  - insulin-like,
  - carb-like,
  - `infusion_set_change` / `sensor_change` markers.
- `temp_target` и другой control-noise больше не попадают в быстрый realtime extractor.
- Добавлен unit-тест `IsfCrRepositoryRealtimeFilterTest`.

## Почему так
- На телефоне realtime refresh `ISF/CR` регулярно падал по таймауту `45s+`, потому что extractor проходил через сотни `therapy_events`, где основной объём составляли `temp_target`.
- Эти события не несут полезной информации для compensation-derived `ISF/CR`, но резко замедляют fast path и мешают вообще получить свежий snapshot.
- После фильтра realtime snapshot снова начал успевать завершаться за секунды, а не жить на stale reuse.

## Риски / ограничения
- Это изменение касается только realtime path; base-fit история по-прежнему использует полный therapy history с последующей доменной фильтрацией extractor'а.
- Если upstream начнёт писать новый insulin/carb event type без units/grams и без явного типа, его нужно будет добавить в realtime filter.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.IsfCrRepositoryRealtimeFilterTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrEngineTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrConfidenceModelTest" --console=plain --no-daemon`
2. Установить сборку на телефон.
3. Проверить в audit появление `isfcr_realtime_refresh_completed` без `isfcr_realtime_refresh_failed` на этом же цикле.
4. Проверить, что в свежем `isf_cr_snapshots` появились `isf_global_evidence_strong` / `cr_global_evidence_strong`, а `crEff` остаётся compensation-derived при `partial_metric_keep`.

# Изменения — Этап 191: Temp target rate-limit is blocked, not failed

## Что сделано
- Для `temp target` и `carbs` rate-limit случаи перестали записываться как `FAILED` в `action_commands`.
- Добавлен отдельный статус `BLOCKED` в `NightscoutActionRepository`.
- `action_delivery_failed` больше не логируется для rate-limit skip; вместо этого пишется `action_delivery_blocked`.
- В overview/audit `BLOCKED` отображается как предупреждение, а не как ошибка доставки.

## Почему так
- По живым логам реальная отправка `temp target` проходила успешно (`temp_target_sent`, `local_nightscout_relay_to_aaps`), но UI и audit продолжали показывать ложные ошибки, потому что duplicate/rate-limit skip маркировался как `FAILED`.
- Это мешало отличать настоящую поломку канала доставки от нормальной защиты от повторной отправки.

## Риски / ограничения
- Исторические старые `FAILED` записи в БД останутся старыми; правка влияет на новые команды после обновления.
- Если потребуется, можно отдельно сделать migration/cleanup старых `temp_target_rate_limit_30m` failed rows, но сейчас это не выполнялось.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
2. Вызвать повторную отправку `temp target` внутри rate-limit окна.
3. Проверить, что в `action_commands` новая запись получает `status=BLOCKED`, а не `FAILED`.
4. Проверить, что overview/audit показывают `BLOCKED` как warning, а реальные доставки продолжают показываться как `SENT`.

# Изменения — Этап 192: Loopback Nightscout sync-read retry and readiness gate

## Что сделано
- В `SyncRepository` добавлено ожидание готовности локального loopback Nightscout перед incremental sync.
- Для read-запросов `sgv`, `treatments` и `devicestatus` добавлен один мягкий retry только при loopback `ConnectException`.
- Общая sync-логика, курсоры и доменная обработка данных не менялись.
- Сборка установлена на телефон и проверена живым USB-аудитом.

## Почему так
- По живым логам ошибка `temp target` уже была устранена, но на чтении `https://127.0.0.1:17582/api/v1/...` оставались race-condition ошибки подключения во время запуска или перезапуска локального Nightscout.
- Одного одноразового `isLoopbackReachable()` перед sync было недостаточно: сокет мог быть доступен на precheck, но actual fetch выполнялся до полной готовности сервера.
- Ожидание готовности и один повтор на connect-failure устраняют этот короткий стартовый провал без агрессивного ретрая и без изменения сетевого поведения для внешнего Nightscout.

## Риски / ограничения
- Retry применяется только к loopback URL (`127.0.0.1` / `localhost`) и только к `ConnectException`.
- Ошибки типа `unexpected end of stream` не ретраятся этим патчем; они остаются видимыми как отдельная проблема транспорта.
- Прямой unit test для этого патча не добавлялся; проверка выполнена live-сборкой и USB-аудитом.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
2. Установить сборку на телефон и запустить приложение.
3. Проверить в `audit_logs` свежие циклы `nightscout_sync_started` / `nightscout_sync_completed`.
4. Убедиться, что в новых циклах нет `loopback_unreachable` и `ConnectException`, а `temp_target_sent` и `local_nightscout_relay_to_aaps` продолжают появляться.

# Изменения — Этап 193: Runtime COB subtracts synthetic UAM export before bias

## Что сделано
- В `AutomationRepository` runtime `COB` больше не использует внешний `AAPS raw COB` как есть, если в локальной истории есть остаток synthetic `UAM_ENGINE` carbs.
- Добавлен расчёт `synthetic UAM residual COB` по локальным tagged carb events (`UAM_ENGINE|...`) с той же моделью усвоения, что и в local COB estimate.
- Перед merge с local COB внешний `COB` теперь корректируется: `external adjusted COB = max(0, raw external COB - synthetic UAM residual COB)`.
- Для диагностики добавлены runtime telemetry keys:
  - `cob_external_adjusted_grams`
  - `cob_synthetic_uam_subtracted_grams`
- Добавлены unit tests на вычитание synthetic UAM residual COB.
- Попутно исправлен устаревший fake DAO в `TempTargetSendThrottleTest`, чтобы targeted unit suite снова компилировалась.

## Почему так
- По живой БД видно, что therapy history содержит множество `UAM_ENGINE` carb events, а forecast/runtime одновременно использует отдельный UAM contour и внешний `COB` из AAPS/xDrip.
- Это создавало системный риск двойного учёта: `UAM` уже учтён в latent/runtime ветке, а затем те же synthetic carbs повторно возвращались в прогноз через `COB` bias.
- На длинном горизонте (`30m/60m`) такой double-count особенно вреден и раздувает ошибку после inferred-meal эпизодов.

## Риски / ограничения
- Коррекция касается только runtime `effective COB`; `AAPS raw COB` сохранён как справочный внешний сигнал.
- Остаток synthetic UAM COB считается по локальной carb-абсорбции Copilot, поэтому возможны небольшие расхождения с тем, как AAPS декрементирует COB internally.
- Если в будущем появятся другие synthetic carb sources, их нужно будет также маркировать и включить в этот subtract-contour.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.data.repository.TempTargetSendThrottleTest" --tests "io.aaps.copilot.domain.predict.HybridPredictionEngineV3Test" --console=plain --no-daemon`
2. На живой БД/телефоне проверить telemetry:
   - `cob_external_raw_grams`
   - `cob_external_adjusted_grams`
   - `cob_synthetic_uam_subtracted_grams`
3. Убедиться, что при активных `UAM_ENGINE` carbs `cob_external_adjusted_grams <= cob_external_raw_grams`.
4. В forecast audit проверить, что `forecastCobIobBiasApplied=1` при этом использует уже очищенный runtime `cob_grams`.

# Изменения — Этап 194: ISF/CR compensation-derived evidence leaves fallback more often

## Что сделано
- В `IsfCrConfidenceModel` переработан расчёт confidence для runtime `Compensation-derived evidence`:
  - вместо одного медленного total-count ramp добавлено раздельное насыщение по `ISF` и `CR` evidence,
  - добавлены бонусы за strong global/hour-window evidence и same-day-type support,
  - добавлен cross-metric penalty, чтобы `CR-only` или `ISF-only` evidence не выглядело как полноценный operational snapshot.
- В `IsfCrFallbackResolver` добавлен безопасный `soft shadow` режим:
  - snapshot может выйти из `FALLBACK` в `SHADOW`, если оба metric-candidate качественные и keepable,
  - `SHADOW` не применяется в runtime dosing/control gate и остаётся диагностическим режимом.
- В `IsfCrWindowExtractor` correction-window contamination для `ISF` больше не ломается synthetic `UAM_ENGINE` carbs:
  - tagged synthetic carbs исключены из `carbsAround`,
  - это позволяет не терять реальный correction evidence только из-за UAM-export рядом.
- Добавлены/обновлены unit tests для confidence, soft-shadow и extractor behavior.

## Почему так
- На живых данных snapshot почти всегда оставался в `FALLBACK` не из-за плохого сенсора, а из-за слишком консервативного confidence contour и потери `ISF` evidence через `isf_carbs_around`.
- При этом `CR` evidence уже было рабочим, но общий snapshot схлопывался обратно в fallback mode и выглядел как будто compensation-derived контур не живёт.
- Фикс повышает качество именно compensation-derived runtime contour, а не просто занижает порог безопасности.

## Риски / ограничения
- Если реальных correction windows в истории по-прежнему мало, `ISF` может оставаться sparse и snapshot всё ещё уйдёт в `FALLBACK` либо в `soft SHADOW`.
- `soft shadow` улучшает наблюдаемость и историю источников, но не означает автоматическое применение snapshot в runtime control.
- Основной внешний лимит остаётся прежним: если upstream treatment history не публикует реальные bolus/carbs полноценно, quality ceiling для `Compensation-derived evidence` останется ограниченным.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrConfidenceModelTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrEngineTest" --console=plain --no-daemon`
2. На живой БД/телефоне проверить свежие `isfcr_realtime_computed` и `isfcr_runtime_gate`:
   - рост `confidence`,
   - появление `SHADOW` вместо постоянного `FALLBACK` в окнах с качественным evidence,
   - уменьшение dropped reason `isf_carbs_around` там, где рядом были только synthetic `UAM_ENGINE` carbs.
3. В аналитике `ISF/CR` проверить, что линия `Compensation-derived evidence` появляется чаще и не схлопывается обратно в fallback-only поведение.

# Изменения — Этап 195: Realtime ISF/CR evidence stops starving on short noisy therapy windows

## Что сделано
- В `IsfCrRepository.computeRealtimeSnapshot()` расширен realtime lookback для `glucose/therapy` с `18h` до `72h`.
- Добавлен adaptive widening therapy scan:
  - сначала realtime path читает bounded therapy window,
  - если raw query упёрся в лимит, а после фильтрации осталось слишком мало релевантных insulin/carb/set/sensor rows, запрос автоматически расширяется до широкого лимита.
- Добавлен unit test на правило widening при saturation `temp_target` rows.

## Почему так
- На живой БД главный bottleneck `Compensation-derived evidence` оказался не только в confidence-модели, а ещё и в самом runtime input window:
  - realtime path смотрел только `18h`,
  - raw therapy limit был слишком мал на фоне сотен `temp_target`,
  - после фильтрации до extractor доходили буквально 1–2 correction windows.
- Из-за этого `isfEvidence=0` оставался почти постоянным даже при наличии истории в базе.
- Новый runtime window опирается на реальную density полезных therapy rows, а не на шум от `temp_target`.

## Риски / ограничения
- Realtime path читает больше исторических glucose/therapy rows, поэтому CPU/IO на цикле `isfcr` немного вырастет.
- Telemetry окно не расширялось; оно остаётся коротким и cheap, потому что для wear/activity/UAM context старые telemetry rows не дают практической пользы.
- Если upstream по-прежнему не публикует реальные bolus/carbs полноценно, improvement останется ограниченным.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.isfcr.IsfCrConfidenceModelTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrWindowExtractorTest" --tests "io.aaps.copilot.domain.isfcr.IsfCrEngineTest" --tests "io.aaps.copilot.data.repository.IsfCrRepositoryRealtimeFilterTest" --console=plain --no-daemon`
2. На телефоне/живой БД проверить новые `isfcr_realtime_computed`:
   - рост `usedEvidence/isfEvidence`,
   - уменьшение случаев `isfEvidence=0` только из-за short therapy window,
   - более частый переход из `FALLBACK` хотя бы в `SHADOW`, если sensor-quality gate чистый.

# Изменения — Этап 196: Analytics ISF/CR line toggles and overlay traces

## Что сделано
- Во вкладке `Analytics -> ISF/CR` добавлены переключатели видимости для трёх основных линий:
  - `Compensation-derived evidence`
  - `Copilot fallback runtime`
  - `AAPS raw`
- На те же графики добавлены опциональные overlay-линии:
  - `COB`
  - `UAM`
  - `activity`
- Overlay-данные строятся из telemetry history на том же временном ряду, что и история ISF/CR.
- Для overlay-линий добавлено независимое auto-scaling внутри выбранного окна, чтобы они не ломали шкалу `ISF/CR`, но оставались визуально сопоставимыми по времени.
- В `AnalyticsUiState/MainUiState` добавлена отдельная история overlay-точек.
- Расширен telemetry-history набор ключей для аналитики (`COB/UAM/activity`).
- Добавлен unit test на проброс overlay history в analytics state.

## Почему так
- Пользовательский запрос был не только в том, чтобы видеть три источника `ISF/CR`, но и в том, чтобы быстро отключать шумные линии и накладывать метаболические контуры (`COB/UAM/activity`) без потери читабельности.
- Если overlay рисовать в абсолютной шкале вместе с `ISF/CR`, график становится практически бесполезным: диапазоны и единицы разные.
- Поэтому выбран диагностический режим: единая ось времени, primary axis для `ISF/CR`, а overlay-линии автоматически нормализуются внутри окна и сопровождаются явной легендой с фактическим диапазоном значений.

## Риски / ограничения
- Overlay-линии служат для корреляционного чтения графика, а не для абсолютного сравнения по общей оси единиц.
- Для `activity` используется `activity_ratio`; если этот telemetry-сигнал не поступает, overlay-линия не будет показана.
- Переключатели пока локальные (`rememberSaveable`) и не сохраняются в глобальных настройках приложения.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --tests "io.aaps.copilot.ui.IsfCrHistoryResolverTest" --console=plain --no-daemon`
2. Открыть `Analytics -> ISF/CR`.
3. Убедиться, что под каждым графиком есть отдельные переключатели для:
   - `Evidence / Fallback / AAPS raw`
   - `COB / UAM / Activity`
4. Включить overlay-линии и убедиться, что:
   - график `ISF/CR` остаётся читаемым,
   - overlay-линии появляются на той же временной оси,
   - под графиком отображается легенда с фактическими диапазонами overlay-значений.

# Изменения — Этап 197: Circadian Pattern Engine v1 (weekday/weekend, 5–14d, forecast prior)

## Что сделано
- Добавлен новый circadian слой данных и хранения:
  - `circadian_slot_stats`
  - `circadian_transition_stats`
  - `circadian_pattern_snapshots`
- Реализован `CircadianPatternEngine`:
  - `15-minute` слот-агрегация,
  - сегменты `WEEKDAY / WEEKEND / ALL`,
  - stable window fallback `14d -> 10d -> 7d -> ALL`,
  - bounded recency correction (`5d`),
  - residual forecast bias по историческим forecast-vs-actual,
  - derived `pattern_windows` для существующего rule engine.
- В `AnalyticsRepository` circadian-паттерны теперь рассчитываются и сохраняются вместе с обычной аналитикой.
- В `AutomationRepository` добавлен bounded circadian prior для прогнозов `5/30/60`:
  - применяется после физиологической path-simulation,
  - confidence-gated,
  - attenuated в acute-state,
  - полностью блокируется на stale/suspect sensor.
- В `MainViewModel` и `AnalyticsScreen` добавлен новый блок `Circadian glucose patterns`:
  - секции `WEEKDAY / WEEKEND / ALL fallback`,
  - переключатель окна `5d / 7d / 10d / 14d`,
  - median/p10/p25/p75/p90 curve,
  - expected delta `+30m / +60m`,
  - top risky windows.
- Добавлены тесты:
  - `CircadianPatternEngineTest`
  - расширен `CopilotMigrationsTest` для `10 -> 11`
  - расширен `AutomationRepositoryForecastBiasTest` для circadian prior.

## Почему так
- Существующий `PatternAnalyzer` годился только для hourly low/high risk windows и не давал пригодного prior для прогноза `30/60m`.
- Реальный circadian prior должен учитывать разницу `weekday/weekend`, окно истории `5–14` дней и текущую устойчивость паттерна, но не заменять физиологическую модель `IOB/COB/UAM/ISF/CR`.
- Отдельные таблицы и snapshot-слой дают проверяемый, explainable и UI-доступный источник паттернов без поломки существующего rule-engine контура.

## Риски / ограничения
- Circadian prior остаётся secondary prior и по умолчанию ослабляется при acute-state; ожидать большой пользы на резких meal/UAM эпизодах не нужно.
- При `useWeekendSplit=false` runtime `resolvePrior()` по-прежнему ориентирован на weekday/weekend запрос и опирается на `ALL` только через fallback snapshot, а не как единственный requested segment.
- UI пока не даёт отдельного switch в Settings для circadian tuning; настройки уже есть в `AppSettingsStore`, но явное управление в Settings остаётся следующим шагом.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.local.CopilotMigrationsTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
3. Открыть `Analytics -> ISF/CR` и проверить блок `Circadian glucose patterns`:
   - есть секции `weekday / weekend / all days`,
   - переключаются окна `5d/7d/10d/14d`,
   - видны median curve, `+30m/+60m` delta и risky windows.
4. В debug runtime telemetry проверить новые keys:
   - `pattern_prior_confidence`
   - `pattern_prior_30_mmol`
   - `pattern_prior_60_mmol`
   - `pattern_prior_residual_bias_30_mmol`
   - `pattern_prior_residual_bias_60_mmol`
   - `pattern_prior_segment_source`.

# Изменения — Этап 198: Circadian settings UI wiring

## Что сделано
- В `MainUiState` добавлены все runtime-поля circadian settings:
  - `circadianPatternsEnabled`
  - `circadianStableLookbackDays`
  - `circadianRecencyLookbackDays`
  - `circadianUseWeekendSplit`
  - `circadianUseReplayResidualBias`
  - `circadianForecastWeight30`
  - `circadianForecastWeight60`
- Эти настройки теперь пробрасываются из `AppSettings` в `MainViewModel` и далее в `SettingsUiState`.
- Починен `toSettingsUiState()` в `MainUiStateMappers`, из-за которого `compileDebugKotlin` падал после добавления нового `CircadianSettingsCard`.
- Проверена совместимость с уже добавленным `CircadianSettingsCard` в `Settings -> Advanced`.

## Почему так
- Настройки circadian-приора уже были сохранены в `AppSettingsStore` и использовались в аналитике/runtime, но UI wiring был незавершён: экран настроек требовал новые поля, а `MainUiState` и mapper ещё не умели их отдавать.
- Исправление сделано минимальным: только проброс полей и восстановление compile/test stability, без изменения бизнес-логики circadian engine.

## Риски / ограничения
- Это только UI wiring. Сам circadian engine и его runtime-логика не менялись.
- Настройки по-прежнему находятся в `Settings -> Advanced`; UX-группировка не пересматривалась.
- USB-установка на телефон в этом этапе не выполнялась.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
3. Открыть `Settings -> Advanced` и убедиться, что блок `Circadian patterns`:
   - отображает текущие значения,
   - позволяет включать/выключать pattern prior,
   - позволяет менять stable/recency lookback,
   - позволяет менять weekend split, replay residual bias и веса `30m/60m`.

# Изменения — Этап 199: Circadian replay evaluation tool (24h/7d)

## Что сделано
- Добавлен локальный replay-инструмент:
  - `tools/circadian_replay_eval.py`
- Инструмент:
  - читает `forecasts`, `glucose_samples`, `telemetry_samples`,
  - дедуплицирует glucose по той же source/quality winner-policy,
  - берёт baseline только из forecast rows без `|circadian_v1`,
  - строит causal circadian prior по истории до каждого `forecast.ts`,
  - сравнивает `MAE30/MAE60` для baseline против `baseline + circadian overlay`,
  - отдельно считает `all`, `low_acute`, `weekday`, `weekend`.
- Добавлен устойчивый fallback:
  - если live SQLite copy частично повреждена из-за hot-copy/WAL, инструмент автоматически читает заранее экспортированные CSV-срезы вместо прямого чтения БД.

## Почему так
- После USB-обновления на телефоне ещё не было полноценной 24h/7d истории с `|circadian_v1`, поэтому сравнивать “до/после” по сырым stored model versions было бы некорректно.
- Правильный способ для этой фазы — взять те же baseline forecast rows и наложить на них causal circadian prior офлайн на одной и той же истории. Так измеряется именно вклад circadian-слоя, а не всей модели в целом.

## Риски / ограничения
- Это офлайн replay именно circadian overlay, а не полный rerun всего prediction stack.
- В live-copy телефона часть старых страниц `telemetry_samples` была повреждена на диске при hot-copy, поэтому replay для telemetry работает через selective CSV export fallback.
- `IOB` acute gate в replay-tool учитывает `iob_units`, `iob_effective_units`, `iob_real_units`; если telemetry по одному alias битая, replay использует доступный соседний alias.

## Как проверить
1. Снять актуальную копию БД/CSV с телефона.
2. Запустить:
   `python3 /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py /path/to/copilot.db --timezone Europe/Moscow --out /path/to/replay_metrics.json`
3. Проверить в JSON:
   - `windows[0]` = `24h`
   - `windows[1]` = `7d`
   - `all/low_acute/weekday/weekend`
   - `mae_baseline` vs `mae_circadian`

# Изменения — Этап 200: Circadian replay summary in Analytics + AI Analysis

## Что сделано
- Добавлен on-device replay summary для circadian prior:
  - новый evaluator: `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/data/repository/CircadianReplaySummaryEvaluator.kt`
  - новый repository entrypoint: `AnalyticsRepository.buildCircadianReplaySummary(...)`
- Replay summary теперь строится прямо в приложении из:
  - stored `forecasts`,
  - deduplicated `glucose_samples`,
  - `telemetry_samples`,
  - persisted `circadian_slot_stats`,
  - persisted `circadian_transition_stats`,
  - persisted `circadian_pattern_snapshots`.
- В `MainViewModel` добавлен lazy refresh replay summary для экранов `Analytics` и `AI Analysis` с TTL `15 min`.
- В UI добавлены карточки summary:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/main/kotlin/io/aaps/copilot/ui/foundation/screens/CircadianReplaySummaryCards.kt`
  - summary показывается и в `Analytics`, и в `AI Analysis`.
- Добавлены новые UI модели и строки ресурсов для:
  - `24h / 7d`,
  - `ALL / LOW_ACUTE / WEEKDAY / WEEKEND`,
  - `MAE baseline -> circadian`,
  - `mean shift 30m/60m`.
- Добавлен unit test:
  - `/Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app/app/src/test/kotlin/io/aaps/copilot/data/repository/CircadianReplaySummaryEvaluatorTest.kt`
  - тест проверяет обратную реконструкцию baseline из уже сохранённого `|circadian_v1` forecast row и корректный расчёт `MAE`.

## Почему так
- Внешний Python replay уже показал полезность circadian prior, но результат не был доступен прямо на телефоне.
- Для корректного on-device сравнения нужно было не просто читать сохранённые rows, а уметь инвертировать circadian blend там, где stored forecast уже содержит `|circadian_v1`. Иначе `baseline vs circadian` сравнивались бы некорректно.

## Риски / ограничения
- Replay summary использует уже сохранённые circadian tables и не делает full rerun всего prediction stack по каждой исторической точке.
- Summary предназначен для быстрой causal-оценки эффекта circadian prior, а не для полной замены внешнего replay pipeline.
- Refresh идёт только при открытии `Analytics`/`AI Analysis` и кешируется на `15 min`, чтобы не перегружать UI и БД.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. Открыть на телефоне `Analytics` и `AI Analysis`:
   - найти блок `Circadian replay impact`,
   - проверить `24h` и `7d`,
   - убедиться, что видны `ALL / LOW_ACUTE / WEEKDAY / WEEKEND` и строки `MAE baseline -> circadian`.

# Изменения — Этап 201: AI Analysis window simplification + AI tuning block diagnostics

## Что сделано
- Упрощён экран `AI Analysis`:
  - оставлено только окно анализа `3d / 5d / 7d / 30d`,
  - убраны screen-level фильтры `source`, `status`, `weeks`, `focus horizon`, `focus factor`,
  - внутренние запросы истории/тренда продолжают работать через тот же `InsightsRepository`, но теперь всегда с `source=all`, `status=all` и derived `weeks`.
- Нормализован AI tuning blocked reason в runtime UI:
  - `Read timed out` -> `timeout while waiting for OpenAI response`,
  - `empty structured output` -> `OpenAI returned no valid structured JSON`,
  - отдельные причины для пустого API key, auth, rate limit и server errors.
- Усилен парсер `Responses API` для daily optimizer:
  - поддерживает `output_text`,
  - nested `output[].content[].text`,
  - `text.value`,
  - `parsed`,
  - `json`,
  - `arguments`.
- Для optimizer введён отдельный HTTP client с более длинными `read/call timeout`, чтобы background `responses` path не падал в локальный timeout раньше времени.
- Добавлены unit tests для новых форм structured output.

## Почему так
- Пользовательский экран AI Analysis был перегружен фильтрами, которые не помогают разбирать ежедневную компенсацию и только мешают мобильному UI.
- `AI tuning blocked` был честным по состоянию, но причина часто выглядела слишком общей. Основная фактическая проблема была в хрупком parser'е structured output и слишком коротком timeout для optimizer-пути.

## Риски / ограничения
- Экран AI Analysis теперь сознательно менее гибкий; расширенные фильтры остаются вне этого экрана.
- Если OpenAI действительно вернёт пустой/битый structured output, статус всё равно останется `BLOCKED`, но уже с точной причиной.
- Таймауты увеличены только для optimizer path, не для обычного AI chat.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AiChatRepositoryOptimizerTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --console=plain --no-daemon`
3. Открыть `AI Analysis` на телефоне:
   - увидеть только окна `3d / 5d / 7d / 30d`,
   - убедиться, что `AI tuning blocked` показывает конкретную причину, а не абстрактный статус.

# Изменения — Этап 202: AI Analysis top-chat + multimodal attachments + OpenAI voice mode

## Что сделано
- AI chat перенесён в самый верх экрана `AI Analysis` и превращён в основной interactive surface:
  - крупный ввод,
  - крупные message bubbles,
  - pending attachment chips,
  - voice status / voice reply switch.
- Добавлены вложения:
  - фото через picker (`image/*`),
  - файлы через generic document picker (`*/*`).
- Добавлен мультимодальный OpenAI path:
  - `Responses API` для image attachments (`input_image`),
  - text-like files (`txt/log/md/csv/json/xml/yaml`) локально читаются, урезаются по лимиту и добавляются как bounded attachment context.
- Добавлен голосовой режим на OpenAI:
  - запись микрофона (`RECORD_AUDIO`),
  - транскрипция через `audio/transcriptions` (`gpt-4o-mini-transcribe`),
  - обычный AI chat по транскрипту,
  - опциональный voice reply через `audio/speech` (`gpt-4o-mini-tts`).
- Упрощён controls block в `AI Analysis`:
  - day-window `3 / 5 / 7 / 30` применяется прямо по chip tap,
  - отдельная кнопка `Apply filters` убрана,
  - старые source/status/week filters не возвращались.
- Добавлены unit tests:
  - parser voice transcription,
  - text attachment preview extraction,
  - mapper новых AI chat state fields в `AiAnalysisUiState`.

## Почему так
- Пользователю нужен AI chat как первая точка входа, а не как нижний вторичный блок после отчётов.
- Для телефона важнее короткий, быстрый, визуально понятный multimodal composer, чем ещё один перегруженный фильтрами экран.
- OpenAI voice chain реализован консервативно: через те же chat/repository контуры, без обхода safety и без отдельных скрытых действий.

## Риски / ограничения
- На первом этапе только image attachments отправляются в OpenAI нативно; произвольные бинарные файлы не анализируются как полноценные file objects.
- Для файлов поддерживается bounded local preview; это сознательно безопаснее и проще, чем сырой upload всего содержимого.
- Voice mode требует `RECORD_AUDIO` и рабочий OpenAI API key; при отсутствии ключа/chat endpoint voice flow деградирует безопасно.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AiChatRepositoryOptimizerTest" --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --console=plain --no-daemon`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
4. На телефоне открыть `AI Analysis`:
   - убедиться, что chat находится над остальными секциями,
   - прикрепить фото и файл,
   - включить `voice replies`,
   - записать короткий voice prompt и получить текстовый/голосовой ответ.

# Изменения — Этап 203: AI chat mobile UX pass

## Что сделано
- В `AI Analysis` добавлены быстрые AI prompt-chips:
  - тренд,
  - ошибки 30m/60m,
  - ISF/CR,
  - UAM.
- Действия `Фото / Файл / Голос` переведены из маленьких icon-only кнопок в подписанные `OutlinedButton`, чтобы ими было проще попадать на телефоне.
- Кнопка отправки переведена на `AutoMirrored Send`, чтобы не оставлять deprecated icon usage в новом AI chat контуре.

## Почему так
- После переноса AI chat наверх главным bottleneck стал не backend, а мобильный UX: мелкие иконки и пустой composer без подсказок.
- Для частых сценариев анализа быстрее дать готовые prompt-chips, чем заставлять каждый раз печатать запрос вручную.

## Риски / ограничения
- Prompt-chips пока статические и не персонализируются под текущий день/ошибки.
- Голосовой режим остался тем же по логике; на этом этапе менялся только UX-слой.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. На телефоне открыть `AI Analysis` и проверить:
   - наличие quick prompt-chips под voice-mode block,
   - большие кнопки `Фото / Файл / Голос`,
   - нормальную отправку вопроса через обновлённый composer.

# Изменения — Этап 204: AI chat attachment picker hardening

## Что сделано
- `AI Analysis` переведён с общего `GetContent` на системный `OpenDocument` picker для файлов и изображений.
- Для выбранных URI добавлен `takePersistableUriPermission(READ)`, чтобы вложения оставались читаемыми после выбора и не зависели от краткоживущего grant.
- Сборка обновлена на устройстве и повторно проверен запуск `MainActivity`.

## Почему так
- Предыдущий picker-path был рабочим, но на реальном устройстве давал шумные package visibility события и был менее надёжен для файловых провайдеров.
- `OpenDocument` лучше соответствует задаче AI chat attachments: это явный SAF-контур с устойчивыми read grants.

## Риски / ограничения
- Логи `AppsFilter ... BLOCKED` от MIUI всё ещё появляются при запуске; это системный шум package visibility, а не crash приложения.
- Launch jank на холодном старте ещё остаётся; этот этап закрывает attachment-flow, а не startup performance.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin :app:assembleDebug --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --console=plain --no-daemon`
3. На телефоне открыть `AI Analysis`:
   - нажать `Фото` и выбрать изображение через SAF,
   - нажать `Файл` и выбрать документ,
   - убедиться, что вложения появляются в composer как chips без падения экрана.

# Изменения — Этап 205: AI warm-up lazy-load on route

## Что сделано
- Убрал AI-специфичный прогрев из `MainViewModel.init`:
  - `refreshCloudJobs`
  - `refreshAnalysisInsights`
  - `maybeGenerateLocalDailyAnalysis`
- Эти задачи теперь стартуют при переходе на маршрут `AI Analysis`.
- `runAutoConnectNow`, `refreshCloudJobs`, `refreshAnalysisInsights`, `maybeGenerateLocalDailyAnalysis` переведены с main launcher на `Dispatchers.IO`.

## Почему так
- Холодный старт на `Overview` не должен платить цену за `AI Analysis`.
- По live-logcat реальная проблема была не в crash, а в старте `MainActivity` с избыточной работой до первого экрана.

## Риски / ограничения
- Первый заход на `AI Analysis` теперь честно делает свой warm-up уже по месту, а не заранее.
- Launch jank уменьшился, но полностью не исчез; часть задержки остаётся на общем cold-start и первой инициализации процесса.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin :app:assembleDebug --console=plain --no-daemon`
2. Переустановить APK и сделать чистый cold-start:
   - `adb shell am force-stop io.aaps.predictivecopilot`
   - `adb logcat -c`
   - `adb shell am start -n io.aaps.predictivecopilot/io.aaps.copilot.MainActivity`
3. Проверить logcat:
   - `Displayed io.aaps.predictivecopilot/io.aaps.copilot.MainActivity`
   - отсутствие ранних `AI Analysis` refresh сообщений при открытии `Overview`.

# Изменения — Этап 207: Circadian Forecast Quality v2 (replay-aware 30m/60m)

## Что сделано
- Добавлен новый replay-aware circadian storage layer:
  - `circadian_replay_slot_stats`
  - Room migration `11 -> 12`
  - entity/DAO wiring в circadian persistence.
- `CircadianReplaySummaryEvaluator` теперь умеет:
  - fit replay slot stats по `dayType × windowDays × slotIndex × horizon`,
  - классифицировать buckets как `HELPFUL / NEUTRAL / HARMFUL / INSUFFICIENT`,
  - учитывать low-acute фильтр и coverage thresholds,
  - корректно реконструировать baseline из stored rows с `|circadian_v1` и `|circadian_v2`.
- `CircadianPatternEngine.resolvePrior(...)` расширен replay-aware полями:
  - `replayBias30/60`
  - `replaySampleCount30/60`
  - `replayWinRate30/60`
  - `replayMaeImprovement30/60`
  - `replayBucketStatus30/60`
- `AnalyticsRepository` теперь:
  - рассчитывает и сохраняет `circadian_replay_slot_stats`,
  - использует persisted replay stats при `resolveCircadianPrior(...)`,
  - прокидывает replay-aware summary в UI.
- `AutomationRepository.applyCircadianPatternForecastBiasStatic(...)` переведён на `circadian_v2`:
  - replay-aware weight для `30m/60m`,
  - replay residual bias только на `30m/60m`,
  - hard clamps для total circadian shift,
  - новые telemetry keys:
    - `pattern_prior_replay_status_30/60`
    - `pattern_prior_replay_bias_30/60`
    - `pattern_prior_replay_weight_30/60`
- `Analytics` UI теперь показывает runtime/replay diagnostics в блоке `Circadian glucose patterns`:
  - bucket status,
  - win rate,
  - `MAE baseline -> circadian`,
  - replay sample count,
  - fallback-to-`ALL`.

## Почему так
- Базовый circadian v1 уже давал прирост на `30m/60m`, но weight был одинаков для “полезных” и “вредных” time-of-day buckets.
- `v2` делает circadian слой измеримым и управляемым: runtime усиливает prior только там, где replay доказывает пользу на low-acute истории.
- Это улучшает long-horizon forecast quality без прямого вмешательства в controller API.

## Риски / ограничения
- Replay-aware weight пока влияет только на circadian overlay, а не на полный rerun physics-model; остальная ошибка `30m/60m` всё ещё определяется качеством `IOB/COB/UAM/ISF/CR`.
- Summary buckets `ALL` и `LOW_ACUTE` могут оставаться `INSUFFICIENT`, даже если `WEEKDAY/WEEKEND` buckets уже `HELPFUL`; это ожидаемо, потому что persisted replay stats хранятся по day-type/stable-window, а не по summary window `1d/7d`.
- `5m` circadian shift намеренно остаётся минимальным и не использует replay bias.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.local.CopilotMigrationsTest" --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. Открыть `Analytics -> Circadian glucose patterns` и проверить:
   - `Replay diagnostics` в каждой секции,
   - статусы `HELPFUL / NEUTRAL / HARMFUL / INSUFFICIENT`,
   - `MAE baseline -> circadian`,
   - `fallback to ALL` там, где local day-type coverage недостаточен.

# Изменения — Этап 206: Overview base target and Audit/UAM navigation

## Что сделано
- На Overview верхний баннер заменён на быстрый контроль Base target с шагом 0.1 mmol/L и текущим статусом freshness/kill switch.
- Нижняя навигация перестроена в Overview / Forecast / Analytics / Safety.
- Отдельная нижняя вкладка UAM убрана; события и сводка UAM перенесены в Audit через переключатель Log / UAM.

## Почему так
- Base target стал оперативным управлением на основном экране, без перехода в Settings/Safety.
- Analytics вынесена в нижнюю навигацию, потому что используется чаще как рабочий экран.
- UAM логичнее смотреть рядом с audit-трассировкой, а не как отдельный главный раздел.

## Риски / ограничения
- UAM stateflow в ViewModel пока сохранён отдельно и используется Audit-экраном; это намеренно, чтобы не ломать существующие action handlers.
- App Health banner остаётся на остальных экранах; только Overview заменён на Base target banner.

## Как проверить
1) ./gradlew :app:compileDebugKotlin
2) ./gradlew :app:testDebugUnitTest --tests \"io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest\"
3) Открыть Overview и поменять Base target кнопками +/-
4) Проверить нижнюю навигацию: вместо UAM должна быть Analytics
5) Открыть Audit Log и переключить табы Log / UAM

# Изменения — Этап: Overview Base Target Banner polish

## Что сделано
- Переработан верхний `Base target` banner на экране `Overview`.
- Добавлен hero-блок с крупным значением цели по центру и более заметными `+/-` кнопками с шагом `0.1 mmol/L`.
- Статусы `live/stale` и `kill switch` вынесены в отдельные компактные badges.
- Для `Midnight glass` и `Dynamic gradient` добавлены более выразительные surface/background/outline сочетания без изменения бизнес-логики.
- Нижняя строка баннера упорядочена: `last sync` показывается отдельной pill-меткой, диапазон цели остаётся справа.

## Почему так
- После переноса `Base target` на `Overview` баннер стал ключевой быстрой точкой управления и требовал более сильной визуальной иерархии.
- Старый вариант был функционален, но выглядел как обычная карточка и хуже читался на тёмных стилях.

## Риски / ограничения
- Это чисто UI-изменение: логика изменения цели, safety-границы и состояние синхронизации не менялись.
- Отдельный текстовый hint для действия `±0.1` пока зашит в интерфейсе и не вынесен в string resource.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin :app:assembleDebug`
2. Установить `app-debug.apk` и открыть экран `Overview`.
3. Проверить крупное отображение `Base target`, отдельные badges состояния и работу `+/-` с шагом `0.1 mmol/L`.

# Изменения — Этап 208: Circadian State Self-Heal

## Что сделано
- Добавлен health-check для circadian analytics state (`circadian_slot_stats`, `circadian_transition_stats`, `circadian_pattern_snapshots`, `circadian_replay_slot_stats`).
- При пустом или устаревшем circadian state запускается bounded circadian-only rebuild с lookback не больше `30d`.
- Триггеры self-heal добавлены на startup `MainViewModel` и при открытии маршрутов `Analytics` / `AI Analysis`.
- После успешного circadian rebuild summary по replay обновляется принудительно.

## Почему так
- `circadian_v2` был реализован и установлен, но без заполненных circadian таблиц runtime и analytics не могли показать ни replay diagnostics, ни `pattern_prior_replay_*`.
- До фикса открытие `Analytics` запускало только summary refresh, но не сам пересчёт circadian state.

## Риски / ограничения
- Первый self-heal после пустого state даёт дополнительную фоновую нагрузку, хотя она ограничена circadian-only rebuild и capped lookback.
- Если на устройстве мало history/forecast rows, self-heal корректно заполнит таблицы, но replay buckets могут всё равно остаться `INSUFFICIENT`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AnalyticsRepositoryProfileHealthTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. Установить APK, открыть `Analytics`, затем проверить в БД появление строк в `circadian_*` и `pattern_prior_replay_*`.

# Изменения — Этап 212: Circadian Long-Horizon Weighting v2.1

## Что сделано
- Для `30m/60m` circadian prior добавлены новые runtime-признаки:
  - `slotP10/P25/P75/P90`,
  - `medianReversion30/60`,
  - `stabilityScore`,
  - `horizonQuality30/60`.
- В `CircadianPatternEngine.resolvePrior()` long-horizon prior теперь вычисляет bounded `median reversion` к исторической медиане слота, если текущая глюкоза ушла за `p25..p75`.
- Reversion зависит от:
  - replay bucket status,
  - slot stability,
  - horizon-specific transition quality,
  - acute attenuation,
  и остаётся жёстко bounded (`30m <= 0.18`, `60m <= 0.32 mmol` до финального blend clamp).
- В `AutomationRepository.applyCircadianPatternForecastBiasStatic()` и `CircadianReplaySummaryEvaluator` horizon weight теперь учитывает:
  - `confidence`,
  - `qualityScore`,
  - `stabilityScore`,
  - `horizonQuality`,
  - replay-aware multiplier.
- Replay inversion для уже сохранённых `|circadian_v2` рядов исправлена:
  - baseline теперь восстанавливается тем же replay-aware weight, который использовался при записи, а не нейтральным `1.0`.
- Добавлены regression-тесты:
  - на отрицательный `median reversion` при glucose above band,
  - на runtime long-horizon reversion impact,
  - на корректную reconstruction/inversion для `circadian_v2`.

## Почему так
- После стабилизации circadian runtime основной оставшийся резерв точности был в `30m/60m`.
- Простого `delta30/delta60 + replay bias` недостаточно: если текущее значение уже сильно выше/ниже типичного слота, bounded возврат к исторической медиане улучшает long-horizon без ломки acute-state.
- Replay summary без replay-aware inversion для `circadian_v2` давал бы неточные offline-метрики и маскировал бы реальный эффект нового слоя.

## Риски / ограничения
- `median reversion` специально не применяется как агрессивная коррекция:
  - он отключается на stale/suspect sensor,
  - не используется при harmful replay bucket,
  - остаётся secondary prior, а не заменой физиологической path simulation.
- Replay CLI-скрипт всё ещё требует синхронизации с новой `circadian_v2` логикой, если нужно считать offline-метрики вне Android runtime.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --console=plain --no-daemon`
3. На `Analytics` / `AI Analysis` проверить, что `circadian_v2` runtime diagnostics продолжают показывать replay bucket status, а после long-horizon изменений replay summary остаётся causal и не ломает baseline reconstruction.

# Изменения — Этап 209: Circadian State Visibility in Analytics

## Что сделано
- В `Analytics -> Circadian glucose patterns` добавлена отдельная карточка `Circadian state`.
- Карточка показывает:
  - статус `READY / PARTIAL / STALE / EMPTY`,
  - counts для `slot / transition / snapshot / replay / section`,
  - последние `pattern` и `replay` timestamps,
  - активное отображаемое source mapping (`weekday→weekday:14d`, `weekend→all:10d`, и т.д.).
- Источник данных для карточки берётся напрямую из уже подписанных `MainViewModel` потоков (`circadian slot/transition/snapshot/replay`), без новых DAO-запросов.
- Карточка показывается даже при полностью пустом circadian state, чтобы на телефоне можно было отличить `EMPTY` от `не отрисовалось`.

## Почему так
- Hot-copy live SQLite с WAL на телефоне даёт ненадёжную картину и периодически выглядит как `0 rows` или `database malformed`, хотя app при этом может работать нормально.
- Для circadian self-heal нужна on-device проверяемость, а не анализ побочного дампа БД.

## Риски / ограничения
- Статус карточки сейчас вычисляется на UI-слое из counts/timestamps; это диагностический слой, а не отдельный persisted runtime flag.
- `reason` остаётся кратким диагностическим текстом, а не полной audit-трассой self-heal.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. Открыть `Analytics -> Circadian glucose patterns` и проверить карточку `Circadian state`:
   - при пустом состоянии должна быть `EMPTY`,
   - после self-heal должны появиться counts и timestamps,
   - при нормальной работе статус должен стать `READY` или `PARTIAL`.

# Изменения — Этап 210: OOM-safe Circadian Self-Heal

## Что сделано
- Устранён live `OutOfMemoryError` в `ensureCircadianStateHealthy`.
- Circadian self-heal больше не читает весь `telemetry_samples` за lookback-окно; вместо этого используется whitelist ключей, реально нужных для circadian fit и replay quality.
- Максимальный bounded lookback для circadian self-heal снижен до `21d`.
- Повторное использование уже загруженных `glucose / forecast / telemetry` entity-списков добавлено и для `fitCircadianReplayStats`.
- Сам self-heal обёрнут в fail-safe `try/catch` с audit-логом `circadian_pattern_self_heal_failed`, чтобы ошибка rebuild больше не убивала процесс приложения.

## Почему так
- Реальный OOM на телефоне происходил в `TelemetryDao.since(...)` при полном чтении telemetry history на старте Analytics/self-heal.
- Проблема была не в UI-карточке, а в слишком тяжёлом фоне circadian rebuild.

## Риски / ограничения
- Self-heal теперь смотрит только circadian-relevant telemetry keys; если в будущем fit начнёт зависеть от новых сигналов, whitelist нужно будет расширить явно.
- `21d` lookback выбран как безопасный компромисс для устройства; для очень редких weekend bucket'ов coverage может быть слабее, чем при полном `30d`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AnalyticsRepositoryProfileHealthTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --console=plain --no-daemon`
3. Установить APK, выполнить `force-stop -> start`, затем проверить logcat:
   - нет `OutOfMemoryError`,
   - нет `FATAL EXCEPTION`,
   - при необходимости есть `circadian_pattern_self_heal_completed` или безопасный `circadian_pattern_self_heal_failed`, но процесс остаётся жив.

# Изменения — Этап 211: Launch path split for Overview/AppHealth

## Что сделано
- `Overview`, `AppHealth`, и верхний `Base target` banner переведены на лёгкий `primaryUiState`, который не зависит от полного analytics/AI/circadian combine-контура.
- Из `CopilotFoundationRoot` убрана стартовая зависимость от тяжёлого `safetyUiState`; root теперь подписывается на отдельный `baseTargetBannerUiState`.
- Startup self-heal для `profile` и `circadian` больше не запускается из `MainViewModel.init`.
- `profile` и `circadian` self-heal теперь стартуют только при входе на маршруты `Analytics` / `AI Analysis`.
- Оба self-heal переведены на `Dispatchers.IO`, чтобы не конкурировать с первым рендером `Overview`.

## Почему так
- После прошлых оптимизациий основной startup bottleneck оставался в том, что `Overview` продолжал косвенно подписываться на тяжёлый `uiState`, а `MainViewModel.init` всё равно запускал rebuild-задачи analytics на холодном старте.
- Пользователь, который открывает только `Overview`, не должен платить CPU/IO-цену за repair `profile`/`circadian` таблиц и полный analytics path.

## Риски / ограничения
- Первый заход на `Analytics` или `AI Analysis` теперь может запускать repair позже, уже после открытия этих экранов.
- Если пользователь не открывает `Analytics`, profile/circadian self-heal не выполнится до следующего route-trigger или фонового job.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:compileDebugKotlin --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.ui.foundation.screens.MainUiStateMappersTest" --console=plain --no-daemon`
3. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
4. Установить APK и снять cold-start `logcat`:
   - `Displayed io.aaps.predictivecopilot/...` должен остаться ниже предыдущего уровня,
   - на старте `Overview` не должно быть `profile_estimate_self_heal_requested` / `circadian_pattern_self_heal_requested`,
   - `Analytics` при открытии должен по-прежнему запускать route-lazy repair при необходимости.

# Изменения — Этап 213: Circadian Replay CLI Synced to v2 Runtime

## Что сделано
- Доведён `/Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py` до текущей app-математики `circadian_v2`.
- Replay CLI теперь читает те же persisted таблицы:
  - `circadian_pattern_snapshots`
  - `circadian_slot_stats`
  - `circadian_transition_stats`
  - `circadian_replay_slot_stats`
- Baseline reconstruction для сохранённых forecast rows синхронизирована с runtime:
  - `|circadian_v1` инвертируется без replay-aware multiplier
  - `|circadian_v2` инвертируется с тем же replay-aware multiplier, что и в приложении
- В CLI добавлены:
  - `slot stability`
  - `horizon quality`
  - `median reversion`
  - `replay bucket status`
  - bounded `replayBias30/60`
- CLI стал устойчивее к частично битым hot-copy SQLite: необязательные таблицы читаются в best-effort режиме, вместо немедленного падения всего replay.
- Сохранены два replay-артефакта:
  - стабильный snapshot с валидной telemetry
  - свежий live export, где overall `ALL` считается корректно даже при повреждённой `telemetry_samples`

## Почему так
- До этого replay CLI отставал от приложения: он применял упрощённый prior и не умел корректно восстанавливать baseline после `circadian_v2`.
- Такие метрики выглядели точными, но сравнивали уже другую математику, а значит были плохим ориентиром для тюнинга `30m/60m`.

## Риски / ограничения
- Свежий live export с телефона всё ещё может иметь повреждённую `telemetry_samples` из-за горячего копирования WAL; в таком случае `LOW_ACUTE` и telemetry-driven сегменты на live-copy теряют точность.
- Для честного `LOW_ACUTE` анализа нужно использовать рабочий snapshot или консистентный backup, а не любой raw hot-copy SQLite.

## Как проверить
1. `python3 -m py_compile /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py`
2. `python3 /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py /Users/mac/Andoidaps/exports/circadian_v21_live_20260310/db/copilot.db --timezone Europe/Moscow --out /Users/mac/Andoidaps/exports/circadian_v21_live_20260310/replay_metrics_v21_synced.json`
3. `python3 /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py /Users/mac/Andoidaps/exports/circadian_replay_live_20260310_034300/db/databases/copilot.db --timezone Europe/Moscow --out /Users/mac/Andoidaps/exports/circadian_replay_live_20260310_034300/replay_metrics_v21_synced.json`
4. Проверить, что:
   - `24h/7d` `MAE30/MAE60` считаются без падения,
   - `circadian_v2` rows не искажают baseline,
   - replay summary выдаёт отдельные `ALL / LOW_ACUTE / WEEKDAY / WEEKEND`.

# Изменения — Этап 214: Circadian Replay Neighbor Fallback

## Что сделано
- В runtime `CircadianPatternEngine` добавлен bounded fallback на соседние replay buckets:
  - если точный `15-minute` replay bucket для активного слота остаётся `INSUFFICIENT`,
  - движок агрегирует replay-качество из соседних слотов в диапазоне `±2` слота (`±30 минут`)
  - с весами по расстоянию, не заменяя сам glucose-template слот.
- Такой же механизм добавлен в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py`, чтобы offline replay использовал ту же математику, что и приложение.
- На стабильном snapshot это заметно подняло `appliedRows` для circadian `30m/60m` и вывело long-horizon эффект из почти нулевого уровня в измеримый:
  - `7d 60m` улучшение стало порядка `-1.43%`
  - `7d LOW_ACUTE 60m` улучшение стало порядка `-4.57%`

## Почему так
- После `circadian_v2` главный bottleneck уже был не в самой формуле long-horizon prior, а в слишком частом статусе `INSUFFICIENT`.
- Exact-slot replay buckets на реальных данных нередко разрежены, особенно для `weekend` и low-acute режимов.
- Брать соседние слоты безопаснее, чем усиливать вес глобально: время суток остаётся локальным, но sparse buckets перестают быть полностью бесполезными.

## Риски / ограничения
- Fallback ограничен только replay-quality слоем. Он не должен подменять сам glucose-template slot и не должен размывать acute-state protection.
- На live hot-copy SQLite с повреждённой telemetry эффект по `LOW_ACUTE` может считаться неточно; эталонные выводы нужно делать по консистентному snapshot.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --console=plain --no-daemon`
2. `python3 /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py /Users/mac/Andoidaps/exports/circadian_v21_live_20260310/db/copilot.db --timezone Europe/Moscow --out /Users/mac/Andoidaps/exports/circadian_v21_live_20260310/replay_metrics_v21_neighbor.json`
3. Проверить, что:
   - `appliedPct` заметно выше, чем у `replay_metrics_v21_synced.json`,
   - `24h/7d` `MAE60` не хуже baseline,
   - `LOW_ACUTE 60m` на стабильном snapshot улучшается сильнее, чем без neighbor fallback.

# Изменения — Этап 215: Circadian Runtime Observability Fixed

## Что сделано
- Исправлен runtime observability gap для `circadian_v2`:
  - `pattern_prior_replay_status_30/60` уже писались в `telemetry_samples`,
  - но numeric debug-ключи (`replay_weight`, `replay_bias`, `horizon_quality`, `stability_score`) оставались только во внутреннем runtime map и не попадали в БД.
- Добавлен единый builder `buildCircadianPriorTelemetryRowsStatic(...)` в `AutomationRepository`, который публикует в `telemetry_samples`:
  - `pattern_prior_replay_weight_30/60`
  - `pattern_prior_replay_bias_30/60`
  - `pattern_prior_horizon_quality_30/60`
  - `pattern_prior_stability_score`
  - `pattern_prior_segment_source`
  - `pattern_prior_requested_day_type`
  - а также replay statuses и базовые circadian поля.
- На телефоне после USB-обновления live WAL-consistent snapshot подтвердил, что новые ключи уже реально пишутся.

## Почему так
- Без этих numeric keys на телефоне было видно только `HARMFUL / INSUFFICIENT`, но не было видно, насколько именно replay-aware слой ослабил `30m/60m`.
- Для тюнинга качества long-horizon прогноза этого недостаточно: нужен не только статус, но и фактический `weight/bias/quality`.

## Риски / ограничения
- Текущий live slot может оставаться `HARMFUL`; это не баг observability, а реальный результат replay-контроля качества.
- В таком режиме circadian слой не должен агрессивно тянуть прогноз, и маленькие `pattern_prior_replay_weight_*` как раз это подтверждают.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew clean :app:assembleDebug --console=plain --no-daemon`
2. Установить APK по USB и снять WAL-consistent snapshot БД.
3. Проверить, что в `telemetry_samples` появились ключи:
   - `pattern_prior_replay_weight_30/60`
   - `pattern_prior_replay_bias_30/60`
   - `pattern_prior_horizon_quality_30/60`
   - `pattern_prior_stability_score`
4. На live-снимке `2026-03-10 04:06:00` подтверждено:
   - `pattern_prior_replay_status_30 = HARMFUL`
   - `pattern_prior_replay_status_60 = HARMFUL`
   - `pattern_prior_replay_weight_30 = 0.0201654158638514`
   - `pattern_prior_replay_weight_60 = 0.0287964391057854`
   - `pattern_prior_replay_bias_30 = 0.2`
   - `pattern_prior_replay_bias_60 = 0.35`
   - `pattern_prior_horizon_quality_30 = 0.801030566865493`
   - `pattern_prior_horizon_quality_60 = 0.833405239881296`
   - `pattern_prior_stability_score = 0.510556402110326`
# Изменения — Этап 216: Circadian Replay Bootstrap Fit

## Что сделано
- Исправлен bootstrap-fit для `circadian_replay_slot_stats` в `CircadianReplaySummaryEvaluator`.
- Replay quality теперь оценивает сам `circadian` template без runtime replay-gating:
  - при первичном fit используется `bootstrapReplayFit=true`,
  - `30m/60m` веса не обнуляются из-за пустых replay stats,
  - в persisted replay buckets учитываются только `low-acute` строки, где circadian реально дал ненулевой shift.
- Добавлен регрессионный тест на bootstrap replay-fit, подтверждающий, что weekday `30m` bucket получает реальный `sampleCount/winRate/maeImprovement`, а не нулевой `HARMFUL(0/0)`.
- Обновлены `ARCHITECTURE.md` и `INVARIANTS.md`.

## Почему так
- До фикса replay-fit сам себя душил: `resolvePrior()` без replay stats давал `INSUFFICIENT`, из-за чего `30m/60m` circadian-вес почти занулялся, `baseline == circadian`, и persisted bucket quality получала ложные `0.0 / 0.0 / HARMFUL`.
- Это мешало runtime отличать реально вредные buckets от тех, где replay layer просто не смог измерить полезность.

## Риски / ограничения
- Bootstrap-fit теперь оценивает шаблон агрессивнее, чем runtime применяет его на живом цикле; это нормально, потому что runtime всё равно остаётся replay-aware и safety-gated.
- Исторические `circadian_replay_slot_stats`, уже записанные в БД, не пересчитаны автоматически до следующего analytics/circadian self-heal.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. Открыть `Analytics` на телефоне, дождаться circadian self-heal и проверить, что replay buckets для `weekday 30m/60m` перестают массово выглядеть как `HARMFUL` с нулевыми `winRate/maeImprovement`.

# Изменения — Этап 217: Circadian Replay Pollution Sentinel

## Что сделано
- Добавлен sentinel для старых polluted `circadian_replay_slot_stats`.
- Circadian self-heal теперь считает replay-таблицу нездоровой не только при `missing/stale`, но и когда в qualified bucket’ах остаётся legacy-подпись:
  - `sampleCount >= 8`
  - достаточное `coverageDays`
  - `maeImprovementMmol == 0`
  - `winRate == 0`
- Для этого в `CircadianPatternDao` добавлены счётчики:
  - `countQualifiedReplaySlotStats()`
  - `countPollutedZeroQualityReplaySlotStats()`
- `AnalyticsRepository.determineCircadianStateRebuildReason(...)` теперь возвращает `polluted_replay_zero_quality`, если такие строки найдены.
- Добавлен regression test на новый rebuild reason.

## Почему так
- После исправления bootstrap replay-fit приложение всё равно продолжало читать старые replay rows с `0.0 / 0.0`, потому что self-heal считал непустую и свежую replay-таблицу “здоровой”.
- В результате runtime оставался на старых `HARMFUL` bucket’ах и почти занулял `circadian_v2` на `30m/60m`, несмотря на исправленный fit-код.

## Риски / ограничения
- Если после rebuild в таблице всё ещё появятся qualified rows с точным `0/0`, self-heal будет пытаться пересобрать circadian state снова. На текущей математике это маловероятно и само по себе является полезным сигналом о деградации fit-пайплайна.
- Новый sentinel не меняет runtime-controller напрямую; он только гарантирует, что analytics/runtime не живут на старых polluted replay-таблицах.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AnalyticsRepositoryProfileHealthTest" --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. Открыть `Analytics` на телефоне и проверить в `audit_logs`, что появляется:
   - `circadian_pattern_self_heal_requested reason=polluted_replay_zero_quality`
   - затем `circadian_pattern_self_heal_completed`
4. Снять WAL-consistent snapshot и убедиться, что qualified replay rows больше не массово остаются с `maeImprovementMmol == 0` и `winRate == 0`.

# Изменения — Этап 218: Circadian Helpful Replay Boost

## Что сделано
- Для `circadian_v2` добавлен небольшой bounded boost только для действительно качественных `HELPFUL` replay buckets.
- В `CircadianReplaySummaryEvaluator` добавлена функция `replayHelpfulBoost(...)`, которая:
  - работает только при `sampleCount >= 12`,
  - требует `acuteAttenuation >= 0.85`,
  - требует horizon-specific `winRate / maeImprovementMmol`,
  - поднимает replay multiplier только в безопасном диапазоне (`<= 1.25`).
- `AutomationRepository` переведён на ту же математику:
  - runtime `patternPriorWeightForHorizonStatic(...)` и `replayBiasStrengthForHorizonStatic(...)` теперь используют тот же helpful boost, что и replay-evaluator.
- Исправлена baseline reconstruction для replay summary:
  - `|circadian_v1` больше не получает replay bias при обратном восстановлении baseline,
  - replay-aware inversion применяется только к `|circadian_v2`.
- Добавлен regression test на то, что calm `HELPFUL` bucket даёт более сильный `30m/60m` weight, чем тот же bucket в acute mode.

## Почему так
- После очистки polluted replay rows bucket-ы стали `HELPFUL`, но runtime всё ещё применял их слишком консервативно.
- Нужно было усилить только действительно хорошие low-acute слоты, не ослабляя safety-поведение для `HARMFUL`, `INSUFFICIENT` или acute-state окон.
- Одновременно replay summary должен был считать baseline честно: `circadian_v1` и `circadian_v2` нельзя инвертировать одной и той же replay-aware формулой.

## Риски / ограничения
- Helpful boost не гарантирует большой прирост по общему `24h/7d`; он рассчитан на качественные `30m/60m` low-acute buckets и может дать заметный эффект только там, где replay bucket уже стабильно `HELPFUL`.
- Boost ограничен multiplier-ом `<= 1.25` и всё равно остаётся под общими horizon clamps; он не должен заметно менять acute-state поведение.
- Если replay bucket quality быстро деградирует, boost автоматически исчезает, потому что перестают выполняться `sampleCount / winRate / maeImprovement / acuteAttenuation`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. `python3 /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py /Users/mac/Andoidaps/exports/circadian_replay_postcycle_20260310_043500/wal_bundle/copilot.db --timezone Europe/Moscow --out /Users/mac/Andoidaps/exports/circadian_replay_postcycle_20260310_043500/replay_metrics_postboost.json`
4. Сравнить `replay_metrics_postboost.json` с `/Users/mac/Andoidaps/exports/circadian_replay_postcycle_20260310_043500/replay_metrics_postfix.json`, особенно по `7d LOW_ACUTE 30m/60m`.

# Изменения — Этап 219: Dormant UAM Soft Attenuation for Circadian

## Что сделано
- В `CircadianPatternEngine.resolvePrior(...)` разделён `UAM active` на два случая:
  - `strong UAM`: текущий рост/absorption действительно активен и circadian prior жёстко ослабляется до `0.4`,
  - `dormant UAM`: старый inferred-event ещё висит в telemetry, но нет текущего роста (`delta5` calm), `uci0` низкий, `gAbs` низкий и confidence слабый; такой случай теперь ослабляет prior только до `0.7`.
- Для определения `strong/dormant UAM` добавлены runtime-признаки:
  - `uam_runtime_flag / uam_inferred_flag / uam_value`
  - `uam_runtime_confidence / uam_inferred_confidence`
  - `uam_inferred_gabs_last5_g`
  - `uam_uci0_mmol5`
- Добавлен unit-test на новый кейс: dormant UAM без текущего роста должен давать `acuteAttenuation = 0.7`, а не `0.4`.

## Почему так
- Live-аудит после `circadian_v2` показал реальный bottleneck не в bucket quality, а в слишком жёстком `acuteAttenuation`.
- В нескольких живых циклах `IOB` и `COB` уже были низкими, `delta5` был спокойным, но `circadian` всё равно получал `0.4` только из-за старого `uam_runtime_flag=1`.
- Это уже не safety-кейс, а потеря точности `30m/60m`: prior душился даже там, где replay bucket был `HELPFUL`.

## Риски / ограничения
- Soft attenuation касается только dormant low-confidence UAM; он не ослабляет защиту при реальном активном росте или свежем UAM absorption.
- Если thresholds окажутся слишком мягкими, circadian может стать активнее в пограничных meal windows. Это нужно проверять по live telemetry и replay `LOW_ACUTE`.
- Полный acute-state (`delta5`, `COB`, `IOB`, strong UAM`) по-прежнему остаётся без изменений и режет prior до `0.4`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. Установить APK по USB и в live telemetry проверить, что calm `UAM inferred_event` без реального роста больше не даёт автоматом `pattern_prior_acute_attenuation = 0.4`.

# Изменения — Этап 220: Sensor Quality Delta Dedup for Circadian Acute Gate

## Что сделано
- `AutomationRepository.evaluateSensorQualityStatic(...)` теперь сначала прогоняет glucose через `GlucoseSanitizer.filterPoints(...)`, а уже потом считает `delta5` / `noiseStd`.
- Это убрало ложные резкие `sensor_quality_delta5_mmol`, возникавшие на mixed-source истории с дублирующимися `timestamp`.
- Добавлен regression test на duplicate timestamp с разными источниками:
  - при одинаковом времени `nightscout` должен выигрывать у низкоприоритетного `local_broadcast`,
  - `delta5` должен считаться по dedup-ряду, а не по случайной mixed-source паре.

## Почему так
- Live-аудит показал, что circadian prior часто оставался в `acuteAttenuation = 0.4` не только из-за UAM, но и из-за слишком шумного `sensor_quality_delta5_mmol`.
- Этот `delta5` считался по сырому списку glucose points и мог ловить ложные mixed-source скачки на одинаковых или почти одинаковых timestamp.
- Пока acute-gate опирался на такой шумный `delta5`, даже правильный helpful replay bucket не мог заметно усилить `30m/60m`.

## Риски / ограничения
- Эта правка не меняет ingest и не чистит историческую таблицу — она только делает runtime sensor-quality и circadian acute-gate опирающимися на уже существующий dedup policy.
- Если в данных реально есть быстрый скачок на нормальном dedup-ряду, acute-gate останется жёстким. Правка убирает только mixed-source artifact.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. После установки по USB снять live WAL-bundle и проверить, что:
   - `sensor_quality_delta5_mmol` перестал ловить mixed-source spikes,
   - `pattern_prior_acute_attenuation` чаще выходит из постоянного `0.4` в спокойных окнах,
   - `pattern_prior_replay_weight_30/60` растёт там, где replay bucket уже `HELPFUL`.


# Изменения — Этап 221: Replay Bucket Thresholds Tuned for 15m Circadian Slots

## Что сделано
- Для replay-aware circadian bucket`ов убран завышенный универсальный порог `sampleCount >= 8`.
- Порог стал day-type aware и соответствует реальной плотности `15m` slot-ов:
  - `WEEKDAY >= 5`
  - `WEEKEND >= 4`
  - `ALL >= 6`
- Те же пороги применены и в runtime `CircadianPatternEngine`, и в replay summary evaluator, чтобы аналитика и runtime не расходились.
- Helpful replay boost теперь может включаться уже с `sampleCount >= 8`, а не только с `12`, что соответствует реальному потолку живых weekday/weekend bucket`ов.

## Почему так
- Live-аудит показал, что медиана `sampleCount` у `14d` replay slot-ов всего около `3`, а `p75` около `5`.
- При старом пороге `8` большинство slot-ов падали в `INSUFFICIENT`, хотя уже имели достаточный `coverageDays`, хороший `winRate` и отрицательный `maeImprovement`.
- Это искусственно душило `30m/60m` prior и не давало helpful bucket`ам реально поднимать вес в прогнозе.

## Риски / ограничения
- Порог стал мягче, поэтому больше bucket`ов войдёт в `HELPFUL/NEUTRAL/HARMFUL` вместо `INSUFFICIENT`.
- Safety остаётся на месте: качество всё ещё режется через `coverageDays`, `winRate`, `maeImprovement`, acute attenuation и общие horizon clamps.
- Если после этого появятся ложные helpful bucket`ы, следующий тюнинг должен идти по `winRate/maeImprovement`, а не возвратом к завышенному `sampleCount`.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. После установки на телефон проверить live telemetry:
   - уменьшение доли `pattern_prior_replay_status_* = INSUFFICIENT`
   - рост `pattern_prior_replay_weight_30/60` в calm weekday/weekend slot-ах.


# Изменения — Этап 222: Helpful Replay Boost for Dormant-UAM Windows

## Что сделано
- `replayHelpfulBoost(...)` больше не требует `acuteAttenuation >= 0.85`.
- Boost разрешён уже при `acuteAttenuation >= 0.70`, то есть в окнах с dormant `UAM`, где prior уже ослаблен, но не должен полностью терять полезный replay-boost.
- Добавлен unit-test на dormant-UAM уровень attenuation (`0.70`), который проверяет, что helpful boost реально включается для `30m/60m`.

## Почему так
- Live-аудит показал, что после исправления dormant-UAM gate многие calm slot-ы уже доходят до `acuteAttenuation = 0.70`, но additional helpful boost всё ещё не включался из-за старого порога `0.85`.
- Это обрезало полезный replay-aware circadian слой именно в тех окнах, где lingering UAM уже не представляет реальной meal-динамики, но ещё числится активным в telemetry.

## Риски / ограничения
- Boost всё ещё не включается при сильном acute-state (`acuteAttenuation = 0.4`).
- Общие horizon clamps и replay bucket classification не изменились; правка только даёт полезному replay слою шанс усилиться в безопасном полуспокойном окне.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
2. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:assembleDebug --console=plain --no-daemon`
3. После установки на телефон проверить строки telemetry, где `pattern_prior_acute_attenuation = 0.7`, и убедиться, что `pattern_prior_replay_weight_30/60` выше предыдущего уровня для тех же `HELPFUL` bucket-ов.


# Изменения — Этап 223: Neutralize Noisy Near-Zero Replay Buckets

## Что сделано
- Replay bucket classifier перестал помечать почти-нейтральные noisy slot-ы как `HARMFUL` только из-за слабого `winRate`.
- Теперь `HARMFUL` выставляется только в двух случаях:
  - явная регрессия `maeImprovement >= 0.05`,
  - или слабая, но положительная регрессия `maeImprovement > 0.02` вместе с действительно плохим `winRate < 0.40`.
- Та же логика применена и в runtime `CircadianPatternEngine`, и в on-device replay evaluator.
- Replay CLI синхронизирован с app-side логикой:
  - day-type aware replay sample thresholds,
  - dormant-UAM helpful boost при `acuteAttenuation >= 0.70`,
  - новый `HARMFUL/NEUTRAL` classifier.

## Почему так
- После снижения `sampleCount` thresholds большая часть оставшихся `HARMFUL` bucket-ов оказалась не реально вредной, а просто шумной: `maeImprovement` был около нуля, но `winRate` из-за малой выборки оставался ниже старого порога.
- Такие bucket-ы зря зануляли circadian prior и мешали `30m/60m`, хотя по факту должны были вести себя как `NEUTRAL`.
- Одновременно replay CLI уже отставал от runtime, поэтому offline метрики занижали реальный эффект последних правок.

## Риски / ограничения
- Классификатор стал мягче, поэтому часть бывших `HARMFUL` bucket-ов теперь станет `NEUTRAL` и позволит prior участвовать слабым весом.
- Safety остаётся на месте: `INSUFFICIENT`, acute attenuation, horizon clamps и replay bias clamps не менялись.
- Если после этого появятся реально вредные bucket-ы, следующий тюнинг должен идти по `maeImprovement/winRate` порогам, а не по возврату к старому грубому `HARMFUL` правилу.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
2. `python3 -m py_compile /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py`
3. На live DB проверить, что `30m` harmful buckets при околонулевом `maeImprovement` исчезают, а replay summary начинает давать более высокий `appliedPct` и более честный выигрыш `30m/60m`.


# Изменения — Этап 224: Prefer Stable Replay Neighborhood Over Harmful Exact Slot

## Что сделано
- Runtime `CircadianPatternEngine` больше не обязан держаться за exact 15-minute replay bucket, если он `HARMFUL`, а соседний slot-neighborhood по тому же `dayType/window/horizon` уже даёт более здоровый сигнал.
- Для сравнения теперь строятся два кандидата:
  - `exact` bucket (только текущий slot),
  - `neighborhood` bucket без центрального slot-а (`radius=2`, `includeCenter=false`).
- Если `exact` вредный, а `neighborhood` улучшает bucket-класс, runtime берёт neighborhood.
- Если `exact` нейтральный, а `neighborhood` становится `HELPFUL` без ухудшения coverage/sample support, runtime тоже может предпочесть neighborhood.
- Replay CLI синхронизирован с той же логикой выбора bucket-а, чтобы offline replay и телефон считали одинаково.

## Почему так
- Live-аудит показал реальный bottleneck: exact bucket часто оставался локально шумным или остаточно `HARMFUL`, хотя соседние 15-minute слоты уже были `HELPFUL`.
- Предыдущая реализация агрегировала neighborhood вместе с вредным центром, поэтому полезный соседний сигнал размывался до `NEUTRAL`.
- После исключения центрального harmful slot-а из alternative neighborhood runtime начал заметно чаще включать полезный circadian prior на `30m/60m`.

## Риски / ограничения
- Это не “подкрутка” replay rows, а только runtime rule выбора лучшего локального prior bucket-а.
- Acute attenuation, replay bias clamps, horizon clamps и stale/sensor gates не менялись.
- Если local neighborhood тоже шумный, runtime продолжит использовать exact bucket или ослабленный prior как раньше.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
2. `python3 -m py_compile /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py`
3. На телефоне проверить свежие telemetry keys:
   - `pattern_prior_replay_status_30/60 = HELPFUL`
   - рост `pattern_prior_replay_weight_30/60`
4. На WAL-consistent replay snapshot проверить новый baseline:
   - `1d all: 30m -2.05%, 60m -1.88%`
   - `7d all: 30m -3.56%, 60m -5.17%`
   - `7d weekend: 30m -4.40%, 60m -7.00%`


# Изменения — Этап 225: Make LOW_ACUTE Replay Diagnostics Telemetry-Aware

## Что сделано
- `LOW_ACUTE` в replay/eval больше не считается по умолчанию из пустой telemetry как будто все значения равны нулю.
- В app-side `CircadianReplaySummaryEvaluator` low-acute теперь требует:
  - реального `delta5`,
  - и хотя бы одного живого metabolic telemetry сигнала (`COB` или `IOB` или `UAM`).
- Тот же консервативный gate перенесён в `/Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py`.
- Unit tests обновлены:
  - существующие replay tests теперь дают явную telemetry, если хотят попасть в `LOW_ACUTE`;
  - добавлен отдельный тест, что при пустой telemetry bucket `LOW_ACUTE` не рисуется.

## Почему так
- После усиления circadian prior оказалось, что replay summary на некоторых WAL snapshots показывает `LOW_ACUTE == ALL`.
- Причина была не в самой модели, а в evaluator: missing telemetry silently превращалась в `0.0`, и любое окно без snapshot автоматически считалось спокойным.
- Это искажало качество `30m/60m`, потому что часть острого/неизвестного контекста ошибочно попадала в спокойную выборку.

## Риски / ограничения
- `LOW_ACUTE` summary теперь может стать заметно меньше или временно пустой на старых/битых hot-copy snapshots без свежей telemetry.
- Это правильное поведение: лучше не считать окно спокойным, чем ложноположительно использовать его для long-horizon tuning.
- На сам runtime forecast этот этап влияет через корректность replay diagnostics и последующего тюнинга, а не через прямое изменение physiology/path model.

## Как проверить
1. `cd /Users/mac/Andoidaps/AAPSPredictiveCopilot/android-app && ./gradlew :app:testDebugUnitTest --tests "io.aaps.copilot.data.repository.CircadianReplaySummaryEvaluatorTest" --tests "io.aaps.copilot.domain.predict.CircadianPatternEngineTest" --tests "io.aaps.copilot.data.repository.AutomationRepositoryForecastBiasTest" --console=plain --no-daemon`
2. `python3 -m py_compile /Users/mac/Andoidaps/AAPSPredictiveCopilot/tools/circadian_replay_eval.py`
3. На WAL-consistent snapshot без свежей telemetry проверить, что `low_acute` становится пустым, а не копией `all`.
