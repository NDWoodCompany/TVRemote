const C='tvremote-v4',F=['./','index.html','style.css','app.js','manifest.webmanifest','icons/icon-192.png','icons/icon-512.png'];
addEventListener('install',e=>{skipWaiting();e.waitUntil(caches.open(C).then(c=>c.addAll(F.map(u=>new Request(u,{cache:'reload'})))))});
addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>clients.claim())));
// Mạng trước (luôn kiểm tra bản mới, bỏ qua cache HTTP 10 phút của GitHub Pages); mất mạng mới dùng cache.
addEventListener('fetch',e=>{const r=e.request,u=new URL(r.url);if(r.method!=='GET'||u.origin!==location.origin||u.pathname.includes('/api/'))return;
e.respondWith(fetch(new Request(r.url,{cache:'no-cache'})).then(x=>{if(x.ok){const c=x.clone();caches.open(C).then(k=>k.put(r.url,c))}return x}).catch(()=>caches.match(r.url,{ignoreSearch:true}).then(x=>x||caches.match('index.html'))))});
