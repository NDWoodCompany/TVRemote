const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
const INS={tv:'TV',hdmi1:'HDMI 1',hdmi2:'HDMI 2',hdmi3:'HDMI 3',hdmi4:'HDMI 4',input:'Chuyển vòng'};
const APPS={'com.teamsmart.videomanager.tv':'SmartTube','com.liskovsoft.smarttubetv.beta':'SmartTube Beta','com.google.android.youtube.tv':'YouTube (Android TV)','com.google.android.youtube':'YouTube (bản điện thoại)','':'Mặc định của Box (có thể hiện hộp chọn)'};
const F=[['sonyIp','IP Sony BRAVIA'],['psk','Sony PSK (nếu TV đã đặt)','password'],['tvMac','MAC của TV (tùy chọn – để bật TV từ standby)'],['boxIp','IP Android Box'],['adbPort','Cổng ADB (mặc định 5555)']];
let target='box',tt,BASE=localStorage.bridge||'';
function toast(t){const e=$('#toast');e.textContent=t;e.classList.add('show');clearTimeout(tt);tt=setTimeout(()=>e.classList.remove('show'),3000)}
const st=t=>$('#st').textContent=t;
async function api(p,b){let j;try{const r=await fetch(BASE+'api/'+p,b===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});j=await r.json()}
catch(e){$('#warn').hidden=false;toast('Không gọi được bridge');throw e}
if(!j.ok){toast(j.error||'Lỗi');throw Error(j.error)}return j}
const sendS=c=>api('sony',{cmd:c}),sendB=k=>api('box',{key:k});
function hold(el,fn){let on=0,t;const loop=()=>{if(!on)return;fn().then(()=>{on&&(t=setTimeout(loop,40))}).catch(()=>on=0)};
el.addEventListener('pointerdown',e=>{e.preventDefault();on=1;navigator.vibrate&&navigator.vibrate(10);fn().then(()=>{t=setTimeout(loop,420)}).catch(()=>on=0)});
['pointerup','pointerleave','pointercancel'].forEach(n=>el.addEventListener(n,()=>{on=0;clearTimeout(t)}))}
function bind(b,f){b.hasAttribute('data-r')?hold(b,f):b.addEventListener('click',()=>{navigator.vibrate&&navigator.vibrate(10);f().catch(()=>{})})}
$$('[data-s]').forEach(b=>bind(b,()=>sendS(b.dataset.s)));
$$('[data-b]').forEach(b=>bind(b,()=>sendB(b.dataset.b)));
// nguồn vào
const sh=$('#sheet>div');for(const k in INS){const b=document.createElement('button');b.textContent=INS[k];b.onclick=()=>{sendS(k).then(()=>{if(k!=='input'){localStorage.tvIn=k;cap()}}).catch(()=>{});$('#sheet').hidden=true};sh.append(b)}
const cap=()=>$('#inCap').textContent=INS[localStorage.tvIn||'hdmi2'].toUpperCase();cap();
$('#inBtn').onclick=()=>$('#sheet').hidden=false;$('#sheet').onclick=e=>{if(e.target.id==='sheet')e.target.hidden=true};
// điều hướng
$$('nav button').forEach(b=>b.onclick=()=>{$$('.view').forEach(v=>v.classList.toggle('on',v.id===b.dataset.v));$$('nav button').forEach(n=>n.classList.toggle('on',n===b))});
// bàn phím số
$('#pad').innerHTML='123456789*0#'.split('').map(k=>`<button>${k}</button>`).join('');
$$('#pad button').forEach(b=>b.onclick=()=>{const k=b.textContent;(target==='tv'?(/\d/.test(k)?sendS('num'+k):Promise.resolve()):sendB(k)).catch(()=>{})});
$$('#tgt button').forEach(b=>b.onclick=()=>{target=b.dataset.t;$$('#tgt button').forEach(x=>x.classList.toggle('on',x===b))});
// giọng nói + gõ text
const SR=window.SpeechRecognition||window.webkitSpeechRecognition;
function manual(m){st(m);$('#q').focus()}
$('#mic').onclick=()=>{$('#ssheet').hidden=false;if(!SR)return manual('Trình duyệt không hỗ trợ giọng nói – chạm ô nhập rồi dùng micro trên bàn phím (Gboard).');
const r=new SR();r.lang=localStorage.lang||'vi-VN';r.interimResults=true;r.onstart=()=>st('Đang nghe…');
r.onresult=e=>{const x=e.results[e.results.length-1];$('#q').value=x[0].transcript;if(x.isFinal){st('Đã nhận: '+x[0].transcript);doSearch()}};
r.onerror=e=>manual('Micro trình duyệt không dùng được ('+e.error+'). Chạm ô nhập rồi bấm micro trên bàn phím.');
try{r.start()}catch(e){}};
const val=()=>{const q=$('#q').value.trim();if(!q)toast('Nhập hoặc nói nội dung trước');return q};
async function doSearch(){const q=val();if(!q)return;st('Đang gửi tìm kiếm tới Box…');try{await api('box/search',{q});st('Đã gửi: '+q)}catch(e){st('Lỗi: '+e.message)}}
$('#goSearch').onclick=doSearch;$('#q').addEventListener('keydown',e=>{if(e.key==='Enter')doSearch()});
$('#goType').onclick=async()=>{const q=val();if(!q)return;st('Đang gõ vào Box…');try{await api('box/text',{text:q,enter:$('#ent').checked});st('Đã gõ: '+q)}catch(e){st('Lỗi: '+e.message)}};
$('#goOpen').onclick=()=>api('box/open',{}).then(()=>st('Đã mở app trên Box')).catch(e=>st('Lỗi: '+e.message));
// cài đặt
const fm=$('#form');fm.innerHTML=F.map(([k,l,t])=>`<label>${l}</label><input type="${t||'text'}" id="f_${k}" autocomplete="off">`).join('')+
`<label>App dùng cho "Tìm trên Box"</label><select id="f_searchApp">${Object.entries(APPS).map(([k,v])=>`<option value="${k}">${v}</option>`).join('')}</select>
<label>TV tự bật/tắt theo Box (chỉ khi chạy bằng APK)</label><select id="f_follow"><option value="1">Bật</option><option value="0">Tắt</option></select>
<label>Ngôn ngữ giọng nói (trình duyệt)</label><select id="f_lang"><option value="vi-VN">Tiếng Việt</option><option value="en-US">English</option></select>`;
$('#f_lang').value=localStorage.lang||'vi-VN';
const loadCfg=()=>api('config').then(({cfg})=>{F.forEach(([k])=>{if(k!=='psk')$('#f_'+k).value=cfg[k]??''});$('#f_psk').placeholder=cfg.pskSet?'(đã lưu – để trống nếu giữ nguyên)':'';$('#f_searchApp').value=cfg.searchApp;$('#f_follow').value=cfg.follow||'1'}).catch(()=>{});
// Trang HTTPS (GitHub Pages): tự tìm bridge chạy trên chính thiết bị này (localhost được trình duyệt cho phép)
(async()=>{if(location.protocol==='https:'){for(const b of [BASE,'http://127.0.0.1:8765/'].filter(Boolean)){try{const r=await fetch(b+'api/config',{signal:AbortSignal.timeout(1500)});if((await r.json()).ok){BASE=b;localStorage.bridge=b;break}}catch{}}}loadCfg()})();
$('#save').onclick=()=>{localStorage.lang=$('#f_lang').value;const b={};F.concat([['searchApp'],['follow']]).forEach(([k])=>b[k]=$('#f_'+k).value);api('config',b).then(()=>toast('Đã lưu')).catch(()=>{})};
$('#diag').onclick=async()=>{$('#res').textContent='Đang kiểm tra…';try{const r=await api('status');$('#res').textContent='Sony: '+r.sony+'\nBox: '+r.box}catch(e){$('#res').textContent=''}};
if('serviceWorker'in navigator&&isSecureContext){const had=!!navigator.serviceWorker.controller;let rl;navigator.serviceWorker.register('sw.js',{updateViaCache:'none'}).then(r=>r.update());navigator.serviceWorker.addEventListener('controllerchange',()=>{if(had&&!rl){rl=1;location.reload()}})}
$('#ssheet').onclick=e=>{if(e.target.id==='ssheet')e.target.hidden=true};$('#warn').onclick=e=>e.currentTarget.hidden=true;
