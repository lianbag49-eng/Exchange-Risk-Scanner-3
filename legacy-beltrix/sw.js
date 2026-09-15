// Retire only this legacy worker; leave wallet storage and other site scopes intact.
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim().then(()=>self.registration.unregister())));
