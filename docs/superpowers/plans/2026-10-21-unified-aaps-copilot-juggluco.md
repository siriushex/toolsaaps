# Единое приложение AAPS 4 + Copilot + JugglucoNG: архитектура и план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans for an approved subproject. Steps use checkbox syntax. Этот документ является предложением архитектуры и мастер-планом, а не разрешением включать управление помпой или устанавливать экспериментальную сборку.

**Goal:** Создать одно Android-приложение с одним главным интерфейсом, приёмом CGM, функциями Copilot и штатным контуром AAPS, сохранив историю, настройки и проверяемое выполнение команд.

**Architecture:** Основой служит ограниченно изменённый fork AAPS 4. Copilot подключается через отдельные модули и адаптеры; из JugglucoNG используется сенсорный/калибровочный слой, а не второй контур терапии. Владение данными и исполнительными действиями явно разделено. Облачный ИИ не участвует в критическом цикле управления.

**Tech Stack:** Kotlin, Jetpack Compose, существующие AAPS Metro/Multiplatform-модули, Android Room/SQLite, Coroutines/Flow, WorkManager, BLE/NFC, JNI/C++, Android Binder/AIDL для сенсорного процесса, существующий сервер Copilot.

**Статус:** исследование и предложение на 21 октября 2026, Asia/Tbilisi. Приложения, сервер, помпа и сенсор в рамках подготовки плана не менялись. Первый выпуск предполагается личным пилотом с одной подтверждённой парой «сенсор + помпа»; расширение на другие устройства предусмотрено отдельно.

---

## 1. Что означает единый продукт

Целевой результат:

- Один APK на телефоне, один launcher, один маршрут настройки, один экран состояния.
- Одно место ввода еды/инсулина и просмотра подтверждения, одна панель оповещений.
- Один владелец связи с каждой помпой и активным сенсором.
- Одна достоверная клиническая история, доступная прогнозу и ИИ без локальных HTTP-пересылок.
- Режимы: ручной дневник; CGM без помпы; CGM с ручными инъекциями; помпа в открытом контуре; разрешённый AAPS closed loop.
- Обновление всего продукта одной согласованной сборкой, с проверкой подписи и миграций.

Не требуется буквально один процесс, один файл БД или один foreground service. Они могут ухудшить надёжность. Часы остаются отдельным приложением для отдельного устройства, но входят в один продукт. Уведомления, обязательные для нескольких Android-служб, нельзя обещать полностью скрыть.

## 2. Проверенные исходники и ограничения исследования

| Проект | Исследованная база | Подтверждено |
|---|---|---|
| AAPS | `d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0`, официальный `4.0.0-rc1` | Модульная архитектура, Compose, Kotlin Multiplatform, Metro DI, `PersistenceLayer`, очередь помпы и поддерживаемые драйверы. Это prerelease, не финальная 4.0. |
| Copilot | `a6692924eff4d759458ffd5acff01f77ca6974f5`, приложение `0.1.5` | Android-приложение с собственным `Application`, Room37, WorkManager, прогнозом, Target Manager, локальным обменом и серверным ИИ. |
| JugglucoNG | `a02d85ea08c984a88de3e05431824e6066593c67`, снимок `main`, версия в исходниках `1.2.5-Alpha` | Compose, BLE/NFC, Kotlin- и JNI-драйверы, Room-история, калибровки; также собственные журнал, IOB/COB, прогноз и оповещения. Это экспериментальная программа, не только «драйвер сенсора». |

Исследована именно JugglucoNG `ctqvva/JugglucoNG`, а не лежащая рядом старая копия Juggluco. Снимок клонирован для чтения в `artifacts/unified-product-plan-20261021/JugglucoNG`. Наличие поддержки в README не является проверкой конкретного сенсора или прошивки.

Перед пилотом повторно проверить официальный стабильный релиз AAPS. Если он изменился, обновлять pin отдельной проверенной задачей, не автоматически брать новый `main` поверх незавершённого объединения.

В этой работе не выполнялись новые сборки объединённого APK, клиническая проверка или профилирование телефона. Числа далее, явно обозначенные как критерии или оценки, не являются измеренным результатом.

### 2.1 Почему это не простое добавление зависимостей

1. AAPS и Copilot являются самостоятельными Android applications. JugglucoNG `Common` также собирается как приложение, а не готовая библиотека.
2. Все три имеют собственный bootstrap и конфигурацию WorkManager. В одном процессе нельзя просто вызвать три `Application.onCreate()`.
3. В Copilot найдено 38 ссылок/приведений к `CopilotApp` в 26 файлах; `AutomationRepository` содержит 14436 строк, `AppContainer` 2233. Нужны узкие места подключения, не переписывание всего за один раз.
4. AAPS использует AGP9.4.1/Kotlin2.4.21, Copilot AGP8.13.2/Kotlin2.2.21, JugglucoNG AGP9.3.2/Compose plugin2.4.10. Compose-зависимости также различаются. Три независимых Gradle-root нельзя механически смешать.
5. AAPS minSdk31, Copilot minSdk31, JugglucoNG minSdk26. Первая полная сборка требует Android12+, а не сохраняет Android8-поддержку JugglucoNG.
6. `tk.glucodata` в Java/JNI и `applicationId` являются разными идентичностями. Переименование пакета JNI может сломать загрузку и callbacks; runtime-зависимость от app id проверяется отдельно.
7. В JugglucoNG кроме исходников есть отдельные JNI-входы. Его документация описывает 34 бинарных файла и происхождение из закреплённого APK. Возможность скачать файл не даёт нам автоматически права включить его в новый продукт.

## 3. Варианты объединения

| Вариант | Плюсы | Минусы | Решение |
|---|---|---|---|
| Общий интерфейс поверх трёх установленных приложений | Самый небольшой начальный перенос; изоляция существующих установок | Всё ещё три приложения, внешний обмен и три жизненных цикла; проблему обновлений не решает | Допустимый переходный режим, не конечный результат |
| AAPS-host + Copilot-модули + встроенный сенсорный слой NG | Сохраняет существующее ядро и драйверы помпы; даёт настоящий единый APK; убирает внутренний HTTP/подписание между приложениями | Нужны совместимые адаптеры, адаптация bootstrap NG, контролируемый fork AAPS | **Рекомендуется** |
| Новое приложение с переписанными алгоритмами и драйверами | Свобода структуры | Максимальный объём повторной проверки и риск регрессий; фактически новый APS | Не выбирать первым путём |

Сохранять «AAPS совсем без изменений» и одновременно встроить её в новый APK невозможно. Цель: небольшой обозримый patch stack и отсутствие случайных изменений дозирования, а не формальный нулевой diff.

## 4. Целевая структура

```text
Единый APK, основанный на AAPS
  main process
    AAPS bootstrap + Metro + native pump/loop runtime
    Copilot Compose UI
    ClinicalFactsReader -> immutable snapshots -> Copilot prediction
    TargetIntent -> local arbiter -> AAPS target gateway -> native transaction
    Alert arbiter + existing pump-critical adapters
    Server upload outbox, cached AI reports/chat

  :cgm process, после отдельного доказательства работоспособности
    JugglucoNG sensor bootstrap / BLE / NFC / JNI
    NG-owned sensor/calibration store + durable readings outbox
    versioned bounded Binder stream -> internal AAPS CGM source plugin

  existing Copilot server
    device-scoped history / compact context / daily reports / chat / photo jobs
    no direct pump command path
```

### 4.1 Процессы и bootstrap

- AAPS остаётся в своём привычном основном процессе. На первом этапе не переносить всё ядро в `:therapy`: это значительно расширит изменения upstream.
- Изоляция NG в `:cgm` защищает основной процесс от части JNI-crash, но **не является границей доступа к данным**: процессы приложения обычно имеют один UID. Force-stop, обновление пакета и некоторые системные отказы затрагивают весь пакет.
- Сначала доказать sensor-only bootstrap в стенде; только затем добавить process-aware composition. Если неподвижные global/static связи NG этому мешают, закрыть их адаптером и тестами, а не поднимать второй полный интерфейс NG.
- В `:cgm` не запускаются AAPS loop, pump plugins, ИИ, общая база Copilot, независимый прогноз NG или дополнительные Nightscout/HTTP/TURN-сервисы.
- Не использовать `isolatedProcess=true` как готовое решение: его ограничения разрешений/UID не подходят без дополнительного исследования BLE/NFC и хранения.
- Один владелец WorkManager и один совместимый WorkerFactory в основном процессе. NG-компоненты, которым нужен WorkManager, адаптируются через явную регистрацию, не второй default initializer.
- Обязателен тест холодного старта от receiver/service, а не только от launcher. Процессный guard должен работать до создания тяжёлых DI-графов и косвенных lazy-зависимостей.

### 4.2 Владельцы функций

| Данные/действие | Единственный авторитетный владелец | Остальные |
|---|---|---|
| BLE/NFC, состояние сенсора, pairing material | Выбранный NG driver/runtime | UI читает состояние, не открывает второе GATT-соединение |
| Raw/calibrated CGM, ревизия калибровки | Сенсорный слой NG | AAPS получает проверенную клиническую проекцию с provenance |
| Клинически принятые CGM-точки | AAPS source plugin + PersistenceLayer | Copilot читает принятую историю и причины отклонения |
| Подтверждённые болюсы, базал, TBR, pump history | AAPS | Ввод/запрос/доставка не смешиваются в одну запись |
| CR, ISF, DIA, тип/концентрация инсулина и активный профиль | AAPS | Наблюдения и оценки Copilot хранятся отдельно с временем действия |
| Фактические IOB/COB для работы AAPS | AAPS calculator | Модельные оценки не перезаписывают это состояние |
| Прогноз Copilot и оценка усвоения | Copilot | Явная версия модели, confidence, input revision |
| Временная цель | AAPS transaction после решения локального gateway | Target Manager предлагает намерение, не пишет в DB напрямую |
| Пользовательский интерфейс и объяснения | Copilot UI в host | Драйверы помп/сенсора доступны через capability screens |
| История ИИ и отчёты | Сервер + локальный read cache | Не источник авторизации терапии |

Собственные IOB/COB, калькулятор доз, прогноз и клинические сигналы NG не включать одновременно как независимых владельцев. Данные этих функций можно мигрировать с provenance, не запускать три одинаковых автомата.

## 5. Данные, единицы и согласованность

### 5.1 Контракт CGM

Переносимый `ReadingEnvelopeV1` содержит:

| Поле | Смысл |
|---|---|
| `sourceInstanceId`, `sensorSessionId` | Конкретный источник и сессия сенсора, не просто название бренда |
| `sampleId`, `revision` | Стабильная идентичность и версия, включая исправленную калибровку |
| `measuredAtMs`, `receivedAtMs`, `persistedAtMs` | Время измерения, получения, фиксации; не заменяют друг друга |
| `rawMgDl?`, `calibratedMgDl` | Канонические единицы; raw может отсутствовать, но не подменяться calibrated |
| `calibrationRevision`, `qualityFlags` | Что применено и какие ограничения известны |
| `kind` | LIVE / BACKFILL / REVISION; восстановленная история не становится новым live-событием |

IPC передаёт bounded batches и sequence/cursor, а не SQL, произвольные JSON-команды, JNI pointers или всю историю в одном Binder parcel. Размеры и версии контрактов фиксируются тестами.

NG сейчас использует Room identity `(sensorSerial,timestamp)` и на сенсорных путях заменяет минутные buckets. При создании `sampleId` учитывать это реальное поведение; нельзя считать каждый повтор другой точкой. Для точного воспроизведения хранить ревизию и событие замены.

### 5.2 Путь сохранения

1. Сенсорный процесс сохраняет reading/revision и outbox в своей транзакции.
2. Host принимает batch и проверяет источник, sequence, finite/units/time/freshness.
3. AAPS internal CGM plugin проводит клиническое принятие через существующие API.
4. Host фиксирует устойчивый cursor/идентичность принятого события. Повтор после смерти процесса безопасен; одного RAM-`Set` недостаточно.
5. Подтверждение очищает только соответствующую часть outbox. Ошибка не стирает данные сенсора.
6. Copilot получает изменения после клинической фиксации. UI может отдельно показать, что сырая точка пришла, но не принята для управления.

БД NG, AAPS и Copilot сначала сохраняются раздельными. Единая история означает согласованные владельцы/идентичности, а не физическое слияние всех таблиц. Каждый файл имеет одного writer-owner; второй процесс получает данные через API. Нельзя надеяться, что открытие одного Room-файла из трёх процессов само решит блокировки и уведомления.

Автовосстановление аналитической БД Copilot не должно восстанавливать или откатывать AAPS clinical DB и её command receipts. Повреждение кэша/модели допускает изолированное восстановление аналитики, но не повтор старых доз/целей. Политики Android backup и экспорт секретов согласовать явно; не наследовать наиболее широкое `allowBackup` из одного manifest.

Калибровка и сглаживание имеют единственный согласованный pipeline. Если AAPS получает уже откалиброванную NG-точку, второй этап не должен неявно повторять калибровку; raw хранится отдельно. Любая смена pipeline меняет revision и проходит differential/quality checks.

### 5.3 Clinical snapshot

`ClinicalSnapshotV1`: `snapshotId`, `asOf`, `glucoseRevision`, `activeProfileRevision`, временные интервалы действия настроек, текущие компоненты IOB, COB, delivery state, активная цель, CGM trust, активность и факты еды.

- Хранить отдельно bolus IOB, net basal IOB и другие реально доступные компоненты с единицами/определением. Не менять знак и не выбирать компонент только потому, что он лучше выглядит на экране.
- IOB и COB являются модельными состояниями с источником/временем, а не непосредственно измеренными дозами/граммами.
- «Углеводы введены», «еда съедена», «инсулин запрошен», «доставлен» и «оценено моделью» различаются типами.
- Исторический болюс сохраняет свой профиль/ICfg. Сегодняшний препарат/ISF/DIA не приписывается предыдущим 14 дням.
- Дубликаты из Nightscout, ручного ввода и прежнего Copilot не суммируются только по совпадению времени. Неоднозначная миграция помечается для review, а не удаляется.

## 6. Target Manager внутри продукта

### 6.1 Командный путь

`TargetIntentV1 -> TargetArbiter -> AapsTargetGateway -> AAPS-owned transaction -> readback receipt`.

Поля намерения: `commandId`, origin, причина/приоритет, запрошенная цель/длительность/единицы, creation/deadline, input/profile revision, consent/mode revision. Результаты: `APPLIED`, `REJECTED(reason)`, `EXPIRED`, `UNKNOWN`. Возврат из HTTP/очереди или подготовленный preview не означает `APPLIED`.

- Не обращаться к pump driver напрямую; установка target является отдельным действием от доставки инсулина.
- Удаление межпрограммного TLS/HMAC не отменяет актуальность входов, режим работы, блокировки, лимиты и проверку перед самой мутацией.
- Для устойчивой дедупликации `commandId` и записи target использовать одну AAPS-owned транзакцию с durable receipt. Если необходима новая receipt-таблица, это отдельная минимальная миграция AAPS с полным хвостом тестов. Вспомогательная БД Copilot не даёт атомарности с AAPS DB.
- При неизвестном результате запретить автоматический resend/альтернативный канал. Восстановление читает receipt и фактическое состояние; похожее значение цели само по себе не доказательство идентичности команды.
- Встроенный gateway отдельно проходит тесты истечения после ожидания lock/записи. Ранее проверенный patch native Client Control ограничивает dispatch, а не доказывает всю транзакцию.

### 6.2 Eating Soon

- Существующие «кнопка» и «+ → Еда» формируют один тип meal intent, а не два разных пути с разным приоритетом.
- При успешном принятии режим удерживает цель 30 минут от фактического подтверждённого начала. Обычные оптимизации, расписание и повторный прогноз не заменяют её.
- Защита от гипо, аварийное отключение, отсутствие пригодных данных и другие жёсткие ограничения выше пользовательского приоритета. Не обещать «100% применения» при любой неисправности.
- UI всегда показывает итог: применено/активно до времени, отклонено с объяснением или результат неизвестен. Запись еды не выдаётся за успешное применение target.
- Составной ввод еды не выполняется повторно при retry Eating Soon. Частичный результат виден по каждой части группы.

### 6.3 ГИ, усвоение и время еды

Тип еды/порция/история уточняют оценку формы усвоения и прогноз. Из CGM с приблизительными граммами и неизвестными влияниями нельзя достоверно восстановить лабораторный ГИ. Хранить оценку, диапазон/уверенность и coverage, а не выдавать точность, которой нет.

Влияние на терапию проходит через отдельно проверенную модель прогноза и существующий локальный контроллер. Не вводить прямое правило «ГИ×процент снижения цели» или автоматическую смену ISF/CR/DIA из ответа ИИ. Историческая проверка по более поздним дням, shadow и явное включение остаются обязательными.

Таймер onset и клинически рекомендованная пауза до еды являются разными сущностями. Сохранить текущий тихий таймер; рекомендация о времени еды требует своего доказательства. Положительного IOB недостаточно для вывода о болюсе на еду. При неопределённой доставке/SMB/старых входах таймер не создаёт ложное подтверждение.

## 7. Оповещения

- На этапе переноса один действующий audible owner для каждого класса клинических событий. Новый arbiter сначала работает без звука в shadow; прежний владелец не выключается заранее.
- Объединять сигналы по типу, источнику, поколению эпизода и causal id, а не просто одинаковому тексту.
- Сохранить «Вижу» как паузу конкретного эпизода, минимум 10 минут по текущей реализации. Новая CGM-точка того же эпизода не сбрасывает её; новое ухудшение и новый эпизод оцениваются отдельно.
- Тихий onset channel не становится каналом гипо. Права на звук, DND, вибрацию и exact alarm проверяются независимо.
- Технические срочные ошибки помпы не подавляются подтверждением CGM-оповещения.
- Нужны перезапуск, Doze, блокировка экрана, отказ звука/вибрации, потеря разрешения и восстановление после выключения. Ошибка доставки не потребляет «успешный сигнал».
- Один экран и понятная группировка уведомлений возможны; абсолютные 100% доставки человеку и отсутствие всех системных значков гарантировать нельзя.

## 8. Интерфейс и настройка

### 8.1 Главный экран

Сохранить утверждённый компактный подход Copilot: сова/меню, сахар+свежесть, цель/причина, краткая индикация прогноза на графике, IOB/COB и доступные действия. Отдельно видны сенсор и доставка помпы. Цвет не единственный способ обозначения риска.

`COB -> Лента`: полная кривая до оценочного конца усвоения; отдельная «Оценка», без подмены AAPS COB. Один ввод еды, исправления с историей. Доступ к реальным параметрам и ограничениям AAPS не скрывается красивым dashboard.

Сенсор: состояние/калибровка/срок по данным конкретного драйвера, raw vs calibrated по необходимости. Помпа: текущий режим, связь, история, набор и его достоверная смена. AI: Фокус+, последний сохранённый разбор, чат, советы и архив. Подписи/анимации не маскируют отсутствие данных.

### 8.2 Setup

Последовательность: выбор режима -> допустимые для режима устройства -> разрешения по необходимости -> импорт/профиль -> проба приёма данных -> проверка оповещений с отдельным согласием -> итог готовности.

В режиме дневника не требовать BLE/помпу/полный профиль. Режим CGM не даёт автоматически права на pump control. Сохранять штатные objectives, constraints, согласия и подтверждения AAPS; объединённый wizard их не обходит. Не включать closed loop после установки или импорта без подтверждённой готовности.

## 9. ИИ и сервер

- Сохранить текущую модель и способ входа ChatGPT; объединение телефона не требует смены провайдера или новой модели.
- При миграции сохранить logical device identity/историю через отдельную подтверждённую процедуру, а не считать новый package новым человеком или копировать plaintext credentials. Старый uploader отзывается только после подтверждённого перехода.
- На сервере сохраняется подробная история и политика 365 дней. Только контекст модели уплотняется: 10-минутные интервалы с min/max/count/gaps, старый фон при необходимости 30 минут, подробные релевантные эпизоды/еда/инсулин.
- Передавать активный и исторический профиль инсулина, режим, реальные подтверждения доставки, достоверность CGM, калибровки и ревизии. Не передавать ключи BLE/помпы, токены, PIN или содержимое произвольных файлов.
- Каждый вывод содержит период, coverage, факты/неопределённость и короткие рекомендации. Дневной разбор и советы по 3–14 дням сохраняются локально и показываются сразу; не запускать новый LLM-запрос на каждое открытие.
- Плановый разбор после 05:00 по timezone устройства: once-per-local-day, контроль покрытия, catch-up после offline и видимая дата последнего отчёта. Android WorkManager не является гарантией запуска точно в 05:00.
- Рекомендации по ISF/CR/DIA/еде/нагрузке являются проверяемыми предложениями для review; ответ модели не является исполняемой командой. Вся работа помпы и критических сигналов не зависит от доступности сервера.

## 10. Репозиторий и границы изменений

Предлагаемый будущий рабочий каталог: `/Users/mac/Andoidaps/UnifiedDiabetesApp`. В рамках исследования он **не создан**. Рекомендуется отдельный fork/repository, а не массовое переписывание текущего Copilot или старой рабочей AAPS.

```text
<UnifiedDiabetesApp>/                 # AAPS fork как основной Gradle-root
  app/                               # host Application, launcher, process composition
  core/, database/, implementation/, pump/, plugins/  # AAPS с небольшим patch stack
  product/contracts/                 # чистые DTO, причины отказа, версии
  product/sensor-juggluco/            # Android wrapper, IPC, bootstrap adapter
  product/copilot-domain/             # прогноз/оценка без Android Application
  product/copilot-ui/                 # Compose-функции/маршруты
  product/ai-client/                  # uploader/cache/chat/photo
  product/host-adapters/              # Metro bindings, CGM plugin, facts/target adapters
  upstream/copilot/                   # pinned source, не второй application plugin
  upstream/juggluco-ng/               # pinned source + отдельный обзор адаптаций
  verification/                      # differential replay, integration, device acceptance
  docs/adr/, docs/safety/, docs/releases/
```

Названия новых модулей являются предложением, не существующим API. Нельзя просто подключить `Common` или `android-app:app` как библиотеку: сначала создать runtime seams и определить, какие source sets реально включаются. Для NG это наша интеграционная адаптация, не обещание, что upstream примет модульное выделение: его direction.md пока предписывает один Gradle-модуль.

Базовое ограничение первого прототипа: не менять алгоритмы `plugins/aps`, существующие pump drivers и их protocol logic. Допустимые изменения AAPS: composition/build, plugin registration, host UI integration и отдельно обоснованная receipt/target transaction. Каждое отклонение оформляется ADR и тестами.

## 11. План реализации и критерии выхода

Каждая фаза является отдельным утверждаемым подпроектом. Сначала RED, затем минимальный код, GREEN, review diff, сборка и CI точного SHA. Отмеченные пункты ниже ещё не выполнены.

### P0. Feasibility, права и точный объём

**Файлы будущего проекта:** `docs/adr/0001-product-boundaries.md`, `docs/safety/device-matrix.md`, `upstream.lock.json`, `THIRD_PARTY_NOTICES`, `verification/sbom/`.

- [ ] Зафиксировать три SHA и теги, версии toolchain и фактически используемые JNI/ABI. Проверять hash каждого бинарного входа.
- [ ] Выбрать одну реальную пару сенсор/помпа и Android-версию, не угадывая по старым настройкам. Описать поддерживаемые режимы и исключённые функции v0.1.
- [ ] Проверить лицензии всех включаемых компонентов и права на JNI/графику/звуки. Для Copilot определить права/лицензию: в исследованном repo GitHub license=null, корневой LICENSE не найден.
- [ ] Воспроизвести независимые baseline build/test и dependency graphs без секретов и медицинских данных.
- [ ] Проверить sensor-only startup под иным host applicationId и без полного NG Application. Отдельно проверить JNI names, callbacks, native store path и вторичные listeners.
- [ ] Сравнить same-process стенд и `:cgm` startup. Внедрить synthetic driver только в test variant, без реальной помпы.

**Выход:** письменное GO/NO-GO: можно ли воспроизвести конкретный сенсор с допустимой лицензией, известными правками и рабочим bootstrap. Если нет, внешний CGM остаётся переходным режимом; это честно называется не полностью единым APK. Не начинать массовое объединение до результата.

### P1. Чистые contracts и app seams Copilot

**Исходные точки:** `CopilotApp.kt`, `service/AppContainer.kt`, workers/receivers/ViewModels с casts к `CopilotApp`; новые `product/contracts`, `product/copilot-domain`.

- [ ] Тестами зафиксировать текущие прогнозы/графики/Seen/onset на синтетических входах до переноса.
- [ ] Ввести `CopilotRuntimeHost` для получения зависимостей без cast к конкретному Application. Не извлекать весь `AutomationRepository` одновременно.
- [ ] Разделить запуск UI, наблюдения, прогнозов, оповещений и внешних adapters; конструктор не должен сам запускать второй loop/listener.
- [ ] Включить `ReadingEnvelopeV1`, `ClinicalSnapshotV1`, `TargetIntentV1`, typed outcomes, bounded serialization и version compatibility tests.
- [ ] Создать library wrappers с явно перечисленными исходниками и ресурсами; исключить launcher и standalone services там, где их заменяет host.

**Выход:** standalone Copilot сохраняет поведение; библиотечная композиция проходит тесты без второго `Application`, второго WorkManager и скрытых scheduling side effects.

### P2. AAPS-host и общий build

**Исходные точки AAPS:** `settings.gradle`, `app/build.gradle.kts`, `app/src/main/kotlin/app/aaps/MainApp.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/aaps/di/metro/`; новые `product/host-adapters`.

- [ ] Взять AAPS Gradle-root как основу, импортировать согласованный набор зависимостей и Metro bindings. Не использовать глобальные `force latest`/`pickFirst` для скрытия конфликтов.
- [ ] Оставить AAPS WorkManagerFactory owner, добавить явную маршрутизацию известных Copilot workers. Проверять неизвестный worker и холодный старт.
- [ ] Согласовать manifest authorities, providers, intent filters, notification ids, resources, permissions, backup/network policies.
- [ ] Добавить process-aware bootstrap без запуска pump/AI в `:cgm`; проверить providers/receivers, которые запускаются до UI.
- [ ] Подключить минимальный Copilot dashboard только для чтения, оставив штатные AAPS settings/driver screens доступными.

**Выход:** собирается один APK; штатные AAPS тесты и differential replay дают прежние результаты; нет нового канала управления или реального автозапуска устройств.

### P3. Встроенный CGM и калибровка

**Исходные точки NG:** `Applic.java`, `Natives.java`, `drivers/ManagedBluetoothSensorDriver.kt`, `drivers/ManagedSensorRuntime.kt`, `data/HistoryRepository.kt`, `data/HistoryReading.kt`, `data/calibration/`, CMake/JNI; AAPS `plugins/source`.

- [ ] Преобразовать подтверждённый P0 bootstrap в sensor runtime и versioned non-exported service. Сохранить оригинальные JNI class names/keep rules.
- [ ] Оставить одного writer для sensor/calibration stores; реализовать durable reading outbox и batch/cursor API.
- [ ] Создать AAPS internal CGM plugin, использующий `PersistenceLayer.insertCgmSourceData`, а не прямые SQL-вставки.
- [ ] Проверить mg/dL/mmol/L, raw/calibrated, дубликаты, backfill, коррекцию истории, смену сенсора, часы/телефон и ownership epoch.
- [ ] Проверить sensor process death, reconnection и readback фактической проекции. Native crash не должен оставлять старую точку «свежей».

**Выход:** synthetic/replay совпадают с baseline; затем отдельно разрешённая пассивная проверка выбранного настоящего сенсора. Только после неё источник может быть объявлен пригодным для clinical path.

### P4. Единые clinical facts, журнал и профиль

**Исходные точки AAPS:** `core/interfaces/.../db/PersistenceLayer.kt`, profile/insulin/IobCob interfaces; Copilot repositories; NG journal импорт только с provenance.

- [ ] Сделать read adapter committed CGM, bolus/basal/delivery, profile, IOB/COB, target, infusion-set events. Согласовать единицы и интервалы действия.
- [ ] Удалить внутри unified product необходимость пересылки этих же данных через local Nightscout/broadcast. Внешние интеграции оставить отдельным opt-in capability.
- [ ] Все новые терапевтические вводы проводить через существующие AAPS action APIs с readback; локальные заметки/фото не становятся доставленными углеводами или болюсом.
- [ ] Добавить incremental cursor и coherent snapshot projection. При несовпадении поколений показывать неполный snapshot, не собирать «свежий» набор из несовместимых времён.
- [ ] Проверить смешанную историю AAPS/Copilot/NG без двойного учёта, с edits/invalidation и потерей связи.

**Выход:** одни и те же факты видны в UI, прогнозе и server export; изменение источника не меняет смысл исторических доз.

### P5. Target Manager и Eating Soon

**Новые файлы:** `product/host-adapters/.../AapsTargetGateway.kt`, `TargetCommandLedger.kt`, `TargetGatewayTest.kt`; минимальные AAPS-owned receipt entity/transaction/migration при необходимости.

- [ ] Тесты: duplicate command, expiry до/после lock, input/profile сменился, выключен control, process death до/после mutation, receipt недоступен, UNKNOWN recovery без resend.
- [ ] Реализовать target-only gateway; не использовать общий bolus batch с неявными defaults как shortcut.
- [ ] Зафиксировать receipt вместе с фактическим изменением цели в одной транзакции. Проверить TTL/consent у точки применения, а не только при подготовке.
- [ ] Подключить arbiter с единой политикой manual/Eating Soon/защита/обычная автоматизация. Подтвердить обе точки ввода Eating Soon и 30-минутное удержание.
- [ ] Сначала shadow на simulator, затем проверка с virtual pump; реальное управление отдельно допускается после review safety cases.

**Выход:** никакой двойной установки/скрытого повтора в fault tests; UI различает запрос, подтверждённое применение, отказ и неизвестность. Существующий `native_clinical_projection_unavailable` нельзя просто удалить ради демонстрации.

### P6. Оповещения, меню и setup

**Исходные точки:** AAPS `implementation/.../androidNotification/AlarmNotificationManager.kt`; Copilot alarm router/policy/services/receivers; NG alert source adapters; общий Compose navigation.

- [ ] Свести события в общий arbiter в shadow, сохраняя действующие критические источники.
- [ ] Выполнить тестируемое переключение audible owner по классам событий; проверить технические ошибки помпы отдельно от CGM.
- [ ] Сохранить scoped Seen, escalation, mute expiry и тихий onset. Проверить reboot/cold receiver/loss of permission и невозможность скрыть новую опасность старой кнопкой.
- [ ] Реализовать wizard по режимам, компактный экран, sensor/pump details, единую «Еду», «Ленту», Фокус+ и action-linked validation errors.
- [ ] Утвердить motion/reduced motion, крупный шрифт и 320dp; графика не задерживает приём данных и ввод.

**Выход:** simulator/device acceptance для каждого типа сигнала; звуковые испытания на личном телефоне только с отдельным согласием. Не заявлять доставку человеку по одному `notify()`.

### P7. ИИ, отчёты и персональные модели

- [ ] Перевести server uploader на единый committed facts adapter и durable outbox. Сохранить device history/consent без двух одновременно пишущих клиентов.
- [ ] Передавать полный по смыслу, но компактный контекст, включая профиль инсулина/качество CGM/еду/доставку и историю изменённых настроек.
- [ ] Ночной отчёт, советы 3–14 дней и локальный cache: once-per-day, current timezone, late catch-up, no empty overwrite, никаких повторных LLM jobs при открытии вкладки.
- [ ] Подтвердить чат и food-photo jobs на синтетических данных, с идемпотентностью и честными состояниями timeout/unknown. Сетевые ошибки не блокируют loop.
- [ ] Вынести модель усвоения в отдельный контур оценки: chronological holdout, качество CGM/инсулина, confidence, model/version provenance. Влияние прогноза на Target Manager проходит отдельный safety gate.

**Выход:** отключение сервера/сети не меняет локальную терапию; ИИ видит правильный профиль и период, не заявляет, что весь архив помещается в один чат целиком.

### P8. Производительность, безопасность и сопровождение

- [ ] Измерить baseline трёх apps и unified на одном телефоне: aggregate PSS/RSS всех процессов, CPU, battery/wakelocks, BLE reconnect, disk/DB/WAL, APK by group и ночные jobs.
- [ ] Устранить повторные polling/сериализацию/local TLS для внутренних данных; один запуск prediction на актуальную ревизию, bounded queue, chunked history queries.
- [ ] Проверить bursts за 14–21 день, импорт 365 дней и отсутствие unbounded `SELECT *` в живом startup. Не переносить workaround увеличения CursorWindow как основную архитектуру.
- [ ] Минимизировать observers/таймеры и CPU motion в фоне. Вынести тяжёлые операции из главного потока; AI photo не должен конкурировать с loop за неограниченную память.
- [ ] Проверить release R8/JNI keep rules, все реально включённые `.so`, ABI и 16KB pages. Arm64-only personal build допустим после подтверждения устройства; нельзя молча выдавать его за универсальный APK.
- [ ] Создать policy diff для manifest/exported components, SBOM/license checks, secret scan, source-linked signed release и updater package binding.
- [ ] Ввести patch-stack ledger; upstream merge не меняет baseline без differential replay. Сначала upstream security/driver fixes, потом обновлённая сборка exact SHA.

**Выход:** измеренный отчёт, не обещание экономии памяти. Proposed release gate: нет потерянных clinical events/повторных side effects в стенде, нет новых ANR/crash на приёмке, отсутствует регрессия battery/CGM/pump continuity. Порог PSS/CPU фиксируется после baseline, не выдумывается заранее.

### P9. Перенос, пилот и обновление

- [ ] Испытать migration в отдельном test package только с virtual pump/synthetic sensor. Test build не должен захватывать связь реальных устройств.
- [ ] Определить signing/applicationId стратегию. Предпочтительно личное in-place обновление существующего `info.nightscout.androidaps` с его текущим доверенным ключом и заранее проверенными миграциями.
- [ ] Импорт Copilot/NG только через явные versioned exports/imports. Android sandbox не позволяет новому APK просто читать их приватные БД. Keystore/BLE/pump credentials и sensor session могут быть непереносимы; для них нужен отдельный сценарий, а не backup restore «целиком».
- [ ] Провести handover по шагам: согласованная свежая копия/экспорт по отдельному согласию; проверка настроек; подтверждённое прекращение старого владельца конкретного устройства; запуск нового; CGM/pump readback. Никаких двух активных pump controllers.
- [ ] Не выключать старый collector до доказательства приёма новым, но и не запускать два competing sensor clients. При непереносимом активном сенсоре оставить внешний приём до разрешённой новой сессии.
- [ ] Начать с мониторинга/open loop, затем отдельно разрешённый closed-loop pilot с планом наблюдения. Продолжительность аппаратной приёмки задаётся рисками; минимум отдельный 24h сценарий и многодневный soak, а не одна успешная точка.
- [ ] Включить интернет-update для нового package только после проверки полного APK/resume/signer/schema. Текущий Copilot updater привязан к `io.aaps.predictivecopilot` и такой APK правильно отвергнет: это не способ бесшовно заменить AAPS.
- [ ] Для обновления пакета с pump runtime добавить проверку незавершённых действий/неизвестных результатов и отдельный согласованный maintenance handover. Не выполнять установку во время активной доставки; не рассчитывать, что app update затронет только UI. Автовключение контроля после миграции не допускается без проверки режима/данных.

**Выход:** документированная migration receipt с фактическими версиями/подписями/свежестью/режимом. После новой Room migration старый APK может не открывать базу: основной откат является forward fix или отдельно проверенной совместимой процедурой, не восстановлением старой истории терапии.

## 12. Матрица проверки

| Уровень | Обязательные сценарии |
|---|---|
| Pure contracts | units, nonfinite, duplicate/revision, source id, time rollback, bounds, unknown version |
| CGM integration | live/backfill/recalibration, two sensors, explicit active source, ownership switch, JNI unavailable, sensor death |
| AAPS differential | Те же synthetic CGM/profile/bolus inputs дают прежние loop results при выключенном новом controller |
| Target transaction | expiry at mutation, concurrency, consent/profile change, duplicate intent, crash boundaries, unknown without replay |
| Alarm | scoped ACK, worsening, new episode, OFF expiry, sound failure, lost permissions, boot/Doze/locked screen |
| Lifecycle | Cold start from each entry point, no double DI/WorkManager, process kill, app update, force-stop recovery limitations |
| Migration | Supported schema tails, IDs/tombstones/profiles/credentials, low space, interrupted import, no stale therapy restore |
| AI | offline, malformed/empty response, photo timeout, once-per-day/cache, device identity/context boundaries |
| Release | Exact SHA CI, full signed APK, native symbols/16KB, upgrade package binding, Range resume, hash/signature, actual device receipt |

Нельзя подменять истинный driver/pump device test mocked success. Виртуальная помпа необходима, но сама не доказывает пригодность физического драйвера.

### Команды baseline, существующие сегодня

Запускать только в согласованной отдельной копии, с нужными SDK и без реальных устройств. Команды приведены для будущего этапа; в исследовании они не выполнялись повторно.

```bash
# AAPS pinned checkout, JDK21/SDK37.0; JSON recipe устраняет проверенный duplicate dependency.
rtk proxy env JAVA_HOME="$JAVA_21_HOME" ./gradlew --no-daemon --max-workers=2 \
  -I "$COPILOT_DIR/tools/aaps4_compat/android-platform-json.init.gradle" \
  -PandroidPlatformJsonIsolation=true \
  :app:testFullDebugUnitTest :app:compileFullDebugAndroidTestKotlin \
  :app:lintFullRelease :app:assembleFullRelease

# Copilot android-app, JDK17/SDK36; действующая версия source tests.
rtk proxy env JAVA_HOME="$JAVA_17_HOME" ./gradlew --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:lintDebug :app:compileDebugKotlin :app:assembleDebug

# NG pinned checkout; сначала отдельно проверить права и точный manifest JNI inputs.
rtk proxy env JAVA_HOME="$JAVA_21_HOME" ./gradlew --no-daemon --max-workers=2 \
  :Common:testMobileDebugUnitTest :Common:testWearDebugUnitTest
```

Настоящий stock-SDK TLS interop Copilot требует также `AAPS_INTEROP_JAVA_HOME` и `AAPS_INTEROP_CLASSPATH` с **путём к файлу classpath**, не его содержимым. Отсутствующий/пропущенный fixture не считается прошедшим.

NG CI прямо отделяет compile check от готовой native-сборки; недостаток JNI нельзя скрыть compile-only success. Его существующий lint baseline не следует импортировать как широкое отключение lint в едином приложении: новый код проверяется строго, унаследованные находки учитываются адресно.

## 13. Параллельная работа и зависимости

| Поток | Владелец файлов | Можно параллельно после P0 | Что не меняет |
|---|---|---|---|
| A. Sensor | `product/sensor-juggluco`, NG adaptation patch и fixtures | Да, с frozen contracts | AAPS dosing/pump, UI/AI |
| B. AAPS integration | `product/host-adapters`, receipt transaction, Metro registration | Да, с virtual pump | sensor signal processing, NG calibration |
| C. Copilot UI/domain | `product/copilot-*`, runtime seam, navigation/setup | Да, с fake facts/target outcomes | pump commands/driver internals |
| D. AI/history | `product/ai-client`, existing backend protocol tests | Да, с synthetic devices | local dose/target mutation |
| E. QA/release | `verification`, CI, migrations, benchmark/SBOM | С самого начала | production identity, реальные ключи/данные |

Один интегратор владеет root Gradle, MainApp/manifest и общими contracts. Два агента не редактируют эти файлы одновременно. Изменение контракта сначала проходит review и обновляет общие fixtures, затем consumers.

Критический путь: P0 -> P1/P2 -> P3/P4 -> P5/P6 -> P8/P9. P7 может идти параллельно, но не удерживает локальный клинический контур. Wear и расширенный каталог сенсоров/помп идут после phone pilot.

## 14. Время и первый объём

Это предварительная инженерная оценка, а не обещание срока:

- Feasibility P0 с реальными build inputs: 5–10 рабочих дней. Пересчёт оценки после результата.
- Личная alpha одной пары устройств: ориентировочно 8–12 календарных недель при 3 опытных разработчиках и отдельной QA/safety review. Не все задачи и аппаратные проверки распараллеливаются.
- Универсальный продукт с большим каталогом устройств, Wear, публичной дистрибуцией и внешним аудитом требует отдельного плана; переносить срок alpha на него нельзя.

Для публичного выпуска отдельно определить назначение продукта, рынки и применимые требования к медицинскому ПО/данным вместе с компетентными специалистами. Объединённое приложение не наследует автоматически статус/сертификацию сенсора или помпы; дисклеймер experimental сам по себе не заменяет такую оценку.

Первый вертикальный срез: synthetic sensor -> внутренний AAPS source -> committed facts -> компактный экран Copilot, **без новых live therapy actions**. Следующий срез: выбранный реальный сенсор в пассивном режиме и virtual pump. Только после доказательства этих путей подключать Target Manager к реальному контролю.

## 15. Решения до начала реализации

1. Утвердить AAPS-host как основу, а не переписывание APS или «три APK под одним меню».
2. Подтвердить личный первый выпуск и конкретную sensor/pump matrix.
3. Согласовать лицензию собственных частей Copilot и проверить права JNI/медиа. AAPS AGPLv3 и NG GPLv3 допускают совместное произведение при соблюдении соответствующих условий; это не разрешение на любые бинарные/медиа зависимости.
4. Утвердить владельцев clinical data/actions и запрет неявной параллельной терапии.
5. Утвердить независимый P0 spike и критерии GO/NO-GO. Не начинать установку или большой перенос исходников по одному мастер-плану.

## 16. Источники и точные места подключения

Публичные первоисточники:

- [AAPS RC1 release и статус prerelease](https://github.com/nightscout/AndroidAPS/releases/tag/4.0.0-rc1).
- [AAPS settings.gradle на исследованном SHA](https://github.com/nightscout/AndroidAPS/blob/d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0/settings.gradle).
- [AAPS MainApp](https://github.com/nightscout/AndroidAPS/blob/d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0/app/src/main/kotlin/app/aaps/MainApp.kt).
- [AAPS PersistenceLayer](https://github.com/nightscout/AndroidAPS/blob/d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0/core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/db/PersistenceLayer.kt).
- [AAPS лицензия](https://github.com/nightscout/AndroidAPS/blob/d5e7f5bdbb4aaefa4eaaa89a46fba257ef396fa0/LICENSE.txt).
- [JugglucoNG README в исследованном снимке](https://github.com/ctqvva/JugglucoNG/blob/a02d85ea08c984a88de3e05431824e6066593c67/README.md).
- [NG sensor driver contract](https://github.com/ctqvva/JugglucoNG/blob/a02d85ea08c984a88de3e05431824e6066593c67/Common/src/main/java/tk/glucodata/drivers/ManagedBluetoothSensorDriver.kt).
- [NG storage ownership](https://github.com/ctqvva/JugglucoNG/blob/a02d85ea08c984a88de3e05431824e6066593c67/docs/architecture/storage-ownership.md).
- [NG build/distribution/JNI provenance](https://github.com/ctqvva/JugglucoNG/blob/a02d85ea08c984a88de3e05431824e6066593c67/docs/distribution.md).
- [NG архитектурные ограничения upstream](https://github.com/ctqvva/JugglucoNG/blob/a02d85ea08c984a88de3e05431824e6066593c67/docs/architecture/direction.md).
- [GPL/AGPL compatibility, GNU](https://www.gnu.org/licenses/license-compatibility.html), [AGPLv3](https://www.gnu.org/licenses/agpl-3.0.en.html). Требования конкретного выпуска проверяются отдельно; это не юридическое заключение.
- [Android processes](https://developer.android.com/guide/components/processes-and-threads), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [16KB native pages](https://developer.android.com/guide/practices/page-sizes).

Текущие внутренние опоры Copilot: `docs/ARCHITECTURE.md`, `docs/INVARIANTS.md`, `docs/AAPS4_COMPATIBILITY.md`, `docs/AAPS4_PAIRING.md`, `docs/AAPS4_MASTER_ADMISSION.md`, `docs/APP_UPDATES.md`; текущие source files `CopilotApp.kt`, `service/AppContainer.kt`, `data/local/CopilotDatabase.kt`, `update/AppUpdateManifest.kt`.

Эти источники подтверждают исходные ограничения. Выбор AAPS-host, деление процессов, новые contracts, фазы, сроки и критерии являются инженерным предложением этого плана, а не обещанием или решением разработчиков upstream.
