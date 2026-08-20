#!/usr/bin/env node
/**
 * 开发者中心（机器人后台）功能级 E2E（9.221）。
 *
 * 覆盖：开发者账号登录 → 创建机器人 → 签发 bot token → bot 身份验证，
 * 以及浏览器 UI 登录进入控制台的完整流程（含 console 错误收集）。
 *
 * 前置：服务端需以 SEED_DEMO_USERS=true 与 DEVELOPER_USER_IDS=u1 启动
 * （DEVELOPER_USER_IDS 失败闭合，未配置时所有开发者登录被拒）。
 * 运行：node scripts/developer-e2e.mjs（BASE_URL 可覆盖，默认 http://127.0.0.1:18080）
 */
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { chromium } from 'playwright-core';

const base = (process.env.ADMIN_E2E_BASE_URL || process.env.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');

function findChromium() {
  const configured = process.env.CHROME_PATH;
  const candidates = [
    configured,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser'
  ].filter(Boolean);
  const executable = candidates.find(existsSync);
  if (!executable) {
    throw new Error('No system Chrome/Chromium found. Set CHROME_PATH for developer E2E.');
  }
  return executable;
}

async function waitForServer() {
  for (let i = 0; i < 60; i++) {
    try {
      const response = await fetch(base + '/health/ready');
      if (response.ok) return;
    } catch { /* retry */ }
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  throw new Error('server not ready at ' + base);
}

async function api(path, opt) {
  opt = opt || {};
  opt.headers = Object.assign({ 'Content-Type': 'application/json' }, opt.headers || {});
  const r = await fetch(base + path, opt);
  const text = await r.text();
  let data = null;
  if (text) { try { data = JSON.parse(text); } catch { data = text; } }
  if (!r.ok) {
    throw new Error(`${path} -> ${r.status}: ${(data && data.error) || text}`);
  }
  return data;
}

async function main() {
  await waitForServer();

  // ─── REST 流程：开发者登录 → 建 bot → 签发 token → bot 身份 ───
  const login = await api('/api/developer-account/login', {
    method: 'POST',
    body: JSON.stringify({ email: 'alex@example.com', password: 'password123' })
  });
  assert.ok(login.token, 'developer login should return dev session token');
  assert.equal(login.userId, 'u1', 'seeded developer should be u1');

  const devHeaders = { Authorization: 'Bearer ' + login.token };
  const suffix = Date.now().toString(36);
  const botUsername = 'e2e_bot_' + suffix;
  const bot = await api('/api/developer-account/bots', {
    method: 'POST',
    headers: devHeaders,
    body: JSON.stringify({ name: 'E2E Bot', username: botUsername, description: 'developer e2e' })
  });
  assert.ok(bot.id, 'created bot should have id');
  assert.equal(bot.username, botUsername, 'bot username should match');

  const rotated = await api(`/api/developer-account/bots/${bot.id}/token`, {
    method: 'POST',
    headers: devHeaders
  });
  // 安全设计：完整 token 仅签发时返回一次（tokenOnce），后续只见 tokenPrefix
  assert.ok(rotated.tokenOnce, 'rotated bot token (tokenOnce) should exist');
  assert.ok(rotated.tokenPrefix, 'bot tokenPrefix should exist');

  const me = await api('/api/bot/me', { headers: { 'X-Bot-Token': rotated.tokenOnce } });
  assert.equal(me.username, botUsername, '/api/bot/me should reflect the bot identity');

  // 重复用户名应 409
  const conflict = await fetch(base + '/api/developer-account/bots', {
    method: 'POST',
    headers: devHeaders,
    body: JSON.stringify({ name: 'Dup', username: botUsername })
  });
  assert.equal(conflict.status, 409, 'duplicate bot username should return 409');

  // 未登录创建应 401
  const unauthorized = await fetch(base + '/api/developer-account/bots', {
    method: 'POST',
    body: JSON.stringify({ name: 'X', username: 'no_auth_bot_' + suffix })
  });
  assert.equal(unauthorized.status, 401, 'missing dev session should return 401');

  // ─── 浏览器 UI 流程：开发者登录进入控制台 ───
  const browser = await chromium.launch({
    executablePath: findChromium(),
    headless: process.env.HEADED !== '1',
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    const errors = [];
    page.on('console', msg => {
      if (msg.type() === 'error') errors.push(msg.text());
    });
    page.on('pageerror', error => errors.push(error.message));

    await page.goto(base + '/developer', { waitUntil: 'networkidle' });
    // 默认是 bot-token 登录 tab，先切到开发者账号登录
    await page.locator('#loginTabDev').click();
    await page.locator('#devEmail').waitFor({ state: 'visible', timeout: 5000 });
    await page.locator('#devEmail').fill('alex@example.com');
    await page.locator('#devPassword').fill('password123');
    await page.locator('#devLoginForm button[type=submit]').click();

    // 进入控制台：登录页隐藏、主界面显示
    await page.waitForFunction(
      () => {
        const loginPage = document.getElementById('loginPage');
        const appPage = document.getElementById('appPage');
        return loginPage && loginPage.classList.contains('hidden') &&
          appPage && !appPage.classList.contains('hidden');
      },
      null,
      { timeout: 15000 }
    );

    // 控制台应列出 REST 创建的 bot（botSelector 或机器人页）
    const bodyText = await page.evaluate(() => document.body.innerText);
    assert.ok(
      bodyText.includes('E2E Bot') || bodyText.includes(botUsername),
      'console should list the created bot'
    );

    assert.equal(errors.length, 0, 'console errors: ' + errors.join(' | '));
  } finally {
    await browser.close();
  }

  console.log('developer center e2e OK: REST flow (login/create/rotate/me/409/401) + UI login + bot listing');
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
