#!/usr/bin/env node
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.resolve(__dirname, '../server/src/main/resources/public');
const port = parseInt(process.env.PORT || '8080', 10);

const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'text/xml; charset=utf-8'
};

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let reqPath = decodeURIComponent(parsedUrl.pathname);

  // 模拟 API 状态检查
  if (reqPath === '/api/public/status') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ ok: true, maintenance: false, version: '0.9.0' }));
    return;
  }

  if (reqPath === '/sitemap.xml') {
    res.writeHead(200, { 'Content-Type': 'text/xml; charset=utf-8' });
    res.end(`<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>http://localhost:8080/</loc><changefreq>weekly</changefreq></url><url><loc>http://localhost:8080/developer</loc><changefreq>weekly</changefreq></url><url><loc>http://localhost:8080/security</loc><changefreq>weekly</changefreq></url><url><loc>http://localhost:8080/privacy</loc><changefreq>weekly</changefreq></url><url><loc>http://localhost:8080/terms</loc><changefreq>weekly</changefreq></url></urlset>`);
    return;
  }

  if (reqPath === '/robots.txt') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('User-agent: *\nAllow: /\nDisallow: /admin\nDisallow: /developer\nDisallow: /api/\nSitemap: http://localhost:8080/sitemap.xml\n');
    return;
  }

  if (reqPath === '/security.txt') {
    res.writeHead(301, { Location: '/.well-known/security.txt' });
    res.end();
    return;
  }

  if (reqPath === '/.well-known/security.txt') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Contact: mailto:security@maodouchat.com\nPreferred-Languages: zh, en\nCanonical: http://localhost:8080/.well-known/security.txt\nPolicy: http://localhost:8080/security#disclosure\nExpires: 2027-08-13T00:00:00.000Z\n');
    return;
  }

  // 旧页面 301 重定向
  if (['/faq', '/faq.html', '/help', '/help.html'].includes(reqPath)) {
    res.writeHead(301, { Location: '/#faq' });
    res.end();
    return;
  }

  // Clean URL 映射
  const cleanRouteMap = {
    '/': '/index.html',
    '/security': '/security.html',
    '/privacy': '/privacy.html',
    '/terms': '/terms.html',
    '/developer': '/developer.html',
    '/manifest.webmanifest': '/manifest.webmanifest',
    '/sw.js': '/sw.js'
  };

  if (cleanRouteMap[reqPath]) {
    reqPath = cleanRouteMap[reqPath];
  }

  let filePath = path.join(publicDir, reqPath);

  // 如果请求没有扩展名且有对应 .html 文件
  if (!path.extname(filePath) && fs.existsSync(filePath + '.html')) {
    filePath = filePath + '.html';
  }

  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    const ext = path.extname(filePath);
    const contentType = mimeTypes[ext] || 'application/octet-stream';
    res.writeHead(200, {
      'Content-Type': contentType,
      'Cache-Control': ext === '.html' ? 'no-cache, must-revalidate' : 'public, max-age=3600'
    });
    fs.createReadStream(filePath).pipe(res);
  } else {
    res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end('<h1>404 Not Found</h1><p><a href="/">返回首页</a></p>');
  }
});

server.listen(port, '0.0.0.0', () => {
  console.log(`\n🎉 毛豆聊天官网本地预览服务已启动！`);
  console.log(`👉 本地访问地址: http://localhost:${port}`);
  console.log(`👉 局域网访问: http://127.0.0.1:${port}\n`);
});
