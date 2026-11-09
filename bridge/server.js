'use strict';
// TVRemote bridge: phục vụ giao diện + chuyển lệnh sang Sony (IRCC/WoL) và Android Box (ADB). Cần Node 18+ và adb.
const http=require('http'),fs=require('fs'),os=require('os'),path=require('path'),dgram=require('dgram'),{execFile}=require('child_process');
const WEB=path.join(__dirname,'..'),CFG=path.join(__dirname,'config.json');
const DEF={port:8765,sonyIp:'192.168.1.234',psk:'',tvMac:'',boxIp:'192.168.1.233',adbPort:5555,adbPath:'adb',searchApp:'com.teamsmart.videomanager.tv'};
let cfg={...DEF};try{cfg={...DEF,...JSON.parse(fs.readFileSync(CFG,'utf8'))}}catch{}
const saveCfg=()=>fs.writeFileSync(CFG,JSON.stringify(cfg,null,2));
// ---------- Sony ----------
const ir=id=>'AAAAAQAAAAEAAAA'+String.fromCharCode(65+id)+'Aw==';
const IRCC={power:ir(21),wake:ir(46),off:ir(47),input:ir(37),tv:ir(36),volUp:ir(18),volDown:ir(19),mute:ir(20),chUp:ir(16),chDown:ir(17),
 hdmi1:'AAAAAgAAABoAAABaAw==',hdmi2:'AAAAAgAAABoAAABbAw==',hdmi3:'AAAAAgAAABoAAABcAw==',hdmi4:'AAAAAgAAABoAAABdAw=='};
for(let i=0;i<10;i++)IRCC['num'+i]=ir((i+9)%10);
const sonyReq=(u,o={})=>fetch('http://'+cfg.sonyIp+u,{...o,headers:{...(o.headers||{}),...(cfg.psk?{'X-Auth-PSK':cfg.psk}:{})},signal:AbortSignal.timeout(4000)});
async function ircc(code){
 const body=`<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:X_SendIRCC xmlns:u="urn:schemas-sony-com:service:IRCC:1"><IRCCCode>${code}</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>`;
 let r;try{r=await sonyReq('/sony/IRCC',{method:'POST',headers:{'Content-Type':'text/xml; charset=UTF-8',SOAPAction:'"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC"'},body})}catch(e){throw Error('Không kết nối được TV ('+cfg.sonyIp+'): '+e.message)}
 if(!r.ok)throw Error('TV trả HTTP '+r.status+(r.status===403?' – sai/thiếu PSK hoặc chưa bật IP Control':''))}
function wol(){const m=cfg.tvMac.replace(/[^0-9a-f]/gi,'');if(m.length!==12)return false;
 const p=Buffer.concat([Buffer.alloc(6,255),...Array(16).fill(Buffer.from(m,'hex'))]),s=dgram.createSocket('udp4');
 s.bind(()=>{s.setBroadcast(true);['255.255.255.255',cfg.sonyIp.replace(/\d+$/,'255')].forEach(a=>s.send(p,9,a));setTimeout(()=>s.close(),500)});return true}
const powerStatus=async()=>{try{const r=await sonyReq('/sony/system',{method:'POST',body:JSON.stringify({method:'getPowerStatus',id:1,params:[],version:'1.0'})});return (await r.json()).result?.[0]?.status||''}catch{return''}};
async function tvPower(){const s=await powerStatus();
 if(s==='standby')wol();
 return ircc(IRCC.power)}
// ---------- ADB / Android Box ----------
const shq=s=>"'"+String(s).replace(/'/g,"'\\''")+"'",tgt=()=>cfg.boxIp+':'+cfg.adbPort;
const run=(f,a,t=8000)=>new Promise((ok,no)=>execFile(f,a,{timeout:t,windowsHide:true},(e,o,er)=>e?no(Error((String(er||o||e.message)).trim())):ok(String(o).trim())));
let Q=Promise.resolve();
function adb(cmd){const job=Q.then(async()=>{const go=()=>run(cfg.adbPath,['-s',tgt(),'shell',cmd]);
 try{return await go()}catch(e){if(e.code==='ENOENT'||/ENOENT/.test(e.message))throw Error('Không tìm thấy adb – cài platform-tools hoặc sửa adbPath trong bridge/config.json');
  await run(cfg.adbPath,['connect',tgt()],6000).catch(()=>{});
  try{return await go()}catch(e2){throw Error(/unauthorized/.test(e2.message)?'Box chưa cho phép ADB – bấm "Luôn cho phép" trên màn hình TV':'ADB lỗi: '+e2.message)}}});Q=job.catch(()=>{});return job}
const KEY={UP:19,DOWN:20,LEFT:21,RIGHT:22,OK:23,BACK:4,EXIT:3,HOME:3,POWER:26,'VOL+':24,'VOL-':25,MUTE:164,'*':17,'#':18};for(let i=0;i<10;i++)KEY[i]=7+i;
const okApp=a=>/^[\w.]*$/.test(a);
async function boxText(t,enter){
 if(/^[\x20-\x7e]*$/.test(t))await adb('input text '+shq(t.replace(/ /g,'%s')));
 else{const pk='com.android.adbkeyboard',ime=pk+'/.AdbIME';
  if(!(await adb('pm list packages '+pk)).includes(pk))throw Error('Gõ tiếng Việt/ký tự đặc biệt cần cài ADBKeyboard (com.android.adbkeyboard) trên Box');
  const prev=await adb('settings get secure default_input_method');
  await adb(`ime enable ${ime};ime set ${ime}`);
  try{await adb('am broadcast -a ADB_INPUT_B64 --es msg '+Buffer.from(t).toString('base64'))}
  finally{await new Promise(r=>setTimeout(r,300));if(prev&&prev!=='null')await adb('ime set '+prev).catch(()=>{})}}
 if(enter)await adb('input keyevent 66')}
async function boxSearch(q){const o=await adb('am start -a android.intent.action.VIEW -d '+shq('https://www.youtube.com/results?search_query='+encodeURIComponent(q))+(cfg.searchApp?' -p '+cfg.searchApp:''));
 if(/Error/.test(o))throw Error(o.split('\n').find(l=>/Error/.test(l))+' (app chưa cài hoặc không hỗ trợ link tìm kiếm)')}
async function boxOpen(){const p=cfg.searchApp||'com.google.android.youtube.tv',a=c=>adb(`am start -a android.intent.action.MAIN -c android.intent.category.${c} -p ${p}`);
 let o=await a('LEANBACK_LAUNCHER');if(/Error/.test(o))o=await a('LAUNCHER');if(/Error/.test(o))throw Error('Không mở được '+p)}
async function status(){const o={};
 try{const r=await sonyReq('/sony/system',{method:'POST',body:JSON.stringify({method:'getPowerStatus',id:1,params:[],version:'1.0'})}),j=await r.json();o.sony=j.result?'OK · '+j.result[0].status:'Có phản hồi nhưng bị từ chối (cần PSK?) '+JSON.stringify(j.error||'')}catch(e){o.sony='Không kết nối được ('+e.message+')'}
 try{o.box='OK · '+await adb('getprop ro.product.model')}catch(e){o.box='Lỗi: '+e.message}return o}
// ---------- HTTP ----------
const MIME={'.html':'text/html;charset=utf-8','.css':'text/css','.js':'text/javascript','.webmanifest':'application/manifest+json','.png':'image/png'};
const STATIC=new Set(['index.html','style.css','app.js','sw.js','manifest.webmanifest','icons/icon-192.png','icons/icon-512.png']);
const CORS={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'Content-Type','Access-Control-Allow-Private-Network':'true'};
http.createServer(async(req,res)=>{
 const u=new URL(req.url,'http://x'),p=u.pathname,send=o=>{res.writeHead(200,{'Content-Type':'application/json',...CORS});res.end(JSON.stringify(o))};
 if(req.method==='OPTIONS'){res.writeHead(204,CORS);return res.end()}
 if(p.startsWith('/api/')){
  const b=await new Promise(r=>{let d='';req.on('data',c=>d+=c);req.on('end',()=>{try{r(JSON.parse(d||'{}'))}catch{r({})}})});
  try{
   if(p==='/api/config'&&req.method==='GET'){const{psk,adbPath,...c}=cfg;return send({ok:true,cfg:{...c,pskSet:!!psk}})}
   if(p==='/api/config'){for(const k of['sonyIp','tvMac','boxIp'])if(typeof b[k]==='string')cfg[k]=b[k].trim();
    if(typeof b.searchApp==='string'&&okApp(b.searchApp))cfg.searchApp=b.searchApp;if(b.psk)cfg.psk=b.psk;if(+b.adbPort)cfg.adbPort=+b.adbPort;saveCfg();return send({ok:true})}
   if(p==='/api/sony'){if(b.cmd==='power')await tvPower();else if(IRCC[b.cmd])await ircc(IRCC[b.cmd]);else throw Error('Lệnh TV không hỗ trợ');return send({ok:true})}
   if(p==='/api/box'){if(!(b.key in KEY))throw Error('Phím không hỗ trợ');await adb('input keyevent '+KEY[b.key]);return send({ok:true})}
   if(p==='/api/box/text'){if(typeof b.text!=='string'||!b.text||b.text.length>200)throw Error('Nội dung không hợp lệ');await boxText(b.text,!!b.enter);return send({ok:true})}
   if(p==='/api/box/search'){if(typeof b.q!=='string'||!b.q.trim())throw Error('Thiếu nội dung');await boxSearch(b.q.trim().slice(0,200));return send({ok:true})}
   if(p==='/api/box/open'){await boxOpen();return send({ok:true})}
   if(p==='/api/status'){return send({ok:true,...await status()})}
   throw Error('API không tồn tại')
  }catch(e){return send({ok:false,error:e.message})}}
 const f=p==='/'?'index.html':p.slice(1);
 if(!STATIC.has(f)){res.writeHead(404);return res.end('404')}
 fs.readFile(path.join(WEB,f),(e,d)=>{if(e){res.writeHead(404);return res.end()}res.writeHead(200,{'Content-Type':MIME[path.extname(f)]||'application/octet-stream','Cache-Control':'no-cache'});res.end(d)})
}).listen(cfg.port,'0.0.0.0',()=>{console.log('TVRemote bridge đang chạy. Mở trên điện thoại (cùng Wi-Fi):');
 for(const l of Object.values(os.networkInterfaces()))for(const a of l)if(a.family==='IPv4'&&!a.internal)console.log('  http://'+a.address+':'+cfg.port)});
