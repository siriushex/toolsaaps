/* User-selected visual bands only. Never changes alarm or therapy thresholds. */
(() => {
  const duration=600;
  const palettes={
    white:{body:['#ffffff','#f7f9fc','#e3eaf1','#aebdcb'],wings:['#edf2f8','#b7c6d5'],eyes:['#ffffff','#dce6ef','#8fa3b7'],edge:'#697e90',outline:'#697e90'},
    yellow:{body:['#fff0b7','#f5d263','#deb137','#a57513'],wings:['#ebc24b','#b5871c'],eyes:['#fff0a5','#ecc44a','#976d14'],edge:'#876213',outline:'#a57513'},
    green:{body:['#a4e8be','#42bf82','#219b68','#096848'],wings:['#3bb47c','#16835a'],eyes:['#a6efbc','#3bc47f','#086443'],edge:'#0a6548',outline:'#16835a'},
    red:{body:['#ff9c99','#f45b60','#dd3549','#a61d36'],wings:['#ef5860','#c52f44'],eyes:['#ffa7a0','#ed5e61','#9e2337'],edge:'#a51b32',outline:'#c52f44'},
    purple:{body:['#dac1f3','#ae84db','#8858bb','#54277f'],wings:['#a474ce','#71439f'],eyes:['#e3caff','#b88aec','#653496'],edge:'#512578',outline:'#71439f'},
    unknown:{body:['#d5dde2','#acb9c2','#81949f','#526873'],wings:['#a2b2bc','#6b818e'],eyes:['#dce3e7','#a6b4bf','#5d707e'],edge:'#4e6673',outline:'#6b818e'}
  };
  const labels={white:'Ниже 2,8',yellow:'Ниже 3,6',green:'В вашем диапазоне',red:'Выше 9',purple:'Выше 13',unknown:'Нет актуальных данных'};
  function classify({glucose,fresh=true,available=true}={}){
    const valid=available&&fresh&&Number.isFinite(glucose)&&glucose>0;
    const band=!valid?'unknown':glucose<2.8?'white':glucose<3.6?'yellow':glucose>13?'purple':glucose>9?'red':'green';
    return {band,label:labels[band],palette:palettes[band],valid,glucose:available&&Number.isFinite(glucose)&&glucose>0?glucose:null,
      urgencyChanged:false};
  }
  function rgb(hex){return hex.slice(1).match(/../g).map(v=>parseInt(v,16)/255);}
  function hsl(hex){
    const [r,g,b]=rgb(hex),max=Math.max(r,g,b),min=Math.min(r,g,b),d=max-min,l=(max+min)/2;
    if(d===0)return [0,0,l];
    let h=max===r?((g-b)/d)%6:max===g?(b-r)/d+2:(r-g)/d+4;
    return [(h*60+360)%360,d/(1-Math.abs(2*l-1)),l];
  }
  function hexHsl([h,s,l]){
    const c=(1-Math.abs(2*l-1))*s,x=c*(1-Math.abs((h/60)%2-1)),m=l-c/2;
    const [r,g,b]=h<60?[c,x,0]:h<120?[x,c,0]:h<180?[0,c,x]:h<240?[0,x,c]:h<300?[x,0,c]:[c,0,x];
    return '#'+[r,g,b].map(v=>Math.round(Math.max(0,Math.min(1,v+m))*255).toString(16).padStart(2,'0')).join('');
  }
  function blendColor(from,to,p){
    if(p<=0)return from;if(p>=1)return to;
    const ar=rgb(from),br=rgb(to),chroma=c=>Math.max(...c)-Math.min(...c);
    if(chroma(ar)<.18||chroma(br)<.18)return '#'+ar.map((v,i)=>Math.round((v+(br[i]-v)*p)*255).toString(16).padStart(2,'0')).join('');
    const a=hsl(from),b=hsl(to);if(a[1]<.02)a[0]=b[0];if(b[1]<.02)b[0]=a[0];
    const hue=((b[0]-a[0]+540)%360)-180;
    return hexHsl([(a[0]+hue*p+360)%360,a[1]+(b[1]-a[1])*p,a[2]+(b[2]-a[2])*p]);
  }
  function mix(from,to,p){return {body:from.body.map((color,i)=>blendColor(color,to.body[i],p)),
    wings:from.wings.map((color,i)=>blendColor(color,to.wings[i],p)),eyes:from.eyes.map((color,i)=>blendColor(color,to.eyes[i],p)),
    edge:blendColor(from.edge,to.edge,p),outline:blendColor(from.outline,to.outline,p)};}
  function at(transition,now,{reduced=false,visible=true}={}){
    if(!Number.isFinite(now)||now<0)throw new Error('invalid colour clock');
    const p=reduced||!visible||transition.duration===0?1:Math.max(0,Math.min(1,(now-transition.startedAt)/transition.duration));
    return {palette:mix(transition.from,transition.to,p*p*(3-2*p)),active:p<1};
  }
  function begin(previous,state,now,options={}){
    if(!Number.isFinite(now)||now<0)throw new Error('invalid colour clock');
    const from=previous?at(previous,now,options).palette:state.palette;
    return {from,to:state.palette,startedAt:now,duration:!previous||!state.valid||options.reduced||options.visible===false?0:duration};
  }
  const api=Object.freeze({duration,palettes,labels,classify,blendColor,mix,at,begin});
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
  else window.GlucoseColors12=api;
})();
