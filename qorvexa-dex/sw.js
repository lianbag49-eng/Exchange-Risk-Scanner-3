// Remove caches owned by earlier QORVEXA previews. Account and market responses are never cached.
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',e=>e.waitUntil((async()=>{for(const k of await caches.keys())if(k.startsWith('qorvexa'))await caches.delete(k);await self.clients.claim()})()));
