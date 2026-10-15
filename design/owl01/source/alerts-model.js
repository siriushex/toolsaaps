/* Presentation study only. Alert kinds are supplied, never inferred from glucose. */
(() => {
  const base=typeof module!=='undefined'&&module.exports?require('./character-model.js'):window.Character12;
  const blink=typeof module!=='undefined'&&module.exports?require('./motion-model.js'):window.Motion12;
  const colors=typeof module!=='undefined'&&module.exports?require('./glucose-colors.js'):window.GlucoseColors12;
  const kinds=Object.freeze({
    reminder:{label:'Набор',tier:'info',title:['Проверьте срок','инфузионного набора'],source:'По подтверждённой смене',icon:'Clock',profile:'Без звука'},
    watch:{label:'Наблюдение',tier:'watch',title:['Наблюдение','за прогнозом'],source:'Прогноз · наблюдение',icon:'Eye',profile:'Вибрация'},
    warning:{label:'Внимание',tier:'warning',title:['Возможное снижение','по прогнозу'],source:'Прогноз на 30 минут',icon:'TriangleAlert',profile:'Мягкий сигнал + вибрация'},
    high:{label:'Внимание',tier:'warning',title:['Возможное повышение','по прогнозу'],source:'Прогноз · риск повышения',icon:'TrendingUp',profile:'Мягкий сигнал + вибрация'},
    forecastUrgent:{label:'Срочно',tier:'urgent',title:['Срочный риск','по прогнозу'],source:'Краткосрочный прогноз',icon:'TriangleAlert',profile:'Срочный сигнал + вибрация'},
    urgent:{label:'Срочно',tier:'urgent',title:['Низкая глюкоза','по данным CGM'],source:'Текущие данные CGM',icon:'TriangleAlert',profile:'Срочный сигнал + вибрация'},
    data:{label:'Данные',tier:'warning',title:['Нет свежих','данных CGM'],source:'Свежесть источника',icon:'WifiOff',profile:'Технический сигнал'},
    delivery:{label:'Доставка',tier:'warning',title:['Проверьте','доставку инсулина'],source:'Подтверждённая диагностика',icon:'Unplug',profile:'Технический сигнал'},
    ai:{label:'Анализ',tier:'info',title:['Новый анализ','Copilot'],source:'ИИ · информационное сообщение',icon:'MessageSquare',profile:'Без звука'}
  });
  const tiers={info:1,watch:2,warning:3,urgent:4};
  const iconMotionMs=300;
  function describe(kind){if(!Object.hasOwn(kinds,kind))throw new Error('unknown alert kind');return kinds[kind];}
  function open(kind,id){describe(kind);if(typeof id!=='string'||!id.trim())throw new Error('missing event identity');return {kind,id,active:true,seen:false,clipStopped:false};}
  function reduce(current,action){
    if(action.type==='OPEN'){
      const next=open(action.kind,action.id);
      if(current?.id===next.id&&current.kind===next.kind)return current;
      return next;
    }
    if(!current||action.id!==current.id)return current;
    if(action.type==='SOURCE_RESOLVED')return {...current,active:false,clipStopped:true};
    if(!current.active)return current;
    if(action.type==='ACK')return {...current,seen:true,clipStopped:true};
    if(action.type==='STOP_CLIP')return {...current,clipStopped:true};
    if(action.type==='ENGINE_REPEAT')return {...current,clipStopped:false};
    if(action.type==='VIEW_DETAILS')return current;
    throw new Error('unknown presentation action');
  }
  function readiness(flags={}){
    const issues=[];
    for(const [key,label] of [['permission','Уведомления запрещены'],['channel','Канал отключён'],
      ['sound','Звук канала выключен'],['background','Фоновая работа ограничена'],['route','Аудиовыход не проверен']]){
      if(flags[key]!==true)issues.push({key,label});
    }
    return {issues,status:issues.length?'Есть ограничения':'Настройки разрешают оповещения',
      deliveryVerified:false,testLabel:'Звук на устройстве не проверен'};
  }
  function sample(event,elapsed=0,{reduced=false,visible=true,fresh=true,locked=false,palette}={}){
    if(!Number.isFinite(elapsed)||elapsed<0)throw new Error('invalid presentation time');
    const kind=describe(event.kind),pose=base.rest();
    const duration=kind.tier==='urgent'?0:iconMotionMs;
    const active=event.active&&!reduced&&visible&&elapsed<duration;
    if(active)Object.assign(pose,blink.sampleBlink('inside',elapsed*420/iconMotionMs));
    if(palette)pose.colors=locked?colors.palettes.unknown:palette;
    let title=[...kind.title],source=kind.source;
    if(event.active&&!fresh&&['urgent','forecastUrgent','warning','high','watch'].includes(event.kind)){
      title=event.kind==='urgent'?['Ранее зафиксирован','низкий уровень']:['Данные прогноза','требуют обновления'];
      source='Свежих данных нет · событие активно';
    }
    if(!event.active){title=['Событие','завершено'];source='Закрыто источником события';}
    if(locked){title=event.active?['Copilot: требуется','ваше внимание']:['Copilot','Событие завершено'];source='Подробности после разблокировки';}
    return {active,pose,tier:event.active?kind.tier:'resolved',rank:event.active?tiers[kind.tier]:0,label:locked?(event.active?'Оповещение':'Завершено'):(event.active?kind.label:'Завершено'),title,source,
      icon:locked?'Bell':event.active?kind.icon:'CircleCheck',profile:event.active?kind.profile:'Сигнал остановлен',
      status:!event.active?'Причина больше не активна':event.clipStopped?'Текущий сигнал остановлен':event.seen?'Просмотрено':'Событие активно',
      conditionActive:event.active,seen:event.seen,clipStopped:event.clipStopped,
      allowLoadingAnimation:!event.active,deliveryVerified:false};
  }
  const api=Object.freeze({kinds,describe,open,reduce,readiness,sample,iconMotionMs});
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
  else window.AlertStudy12=api;
})();
