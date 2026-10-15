/* Synthetic presentation catalogue. No detection, delivery or therapy rules. */
(() => {
  const base=typeof module!=='undefined'&&module.exports?require('./alerts-model.js'):window.AlertStudy12;
  const groups=Object.freeze([
    {id:'glucose',label:'Глюкоза',icon:'Droplet'},
    {id:'data',label:'Данные и прогноз',icon:'ChartNoAxesCombined'},
    {id:'delivery',label:'Доставка инсулина',icon:'Unplug'},
    {id:'food',label:'Цели и еда',icon:'Utensils'},
    {id:'maintenance',label:'Обслуживание',icon:'Clock'},
    {id:'system',label:'Приложение и ИИ',icon:'Settings'}
  ].map(Object.freeze));
  const surfaceLabels=Object.freeze({alert:'Оповещение',status:'Статус',inbox:'Входящие'});
  const priorityLabels=Object.freeze({info:'Информация',watch:'Наблюдение',warning:'Внимание',urgent:'Срочно'});
  const entries=Object.freeze([
    {id:'low-now',group:'glucose',kind:'urgent',icon:'CircleAlert',title:['Низкая глюкоза','по данным CGM'],source:'Текущие данные CGM',surface:'alert',nativeKind:'LOW_NOW',
      when:'Нативный движок передал LOW_NOW по актуальным данным.',route:'Глюкоза и последние измерения',limit:'Порог цветовой палитры не является порогом этого оповещения.'},
    {id:'low-imminent',group:'glucose',kind:'forecastUrgent',icon:'TriangleAlert',title:['Риск низкой глюкозы','в ближайшие минуты'],source:'Краткосрочный прогноз',surface:'alert',nativeKind:'CRITICAL_5',
      when:'Нативный движок передал CRITICAL_5, а не только высокий процент риска ИИ.',route:'Прогноз и качество данных',limit:'Прогноз не обозначается как уже измеренная гипогликемия.'},
    {id:'low-warning',group:'glucose',kind:'warning',icon:'TrendingDown',title:['Возможное снижение','по прогнозу'],source:'Прогноз на 30 минут',surface:'alert',nativeKind:'WARNING_30',
      when:'Нативный движок передал WARNING_30.',route:'Прогноз на 30 минут',limit:'Не подменяет срочное событие, если движок повысил приоритет.'},
    {id:'low-watch',group:'glucose',kind:'watch',icon:'Eye',title:['Наблюдение','за риском снижения'],source:'Прогноз на 60 минут',surface:'alert',nativeKind:'WATCH_60',
      when:'Нативный движок передал WATCH_60.',route:'Прогноз на 60 минут',limit:'Спокойный профиль наблюдения без аварийной графики.'},
    {id:'high-risk',group:'glucose',kind:'high',icon:'TrendingUp',title:['Возможное повышение','по прогнозу'],source:'Прогноз · риск повышения',surface:'alert',nativeKind:'SOFT_HIGH_RISK',
      when:'Нативный движок передал SOFT_HIGH_RISK.',route:'Прогноз и причины решения',limit:'Сам по себе не доказывает неисправность помпы или инфузионного набора.'},
    {id:'high-measured',group:'glucose',kind:'high',icon:'ArrowUp',title:['Глюкоза выше','настроенного предела'],source:'Текущие данные CGM',surface:'alert',
      when:'Существующий источник подтвердил превышение настроенного предела.',route:'Глюкоза и история',limit:'Границы цветов 9 и 13 не создают новый клинический порог.'},
    {id:'rise-trend',group:'glucose',kind:'watch',icon:'ArrowUpRight',title:['Глюкоза','быстро растёт'],source:'Подтверждённый тренд CGM',surface:'status',
      when:'Есть достоверный тренд; без отдельного события риска показывается только статус.',route:'Тренд и прогноз',limit:'Не назначает дозу и не создаёт срочный сигнал по одной стрелке.'},
    {id:'fall-trend',group:'glucose',kind:'watch',icon:'ArrowDownRight',title:['Глюкоза','быстро снижается'],source:'Подтверждённый тренд CGM',surface:'status',
      when:'Есть достоверный тренд; срочность задаётся отдельным движком риска.',route:'Тренд и прогноз',limit:'Не дублирует активное оповещение о снижении.'},

    {id:'cgm-stale',group:'data',kind:'data',icon:'WifiOff',title:['Нет свежих','данных CGM'],source:'Свежесть источника CGM',surface:'alert',
      when:'Источник сообщил превышение существующего срока свежести.',route:'Источник CGM и время связи',limit:'Последнее значение не показывается как текущее; сова и глаза серые.'},
    {id:'aaps-stale',group:'data',kind:'data',icon:'RefreshCwOff',title:['Нет свежих','данных AAPS'],source:'Обмен с AAPS',surface:'alert',
      when:'Подтверждена просрочка обмена с AAPS.',route:'Диагностика обмена AAPS',limit:'Отсутствие обмена не означает, что помпа остановлена.'},
    {id:'sensor-quality',group:'data',kind:'data',icon:'ScanLine',title:['Данные сенсора','требуют проверки'],source:'Диагностика качества CGM',surface:'alert',
      when:'Есть подтверждённый сигнал качества или ошибки от источника.',route:'Диагностика сенсора',limit:'Не объявляет сенсор неисправным только по форме графика или возрасту.'},
    {id:'measurement-mismatch',group:'data',kind:'watch',icon:'GitCompareArrows',title:['Измерения','заметно расходятся'],source:'Сопоставленные измерения',surface:'status',
      when:'Есть сопоставимые по времени контрольное измерение и CGM.',route:'Контрольные измерения',limit:'Не советует калибровать по одному несопоставимому значению.'},
    {id:'prediction-unavailable',group:'data',kind:'data',icon:'ChartNoAxesCombined',title:['Прогноз','пока недоступен'],source:'Проверка входных данных',surface:'status',
      when:'Расчёт не прошёл существующие проверки данных или свежести.',route:'Качество прогноза',limit:'Недостающий прогноз не изображается как благоприятный исход.'},
    {id:'iob-missing',group:'data',kind:'data',icon:'Syringe',title:['Нет актуального','значения IOB'],source:'Данные активного инсулина',surface:'status',
      when:'Источник не передал пригодное для расчёта значение IOB.',route:'Обмен и источники IOB',limit:'Неизвестный IOB нельзя подменять нулём.'},
    {id:'cob-missing',group:'data',kind:'data',icon:'WheatOff',title:['Оценка COB','пока недоступна'],source:'Данные углеводов',surface:'status',
      when:'Нет достаточного подтверждённого контекста для оценки COB.',route:'Углеводы и история еды',limit:'Неизвестное усвоение не выдаётся за нулевой остаток.'},
    {id:'isf-change',group:'data',kind:'watch',icon:'Gauge',title:['Чувствительность','изменилась'],source:'Сопоставимая история ISF',surface:'inbox',
      when:'Проверенный анализ выявил изменение с достаточным контекстом.',route:'История ISF и качество оценки',limit:'Показывать направление, период и источник; без автоматического совета по дозе.'},

    {id:'delivery-check',group:'delivery',kind:'delivery',icon:'ShieldAlert',title:['Проверьте','доставку инсулина'],source:'Диагностика доставки',surface:'alert',
      when:'Существующая диагностика передала обоснованный сигнал проверки.',route:'Доставка: причины и данные',limit:'Подозрение не подписывается как доказанная окклюзия.'},
    {id:'pump-disconnected',group:'delivery',kind:'delivery',icon:'Unplug',title:['Нет связи','с помпой'],source:'Подтверждённый статус связи',surface:'alert',
      when:'Источник помпы явно сообщил потерю связи.',route:'Связь с помпой',limit:'Потеря связи не равна доказанной остановке подачи.'},
    {id:'pump-paused',group:'delivery',kind:'delivery',icon:'CirclePause',title:['Подача инсулина','приостановлена'],source:'Статус подачи от помпы',surface:'alert',
      when:'Есть подтверждённый статус приостановки; учитывать осознанную паузу.',route:'Подтверждённый статус помпы',limit:'Срочность определяет нативная диагностика; не возобновлять подачу из макета.'},
    {id:'bolus-unconfirmed',group:'delivery',kind:'delivery',icon:'Syringe',title:['Болюс','не подтверждён'],source:'Подтверждение доставки',surface:'alert',
      when:'Команда есть, но подтверждения доставки нет или получен явный отказ.',route:'История команды и доставки',limit:'Не предлагать автоматическое повторение: первая команда могла выполниться.'},

    {id:'target-rejected',group:'food',kind:'data',icon:'CircleX',title:['Цель','не применена'],source:'Явный ответ AAPS',surface:'alert',
      when:'Получен явный отказ на команду цели.',route:'Target Manager: причина отказа',limit:'Предложенная цель не показывается как применённая.'},
    {id:'target-pending',group:'food',kind:'watch',icon:'Clock3',title:['Цель ожидает','подтверждения'],source:'Ожидание ответа AAPS',surface:'status',
      when:'Команда отправлена, но фактическая цель ещё не подтверждена.',route:'Target Manager: обмен',limit:'Ожидание не называется ни успехом, ни доказанным отказом.'},
    {id:'eating-soon',group:'food',kind:'reminder',icon:'Utensils',title:['Eating Soon','активен'],source:'Подтверждённая цель AAPS',surface:'status',
      when:'AAPS подтвердил начало цели; окончание брать из подтверждённого состояния.',route:'Target Manager: Eating Soon',limit:'Не запускает режим и не меняет его приоритет; таймер не угадывается.'},
    {id:'meal-unconfirmed',group:'food',kind:'data',icon:'ClipboardClock',title:['Запись еды','не подтверждена'],source:'Результат сохранения еды',surface:'alert',
      when:'Нет подтверждения записи либо получен явный отказ.',route:'История еды и статус записи',limit:'Не пересылать углеводы повторно автоматически.'},
    {id:'meal-model-uncertain',group:'food',kind:'watch',icon:'Wheat',title:['Усвоение','оценено приблизительно'],source:'Качество модели еды',surface:'status',
      when:'График построен на ограниченной истории или типовой модели.',route:'Лента: оценка и источники',limit:'Не представлять оценочный ГИ и граммы как точно измеренные.'},

    {id:'set-due',group:'maintenance',kind:'reminder',icon:'Clock',title:['Пора проверить','смену набора'],source:'Подтверждённая дата смены',surface:'alert',
      when:'Достигнут согласованный срок 72 часа от достоверной смены.',route:'Обслуживание: инфузионный набор',limit:'Расход инсулина и ISF не подменяют дату и не доказывают неисправность.'},
    {id:'set-date-unknown',group:'maintenance',kind:'reminder',icon:'CalendarX',title:['Не указана','дата смены набора'],source:'Журнал обслуживания',surface:'status',
      when:'Нет достоверной даты смены.',route:'Обслуживание: дата набора',limit:'Не создавать выдуманный возраст и автоматическую отметку о замене.'},
    {id:'sensor-due',group:'maintenance',kind:'reminder',icon:'CalendarClock',title:['Подходит срок','замены сенсора'],source:'Данные срока сенсора',surface:'alert',
      when:'Есть достоверная дата запуска и срок именно используемой модели.',route:'Сенсор: дата и источник срока',limit:'Не задавать универсальные 14 дней для любого сенсора.'},
    {id:'sensor-date-unknown',group:'maintenance',kind:'reminder',icon:'CalendarX',title:['Срок сенсора','неизвестен'],source:'Нет даты или модели срока',surface:'status',
      when:'Неизвестна дата запуска или допустимый срок модели.',route:'Сенсор: сведения о сроке',limit:'Не вычислять оставшиеся дни по предположению.'},
    {id:'pump-battery',group:'maintenance',kind:'data',icon:'BatteryLow',title:['Низкий заряд','батареи помпы'],source:'Состояние батареи от помпы',surface:'alert',
      when:'Помпа передала соответствующий статус или собственное предупреждение.',route:'Помпа: батарея и время статуса',limit:'Не показывать выдуманный процент или оценку часов работы.'},
    {id:'reservoir-low',group:'maintenance',kind:'data',icon:'Container',title:['Мало инсулина','в резервуаре'],source:'Остаток по данным помпы',surface:'alert',
      when:'Есть подтверждённое предупреждение об остатке от источника.',route:'Помпа: остаток и свежесть',limit:'Не считать остаток точным по одному предполагаемому расходу.'},

    {id:'notifications-blocked',group:'system',kind:'data',icon:'BellOff',title:['Уведомления','запрещены'],source:'Проверка разрешений',surface:'alert',
      when:'Проверка разрешений подтвердила ограничение.',route:'Настройки уведомлений Copilot',limit:'Разрешение само по себе не доказывает слышимость сигнала.'},
    {id:'channel-silent',group:'system',kind:'data',icon:'VolumeX',title:['Звук оповещений','выключен'],source:'Настройки канала',surface:'alert',
      when:'Проверка канала подтверждает отключение звука.',route:'Настройки канала оповещений',limit:'Не менять громкость и канал без отдельного действия пользователя.'},
    {id:'background-restricted',group:'system',kind:'data',icon:'BatteryWarning',title:['Фоновая работа','ограничена'],source:'Проверка фоновой работы',surface:'alert',
      when:'Обнаружено конкретное ограничение фоновой работы.',route:'Энергосбережение Copilot',limit:'Не обещать надёжную доставку только после снятия одной настройки.'},
    {id:'audio-route',group:'system',kind:'watch',icon:'Headphones',title:['Аудиовыход','не проверен'],source:'Диагностика звука',surface:'status',
      when:'Нет подтверждённой проверки актуального аудиовыхода.',route:'Оповещения: маршрут звука',limit:'Состояние Bluetooth не является доказательством слышимости.'},
    {id:'signal-paused',group:'system',kind:'data',icon:'AlarmClockOff',title:['Сигнал','приостановлен'],source:'Состояние текущего сигнала',surface:'status',
      when:'Есть явная пауза сигнала; показать её область и срок, если известны.',route:'Текущее оповещение и повторы',limit:'Пауза не равна устранению причины и не должна маскировать новый риск.'},
    {id:'profile-incomplete',group:'system',kind:'data',icon:'UserRoundPen',title:['Профиль','заполнен не полностью'],source:'Проверка полей профиля',surface:'status',
      when:'Валидатор указал конкретные отсутствующие или некорректные поля.',route:'Профиль: проблемное поле',limit:'Показывать точное поле, а не расплывчатую ошибку; не делать из этого аварию.'},
    {id:'activity-profile',group:'system',kind:'data',icon:'Activity',title:['Профиль активности','требует проверки'],source:'Проверка контекста активности',surface:'status',
      when:'Есть конкретная причина непригодности профиля активности.',route:'Активность: причина и настройки',limit:'Не блокировать просмотр CGM и не выдавать сообщение за риск глюкозы.'},
    {id:'ai-ready',group:'system',kind:'ai',icon:'MessageSquareText',title:['Новый анализ','Copilot'],source:'ИИ · информационный анализ',surface:'inbox',
      when:'Завершён анализ, известны его период и источники.',route:'ИИ: результат и исходные данные',limit:'Не оформлять рекомендацию ИИ как команду терапии или срочный сигнал.'},
    {id:'ai-failed',group:'system',kind:'ai',icon:'CloudOff',title:['Анализ ИИ','не выполнен'],source:'Состояние задания ИИ',surface:'inbox',
      when:'Получен явный сбой или подтверждённая недоступность задания.',route:'ИИ: запрос, ошибка и повтор',limit:'Сбой ИИ не должен отключать локальные оповещения о глюкозе.'}
  ].map(entry=>Object.freeze({...entry,title:Object.freeze(entry.title),origin:entry.nativeKind?'native':'proposal'})));
  const byId=new Map(entries.map(entry=>[entry.id,entry]));
  function get(id){const entry=byId.get(id);if(!entry)throw new Error('unknown catalogue entry');return entry;}
  function list({group='all',surface='all',query=''}={}){
    const needle=String(query).normalize('NFKC').trim().toLocaleLowerCase('ru-RU');
    return entries.filter(entry=>(group==='all'||entry.group===group)&&(surface==='all'||entry.surface===surface)&&
      (!needle||[...entry.title,entry.source,entry.route,entry.nativeKind??''].join(' ').normalize('NFKC').toLocaleLowerCase('ru-RU').includes(needle)));
  }
  function open(id,eventId){const entry=get(id);return {...base.open(entry.kind,eventId),catalogueId:id};}
  function sample(event,elapsed=0,options={}){
    if(!Number.isFinite(elapsed)||elapsed<0)throw new Error('invalid presentation time');
    const entry=get(event.catalogueId),motion=entry.surface==='alert';
    if(event.kind!==entry.kind)throw new Error('mismatched catalogue event');
    const s=base.sample(event,motion?elapsed:Math.max(elapsed,1000),options);
    if(event.active&&!options.locked){
      s.title=[...entry.title];s.source=entry.source;s.icon=entry.icon;
      if(options.fresh===false&&entry.group==='glucose'){
        s.title=entry.id==='low-now'?['Ранее зафиксирован','низкий уровень']:['Нет свежих','данных CGM'];
        s.source='Свежих данных нет · событие активно';
      }
    }
    if(entry.surface!=='alert'&&event.active){s.profile='Без звука';s.tier='info';s.rank=1;}
    if(event.active&&!options.locked)s.label=entry.surface==='alert'?(s.tier==='info'?'Напоминание':priorityLabels[s.tier]):surfaceLabels[entry.surface];
    return {...s,entry,surface:entry.surface};
  }
  const api=Object.freeze({groups,entries,surfaceLabels,priorityLabels,get,list,open,sample,reduce:base.reduce});
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
  else window.NotificationCatalogue12=api;
})();
