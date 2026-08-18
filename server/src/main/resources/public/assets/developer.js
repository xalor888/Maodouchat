/* ═══════════════════════════════════════════════════════
   毛豆聊天 开发者中心 - 交互逻辑
   双模式登录：
     - bot   : Bot Token (X-Bot-Token)
     - dev   : 开发者账号 (email+password -> dev_session JWT, Bearer)
   ═══════════════════════════════════════════════════════ */
(function () {
  'use strict';

  // ─── State ───────────────────────────
  // session shape: { mode:'bot'|'dev', token, userId?, email?, name?, bots?, selectedBotId? }
  var session = null;
  var botData = null;        // bot-mode: /api/bot/me result
  var capabilities = [];     // cached manifest
  var rotateTargetId = '';
  var webhookTargetId = '';
  var commandsTargetId = '';
  var activePage = 'dashboard';
  var SESSION_KEY = 'maodou-dev-session';

  function loadSession() {
    try { return JSON.parse(sessionStorage.getItem(SESSION_KEY) || 'null'); }
    catch (e) { return null; }
  }
  function saveSession() {
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(session)); } catch (e) {}
  }
  function clearSession() {
    session = null;
    try { sessionStorage.removeItem(SESSION_KEY); } catch (e) {}
  }

  // ─── DOM Helpers ─────────────────────
  var el = function (id) { return document.getElementById(id); };
  var esc = function (v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };
  var date = function (v) {
    if (!v) return '-';
    var d = new Date(v);
    var pad = function (n) { return String(n).padStart(2, '0'); };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  };

  // ─── Toast ──────────────────────────
  function toast(msg, type) {
    var t = document.createElement('div');
    t.className = 'dev-toast' + (type ? ' ' + type : '');
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 3000);
  }

  // ─── API ────────────────────────────
  // Branches auth header on session.mode:
  //   dev -> Authorization: Bearer <dev_session JWT>
  //   bot -> X-Bot-Token: <bot token>
  function apiHeaders() {
    if (session && session.mode === 'dev') {
      return { 'Authorization': 'Bearer ' + (session.token || ''), 'Content-Type': 'application/json' };
    }
    return { 'X-Bot-Token': (session && session.token) || '', 'Content-Type': 'application/json' };
  }
  async function api(path, opt) {
    opt = opt || {};
    opt.headers = Object.assign(apiHeaders(), opt.headers || {});
    var r = await fetch(path, opt);
    var text = await r.text();
    var data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { data = text; } }
    if (!r.ok) throw new Error((data && data.error) || ('请求失败 ' + r.status));
    return data;
  }
  async function apiRaw(path, opt) {
    opt = opt || {};
    opt.headers = Object.assign(apiHeaders(), opt.headers || {});
    var r = await fetch(path, opt);
    var text = await r.text();
    return { status: r.status, ok: r.ok, body: text };
  }

  function isDev() { return !!(session && session.mode === 'dev'); }
  // The bot the current view is scoped to.
  function currentBot() {
    if (isDev()) {
      var list = (session && session.bots) || [];
      for (var i = 0; i < list.length; i++) {
        if (list[i].id === session.selectedBotId) return list[i];
      }
      return list[0] || null;
    }
    return botData;
  }
  function currentBotId() {
    var b = currentBot();
    return b ? (b.id || b.botId) : null;
  }
  // ?bot_id=... for id-less /api/developer/* routes in dev mode.
  function botQuery() {
    return (isDev() && session.selectedBotId) ? ('?bot_id=' + encodeURIComponent(session.selectedBotId)) : '';
  }

  // ─── Theme ──────────────────────────
  var themeToggle = document.getElementById('themeToggle');
  var iconSun = document.getElementById('iconSun');
  var iconMoon = document.getElementById('iconMoon');
  var themeMeta = document.getElementById('themeColor');

  function applyDevTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    if (themeMeta) themeMeta.setAttribute('content', theme === 'dark' ? '#0b100e' : '#f7f8f4');
    if (themeToggle) {
      themeToggle.setAttribute('aria-label', theme === 'dark' ? '切换到浅色主题' : '切换到深色主题');
      if (iconSun && iconMoon) {
        if (theme === 'dark') {
          iconSun.removeAttribute('hidden');
          iconMoon.setAttribute('hidden', '');
        } else {
          iconMoon.removeAttribute('hidden');
          iconSun.setAttribute('hidden', '');
        }
      }
    }
  }

  if (themeToggle) {
    var savedTheme = 'light';
    try {
      savedTheme = localStorage.getItem('maodou-theme') || (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    } catch (e) {}
    if (savedTheme !== 'dark' && savedTheme !== 'light') savedTheme = 'light';
    applyDevTheme(savedTheme);
    themeToggle.addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'dark' ? 'light' : 'dark';
      try {
        localStorage.setItem('maodou-theme', next);
      } catch (e) {}
      applyDevTheme(next);
    });
  }

  // ─── Login tabs ─────────────────────
  function selectLoginTab(tab) {
    var isBot = tab === 'bot';
    el('loginTabBot').classList.toggle('active', isBot);
    el('loginTabDev').classList.toggle('active', !isBot);
    el('loginPanelBot').classList.toggle('hidden', !isBot);
    el('loginPanelDev').classList.toggle('hidden', isBot);
  }
  if (el('loginTabBot')) {
    el('loginTabBot').addEventListener('click', function () { selectLoginTab('bot'); });
    el('loginTabDev').addEventListener('click', function () { selectLoginTab('dev'); });
  }

  // ─── Bot-token login ────────────────
  if (el('botLoginForm')) {
    el('botLoginForm').addEventListener('submit', async function (e) {
      e.preventDefault();
      el('loginError').textContent = '';
      var btn = el('loginBtn');
      btn.disabled = true;
      btn.textContent = '验证中…';
      try {
        var t = el('botToken').value.trim();
        if (!t) throw new Error('请输入 Token');
        session = { mode: 'bot', token: t };
        saveSession();
        var me = await api('/api/bot/me');
        botData = me;
        enterApp();
        toast('登录成功', 'success');
      } catch (x) {
        clearSession();
        el('loginError').textContent = x.message || '登录失败';
      } finally {
        btn.disabled = false;
        btn.textContent = '登录控制台';
      }
    });
  }

  // ─── Developer-account login ────────
  if (el('devLoginForm')) {
    el('devLoginForm').addEventListener('submit', async function (e) {
      e.preventDefault();
      el('devLoginError').textContent = '';
      var btn = el('devLoginBtn');
      btn.disabled = true;
      btn.textContent = '登录中…';
      try {
        var email = el('devEmail').value.trim();
        var password = el('devPassword').value;
        var totpCode = el('devTotp').value.trim();
        if (!email || !password) throw new Error('请输入邮箱和密码');
        var r = await fetch('/api/developer-account/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: email, password: password, totpCode: totpCode || undefined })
        });
        var data = null;
        var txt = await r.text();
        if (txt) { try { data = JSON.parse(txt); } catch (e) { data = txt; } }
        if (!r.ok) throw new Error((data && data.error) || ('登录失败 ' + r.status));
        if (data && data.requiresTotp) {
          el('devTotpField').classList.remove('hidden');
          el('devTotp').setAttribute('placeholder', '请输入 6 位动态验证码');
          el('devTotp').focus();
          el('devLoginError').textContent = '该账号已开启两步验证，请输入动态验证码后重试';
          return;
        }
        if (!data || !data.token) throw new Error('登录响应异常');
        var bots = data.bots || [];
        session = {
          mode: 'dev',
          token: data.token,
          userId: data.userId,
          email: data.email,
          name: data.name,
          bots: bots,
          selectedBotId: bots[0] && bots[0].id
        };
        saveSession();
        enterApp();
        toast('登录成功', 'success');
      } catch (x) {
        el('devLoginError').textContent = x.message || '登录失败';
      } finally {
        btn.disabled = false;
        btn.textContent = '登录开发者中心';
      }
    });
  }

  // ─── Enter / leave app ──────────────
  function enterApp() {
    el('loginPage').classList.add('hidden');
    el('appPage').classList.remove('hidden');
    renderTopBar();
    loadHealth();
    loadPage(activePage);
  }
  function renderTopBar() {
    var bar = el('devTopBar');
    if (!bar) return;
    if (isDev()) {
      bar.classList.remove('hidden');
      var sel = el('botSelector');
      var bots = (session.bots) || [];
      var html = '';
      if (bots.length === 0) {
        html = '<option value="">（暂无机器人）</option>';
      } else {
        bots.forEach(function (b) {
          html += '<option value="' + esc(b.id) + '"' + (b.id === session.selectedBotId ? ' selected' : '') + '>' + esc(b.name) + '</option>';
        });
      }
      sel.innerHTML = html;
      var acct = el('devAccountLabel');
      if (acct) acct.textContent = session.email || session.name || '';
    } else {
      bar.classList.add('hidden');
    }
  }
  if (el('botSelector')) {
    el('botSelector').addEventListener('change', function () {
      session.selectedBotId = el('botSelector').value;
      saveSession();
      loadHealth();
      loadPage(activePage);
    });
  }

  // ─── Logout ─────────────────────────
  if (el('logoutBtn')) {
    el('logoutBtn').addEventListener('click', function () {
      clearSession();
      botData = null;
      el('appPage').classList.add('hidden');
      el('loginPage').classList.remove('hidden');
      if (el('botToken')) el('botToken').value = '';
      if (el('devPassword')) el('devPassword').value = '';
      if (el('devTotp')) el('devTotp').value = '';
      if (el('devTotpField')) el('devTotpField').classList.add('hidden');
      selectLoginTab('bot');
    });
  }

  // ─── Navigation ─────────────────────
  document.querySelectorAll('.dev-nav-item').forEach(function (btn) {
    btn.addEventListener('click', function () {
      activePage = btn.dataset.page;
      document.querySelectorAll('.dev-nav-item').forEach(function (b) { b.classList.toggle('active', b === btn); });
      loadPage(activePage);
    });
  });

  function loadPage(page) {
    document.querySelectorAll('.dev-page').forEach(function (p) { p.classList.remove('active'); });
    var target = el('page-' + page);
    if (target) target.classList.add('active');
    switch (page) {
      case 'dashboard': DevApp.loadDashboard(); break;
      case 'bots': DevApp.loadBots(); break;
      case 'explorer': DevApp.loadExplorer(); break;
      case 'docs': DevApp.loadDocs(); break;
      case 'logs': DevApp.loadLogs(); break;
      case 'capabilities': DevApp.loadCapabilities(); break;
    }
  }

  // ─── Health (sidebar + header indicators) ─────
  async function loadHealth() {
    var dot = el('healthDot');
    var label = el('healthLabel');
    var topDot = el('healthDotTop');
    var topLabel = el('healthLabelTop');
    if (!dot || !label) return;
    var apply = function (cls, text) {
      dot.className = 'health-dot ' + cls;
      label.textContent = text;
      if (topDot) topDot.className = 'health-dot ' + cls;
      if (topLabel) topLabel.textContent = text;
    };
    apply('pending', '检查中…');
    try {
      var h = await api('/api/developer/health' + botQuery());
      var ok = h.status === 'healthy';
      apply(ok ? 'success' : 'error', ok ? '健康' : '降级');
      label.title = 'bot: ' + (h.bot && h.bot.enabled ? 'enabled' : 'disabled') +
        ' / webhook: ' + (h.bot && h.bot.webhookConfigured ? 'on' : 'off') +
        ' / maintenance: ' + (h.server && h.server.maintenanceMode ? 'on' : 'off');
    } catch (x) {
      apply('error', '不可用');
    }
  }

  // ─── Inline SVG sparkline ───────────
  function sparkline(stats) {
    if (!stats || stats.length === 0) {
      return '<div style="color:var(--text-muted);padding:24px;text-align:center">暂无数据</div>';
    }
    var w = 700, h = 200, padL = 36, padR = 16, padT = 16, padB = 28;
    var max = 0;
    stats.forEach(function (s) { if ((s.commandCount || 0) > max) max = s.commandCount || 0; });
    if (max === 0) max = 1;
    var n = stats.length;
    var step = (w - padL - padR) / Math.max(1, n - 1);
    var pts = stats.map(function (s, i) {
      var x = padL + i * step;
      var y = h - padB - ((s.commandCount || 0) / max) * (h - padT - padB);
      return [x, y];
    });
    var line = pts.map(function (p) { return p[0].toFixed(1) + ',' + p[1].toFixed(1); }).join(' ');
    var area = line + ' ' + (padL + (n - 1) * step).toFixed(1) + ',' + (h - padB) + ' ' + padL + ',' + (h - padB);
    var dots = pts.map(function (p) {
      return '<circle class="chart-dot" cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="3"/>';
    }).join('');
    var midY = (padT + (h - padB)) / 2;
    var labels = stats.map(function (s, i) {
      var skip = Math.ceil(n / 7);
      if (i % skip !== 0 && i !== n - 1) return '';
      var x = padL + i * step;
      var d = new Date(s.day);
      var lbl = (d.getMonth() + 1) + '/' + d.getDate();
      return '<text class="chart-label" x="' + x.toFixed(1) + '" y="' + (h - 8) + '" text-anchor="middle">' + esc(lbl) + '</text>';
    }).join('');
    return '<svg viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">' +
      '<polyline class="chart-grid" points="' + padL + ',' + padT + ' ' + (w - padR) + ',' + padT + '"/>' +
      '<polyline class="chart-grid" points="' + padL + ',' + midY.toFixed(1) + ' ' + (w - padR) + ',' + midY.toFixed(1) + '"/>' +
      '<polygon class="chart-area" points="' + area + '"/>' +
      '<polyline class="chart-line" points="' + line + '"/>' +
      dots + labels +
      '</svg>';
  }

  // ═══ DevApp (page controllers) ═══════
  var DevApp = window.DevApp = {
    // ─── Dashboard ──────────────────────
    loadDashboard: async function () {
      try {
        var bid = currentBotId();
        if (!bid) {
          el('dashStats').innerHTML = '<div class="dev-stat"><div class="dev-stat-label">提示</div><div class="dev-stat-value" style="font-size:16px">请先创建机器人</div></div>';
          el('botInfo').innerHTML = '';
          el('webhookInfo').innerHTML = '';
          el('dashAnalytics').innerHTML = '';
          return;
        }
        // Unified dashboard endpoint (works for both bot-token and dev_session).
        var dash = await api('/api/developer/dashboard' + botQuery());
        var caps = await api('/api/developer/capabilities' + botQuery());
        capabilities = (caps && caps.messaging) ? [caps] : [];

        var me = currentBot() || {};
        el('dashStats').innerHTML =
          '<div class="dev-stat"><div class="dev-stat-label">机器人</div><div class="dev-stat-value" style="font-size:20px">' + esc(me.name || me.username || dash.botName || '-') + '</div><div class="dev-stat-sub">@' + esc(me.username || dash.botUsername || '-') + '</div></div>' +
          '<div class="dev-stat"><div class="dev-stat-label">总命令数</div><div class="dev-stat-value">' + (dash.totalCommands || 0) + '</div><div class="dev-stat-sub">24h: ' + (dash.commandsLast24h || 0) + '</div></div>' +
          '<div class="dev-stat"><div class="dev-stat-label">24h 活跃用户</div><div class="dev-stat-value">' + (dash.uniqueUsersLast24h || 0) + '</div></div>' +
          '<div class="dev-stat"><div class="dev-stat-label">待处理更新</div><div class="dev-stat-value">' + (dash.pendingUpdates || 0) + '</div><div class="dev-stat-sub">' + (dash.webhookConfigured ? 'Webhook 已配置' : 'Webhook 未配置') + '</div></div>';

        // Bot info
        el('botInfo').innerHTML =
          '<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">' +
          '<div><span style="color:var(--text-muted);font-size:12px">Bot ID</span><div style="font-weight:500;font-family:monospace;font-size:13px">' + esc(me.id || bid) + '</div></div>' +
          '<div><span style="color:var(--text-muted);font-size:12px">用户名</span><div style="font-weight:500">@' + esc(me.username || '-') + '</div></div>' +
          '<div><span style="color:var(--text-muted);font-size:12px">描述</span><div style="font-weight:500">' + esc(me.description || '-') + '</div></div>' +
          '<div><span style="color:var(--text-muted);font-size:12px">创建时间</span><div style="font-weight:500">' + date(me.createdAt) + '</div></div>' +
          '</div>';

        // Webhook status
        var whUrl = me.webhookUrl || (me.webhookInfo && me.webhookInfo.url) || '';
        el('webhookInfo').innerHTML =
          '<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">' +
          '<div><span style="color:var(--text-muted);font-size:12px">Webhook URL</span><div style="font-weight:500;font-family:monospace;font-size:13px;word-break:break-all">' + (whUrl ? esc(whUrl) : '未设置') + '</div></div>' +
          '<div><span style="color:var(--text-muted);font-size:12px">状态</span><div style="font-weight:500">' + (dash.webhookConfigured ? '<span style="color:var(--success)">✓ 已配置</span>' : '<span style="color:var(--text-muted)">未配置</span>') + '</div></div>' +
          '<div><span style="color:var(--text-muted);font-size:12px">待处理更新</span><div style="font-weight:500">' + (dash.pendingUpdates || 0) + '</div></div>' +
          '<div><span style="color:var(--text-muted);font-size:12px">总命令数</span><div style="font-weight:500">' + (dash.totalCommands || 0) + '</div></div>' +
          '</div>';

        // 7-day analytics sparkline
        try {
          var an = await api('/api/developer/bots/' + encodeURIComponent(bid) + '/analytics?days=7');
          var top = (an.commandBreakdown || []).slice(0, 5);
          var topHtml = top.length
            ? top.map(function (c) { return '<tr><td><code>' + esc(c.command) + '</code></td><td style="text-align:right">' + c.count + '</td></tr>'; }).join('')
            : '<tr><td colspan="2" style="color:var(--text-muted);text-align:center">暂无数据</td></tr>';
          el('dashAnalytics').innerHTML =
            '<div class="chart-container"><h4>近 7 天命令趋势（共 ' + (an.totalCommands || 0) + ' 条）</h4>' + sparkline(an.dailyStats) + '</div>' +
            '<div class="dev-card"><div class="dev-card-header"><h3>热门命令</h3></div><div class="dev-card-body"><table class="doc-table"><thead><tr><th>命令</th><th style="text-align:right">次数</th></tr></thead><tbody>' + topHtml + '</tbody></table></div></div>';
        } catch (e2) {
          el('dashAnalytics').innerHTML = '<div class="chart-container"><h4>近 7 天命令趋势</h4><div style="color:var(--text-muted);padding:20px">加载失败: ' + esc(e2.message) + '</div></div>';
        }
      } catch (x) {
        el('dashStats').innerHTML = '<div style="color:var(--danger)">加载失败: ' + esc(x.message) + '</div>';
        el('botInfo').innerHTML = '';
        el('webhookInfo').innerHTML = '';
        el('dashAnalytics').innerHTML = '';
      }
    },

    // ─── Bots Management ────────────────
    loadBots: async function () {
      var createBtn = document.querySelector('[onclick="DevApp.showCreateBot()"]');
      if (!isDev()) {
        if (createBtn) createBtn.classList.add('hidden');
        el('botsList').innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-muted)">机器人管理需要开发者账号登录。<br/>请退出后使用「开发者账号登录」。</div>';
        return;
      }
      if (createBtn) createBtn.classList.remove('hidden');
      try {
        // Refresh bot list from /me.
        var me = await api('/api/developer-account/me');
        session.bots = me.bots || [];
        if (session.bots.length && !session.bots.some(function (b) { return b.id === session.selectedBotId; })) {
          session.selectedBotId = session.bots[0].id;
        }
        saveSession();
        renderTopBar();
        var list = session.bots;
        if (list.length === 0) {
          el('botsList').innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-muted)">暂无机器人，点击右上角创建。</div>';
          return;
        }
        var html = '';
        list.forEach(function (b) {
          var enabled = b.enabled !== false;
          html += '<div class="dev-card"><div class="dev-card-header">' +
            '<div><h3 style="font-size:16px">' + esc(b.name) + (enabled ? '' : ' <span style="color:var(--text-muted);font-size:12px">（已停用）</span>') + '</h3>' +
            '<div style="font-size:12px;color:var(--text-muted);font-family:monospace">' + esc(b.id) + '</div></div>' +
            '<div style="display:flex;gap:8px;flex-wrap:wrap">' +
            // 8.47 修复：内联 onclick 手写转义（仅处理 \\ 与 '）→ data-* 属性 + 委托监听
            //（esc() 在双引号属性中完整转义，杜绝未来 id 格式变化时的属性逃逸/注入）
            '<button class="dev-btn dev-btn-ghost" data-bot-action="webhook" data-bot-id="' + esc(b.id) + '">Webhook</button>' +
            '<button class="dev-btn dev-btn-ghost" data-bot-action="commands" data-bot-id="' + esc(b.id) + '">命令菜单</button>' +
            '<button class="dev-btn dev-btn-ghost" data-bot-action="toggle" data-bot-id="' + esc(b.id) + '" data-enabled="' + (!enabled) + '">' + (enabled ? '停用' : '启用') + '</button>' +
            '<button class="dev-btn dev-btn-ghost" data-bot-action="rotate" data-bot-id="' + esc(b.id) + '">轮换 Token</button>' +
            '<button class="dev-btn dev-btn-danger" data-bot-action="delete" data-bot-id="' + esc(b.id) + '">删除</button>' +
            '</div></div>' +
            '<div class="dev-card-body" style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px">' +
            '<div><span style="color:var(--text-muted);font-size:12px">用户名</span><div style="font-weight:500">@' + esc(b.username || '-') + '</div></div>' +
            '<div><span style="color:var(--text-muted);font-size:12px">描述</span><div style="font-weight:500">' + esc(b.description || '-') + '</div></div>' +
            '<div><span style="color:var(--text-muted);font-size:12px">创建时间</span><div style="font-weight:500">' + date(b.createdAt) + '</div></div>' +
            '</div></div>';
        });
        el('botsList').innerHTML = html;
        // 8.47：bot 操作按钮事件委托
        el('botsList').onclick = function (e) {
          var btn = e.target.closest('[data-bot-action]');
          if (!btn) return;
          var id = btn.getAttribute('data-bot-id') || '';
          var action = btn.getAttribute('data-bot-action');
          if (action === 'webhook') DevApp.showWebhook(id);
          else if (action === 'commands') DevApp.showCommands(id);
          else if (action === 'toggle') DevApp.toggleEnabled(id, btn.getAttribute('data-enabled') === 'true');
          else if (action === 'rotate') DevApp.rotateTokenConfirm(id);
          else if (action === 'delete') DevApp.deleteBot(id);
        };
      } catch (x) {
        el('botsList').innerHTML = '<div style="color:var(--danger)">' + esc(x.message) + '</div>';
      }
    },

    showCreateBot: function () {
      el('createBotDialog').classList.remove('hidden');
      el('createBotDialog').style.display = 'grid';
    },
    hideCreateBot: function () {
      el('createBotDialog').classList.add('hidden');
      el('createBotDialog').style.display = '';
    },
    createBot: async function () {
      var name = el('newBotName').value.trim();
      if (!name) { toast('请输入名称', 'error'); return; }
      try {
        var bot = await api('/api/developer-account/bots', {
          method: 'POST',
          body: JSON.stringify({
            name: name,
            username: el('newBotUsername').value.trim() || undefined,
            description: el('newBotDesc').value.trim() || undefined
          })
        });
        DevApp.hideCreateBot();
        el('newBotName').value = '';
        el('newBotUsername').value = '';
        el('newBotDesc').value = '';
        toast('机器人创建成功', 'success');
        if (bot && bot.tokenOnce) {
          // 8.47 修复：不再 alert 明文 token（屏录/截图/日志泄露）——复制到剪贴板 + 提示
          DevApp.copyTokenOnce(bot.tokenOnce);
        }
        DevApp.loadBots();
      } catch (x) {
        toast('创建失败: ' + x.message, 'error');
      }
    },

    toggleEnabled: async function (id, enabled) {
      try {
        await api('/api/developer-account/bots/' + encodeURIComponent(id) + '/enabled', {
          method: 'PUT',
          body: JSON.stringify({ enabled: !!enabled })
        });
        toast(enabled ? '已启用' : '已停用', 'success');
        DevApp.loadBots();
      } catch (x) {
        toast('操作失败: ' + x.message, 'error');
      }
    },

    rotateTokenConfirm: function (id) {
      rotateTargetId = id;
      el('rotateTokenDialog').classList.remove('hidden');
      el('rotateTokenDialog').style.display = 'grid';
    },
    hideRotateToken: function () {
      el('rotateTokenDialog').classList.add('hidden');
      el('rotateTokenDialog').style.display = '';
    },
    rotateToken: async function () {
      try {
        var result = await api('/api/developer-account/bots/' + encodeURIComponent(rotateTargetId) + '/token', { method: 'POST' });
        DevApp.hideRotateToken();
        toast('Token 已轮换，请更新你的应用配置', 'success');
        if (result && result.tokenOnce) {
          // 8.47 修复：不再 alert 明文 token
          DevApp.copyTokenOnce(result.tokenOnce);
        }
        DevApp.loadBots();
      } catch (x) {
        toast('轮换失败: ' + x.message, 'error');
      }
    },

    // 8.47：一次性复制新 token（剪贴板 + toast），避免明文弹出泄露
    copyTokenOnce: function (token) {
      var done = function () { toast('新 Token 已复制到剪贴板（仅显示一次）', 'success'); };
      var fail = function () { toast('无法自动复制，请查看浏览器控制台', 'error'); };
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(token).then(done, fail);
      } else {
        // 非安全上下文回退：临时 textarea 复制
        try {
          var ta = document.createElement('textarea');
          ta.value = token;
          ta.style.position = 'fixed';
          ta.style.opacity = '0';
          document.body.appendChild(ta);
          ta.select();
          document.execCommand('copy');
          document.body.removeChild(ta);
          done();
        } catch (e) { fail(); }
      }
    },

    // ─── Webhook form + test ────────────
    showWebhook: async function (id) {
      webhookTargetId = id;
      var bot = null;
      if (isDev()) {
        var list = session.bots || [];
        for (var i = 0; i < list.length; i++) { if (list[i].id === id) bot = list[i]; }
      } else { bot = botData; }
      el('webhookUrlInput').value = (bot && bot.webhookUrl) || '';
      el('webhookSecretInput').value = '';
      el('webhookTestResult').innerHTML = '<span style="color:var(--text-muted)">保存后可发送测试请求。</span>';
      el('webhookDialog').classList.remove('hidden');
      el('webhookDialog').style.display = 'grid';
    },
    hideWebhook: function () {
      el('webhookDialog').classList.add('hidden');
      el('webhookDialog').style.display = '';
    },
    saveWebhook: async function () {
      try {
        await api('/api/developer-account/bots/' + encodeURIComponent(webhookTargetId) + '/webhook', {
          method: 'PUT',
          body: JSON.stringify({ url: el('webhookUrlInput').value.trim(), secret: el('webhookSecretInput').value.trim() || undefined })
        });
        toast('Webhook 已更新', 'success');
        DevApp.hideWebhook();
        DevApp.loadBots();
      } catch (x) {
        toast('更新失败: ' + x.message, 'error');
      }
    },
    testWebhook: async function () {
      el('webhookTestResult').innerHTML = '<div class="dev-loading"><div class="dev-spinner"></div>发送测试请求…</div>';
      try {
        var res = await apiRaw('/api/developer/bots/' + encodeURIComponent(webhookTargetId) + '/test-webhook', { method: 'POST' });
        var data = null;
        try { data = JSON.parse(res.body); } catch (e) { data = null; }
        var body = data ? JSON.stringify(data, null, 2) : (res.body || '');
        var ok = data && data.success;
        var statusLine = ok
          ? '<span class="status-ok">✓ HTTP ' + (data.statusCode || 0) + ' · ' + (data.latencyMs || 0) + 'ms</span>'
          : '<span class="status-err">✗ HTTP ' + (data ? data.statusCode : res.status) + (data && data.error ? ' · ' + esc(data.error) : '') + '</span>';
        el('webhookTestResult').innerHTML = statusLine + '\n\n' + esc(body);
      } catch (x) {
        el('webhookTestResult').innerHTML = '<span class="status-err">✗ ' + esc(x.message) + '</span>';
      }
    },

    // ─── Command menu editor ────────────
    showCommands: async function (id) {
      commandsTargetId = id;
      el('commandsDialog').classList.remove('hidden');
      el('commandsDialog').style.display = 'grid';
      el('commandsEditor').innerHTML = '<div class="dev-loading"><div class="dev-spinner"></div>加载…</div>';
      try {
        var data = await api('/api/developer-account/bots/' + encodeURIComponent(id) + '/commands');
        var cmds = (data && data.commands) || [];
        renderCommandRows(cmds);
      } catch (x) {
        el('commandsEditor').innerHTML = '<div style="color:var(--danger)">' + esc(x.message) + '</div>';
      }
    },
    hideCommands: function () {
      el('commandsDialog').classList.add('hidden');
      el('commandsDialog').style.display = '';
    },
    addCommandRow: function (cmd, desc) {
      var wrap = el('commandsEditor');
      var row = document.createElement('div');
      row.className = 'cmd-row';
      row.innerHTML =
        '<input class="cmd-cmd" placeholder="start" value="' + esc(cmd || '') + '"/>' +
        '<input class="cmd-desc" placeholder="开始使用机器人" value="' + esc(desc || '') + '"/>' +
        '<button class="dev-btn dev-btn-ghost" onclick="this.parentElement.remove()">移除</button>';
      wrap.appendChild(row);
    },
    saveCommands: async function () {
      var rows = el('commandsEditor').querySelectorAll('.cmd-row');
      var cmds = [];
      rows.forEach(function (r) {
        var c = r.querySelector('.cmd-cmd').value.trim();
        var d = r.querySelector('.cmd-desc').value.trim();
        if (c && d) cmds.push({ command: c, description: d });
      });
      try {
        await api('/api/developer-account/bots/' + encodeURIComponent(commandsTargetId) + '/commands', {
          method: 'PUT',
          body: JSON.stringify({ commands: cmds })
        });
        toast('命令菜单已保存', 'success');
        DevApp.hideCommands();
      } catch (x) {
        toast('保存失败: ' + x.message, 'error');
      }
    },

    deleteBot: function (id) {
      if (!confirm('确认删除机器人 ' + id + '？此操作不可逆。')) return;
      api('/api/developer-account/bots/' + encodeURIComponent(id), { method: 'DELETE' })
        .then(function () {
          toast('机器人已删除', 'success');
          if (session.selectedBotId === id) {
            var remaining = (session.bots || []).filter(function (b) { return b.id !== id; });
            session.selectedBotId = remaining[0] && remaining[0].id;
            saveSession();
          }
          DevApp.loadBots();
          loadHealth();
        })
        .catch(function (x) { toast('删除失败: ' + x.message, 'error'); });
    },

    // ─── Capabilities page ──────────────
    loadCapabilities: async function () {
      try {
        var c = await api('/api/developer/capabilities' + botQuery());
        capabilities = c;
        function bool(v) { return v ? '<span style="color:var(--success)">✓ 支持</span>' : '<span style="color:var(--text-muted)">✗ 不支持</span>'; }
        function row(label, v) { return '<div style="display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid var(--surface-border)"><span style="color:var(--text-secondary)">' + esc(label) + '</span><span>' + (typeof v === 'boolean' ? bool(v) : esc(v)) + '</span></div>'; }
        var m = c.messaging || {}, g = c.groups || {}, a = c.ai || {}, it = c.integrations || {};
        el('capsContent').innerHTML =
          '<div class="dev-stats">' +
          '<div class="dev-card"><div class="dev-card-header"><h3>消息能力</h3></div><div class="dev-card-body">' +
          row('文本消息', m.canSendMessage) + row('图片', m.canSendImages) + row('视频', m.canSendVideos) +
          row('文件', m.canSendFiles) + row('语音', m.canSendVoice) + row('Markdown', m.canSendMarkdown) +
          row('最大长度', m.maxMessageLength) + row('回复', m.supportsReply) + row('转发', m.supportsForward) +
          row('置顶', m.supportsPin) + row('编辑', m.supportsEdit) + row('撤回', m.supportsRevoke) + row('表态', m.supportsReaction) +
          '</div></div>' +
          '<div class="dev-card"><div class="dev-card-header"><h3>群组能力</h3></div><div class="dev-card-body">' +
          row('加入群组', g.canJoinGroups) + row('创建群组', g.canCreateGroups) + row('最大群规模', g.maxGroupSize) +
          row('读取群历史', g.canReadGroupHistory) + row('管理成员', g.canManageMembers) + row('群玩法', g.supportsGroupPlay) + row('投票', g.supportsPolls) +
          '</div></div>' +
          '<div class="dev-card"><div class="dev-card-header"><h3>AI 能力</h3></div><div class="dev-card-body">' +
          row('翻译', a.translateEnabled) + row('摘要', a.summarizeEnabled) + row('改写', a.rewriteEnabled) +
          row('建议回复', a.suggestRepliesEnabled) + row('语音转文字', a.transcribeEnabled) + row('图像分析', a.analyzeImageEnabled) +
          row('文件分析', a.analyzeFileEnabled) + row('语义搜索', a.semanticSearchEnabled) + row('群助手', a.groupAssistantEnabled) +
          '</div></div>' +
          '<div class="dev-card"><div class="dev-card-header"><h3>集成能力</h3></div><div class="dev-card-body">' +
          row('Webhook', it.webhookSupported) + row('最大重试', it.webhookMaxRetries) + row('超时(秒)', it.webhookTimeoutSeconds) +
          row('最大命令数', it.maxCommands) + row('更新类型', (it.supportedUpdateTypes || []).join(', ')) +
          '</div></div>' +
          '</div>';
      } catch (x) {
        el('capsContent').innerHTML = '<div style="color:var(--danger)">加载失败: ' + esc(x.message) + '</div>';
      }
    },

    // ─── API Explorer ───────────────────
    loadExplorer: function () {
      if (isDev()) {
        el('explorerEndpoints').innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-muted)">API 探索器需逐 Bot 调用消息接口，请退出后使用「Bot Token 登录」使用此页。</div>';
        el('explorerRequest').classList.add('hidden');
        el('explorerResponse').classList.add('hidden');
        return;
      }
      var endpoints = [
        { method: 'GET', path: '/api/bot/me', desc: '获取当前 Bot 资料' },
        { method: 'GET', path: '/api/bot/whoami', desc: '鉴权探测' },
        { method: 'GET', path: '/api/bot/chats', desc: '获取 Bot 所在会话' },
        { method: 'GET', path: '/api/bot/webhookInfo', desc: 'Webhook 状态' },
        { method: 'GET', path: '/api/bot/listCapabilities', desc: '能力清单' },
        { method: 'GET', path: '/api/bot/commandStats', desc: '命令统计' },
        { method: 'GET', path: '/api/bot/commandLogs', desc: '命令日志' },
        { method: 'POST', path: '/api/bot/sendMessage', desc: '发送消息', body: '{\n  "chatId": "",\n  "text": "Hello!",\n  "parseMode": "MARKDOWN"\n}' },
        { method: 'POST', path: '/api/bot/sendMarkdown', desc: '发送 Markdown 消息', body: '{\n  "chatId": "",\n  "text": "**Bold** _italic_"\n}' },
        { method: 'POST', path: '/api/bot/editMessage', desc: '编辑消息', body: '{\n  "chatId": "",\n  "messageId": "",\n  "text": "Updated"\n}' },
        { method: 'POST', path: '/api/bot/answerCallbackQuery', desc: '回调查询应答', body: '{\n  "callbackQueryId": "",\n  "text": "OK"\n}' },
        { method: 'POST', path: '/api/bot/sendChatAction', desc: '发送聊天动作', body: '{\n  "chatId": "",\n  "action": "typing"\n}' },
        { method: 'GET', path: '/api/bot/getUpdates', desc: '获取更新（长轮询）' },
        { method: 'POST', path: '/api/bot/setWebhook', desc: '设置 Webhook', body: '{\n  "url": "https://your-server.com/webhook"\n}' },
        { method: 'POST', path: '/api/bot/pinChatMessage', desc: '置顶消息', body: '{\n  "chatId": "",\n  "messageId": ""\n}' },
        { method: 'GET', path: '/api/bot/getChatPins', desc: '获取置顶消息' },
        { method: 'POST', path: '/api/bot/sendPoll', desc: '发送投票', body: '{\n  "chatId": "",\n  "question": "Best language?",\n  "options": ["JS", "Python", "Go"]\n}' },
        { method: 'POST', path: '/api/bot/sendDice', desc: '发送骰子', body: '{\n  "chatId": "",\n  "emoji": "🎲"\n}' },
        { method: 'POST', path: '/api/bot/sendLocation', desc: '发送位置', body: '{\n  "chatId": "",\n  "latitude": 39.9,\n  "longitude": 116.4\n}' },
        { method: 'POST', path: '/api/bot/sendContact', desc: '发送联系人', body: '{\n  "chatId": "",\n  "phoneNumber": "+86...",\n  "firstName": "Name"\n}' },
        { method: 'POST', path: '/api/bot/forwardMessage', desc: '转发消息', body: '{\n  "chatId": "",\n  "fromChatId": "",\n  "messageId": ""\n}' },
        { method: 'POST', path: '/api/bot/copyMessage', desc: '复制消息', body: '{\n  "chatId": "",\n  "fromChatId": "",\n  "messageId": ""\n}' },
        { method: 'GET', path: '/api/bot/getMyCommands', desc: '获取命令列表' },
        { method: 'POST', path: '/api/bot/setMyCommands', desc: '设置命令列表', body: '{\n  "commands": [\n    {"command": "start", "description": "Start bot"}\n  ]\n}' },
        { method: 'POST', path: '/api/bot/setMyName', desc: '设置 Bot 名称', body: '{\n  "name": "My Bot"\n}' },
        { method: 'POST', path: '/api/bot/setMyDescription', desc: '设置 Bot 描述', body: '{\n  "description": "Bot description"\n}' },
        { method: 'GET', path: '/api/bot/getChatMemberCount', desc: '获取群成员数' },
        { method: 'GET', path: '/api/bot/getChatHistory', desc: '获取聊天历史' },
        { method: 'GET', path: '/api/bot/listChatPolls', desc: '列出群投票' },
        { method: 'GET', path: '/api/bot/getPublicStatus', desc: '公开状态' },
        { method: 'GET', path: '/api/bot/getServerTime', desc: '服务器时间' },
        { method: 'GET', path: '/api/bot/uptime', desc: '运行时间' },
        { method: 'GET', path: '/api/bot/healthz', desc: '健康检查' }
      ];

      var html = '<div class="dev-card"><div class="dev-card-header"><h3>API 端点 (' + endpoints.length + ')</h3></div><div class="dev-card-body">';
      endpoints.forEach(function (ep, i) {
        var methodClass = ep.method.toLowerCase();
        html += '<div class="api-endpoint" onclick="DevApp.selectEndpoint(' + i + ')" data-idx="' + i + '">' +
          '<span class="api-method ' + methodClass + '">' + ep.method + '</span>' +
          '<span class="api-endpoint-path">' + esc(ep.path) + '</span>' +
          '<span style="color:var(--text-muted);font-size:13px;margin-left:auto">' + esc(ep.desc) + '</span>' +
          '</div>';
      });
      html += '</div></div>';
      el('explorerEndpoints').innerHTML = html;
      el('explorerRequest').classList.add('hidden');
      el('explorerResponse').classList.add('hidden');
      window._apiEndpoints = endpoints;
    },

    selectEndpoint: function (idx) {
      var ep = window._apiEndpoints[idx];
      var isGet = ep.method === 'GET';
      var bodyHtml = '';
      if (!isGet) {
        bodyHtml = '<div class="dev-field"><label>请求体 (JSON)</label><textarea id="explorerBody" rows="6">' + esc(ep.body || '{}') + '</textarea></div>';
      }
      el('explorerRequest').innerHTML =
        '<div class="api-request-panel">' +
        '<div class="api-request-header">' +
        '<span class="api-method ' + ep.method.toLowerCase() + '">' + ep.method + '</span>' +
        '<span style="font-family:monospace;font-size:13px">' + esc(ep.path) + '</span>' +
        '<button class="dev-btn dev-btn-primary" onclick="DevApp.executeApi(' + idx + ')" style="margin-left:auto">发送请求</button>' +
        '</div>' +
        '<div style="padding:16px">' +
        '<div class="dev-field"><label>路径/查询参数</label><input id="explorerParams" placeholder="/api/bot/me" value="' + esc(ep.path) + '"/></div>' +
        bodyHtml +
        '</div></div>';
      el('explorerRequest').classList.remove('hidden');
      el('explorerResponse').classList.add('hidden');
    },

    executeApi: async function (idx) {
      var ep = window._apiEndpoints[idx];
      var path = el('explorerParams').value.trim();
      var opts = { method: ep.method };
      if (ep.method !== 'GET') {
        var bodyText = document.getElementById('explorerBody');
        if (bodyText && bodyText.value.trim()) {
          try {
            JSON.parse(bodyText.value);
            opts.body = bodyText.value;
          } catch (e) {
            toast('JSON 格式错误', 'error');
            return;
          }
        }
      }
      try {
        el('explorerResponse').innerHTML = '<div class="dev-loading"><div class="dev-spinner"></div>发送中...</div>';
        el('explorerResponse').classList.remove('hidden');
        var result = await apiRaw(path, opts);
        var formatted;
        try {
          formatted = JSON.stringify(JSON.parse(result.body), null, 2);
        } catch (e) {
          formatted = result.body;
        }
        el('explorerResponse').innerHTML =
          '<div class="api-request-panel">' +
          '<div class="api-request-header">' +
          '<span style="font-weight:600">' + (result.ok ? '✓ ' + result.status : '✗ ' + result.status) + '</span>' +
          '<button class="copy-btn" onclick="DevApp.copyResponse()">复制</button>' +
          '</div>' +
          '<pre class="api-response ' + (result.ok ? 'success' : 'error') + '" id="responseText">' + esc(formatted) + '</pre>' +
          '</div>';
      } catch (x) {
        el('explorerResponse').innerHTML = '<div style="color:var(--danger);padding:16px">' + esc(x.message) + '</div>';
        el('explorerResponse').classList.remove('hidden');
      }
    },

    copyResponse: function () {
      var text = document.getElementById('responseText');
      if (text) {
        navigator.clipboard.writeText(text.textContent).then(function () {
          toast('已复制', 'success');
        });
      }
    },

    // ─── Docs ──────────────────────────
    loadDocs: function () {
      el('docsContent').innerHTML =
        '<div class="doc-section">' +
        '<h3>鉴权</h3>' +
        '<p style="color:var(--text-secondary);margin-bottom:16px">Bot API 请求在 Header 中携带 Token；开发者账号模式使用 Bearer JWT：</p>' +
        '<div style="background:var(--surface);border:1px solid var(--surface-border);border-radius:var(--radius-sm);padding:16px;font-family:monospace;font-size:13px;margin-bottom:16px">' +
        '# Bot Token 模式<br>X-Bot-Token: &lt;your_token&gt;<br><br># 开发者账号模式<br>Authorization: Bearer &lt;dev_session_jwt&gt;' +
        '</div>' +
        '</div>' +

        '<div class="doc-section">' +
        '<h3>核心 API</h3>' +
        '<table class="doc-table"><thead><tr><th>方法</th><th>端点</th><th>说明</th><th>示例</th></tr></thead><tbody>' +
        docRow('GET', '/api/bot/me', '获取 Bot 资料', 'curl /api/bot/me -H "X-Bot-Token: TOKEN"') +
        docRow('GET', '/api/bot/whoami', '鉴权探测', 'curl /api/bot/whoami -H "X-Bot-Token: TOKEN"') +
        docRow('GET', '/api/bot/chats', 'Bot 所在会话', 'curl /api/bot/chats -H "X-Bot-Token: TOKEN"') +
        docRow('GET', '/api/bot/webhookInfo', 'Webhook 状态', 'curl /api/bot/webhookInfo -H "X-Bot-Token: TOKEN"') +
        docRow('GET', '/api/bot/listCapabilities', '能力清单', 'curl /api/bot/listCapabilities -H "X-Bot-Token: TOKEN"') +
        docRow('POST', '/api/bot/sendMessage', '发送消息', 'curl -X POST /api/bot/sendMessage ...') +
        docRow('POST', '/api/bot/editMessage', '编辑消息', 'curl -X POST /api/bot/editMessage ...') +
        docRow('POST', '/api/bot/answerCallbackQuery', '回调应答', 'curl -X POST /api/bot/answerCallbackQuery ...') +
        docRow('POST', '/api/bot/sendChatAction', '聊天动作', 'curl -X POST /api/bot/sendChatAction ...') +
        docRow('GET', '/api/bot/getUpdates', '获取更新', 'curl /api/bot/getUpdates -H "X-Bot-Token: TOKEN"') +
        docRow('POST', '/api/bot/setWebhook', '设置 Webhook', 'curl -X POST /api/bot/setWebhook ...') +
        docRow('POST', '/api/bot/pinChatMessage', '置顶消息', 'curl -X POST /api/bot/pinChatMessage ...') +
        docRow('GET', '/api/bot/getChatPins', '获取置顶', 'curl /api/bot/getChatPins -H "X-Bot-Token: TOKEN"') +
        docRow('POST', '/api/bot/sendPoll', '发送投票', 'curl -X POST /api/bot/sendPoll ...') +
        docRow('POST', '/api/bot/sendDice', '发送骰子', 'curl -X POST /api/bot/sendDice ...') +
        docRow('POST', '/api/bot/sendLocation', '发送位置', 'curl -X POST /api/bot/sendLocation ...') +
        docRow('POST', '/api/bot/sendContact', '发送联系人', 'curl -X POST /api/bot/sendContact ...') +
        docRow('POST', '/api/bot/forwardMessage', '转发消息', 'curl -X POST /api/bot/forwardMessage ...') +
        docRow('POST', '/api/bot/copyMessage', '复制消息', 'curl -X POST /api/bot/copyMessage ...') +
        docRow('GET', '/api/bot/getMyCommands', '获取命令', 'curl /api/bot/getMyCommands ...') +
        docRow('POST', '/api/bot/setMyCommands', '设置命令', 'curl -X POST /api/bot/setMyCommands ...') +
        docRow('POST', '/api/bot/setMyName', '设置名称', 'curl -X POST /api/bot/setMyName ...') +
        docRow('POST', '/api/bot/setMyDescription', '设置描述', 'curl -X POST /api/bot/setMyDescription ...') +
        docRow('GET', '/api/bot/getChatMemberCount', '群成员数', 'curl /api/bot/getChatMemberCount ...') +
        docRow('GET', '/api/bot/getChatHistory', '聊天历史', 'curl /api/bot/getChatHistory ...') +
        docRow('GET', '/api/bot/listChatPolls', '群投票列表', 'curl /api/bot/listChatPolls ...') +
        docRow('GET', '/api/bot/commandStats', '命令统计', 'curl /api/bot/commandStats ...') +
        docRow('GET', '/api/bot/commandLogs', '命令日志', 'curl /api/bot/commandLogs ...') +
        docRow('GET', '/api/bot/getPublicStatus', '公开状态', 'curl /api/bot/getPublicStatus ...') +
        docRow('GET', '/api/bot/healthz', '健康检查', 'curl /api/bot/healthz ...') +
        docRow('GET', '/api/bot/uptime', '运行时间', 'curl /api/bot/uptime ...') +
        docRow('GET', '/api/bot/getServerTime', '服务器时间', 'curl /api/bot/getServerTime ...') +
        '</tbody></table></div>' +

        '<div class="doc-section">' +
        '<h3>开发者账号 API</h3>' +
        '<table class="doc-table"><thead><tr><th>方法</th><th>端点</th><th>说明</th></tr></thead><tbody>' +
        docRow('POST', '/api/developer-account/login', '邮箱密码登录') +
        docRow('GET', '/api/developer-account/me', '当前账号 + 机器人列表') +
        docRow('POST', '/api/developer-account/bots', '创建机器人') +
        docRow('POST', '/api/developer-account/bots/{id}/token', '轮换 Token') +
        docRow('PUT', '/api/developer-account/bots/{id}/webhook', '设置 Webhook') +
        docRow('PUT', '/api/developer-account/bots/{id}/enabled', '启用/停用') +
        docRow('PUT', '/api/developer-account/bots/{id}/commands', '设置命令菜单') +
        docRow('GET', '/api/developer-account/bots/{id}/commands', '获取命令菜单') +
        docRow('DELETE', '/api/developer-account/bots/{id}', '删除机器人') +
        '</tbody></table></div>' +

        '<div class="doc-section">' +
        '<h3>开发者数据 API（dev_session 或 Bot Token）</h3>' +
        '<table class="doc-table"><thead><tr><th>方法</th><th>端点</th><th>说明</th></tr></thead><tbody>' +
        docRow('GET', '/api/developer/dashboard', '仪表盘汇总') +
        docRow('GET', '/api/developer/bots/{id}/analytics', '命令分析（?days=7）') +
        docRow('GET', '/api/developer/bots/{id}/logs', '结构化日志') +
        docRow('POST', '/api/developer/bots/{id}/test-webhook', '测试 Webhook 投递') +
        docRow('GET', '/api/developer/capabilities', '能力清单') +
        docRow('GET', '/api/developer/health', '健康检查') +
        '</tbody></table></div>' +

        '<div class="doc-section">' +
        '<h3>Webhook 签名</h3>' +
        '<p style="color:var(--text-secondary);margin-bottom:12px">Webhook 请求包含 HMAC-SHA256 签名头：</p>' +
        '<div style="background:var(--surface);border:1px solid var(--surface-border);border-radius:var(--radius-sm);padding:16px;font-family:monospace;font-size:13px">' +
        'X-Maodouchat-Signature: sha256=&lt;hex&gt;' +
        '</div>' +
        '</div>' +

        '<div class="doc-section">' +
        '<h3>完整文档</h3>' +
        '<p style="color:var(--text-secondary)">更多详情请参阅 <code style="background:var(--surface);padding:2px 6px;border-radius:4px">docs/bot-developer-api.md</code></p>' +
        '</div>';
    },

    // ─── Logs ──────────────────────────
    loadLogs: async function () {
      try {
        var bid = currentBotId();
        if (!bid) {
          el('logsContent').innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-muted)">未选择机器人</div>';
          return;
        }
        var data = await api('/api/developer/bots/' + encodeURIComponent(bid) + '/logs?limit=50');
        var logs = (data && data.logs) || [];
        if (logs.length === 0) {
          el('logsContent').innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-muted)">暂无命令日志</div>';
          return;
        }
        var html = '<div style="overflow-x:auto"><table class="doc-table"><thead><tr><th>时间</th><th>命令</th><th>聊天</th><th>用户</th></tr></thead><tbody>';
        logs.forEach(function (log) {
          html += '<tr>' +
            '<td>' + date(log.createdAt || log.timestamp) + '</td>' +
            '<td><code>' + esc(log.command || log.type || '-') + '</code></td>' +
            '<td style="font-family:monospace;font-size:12px">' + esc(log.chatId || '-') + '</td>' +
            '<td style="font-family:monospace;font-size:12px">' + esc(log.userId || '-') + '</td>' +
            '</tr>';
        });
        html += '</tbody></table></div>';
        el('logsContent').innerHTML = html;
      } catch (x) {
        el('logsContent').innerHTML = '<div style="color:var(--danger)">' + esc(x.message) + '</div>';
      }
    }
  };

  function renderCommandRows(cmds) {
    var wrap = el('commandsEditor');
    wrap.innerHTML = '';
    if (!cmds || cmds.length === 0) {
      DevApp.addCommandRow('', '');
      return;
    }
    cmds.forEach(function (c) { DevApp.addCommandRow(c.command, c.description); });
  }

  function docRow(method, path, desc, example) {
    var methodClass = method.toLowerCase();
    return '<tr>' +
      '<td><span class="api-method ' + methodClass + '">' + method + '</span></td>' +
      '<td><code>' + esc(path) + '</code></td>' +
      '<td>' + esc(desc) + '</td>' +
      '<td style="font-size:12px;color:var(--text-muted);font-family:monospace">' + esc(example || '') + '</td>' +
      '</tr>';
  }

  // ─── Restore session on load ────────
  session = loadSession();
  if (session && session.token) {
    if (session.mode === 'bot') {
      // Validate the stored bot token is still good.
      api('/api/bot/me').then(function (me) {
        botData = me;
        enterApp();
      }).catch(function () {
        clearSession();
      });
    } else if (session.mode === 'dev') {
      // Validate the dev_session token via /me.
      api('/api/developer-account/me').then(function (me) {
        session.bots = me.bots || [];
        if (session.bots.length && !session.bots.some(function (b) { return b.id === session.selectedBotId; })) {
          session.selectedBotId = session.bots[0].id;
        }
        saveSession();
        enterApp();
      }).catch(function () {
        clearSession();
      });
    } else {
      clearSession();
    }
  }

})();
