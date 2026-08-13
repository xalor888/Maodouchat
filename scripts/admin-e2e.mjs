import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

const workspace = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const baseUrl = (process.env.ADMIN_E2E_BASE_URL || "http://127.0.0.1:18080").replace(/\/$/, "");
const reportDir = path.join(workspace, "build", "reports");
mkdirSync(reportDir, { recursive: true });

function findChromium() {
  const configured = process.env.CHROMIUM_PATH;
  const candidates = [
    configured,
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser"
  ].filter(Boolean);
  const executable = candidates.find(existsSync);
  if (!executable) {
    throw new Error("No system Chrome/Edge found. Set CHROMIUM_PATH for admin E2E.");
  }
  return executable;
}

async function waitForServer(timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(baseUrl + "/health/ready");
      if (response.ok) return;
      lastError = new Error("health returned " + response.status);
    } catch (error) {
      lastError = error;
    }
    await new Promise(resolve => setTimeout(resolve, 750));
  }
  throw new Error("Server did not become ready: " + (lastError?.message || "unknown error"));
}

await waitForServer();
const browser = await chromium.launch({
  executablePath: findChromium(),
  headless: process.env.HEADED !== "1",
  args: ["--no-sandbox", "--disable-dev-shm-usage"]
});

const context = await browser.newContext({
  acceptDownloads: true,
  viewport: { width: 1440, height: 1000 },
  colorScheme: "light"
});
const page = await context.newPage();
const browserErrors = [];
page.on("pageerror", error => browserErrors.push("pageerror: " + error.message));
page.on("console", message => {
  if (message.type() === "error") browserErrors.push("console: " + message.text());
});

try {
  const pageResponse = await page.goto(baseUrl + "/admin", { waitUntil: "domcontentloaded" });
  assert.equal(pageResponse?.status(), 200);
  assert.match(pageResponse?.headers()["cache-control"] || "", /no-store/);
  assert.match(pageResponse?.headers()["content-security-policy"] || "", /frame-ancestors 'none'/);
  // 管理后台 CSP 有意放开 unsafe-inline（admin.js 大量内联样式/onclick，见 AdminRouting.kt 注释）。
  // 关键防护仍需具备：base-uri 'none'（防 base 标签注入）。
  assert.match(pageResponse?.headers()["content-security-policy"] || "", /base-uri 'none'/);
  const [cssResponse, jsResponse] = await Promise.all([
    page.request.get(baseUrl + "/admin/assets/admin.css"),
    page.request.get(baseUrl + "/admin/assets/admin.js")
  ]);
  assert.equal(cssResponse.status(), 200);
  assert.match(cssResponse.headers()["content-type"] || "", /text\/css/);
  assert.equal(jsResponse.status(), 200);
  assert.match(jsResponse.headers()["content-type"] || "", /javascript/);

  await page.locator("#email").fill("alex@example.com");
  await page.locator("#password").fill("password123");
  const sessionResponsePromise = page.waitForResponse(response =>
    response.url().endsWith("/api/admin/session") && response.request().method() === "POST"
  );
  await page.locator("#login-form button[type=submit]").click();
  const sessionResponse = await sessionResponsePromise;
  assert.equal(sessionResponse.status(), 200);
  const session = await sessionResponse.json();
  const remainingMs = session.expiresAt - Date.now();
  assert.ok(remainingMs > 4 * 60_000 && remainingMs <= 5 * 60_000, "admin session must be five minutes");

  await page.locator("#app:not(.hidden)").waitFor();
  // 仪表盘统计卡片渲染在 #content 内，类名为 .stat-card
  await page.locator("#content .stat-card").first().waitFor();
  // 会话时钟徽章只显示剩余时间（如 "4:59"），不含"高权限会话"文本
  assert.match(await page.locator("#session-info").innerText(), /^\d+:\d{2}$/);
  assert.equal(await page.locator("#password").inputValue(), "");
  const storage = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage)
  }));
  assert.deepEqual(storage, { local: [], session: [] });

  await page.locator('nav button[data-tab="users"]').click();
  await page.locator("tbody tr").first().waitFor();
  assert.ok(await page.locator("tbody").innerText().then(text => text.includes("u1")));

  const ruleName = "e2e-rule-" + Date.now();
  const editedRuleName = ruleName + "-edited";
  await page.locator('nav button[data-tab="rules"]').click();
  await page.locator("#rule-form").waitFor();
  await page.locator("#rule-name").fill(ruleName);
  await page.locator("#rule-scope").selectOption("POST");
  await page.locator("#rule-type").selectOption("KEYWORD");
  await page.locator("#rule-pattern").fill("e2e-pattern");
  await page.locator("#rule-action").selectOption("WARN_MOD");
  async function acceptConfirm() {
    await page.locator("#modal-overlay:not(.hidden)").waitFor();
    await page.locator("#modal-confirm").click();
    await page.locator("#modal-overlay:not(.hidden)").waitFor({ state: "hidden" });
  }

  await page.locator("#rule-submit").click();
  await acceptConfirm();
  let ruleRow = page.locator("tbody tr").filter({ hasText: ruleName });
  await ruleRow.waitFor();

  await ruleRow.locator("[data-rule-edit]").click();
  await page.locator("#rule-name").fill(editedRuleName);
  await page.locator("#rule-pattern").fill("e2e-pattern-edited");
  await page.locator("#rule-submit").click();
  await acceptConfirm();
  ruleRow = page.locator("tbody tr").filter({ hasText: editedRuleName });
  await ruleRow.waitFor();
  assert.match(await ruleRow.innerText(), /e2e-pattern-edited/);

  await ruleRow.locator("[data-rule-toggle]").click();
  await acceptConfirm();
  ruleRow = page.locator("tbody tr").filter({ hasText: editedRuleName });
  await ruleRow.waitFor();
  assert.match(await ruleRow.innerText(), /停用/);

  await page.locator('nav button[data-tab="audit"]').click();
  await page.locator("#audit-export").waitFor();
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.locator("#audit-export").click()
  ]);
  const downloadPath = await download.path();
  assert.ok(downloadPath, "audit CSV download must have a temporary path");
  const csv = readFileSync(downloadPath, "utf8");
  assert.ok(csv.startsWith("\uFEFFid,actorId,targetUserId"));
  assert.match(csv, /ADMIN_SESSION_ISSUED/);
  assert.match(csv, /ADMIN_RULE_UPDATED/);

  await page.screenshot({
    path: path.join(reportDir, "admin-e2e.png"),
    fullPage: true
  });

  await page.locator('nav button[data-tab="rules"]').click();
  ruleRow = page.locator("tbody tr").filter({ hasText: editedRuleName });
  await ruleRow.waitFor();
  await ruleRow.locator("[data-rule-delete]").click();
  await acceptConfirm();
  await page.locator("tbody tr").filter({ hasText: editedRuleName }).waitFor({ state: "detached" });

  await page.locator("#logout").click();
  await page.locator("#login:not(.hidden)").waitFor();
  assert.equal(browserErrors.length, 0, browserErrors.join("\n"));
  process.stdout.write("Admin browser E2E passed\n");
} finally {
  await context.close();
  await browser.close();
}
