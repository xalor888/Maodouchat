import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

// ⚠️ 警告：仓库中的启动图标 PNG（ic_launcher_foreground / ic_launcher / ic_launcher_round）
// 是手工定稿构图，与 logo.svg 的原始渲染结果不同（留白、居中、比例都不同）。
// 直接运行本脚本会用 SVG 渲染结果覆盖定稿图标，导致安装后图标观感变化。
// 调整图标大小请使用 scripts/shrink_launcher_foreground.py（对定稿图等比缩放）。
// 本脚本仅用于更新 web/PWA logo 资源（第 4 部分）。

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const rootDir = path.resolve(__dirname, '..');

const svgPath = path.join(rootDir, 'server/src/main/resources/public/assets/logo.svg');
const svgContent = fs.readFileSync(svgPath, 'utf8');

async function main() {
  const browser = await chromium.launch({
    executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
    args: ['--no-sandbox', '--disable-gpu']
  });

  const page = await browser.newPage();

  // Helper to render and screenshot an HTML container
  async function renderToPNG(html, width, height, outputPath) {
    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    await page.setViewportSize({ width, height });
    await page.setContent(`<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body { width: ${width}px; height: ${height}px; background: transparent; overflow: hidden; }
  </style>
</head>
<body>
  ${html}
</body>
</html>`);

    // Give browser a tick to render gradients
    await page.waitForTimeout(50);

    const buffer = await page.screenshot({
      type: 'png',
      omitBackground: true,
      clip: { x: 0, y: 0, width, height }
    });
    fs.writeFileSync(outputPath, buffer);
    console.log(`Generated: ${outputPath} (${width}x${height})`);
  }

  // 1. Adaptive Icon Foregrounds (Safe zone ~62.5% of total canvas, centered on transparent background)
  // Logo occupies ~55% of the canvas so it sits comfortably inside the safe zone
  // and doesn't look oversized/cropped under launcher icon masks.
  const foregroundDensities = [
    { name: 'drawable-mdpi', size: 108, logoSize: 60 },
    { name: 'drawable-hdpi', size: 162, logoSize: 90 },
    { name: 'drawable-xhdpi', size: 216, logoSize: 120 },
    { name: 'drawable-xxhdpi', size: 324, logoSize: 180 },
    { name: 'drawable-xxxhdpi', size: 432, logoSize: 240 },
  ];

  for (const item of foregroundDensities) {
    const pad = (item.size - item.logoSize) / 2;
    const html = `
      <div style="width:${item.size}px; height:${item.size}px; display:flex; align-items:center; justify-content:center; background:transparent;">
        <div style="width:${item.logoSize}px; height:${item.logoSize}px; display:flex; align-items:center; justify-content:center;">
          ${svgContent}
        </div>
      </div>
    `;
    const outPath = path.join(rootDir, 'app/src/main/res', item.name, 'ic_launcher_foreground.png');
    await renderToPNG(html, item.size, item.size, outPath);
  }

  // 2. Legacy Squircle Mipmaps (ic_launcher.png) - Clean white squircle with subtle shadow
  const mipmapDensities = [
    { name: 'mipmap-mdpi', size: 48, logoSize: 34, radius: 10 },
    { name: 'mipmap-hdpi', size: 72, logoSize: 52, radius: 16 },
    { name: 'mipmap-xhdpi', size: 96, logoSize: 70, radius: 21 },
    { name: 'mipmap-xxhdpi', size: 144, logoSize: 104, radius: 32 },
    { name: 'mipmap-xxxhdpi', size: 192, logoSize: 140, radius: 42 },
  ];

  for (const item of mipmapDensities) {
    const html = `
      <div style="width:${item.size}px; height:${item.size}px; display:flex; align-items:center; justify-content:center; background:transparent;">
        <div style="width:${item.size - 2}px; height:${item.size - 2}px; background:#ffffff; border-radius:${item.radius}px; box-shadow: 0 1px 3px rgba(0,0,0,0.12); display:flex; align-items:center; justify-content:center;">
          <div style="width:${item.logoSize}px; height:${item.logoSize}px; display:flex; align-items:center; justify-content:center;">
            ${svgContent}
          </div>
        </div>
      </div>
    `;
    const outPath = path.join(rootDir, 'app/src/main/res', item.name, 'ic_launcher.png');
    await renderToPNG(html, item.size, item.size, outPath);
  }

  // 3. Legacy Round Mipmaps (ic_launcher_round.png) - Clean white circle with subtle shadow
  for (const item of mipmapDensities) {
    const html = `
      <div style="width:${item.size}px; height:${item.size}px; display:flex; align-items:center; justify-content:center; background:transparent;">
        <div style="width:${item.size - 2}px; height:${item.size - 2}px; background:#ffffff; border-radius:50%; box-shadow: 0 1px 3px rgba(0,0,0,0.12); display:flex; align-items:center; justify-content:center;">
          <div style="width:${item.logoSize}px; height:${item.logoSize}px; display:flex; align-items:center; justify-content:center;">
            ${svgContent}
          </div>
        </div>
      </div>
    `;
    const outPath = path.join(rootDir, 'app/src/main/res', item.name, 'ic_launcher_round.png');
    await renderToPNG(html, item.size, item.size, outPath);
  }

  // 4. In-App Logo & Web / PWA Assets (Full resolution transparent logo)
  const fullLogos = [
    { path: path.join(rootDir, 'app/src/main/res/drawable-nodpi/logo.png'), size: 512 },
    { path: path.join(rootDir, 'logo.png'), size: 512 },
    { path: path.join(rootDir, 'logo2.png'), size: 512 },
    { path: path.join(rootDir, 'server/src/main/resources/public/assets/logo.png'), size: 512 },
    { path: path.join(rootDir, 'server/src/main/resources/public/assets/icon-192.png'), size: 192 },
    { path: path.join(rootDir, 'server/src/main/resources/public/assets/icon-512.png'), size: 512 },
  ];

  for (const item of fullLogos) {
    const html = `
      <div style="width:${item.size}px; height:${item.size}px; display:flex; align-items:center; justify-content:center; background:transparent;">
        <div style="width:${item.size}px; height:${item.size}px; display:flex; align-items:center; justify-content:center;">
          ${svgContent}
        </div>
      </div>
    `;
    await renderToPNG(html, item.size, item.size, item.path);
  }

  await browser.close();
  console.log('All icons generated successfully!');
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
