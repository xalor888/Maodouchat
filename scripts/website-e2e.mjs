#!/usr/bin/env node
import { existsSync } from 'node:fs';
import { chromium } from 'playwright-core';

const base = (process.env.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
const pages = ['/', '/faq', '/privacy', '/terms', '/security', '/help', '/developer'];

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
    throw new Error('No system Chrome/Chromium found. Set CHROME_PATH for website E2E.');
  }
  return executable;
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function checkPage(browser, path, width, height) {
  const context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  const errors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', error => errors.push(error.message));

  const response = await page.goto(`${base}${path}`, { waitUntil: 'networkidle' });
  assert(response && response.status() === 200, `${path}@${width} should return 200, got ${response && response.status()}`);
  if (path === '/') {
    await page.waitForFunction(
      () => ['online', 'offline'].includes(document.getElementById('serviceStatus')?.dataset.state || ''),
      null,
      { timeout: 6000 }
    );
  }

  const state = await page.evaluate(() => ({
    overflow: document.documentElement.scrollWidth > window.innerWidth,
    htmlLinks: [...document.querySelectorAll('a')]
      .filter(link => (link.getAttribute('href') || '').endsWith('.html'))
      .length,
    favicon: document.querySelector('link[rel="icon"]')?.getAttribute('href') || '',
    manifest: document.querySelector('link[rel="manifest"]')?.getAttribute('href') || '',
    appleTouchIcon: document.querySelector('link[rel="apple-touch-icon"]')?.getAttribute('href') || '',
    serviceState: document.getElementById('serviceStatus')?.dataset.state || null
  }));

  assert(!state.overflow, `${path}@${width} should not overflow horizontally`);
  assert(state.htmlLinks === 0, `${path}@${width} should not contain .html links`);
  assert(state.favicon === '/assets/logo.png', `${path}@${width} should use /assets/logo.png favicon`);
  if (path === '/') {
    assert(state.manifest === '/manifest.webmanifest', '/ should declare /manifest.webmanifest');
    assert(state.appleTouchIcon === '/assets/icon-512.png', '/ should declare apple-touch-icon');
  }
  assert(errors.length === 0, `${path}@${width} console errors: ${errors.join('; ')}`);

  if (path === '/' && width === 390) {
    const themeToggle = page.locator('#themeToggle');
    await themeToggle.click();
    assert(
      (await page.evaluate(() => ({
        theme: document.documentElement.dataset.theme,
        meta: document.querySelector('#themeColor')?.content
      }))).theme === 'dark',
      'homepage theme toggle should enter dark mode'
    );
    await themeToggle.click();

    const navToggle = page.locator('#navToggle');
    await navToggle.click();
    assert(
      await page.evaluate(() => document.getElementById('navLinks').classList.contains('open')),
      'mobile navigation should open'
    );
    await page.keyboard.press('Escape');
    assert(
      await page.evaluate(() => !document.getElementById('navLinks').classList.contains('open')),
      'Escape should close mobile navigation'
    );

    const secretTab = page.locator('#tab-secret');
    await secretTab.click();
    await secretTab.press('ArrowRight');
    const tabState = await page.evaluate(() => ({
      focus: document.activeElement?.id,
      selected: [...document.querySelectorAll('.preview-tab')]
        .filter(tab => tab.getAttribute('aria-selected') === 'true')
        .map(tab => tab.id),
      visible: [...document.querySelectorAll('.phone-screen')]
        .filter(panel => panel.classList.contains('active'))
        .map(panel => panel.id)
    }));
    assert(tabState.focus === 'tab-ai', 'preview tabs should support arrow-key navigation');
    assert(tabState.selected.join(',') === 'tab-ai', 'arrow key should select the next preview tab');
    assert(tabState.visible.join(',') === 'panel-ai', 'selected tab should show its matching panel');
  }

  await context.close();
  return { path, width, ...state };
}

async function checkStaticRoutes() {
  const results = [];
  for (const path of ['/sitemap.xml', '/robots.txt', '/.well-known/security.txt', '/manifest.webmanifest', '/sw.js']) {
    const response = await fetch(`${base}${path}`);
    assert(response.status === 200, `${path} should return 200`);
    const body = await response.text();
    if (path !== '/robots.txt') {
      assert(!body.includes('.html'), `${path} should not reference .html URLs`);
    }
    if (path === '/robots.txt') {
      assert(body.includes('Sitemap: '), '/robots.txt should declare Sitemap');
    }
    results.push(path);
  }

  const legacy = await fetch(`${base}/faq.html`, { redirect: 'manual' });
  assert(legacy.status === 301, '/faq.html should return 301');
  assert((legacy.headers.get('location') || '').endsWith('/faq'), '/faq.html should point to /faq');
  const securityLegacy = await fetch(`${base}/security.txt`, { redirect: 'manual' });
  assert(securityLegacy.status === 301, '/security.txt should return 301');
  assert(
    (securityLegacy.headers.get('location') || '').endsWith('/.well-known/security.txt'),
    '/security.txt should point to /.well-known/security.txt'
  );
  return results;
}

try {
  const browser = await chromium.launch({ executablePath: findChromium(), headless: true });
  const results = [];
  for (const path of pages) {
    results.push(await checkPage(browser, path, 1440, 900));
    results.push(await checkPage(browser, path, 390, 844));
  }
  await browser.close();
  const staticRoutes = await checkStaticRoutes();
  console.log(`website e2e OK: ${results.length} page/viewport checks, ${staticRoutes.length} static routes`);
} catch (error) {
  console.error(`website e2e FAILED: ${error.message}`);
  process.exit(1);
}
