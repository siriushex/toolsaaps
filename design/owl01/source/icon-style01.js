/* Rounded Lucide contours with a quiet material edge at larger optical sizes. */
(() => {
  const escape=value=>String(value).replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
  function primitive(tag,attrs,size=24){
    const shape=`<${tag} ${Object.entries(attrs).map(([key,value])=>`${key}="${escape(value)}"`).join(' ')}/>`;
    return size<48?shape:`${shape}<g data-material-edge="soft-01" stroke="#fff" stroke-width=".55" opacity=".32" transform="translate(-.12 -.12)" pointer-events="none">${shape}</g>`;
  }
  const api=Object.freeze({primitive});
  if(typeof module!=='undefined'&&module.exports)module.exports=api;else window.IconStyle01=api;
})();
