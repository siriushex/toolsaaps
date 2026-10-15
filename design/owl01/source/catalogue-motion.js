/* Finite local icon studies. No incident, delivery or therapy state is changed. */
(() => {
  const local=typeof module!=='undefined'&&module.exports;
  const catalogue=local?require('./notification-catalogue.js'):window.NotificationCatalogue12;
  const finish=local?require('./icon-style01.js'):window.IconStyle01;
  const recipes={
    'low-now':{duration:260,label:'Акцент контура',manual:true,parts:[{indices:[0],effect:'weight',amount:.34}]},
    'low-imminent':{duration:300,label:'Акцент предупреждения',manual:true,parts:[{indices:[1,2],effect:'weight',amount:.3}]},
    'low-warning':{duration:540,label:'Направление снижения',parts:[{indices:[0],effect:'shift',x:.7,y:.7},{indices:[1],effect:'trace'}]},
    'low-watch':{duration:620,label:'Внимательный взгляд',parts:[{indices:[1],effect:'shift',x:.7,y:.25}]},
    'high-risk':{duration:540,label:'Направление роста',parts:[{indices:[0],effect:'shift',x:.7,y:-.7},{indices:[1],effect:'trace'}]},
    'high-measured':{duration:460,label:'Короткий подъём',parts:[{indices:[0,1],effect:'shift',y:-.75}]},
    'rise-trend':{duration:460,label:'Рост по диагонали',parts:[{indices:[0,1],effect:'shift',x:.65,y:-.65}]},
    'fall-trend':{duration:460,label:'Снижение по диагонали',parts:[{indices:[0,1],effect:'shift',x:.65,y:.65}]},
    'cgm-stale':{duration:620,label:'Сигнал прерван',parts:[{indices:[1,2,3,4,5],effect:'weight',amount:.32,stagger:.06}]},
    'aaps-stale':{duration:580,label:'Обмен приостановлен',parts:[{indices:[0,1,2,3,4,5],effect:'rotate',angle:-5,pivot:[12,12]}]},
    'sensor-quality':{duration:720,label:'Проход сканера',parts:[{indices:[4],effect:'shift',y:2.5}]},
    'measurement-mismatch':{duration:640,label:'Сопоставление измерений',parts:[{indices:[1,2],effect:'shift',x:-.65},{indices:[4,5],effect:'shift',x:.65,delay:.16}]},
    'prediction-unavailable':{duration:680,label:'Контур прогноза',parts:[{indices:[3],effect:'trace'},{indices:[0,1,2,4,5],effect:'weight',amount:.3,stagger:.04}]},
    'iob-missing':{duration:540,label:'Данные активного инсулина',parts:[{indices:[3],effect:'weight',amount:.65}]},
    'cob-missing':{duration:640,label:'Неизвестный остаток',parts:[{indices:[2,3,4,5,6,7,8],effect:'weight',amount:.34,stagger:.025}]},
    'isf-change':{duration:680,label:'Изменение чувствительности',parts:[{indices:[0],effect:'rotate',angle:11,pivot:[12,14]}]},
    'delivery-check':{duration:440,label:'Проверка доставки',parts:[{indices:[1,2],effect:'weight',amount:.55}]},
    'pump-disconnected':{duration:640,label:'Разрыв соединения',parts:[{indices:[0,5],effect:'shift',x:.5,y:-.5},{indices:[1,2,3,4],effect:'shift',x:-.5,y:.5}]},
    'pump-paused':{duration:400,label:'Пауза подачи',parts:[{indices:[1],effect:'shift',x:-.3},{indices:[2],effect:'shift',x:.3}]},
    'bolus-unconfirmed':{duration:560,label:'Подтверждение отсутствует',parts:[{indices:[0,5],effect:'weight',amount:.5,delay:.12}]},
    'target-rejected':{duration:420,label:'Цель отклонена',parts:[{indices:[1,2],effect:'weight',amount:.5}]},
    'target-pending':{duration:720,label:'Ожидание подтверждения',parts:[{indices:[1],effect:'rotate',angle:14,pivot:[12,12]}]},
    'eating-soon':{duration:560,label:'Подготовка к еде',parts:[{indices:[0,1],effect:'shift',y:-.65},{indices:[2],effect:'shift',y:-.65,delay:.2}]},
    'meal-unconfirmed':{duration:660,label:'Ожидание записи еды',parts:[{indices:[0],effect:'rotate',angle:13,pivot:[16,16]}]},
    'meal-model-uncertain':{duration:720,label:'Последовательность усвоения',parts:[{indices:[1,5,2,6,3,7,4],effect:'weight',amount:.45,stagger:.055}]},
    'set-due':{duration:620,label:'Время смены набора',parts:[{indices:[1],effect:'rotate',angle:10,pivot:[12,12]}]},
    'set-date-unknown':{duration:500,label:'Дата набора не указана',parts:[{indices:[4,5],effect:'weight',amount:.48}]},
    'sensor-due':{duration:720,label:'Срок сенсора',parts:[{indices:[0],effect:'rotate',angle:15,pivot:[16,16]}]},
    'sensor-date-unknown':{duration:560,label:'Срок не определён',parts:[{indices:[0,1],effect:'shift',y:-.45},{indices:[4,5],effect:'weight',amount:.3,delay:.2}]},
    'pump-battery':{duration:540,label:'Низкий заряд',parts:[{indices:[1],effect:'weight',amount:.7}]},
    'reservoir-low':{duration:620,label:'Малый остаток',parts:[{indices:[3,4],effect:'weight',amount:.48,stagger:.14}]},
    'notifications-blocked':{duration:500,label:'Уведомления отключены',parts:[{indices:[0],effect:'shift',y:.5},{indices:[2],effect:'weight',amount:.45}]},
    'channel-silent':{duration:460,label:'Звук выключен',parts:[{indices:[1,2],effect:'weight',amount:.48}]},
    'background-restricted':{duration:500,label:'Ограничение фона',parts:[{indices:[0,1],effect:'weight',amount:.45}]},
    'audio-route':{duration:660,label:'Проверка аудиовыхода',parts:[{indices:[0],effect:'trace'}]},
    'signal-paused':{duration:520,label:'Приостановленный сигнал',parts:[{indices:[4],effect:'weight',amount:.45}]},
    'profile-incomplete':{duration:600,label:'Уточнение профиля',parts:[{indices:[1],effect:'shift',x:.55,y:-.55}]},
    'activity-profile':{duration:700,label:'Контур активности',parts:[{indices:[0],effect:'trace'}]},
    'ai-ready':{duration:760,label:'Новый текст анализа',parts:[{indices:[3,1,2],effect:'weight',amount:.5,stagger:.15}]},
    'ai-failed':{duration:560,label:'Анализ недоступен',parts:[{indices:[2],effect:'weight',amount:.42}]}
  };
  for(const recipe of Object.values(recipes)){
    for(const part of recipe.parts){Object.freeze(part.indices);if(part.pivot)Object.freeze(part.pivot);Object.freeze(part);}
    Object.freeze(recipe.parts);Object.freeze(recipe);
  }
  Object.freeze(recipes);
  const rest=()=>({x:0,y:0,angle:0,pivot:[12,12],weight:1.8,trace:0,offset:100});
  const finite=(v,fallback=0)=>Number.isFinite(v)?v:fallback;
  function sample(id,time,{reduced=false,visible=true,locked=false,manual=false}={}){
    const recipe=recipes[id];if(!recipe)throw new Error('unknown motion');
    if(!Number.isFinite(time)||time<0)throw new Error('invalid motion time');
    const enabled=!reduced&&visible&&!locked&&(!recipe.manual||manual);
    const active=enabled&&time<recipe.duration;
    const parts={};
    for(const group of recipe.parts)group.indices.forEach((index,order)=>{
      const pose=parts[index]??=rest();
      if(!active||time===0)return;
      const delay=(group.delay??0)+order*(group.stagger??0),span=Math.max(.25,1-delay);
      const p=Math.max(0,Math.min(1,(time/recipe.duration-delay)/span));
      const wave=p===0||p===1?0:Math.sin(Math.PI*p)**2;
      if(group.effect==='shift'){pose.x+=finite(group.x)*wave;pose.y+=finite(group.y)*wave;}
      if(group.effect==='rotate'){pose.angle+=group.angle*wave;pose.pivot=group.pivot;}
      if(group.effect==='weight')pose.weight+=group.amount*wave;
      if(group.effect==='trace'){pose.trace=.6*wave;pose.offset=24-48*p;}
    });
    return {active,duration:recipe.duration,label:recipe.label,parts};
  }
  const escape=value=>String(value).replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
  const transform=p=>`translate(${p.x} ${p.y}) rotate(${p.angle} ${p.pivot.join(' ')})`;
  function markup(id,icons,{size=48,time=0,...options}={}){
    const entry=catalogue.get(id),shapes=icons[entry.icon];if(!shapes)throw new Error('missing glyph');
    if(![16,24,48,64].includes(size))throw new Error('unsupported optical size');
    const state=sample(id,time,options);
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false" data-motion-icon="${id}">${shapes.map(([tag,attrs],i)=>{
      const p=state.parts[i]??rest(),shape=finish.primitive(tag,attrs,size);
      const trace=recipes[id].parts.some(part=>part.indices.includes(i)&&part.effect==='trace');
      return `<g data-motion-part="${i}" transform="${transform(p)}" stroke-width="${p.weight}">${shape}${trace?`<g data-motion-trace="${i}" opacity="${p.trace}" stroke-width="2.5" stroke-dasharray="4 20" stroke-dashoffset="${p.offset}">${shape}</g>`:''}</g>`;
    }).join('')}</svg>`;
  }
  function update(root,state){
    for(const node of root.querySelectorAll('[data-motion-part]')){
      const p=state.parts[Number(node.dataset.motionPart)]??rest();
      node.setAttribute('transform',transform(p));node.setAttribute('stroke-width',p.weight);
    }
    for(const node of root.querySelectorAll('[data-motion-trace]')){
      const p=state.parts[Number(node.dataset.motionTrace)]??rest();
      node.setAttribute('opacity',p.trace);node.setAttribute('stroke-dashoffset',p.offset);
    }
  }
  const api=Object.freeze({recipes,sample,markup,update});
  if(local)module.exports=api;else window.CatalogueMotion12=api;
})();
