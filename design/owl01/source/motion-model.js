/* Pure motion timing, shared by the preview and source-level checks. */
(() => {
  const points = Object.freeze([
    {at:0,y:1,opacity:1,lid:0},{at:.34,y:1,opacity:1,lid:0},
    {at:.45,y:.05,opacity:0,lid:1},{at:.49,y:.05,opacity:0,lid:1},
    {at:.61,y:1,opacity:1,lid:0},{at:1,y:1,opacity:1,lid:0}
  ]);
  const duration = mode => {
    if(mode==='launch')return 800;
    if(mode==='inside')return 420;
    throw new Error('unknown motion scenario');
  };
  const canAnimate = ({reduced,visible,mode,state}) => !reduced&&visible&&
    !(mode==='inside'&&['error','offline'].includes(state));
  const eyeFrames = () => ({
    open:points.map(point=>({offset:point.at,transform:`scaleY(${point.y})`,opacity:point.opacity})),
    closed:points.map(point=>({offset:point.at,opacity:point.lid}))
  });
  const sampleBlink = (mode,time) => {
    const position=Math.max(0,Math.min(1,time/duration(mode)));
    const exact=points.find(point=>point.at===position);
    if(exact)return {y:exact.y,opacity:exact.opacity,lid:exact.lid};
    const right=points.findIndex(point=>point.at>=position);
    if(right===0)return {y:1,opacity:1,lid:0};
    const a=points[right-1],b=points[right],mix=(position-a.at)/(b.at-a.at);
    return Object.fromEntries(['y','opacity','lid'].map(key=>[key,a[key]+(b[key]-a[key])*mix]));
  };
  const model=Object.freeze({duration,canAnimate,eyeFrames,sampleBlink,WAITING_MOTION_LIMIT:8000});
  if(typeof module!=='undefined'&&module.exports)module.exports=model;
  else window.Motion12=model;
})();
