const C='tvremote-v3',F=['./','index.html','style.css','app.js','manifest.webmanifest','icons/icon-192.png','icons/icon-512.png'];
addEventListener('install',e=>{skipWaiting();e.waitUntil(caches.open(C).then(c=>c.addAll(F)))});
addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>clients.claim())));
addEventListener('fetch',e=>{const u=new URL(e.request.url);if(u.origin!==location.origin||u.pathname.includes('/api/'))return;
e.respondWith(fetch(e.request).then(r=>{const c=r.clone();caches.open(C).then(x=>x.put(e.request,c));return r}).catch(()=>caches.match(e.request)))});
