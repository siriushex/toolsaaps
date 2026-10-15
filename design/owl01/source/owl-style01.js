/* Shared feathered artwork. Optical detail follows the final displayed size. */
(() => {
  const local=typeof module!=='undefined'&&module.exports;
  const material=local?require('./style01-material.js'):window.OwlMaterial01;
  let serial=0;
  const eyesGeometry=Object.freeze({centers:Object.freeze([96,184]),cy:151,rx:23,ry:17,iris:16.8});
  const defaultColors=Object.freeze({body:Object.freeze(['#ff7275','#f2474d','#df303d','#ad172d']),
    wings:Object.freeze(['#ee454d','#c32539']),edge:'#b11e34',outline:'none'});
  const drop='M140 12C134 12 130 26 121 39C99 70 57 100 42 153C23 226 75 266 140 266C205 266 257 226 238 153C223 100 181 70 159 39C150 26 146 12 140 12Z';
  const mask='M140 129C125 117 110 101 88 105C58 110 48 142 59 165C70 190 98 191 107 213Q140 233 173 213C182 191 210 190 221 165C232 142 222 110 192 105C170 101 155 117 140 129Z';
  const wing='M46 151C34 224 85 249 113 254C112 232 103 214 89 202C62 181 49 174 46 151Z';
  const layers=['M46 158C49 188 69 203 92 209C81 187 61 179 46 158Z',
    'M46 181C51 210 74 226 104 234C96 212 66 207 46 181Z',
    'M55 209C65 232 88 246 113 254C109 236 80 231 55 209Z'];
  function markup(size=64,p={},options={}){
    const id='feather-'+(++serial),flat=!!options.flat,launch=!!options.launch;
    const optical=p.opticalSize??size,detail=optical>=48,texture=optical>=96&&!flat;
    const colors=p.colors??defaultColors,eyes=colors.eyes??['#414747','#171c1d','#0c1113'];
    const paint=(name,color)=>flat?color:`url(#${id}-${name})`;
    const feathers=shape=>texture?`<g class="soft-feathers" opacity="${p.material==='smooth'?0:1}" style="mix-blend-mode:soft-light"><path d="${shape}" fill="url(#${id}-feathers)" opacity=".52"/></g>`:'';
    const wingArt=`<path d="${wing}" fill="${paint('wing',colors.wings[0])}"/>${detail?layers.map((d,i)=>`<path class="layered-feather" d="${d}" fill="${paint('wing',colors.wings[0])}"/><path class="wing-detail" d="${d}" fill="none" stroke="${colors.edge}" stroke-opacity="${.2+i*.025}" stroke-width="1.4"/><path d="${d}" fill="none" stroke="#fff" stroke-opacity=".08" stroke-width=".55" transform="translate(.7 -.7)"/>`).join(''):''}${feathers(wing)}`;
    return `<svg xmlns="http://www.w3.org/2000/svg" class="owl" width="${size}" height="${size}" viewBox="0 0 280 280" role="img" aria-label="Copilot: сова-капля" data-flat="${flat}" data-style="feather-01" data-optical-size="${optical}"><defs>
      <linearGradient class="body-gradient" id="${id}-red" x1="42" y1="20" x2="235" y2="265" gradientUnits="userSpaceOnUse"><stop stop-color="${colors.body[0]}"/><stop offset=".36" stop-color="${colors.body[1]}"/><stop offset=".76" stop-color="${colors.body[2]}"/><stop offset="1" stop-color="${colors.body[3]}"/></linearGradient>
      <linearGradient id="${id}-teal" x1="95" y1="105" x2="175" y2="265" gradientUnits="userSpaceOnUse"><stop stop-color="#227b87"/><stop offset=".46" stop-color="#075366"/><stop offset="1" stop-color="#063746"/></linearGradient>
      <radialGradient id="${id}-white" cx=".4" cy=".3" r="1"><stop stop-color="#fffdf8"/><stop offset=".7" stop-color="#f4f7f3"/><stop offset="1" stop-color="#e9efec"/></radialGradient>
      <radialGradient class="iris-gradient" id="${id}-pupil" cx=".34" cy=".25" r=".8"><stop stop-color="${eyes[0]}"/><stop offset=".5" stop-color="${eyes[1]}"/><stop offset="1" stop-color="${eyes[2]}"/></radialGradient>
      <linearGradient class="wing-gradient" id="${id}-wing" x1="46" y1="155" x2="103" y2="254" gradientUnits="userSpaceOnUse"><stop stop-color="${colors.wings[0]}"/><stop offset="1" stop-color="${colors.wings[1]}"/></linearGradient>
      <linearGradient id="${id}-glint"><stop stop-color="#fff" stop-opacity="0"/><stop offset=".5" stop-color="#fff"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
      <clipPath id="${id}-outline"><path d="${drop}"/></clipPath>
      ${texture?`<pattern id="${id}-feathers" width="78" height="78" patternUnits="userSpaceOnUse"><image href="${material}" width="78" height="78"/></pattern>`:''}
      </defs><g class="owl-body" opacity="${p.bodyOpacity??1}" transform="translate(140 140) scale(${p.bodyScale??1}) translate(-140 -140)">
      <path class="${launch?'body-fill':'body'}" d="${drop}" fill="${paint('red',colors.body[1])}"/>${feathers(drop)}
      <g clip-path="url(#${id}-outline)">
        <g class="left-wing" transform="rotate(${p.leftWing??0} 55 160)">${wingArt}</g>
        <g class="${launch?'wing-right':'right-wing'}" style="transform-origin:225px 160px" transform="rotate(${p.rightWing??0} 225 160)"><g transform="translate(280 0) scale(-1 1)">${wingArt}</g></g>
        <path class="breast" d="M104 191C115 221 116 246 116 266H164C164 246 165 221 176 191Z" fill="${paint('teal','#075366')}"/>
        ${feathers('M104 191C115 221 116 246 116 266H164C164 246 165 221 176 191Z')}
        <g class="owl-glint" opacity="${p.glintOpacity??0}" transform="translate(${p.glintX??-90} 0)"><path fill="url(#${id}-glint)" d="M-48-20H8L116 300H60Z"/></g>
        <g class="head" transform="translate(0 ${p.headY??0}) rotate(${p.headAngle??0} 140 156)">
          <path class="face-fill" d="${mask}" fill="${paint('teal','#075366')}"/>${feathers(mask)}
          ${eyesGeometry.centers.map((x,i)=>{const target=p.pupils?.[i]??{x:p.gazeX??0,y:p.gazeY??0};const aperture=`M${x-23} 153C${x-19} 135 ${x+10} 132 ${x+23} 146C${x+22} 161 ${x+12} 168 ${x} 168C${x-12} 168 ${x-20} 163 ${x-23} 153Z`;return `<g class="${launch?'eye-open '+(i?'eye-right':'eye-left'):'open-eye eye-'+i}" style="transform-origin:${x}px 151px" opacity="${p.opacity??1}" transform="translate(${x} 151) scale(1 ${p.y??1}) translate(-${x} -151)"><defs><clipPath id="${id}-eye-${i}"><path d="${aperture}"/></clipPath></defs><path class="eye-white" d="${aperture}" fill="${paint('white','#f4f7f3')}"/><g clip-path="url(#${id}-eye-${i})"><g class="pupil" transform="translate(${target.x} ${target.y})"><circle cx="${x}" cy="151" r="16.8" fill="${paint('pupil',eyes[1])}"/><circle class="pupil-core" cx="${x}" cy="151" r="9.4" fill="#0c1113" opacity="${colors.eyes?1:0}"/><circle cx="${x-3.2}" cy="147.2" r="1.6" fill="#fff" opacity=".48"/></g></g><path class="upper-lid" d="M${x-23} 153C${x-19} 135 ${x+10} 132 ${x+23} 146" fill="none" stroke="#063d4a" stroke-width="${detail?3.6:2.1}" stroke-opacity=".78" stroke-linecap="round"/></g>`;}).join('')}
          <g class="${launch?'eye-closed':'lids'}" opacity="${p.lid??0}" fill="none" stroke="#033645" stroke-width="4.2" stroke-linecap="round"><path d="M76 151Q96 161 116 151"/><path d="M164 151Q184 161 204 151"/></g>
          <path class="beak" d="M137 178Q140 174 143 178L151 187Q154 190 151 194L143 209Q140 214 137 209L129 194Q126 190 129 187Z" fill="${paint('white','#fff')}"/>
        </g></g><path class="status-outline" d="${drop}" fill="none" stroke="${colors.outline??'none'}" stroke-width="1.4"/></g></svg>`;
  }
  const api=Object.freeze({markup,defaultColors,eyesGeometry,drop,mask});
  if(local)module.exports=api;else window.OwlStyle01=api;
})();
