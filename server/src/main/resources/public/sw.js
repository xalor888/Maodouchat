const CACHE_NAME = 'maodouchat-site-v3';
const CORE_ASSETS = [
  '/',
  '/assets/style.css',
  '/assets/home.css',
  '/assets/logo.png',
  '/assets/icon-512.png',
  '/manifest.webmanifest'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(CORE_ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || !request.url.startsWith(self.location.origin)) return;
  const path = new URL(request.url).pathname;
  // 管理后台不在站点 SW 职责范围：不缓存、不兜底（旧实现曾把 /admin/assets/* 一并永久缓存，
  // 且离线访问 /admin 会误回营销首页）
  if (path === '/admin' || path.startsWith('/admin/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          if (response.ok) {
            caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => caches.match('/'))
    );
    return;
  }

  // 精确匹配站点 /assets/（旧实现用 includes('/assets/') 误吞 /admin/assets/*）
  const isSiteAsset = path.startsWith('/assets/');
  event.respondWith(
    caches.match(request).then((cached) => {
      // 8.48 修复：/assets/ 文件名无版本号，cache-first 会让发版后的老用户无限期拿到旧资源——
      // 改为 stale-while-revalidate：先回缓存，同时后台刷新
      if (cached && isSiteAsset) {
        fetch(request).then((response) => {
          if (response.ok) {
            caches.open(CACHE_NAME).then((cache) => cache.put(request, response));
          }
        }).catch(() => {});
        return cached;
      }
      if (cached) return cached;
      return fetch(request).then((response) => {
        const copy = response.clone();
        if (response.ok && isSiteAsset) {
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
        }
        return response;
      }).catch(() => Response.error());
    })
  );
});
