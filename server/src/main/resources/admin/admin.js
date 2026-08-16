/* ═══════════════════════════════════════════════════════
   毛豆聊天管理后台 — 交互逻辑
   ═══════════════════════════════════════════════════════ */
(function () {
  'use strict';

  // ─── 状态 ───────────────────────────
  var token = '';
  var sessionExpiresAt = 0;
  var sessionTimer = 0;
  var activeTab = 'dashboard';
  var page = { users: 0, chats: 0, posts: 0, comments: 0, reports: 0, audit: 0, 'risk-events': 0, 'ai-usage': 0, 'push-tokens': 0 };
  var pageSize = 25;
  var searchQuery = {};
  var filterStatus = {};
  var dashboardData = null;
  var systemStatsData = null;

  // ─── DOM 辅助 ───────────────────────
  var el = function (id) { return document.getElementById(id); };
  var esc = function (v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  };
  var date = function (v) {
    if (!v) return '—';
    var d = new Date(v);
    var pad = function (n) { return String(n).padStart(2, '0'); };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  };
  var dateShort = function (v) {
    if (!v) return '—';
    var d = new Date(v);
    var pad = function (n) { return String(n).padStart(2, '0'); };
    return pad(d.getMonth() + 1) + '/' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  };
  var timeAgo = function (v) {
    if (!v) return '—';
    var diff = Date.now() - v;
    if (diff < 60000) return Math.floor(diff / 1000) + ' 秒前';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    return Math.floor(diff / 86400000) + ' 天前';
  };
  var fmtBytes = function (v) {
    if (!v || v < 1024) return v + ' B';
    if (v < 1048576) return (v / 1024).toFixed(1) + ' KB';
    if (v < 1073741824) return (v / 1048576).toFixed(1) + ' MB';
    return (v / 1073741824).toFixed(2) + ' GB';
  };
  var fmtDuration = function (ms) {
    if (!ms) return '—';
    var s = Math.floor(ms / 1000);
    var d = Math.floor(s / 86400);
    var h = Math.floor((s % 86400) / 3600);
    var m = Math.floor((s % 3600) / 60);
    if (d > 0) return d + '天 ' + h + '时';
    if (h > 0) return h + '时 ' + m + '分';
    return m + '分 ' + (s % 60) + '秒';
  };

  // ─── 审计动作标签 ───────────────────
  var auditLabels = {
    ADMIN_SESSION_ISSUED: '高权限会话签发',
    ADMIN_STATUS_UPDATE: '用户封禁状态变更',
    ADMIN_POST_RESTRICT: '禁动态状态变更',
    ADMIN_ACCOUNT_DEACTIVATED: '账号匿名化停用',
    ADMIN_POST_DELETED: '删除动态',
    ADMIN_COMMENT_DELETED: '删除评论',
    ADMIN_RULE_CREATED: '创建风控规则',
    ADMIN_RULE_UPDATED: '更新风控规则',
    ADMIN_RULE_DELETED: '删除风控规则',
    REPORT_STATUS_UPDATE: '更新举报状态',
    REPORT_ACTION_APPLIED: '执行举报处置',
    ADMIN_AUDIT_EXPORTED: '导出审计日志',
    ADMIN_CHAT_DISSOLVED: '解散群聊',
    RISK_EVENT_RESOLVED: '风控事件已处理'
  };
  var auditLabel = function (action) { return auditLabels[action] || action; };

  var reportReasonLabels = {
    SPAM: '垃圾信息',
    HARASSMENT: '骚扰',
    HATE_SPEECH: '仇恨言论',
    VIOLENCE: '暴力',
    ILLEGAL_CONTENT: '违法内容',
    INAPPROPRIATE: '不当内容',
    OTHER: '其他'
  };
  var reportReasonLabel = function (r) { return reportReasonLabels[r] || r; };

  // §5.1 用户处置原因模板（与 AdminDispositionPolicy 对齐；启动后可被 API 覆盖）
  var dispositionTemplates = {
    banReasons: [
      { code: 'spam', labelZh: '垃圾广告 / 引流', defaultDays: 7, requiresCustomNote: false },
      { code: 'harassment', labelZh: '骚扰 / 辱骂', defaultDays: 14, requiresCustomNote: false },
      { code: 'scam', labelZh: '诈骗 / 欺诈', defaultDays: 30, requiresCustomNote: false },
      { code: 'illegal', labelZh: '违法违规内容', defaultDays: 90, requiresCustomNote: false },
      { code: 'impersonation', labelZh: '冒充他人 / 仿冒', defaultDays: 30, requiresCustomNote: false },
      { code: 'abuse_api', labelZh: '滥用接口 / 自动化', defaultDays: 7, requiresCustomNote: false },
      { code: 'other', labelZh: '其他（须填写备注）', defaultDays: 1, requiresCustomNote: true }
    ],
    postRestrictReasons: [
      { code: 'spam_feed', labelZh: '垃圾动态 / 刷屏发帖', defaultDays: 1, requiresCustomNote: false },
      { code: 'harassment_feed', labelZh: '骚扰 / 不当内容', defaultDays: 3, requiresCustomNote: false },
      { code: 'scam_feed', labelZh: '引流 / 诈骗宣传', defaultDays: 7, requiresCustomNote: false },
      { code: 'illegal_feed', labelZh: '违法违规动态', defaultDays: 14, requiresCustomNote: false },
      { code: 'impersonation_feed', labelZh: '冒充 / 仿冒形象', defaultDays: 7, requiresCustomNote: false },
      { code: 'other', labelZh: '其他（须填写备注）', defaultDays: 1, requiresCustomNote: true }
    ],
    unbanReasonCode: 'unban',
    unrestrictPostsReasonCode: 'unrestrict_posts',
    messageRestrictReasons: [
      { code: 'spam_chat', labelZh: 'spam / flood', defaultDays: 1, requiresCustomNote: false },
      { code: 'harassment_chat', labelZh: 'harassment in chat', defaultDays: 3, requiresCustomNote: false },
      { code: 'scam_chat', labelZh: 'scam / fraud chat', defaultDays: 7, requiresCustomNote: false },
      { code: 'illegal_chat', labelZh: 'illegal content chat', defaultDays: 14, requiresCustomNote: false },
      { code: 'abuse_api', labelZh: 'api / bot abuse', defaultDays: 3, requiresCustomNote: false },
      { code: 'other', labelZh: 'other (note required)', defaultDays: 1, requiresCustomNote: true }
    ],
    unrestrictMessagesReasonCode: 'unrestrict_messages',
    appealNoticeZh: '申诉说明（只读）：用户可通过应用内「帮助与反馈」提交申诉；运营在审计日志中检索 reasonCode / note 后复核。当前版本不提供自动解封工单。',
    maxBanDays: 3650,
    maxPostRestrictDays: 90,
    maxMessageRestrictDays: 90
  };
  var dispositionTemplatesLoaded = false;
  async function ensureDispositionTemplates() {
    if (dispositionTemplatesLoaded || !token) return;
    try {
      var data = await api('/api/admin/disposition-templates');
      if (data && data.banReasons && data.banReasons.length) {
        dispositionTemplates = data;
        dispositionTemplatesLoaded = true;
      }
    } catch (e) {
      // keep embedded fallback
    }
  }

  // ─── Toast ──────────────────────────
  function toast(msg, type) {
    var t = el('toast');
    t.className = 'toast' + (type ? ' toast-' + type : '');
    t.querySelector('.toast-text').textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { t.classList.add('hidden'); }, 3000);
  }

  // ─── API ────────────────────────────
  async function api(path, opt) {
    opt = opt || {};
    opt.headers = Object.assign({ Authorization: 'Bearer ' + token }, opt.headers || {});
    if (opt.body) opt.headers['Content-Type'] = 'application/json';
    var r = await fetch(path, opt);
    var text = await r.text();
    var data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { /* non-JSON response */ } }
    if (!r.ok) throw new Error((data && data.error) || ('请求失败 ' + r.status));
    return data;
  }

  // ─── 会话时钟 ───────────────────────
  function startSessionClock() {
    clearInterval(sessionTimer);
    function tick() {
      var left = Math.max(0, Math.ceil((sessionExpiresAt - Date.now()) / 1000));
      var m = Math.floor(left / 60);
      var s = left % 60;
      el('session-info').querySelector('span').textContent = m + ':' + String(s).padStart(2, '0');
      if (left <= 30) el('session-info').style.color = 'var(--danger)';
      if (left === 0) {
        clearInterval(sessionTimer);
        token = '';
        toast('管理员会话已到期，请重新登录', 'error');
        setTimeout(function () { location.reload(); }, 1500);
      }
    }
    tick();
    sessionTimer = setInterval(tick, 1000);
  }

  // ─── 登录 ───────────────────────────
  el('login-form').addEventListener('submit', async function (e) {
    e.preventDefault();
    el('login-error').textContent = '';
    var btn = e.target.querySelector('button[type="submit"]');
    btn.disabled = true;
    btn.textContent = '验证中…';
    try {
      var password = el('password').value;
      var r = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: el('email').value, password: password })
      });
      // 8.47 修复：与 api() 一致先取文本再容错解析——网关 502/HTML 时 r.json() 抛
      // SyntaxError，错误信息对管理员是乱码
      var rtext = await r.text();
      var d = null;
      if (rtext) { try { d = JSON.parse(rtext); } catch (e) { d = null; } }
      if (!r.ok || !d || !d.token) throw new Error((d && d.error) || ('请求失败 ' + r.status));

      var sr = await fetch('/api/admin/session', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + d.token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: password })
      });
      var srtext = await sr.text();
      var sd = null;
      if (srtext) { try { sd = JSON.parse(srtext); } catch (e) { sd = null; } }
      if (!sr.ok || !sd || !sd.token) throw new Error((sd && sd.error) || ('管理员二次验证失败 ' + sr.status));

      token = sd.token;
      sessionExpiresAt = sd.expiresAt;
      startSessionClock();
      el('login').classList.add('hidden');
      el('app').classList.remove('hidden');
      el('password').value = '';
      password = '';
      await loadTab();
      loadNavBadges();
    } catch (x) {
      token = '';
      el('password').value = '';
      el('login-error').textContent = x.message;
    } finally {
      btn.disabled = false;
      btn.innerHTML = '<svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor"><path d="M8 1a2 2 0 012 2v1h1a2 2 0 012 2v2a2 2 0 01-2 2H5a2 2 0 01-2-2V6a2 2 0 012-2h1V3a2 2 0 012-2zM6 7v1h4V7H6zm0 3v1h4v-1H6z"/></svg>安全登录';
    }
  });

  // ─── 退出 ───────────────────────────
  el('logout').onclick = function () { token = ''; location.reload(); };

  // ─── 刷新 ───────────────────────────
  el('refresh-btn').onclick = function () { loadTab(); toast('已刷新'); };

  // ─── 侧边栏切换 ─────────────────────
  el('menu-toggle').onclick = function () {
    el('sidebar').classList.toggle('open');
    el('sidebar-overlay').classList.toggle('hidden');
  };
  el('sidebar-overlay').onclick = function () {
    el('sidebar').classList.remove('open');
    el('sidebar-overlay').classList.add('hidden');
  };

  // ─── 导航 ───────────────────────────
  var tabTitles = {
    dashboard: '仪表盘',
    users: '用户管理',
    chats: '群聊管理',
    posts: '动态管理',
    comments: '评论管理',
    reports: '举报审核',
    rules: '风控规则',
    'risk-events': '风控事件',
    'ai-usage': 'AI 审计',
    'push-tokens': '推送令牌',
    audit: '操作审计'
  };

  el('nav').onclick = function (e) {
    var b = e.target.closest('button[data-tab]');
    if (!b) return;
    activeTab = b.dataset.tab;
    document.querySelectorAll('.nav-item').forEach(function (x) { x.classList.toggle('active', x === b); });
    el('page-title').textContent = tabTitles[activeTab] || '';
    // 移动端关闭侧边栏
    el('sidebar').classList.remove('open');
    el('sidebar-overlay').classList.add('hidden');
    loadTab();
  };

  // ─── 加载 Tab ───────────────────────
  // 8.47 修复：loadSeq 版本号——快速切换 tab 时旧请求晚返回不得覆盖当前页（错误页/错页数据）
  var loadSeq = 0;
  // 8.47 补全：成功路径同样受 seq 守卫——loader 由 loadTab 传入 seq 时，
  // 若已有更新的 loadTab 启动（快速切换 tab），旧响应不得覆盖当前页
  function staleTab(seq) { return typeof seq === 'number' && seq !== loadSeq; }
  async function loadTab() {
    var seq = ++loadSeq;
    // 8.47 修复：B6 专属 tab（announcements/user-tags/rate-limit/device-consistency）
    // 由 B6 模块独立渲染——主模块不设 loading、不覆盖，避免与 B6 模块竞态双重重渲染
    var ownTabs = ['dashboard', 'ranking', 'online', 'users', 'chats', 'messages', 'posts', 'comments', 'reports', 'rules', 'risk-events', 'storage', 'ai-usage', 'push-tokens', 'audit', 'watermark', 'bots', 'settings'];
    if (ownTabs.indexOf(activeTab) < 0) return;
    el('content').innerHTML = '<div class="loading-state"><div class="spinner"></div><span>加载中…</span></div>';
    try {
      switch (activeTab) {
        case 'dashboard': await loadDashboard(seq); break;
        case 'ranking': await loadRanking(seq); break;
        case 'online': await loadOnline(seq); break;
        case 'users': await loadUsers(seq); break;
        case 'chats': await loadChats(seq); break;
        case 'messages': await loadMessageSearch(seq); break;
        case 'posts': await loadPosts(seq); break;
        case 'comments': await loadComments(seq); break;
        case 'reports': await loadReports(seq); break;
        case 'rules': await loadRules(seq); break;
        case 'risk-events': await loadRiskEvents(seq); break;
        case 'storage': await loadStorage(seq); break;
        case 'ai-usage': await loadAiUsage(seq); break;
        case 'push-tokens': await loadPushTokens(seq); break;
        case 'audit': await loadAudit(seq); break;
        case 'watermark': await loadWatermark(seq); break;
        case 'bots': await loadBots(seq); break;
        case 'settings': await loadSettings(seq); break;
      }
    } catch (x) {
      if (seq !== loadSeq) return;
      el('content').innerHTML = '<div class="empty-state"><svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 1a7 7 0 100 14 7 7 0 000-14zm0 4a1 1 0 110 2 1 1 0 010-2zm1 4v3H7V9h2z"/></svg><p>' + esc(x.message) + '</p></div>';
    }
  }

  // ─── 导航角标 ───────────────────────
  async function loadNavBadges() {
    try {
      var d = await api('/api/admin/dashboard');
      var rb = el('nav-reports-badge');
      if (d.pendingReports > 0) {
        rb.textContent = d.pendingReports;
        rb.classList.remove('hidden');
      } else {
        rb.classList.add('hidden');
      }
    } catch (e) { /* ignore */ }
    try {
      var s = await api('/api/admin/system-stats');
      var rib = el('nav-risk-badge');
      if (s.pendingRiskEvents > 0) {
        rib.textContent = s.pendingRiskEvents;
        rib.classList.remove('hidden');
      } else {
        rib.classList.add('hidden');
      }
      var ob = el('nav-online-badge');
      if (ob && s.onlineUsers > 0) {
        ob.textContent = s.onlineUsers;
        ob.classList.remove('hidden');
      } else if (ob) {
        ob.classList.add('hidden');
      }
    } catch (e) { /* ignore */ }
  }

  // ─── 分页 ───────────────────────────
  function pager(kind, count) {
    var p = page[kind] || 0;
    return '<div class="pagination">' +
      '<span class="page-info">第 ' + (p + 1) + ' 页</span>' +
      '<button class="btn btn-ghost btn-sm" data-prev="' + kind + '" ' + (p === 0 ? 'disabled' : '') + '>上一页</button>' +
      '<button class="btn btn-ghost btn-sm" data-next="' + kind + '" ' + (count < pageSize ? 'disabled' : '') + '>下一页</button>' +
      '</div>';
  }
  function bindPager(kind, count, loader) {
    var prev = document.querySelector('[data-prev="' + kind + '"]');
    var next = document.querySelector('[data-next="' + kind + '"]');
    if (prev) prev.onclick = function () { if ((page[kind] || 0) > 0) { page[kind]--; loader(); } };
    if (next) next.onclick = function () { if (count === pageSize) { page[kind] = (page[kind] || 0) + 1; loader(); } };
  }

  // ─── 搜索栏 ─────────────────────────
  function searchBar(kind, placeholder, extraFilters) {
    var sq = searchQuery[kind] || '';
    var fs = filterStatus[kind] || '';
    var html = '<div class="toolbar">' +
      '<input class="search-input" id="search-' + kind + '" value="' + esc(sq) + '" placeholder="' + placeholder + '"/>';
    if (extraFilters) {
      html += '<select class="filter-select" id="filter-' + kind + '">' + extraFilters + '</select>';
    }
    html += '<button class="btn btn-primary btn-sm" id="search-btn-' + kind + '">搜索</button></div>';
    return html;
  }
  function bindSearch(kind, loader) {
    var input = el('search-' + kind);
    var btn = el('search-btn-' + kind);
    var filter = el('filter-' + kind);
    function doSearch() {
      searchQuery[kind] = input.value.trim();
      if (filter) filterStatus[kind] = filter.value;
      page[kind] = 0;
      loader();
    }
    if (btn) btn.onclick = doSearch;
    if (input) input.onkeydown = function (e) { if (e.key === 'Enter') { e.preventDefault(); doSearch(); } };
    if (filter) filter.onchange = doSearch;
  }

  // ═════════════════════════════════════
  // 仪表盘
  // ═════════════════════════════════════
  async function loadDashboard(seq) {
    var d = await api('/api/admin/dashboard');
    var s = await api('/api/admin/system-stats');
    var t = await api('/api/admin/trends');
    var ops = null;
    try { ops = await api('/api/admin/ops-snapshot'); } catch (e) { ops = null; }
    var sec = null;
    try { sec = await api('/api/admin/security-snapshot'); } catch (e) { sec = null; }
    dashboardData = d;
    systemStatsData = s;
    window.__opsSnapshot = ops;
    window.__securitySnapshot = sec;
    // expose quick broadcast from console/toolbar

    var memPct = s.jvmMaxMemoryBytes > 0 ? Math.round(s.jvmUsedMemoryBytes / s.jvmMaxMemoryBytes * 100) : 0;

    var statsHtml = '<div style="margin:0 0 12px 0"><button class="btn primary" onclick="adminBroadcast()">Broadcast to online</button></div>' +
      '<div class="stats-grid">' +
      statCard('users', '总用户数', d.totalUsers, d.activeUsers24h + ' 人 24h 活跃', 'green') +
      statCard('posts', '动态总数', d.totalPosts, s.totalComments + ' 条评论', 'blue') +
      statCard('reports', '待审举报', d.pendingReports, '共 ' + d.totalReports + ' 条举报', d.pendingReports > 0 ? 'red' : '') +
      statCard('rules', '启用规则', d.activeModerationRules, '风控规则数', 'orange') +
      statCard('messages', '消息总量', s.totalMessages, '已发送消息', 'blue') +
      statCard('chats', '群聊总数', s.totalGroups, '共 ' + s.totalChats + ' 个会话', 'green') +
      statCard('storage', '附件存储', fmtBytes(s.attachmentStorageBytes), s.totalAttachments + ' 个文件', '') +
      statCard('online', '在线用户', s.onlineUsers, '当前在线', 'green') +
      '</div>';
    if (ops) {
      statsHtml += statCard('bots', 'Bots', ops.botsEnabled + '/' + ops.botsTotal, (ops.botsWithWebhook || 0) + ' webhooks', 'blue');
      statsHtml += statCard('polls', 'Polls', ops.pollsOpen + '/' + ops.pollsTotal, (ops.pollVotes || 0) + ' votes', 'green');
    }
    if (sec && sec.flags) {
      var f = sec.flags || {};
      var lim = sec.limits || {};
      statsHtml += statCard('sec-ai', 'Cloud AI', f.aiEnabled ? 'ON' : 'OFF', 'runtime kill switch', f.aiEnabled ? 'green' : 'red');
      statsHtml += statCard('sec-bots', 'Bot platform', f.botsAllowed ? 'ON' : 'OFF', 'allow_bots', f.botsAllowed ? 'green' : 'orange');
      statsHtml += statCard('sec-sealed', 'Sealed sender', f.sealedSenderEnabled ? 'ON' : 'OFF', 'certificates', f.sealedSenderEnabled ? 'green' : '');
      statsHtml += statCard('sec-ip', 'IP blocklist', lim.ipBlocklistCount || 0, 'max msg/min ' + (lim.maxMessagePerMin || '-'), (lim.ipBlocklistCount > 0) ? 'red' : '');
      statsHtml += statCard('sec-risk', 'Risk review', sec.openRiskEvents || 0, 'needs_review events', (sec.openRiskEvents > 0) ? 'red' : 'green');
      statsHtml += statCard('sec-maint', 'Maintenance', f.maintenanceMode ? 'YES' : 'no', f.registrationOpen ? 'reg open' : 'reg closed', f.maintenanceMode ? 'red' : '');
      statsHtml += statCard('sec-pqxdh', 'PQXDH preview', f.pqxdhPreview ? 'ON' : 'OFF', 'min app ' + (lim.minAppVersion || '0'), f.pqxdhPreview ? 'green' : '');
      statsHtml += statCard('sec-secret', 'Secret required', f.secretChatRequired ? 'YES' : 'no', '1:1 policy banner', f.secretChatRequired ? 'orange' : '');
      statsHtml += statCard('sec-capture', 'Capture alerts', f.captureAlertEnabled === false ? 'OFF' : 'ON', 'max bots/user ' + (lim.maxBotsPerUser || '-'), f.captureAlertEnabled === false ? 'red' : 'green');
    }


    // 趋势图
    var chartHtml = '<div class="chart-row">' +
      chartCard('近 7 天新增用户', t.newUsers, '#6366f1') +
      chartCard('近 7 天消息量', t.newMessages, '#10b981') +
      chartCard('近 7 天动态量', t.newPosts, '#f59e0b') +
      '</div>';

    // 系统健康
    var healthHtml = '<div class="panel"><div class="panel-header"><h2>系统健康</h2></div><div class="panel-body"><div style="padding:18px">' +
      '<div class="detail-grid">' +
      detailItem('服务器运行时间', fmtDuration(s.serverUptimeMs)) +
      detailItem('JVM 内存使用', fmtBytes(s.jvmUsedMemoryBytes) + ' / ' + fmtBytes(s.jvmMaxMemoryBytes) + ' (' + memPct + '%)') +
      detailItem('活跃线程数', s.activeThreads) +
      detailItem('AI 调用总数', s.totalAiCalls + (s.aiErrorCount > 0 ? '（' + s.aiErrorCount + ' 错误）' : '')) +
      detailItem('风控事件', s.totalRiskEvents + (s.pendingRiskEvents > 0 ? '（' + s.pendingRiskEvents + ' 待处理）' : '')) +
      detailItem('推送令牌', s.totalPushTokens) +
      detailItem('点赞总数', s.totalPostLikes) +
      detailItem('已注销用户', d.deactivatedUsers) +
      '</div>' +
      '<div class="mem-bar"><div class="mem-bar-fill" style="width:' + memPct + '%"></div></div>' +
      '</div></div></div>';

    if (staleTab(seq)) return;
    el('content').innerHTML = statsHtml + chartHtml + healthHtml;
  }

  function statCard(icon, label, value, sub, color) {
    var icons = {
      users: '<path d="M8 3a2.5 2.5 0 100 5 2.5 2.5 0 000-5zM3 12c0-2 2.2-3.5 5-3.5s5 1.5 5 3.5v1H3v-1z"/>',
      posts: '<path d="M3 2a1 1 0 00-1 1v10a1 1 0 001 1h10a1 1 0 001-1V5l-3-3H3zm2 4h6v1H5V6zm0 3h6v1H5V9z"/>',
      reports: '<path d="M8 1L1 14h14L8 1zm0 4v4H7V5h1zm0 6v1H7v-1h1z"/>',
      rules: '<path d="M8 1l6 2v4c0 3.5-2.5 6.5-6 8-3.5-1.5-6-4.5-6-8V3l6-2z"/>',
      messages: '<path d="M2 3a1 1 0 011-1h10a1 1 0 011 1v7a1 1 0 01-1 1H7l-3 3v-3H3a1 1 0 01-1-1V3z"/>',
      chats: '<path d="M3 4a1 1 0 011-1h8a1 1 0 011 1v6a1 1 0 01-1 1H6l-3 3v-3a1 1 0 01-1-1V4z"/>',
      storage: '<path d="M2 4a1 1 0 011-1h10a1 1 0 011 1v8a1 1 0 01-1 1H3a1 1 0 01-1-1V4zm2 2v2h8V6H4z"/>',
      online: '<path d="M8 2a6 6 0 100 12 6 6 0 000-12zm0 3a3 3 0 110 6 3 3 0 010-6z"/>'
    };
    return '<div class="stat-card">' +
      '<div class="stat-icon ' + (color || '') + '"><svg viewBox="0 0 16 16" fill="currentColor">' + (icons[icon] || icons.users) + '</svg></div>' +
      '<div class="stat-label">' + label + '</div>' +
      '<div class="stat-value">' + esc(value) + '</div>' +
      '<div class="stat-sub">' + esc(sub) + '</div>' +
      '</div>';
  }

  function chartCard(title, points, color) {
    if (!points || points.length === 0) return '<div class="chart-card"><h3>' + esc(title) + '</h3><div class="muted">暂无数据</div></div>';
    var max = Math.max.apply(null, points.map(function (p) { return p.value; }));
    if (max === 0) max = 1;
    var w = 100, h = 60;
    var step = w / (points.length - 1 || 1);
    var path = points.map(function (p, i) {
      var x = i * step;
      var y = h - (p.value / max) * (h - 8) - 4;
      return (i === 0 ? 'M' : 'L') + x.toFixed(1) + ',' + y.toFixed(1);
    }).join(' ');
    var areaPath = path + ' L' + w + ',' + h + ' L0,' + h + ' Z';
    var labels = points.map(function (p, i) {
      var d = new Date(p.timestamp);
      return '<text x="' + (i * step).toFixed(1) + '" y="' + (h + 14) + '" text-anchor="middle" font-size="7" fill="currentColor" opacity="0.6">' + (d.getMonth() + 1) + '/' + d.getDate() + '</text>';
    }).join('');
    var vals = points.map(function (p, i) {
      var x = i * step;
      var y = h - (p.value / max) * (h - 8) - 4;
      return '<circle cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" r="2" fill="' + color + '"><title>' + p.value + '</title></circle>';
    }).join('');
    return '<div class="chart-card"><h3>' + esc(title) + '</h3>' +
      '<svg class="chart-svg" viewBox="0 0 ' + w + ' ' + (h + 18) + '" preserveAspectRatio="none">' +
      '<path d="' + areaPath + '" fill="' + color + '" opacity="0.1"/>' +
      '<path d="' + path + '" fill="none" stroke="' + color + '" stroke-width="1.5"/>' +
      vals + labels +
      '</svg></div>';
  }

  function detailItem(label, value) {
    return '<div class="detail-item"><span class="label">' + esc(label) + '</span><span class="value">' + esc(value) + '</span></div>';
  }
  function detailItemHtml(label, html) {
    return '<div class="detail-item"><span class="label">' + esc(label) + '</span><span class="value">' + html + '</span></div>';
  }

  // ═════════════════════════════════════
  // 用户管理
  // ═════════════════════════════════════
  async function loadUsers(seq) {
    var q = searchQuery.users || '';
    var st = filterStatus.users || '';
    var offset = (page.users || 0) * pageSize;
    var url = '/api/admin/users?limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&q=' + encodeURIComponent(q);
    if (st) url += '&status=' + st;
    var rows = await api(url);

    var filters = '<option value="">全部状态</option>' +
      '<option value="active"' + (st === 'active' ? ' selected' : '') + '>正常</option>' +
      '<option value="banned"' + (st === 'banned' ? ' selected' : '') + '>已封禁</option>' +
      '<option value="online"' + (st === 'online' ? ' selected' : '') + '>在线</option>' +
      '<option value="deleted"' + (st === 'deleted' ? ' selected' : '') + '>已注销</option>';

    var html = '<div class="panel">' +
      '<div class="panel-header">' +
      '<h2>用户管理</h2>' +
      searchBar('users', '搜索用户名或邮箱…', filters) +
      '<button class="btn btn-ghost btn-sm" id="users-export-btn">Export CSV</button>' +
      '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>用户</th><th>邮箱</th><th>状态</th><th>最近活跃</th><th>操作</th></tr></thead>' +
      '<tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无用户数据</p></div></td></tr>';
    } else {
      rows.forEach(function (u) {
        var status;
        if (u.deletedAt) status = '<span class="badge badge-red">已注销</span>';
        else if (u.suspendedUntil > Date.now()) status = '<span class="badge badge-red">封禁至 ' + esc(dateShort(u.suspendedUntil)) + '</span>';
        else if (u.postRestrictedUntil > Date.now()) status = '<span class="badge badge-orange">禁动态至 ' + esc(dateShort(u.postRestrictedUntil)) + '</span>';
        else if (u.isModerator) status = '<span class="badge badge-purple">审核员</span>';
        else status = '<span class="badge badge-green">正常</span>';

        var actions = '';
        if (!u.deletedAt) {
          actions += '<button class="btn btn-ghost btn-sm" data-detail="' + esc(u.id) + '">详情</button>';
          actions += '<button class="btn btn-ghost btn-sm" data-ban="' + esc(u.id) + '">封禁/解封</button>';
          actions += '<button class="btn btn-ghost btn-sm" data-post-restrict="' + esc(u.id) + '">禁动态</button>';
          actions += '<button class="btn btn-ghost btn-sm" data-message-restrict="' + esc(u.id) + '">Msg ban</button>';
          actions += '<button class="btn btn-danger btn-sm" data-deactivate="' + esc(u.id) + '">停用</button>';
        }

        html += '<tr>' +
          '<td><div class="cell-main">' + esc(u.name) + '</div><div class="cell-id">' + esc(u.id) + '</div></td>' +
          '<td>' + esc(u.email) + '</td>' +
          '<td>' + status + '</td>' +
          '<td>' + esc(timeAgo(u.lastActiveAt)) + '</td>' +
          '<td><div class="toolbar">' + actions + '</div></td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('users', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    bindSearch('users', loadUsers);
    bindPager('users', rows.length, loadUsers);
    bindUserActions();
    var ue = el('users-export-btn');
    if (ue) {
      ue.onclick = async function () {
        try {
          var res = await fetch('/api/admin/users-export?limit=5000', {
            headers: { Authorization: 'Bearer ' + token }
          });
          if (!res.ok) throw new Error('export failed ' + res.status);
          var blob = await res.blob();
          var a = document.createElement('a');
          a.href = URL.createObjectURL(blob);
          a.download = 'maodouchat-users-' + Date.now() + '.csv';
          a.click();
          URL.revokeObjectURL(a.href);
          toast('Users CSV exported', 'success');
        } catch (e) {
          toast('Export failed: ' + (e && e.message ? e.message : e), 'error');
        }
      };
    }
  }

  function bindUserActions() {
    document.querySelectorAll('[data-detail]').forEach(function (b) {
      b.onclick = async function () {
        try { await showUserDetail(b.dataset.detail); }
        catch (e) { toast(e.message || '加载详情失败', 'error'); }
      };
    });
    document.querySelectorAll('[data-deactivate]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.deactivate;
        showConfirm('停用账号', '停用后账号将被匿名化，所有登录凭据撤销，此操作不可逆。确认停用账号 ' + id + '？', 'danger', async function () {
          await api('/api/admin/users/' + encodeURIComponent(id), { method: 'DELETE' });
          toast('账号已停用', 'success');
          await loadUsers();
          loadNavBadges();
        });
      };
    });
    document.querySelectorAll('[data-ban]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.ban;
        await ensureDispositionTemplates();
        var maxDays = dispositionTemplates.maxBanDays || 3650;
        var appeal = dispositionTemplates.appealNoticeZh || '';
        showSelect(
          '用户处置',
          '选择操作（封禁须选原因模板；解封可选备注）\n' + appeal,
          [{ value: 'unban', label: '解封' }].concat(
            (dispositionTemplates.banReasons || []).map(function (r) {
              return { value: r.code, label: r.labelZh + '（默认 ' + r.defaultDays + ' 天）' };
            })
          ),
          'spam',
          async function (reasonCode) {
            if (reasonCode === 'unban' || reasonCode === dispositionTemplates.unbanReasonCode) {
              showPrompt('解封用户', '可选备注（写入审计，最多 400 字）', '', '备注', async function (note) {
                await api('/api/admin/users/' + encodeURIComponent(id) + '/status', {
                  method: 'PUT',
                  body: JSON.stringify({
                    bannedUntil: 0,
                    reasonCode: dispositionTemplates.unbanReasonCode || 'unban',
                    note: note || null
                  })
                });
                toast('已解封', 'success');
                await loadUsers();
                return true;
              });
              return true;
            }
            var template = (dispositionTemplates.banReasons || []).find(function (r) { return r.code === reasonCode; });
            var defaultDays = template ? String(template.defaultDays) : '7';
            showPrompt(
              '封禁天数',
              '原因：' + (template ? template.labelZh : reasonCode) + '（1–' + maxDays + ' 天）\n' + appeal,
              defaultDays,
              '天数',
              async function (val) {
                var n = Number(val);
                if (!Number.isFinite(n) || n < 1 || n > maxDays) { toast('天数无效', 'error'); return false; }
                var until = Date.now() + Math.round(n * 86400000);
                var finish = async function (note) {
                  if (template && template.requiresCustomNote && !(note && String(note).trim())) {
                    toast('该原因须填写备注', 'error');
                    return false;
                  }
                  await api('/api/admin/users/' + encodeURIComponent(id) + '/status', {
                    method: 'PUT',
                    body: JSON.stringify({
                      bannedUntil: until,
                      reasonCode: reasonCode,
                      note: note ? String(note).trim() : null
                    })
                  });
                  toast('已封禁 ' + n + ' 天（' + reasonCode + '）', 'success');
                  await loadUsers();
                  return true;
                };
                if (template && template.requiresCustomNote) {
                  showPrompt('处置备注', '该原因须填写备注（审计元数据，无聊天正文）', '', '备注', finish);
                  return true;
                }
                return finish(null);
              }
            );
            return true;
          }
        );
      };
    });
    document.querySelectorAll('[data-post-restrict]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.postRestrict;
        await ensureDispositionTemplates();
        var maxDays = dispositionTemplates.maxPostRestrictDays || 90;
        var appeal = dispositionTemplates.appealNoticeZh || '';
        var reasons = dispositionTemplates.postRestrictReasons || [];
        showSelect(
          '禁动态处置',
          '限制发布动态（不封禁登录；写路径 rejectIfPostRestricted 生效）\n' + appeal,
          [{ value: 'unrestrict_posts', label: '解除禁动态' }].concat(
            reasons.map(function (r) {
              return { value: r.code, label: r.labelZh + '（默认 ' + r.defaultDays + ' 天）' };
            })
          ),
          'spam_feed',
          async function (reasonCode) {
            if (reasonCode === 'unrestrict_posts' || reasonCode === dispositionTemplates.unrestrictPostsReasonCode) {
              showPrompt('解除禁动态', '可选备注（写入审计，最多 400 字）', '', '备注', async function (note) {
                await api('/api/admin/users/' + encodeURIComponent(id) + '/post-restriction', {
                  method: 'PUT',
                  body: JSON.stringify({
                    postRestrictedUntil: 0,
                    reasonCode: dispositionTemplates.unrestrictPostsReasonCode || 'unrestrict_posts',
                    note: note || null
                  })
                });
                toast('已解除禁动态', 'success');
                await loadUsers();
                return true;
              });
              return true;
            }
            var template = reasons.find(function (r) { return r.code === reasonCode; });
            var defaultDays = template ? String(template.defaultDays) : '1';
            showPrompt(
              '禁动态天数',
              '原因：' + (template ? template.labelZh : reasonCode) + '（1–' + maxDays + ' 天）\n' + appeal,
              defaultDays,
              '天数',
              async function (val) {
                var n = Number(val);
                if (!Number.isFinite(n) || n < 1 || n > maxDays) { toast('天数无效', 'error'); return false; }
                var until = Date.now() + Math.round(n * 86400000);
                var finish = async function (note) {
                  if (template && template.requiresCustomNote && !(note && String(note).trim())) {
                    toast('该原因须填写备注', 'error');
                    return false;
                  }
                  await api('/api/admin/users/' + encodeURIComponent(id) + '/post-restriction', {
                    method: 'PUT',
                    body: JSON.stringify({
                      postRestrictedUntil: until,
                      reasonCode: reasonCode,
                      note: note ? String(note).trim() : null
                    })
                  });
                  toast('已禁动态 ' + n + ' 天（' + reasonCode + '）', 'success');
                  await loadUsers();
                  return true;
                };
                if (template && template.requiresCustomNote) {
                  showPrompt('处置备注', '该原因须填写备注（审计元数据，无聊天正文）', '', '备注', finish);
                  return true;
                }
                return finish(null);
              }
            );
            return true;
          }
        );
      };
    });

    document.querySelectorAll('[data-message-restrict]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.messageRestrict;
        await ensureDispositionTemplates();
        var maxDays = dispositionTemplates.maxMessageRestrictDays || 90;
        var appeal = dispositionTemplates.appealNoticeZh || '';
        var reasons = dispositionTemplates.messageRestrictReasons || [];
        showSelect(
          'Message restriction',
          'Restrict sending messages (enforced by rejectIfMessageRestricted).\n' + appeal,
          [{ value: 'unrestrict_messages', label: 'Clear message ban' }].concat(
            reasons.map(function (r) {
              return { value: r.code, label: (r.labelZh || r.code) + ' (default ' + r.defaultDays + 'd)' };
            })
          ),
          'spam_chat',
          async function (reasonCode) {
            if (reasonCode === 'unrestrict_messages' || reasonCode === dispositionTemplates.unrestrictMessagesReasonCode) {
              showPrompt('Clear message ban', 'Optional note (max 400)', '', 'Note', async function (note) {
                await api('/api/admin/users/' + encodeURIComponent(id) + '/message-restriction', {
                  method: 'PUT',
                  body: JSON.stringify({
                    messageRestrictedUntil: 0,
                    reasonCode: dispositionTemplates.unrestrictMessagesReasonCode || 'unrestrict_messages',
                    note: note || null
                  })
                });
                toast('Message ban cleared', 'success');
                await loadUsers();
                return true;
              });
              return true;
            }
            var template = reasons.find(function (r) { return r.code === reasonCode; });
            var defaultDays = template ? String(template.defaultDays) : '1';
            showPrompt(
              'Message ban days',
              'Reason: ' + (template ? template.labelZh : reasonCode) + ' (1-' + maxDays + 'd)\n' + appeal,
              defaultDays,
              'Days',
              async function (val) {
                var n = Number(val);
                if (!Number.isFinite(n) || n < 1 || n > maxDays) { toast('Invalid days', 'error'); return false; }
                var until = Date.now() + Math.round(n * 86400000);
                var finish = async function (note) {
                  if (template && template.requiresCustomNote && !(note && String(note).trim())) {
                    toast('Note required', 'error');
                    return false;
                  }
                  await api('/api/admin/users/' + encodeURIComponent(id) + '/message-restriction', {
                    method: 'PUT',
                    body: JSON.stringify({
                      messageRestrictedUntil: until,
                      reasonCode: reasonCode,
                      note: note ? String(note).trim() : null
                    })
                  });
                  toast('Message banned ' + n + 'd (' + reasonCode + ')', 'success');
                  await loadUsers();
                  return true;
                };
                if (template && template.requiresCustomNote) {
                  showPrompt('Custom note', 'Required note', '', 'Note', finish);
                  return true;
                }
                return finish(null);
              }
            );
            return true;
          }
        );
      };
    });

  }

  async function showUserDetail(id) {
    var d = await api('/api/admin/users/' + encodeURIComponent(id) + '/detail');
    var drawerBody = el('drawer-body');
    el('drawer-title').textContent = d.name;

    var status;
    if (d.deletedAt) status = '<span class="badge badge-red">已注销</span>';
    else if (d.suspendedUntil > Date.now()) status = '<span class="badge badge-red">封禁至 ' + esc(date(d.suspendedUntil)) + '</span>';
    else if (d.postRestrictedUntil > Date.now()) status = '<span class="badge badge-orange">禁动态至 ' + esc(date(d.postRestrictedUntil)) + '</span>';
    else status = '<span class="badge badge-green">正常</span>';

    var postRestrictLabel = (d.postRestrictedUntil && d.postRestrictedUntil > Date.now())
      ? date(d.postRestrictedUntil)
      : '无';
    var msgRestrictLabel = (d.messageRestrictedUntil && d.messageRestrictedUntil > Date.now())
      ? date(d.messageRestrictedUntil)
      : '无';

    drawerBody.innerHTML =
      '<div class="detail-section">' +
      '<div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">' +
      '<div style="width:56px;height:56px;border-radius:50%;background:var(--brand-bg);display:flex;align-items:center;justify-content:center;font-size:24px;font-weight:700;color:var(--brand)">' + esc(d.name.charAt(0).toUpperCase()) + '</div>' +
      '<div><div style="font-size:18px;font-weight:700">' + esc(d.name) + '</div><div style="font-size:13px;color:var(--text-muted)">' + esc(d.email) + '</div></div>' +
      '</div>' +
      '<div class="detail-grid">' +
      detailItem('用户 ID', d.id) +
      detailItemHtml('状态', status) +
      detailItem('禁动态至', postRestrictLabel) +
      detailItem('禁消息至', msgRestrictLabel) +
      detailItem('最近活跃', date(d.lastActiveAt)) +
      detailItem('是否审核员', d.isModerator ? '是' : '否') +
      '</div></div>' +
      '<div class="detail-section"><h4>活动统计</h4><div class="detail-grid">' +
      detailItem('消息数', d.messageCount) +
      detailItem('动态数', d.postCount) +
      detailItem('评论数', d.commentCount) +
      detailItem('群聊数', d.chatCount) +
      detailItem('推送令牌', d.pushTokenCount) +
      detailItem('相关举报', d.reportCount) +
      '</div></div>' +
      '<div class="detail-section"><h4>Security ops</h4>' +
      '<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px">' +
      '<button class="btn btn-danger" data-ua="force-logout" data-user-id="' + esc(d.id) + '">Force logout</button>' +
      '<button class="btn" data-ua="sessions" data-user-id="' + esc(d.id) + '">Sessions</button>' +
      '<button class="btn btn-danger" data-ua="msg-restrict" data-user-id="' + esc(d.id) + '">Msg ban</button>' +
      '<button class="btn" data-ua="grant-mod" data-user-id="' + esc(d.id) + '">Grant moderator</button>' +
      '<button class="btn" data-ua="revoke-mod" data-user-id="' + esc(d.id) + '">Revoke moderator</button>' +
      '<button class="btn" data-ua="disable-totp" data-user-id="' + esc(d.id) + '">Disable TOTP</button>' +
      '</div></div>';

    // 8.48 修复：内联 onclick 的 esc() 对 JS 字符串上下文无效（&#39; 属性解析后还原为引号），
    // 改为 data-* + 事件委托；onclick 属性赋值幂等，不会随抽屉重开而叠加
    drawerBody.onclick = function (ev) {
      var btn = ev.target && ev.target.closest ? ev.target.closest('button[data-ua]') : null;
      if (!btn) return;
      var uid = btn.getAttribute('data-user-id') || '';
      switch (btn.getAttribute('data-ua')) {
        case 'force-logout': adminForceLogout(uid); break;
        case 'sessions': adminLoadUserSessions(uid); break;
        case 'msg-restrict': adminMessageRestrict(uid); break;
        case 'grant-mod': adminSetModerator(uid, true); break;
        case 'revoke-mod': adminSetModerator(uid, false); break;
        case 'disable-totp': adminDisableTotp(uid); break;
      }
    };

    el('drawer-overlay').classList.remove('hidden');
  }

  // ═════════════════════════════════════
  // 群聊管理
  // ═════════════════════════════════════
  
  async function loadMessageSearch(seq) {
    var n = el('page-title'); if (n) n.textContent = 'Message search';
    var q = (searchQuery.messages || '');
    var html = '<div class="panel"><div class="panel-header"><h2>Message metadata search</h2></div><div class="panel-body">' +
      '<p style="color:var(--text-muted);font-size:13px">E2EE payloads are opaque. Search by message id, type, SYSTEM/NUDGE text, chatId, or senderId.</p>' +
      '<div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px">' +
      '<input id="msg-q" placeholder="query / id / type" value="' + esc(q) + '" style="flex:1;min-width:160px"/>' +
      '<input id="msg-chat" placeholder="chatId" style="width:180px"/>' +
      '<input id="msg-user" placeholder="senderId" style="width:180px"/>' +
      '<button class="btn btn-primary" id="msg-search-btn">Search</button></div>' +
      '<div id="msg-results"><div class="empty-state"><p>Enter filters and search</p></div></div></div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    async function run() {
      var qq = (el('msg-q').value || '').trim();
      var chatId = (el('msg-chat').value || '').trim();
      var userId = (el('msg-user').value || '').trim();
      searchQuery.messages = qq;
      if (!qq && !chatId && !userId) { toast('Need q, chatId, or userId', 'error'); return; }
      var url = '/api/admin/messages/search?limit=50&offset=0';
      if (qq) url += '&q=' + encodeURIComponent(qq);
      if (chatId) url += '&chatId=' + encodeURIComponent(chatId);
      if (userId) url += '&userId=' + encodeURIComponent(userId);
      try {
        var data = await api(url);
        var items = data.items || [];
        var table = '<div class="table-wrap"><table><thead><tr><th>Time</th><th>Chat</th><th>Sender</th><th>Type</th><th>Preview</th><th>Flags</th></tr></thead><tbody>';
        if (!items.length) table += '<tr><td colspan="6"><div class="empty-state"><p>No rows</p></div></td></tr>';
        items.forEach(function (m) {
          var flags = [];
          if (m.sealedSender) flags.push('sealed');
          if (m.e2eeLikely) flags.push('e2ee?');
          table += '<tr>' +
            '<td>' + esc(date(m.timestamp)) + '</td>' +
            '<td><div class="cell-id">' + esc(m.chatId) + '</div></td>' +
            '<td><div class="cell-id">' + esc(m.senderId) + '</div></td>' +
            '<td>' + esc(m.type) + '</td>' +
            '<td style="max-width:280px;word-break:break-all">' + esc(m.contentPreview || '') + '</td>' +
            '<td>' + esc(flags.join(', ') || '-') + '</td></tr>';
        });
        table += '</tbody></table></div>';
        // 8.47 修复：检索固定 limit=50 无分页——命中达到上限时明确提示截断，避免误以为全集
        if (data.count >= 50) {
          table += '<p style="color:var(--text-muted);font-size:12px;margin-top:8px">结果可能被截断（仅显示前 50 条），请细化筛选条件后重试。</p>';
        }
        el('msg-results').innerHTML = table;
      } catch (e) {
        toast('Search failed: ' + (e && e.message ? e.message : e), 'error');
      }
    }
    el('msg-search-btn').onclick = run;
    el('msg-q').onkeydown = function (e) { if (e.key === 'Enter') run(); };
  }

async function loadChats(seq) {
    var q = searchQuery.chats || '';
    var offset = (page.chats || 0) * pageSize;
    var url = '/api/admin/chats?limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&q=' + encodeURIComponent(q);
    var rows = await api(url);

    var html = '<div class="panel">' +
      '<div class="panel-header"><h2>群聊管理</h2>' + searchBar('chats', '搜索群名称…') + '<button class="btn btn-ghost btn-sm" id="chats-export-btn">Export chats CSV</button>' +
      '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>群名称</th><th>类型</th><th>成员数</th><th>最后活动</th><th>操作</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无群聊数据</p></div></td></tr>';
    } else {
      rows.forEach(function (c) {
        var typeBadge = c.isGroup ? '<span class="badge badge-blue">群聊</span>' : '<span class="badge">单聊</span>';
        html += '<tr>' +
          '<td><div class="cell-main">' + esc(c.groupName || '(未命名)') + '</div><div class="cell-id">' + esc(c.id) + '</div></td>' +
          '<td>' + typeBadge + '</td>' +
          '<td>' + esc(c.memberCount) + ' 人</td>' +
          '<td>' + esc(c.lastActivity ? timeAgo(c.lastActivity) : '—') + '</td>' +
          '<td>' + (c.isGroup ? '<button class="btn btn-danger btn-sm" data-dissolve="' + esc(c.id) + '">解散</button>' : '—') + '</td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('chats', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    bindSearch('chats', loadChats);
    bindPager('chats', rows.length, loadChats);

    document.querySelectorAll('[data-dissolve]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.dissolve;
        showConfirm('解散群聊', '解散后所有成员将被移除，聊天记录将被删除，此操作不可逆。确认解散群聊 ' + id + '？', 'danger', async function () {
          await api('/api/admin/chats/' + encodeURIComponent(id), { method: 'DELETE' });
          toast('群聊已解散', 'success');
          await loadChats();
        });
      };
    });
  
    var ce = el('chats-export-btn');
    if (ce) {
      ce.onclick = async function () {
        try {
          var res = await fetch('/api/admin/chats-export?limit=2000', {
            headers: { Authorization: 'Bearer ' + token }
          });
          if (!res.ok) throw new Error('export failed ' + res.status);
          var blob = await res.blob();
          var a = document.createElement('a');
          a.href = URL.createObjectURL(blob);
          a.download = 'maodouchat-chats-' + Date.now() + '.csv';
          a.click();
          URL.revokeObjectURL(a.href);
          toast('Chats CSV exported', 'success');
        } catch (e) {
          toast('Export failed: ' + (e && e.message ? e.message : e), 'error');
        }
      };
    }

  }

  // ═════════════════════════════════════
  // 动态管理
  // ═════════════════════════════════════
  async function loadPosts(seq) {
    var q = searchQuery.posts || '';
    var st = filterStatus.posts || '';
    var offset = (page.posts || 0) * pageSize;
    var url = '/api/admin/posts?limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&q=' + encodeURIComponent(q);
    if (st) url += '&status=' + st;
    var rows = await api(url);

    var filters = '<option value="">全部状态</option>' +
      '<option value="PUBLISHED"' + (st === 'PUBLISHED' ? ' selected' : '') + '>已发布</option>' +
      '<option value="HELD"' + (st === 'HELD' ? ' selected' : '') + '>已暂扣</option>' +
      '<option value="DELETED"' + (st === 'DELETED' ? ' selected' : '') + '>已删除</option>';

    var html = '<div class="panel"><div class="panel-header"><h2>动态管理</h2>' + searchBar('posts', '搜索动态内容…', filters) + '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>作者</th><th>内容</th><th>状态</th><th>发布时间</th><th>操作</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无动态数据</p></div></td></tr>';
    } else {
      rows.forEach(function (p) {
        var statusBadge = p.status === 'PUBLISHED' ? '<span class="badge badge-green">已发布</span>' :
          p.status === 'HELD' ? '<span class="badge badge-orange">已暂扣</span>' :
            '<span class="badge badge-red">已删除</span>';
        html += '<tr>' +
          '<td><div class="cell-main">' + esc(p.authorName) + '</div><div class="cell-id">' + esc(p.authorId) + '</div></td>' +
          '<td style="max-width:300px">' + esc(p.content.slice(0, 200)) + (p.content.length > 200 ? '…' : '') + '</td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' + esc(date(p.createdAt)) + '</td>' +
          '<td><button class="btn btn-danger btn-sm" data-post="' + esc(p.id) + '">删除</button></td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('posts', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    bindSearch('posts', loadPosts);
    bindPager('posts', rows.length, loadPosts);

    document.querySelectorAll('[data-post]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.post;
        showConfirm('删除动态', '确认删除这条动态？此操作不可逆。', 'danger', async function () {
          await api('/api/admin/posts/' + encodeURIComponent(id), { method: 'DELETE' });
          toast('动态已删除', 'success');
          await loadPosts();
          loadNavBadges();
        });
      };
    });
  }

  // ═════════════════════════════════════
  // 评论管理
  // ═════════════════════════════════════
  async function loadComments(seq) {
    var q = searchQuery.comments || '';
    var offset = (page.comments || 0) * pageSize;
    var url = '/api/admin/comments?limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&q=' + encodeURIComponent(q);
    var rows = await api(url);

    var html = '<div class="panel"><div class="panel-header"><h2>评论管理</h2>' + searchBar('comments', '搜索评论内容或作者…') + '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>作者</th><th>评论内容</th><th>动态 ID</th><th>时间</th><th>操作</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无评论数据</p></div></td></tr>';
    } else {
      rows.forEach(function (c) {
        html += '<tr>' +
          '<td><div class="cell-main">' + esc(c.authorName) + '</div><div class="cell-id">' + esc(c.authorId) + '</div></td>' +
          '<td style="max-width:300px">' + esc(c.content.slice(0, 200)) + (c.content.length > 200 ? '…' : '') + '</td>' +
          '<td><span class="cell-id">' + esc(c.postId) + '</span></td>' +
          '<td>' + esc(date(c.createdAt)) + '</td>' +
          '<td><button class="btn btn-danger btn-sm" data-comment="' + esc(c.id) + '">删除</button></td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('comments', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    bindSearch('comments', loadComments);
    bindPager('comments', rows.length, loadComments);

    document.querySelectorAll('[data-comment]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.comment;
        showConfirm('删除评论', '确认删除这条评论？', 'danger', async function () {
          await api('/api/admin/comments/' + encodeURIComponent(id), { method: 'DELETE' });
          toast('评论已删除', 'success');
          await loadComments();
        });
      };
    });
  }

  // ═════════════════════════════════════
  // 举报审核
  // ═════════════════════════════════════
  async function loadReports(seq) {
    var st = filterStatus.reports || '';
    var offset = (page.reports || 0) * pageSize;
    var url = '/api/admin/reports?limit=' + pageSize + '&offset=' + offset;
    if (st) url += '&status=' + st;
    var rows = await api(url);

    var filters = '<option value="">全部</option>' +
      '<option value="OPEN"' + (st === 'OPEN' ? ' selected' : '') + '>待处理</option>' +
      '<option value="RESOLVED"' + (st === 'RESOLVED' ? ' selected' : '') + '>已处置</option>' +
      '<option value="REJECTED"' + (st === 'REJECTED' ? ' selected' : '') + '>已驳回</option>';

    var html = '<div class="panel"><div class="panel-header"><h2>举报审核</h2>' +
      '<div class="toolbar"><select class="filter-select" id="filter-reports">' + filters + '</select></div></div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>目标</th><th>原因</th><th>状态</th><th>时间</th><th>处置</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无举报数据</p></div></td></tr>';
    } else {
      rows.forEach(function (r) {
        var statusBadge = r.status === 'OPEN' ? '<span class="badge badge-red">待处理</span>' :
          r.status === 'RESOLVED' ? '<span class="badge badge-green">已处置</span>' :
            '<span class="badge">已驳回</span>';
        var typeLabel = r.targetType === 'MESSAGE' ? '消息' : r.targetType === 'POST' ? '动态' :
          r.targetType === 'COMMENT' ? '评论' : r.targetType === 'USER' ? '用户' : r.targetType;

        var actionSelect = '<select class="filter-select" data-action-select="' + esc(r.id) + '" style="font-size:12px">' +
          '<option value="NO_ACTION">仅关闭</option>' +
          '<option value="DELETE_CONTENT">删除内容</option>' +
          '<option value="RESTRICT_MESSAGES_24H">禁发消息 24h</option>' +
          '<option value="RESTRICT_POSTS_7D">禁发动态 7d</option>' +
          '<option value="SUSPEND_24H">停用 24h</option>' +
          '</select>';

        html += '<tr>' +
          '<td><div class="cell-main">' + esc(typeLabel) + '</div><div class="cell-id">' + esc(r.targetId) + '</div></td>' +
          '<td><div class="cell-main">' + esc(reportReasonLabel(r.reason)) + '</div><div class="muted" style="font-size:12px">' + esc(r.description || '') + '</div></td>' +
          '<td>' + statusBadge + (r.actionTaken ? '<div class="muted" style="font-size:11px;margin-top:2px">' + esc(r.actionTaken) + '</div>' : '') + '</td>' +
          '<td>' + esc(date(r.createdAt)) + '</td>' +
          '<td><div class="toolbar" style="flex-direction:column;align-items:flex-start">' + actionSelect +
          '<div class="toolbar" style="margin-top:4px">' +
          (r.status === 'OPEN' ? '<button class="btn btn-primary btn-sm" data-report-action="' + esc(r.id) + '">执行</button><button class="btn btn-ghost btn-sm" data-report-reject="' + esc(r.id) + '">驳回</button>' : '—') +
          '</div></div></td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('reports', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;

    var filterEl = el('filter-reports');
    if (filterEl) filterEl.onchange = function () { filterStatus.reports = filterEl.value; page.reports = 0; loadReports(); };
    bindPager('reports', rows.length, loadReports);

    document.querySelectorAll('[data-report-action]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.reportAction;
        var select = document.querySelector('[data-action-select="' + id + '"]');
        showPrompt('处置举报', '处置备注（可留空）', '', '备注', async function (note) {
          showConfirm('执行处置', '确认执行 ' + select.value + '？', 'warn', async function () {
            await api('/api/admin/reports/' + encodeURIComponent(id) + '/action', {
              method: 'POST', body: JSON.stringify({ action: select.value, resolutionNote: note })
            });
            toast('举报处置已执行', 'success');
            await loadReports();
            loadNavBadges();
          });
          return true;
        });
      };
    });

    document.querySelectorAll('[data-report-reject]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.reportReject;
        showPrompt('驳回举报', '驳回原因', '', '原因', async function (note) {
          await api('/api/admin/reports/' + encodeURIComponent(id) + '/status', {
            method: 'PUT', body: JSON.stringify({ status: 'REJECTED', resolutionNote: note })
          });
          toast('举报已驳回', 'success');
          await loadReports();
          loadNavBadges();
          return true;
        });
      };
    });
  }

  // ═════════════════════════════════════
  // 风控规则
  // ═════════════════════════════════════
  async function loadRules(seq) {
    var rows = await api('/api/admin/moderation-rules');
    var byId = {};
    rows.forEach(function (r) { byId[r.id] = r; });

    var html = '<div class="panel">' +
      '<div class="panel-header"><h2>风控规则</h2></div>' +
      '<div class="panel-body">' +
      '<form id="rule-form" class="form-grid">' +
      '<div class="form-field"><label>规则名称</label><input id="rule-name" placeholder="如：禁止链接" required/></div>' +
      '<div class="form-field"><label>范围</label><select id="rule-scope"><option>ALL</option><option>POST</option><option>COMMENT</option></select></div>' +
      '<div class="form-field"><label>匹配类型</label><select id="rule-type"><option>KEYWORD</option><option>REGEX</option><option>URL</option><option>FREQUENCY</option></select></div>' +
      '<div class="form-field"><label>匹配表达式</label><input id="rule-pattern" placeholder="关键词或正则" required/></div>' +
      '<div class="form-field"><label>处置动作</label><select id="rule-action"><option>WARN_MOD</option><option>AUTO_HOLD</option><option>AUTO_DELETE</option><option>AUTO_RATE_LIMIT</option></select></div>' +
      '<div class="form-field"><label>优先级</label><input id="rule-priority" type="number" value="100" min="0" max="10000"/></div>' +
      '<div class="form-actions"><button id="rule-submit" class="btn btn-primary btn-sm" type="submit">新增规则</button>' +
      '<button id="rule-cancel" class="btn btn-ghost btn-sm hidden" type="button">取消编辑</button></div>' +
      '</form>' +
      '<div class="table-wrap"><table>' +
      '<thead><tr><th>名称</th><th>范围</th><th>匹配</th><th>动作</th><th>状态</th><th>操作</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="6"><div class="empty-state"><p>暂无风控规则</p></div></td></tr>';
    } else {
      rows.forEach(function (r) {
        html += '<tr>' +
          '<td><div class="cell-main">' + esc(r.name) + '</div><div class="cell-id">' + esc(r.id) + '</div></td>' +
          '<td><span class="badge">' + esc(r.scope) + '</span></td>' +
          '<td><div class="cell-main">' + esc(r.matchType) + '</div><div class="cell-id">' + esc(r.pattern || '') + '</div></td>' +
          '<td><span class="badge badge-purple">' + esc(r.action) + '</span></td>' +
          '<td>' + (r.enabled ? '<span class="badge badge-green">启用</span>' : '<span class="badge">停用</span>') + '</td>' +
          '<td><div class="toolbar">' +
          '<button class="btn btn-ghost btn-sm" data-rule-edit="' + esc(r.id) + '">编辑</button>' +
          '<button class="btn btn-ghost btn-sm" data-rule-toggle="' + esc(r.id) + '">' + (r.enabled ? '停用' : '启用') + '</button>' +
          '<button class="btn btn-danger btn-sm" data-rule-delete="' + esc(r.id) + '">删除</button>' +
          '</div></td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div></div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;

    var editing = '';
    function clearRuleForm() {
      editing = '';
      el('rule-form').reset();
      el('rule-priority').value = '100';
      el('rule-submit').textContent = '新增规则';
      el('rule-cancel').classList.add('hidden');
    }
    el('rule-cancel').onclick = clearRuleForm;

    el('rule-form').onsubmit = async function (e) {
      e.preventDefault();
      var name = el('rule-name').value;
      var scope = el('rule-scope').value;
      var matchType = el('rule-type').value;
      var pattern = el('rule-pattern').value;
      var action = el('rule-action').value;
      var priority = Number(el('rule-priority').value);
      var body = JSON.stringify({
        name: name,
        scope: scope,
        matchType: matchType,
        pattern: pattern,
        action: action,
        priority: priority
      });
      var summary = (editing ? '更新' : '创建') + '规则「' + name + '」\n范围 ' + scope +
        ' · ' + matchType + ' · 动作 ' + action +
        (action === 'AUTO_DELETE' || action === 'AUTO_RATE_LIMIT' ? '\n该动作为自动处置，请二次确认。' : '');
      showConfirm(
        editing ? '确认更新规则' : '确认创建规则',
        summary,
        action === 'AUTO_DELETE' ? 'danger' : 'warn',
        async function () {
          try {
            if (editing) {
              await api('/api/admin/moderation-rules/' + encodeURIComponent(editing), { method: 'PUT', body: body });
              toast('规则已更新', 'success');
            } else {
              await api('/api/admin/moderation-rules', { method: 'POST', body: body });
              toast('规则已创建', 'success');
            }
            await loadRules();
            loadNavBadges();
          } catch (err) { toast(err.message || '操作失败', 'error'); }
        }
      );
    };

    document.querySelectorAll('[data-rule-edit]').forEach(function (b) {
      b.onclick = function () {
        var r = byId[b.dataset.ruleEdit];
        editing = r.id;
        el('rule-name').value = r.name;
        el('rule-scope').value = r.scope;
        el('rule-type').value = r.matchType;
        el('rule-pattern').value = r.pattern || '';
        el('rule-action').value = r.action;
        el('rule-priority').value = r.priority;
        el('rule-submit').textContent = '保存修改';
        el('rule-cancel').classList.remove('hidden');
        el('rule-name').focus();
      };
    });

    document.querySelectorAll('[data-rule-toggle]').forEach(function (b) {
      b.onclick = async function () {
        var r = byId[b.dataset.ruleToggle];
        var enabling = !r.enabled;
        var title = enabling ? '确认启用规则' : '确认停用规则';
        var body = (enabling ? '启用' : '停用') + '「' + r.name + '」（' + r.action + '）？' +
          (enabling && (r.action === 'AUTO_DELETE' || r.action === 'AUTO_RATE_LIMIT')
            ? '\n启用后将自动处置匹配内容，请确认已评估误伤。' : '');
        showConfirm(title, body, enabling ? 'warn' : 'info', async function () {
          try {
            await api('/api/admin/moderation-rules/' + encodeURIComponent(r.id), {
              method: 'PUT', body: JSON.stringify({ enabled: enabling })
            });
            toast(enabling ? '规则已启用' : '规则已停用', 'success');
            await loadRules();
            loadNavBadges();
          } catch (e) { toast(e.message || '操作失败', 'error'); }
        });
      };
    });

    document.querySelectorAll('[data-rule-delete]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.ruleDelete;
        showConfirm('删除规则', '确认删除风控规则 ' + id + '？', 'danger', async function () {
          await api('/api/admin/moderation-rules/' + encodeURIComponent(id), { method: 'DELETE' });
          toast('规则已删除', 'success');
          await loadRules();
          loadNavBadges();
        });
      };
    });
  }

  // ═════════════════════════════════════
  // 风控事件
  // ═════════════════════════════════════
  async function loadRiskEvents(seq) {
    var pendingOnly = filterStatus['risk-events'] === 'true';
    var offset = (page['risk-events'] || 0) * pageSize;
    var url = '/api/admin/risk-events?limit=' + pageSize + '&offset=' + offset;
    if (pendingOnly) url += '&pending=true';
    var rows = await api(url);

    var filters = '<option value="">全部事件</option>' +
      '<option value="true"' + (pendingOnly ? ' selected' : '') + '>仅待处理</option>';

    var html = '<div class="panel"><div class="panel-header"><h2>风控事件</h2>' +
      '<div class="toolbar"><select class="filter-select" id="filter-risk-events">' + filters + '</select></div></div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>用户</th><th>来源</th><th>动作</th><th>匹配</th><th>状态</th><th>时间</th><th>操作</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="7"><div class="empty-state"><p>暂无风控事件</p></div></td></tr>';
    } else {
      rows.forEach(function (r) {
        var statusBadge = r.needsReview ? '<span class="badge badge-red">待处理</span>' : '<span class="badge badge-green">已处理</span>';
        var sourceBadge = r.source === 'POST' ? '<span class="badge badge-blue">动态</span>' :
          r.source === 'COMMENT' ? '<span class="badge badge-purple">评论</span>' :
            r.source === 'MESSAGE' ? '<span class="badge">消息</span>' : '<span class="badge">' + esc(r.source) + '</span>';
        html += '<tr>' +
          '<td><span class="cell-id">' + esc(r.userId) + '</span></td>' +
          '<td>' + sourceBadge + '</td>' +
          '<td><span class="badge badge-orange">' + esc(r.action) + '</span></td>' +
          '<td style="max-width:200px">' + esc(r.matched || '—') + '</td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' + esc(date(r.createdAt)) + '</td>' +
          '<td>' + (r.needsReview ? '<button class="btn btn-primary btn-sm" data-risk-resolve="' + esc(r.id) + '">标记已处理</button>' : '—') + '</td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('risk-events', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;

    var filterEl = el('filter-risk-events');
    if (filterEl) filterEl.onchange = function () { filterStatus['risk-events'] = filterEl.value; page['risk-events'] = 0; loadRiskEvents(); };
    bindPager('risk-events', rows.length, loadRiskEvents);

    document.querySelectorAll('[data-risk-resolve]').forEach(function (b) {
      b.onclick = async function () {
        var id = b.dataset.riskResolve;
        try {
          await api('/api/admin/risk-events/' + encodeURIComponent(id) + '/resolve', { method: 'PUT' });
          toast('事件已标记为已处理', 'success');
          await loadRiskEvents();
          loadNavBadges();
        } catch (e) { toast(e.message || '操作失败', 'error'); }
      };
    });
  }

  // ═════════════════════════════════════
  // AI 审计
  // ═════════════════════════════════════
  async function loadAiUsage(seq) {
    var offset = (page['ai-usage'] || 0) * pageSize;
    var q = searchQuery['ai-usage'] || '';
    var url = '/api/admin/ai-usage?limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&userId=' + encodeURIComponent(q);
    var rows = await api(url);

    var html = '<div class="panel"><div class="panel-header"><div>' +
      '<h2>AI 使用审计</h2>' +
      '<p class="panel-sub">仅元数据（模型 / 估算 tokens / 延迟 / 状态），不展示 prompt 或聊天正文</p></div></div>' +
      '<div class="panel-body">' +
      '<div class="toolbar" style="padding:12px 18px"><input id="ai-usage-search" class="search-input" placeholder="按用户 ID 筛选" value="' + esc(q) + '"/>' +
      '<button class="btn btn-secondary" id="ai-usage-search-btn">筛选</button></div>' +
      '<div class="table-wrap"><table>' +
      '<thead><tr><th>用户</th><th>功能</th><th>模型</th><th>状态</th><th>输入字符</th><th>估算 tokens</th><th>上下文消息</th><th>耗时</th><th>时间</th><th>错误</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="10"><div class="empty-state"><p>暂无 AI 调用记录</p></div></td></tr>';
    } else {
      rows.forEach(function (r) {
        var ok = r.status === 'success' || r.status === 'SUCCESS' || r.status === 'OK';
        var statusBadge = ok ?
          '<span class="badge badge-green">' + esc(r.status) + '</span>' :
          '<span class="badge badge-red">' + esc(r.status) + '</span>';
        var featureBadge = '<span class="badge badge-purple">' + esc(r.feature) + '</span>';
        var tokens = (typeof r.estimatedTokens === 'number') ? r.estimatedTokens : Math.ceil((r.inputChars || 0) / 4);
        html += '<tr>' +
          '<td><span class="cell-id">' + esc(r.userId) + '</span></td>' +
          '<td>' + featureBadge + '</td>' +
          '<td>' + esc(r.model || '—') + '</td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' + esc(r.inputChars) + '</td>' +
          '<td>' + esc(tokens) + '</td>' +
          '<td>' + esc(r.contextMessages) + '</td>' +
          '<td>' + (r.durationMs ? esc(r.durationMs) + 'ms' : '—') + '</td>' +
          '<td>' + esc(date(r.createdAt)) + '</td>' +
          '<td style="max-width:200px">' + esc(r.error || '—') + '</td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('ai-usage', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    bindPager('ai-usage', rows.length, loadAiUsage);
    var searchBtn = el('ai-usage-search-btn');
    var searchInput = el('ai-usage-search');
    if (searchBtn && searchInput) {
      searchBtn.onclick = function () {
        searchQuery['ai-usage'] = (searchInput.value || '').trim();
        page['ai-usage'] = 0;
        loadAiUsage();
      };
    }
  }

  // ═════════════════════════════════════
  // 推送令牌
  // ═════════════════════════════════════
  async function loadPushTokens(seq) {
    var q = searchQuery['push-tokens'] || '';
    var offset = (page['push-tokens'] || 0) * pageSize;
    var url = '/api/admin/push-tokens?limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&q=' + encodeURIComponent(q);
    var rows = await api(url);

    var html = '<div class="panel"><div class="panel-header"><h2>推送令牌管理</h2>' + searchBar('push-tokens', '按用户 ID 搜索…') + '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>用户 ID</th><th>设备 ID</th><th>平台</th><th>时区偏移</th><th>更新时间</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无推送令牌</p></div></td></tr>';
    } else {
      rows.forEach(function (r) {
        var platformBadge = r.platform === 'ANDROID' ? '<span class="badge badge-green">Android</span>' :
          r.platform === 'IOS' ? '<span class="badge badge-blue">iOS</span>' : '<span class="badge">' + esc(r.platform) + '</span>';
        var tz = r.timezoneOffsetMinutes !== 0 ? 'UTC' + (r.timezoneOffsetMinutes > 0 ? '+' : '') + (r.timezoneOffsetMinutes / 60) : 'UTC';
        html += '<tr>' +
          '<td><span class="cell-id">' + esc(r.userId) + '</span></td>' +
          '<td><span class="cell-id">' + esc(r.deviceId) + '</span></td>' +
          '<td>' + platformBadge + '</td>' +
          '<td>' + esc(tz) + '</td>' +
          '<td>' + esc(date(r.updatedAt)) + '</td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('push-tokens', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    bindSearch('push-tokens', loadPushTokens);
    bindPager('push-tokens', rows.length, loadPushTokens);
  }

  // ═════════════════════════════════════
  // 操作审计
  // ═════════════════════════════════════
  async function loadAudit(seq) {
    var offset = (page.audit || 0) * pageSize;
    var actionF = filterStatus.audit || '';
    var q = searchQuery.audit || '';
    var url = '/api/admin/audit-logs?limit=' + pageSize + '&offset=' + offset;
    if (actionF) url += '&action=' + encodeURIComponent(actionF);
    if (q) url += '&q=' + encodeURIComponent(q);
    var rows = await api(url);

    var actionOptions = Object.keys(auditLabels).map(function (k) {
      return '<option value="' + k + '"' + (actionF === k ? ' selected' : '') + '>' + esc(auditLabels[k]) + '</option>';
    }).join('');

    var html = '<div class="panel"><div class="panel-header"><h2>操作审计</h2>' +
      '<div class="toolbar">' +
      '<input class="search-input" id="search-audit" value="' + esc(q) + '" placeholder="搜索操作者、目标、动作或详情…"/>' +
      '<select class="filter-select" id="filter-audit"><option value="">全部动作</option>' + actionOptions + '</select>' +
      '<button class="btn btn-primary btn-sm" id="search-btn-audit">搜索</button>' +
      '<button class="btn btn-ghost btn-sm" id="audit-export">导出 CSV</button>' +
      '</div></div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>时间</th><th>操作者</th><th>目标用户</th><th>动作</th><th>详情</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无审计日志</p></div></td></tr>';
    } else {
      rows.forEach(function (r) {
        html += '<tr>' +
          '<td>' + esc(date(r.createdAt)) + '</td>' +
          '<td><span class="cell-id">' + esc(r.actorId || '—') + '</span></td>' +
          '<td><span class="cell-id">' + esc(r.targetUserId || '—') + '</span></td>' +
          '<td><span class="badge badge-purple">' + esc(auditLabel(r.action)) + '</span></td>' +
          '<td style="max-width:300px" class="mono">' + esc(r.detail || '—') + '</td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('audit', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;

    bindSearch('audit', loadAudit);
    bindPager('audit', rows.length, loadAudit);

    el('audit-export').onclick = async function () {
      try {
        var exportUrl = '/api/admin/audit-logs/export';
        var r = await fetch(exportUrl, { headers: { Authorization: 'Bearer ' + token } });
        if (!r.ok) { var d = await r.json(); toast(d.error || '导出失败', 'error'); return; }
        var blob = await r.blob();
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = 'maodouchat-admin-audit.csv';
        a.click();
        setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
        toast('审计日志已导出', 'success');
      } catch (e) { toast('导出失败: ' + e.message, 'error'); }
    };
  }

  // ═════════════════════════════════════
  // 排行榜
  // ═════════════════════════════════════
  async function loadRanking(seq) {
    var r = await api('/api/admin/ranking?limit=20');
    var rt = null;
    try { rt = await api('/api/admin/rich-trends'); } catch (e) { /* 可选 */ }

    function rankTable(title, entries, valueFmt) {
      var rows = '';
      if (!entries || entries.length === 0) {
        rows = '<tr><td colspan="4"><div class="empty-state"><p>暂无数据</p></div></td></tr>';
      } else {
        entries.forEach(function (e, i) {
          var medal = i === 0 ? '🥇' : i === 1 ? '🥈' : i === 2 ? '🥉' : (i + 1);
          rows += '<tr>' +
            '<td style="text-align:center;font-weight:600">' + medal + '</td>' +
            '<td><div class="user-cell">' +
              (e.avatar ? '<img class="avatar-sm" src="' + esc(e.avatar) + '"/>' : '<div class="avatar-sm avatar-placeholder"></div>') +
              '<span>' + esc(e.userName) + '</span>' +
            '</div></td>' +
            '<td><span class="cell-id">' + esc(e.userId) + '</span></td>' +
            '<td style="text-align:right;font-weight:600">' + (valueFmt ? valueFmt(e.value) : esc(e.value)) + '</td>' +
            '</tr>';
        });
      }
      return '<div class="panel"><div class="panel-header"><h2>' + esc(title) + '</h2></div>' +
        '<div class="panel-body"><div class="table-wrap"><table>' +
        '<thead><tr><th style="width:48px">#</th><th>名称</th><th>ID</th><th style="text-align:right">数值</th></tr></thead><tbody>' +
        rows + '</tbody></table></div></div></div>';
    }

    var grid = '<div class="stats-grid">' +
      statCard('messages', '消息排行', r.topMessagers.length, 'Top 20 发消息用户', 'blue') +
      statCard('posts', '动态排行', r.topPosters.length, 'Top 20 发动态用户', 'green') +
      statCard('storage', '存储排行', r.topStorageUsers.length, 'Top 20 存储用户', 'orange') +
      statCard('chats', '群活跃', r.mostActiveGroups.length, 'Top 20 活跃群', '') +
      '</div>';

    var charts = '';
    if (rt) {
      charts = '<div class="chart-row">' +
        chartCard('近 7 天活跃用户', rt.activeUsers, '#6366f1') +
        chartCard('近 7 天消息量', rt.newMessages, '#10b981') +
        chartCard('近 7 天新增附件', rt.newAttachments, '#f59e0b') +
        '</div><div class="chart-row">' +
        chartCard('近 7 天新增举报', rt.newReports, '#ef4444') +
        chartCard('近 7 天 AI 调用', rt.newAiCalls, '#8b5cf6') +
        '</div>';
    }

    var tables = '<div class="two-col">' +
      rankTable('消息发送排行', r.topMessagers) +
      rankTable('动态发布排行', r.topPosters) +
      '</div><div class="two-col">' +
      rankTable('存储用量排行', r.topStorageUsers, fmtBytes) +
      rankTable('群活跃排行', r.mostActiveGroups) +
      '</div>';

    if (staleTab(seq)) return;
    el('content').innerHTML = grid + charts + tables;
  }

  // ═════════════════════════════════════
  // 在线用户
  // ═════════════════════════════════════
  async function loadOnline(seq) {
    var users = await api('/api/admin/online?limit=500');
    var badge = el('nav-online-badge');
    if (badge) {
      if (users.length > 0) { badge.textContent = users.length; badge.classList.remove('hidden'); }
      else badge.classList.add('hidden');
    }

    var html = '<div class="panel"><div class="panel-header"><h2>在线用户</h2>' +
      '<div class="toolbar"><span class="muted">' + users.length + ' 人在线</span>' +
      '<button class="btn btn-ghost btn-sm" id="online-refresh">刷新</button></div></div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>用户</th><th>ID</th><th>邮箱</th><th>最后活跃</th><th>角色</th></tr></thead><tbody>';

    if (users.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>当前无在线用户</p></div></td></tr>';
    } else {
      users.forEach(function (u) {
        html += '<tr>' +
          '<td><div class="user-cell">' +
            (u.avatar ? '<img class="avatar-sm" src="' + esc(u.avatar) + '"/>' : '<div class="avatar-sm avatar-placeholder"></div>') +
            '<span>' + esc(u.name) + '</span>' +
            '<span class="online-dot"></span>' +
          '</div></td>' +
          '<td><span class="cell-id">' + esc(u.id) + '</span></td>' +
          '<td>' + esc(u.email) + '</td>' +
          '<td>' + esc(timeAgo(u.lastSeen)) + '</td>' +
          '<td>' + (u.isModerator ? '<span class="badge badge-purple">审核员</span>' : '<span class="muted">普通</span>') + '</td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div></div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    var refBtn = el('online-refresh');
    if (refBtn) refBtn.onclick = function () { loadOnline(); };
  }

  // ═════════════════════════════════════
  // 存储用量
  // ═════════════════════════════════════
  async function loadStorage(seq) {
    var s = await api('/api/admin/storage');

    var statsHtml = '<div class="stats-grid">' +
      statCard('storage', '总存储', fmtBytes(s.totalBytes), s.totalFiles + ' 个文件', 'blue') +
      statCard('storage', '用户配额', fmtBytes(s.quotaPerUserBytes), '每用户上限', '') +
      statCard('users', '接近配额', s.usersNearQuota, '使用 ≥80% 配额', s.usersNearQuota > 0 ? 'orange' : 'green') +
      '</div>';

    // 分类饼图/柱状图
    var maxBytes = Math.max.apply(null, (s.byCategory || []).map(function (c) { return c.totalBytes; })) || 1;
    var catRows = (s.byCategory || []).map(function (c) {
      var pct = Math.round(c.totalBytes / maxBytes * 100);
      var colors = { image: '#6366f1', video: '#10b981', file: '#f59e0b', voice: '#ec4899', other: '#8b5cf6', orphan: '#94a3b8' };
      var col = colors[c.category] || '#94a3b8';
      return '<tr>' +
        '<td><span class="badge" style="background:' + col + '20;color:' + col + '">' + esc(c.category) + '</span></td>' +
        '<td style="text-align:right">' + c.fileCount + '</td>' +
        '<td style="text-align:right;font-weight:600">' + fmtBytes(c.totalBytes) + '</td>' +
        '<td><div class="mem-bar"><div class="mem-bar-fill" style="width:' + pct + '%;background:' + col + '"></div></div></td>' +
        '</tr>';
    }).join('');

    var catHtml = '<div class="panel"><div class="panel-header"><h2>存储分类明细</h2></div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>类别</th><th style="text-align:right">文件数</th><th style="text-align:right">占用</th><th style="width:30%">占比</th></tr></thead><tbody>' +
      (catRows || '<tr><td colspan="4"><div class="empty-state"><p>暂无附件</p></div></td></tr>') +
      '</tbody></table></div></div></div>';

    if (staleTab(seq)) return;
    el('content').innerHTML = statsHtml + catHtml;
  }

  // ═════════════════════════════════════
  // Modal 对话框系统
  // ═════════════════════════════════════
  var modalCallback = null;

  function showConfirm(title, body, type, callback) {
    modalCallback = callback;
    el('modal-title').textContent = title;
    el('modal-body').textContent = body;
    el('modal-input-wrap').classList.add('hidden');
    el('modal-select-wrap').classList.add('hidden');

    var iconWrap = el('modal-icon-wrap');
    iconWrap.className = 'modal-icon ' + (type || 'info');
    var iconPaths = {
      warn: '<svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 1L1 14h14L8 1zm0 4v4H7V5h1zm0 6v1H7v-1h1z"/></svg>',
      danger: '<svg viewBox="0 0 16 16" fill="currentColor"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="2"/></svg>',
      info: '<svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 1a7 7 0 100 14 7 7 0 000-14zm0 4a1 1 0 110 2 1 1 0 010-2zm1 4v3H7V9h2z"/></svg>'
    };
    iconWrap.innerHTML = iconPaths[type] || iconPaths.info;

    el('modal-confirm').textContent = '确认';
    el('modal-confirm').className = 'btn ' + (type === 'danger' ? 'btn-danger' : 'btn-primary');
    el('modal-overlay').classList.remove('hidden');
  }

  function showPrompt(title, body, defaultVal, placeholder, callback) {
    modalCallback = callback;
    el('modal-title').textContent = title;
    el('modal-body').textContent = body;
    el('modal-input-wrap').classList.remove('hidden');
    el('modal-select-wrap').classList.add('hidden');
    var input = el('modal-input');
    input.value = defaultVal || '';
    input.placeholder = placeholder || '';
    el('modal-input-hint').textContent = '';
    el('modal-confirm').textContent = '确认';
    el('modal-confirm').className = 'btn btn-primary';

    var iconWrap = el('modal-icon-wrap');
    iconWrap.className = 'modal-icon info';
    iconWrap.innerHTML = '<svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 1a7 7 0 100 14 7 7 0 000-14zm0 4a1 1 0 110 2 1 1 0 010-2zm1 4v3H7V9h2z"/></svg>';

    el('modal-overlay').classList.remove('hidden');
    setTimeout(function () { input.focus(); input.select(); }, 100);
  }

  /** options: [{value, label}] */
  function showSelect(title, body, options, defaultValue, callback) {
    modalCallback = callback;
    el('modal-title').textContent = title;
    el('modal-body').textContent = body;
    el('modal-input-wrap').classList.add('hidden');
    el('modal-select-wrap').classList.remove('hidden');
    var select = el('modal-select');
    select.innerHTML = (options || []).map(function (opt) {
      return '<option value="' + esc(opt.value) + '"' +
        (String(opt.value) === String(defaultValue) ? ' selected' : '') + '>' +
        esc(opt.label) + '</option>';
    }).join('');
    el('modal-confirm').textContent = '下一步';
    el('modal-confirm').className = 'btn btn-primary';
    var iconWrap = el('modal-icon-wrap');
    iconWrap.className = 'modal-icon warn';
    iconWrap.innerHTML = '<svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 1a7 7 0 100 14 7 7 0 000-14zm0 3a1 1 0 011 1v4a1 1 0 11-2 0V5a1 1 0 011-1zm0 8a1.25 1.25 0 110-2.5A1.25 1.25 0 018 12z"/></svg>';
    el('modal-overlay').classList.remove('hidden');
    setTimeout(function () { select.focus(); }, 100);
  }

  el('modal-cancel').onclick = function () {
    el('modal-overlay').classList.add('hidden');
    modalCallback = null;
  };
  el('modal-overlay').onclick = function (e) {
    if (e.target === el('modal-overlay')) {
      el('modal-overlay').classList.add('hidden');
      modalCallback = null;
    }
  };
  el('modal-confirm').onclick = async function () {
    if (!modalCallback) { el('modal-overlay').classList.add('hidden'); return; }
    // 8.47 修复：in-flight 锁——回调执行期间禁用按钮，快速双击/回车不会重复提交
    //（封禁/删除/处置等多次连发会重复扣减/重复审计）
    if (el('modal-confirm').disabled) return;
    el('modal-confirm').disabled = true;
    var input = el('modal-input');
    var select = el('modal-select');
    var cb = modalCallback;
    try {
      if (!el('modal-input-wrap').classList.contains('hidden')) {
        var val = input.value;
        var result = await cb(val);
        if (result === false) { el('modal-confirm').disabled = false; return; } // callback can return false to keep modal open
      } else if (!el('modal-select-wrap').classList.contains('hidden')) {
        var selResult = await cb(select.value);
        if (selResult === false) { el('modal-confirm').disabled = false; return; }
      } else {
        await cb();
      }
    } catch (e) {
      el('modal-confirm').disabled = false;
      toast(e.message || '操作失败', 'error');
      return;
    }
    el('modal-confirm').disabled = false;
    // Only close if the callback didn't open a new modal (e.g. showConfirm inside showPrompt)
    if (modalCallback === cb) {
      el('modal-overlay').classList.add('hidden');
      modalCallback = null;
    }
  };
  // Enter key on modal input
  el('modal-input').onkeydown = function (e) { if (e.key === 'Enter') { e.preventDefault(); el('modal-confirm').click(); } };

  // ═════════════════════════════════════
  // Drawer 关闭
  // ═════════════════════════════════════
  el('drawer-close').onclick = function () { el('drawer-overlay').classList.add('hidden'); };
  el('drawer-overlay').onclick = function (e) {
    if (e.target === el('drawer-overlay')) el('drawer-overlay').classList.add('hidden');
  };

  // ═════════════════════════════════════
  // ESC 关闭弹窗
  // ═════════════════════════════════════
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
      el('modal-overlay').classList.add('hidden');
      el('drawer-overlay').classList.add('hidden');
      el('sidebar').classList.remove('open');
      el('sidebar-overlay').classList.add('hidden');
    }
  });




  
  async function loadSettings(seq) {
    var n = el('page-title'); if (n) n.textContent = 'System settings';
    var data = await api('/api/admin/settings');
    var s = data.settings || {};
    var def = data.defaults || {};
    function row(key, label, type) {
      var val = (s[key] != null ? s[key] : def[key] || '');
      if (type === 'bool') {
        var on = String(val).toLowerCase() === 'true' || val === '1' || val === true;
        return '<label class="field" style="display:flex;align-items:center;gap:8px;margin:8px 0">' +
          '<input type="checkbox" data-setting="' + esc(key) + '" ' + (on ? 'checked' : '') + '/>' +
          '<span><strong>' + esc(label) + '</strong> <code>' + esc(key) + '</code></span></label>';
      }
      if (type === 'textarea') {
        return '<label class="field" style="display:block;margin:10px 0"><div><strong>' + esc(label) +
          '</strong> <code>' + esc(key) + '</code></div>' +
          '<textarea data-setting="' + esc(key) + '" rows="2" style="width:100%;margin-top:4px">' + esc(val) + '</textarea></label>';
      }
      return '<label class="field" style="display:block;margin:10px 0"><div><strong>' + esc(label) +
        '</strong> <code>' + esc(key) + '</code></div>' +
        '<input data-setting="' + esc(key) + '" value="' + esc(val) + '" style="width:100%;margin-top:4px"/></label>';
    }
    var html = '<div class="panel"><div class="panel-header"><h2>Runtime settings</h2>' +
      '<button class="btn btn-primary" id="settings-save">Save</button></div><div class="panel-body">' +
      '<p style="color:var(--text-muted);font-size:13px">Overrides env defaults without restart. Cached ~5s on server.</p>' +
      row('maintenance_mode', 'Maintenance mode', 'bool') +
      row('maintenance_message', 'Maintenance message', 'textarea') +
      row('allow_registration', 'Allow registration', 'bool') +
      row('invite_only_hint', 'Registration closed hint', 'textarea') +
      row('global_banner', 'Global banner', 'textarea') +
      row('max_group_size', 'Max group size', 'text') +
      row('sealed_sender_enabled', 'Sealed sender certificates', 'bool') +
      row('allow_bots', 'Allow bot platform', 'bool') +
      row('force_e2ee_banner', 'Force E2EE banner text', 'textarea') +
      row('max_message_per_min', 'Max messages / user / min', 'text') +
      row('ip_blocklist', 'IP blocklist (comma/newline)', 'textarea') +
      row('ai_enabled', 'Cloud AI features', 'bool') +
      row('public_announcement', 'Public announcement', 'textarea') +
      row('pqxdh_preview', 'PQXDH preview flag (clients)', 'bool') +
      row('min_app_version', 'Min app version code', 'text') +
      row('secret_chat_required', 'Require secret chat for 1:1 (client banner)', 'bool') +
      row('capture_alert_enabled', 'Peer capture alerts', 'bool') +
      row('max_bots_per_user', 'Max bots per user', 'text') +
      row('media_upload_enabled', 'Allow media uploads', 'bool') +
      row('group_play_enabled', 'Allow group play features', 'bool') +
      row('link_preview_enabled', 'Link previews (clients)', 'bool') +
      row('voice_messages_enabled', 'Voice messages (clients)', 'bool') +
      row('reactions_enabled', 'Message reactions (clients)', 'bool') +
      row('stickers_enabled', 'Stickers (clients)', 'bool') +
      row('silent_send_enabled', 'Silent send (clients)', 'bool') +
      row('calls_enabled', 'Voice/video calls (clients)', 'bool') +
      row('scheduled_messages_enabled', 'Scheduled messages (clients)', 'bool') +
      row('view_once_enabled', 'View-once media (clients)', 'bool') +
      row('live_location_enabled', 'Live location (clients)', 'bool') +
      row('markdown_enabled', 'Markdown rendering (clients/bots)', 'bool') +
      row('typing_indicators_enabled', 'Typing indicators (clients)', 'bool') +
      row('read_receipts_enabled', 'Read receipts (clients)', 'bool') +
      row('presence_enabled', 'Online presence (clients)', 'bool') +
      row('message_starring_enabled', 'Message starring (clients)', 'bool') +
      row('chat_export_enabled', 'Chat export (clients)', 'bool') +
      row('message_forwarding_enabled', 'Message forwarding (clients)', 'bool') +
      row('global_search_enabled', 'Global search (clients)', 'bool') +
      row('friend_requests_enabled', 'Friend requests (clients)', 'bool') +
      row('chat_folders_enabled', 'Chat folders (clients)', 'bool') +
      row('posts_enabled', 'Moments / posts (clients)', 'bool') +
      row('block_report_enabled', 'Block & report (clients)', 'bool') +
      row('chat_archive_enabled', 'Chat archive (clients)', 'bool') +
      row('nearby_enabled', 'Nearby people (clients)', 'bool') +
      row('chat_pin_enabled', 'Chat pin (clients)', 'bool') +
      row('marked_unread_enabled', 'Marked unread (clients)', 'bool') +
      row('chat_mute_enabled', 'Chat mute (clients)', 'bool') +
      row('disappearing_messages_enabled', 'Disappearing messages (clients)', 'bool') +
      row('chat_lock_enabled', 'Chat lock (clients)', 'bool') +
      row('message_edit_enabled', 'Message edit (clients)', 'bool') +
      row('message_pin_enabled', 'Message pin (clients)', 'bool') +
      row('message_revoke_enabled', 'Message revoke/delete (clients)', 'bool') +
      row('polls_enabled', 'Polls (clients)', 'bool') +
      row('app_lock_enabled', 'App lock (clients)', 'bool') +
      row('chat_drafts_enabled', 'Chat drafts (clients)', 'bool') +
      row('ai_translate_enabled', 'AI translate (clients)', 'bool') +
      row('group_invites_enabled', 'Group invites (clients)', 'bool') +
      row('mentions_enabled', 'Mentions (clients)', 'bool') +
      row('nudge_enabled', 'Nudge / 拍一拍 (clients)', 'bool') +
      row('safety_code_enabled', 'Safety code (clients)', 'bool') +
      row('qr_code_enabled', 'QR code (clients)', 'bool') +
      row('contact_card_enabled', 'Contact cards (clients)', 'bool') +
      row('spoiler_media_enabled', 'Spoiler media (clients)', 'bool') +
      row('auto_download_enabled', 'Auto-download (clients)', 'bool') +
      row('static_location_enabled', 'Static location (clients)', 'bool') +
      row('file_share_enabled', 'File share (clients)', 'bool') +
      row('secret_chat_enabled', 'Secret chat (clients)', 'bool') +
      row('screen_secure_runtime_enabled', 'Screen secure runtime (clients)', 'bool') +
      row('image_send_enabled', 'Image send (clients)', 'bool') +
      row('video_send_enabled', 'Video send (clients)', 'bool') +
      row('ai_summary_enabled', 'AI summary (clients)', 'bool') +
      row('ai_rewrite_enabled', 'AI rewrite (clients)', 'bool') +
      row('ai_suggest_replies_enabled', 'AI suggest replies (clients)', 'bool') +
      row('ai_transcribe_enabled', 'AI transcribe (clients)', 'bool') +
      row('ai_analyze_image_enabled', 'AI analyze image (clients)', 'bool') +
      row('ai_group_assistant_enabled', 'AI group assistant (clients)', 'bool') +
      row('ai_analyze_file_enabled', 'AI analyze file (clients)', 'bool') +
      row('ai_semantic_search_enabled', 'AI semantic search (clients)', 'bool') +
      row('gif_send_enabled', 'GIF send (clients)', 'bool') +
      row('blind_watermark_enabled', 'Blind watermark (clients)', 'bool') +
      row('voice_call_enabled', 'Voice call fine gate (clients)', 'bool') +
      row('video_call_enabled', 'Video call fine gate (clients)', 'bool') +
      row('chat_wallpaper_enabled', 'Chat wallpaper (clients)', 'bool') +
      row('chat_font_scale_enabled', 'Chat font scale (clients)', 'bool') +
      row('unread_priority_enabled', 'Unread priority (clients)', 'bool') +
      row('ringtone_enabled', 'Ringtone settings (clients)', 'bool') +
      row('notification_sound_enabled', 'Notification sound (clients)', 'bool') +
      row('notification_preview_enabled', 'Notification preview (clients)', 'bool') +
      row('push_notifications_enabled', 'Push notifications master (clients)', 'bool') +
      row('task_reminders_enabled', 'Task reminders (clients)', 'bool') +
      row('dnd_enabled', 'Do-not-disturb windows (clients)', 'bool') +
      row('offline_ai_enabled', 'Offline AI fallbacks (clients)', 'bool') +
      row('in_app_sounds_enabled', 'In-app sounds (clients)', 'bool') +
      row('haptics_enabled', 'Haptics feedback (clients)', 'bool') +
      row('chat_animations_enabled', 'Chat animations (clients)', 'bool') +
      row('nav_transitions_enabled', 'Nav transitions (clients)', 'bool') +
      row('screenshot_detect_enabled', 'Screenshot detect (clients)', 'bool') +
      row('recents_exclusion_enabled', 'Recents exclusion (clients)', 'bool') +
      row('secret_copy_block_enabled', 'Secret chat copy block (clients)', 'bool') +
      row('secret_media_export_block_enabled', 'Secret media export block (clients)', 'bool') +
      row('secret_forward_block_enabled', 'Secret chat forward block (clients)', 'bool') +
      row('secret_chat_export_block_enabled', 'Secret chat history export block (clients)', 'bool') +
      row('visible_watermark_enabled', 'Visible secret surface watermark (clients)', 'bool') +
      row('secret_auto_disappear_enabled', 'Secret chat auto 24h disappear (clients)', 'bool') +
      row('secret_link_preview_block_enabled', 'Secret chat link preview block (clients)', 'bool') +
      row('secret_external_link_block_enabled', 'Secret chat external link open block (clients)', 'bool') +
      row('secret_notif_preview_block_enabled', 'Secret chat notification preview block (clients)', 'bool') +
      row('secret_list_preview_block_enabled', 'Secret chat list preview block (clients)', 'bool') +
      row('secret_reaction_block_enabled', 'Secret chat reaction block (clients)', 'bool') +
      row('secret_star_block_enabled', 'Secret chat star/favorite block (clients)', 'bool') +
      row('secret_typing_block_enabled', 'Secret chat typing indicator block (clients)', 'bool') +
      row('secret_read_receipt_block_enabled', 'Secret chat read receipt block (clients)', 'bool') +
      row('secret_presence_block_enabled', 'Secret chat presence/online block (clients)', 'bool') +
      row('secret_last_seen_block_enabled', 'Secret chat last seen block (clients)', 'bool') +
      '<div style="margin-top:12px;font-size:12px;color:var(--text-muted)">Env allowRegistration: ' +
      esc(String(data.envAllowRegistration)) + '</div></div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    el('settings-save').onclick = async function () {
      var updates = {};
      document.querySelectorAll('[data-setting]').forEach(function (node) {
        var key = node.getAttribute('data-setting');
        if (node.type === 'checkbox') updates[key] = node.checked ? 'true' : 'false';
        else updates[key] = node.value;
      });
      try {
        await api('/api/admin/settings', { method: 'PUT', body: JSON.stringify({ settings: updates }) });
        toast('Settings saved', 'success');
        await loadSettings();
      } catch (e) {
        toast('Save failed: ' + (e && e.message ? e.message : e), 'error');
      }
    };
  }

async function loadWatermark() {
    var n = el('page-title'); if (n) n.textContent = '\u6c34\u5370\u53d6\u8bc1';
    el('content').innerHTML =
      '<div class="card">' +
      '<h3>\u5bc6\u804a\u76f2\u6c34\u5370\u63d0\u53d6</h3>' +
      '<p class="muted">Upload a suspected secret-chat screenshot. Server uses the same DCT-QIM algorithm as the app (48-bit user/chat/device hash). On-screen tiles also include user id + timestamp.</p>' +
      '<div class="row" style="gap:12px;flex-wrap:wrap;margin:12px 0">' +
      '<input type="file" id="wm-file" accept="image/*"/>' +
      '<button class="btn btn-primary" id="wm-extract">\u63d0\u53d6\u6c34\u5370</button>' +
      '<button class="btn btn-ghost" id="wm-selftest">Self-test</button>' +
      '</div>' +
      '<div id="wm-preview" class="muted">No image</div>' +
      '<pre id="wm-result" class="code-block" style="margin-top:12px;white-space:pre-wrap"></pre>' +
      '</div>';
    var fileInput = el('wm-file');
    var result = el('wm-result');
    var preview = el('wm-preview');
    fileInput.onchange = function () {
      var f = fileInput.files && fileInput.files[0];
      preview.textContent = f ? (f.name + ' · ' + fmtBytes(f.size)) : 'No image';
    };
    el('wm-extract').onclick = async function () {
      var f = fileInput.files && fileInput.files[0];
      if (!f) { toast('Pick an image first', 'error'); return; }
      result.textContent = 'Extracting...';
      try {
        var b64 = await fileToBase64(f);
        var data = await api('/api/admin/watermark/extract', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ imageBase64: b64 })
        });
        result.textContent = JSON.stringify(data, null, 2);
        toast(data.found ? 'Watermark found' : 'No watermark');
      } catch (e) {
        result.textContent = String(e.message || e);
        toast('Extract failed', 'error');
      }
    };
    el('wm-selftest').onclick = async function () {
      result.textContent = 'Self-testing...';
      try {
        var data = await api('/api/admin/watermark/self-test');
        result.textContent = JSON.stringify({
          found: data.found,
          payloadHex: data.payloadHex,
          message: data.message,
          sampleLen: (data.samplePngBase64 || '').length
        }, null, 2);
        toast(data.found ? 'Self-test OK' : 'Self-test failed', data.found ? undefined : 'error');
      } catch (e) {
        result.textContent = String(e.message || e);
        toast('Self-test failed', 'error');
      }
    };
  }

  async function loadBots(seq) {
    var n = el('page-title'); if (n) n.textContent = 'Bots';
    el('content').innerHTML = '<div class="loading-state"><div class="spinner"></div><span>Loading...</span></div>';
    try {
      var rows = await api('/api/admin/bots?limit=100');
      var list = Array.isArray(rows) ? rows : (rows.items || rows.bots || []);
      if (!list.length) {
        if (staleTab(seq)) return;
        el('content').innerHTML = '<div class="empty-state"><p>No bots yet. Developers can create via POST /api/bots</p></div>';
        return;
      }
      var html = '<div class="card"><div class="row" style="justify-content:space-between;align-items:center;margin-bottom:8px"><h3 style="margin:0">All bots</h3><button class="btn btn-ghost btn-sm" id="runtime-export-btn">Export runtime JSON</button><button class="btn btn-ghost btn-sm" id="bots-export-btn" style="margin-left:8px">Export bots CSV</button><button class="btn btn-ghost btn-sm" id="polls-export-btn" style="margin-left:8px">Export polls CSV</button><button class="btn btn-ghost btn-sm" id="message-stats-export-btn" style="margin-left:8px">Msg stats CSV</button><button class="btn btn-ghost btn-sm" id="reports-export-btn" style="margin-left:8px">Reports CSV</button><button class="btn btn-ghost btn-sm" id="risk-export-btn" style="margin-left:8px">Risk CSV</button><button class="btn btn-ghost btn-sm" id="online-export-btn" style="margin-left:8px">Online CSV</button><button class="btn btn-ghost btn-sm" id="push-tokens-export-btn" style="margin-left:8px">Push tokens CSV</button><button class="btn btn-ghost btn-sm" id="ai-usage-export-btn" style="margin-left:8px">AI usage CSV</button><button class="btn btn-ghost btn-sm" id="sessions-summary-export-btn" style="margin-left:8px">Sessions summary CSV</button><button class="btn btn-ghost btn-sm" id="moderation-audit-export-btn" style="margin-left:8px">Audit CSV</button><button class="btn btn-ghost btn-sm" id="bot-command-stats-export-btn" style="margin-left:8px">Bot cmds CSV</button><button class="btn btn-ghost btn-sm" id="friends-export-btn" style="margin-left:8px">Friends CSV</button><button class="btn btn-ghost btn-sm" id="reports-meta-export-btn" style="margin-left:8px">Reports meta CSV</button><button class="btn btn-ghost btn-sm" id="blocks-export-btn" style="margin-left:8px">Blocks CSV</button><button class="btn btn-ghost btn-sm" id="chat-settings-export-btn" style="margin-left:8px">Chat settings CSV</button><button class="btn btn-ghost btn-sm" id="disappearing-chats-export-btn" style="margin-left:8px">Disappearing chats CSV</button><button class="btn btn-ghost btn-sm" id="muted-chats-export-btn" style="margin-left:8px">Muted chats CSV</button><button class="btn btn-ghost btn-sm" id="pinned-messages-export-btn" style="margin-left:8px">Pinned messages CSV</button><button class="btn btn-ghost btn-sm" id="poll-votes-export-btn" style="margin-left:8px">Poll votes CSV</button><button class="btn btn-ghost btn-sm" id="restricted-users-export-btn" style="margin-left:8px">Restricted users CSV</button><button class="btn btn-ghost btn-sm" id="group-invites-export-btn" style="margin-left:8px">Group invites CSV</button><button class="btn btn-ghost btn-sm" id="totp-users-export-btn" style="margin-left:8px">TOTP users CSV</button><button class="btn btn-ghost btn-sm" id="identity-users-export-btn" style="margin-left:8px">Identity users CSV</button><button class="btn btn-ghost btn-sm" id="privacy-flags-export-btn" style="margin-left:8px">Privacy flags CSV</button><button class="btn btn-ghost btn-sm" id="online-presence-export-btn" style="margin-left:8px">Online presence CSV</button><button class="btn btn-ghost btn-sm" id="ai-feature-flags-export-btn" style="margin-left:8px">AI feature flags CSV</button></div><table class="table"><thead><tr><th>Name</th><th>Username</th><th>Owner</th><th>Token prefix</th><th>Webhook</th><th>Status</th><th>Created</th><th>Actions</th></tr></thead><tbody>';
      list.forEach(function (b) {
        html += '<tr><td>' + esc(b.name) + '</td><td>@' + esc(b.username) + '</td><td class="mono">' + esc(b.ownerUserId) + '</td><td class="mono">' + esc(b.tokenPrefix) + '</td><td>' + esc(b.webhookUrl || '-') + '</td><td>' + (b.enabled ? 'on' : 'off') + '</td><td>' + date(b.createdAt) + '</td><td>' +
          '<button class="btn btn-ghost btn-sm" data-bot-enable="' + esc(b.id) + '" data-enabled="' + (b.enabled ? '0' : '1') + '">' + (b.enabled ? 'Disable' : 'Enable') + '</button> ' +
          '<button class="btn btn-ghost btn-sm" data-bot-logs="' + esc(b.id) + '">Logs</button></td></tr>';
      });
      html += '</tbody></table><pre id="bot-logs-view" class="mono" style="max-height:280px;overflow:auto;margin-top:12px;white-space:pre-wrap"></pre></div>';
      if (staleTab(seq)) return;
      el('content').innerHTML = html;
      var exportBtn = el('runtime-export-btn');
      if (exportBtn) exportBtn.onclick = async function () {
        try {
          var data = await api('/api/admin/runtime-export');
          var blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
          var a = document.createElement('a');
          a.href = URL.createObjectURL(blob);
          a.download = 'maodouchat-runtime-' + Date.now() + '.json';
          a.click();
          toast('Runtime exported');
        } catch (e) { toast(String(e.message || e), 'error'); }
      };
      function bindCsvExport(btnId, url, filenamePrefix, successMsg) {
        var btn = el(btnId);
        if (!btn) return;
        btn.onclick = async function () {
          try {
            var res = await fetch(url, { headers: { Authorization: 'Bearer ' + token } });
            if (!res.ok) throw new Error('export failed ' + res.status);
            var blob = await res.blob();
            var a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = filenamePrefix + Date.now() + '.csv';
            a.click();
            URL.revokeObjectURL(a.href);
            toast(successMsg, 'success');
          } catch (e) {
            toast('Export failed: ' + (e && e.message ? e.message : e), 'error');
          }
        };
      }
      bindCsvExport('bots-export-btn', '/api/admin/bots-export?limit=2000', 'maodouchat-bots-', 'Bots CSV exported');
      bindCsvExport('polls-export-btn', '/api/admin/polls-export?limit=2000', 'maodouchat-polls-', 'Polls CSV exported');
      bindCsvExport('message-stats-export-btn', '/api/admin/message-stats-export', 'maodouchat-message-stats-', 'Message stats CSV exported');
      bindCsvExport('reports-export-btn', '/api/admin/reports-export?limit=2000', 'maodouchat-reports-', 'Reports CSV exported');
      bindCsvExport('risk-export-btn', '/api/admin/risk-events-export?limit=2000', 'maodouchat-risk-', 'Risk events CSV exported');
      bindCsvExport('online-export-btn', '/api/admin/online-export', 'maodouchat-online-', 'Online CSV exported');
      bindCsvExport('push-tokens-export-btn', '/api/admin/push-tokens-export?limit=5000', 'maodouchat-push-tokens-', 'Push tokens CSV exported');
      bindCsvExport('ai-usage-export-btn', '/api/admin/ai-usage-export?limit=2000', 'maodouchat-ai-usage-', 'AI usage CSV exported');
      bindCsvExport('sessions-summary-export-btn', '/api/admin/sessions-summary-export?limit=5000', 'maodouchat-sessions-', 'Sessions summary CSV exported');
      bindCsvExport('moderation-audit-export-btn', '/api/admin/moderation-audit-export?limit=2000', 'maodouchat-audit-', 'Audit CSV exported');
      bindCsvExport('bot-command-stats-export-btn', '/api/admin/bot-command-stats-export?limit=5000', 'maodouchat-bot-cmds-', 'Bot command stats CSV exported');
      bindCsvExport('friends-export-btn', '/api/admin/friends-export?limit=5000', 'maodouchat-friends-', 'Friends CSV exported');
      bindCsvExport('reports-meta-export-btn', '/api/admin/reports-meta-export?limit=5000', 'maodouchat-reports-meta-', 'Reports meta CSV exported');
      bindCsvExport('blocks-export-btn', '/api/admin/blocks-export?limit=5000', 'maodouchat-blocks-', 'Blocks CSV exported');
      bindCsvExport('chat-settings-export-btn', '/api/admin/chat-settings-export?limit=5000', 'maodouchat-chat-settings-', 'Chat settings CSV exported');
      bindCsvExport('disappearing-chats-export-btn', '/api/admin/disappearing-chats-export?limit=5000', 'maodouchat-disappearing-chats-', 'Disappearing chats CSV exported');
      bindCsvExport('muted-chats-export-btn', '/api/admin/muted-chats-export?limit=5000', 'maodouchat-muted-chats-', 'Muted chats CSV exported');
      bindCsvExport('pinned-messages-export-btn', '/api/admin/pinned-messages-export?limit=5000', 'maodouchat-pinned-messages-', 'Pinned messages CSV exported');
      bindCsvExport('poll-votes-export-btn', '/api/admin/poll-votes-export?limit=5000', 'maodouchat-poll-votes-', 'Poll votes CSV exported');
      bindCsvExport('restricted-users-export-btn', '/api/admin/restricted-users-export?limit=5000', 'maodouchat-restricted-users-', 'Restricted users CSV exported');
      bindCsvExport('group-invites-export-btn', '/api/admin/group-invites-export?limit=5000', 'maodouchat-group-invites-', 'Group invites CSV exported');
      bindCsvExport('totp-users-export-btn', '/api/admin/totp-users-export?limit=5000', 'maodouchat-totp-users-', 'TOTP users CSV exported');
      bindCsvExport('identity-users-export-btn', '/api/admin/identity-users-export?limit=5000', 'maodouchat-identity-users-', 'Identity users CSV exported');
      bindCsvExport('privacy-flags-export-btn', '/api/admin/privacy-flags-export?limit=5000', 'maodouchat-privacy-flags-', 'Privacy flags CSV exported');
      bindCsvExport('online-presence-export-btn', '/api/admin/online-presence-export?limit=5000', 'maodouchat-online-presence-', 'Online presence CSV exported');
      bindCsvExport('ai-feature-flags-export-btn', '/api/admin/ai-feature-flags-export', 'maodouchat-ai-feature-flags-', 'AI feature flags CSV exported');

      document.querySelectorAll('[data-bot-enable]').forEach(function (btn) {
        btn.onclick = async function () {
          var id = btn.getAttribute('data-bot-enable');
          var en = btn.getAttribute('data-enabled') === '1';
          try {
            await api('/api/admin/bots/' + encodeURIComponent(id) + '/enabled', {
              method: 'PUT',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ enabled: en })
            });
            toast(en ? 'Bot enabled' : 'Bot disabled');
            loadBots();
          } catch (e) {
            toast(String(e.message || e), 'error');
          }
        };
      });
      document.querySelectorAll('[data-bot-logs]').forEach(function (btn) {
        btn.onclick = async function () {
          var id = btn.getAttribute('data-bot-logs');
          var view = el('bot-logs-view');
          if (view) view.textContent = 'Loading logs...';
          try {
            var data = await api('/api/admin/bots/' + encodeURIComponent(id) + '/command-logs?limit=80');
            var lines = (data.logs || []).map(function (r) {
              return date(r.createdAt) + '  ' + (r.command || '') + '  chat=' + (r.chatId || '-') + ' user=' + (r.userId || '-');
            });
            if (view) view.textContent = lines.length ? lines.join('\n') : '(no command logs)';
          } catch (e) {
            if (view) view.textContent = String(e.message || e);
          }
        };
      });
    } catch (e) {
      el('content').innerHTML = '<div class="empty-state"><p>' + esc(e.message || e) + '</p></div>';
    }
  }

  function fileToBase64(file) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () {
        var s = String(reader.result || '');
        resolve(s.indexOf('base64,') >= 0 ? s.split('base64,')[1] : s);
      };
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
  }

  // ─── B6 运维增强：受控访问器（仅供追加模块只读调用；admin token 仍只存内存） ──
  window.__b6Admin = {
    api: api,
    toast: toast,
    esc: esc,
    date: date,
    el: el,
    // 8.47 修复：以下 helpers 供 IIFE 外的全局 admin* 按钮函数使用——
    // 此前仅暴露 api/toast/esc/date/el，adminForceLogout 等引用闭包内变量 → ReferenceError
    showSelect: showSelect,
    showPrompt: showPrompt,
    ensureDispositionTemplates: ensureDispositionTemplates,
    loadUsers: loadUsers,
    // 8.48 补全：B6 模块与主模块共享同一 tab 渲染序号——主/B6 tab 互切时，
    // 任一侧的旧响应都不得覆盖另一侧的新页面
    nextTabSeq: function () { return ++loadSeq; },
    isStaleTab: staleTab,
    get dispositionTemplates() { return dispositionTemplates; }
  };

})();


async function adminForceLogout(userId) {
  if (!userId) return;
  if (!confirm('Force logout user ' + userId + ' on all devices?')) return;
  try {
    await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/force-logout', { method: 'POST', body: '{}' });
    window.__b6Admin.toast('Force logout ok');
  } catch (e) {
    window.__b6Admin.toast('Force logout failed: ' + (e && e.message ? e.message : e));
  }
}

async function adminLoadUserSessions(userId) {
  if (!userId) return;
  try {
    var data = await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/sessions?includeRevoked=1');
    var lines = [];
    lines.push('Active refresh sessions: ' + (data.activeRefreshCount || 0));
    (data.refreshSessions || []).slice(0, 20).forEach(function (s, idx) {
      lines.push((s.active ? '[active] ' : '[revoked] ') + s.tokenHashPrefix + ' exp ' + window.__b6Admin.date(s.expiresAt));
    });
    lines.push('Signal devices: ' + ((data.signalDevices || []).length));
    (data.signalDevices || []).slice(0, 12).forEach(function (d) {
      lines.push('#' + d.deviceId + ' ' + (d.deviceName || '') + ' ' + (d.status || '') + ' last ' + window.__b6Admin.date(d.lastSeenAt));
    });
    lines.push('Push tokens: ' + ((data.pushTokens || []).length));
    var text = lines.join('\n') || 'No sessions';
    var action = window.prompt(text + '\n\nActions: type session prefix to revoke one, or ALL to force logout all', '');
    if (action == null) return;
    action = String(action).trim();
    if (!action) return;
    if (action.toUpperCase() === 'ALL') {
      await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/sessions/revoke', {
        method: 'POST',
        body: JSON.stringify({ all: true })
      });
      window.__b6Admin.toast('All sessions revoked', 'success');
    } else {
      await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/sessions/revoke', {
        method: 'POST',
        body: JSON.stringify({ tokenHashPrefix: action })
      });
      window.__b6Admin.toast('Session revoke requested for prefix ' + action, 'success');
    }
  } catch (e) {
    window.__b6Admin.toast('Sessions failed: ' + (e && e.message ? e.message : e), 'error');
  }
}



  async function adminMessageRestrict(userId) {
    // Reuse list button flow via synthetic element
    var fake = { dataset: { messageRestrict: userId }, onclick: null };
    await window.__b6Admin.ensureDispositionTemplates();
    var maxDays = window.__b6Admin.dispositionTemplates.maxMessageRestrictDays || 90;
    var appeal = window.__b6Admin.dispositionTemplates.appealNoticeZh || '';
    var reasons = window.__b6Admin.dispositionTemplates.messageRestrictReasons || [];
    window.__b6Admin.showSelect(
      'Message restriction',
      'Restrict sending messages.\n' + appeal,
      [{ value: 'unrestrict_messages', label: 'Clear message ban' }].concat(
        reasons.map(function (r) {
          return { value: r.code, label: (r.labelZh || r.code) + ' (default ' + r.defaultDays + 'd)' };
        })
      ),
      'spam_chat',
      async function (reasonCode) {
        if (reasonCode === 'unrestrict_messages' || reasonCode === window.__b6Admin.dispositionTemplates.unrestrictMessagesReasonCode) {
          window.__b6Admin.showPrompt('Clear message ban', 'Optional note', '', 'Note', async function (note) {
            await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/message-restriction', {
              method: 'PUT',
              body: JSON.stringify({
                messageRestrictedUntil: 0,
                reasonCode: window.__b6Admin.dispositionTemplates.unrestrictMessagesReasonCode || 'unrestrict_messages',
                note: note || null
              })
            });
            window.__b6Admin.toast('Message ban cleared', 'success');
            window.__b6Admin.el('drawer-overlay').classList.add('hidden');
            await window.__b6Admin.loadUsers();
            return true;
          });
          return true;
        }
        var template = reasons.find(function (r) { return r.code === reasonCode; });
        var defaultDays = template ? String(template.defaultDays) : '1';
        window.__b6Admin.showPrompt('Message ban days', '1-' + maxDays + ' days', defaultDays, 'Days', async function (val) {
          var n = Number(val);
          if (!Number.isFinite(n) || n < 1 || n > maxDays) { window.__b6Admin.toast('Invalid days', 'error'); return false; }
          var until = Date.now() + Math.round(n * 86400000);
          var finish = async function (note) {
            if (template && template.requiresCustomNote && !(note && String(note).trim())) {
              window.__b6Admin.toast('Note required', 'error');
              return false;
            }
            await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/message-restriction', {
              method: 'PUT',
              body: JSON.stringify({
                messageRestrictedUntil: until,
                reasonCode: reasonCode,
                note: note ? String(note).trim() : null
              })
            });
            window.__b6Admin.toast('Message banned ' + n + 'd', 'success');
            window.__b6Admin.el('drawer-overlay').classList.add('hidden');
            await window.__b6Admin.loadUsers();
            return true;
          };
          if (template && template.requiresCustomNote) {
            window.__b6Admin.showPrompt('Custom note', 'Required note', '', 'Note', finish);
            return true;
          }
          return finish(null);
        });
        return true;
      }
    );
  }


async function adminDisableTotp(userId) {
  if (!userId) return;
  if (!confirm('Disable TOTP for user ' + userId + '?')) return;
  try {
    await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/disable-totp', { method: 'POST', body: '{}' });
    window.__b6Admin.toast('TOTP disabled');
  } catch (e) {
    window.__b6Admin.toast('Disable TOTP failed: ' + (e && e.message ? e.message : e));
  }
}


async function adminBroadcast() {
  var text = prompt('Broadcast message to all online users:');
  if (!text || !text.trim()) return;
  try {
    var res = await window.__b6Admin.api('/api/admin/broadcast', { method: 'POST', body: JSON.stringify({ text: text.trim(), title: 'System' }) });
    window.__b6Admin.toast('Broadcast delivered to ' + (res.delivered || 0) + ' online sessions');
  } catch (e) {
    window.__b6Admin.toast('Broadcast failed: ' + (e && e.message ? e.message : e));
  }
}

async function adminSetModerator(userId, enabled) {
  if (!userId) return;
  try {
    await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/moderator', {
      method: 'PUT',
      body: JSON.stringify({ enabled: !!enabled })
    });
    window.__b6Admin.toast(enabled ? 'Moderator granted' : 'Moderator revoked');
  } catch (e) {
    window.__b6Admin.toast('Moderator update failed: ' + (e && e.message ? e.message : e));
  }
}

/* ═══════════════════════════════════════════════════════
   B6 服务端运维增强 — 公告广播 / 用户标签 / 限流仪表盘 / 设备一致性
   （纯追加模块，自包含；通过 window.__b6Admin 访问主闭包 API）
   ═══════════════════════════════════════════════════════ */
(function () {
  'use strict';
  var H = window.__b6Admin;
  var api = H.api, toast = H.toast, esc = H.esc, date = H.date;
  var el = H.el;
  var currentTab = '';
  var pg = { announcements: 0, 'user-tags': 0, 'user-tag-users': 0, 'device-consistency': 0 };
  var pageSize = 25;

  var TABS = {
    announcements: { title: '公告广播', fn: loadAnnouncements },
    'user-tags': { title: '用户标签', fn: loadUserTags },
    'rate-limit': { title: '限流仪表盘', fn: loadRateLimit },
    'device-consistency': { title: '设备一致性', fn: loadDeviceConsistency }
  };

  function fail(x) {
    el('content').innerHTML = '<div class="empty-state"><svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 1a7 7 0 100 14 7 7 0 000-14zm0 4a1 1 0 110 2 1 1 0 010-2zm1 4v3H7V9h2z"/></svg><p>' + esc(x && x.message ? x.message : x) + '</p></div>';
  }

  function pager(kind, count) {
    var p = pg[kind] || 0;
    return '<div class="pagination">' +
      '<span class="page-info">第 ' + (p + 1) + ' 页</span>' +
      '<button class="btn btn-ghost btn-sm" data-b6prev="' + kind + '" ' + (p === 0 ? 'disabled' : '') + '>上一页</button>' +
      '<button class="btn btn-ghost btn-sm" data-b6next="' + kind + '" ' + (count < pageSize ? 'disabled' : '') + '>下一页</button>' +
      '</div>';
  }
  function bindPager(kind, count, loader) {
    var prev = document.querySelector('[data-b6prev="' + kind + '"]');
    var next = document.querySelector('[data-b6next="' + kind + '"]');
    if (prev) prev.onclick = function () { if ((pg[kind] || 0) > 0) { pg[kind]--; loader(); } };
    if (next) next.onclick = function () { if (count === pageSize) { pg[kind] = (pg[kind] || 0) + 1; loader(); } };
  }

  var LEVELS = ['INFO', 'WARNING', 'MAINTENANCE', 'EMERGENCY'];
  var RISK = ['NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
  var STATUS_COLOR = { ACTIVE: 'badge-green', SCHEDULED: 'badge-blue', DRAFT: '', EXPIRED: '', CANCELLED: 'badge-red' };

  // ─── 公告广播 ───────────────────────────
  async function loadAnnouncements(seq) {
    var offset = (pg.announcements || 0) * pageSize;
    var q = document.getElementById('b6-ann-q') ? document.getElementById('b6-ann-q').value.trim() : '';
    var st = document.getElementById('b6-ann-status') ? document.getElementById('b6-ann-status').value : '';
    var url = '/api/admin/announcements?limit=' + pageSize + '&offset=' + offset;
    if (st) url += '&status=' + encodeURIComponent(st);
    if (q) url += '&q=' + encodeURIComponent(q);
    var rows = await api(url);

    var statusOpts = ['', 'ACTIVE', 'SCHEDULED', 'DRAFT', 'EXPIRED', 'CANCELLED'].map(function (s) {
      return '<option value="' + s + '"' + (st === s ? ' selected' : '') + '>' + (s || '全部状态') + '</option>';
    }).join('');

    var html = '<div class="panel"><div class="panel-header"><h2>系统公告广播</h2>' +
      '<div class="toolbar">' +
      '<input class="search-input" id="b6-ann-q" value="' + esc(q) + '" placeholder="搜索标题/内容…"/>' +
      '<select class="filter-select" id="b6-ann-status">' + statusOpts + '</select>' +
      '<button class="btn btn-primary btn-sm" id="b6-ann-search">搜索</button>' +
      '<button class="btn btn-primary btn-sm" id="b6-ann-create">新建公告</button>' +
      '</div></div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>标题</th><th>级别</th><th>受众</th><th>生效</th><th>失效</th><th>状态</th><th>操作</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="7"><div class="empty-state"><p>暂无公告</p></div></td></tr>';
    } else {
      rows.forEach(function (r) {
        html += '<tr>' +
          '<td><strong>' + esc(r.title) + '</strong><div class="cell-sub">' + esc(r.content.slice(0, 60)) + '</div></td>' +
          '<td><span class="badge badge-purple">' + esc(r.level) + '</span></td>' +
          '<td>' + esc(r.audience === 'TAGGED' ? '标签:' + (r.tagId || '?') : '全员') + '</td>' +
          '<td>' + esc(date(r.startsAt)) + '</td>' +
          '<td>' + esc(date(r.expiresAt)) + '</td>' +
          '<td><span class="badge ' + (STATUS_COLOR[r.status] || '') + '">' + esc(r.status) + '</span></td>' +
          '<td style="white-space:nowrap">' +
          (r.status === 'DRAFT' || r.status === 'SCHEDULED' ? '<button class="btn btn-primary btn-sm" data-b6-pub="' + esc(r.id) + '">发布</button> ' : '') +
          (r.status === 'ACTIVE' || r.status === 'SCHEDULED' ? '<button class="btn btn-ghost btn-sm" data-b6-cancel="' + esc(r.id) + '">取消</button> ' : '') +
          (r.status === 'DRAFT' ? '<button class="btn btn-danger btn-sm" data-b6-del="' + esc(r.id) + '">删除</button> ' : '') +
          '<button class="btn btn-ghost btn-sm" data-b6-stats="' + esc(r.id) + '">统计</button>' +
          '</td></tr>';
      });
    }

    html += '</tbody></table></div>' + pager('announcements', rows.length) + '</div></div>';
    if (H.isStaleTab(seq)) return;
    el('content').innerHTML = html;

    var searchBtn = document.getElementById('b6-ann-search');
    if (searchBtn) searchBtn.onclick = function () { pg.announcements = 0; loadAnnouncements(); };
    var input = document.getElementById('b6-ann-q');
    if (input) input.onkeydown = function (e) { if (e.key === 'Enter') { pg.announcements = 0; loadAnnouncements(); } };
    bindPager('announcements', rows.length, loadAnnouncements);

    document.querySelectorAll('[data-b6-pub]').forEach(function (b) {
      b.onclick = function () { api('/api/admin/announcements/' + encodeURIComponent(b.dataset.b6Pub) + '/publish', { method: 'POST', body: '{}' }).then(function () { toast('已发布', 'success'); loadAnnouncements(); }).catch(function (e) { toast('发布失败: ' + e.message, 'error'); }); };
    });
    document.querySelectorAll('[data-b6-cancel]').forEach(function (b) {
      b.onclick = function () {
        if (!confirm('确认取消公告 ' + b.dataset.b6Cancel + '？')) return;
        api('/api/admin/announcements/' + encodeURIComponent(b.dataset.b6Cancel) + '/cancel', { method: 'POST', body: '{}' }).then(function () { toast('已取消', 'success'); loadAnnouncements(); }).catch(function (e) { toast('取消失败: ' + e.message, 'error'); });
      };
    });
    document.querySelectorAll('[data-b6-del]').forEach(function (b) {
      b.onclick = function () {
        if (!confirm('确认删除草稿 ' + b.dataset.b6Del + '？仅未发布草稿可删除。')) return;
        api('/api/admin/announcements/' + encodeURIComponent(b.dataset.b6Del), { method: 'DELETE' }).then(function () { toast('已删除', 'success'); loadAnnouncements(); }).catch(function (e) { toast('删除失败: ' + e.message, 'error'); });
      };
    });
    document.querySelectorAll('[data-b6-stats]').forEach(function (b) {
      b.onclick = async function () {
        try {
          var s = await api('/api/admin/announcements/' + encodeURIComponent(b.dataset.b6Stats) + '/stats');
          toast('受众 ' + s.recipientCount + ' 人 · 已读 ' + s.ackedCount + ' 人', 'success');
        } catch (e) { toast('统计失败: ' + e.message, 'error'); }
      };
    });

    document.getElementById('b6-ann-create').onclick = async function () {
      var title = prompt('公告标题：');
      if (!title || !title.trim()) return;
      var content = prompt('公告内容（平台明文广播，不含会话正文）：');
      if (!content || !content.trim()) return;
      var level = prompt('级别（INFO / WARNING / MAINTENANCE / EMERGENCY，默认 INFO）:', 'INFO');
      level = LEVELS.indexOf((level || 'INFO').toUpperCase()) >= 0 ? (level || 'INFO').toUpperCase() : 'INFO';
      var audience = prompt('受众（ALL 全员 / TAGGED 按标签，默认 ALL）:', 'ALL');
      audience = (audience || 'ALL').toUpperCase() === 'TAGGED' ? 'TAGGED' : 'ALL';
      var tagId = null;
      if (audience === 'TAGGED') {
        tagId = prompt('定向标签 ID（先在「用户标签」页创建标签，填其 id）：');
        if (!tagId || !tagId.trim()) { toast('按标签公告必须指定 tagId', 'error'); return; }
        tagId = tagId.trim();
      }
      var startsAt = prompt('生效时间戳（毫秒，留空立即生效）：');
      var expiresAt = prompt('失效时间戳（毫秒，留空默认 7 天）：');
      var body = {
        title: title.trim(), content: content.trim(), level: level,
        audience: audience, tagId: tagId,
        startsAt: startsAt && Number.isFinite(Number(startsAt)) ? Number(startsAt) : null,
        expiresAt: expiresAt && Number.isFinite(Number(expiresAt)) ? Number(expiresAt) : null
      };
      try {
        await api('/api/admin/announcements', { method: 'POST', body: JSON.stringify(body) });
        toast('公告已创建', 'success');
        pg.announcements = 0;
        loadAnnouncements();
      } catch (e) { toast('创建失败: ' + e.message, 'error'); }
    };
  }

  // ─── 用户标签 + 风控联动 ─────────────────
  async function loadUserTags(seq) {
    var tags = await api('/api/admin/user-tags');
    var risk = null;
    try { risk = await api('/api/admin/tags/risk-summary'); } catch (e) { /* 可选 */ }

    var html = '<div class="panel"><div class="panel-header"><h2>用户标签与风控</h2>' +
      '<div class="toolbar"><button class="btn btn-primary btn-sm" id="b6-tag-create">新建标签</button></div></div>' +
      '<div class="panel-body">';
    if (risk && risk.tags && risk.tags.length) {
      html += '<div class="stats-grid" style="margin-bottom:16px">' + risk.tags.map(function (t) {
        return '<div class="stat-card"><div class="stat-value" style="color:var(--danger)">' + t.userCount + '</div>' +
          '<div class="stat-label">' + esc(t.name) + ' (' + esc(t.riskLevel) + ')</div></div>';
      }).join('') + '</div>';
    }
    html += '<div class="table-wrap"><table>' +
      '<thead><tr><th>名称</th><th>风控级别</th><th>描述</th><th>用户数</th><th>类型</th><th>操作</th></tr></thead><tbody>';

    if (tags.length === 0) {
      html += '<tr><td colspan="6"><div class="empty-state"><p>暂无标签</p></div></td></tr>';
    } else {
      tags.forEach(function (t) {
        var riskBadge = t.riskLevel === 'HIGH' || t.riskLevel === 'CRITICAL' ? 'badge-red' : (t.riskLevel === 'MEDIUM' ? 'badge-warn' : '');
        html += '<tr>' +
          '<td><span class="badge" style="background:' + esc(t.color) + ';color:#fff">' + esc(t.name) + '</span></td>' +
          '<td><span class="badge ' + riskBadge + '">' + esc(t.riskLevel) + '</span></td>' +
          '<td>' + esc(t.description || '—') + '</td>' +
          '<td>' + t.userCount + '</td>' +
          '<td>' + (t.isSystem ? '<span class="badge badge-blue">系统</span>' : '自定义') + '</td>' +
          '<td style="white-space:nowrap">' +
          '<button class="btn btn-ghost btn-sm" data-b6-tag-users="' + esc(t.id) + '">用户</button> ' +
          (t.isSystem ? '' : '<button class="btn btn-ghost btn-sm" data-b6-tag-edit="' + esc(t.id) + '">编辑</button> ' +
            '<button class="btn btn-danger btn-sm" data-b6-tag-del="' + esc(t.id) + '">删除</button>') +
          '</td></tr>';
      });
    }
    html += '</tbody></table></div></div></div>';
    if (H.isStaleTab(seq)) return;
    el('content').innerHTML = html;

    document.getElementById('b6-tag-create').onclick = async function () {
      var name = prompt('标签名称：');
      if (!name || !name.trim()) return;
      var riskLevel = prompt('风控级别（NONE/LOW/MEDIUM/HIGH/CRITICAL，默认 LOW）:', 'LOW');
      riskLevel = RISK.indexOf((riskLevel || 'LOW').toUpperCase()) >= 0 ? (riskLevel || 'LOW').toUpperCase() : 'LOW';
      var color = prompt('标签颜色（十六进制，默认 #64748b）:', '#64748b') || '#64748b';
      var desc = prompt('描述（可留空）：');
      try {
        await api('/api/admin/user-tags', { method: 'POST', body: JSON.stringify({ name: name.trim(), riskLevel: riskLevel, color: color, description: desc || null }) });
        toast('标签已创建', 'success');
        loadUserTags();
      } catch (e) { toast('创建失败: ' + e.message, 'error'); }
    };
    document.querySelectorAll('[data-b6-tag-edit]').forEach(function (b) {
      b.onclick = async function () {
        var newRisk = prompt('修改风控级别（NONE/LOW/MEDIUM/HIGH/CRITICAL）:', 'MEDIUM');
        newRisk = RISK.indexOf((newRisk || 'MEDIUM').toUpperCase()) >= 0 ? (newRisk || 'MEDIUM').toUpperCase() : 'MEDIUM';
        try {
          await api('/api/admin/user-tags/' + encodeURIComponent(b.dataset.b6TagEdit), { method: 'PUT', body: JSON.stringify({ riskLevel: newRisk }) });
          toast('标签已更新', 'success');
          loadUserTags();
        } catch (e) { toast('更新失败: ' + e.message, 'error'); }
      };
    });
    document.querySelectorAll('[data-b6-tag-del]').forEach(function (b) {
      b.onclick = function () {
        if (!confirm('确认删除标签 ' + b.dataset.b6TagDel + '？会移除所有用户上的该标签。')) return;
        api('/api/admin/user-tags/' + encodeURIComponent(b.dataset.b6TagDel), { method: 'DELETE' }).then(function () { toast('标签已删除', 'success'); loadUserTags(); }).catch(function (e) { toast('删除失败: ' + e.message, 'error'); });
      };
    });
    document.querySelectorAll('[data-b6-tag-users]').forEach(function (b) {
      b.onclick = function () { showTagUsers(b.dataset.b6TagUsers); };
    });
  }

  async function showTagUsers(tagId) {
    var offset = (pg['user-tag-users'] || 0) * pageSize;
    var rows = await api('/api/admin/user-tags/' + encodeURIComponent(tagId) + '/users?limit=' + pageSize + '&offset=' + offset);
    var html = '<div class="panel"><div class="panel-header"><h2>标签用户 #' + esc(tagId) + '</h2>' +
      '<div class="toolbar"><button class="btn btn-ghost btn-sm" id="b6-tag-users-back">返回</button>' +
      '<button class="btn btn-primary btn-sm" id="b6-tag-users-add">添加用户</button></div></div>' +
      '<div class="panel-body"><div class="table-wrap"><table>' +
      '<thead><tr><th>用户 ID</th><th>来源</th><th>打标人</th><th>时间</th><th>操作</th></tr></thead><tbody>';
    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>该标签下暂无用户</p></div></td></tr>';
    } else {
      rows.forEach(function (a) {
        html += '<tr><td><span class="cell-id">' + esc(a.userId) + '</span></td><td>' + esc(a.source) + '</td>' +
          '<td>' + esc(a.assignedBy || '—') + '</td><td>' + esc(date(a.createdAt)) + '</td>' +
          '<td><button class="btn btn-danger btn-sm" data-b6-unassign="' + esc(a.userId) + '">移除</button></td></tr>';
      });
    }
    html += '</tbody></table></div>' + pager('user-tag-users', rows.length) + '</div></div>';
    el('content').innerHTML = html;
    bindPager('user-tag-users', rows.length, function () { showTagUsers(tagId); });
    document.getElementById('b6-tag-users-back').onclick = loadUserTags;
    document.getElementById('b6-tag-users-add').onclick = async function () {
      var userId = prompt('用户 ID：');
      if (!userId || !userId.trim()) return;
      try {
        await api('/api/admin/users/' + encodeURIComponent(userId.trim()) + '/tags', { method: 'POST', body: JSON.stringify({ tagIds: [tagId] }) });
        toast('已打标', 'success');
        showTagUsers(tagId);
      } catch (e) { toast('打标失败: ' + e.message, 'error'); }
    };
    document.querySelectorAll('[data-b6-unassign]').forEach(function (b) {
      b.onclick = function () {
        if (!confirm('移除用户 ' + b.dataset.b6Unassign + ' 的该标签？')) return;
        api('/api/admin/users/' + encodeURIComponent(b.dataset.b6Unassign) + '/tags/' + encodeURIComponent(tagId), { method: 'DELETE' }).then(function () { toast('已移除', 'success'); showTagUsers(tagId); }).catch(function (e) { toast('移除失败: ' + e.message, 'error'); });
      };
    });
  }

  // ─── 限流仪表盘 ─────────────────────────
  async function loadRateLimit(seq) {
    var range = '24h';
    var holder = document.getElementById('b6-rl-range');
    if (holder) range = holder.value;
    var d = await api('/api/admin/rate-limit/dashboard?range=' + range);

    var maxR = 1;
    d.points.forEach(function (p) { if (p.rejected > maxR) maxR = p.rejected; });
    var bars = d.points.slice(-48).map(function (p) {
      var h = maxR > 0 ? Math.max(2, Math.round(p.rejected / maxR * 100)) : 2;
      return '<div class="rl-bar" title="' + date(p.bucketStartMs) + ' 拒绝 ' + p.rejected + ' / 放行 ' + p.allowed + '" style="height:' + h + '%"></div>';
    }).join('');

    var html = '<div class="panel"><div class="panel-header"><h2>限流仪表盘</h2>' +
      '<div class="toolbar">' +
      '<select class="filter-select" id="b6-rl-range">' +
      '<option value="1h"' + (range === '1h' ? ' selected' : '') + '>近 1 小时</option>' +
      '<option value="24h"' + (range === '24h' ? ' selected' : '') + '>近 24 小时</option>' +
      '<option value="7d"' + (range === '7d' ? ' selected' : '') + '>近 7 天</option>' +
      '</select>' +
      '<button class="btn btn-primary btn-sm" id="b6-rl-refresh">刷新</button>' +
      '<button class="btn btn-ghost btn-sm" id="b6-rl-sample">立即采样</button>' +
      '</div></div>' +
      '<div class="panel-body">' +
      '<div class="stats-grid">' +
      '<div class="stat-card"><div class="stat-value" style="color:var(--success)">' + d.totalAllowed + '</div><div class="stat-label">放行请求（' + range + '）</div></div>' +
      '<div class="stat-card"><div class="stat-value" style="color:var(--danger)">' + d.totalRejected + '</div><div class="stat-label">拒绝请求（' + range + '）</div></div>' +
      '<div class="stat-card"><div class="stat-value">' + d.peakRejectionsPerMinute + '</div><div class="stat-label">每分钟拒绝峰值</div></div>' +
      '<div class="stat-card"><div class="stat-value">' + d.live.totalBuckets + '/' + d.live.maxBuckets + '</div><div class="stat-label">实时 IP 桶 / 上限</div></div>' +
      '</div>' +
      '<div class="rl-chart"><div class="rl-bars">' + (bars || '<div class="empty-state"><p>暂无采样数据（采样器每分钟写入，最多 31 天）</p></div>') + '</div></div>' +
      '<p class="cell-sub">实时累计: 放行 ' + d.live.allowed + ' · 拒绝 ' + d.live.rejected +
      ' · 每 IP 每分钟上限 ' + d.live.maxPerMinute + ' · 最近采样 ' + (d.lastSnapshotAt ? date(d.lastSnapshotAt) : '—') +
      ' · 保留 ' + d.retentionDays + ' 天</p>' +
      '</div></div>';
    if (H.isStaleTab(seq)) return;
    el('content').innerHTML = html;

    document.getElementById('b6-rl-refresh').onclick = loadRateLimit;
    document.getElementById('b6-rl-range').onchange = loadRateLimit;
    document.getElementById('b6-rl-sample').onclick = async function () {
      try {
        await api('/api/admin/rate-limit/sample', { method: 'POST', body: '{}' });
        toast('已手动采样', 'success');
        loadRateLimit();
      } catch (e) { toast('采样失败: ' + e.message, 'error'); }
    };
  }

  // ─── 设备事件一致性 ─────────────────────
  async function loadDeviceConsistency(seq) {
    var offset = (pg['device-consistency'] || 0) * pageSize;
    var sum = await api('/api/admin/device-consistency/summary');
    var evs = await api('/api/admin/device-consistency/events?limit=' + pageSize + '&offset=' + offset);

    var html = '<div class="panel"><div class="panel-header"><h2>设备事件一致性</h2>' +
      '<div class="toolbar"><span class="badge ' + (sum.anomalyCount > 0 ? 'badge-red' : 'badge-green') + '">异常事件 ' + sum.anomalyCount + '</span></div></div>' +
      '<div class="panel-body">' +
      '<h3 class="panel-subtitle">设备事件序列（幂等应用点）</h3>' +
      '<div class="table-wrap"><table>' +
      '<thead><tr><th>用户</th><th>设备</th><th>事件类型</th><th>已应用 seq</th><th>最近事件</th></tr></thead><tbody>';
    if (sum.sequences.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无设备事件序列记录</p></div></td></tr>';
    } else {
      sum.sequences.slice(0, 50).forEach(function (s) {
        html += '<tr><td><span class="cell-id">' + esc(s.userId) + '</span></td><td>#' + s.deviceId + '</td>' +
          '<td>' + esc(s.eventType) + '</td><td>' + s.lastAppliedSeq + '</td><td>' + esc(date(s.lastEventAt)) + '</td></tr>';
      });
    }
    html += '</tbody></table></div>' +
      '<h3 class="panel-subtitle">一致性异常（STALE / DUPLICATE / OUT_OF_ORDER）</h3>' +
      '<div class="table-wrap"><table>' +
      '<thead><tr><th>时间</th><th>用户</th><th>设备</th><th>类型</th><th>seq</th><th>状态</th><th>详情</th></tr></thead><tbody>';
    if (evs.length === 0) {
      html += '<tr><td colspan="7"><div class="empty-state"><p>暂无异常事件，设备事件一致性正常</p></div></td></tr>';
    } else {
      evs.forEach(function (e) {
        var cls = e.status === 'OUT_OF_ORDER' ? 'badge-warn' : 'badge-red';
        html += '<tr><td>' + esc(date(e.lastSeenAt)) + '</td><td><span class="cell-id">' + esc(e.userId) + '</span></td>' +
          '<td>#' + e.deviceId + '</td><td>' + esc(e.eventType) + '</td><td>' + e.seq + '</td>' +
          '<td><span class="badge ' + cls + '">' + esc(e.status) + '</span></td>' +
          '<td style="max-width:240px" class="mono">' + esc(e.detail || '—') + '</td></tr>';
      });
    }
    html += '</tbody></table></div>' + pager('device-consistency', evs.length) + '</div></div>';
    if (H.isStaleTab(seq)) return;
    el('content').innerHTML = html;
    bindPager('device-consistency', evs.length, loadDeviceConsistency);
  }

  // ─── 导航接线（B6 标签页 + 全局刷新联动）────────────────
  document.getElementById('nav').addEventListener('click', function (e) {
    var b = e.target.closest('button[data-tab]');
    if (!b) return;
    var tab = b.dataset.tab;
    if (!TABS[tab]) return;
    currentTab = tab;
    el('page-title').textContent = TABS[tab].title;
    el('content').innerHTML = '<div class="loading-state"><div class="spinner"></div><span>加载中…</span></div>';
    // 8.48：共享主模块的 tab 渲染序号——主/B6 tab 互切时旧响应不得覆盖新页面
    var seq = H.nextTabSeq();
    TABS[tab].fn(seq).catch(function (x) { if (!H.isStaleTab(seq)) fail(x); });
  });
  document.getElementById('refresh-btn').addEventListener('click', function () {
    if (currentTab && TABS[currentTab]) {
      el('content').innerHTML = '<div class="loading-state"><div class="spinner"></div><span>加载中…</span></div>';
      var seq = H.nextTabSeq();
      TABS[currentTab].fn(seq).catch(function (x) { if (!H.isStaleTab(seq)) fail(x); });
    }
  });

  // ─── B2 密聊防泄漏（Surface #71–#78）：设置页自动追加 8 个开关行 ───
  // 服务端只存开关位、不接触密聊明文；行保存复用 settings-save 的 [data-setting] 收集。
  var b2SecretRows = [
    { key: 'secret_screenshot_burn_enabled', label: 'Secret chat screenshot burn (clients)', def: true },
    { key: 'secret_auto_destroy_enabled', label: 'Secret chat auto session destroy TTL (clients)', def: true },
    { key: 'secret_forward_whitelist_enabled', label: 'Secret chat forward whitelist (clients)', def: true },
    { key: 'secret_sim_change_protection_enabled', label: 'Secret chat SIM change protection (clients)', def: true },
    { key: 'secret_2fa_gate_enabled', label: 'Secret chat 2FA gate (clients)', def: false },
    { key: 'secret_new_device_risk_enabled', label: 'Secret chat new device risk (clients)', def: true },
    { key: 'secret_device_verify_enabled', label: 'Secret chat device verify (clients)', def: true },
    { key: 'secret_session_notice_enabled', label: 'Secret chat two-way session notice (clients)', def: true }
  ];
  function injectB2SecretRows(settings) {
    var host = document.querySelector('#content .panel-body');
    if (!host || document.getElementById('b2-secret-surface-rows')) return;
    var s = settings || {};
    var html = b2SecretRows.map(function (r) {
      var raw = s[r.key] != null ? s[r.key] : r.def;
      var on = String(raw).toLowerCase() === 'true' || raw === 1 || raw === true;
      return '<label class="field" style="display:flex;align-items:center;gap:8px;margin:8px 0">' +
        '<input type="checkbox" data-setting="' + esc(r.key) + '" ' + (on ? 'checked' : '') + '/>' +
        '<span><strong>' + esc(r.label) + '</strong> <code>' + esc(r.key) + '</code></span></label>';
    }).join('');
    var block = document.createElement('div');
    block.id = 'b2-secret-surface-rows';
    block.style.marginTop = '12px';
    block.style.paddingTop = '10px';
    block.style.borderTop = '1px solid var(--border,#333)';
    block.innerHTML = '<div style="font-size:12px;color:var(--text-muted);margin-bottom:4px">B2 · 密聊防泄漏扩展（Surface #71–#78 · burnz/ttlz/fwlz/simz/2faz/ndz/dvz/sntz）</div>' + html;
    host.appendChild(block);
  }
  // 8.48 修复：Settings 保存后 loadSettings() 会重渲染面板——observer 必须保持连接，
  // 每次 Settings 面板出现且 B2 行缺失时重新注入（注入本身幂等，带 in-flight 防抖）
  var b2SettingsBusy = false;
  var b2SettingsObserver = new MutationObserver(function () {
    if (document.getElementById('b2-secret-surface-rows')) return;
    var host = document.querySelector('#content .panel-body');
    var saveBtn = el('settings-save');
    if (!host || !saveBtn || b2SettingsBusy) return;
    b2SettingsBusy = true;
    api('/api/admin/settings').then(function (data) {
      injectB2SecretRows((data && data.settings) || {});
    }).catch(function () { /* 留待下一次 DOM 变更重试 */ })
      .then(function () { b2SettingsBusy = false; });
  });
  b2SettingsObserver.observe(document.body, { childList: true, subtree: true });
})();
