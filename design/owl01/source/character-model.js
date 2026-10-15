/* Finite character studies, not an AI or clinical status controller. */
(() => {
  const blink=typeof module!=='undefined'&&module.exports?require('./motion-model.js'):window.Motion12;
  const catalog=Object.freeze({
    gaze:Object.freeze({name:'Живой взгляд',duration:1600,blinkAt:940}),
    listen:Object.freeze({name:'Слушаю',duration:1600,blinkAt:900}),
    greet:Object.freeze({name:'Привет',duration:1400,blinkAt:720}),
    nod:Object.freeze({name:'Кивок',duration:1000,blinkAt:300})
  });
  const rest=()=>({gazeX:0,gazeY:0,headAngle:0,headY:0,leftWing:0,rightWing:0,y:1,opacity:1,lid:0});
  function describe(name){if(!Object.hasOwn(catalog,name))throw new Error('unknown gesture');return catalog[name];}
  function track(time,points){
    if(time<=points[0][0])return points[0][1];
    for(let i=1;i<points.length;i++){
      if(time===points[i][0])return points[i][1];
      if(time<points[i][0]){
        const [a,x]=points[i-1],[b,z]=points[i],p=(time-a)/(b-a),ease=p*p*(3-2*p);
        return x+(z-x)*ease;
      }
    }
    return points.at(-1)[1];
  }
  function sample(name,time,{reduced=false,visible=true}={}){
    const design=describe(name);
    if(!Number.isFinite(time)||time<0)throw new Error('invalid gesture time');
    const active=!reduced&&visible&&time<design.duration;
    if(!active)return {active,pose:rest()};
    const pose=rest();
    if(name==='gaze'){
      pose.gazeX=track(time,[[0,0],[180,0],[480,6],[900,6],[1210,0],[1600,0]]);
      pose.gazeY=pose.gazeX===0?0:-pose.gazeX/4;
    }
    if(name==='listen'){
      pose.headAngle=track(time,[[0,0],[180,0],[500,-4],[1000,-4],[1440,0],[1600,0]]);
      pose.gazeX=pose.headAngle*.75;pose.gazeY=pose.headAngle*.2;
    }
    if(name==='greet'){
      const wing=track(time,[[0,0],[100,0],[420,6],[560,6],[860,0],[1400,0]]);
      pose.leftWing=wing;pose.rightWing=wing===0?0:-wing;
      pose.headAngle=track(time,[[0,0],[120,0],[440,1.5],[780,0],[1400,0]]);
    }
    if(name==='nod'){
      pose.headY=track(time,[[0,0],[120,0],[360,4],[480,4],[760,0],[1000,0]]);
      pose.gazeY=pose.headY/3;
    }
    if(time>=design.blinkAt&&time<design.blinkAt+420)Object.assign(pose,blink.sampleBlink('inside',time-design.blinkAt));
    return {active,pose};
  }
  const api=Object.freeze({catalog,describe,rest,sample});
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
  else window.Character12=api;
})();
