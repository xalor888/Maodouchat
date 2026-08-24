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
  var filterState = { 'risk-events': 'true', reports: 'OPEN' };
  var dashboardData = null;
  var systemStatsData = null;
  var paneState = { content: 'posts', diagnostics: 'ai-usage', system: 'settings', risk: 'events' };
  var SUBTAB_DEFS = {
    content: [
      { id: 'posts', label: '动态' },
      { id: 'comments', label: '评论' },
      { id: 'chats', label: '群聊' },
      { id: 'messages', label: '消息检索' }
    ],
    diagnostics: [
      { id: 'ai-usage', label: 'AI 审计' },
      { id: 'push-tokens', label: '推送令牌' },
      { id: 'storage', label: '存储' },
      { id: 'watermark', label: '水印取证' }
    ],
    system: [
      { id: 'settings', label: '运营开关' },
      { id: 'bots', label: '机器人' },
      { id: 'user-tags', label: '用户标签' },
      { id: 'rate-limit', label: '限流' },
      { id: 'device-consistency', label: '设备一致性' },
      { id: 'channels', label: '密钥与通道' }
    ],
    risk: [
      { id: 'events', label: '待处理事件' },
      { id: 'rules', label: '匹配规则' }
    ]
  };
  var B6_SYSTEM_PANES = { 'user-tags': 1, 'rate-limit': 1, 'device-consistency': 1 };

  function attachSubtabs() {
    var kind = activeTab;
    if (!SUBTAB_DEFS[kind]) return;
    var host = el('content');
    if (!host) return;
    if (host.querySelector('[data-subtabs="' + kind + '"]')) return;
    var bar = document.createElement('div');
    bar.className = 'subtabs';
    bar.setAttribute('data-subtabs', kind);
    bar.innerHTML = SUBTAB_DEFS[kind].map(function (p) {
      return '<button type="button" class="subtab' + (p.id === paneState[kind] ? ' active' : '') + '" data-pane="' + p.id + '">' + p.label + '</button>';
    }).join('');
    host.insertBefore(bar, host.firstChild);
    bar.addEventListener('click', function (e) {
      var b = e.target.closest('button[data-pane]');
      if (!b) return;
      paneState[kind] = b.dataset.pane;
      loadTab();
    });
  }

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

  function asList(data) {
    if (Array.isArray(data)) return data;
    if (!data || typeof data !== 'object') return [];
    if (Array.isArray(data.items)) return data.items;
    if (Array.isArray(data.rows)) return data.rows;
    if (Array.isArray(data.users)) return data.users;
    if (Array.isArray(data.reports)) return data.reports;
    if (Array.isArray(data.events)) return data.events;
    if (Array.isArray(data.rules)) return data.rules;
    if (Array.isArray(data.posts)) return data.posts;
    if (Array.isArray(data.comments)) return data.comments;
    if (Array.isArray(data.chats)) return data.chats;
    if (Array.isArray(data.messages)) return data.messages;
    if (Array.isArray(data.announcements)) return data.announcements;
    if (Array.isArray(data.logs)) return data.logs;
    if (Array.isArray(data.tokens)) return data.tokens;
    return [];
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
    var btnLabel = el('login-btn-label');
    btn.disabled = true;
    btnLabel.textContent = '验证中…';
    try {
      var password = el('password').value;
      var totpCode = el('totp-code').value.trim();
      var r = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: el('email').value, password: password, totpCode: totpCode })
      });
      // 8.47 修复：与 api() 一致先取文本再容错解析——网关 502/HTML 时 r.json() 抛
      // SyntaxError，错误信息对管理员是乱码
      var rtext = await r.text();
      var d = null;
      if (rtext) { try { d = JSON.parse(rtext); } catch (e) { d = null; } }
      // 9.3xx：TOTP 二次验证——开启 2FA 的主管理员此前永远登不上（后台不处理 requiresTotp）
      if (r.ok && d && d.requiresTotp) {
        el('totp-field').classList.remove('hidden');
        el('totp-code').focus();
        el('login-error').textContent = '';
        btn.disabled = false;
        btnLabel.textContent = '验证并登录';
        return;
      }
      if (!r.ok || !d || !d.token) {
        if (r.status === 401) throw new Error((d && d.error) || '邮箱或密码错误');
        if (r.status === 403) throw new Error((d && d.error) || '该账号不是主管理员。MASTER_ADMINS 配的是用户 ID，不是邮箱。');
        throw new Error((d && d.error) || ('请求失败 ' + r.status));
      }

      var sr = await fetch('/api/admin/session', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + d.token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: password })
      });
      var srtext = await sr.text();
      var sd = null;
      if (srtext) { try { sd = JSON.parse(srtext); } catch (e) { sd = null; } }
      if (!sr.ok || !sd || !sd.token) {
        if (sr.status === 403) throw new Error((sd && sd.error) || '该账号不是主管理员。MASTER_ADMINS 配的是用户 ID，不是邮箱。');
        throw new Error((sd && sd.error) || ('管理员二次验证失败 ' + sr.status));
      }

      token = sd.token;
      sessionExpiresAt = sd.expiresAt;
      startSessionClock();
      el('login').classList.add('hidden');
      el('app').classList.remove('hidden');
      el('password').value = '';
      el('totp-code').value = '';
      password = '';
      await loadTab();
      loadNavBadges();
    } catch (x) {
      token = '';
      el('password').value = '';
      el('totp-code').value = '';
      el('login-error').textContent = x.message;
    } finally {
      btn.disabled = false;
      if (el('totp-field') && !el('totp-field').classList.contains('hidden') && !token) {
        btnLabel.textContent = '验证并登录';
      } else {
        btnLabel.textContent = '登录';
      }
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
    online: '在线用户',
    ranking: '活跃排行',
    content: '内容',
    announcements: '公告',
    chats: '群聊管理',
    posts: '动态管理',
    comments: '评论管理',
    moderation: '审核',
    reports: '举报审核',
    risk: '风控',
    rules: '风控规则',
    'risk-events': '风控事件',
    'ai-usage': 'AI 审计',
    'push-tokens': '推送令牌',
    system: '系统',
    diagnostics: '诊断',
    audit: '操作审计'
  };

  el('nav').onclick = function (e) {
    var b = e.target.closest('button[data-tab]');
    if (!b) return;
    activeTab = b.dataset.tab;
    if (activeTab === 'content') paneState.content = 'posts';
    if (activeTab === 'diagnostics') paneState.diagnostics = 'ai-usage';
    if (activeTab === 'system') paneState.system = 'settings';
    if (activeTab === 'risk') paneState.risk = 'events';
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
    var stayOnB6 = activeTab === 'announcements' ||
      (activeTab === 'system' && B6_SYSTEM_PANES[paneState.system]);
    if (!stayOnB6 && window.__b6Admin && typeof window.__b6Admin.clearTab === 'function') {
      window.__b6Admin.clearTab();
    }
    // B6 专属 pane（announcements + system/user-tags|rate-limit|device-consistency）
    // 一律由本函数分发到 __b6Admin.openTab，禁止 B6 再绑一份 #nav click（双 spinner / 空白竞态）。
    var ownTabs = ['dashboard', 'ranking', 'online', 'users', 'content', 'chats', 'messages', 'posts', 'comments', 'moderation', 'reports', 'risk', 'rules', 'risk-events', 'storage', 'ai-usage', 'push-tokens', 'system', 'diagnostics', 'audit', 'watermark', 'bots', 'settings', 'announcements'];
    if (ownTabs.indexOf(activeTab) < 0) return;
    el('content').innerHTML = '<div class="loading-state"><div class="spinner"></div><span>加载中…</span></div>';
    try {
      switch (activeTab) {
        case 'dashboard': await loadDashboard(seq); break;
        case 'ranking': await loadRanking(seq); break;
        case 'online': await loadOnline(seq); break;
        case 'announcements':
          if (window.__b6Admin && typeof window.__b6Admin.openTab === 'function') {
            window.__b6Admin.openTab('announcements', seq);
            return;
          }
          break;
        case 'users': await loadUsers(seq); break;
        case 'content':
          if (paneState.content === 'comments') await loadComments(seq);
          else if (paneState.content === 'chats') await loadChats(seq);
          else if (paneState.content === 'messages') await loadMessageSearch(seq);
          else await loadPosts(seq);
          break;
        case 'posts': await loadPosts(seq); break;
        case 'chats': await loadChats(seq); break;
        case 'messages': await loadMessageSearch(seq); break;
        case 'comments': await loadComments(seq); break;
        case 'moderation':
        case 'reports': await loadReports(seq); break;
        case 'risk':
          if (paneState.risk === 'rules') await loadRules(seq);
          else await loadRiskEvents(seq);
          break;
        case 'rules': await loadRules(seq); break;
        case 'diagnostics':
          if (paneState.diagnostics === 'push-tokens') await loadPushTokens(seq);
          else if (paneState.diagnostics === 'storage') await loadStorage(seq);
          else if (paneState.diagnostics === 'watermark') await loadWatermark(seq);
          else await loadAiUsage(seq);
          break;
        case 'risk-events': await loadRiskEvents(seq); break;
        case 'storage': await loadStorage(seq); break;
        case 'ai-usage': await loadAiUsage(seq); break;
        case 'push-tokens': await loadPushTokens(seq); break;
        case 'audit': await loadAudit(seq); break;
        case 'watermark': await loadWatermark(seq); break;
        case 'bots': await loadBots(seq); break;
        case 'system':
          if (paneState.system === 'bots') await loadBots(seq);
          else if (paneState.system === 'channels') await loadChannelHealth(seq);
          else if (B6_SYSTEM_PANES[paneState.system] && window.__b6Admin && typeof window.__b6Admin.openTab === 'function') {
            window.__b6Admin.openTab(paneState.system, seq);
            return;
          } else await loadSettings(seq);
          break;
        case 'settings': await loadSettings(seq); break;
      }
    } catch (x) {
      if (seq !== loadSeq) return;
      el('content').innerHTML = '<div class="empty-state"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.25"/><path d="M12 11v5M12 8h.01"/></svg><p>' + esc(x.message) + '</p></div>';
    }
    if (seq === loadSeq) attachSubtabs();
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
    var fs = filterState[kind] || '';
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
      if (filter) filterState[kind] = filter.value;
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

    function healthCard(name, on, sub) {
      return '<div class="health-card ' + (on ? 'on' : 'off') + '">' +
        '<span class="health-dot ' + (on ? 'on' : 'off') + '"></span>' +
        '<div class="health-copy"><div class="health-name">' + esc(name) + '</div>' +
        '<div class="health-sub">' + esc(sub || (on ? '已配置' : '未配置')) + '</div></div></div>';
    }
    var statsHtml = '<div class="dash-toolbar"><div class="dash-section-title" style="margin:0">运营总览</div>' +
      '<button class="btn btn-primary" data-action="admin-broadcast">广播给在线用户</button></div>' +
      '<div class="dash-section"><div class="kpi-grid">' +
      statCard('users', '总用户', d.totalUsers, d.activeUsers24h + ' 人 24h 活跃', 'green') +
      statCard('online', '在线', s.onlineUsers, '当前在线', 'green') +
      statCard('messages', '消息', s.totalMessages, '已发送', 'blue') +
      statCard('chats', '群聊', s.totalGroups, '共 ' + s.totalChats + ' 个会话', 'green') +
      statCard('posts', '动态', d.totalPosts, s.totalComments + ' 条评论', 'blue') +
      statCard('reports', '待审举报', d.pendingReports, '共 ' + d.totalReports + ' 条', d.pendingReports > 0 ? 'red' : '') +
      statCard('storage', '附件', fmtBytes(s.attachmentStorageBytes), s.totalAttachments + ' 个文件', '') +
      statCard('rules', '风控规则', d.activeModerationRules, '已启用', 'orange') +
      (ops ? statCard('chats', '机器人', (ops.botsEnabled || 0) + '/' + (ops.botsTotal || 0), (ops.botsWithWebhook || 0) + ' webhooks', 'blue') : '') +
      (ops ? statCard('posts', '投票', (ops.pollsOpen || 0) + '/' + (ops.pollsTotal || 0), (ops.pollVotes || 0) + ' 票', 'green') : '') +
      '</div></div>';
    var health = null;
    try { health = await api('/api/admin/channel-health'); } catch (e) { health = null; }
    statsHtml += '<div class="dash-section"><div class="dash-section-title">通道健康</div><div class="health-grid">' +
      healthCard('OpenAI', !!(health && health.openaiConfigured), health ? (health.openaiModel || '模型') : '探测失败') +
      healthCard('TURN', !!(health && health.turnConfigured), health ? ((health.turnUrlCount || 0) + ' urls') : '探测失败') +
      healthCard('SMTP', !!(health && health.smtpConfigured), health ? (health.smtpHostMasked || 'host') : '探测失败') +
      healthCard('JWT', !!(health && health.jwtConfigured), health ? 'secret ≥32' : '探测失败') +
      '</div></div>';
    if (sec && sec.flags) {
      var f = sec.flags || {};
      var lim = sec.limits || {};
      statsHtml += '<div class="dash-section"><div class="dash-section-title">安全开关</div><div class="health-grid">' +
        healthCard('客户端 AI 入口', !!f.aiEnabled, 'runtime kill switch，不是云端聊天推理') +
        healthCard('机器人平台', !!f.botsAllowed, 'allow_bots') +
        healthCard('密封发送者', !!f.sealedSenderEnabled, 'certificates') +
        healthCard('待审风控', !(sec.openRiskEvents > 0), (sec.openRiskEvents || 0) + ' needs_review') +
        healthCard('维护模式', !f.maintenanceMode, f.registrationOpen ? '开放注册' : '关闭注册') +
        healthCard('截屏告警', f.captureAlertEnabled !== false, 'max bots/user ' + (lim.maxBotsPerUser || '-')) +
        '</div></div>';
    }


    var chartHtml = '<div class="dash-section"><div class="dash-section-title">近 7 天</div><div class="chart-row">' +
      chartCard('新增用户', t.newUsers, '#6366f1') +
      chartCard('消息量', t.newMessages, '#10b981') +
      chartCard('动态量', t.newPosts, '#f59e0b') +
      '</div></div>';

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
    var broadcastBtn = el('content').querySelector('[data-action="admin-broadcast"]');
    if (broadcastBtn) broadcastBtn.onclick = adminBroadcast;
  }

  function statCard(icon, label, value, sub, color) {
    var icons = {
      users: '<circle cx="12" cy="8" r="3.25"/><path d="M5.5 19c.7-3.2 3.3-5 6.5-5s5.8 1.8 6.5 5"/>',
      posts: '<path d="M7 4.5h10A1.5 1.5 0 0118.5 6v12l-4-1.6-4 1.6V6A1.5 1.5 0 017 4.5z"/><path d="M9.5 9h5M9.5 12.5h5"/>',
      reports: '<path d="M8 4.5h8.5A1.5 1.5 0 0118 6v13.5l-6-2.2-6 2.2V6A1.5 1.5 0 017.5 4.5H8z"/><path d="M9.5 11.5l1.8 1.8 3.4-3.6"/>',
      rules: '<path d="M12 3.5l7.5 3v5.2c0 4.3-3 7.4-7.5 8.8-4.5-1.4-7.5-4.5-7.5-8.8V6.5l7.5-3z"/>',
      messages: '<path d="M5 6.5A2.5 2.5 0 017.5 4h9A2.5 2.5 0 0119 6.5v7A2.5 2.5 0 0116.5 16H11l-4 3.5V16H7.5A2.5 2.5 0 015 13.5v-7z"/>',
      chats: '<path d="M5 7.5A2 2 0 017 5.5h9A2 2 0 0118 7.5v6A2 2 0 0116 15.5H10l-3.5 2.6V15.5H7A2 2 0 015 13.5v-6z"/>',
      storage: '<rect x="4.5" y="5" width="15" height="14" rx="1.8"/><path d="M8 5V4h8v1M8 11h8"/>',
      online: '<circle cx="12" cy="12" r="2.25"/><path d="M8.2 8.2a5.4 5.4 0 000 7.6M15.8 8.2a5.4 5.4 0 010 7.6"/>'
    };
    return '<div class="stat-card">' +
      '<div class="stat-icon ' + (color || '') + '"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">' + (icons[icon] || icons.users) + '</svg></div>' +
      '<div class="stat-meta">' +
      '<div class="stat-label">' + esc(label) + '</div>' +
      '<div class="stat-value">' + esc(value) + '</div>' +
      '<div class="stat-sub">' + esc(sub) + '</div>' +
      '</div></div>';
  }

  function chartCard(title, points, color) {
    if (!points || points.length === 0) {
      return '<div class="chart-card"><h3>' + esc(title) + '</h3><div class="chart-empty">暂无数据</div></div>';
    }
    var max = Math.max.apply(null, points.map(function (p) { return Number(p.value) || 0; }));
    if (max === 0) max = 1;
    var w = 360, h = 148, padL = 8, padR = 8, padT = 10, padB = 24;
    var innerW = w - padL - padR;
    var innerH = h - padT - padB;
    var step = innerW / (points.length - 1 || 1);
    function xy(p, i) {
      return {
        x: padL + i * step,
        y: padT + innerH - ((Number(p.value) || 0) / max) * innerH
      };
    }
    var path = points.map(function (p, i) {
      var pt = xy(p, i);
      return (i === 0 ? 'M' : 'L') + pt.x.toFixed(1) + ',' + pt.y.toFixed(1);
    }).join(' ');
    var last = xy(points[points.length - 1], points.length - 1);
    var first = xy(points[0], 0);
    var areaPath = path + ' L' + last.x.toFixed(1) + ',' + (padT + innerH).toFixed(1) +
      ' L' + first.x.toFixed(1) + ',' + (padT + innerH).toFixed(1) + ' Z';
    var labelEvery = Math.max(1, Math.ceil(points.length / 4));
    var labels = points.map(function (p, i) {
      if (i !== 0 && i !== points.length - 1 && i % labelEvery !== 0) return '';
      var d = new Date(p.timestamp);
      var pt = xy(p, i);
      var anchor = i === 0 ? 'start' : (i === points.length - 1 ? 'end' : 'middle');
      return '<text class="chart-label" x="' + pt.x.toFixed(1) + '" y="' + (h - 6) + '" text-anchor="' + anchor + '">' +
        (d.getMonth() + 1) + '/' + d.getDate() + '</text>';
    }).join('');
    var vals = points.map(function (p, i) {
      var pt = xy(p, i);
      return '<circle class="chart-dot" cx="' + pt.x.toFixed(1) + '" cy="' + pt.y.toFixed(1) + '" r="3"><title>' +
        esc(p.value) + '</title></circle>';
    }).join('');
    return '<div class="chart-card" style="color:' + color + '"><h3>' + esc(title) + '</h3>' +
      '<svg class="chart-svg" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="xMidYMid meet" role="img" aria-label="' + esc(title) + '">' +
      '<line class="chart-axis" x1="' + padL + '" y1="' + (padT + innerH) + '" x2="' + (w - padR) + '" y2="' + (padT + innerH) + '"/>' +
      '<path class="chart-area" d="' + areaPath + '"/>' +
      '<path class="chart-line" d="' + path + '"/>' +
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
    var st = filterState.users || '';
    var offset = (page.users || 0) * pageSize;
    var url = '/api/admin/users?limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&q=' + encodeURIComponent(q);
    if (st) url += '&status=' + st;
    var rows = asList(await api(url));

    var filters = '<option value="">全部（不含已注销）</option>' +
      '<option value="active"' + (st === 'active' ? ' selected' : '') + '>正常</option>' +
      '<option value="banned"' + (st === 'banned' ? ' selected' : '') + '>已封禁</option>' +
      '<option value="online"' + (st === 'online' ? ' selected' : '') + '>在线</option>' +
      '<option value="deleted"' + (st === 'deleted' ? ' selected' : '') + '>已注销</option>';

    var deletedHint = st === 'deleted'
      ? '<p class="panel-sub" style="margin:0 16px 12px">已注销账号不可恢复。资料匿名化为「已注销用户」，登录凭据与本机密钥作废；对方会话里的密文仍保留在服务端（管理员看不到明文）；动态/好友/设备/Signal 密钥已删除；审计日志保留。</p>'
      : '<p class="panel-sub" style="margin:0 16px 12px">默认列表不含已注销账号。筛选「已注销」可查看匿名化记录。停用不可逆，不会把聊天明文留给后台。</p>';

    var html = '<div class="panel">' +
      '<div class="panel-header">' +
      '<h2>用户管理</h2>' +
      searchBar('users', '搜索用户名或邮箱…', filters) +
      '<button class="btn btn-ghost btn-sm" id="users-export-btn">导出 CSV</button>' +
      '</div>' + deletedHint +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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

        var actions = '<button class="btn btn-ghost btn-sm" data-detail="' + esc(u.id) + '">详情</button>';
        if (!u.deletedAt) {
          actions += '<button class="btn btn-ghost btn-sm" data-ban="' + esc(u.id) + '">封禁/解封</button>';
          actions += '<button class="btn btn-ghost btn-sm" data-post-restrict="' + esc(u.id) + '">禁动态</button>';
          actions += '<button class="btn btn-ghost btn-sm" data-message-restrict="' + esc(u.id) + '">禁言</button>';
          actions += '<button class="btn btn-danger btn-sm" data-deactivate="' + esc(u.id) + '">停用</button>';
        }

        html += '<tr>' +
          '<td><div class="cell-main">' + esc(u.name) + '</div><div class="cell-id">' + esc(u.id) + '</div></td>' +
          '<td>' + esc(u.email) + '</td>' +
          '<td>' + status + '</td>' +
          '<td>' + esc(timeAgo(u.lastActiveAt)) + '</td>' +
          '<td><div class="btn-row">' + actions + '</div></td>' +
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
      detailItem('是否审核员', d.isModerator ? '是（手机端审批）' : '否') +
      '</div></div>' +
      '<div class="detail-section"><h4>活动统计</h4><div class="detail-grid">' +
      detailItem('消息数', d.messageCount) +
      detailItem('动态数', d.postCount) +
      detailItem('评论数', d.commentCount) +
      detailItem('群聊数', d.chatCount) +
      detailItem('推送令牌', d.pushTokenCount) +
      detailItem('相关举报', d.reportCount) +
      '</div></div>' +
      (d.deletedAt
        ? '<div class="detail-section"><h4>注销与数据保留</h4>' +
          '<p class="panel-sub">注销时间：' + esc(date(d.deletedAt)) + '。此操作不可恢复、不可重新启用。</p>' +
          '<ul class="panel-sub" style="padding-left:18px;margin:8px 0">' +
          '<li>资料已匿名化为「已注销用户」，邮箱替换为内部占位，密码随机化。</li>' +
          '<li>登录会话、推送令牌、好友关系、动态、设备、Signal 密钥已删除。</li>' +
          '<li>仍留在其他会话里的消息是密文元数据，后台看不到明文，也不能代为解密。</li>' +
          '<li>管理审计日志保留，供事后追查。</li>' +
          '</ul>' +
          '<p class="panel-sub">已注销账号不可再封禁 / 禁言 / 强制下线 / 改角色。</p></div>'
        : '<div class="detail-section"><h4>角色</h4>' +
          '<p class="panel-sub">审核员不是进这个网页后台。授予后，对方用 App 登录 → 设置 → 「审核与风控」，处理用户举报、风险事件、规则。站长（MASTER_ADMINS）在网页后台做封禁/广播/系统开关。</p>' +
          '<div class="btn-row" style="margin-top:10px">' +
          (d.isModerator
            ? '<button class="btn btn-ghost" data-ua="revoke-mod" data-user-id="' + esc(d.id) + '">撤销审核员</button>'
            : '<button class="btn btn-primary" data-ua="grant-mod" data-user-id="' + esc(d.id) + '">授予审核员</button>') +
          '</div></div>' +
          '<div class="detail-section"><h4>安全操作</h4>' +
          '<div class="btn-row" style="margin-top:8px">' +
          '<button class="btn btn-danger" data-ua="force-logout" data-user-id="' + esc(d.id) + '">强制下线</button>' +
          '<button class="btn" data-ua="sessions" data-user-id="' + esc(d.id) + '">会话</button>' +
          '<button class="btn btn-danger" data-ua="msg-restrict" data-user-id="' + esc(d.id) + '">禁言</button>' +
          '<button class="btn" data-ua="disable-totp" data-user-id="' + esc(d.id) + '">关闭 TOTP</button>' +
          '</div></div>');

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
    var q = (searchQuery.messages || '');
    var html = '<div class="panel"><div class="panel-header"><h2>消息元数据检索</h2></div><div class="panel-body">' +
      '<p style="color:var(--text-muted);font-size:13px">端到端加密载荷不可读。可按消息 ID、类型、系统/拍一拍文本、会话 ID 或发送者 ID 检索元数据。</p>' +
      '<div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px">' +
      '<input id="msg-q" placeholder="关键词 / ID / 类型" value="' + esc(q) + '" style="flex:1;min-width:160px"/>' +
      '<input id="msg-chat" placeholder="会话 ID" style="width:180px"/>' +
      '<input id="msg-user" placeholder="发送者 ID" style="width:180px"/>' +
      '<button class="btn btn-primary" id="msg-search-btn">搜索</button></div>' +
      '<div id="msg-results"><div class="empty-state"><p>输入筛选条件后搜索</p></div></div></div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    async function run() {
      var qq = (el('msg-q').value || '').trim();
      var chatId = (el('msg-chat').value || '').trim();
      var userId = (el('msg-user').value || '').trim();
      searchQuery.messages = qq;
      if (!qq && !chatId && !userId) { toast('请填写关键词、会话 ID 或发送者 ID', 'error'); return; }
      var url = '/api/admin/messages/search?limit=50&offset=0';
      if (qq) url += '&q=' + encodeURIComponent(qq);
      if (chatId) url += '&chatId=' + encodeURIComponent(chatId);
      if (userId) url += '&userId=' + encodeURIComponent(userId);
      try {
        var data = await api(url);
        var items = asList(data);
        var table = '<div class="table-wrap"><table class="table"><thead><tr><th>时间</th><th>会话</th><th>发送者</th><th>类型</th><th>预览</th><th>标记</th></tr></thead><tbody>';
        if (!items.length) table += '<tr><td colspan="6"><div class="empty-state"><p>暂无数据</p></div></td></tr>';
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
        toast('搜索失败: ' + (e && e.message ? e.message : e), 'error');
      }
    }
    el('msg-search-btn').onclick = run;
    el('msg-q').onkeydown = function (e) { if (e.key === 'Enter') run(); };
  }

async function loadChats(seq) {
    var q = searchQuery.chats || '';
    var offset = (page.chats || 0) * pageSize;
    var url = '/api/admin/chats?groupOnly=true&limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&q=' + encodeURIComponent(q);
    var rows = asList(await api(url));

    var html = '<div class="panel">' +
      '<div class="panel-header"><h2>群聊管理</h2>' + searchBar('chats', '搜索群名称…') + '<button class="btn btn-ghost btn-sm" id="chats-export-btn">导出群聊 CSV</button>' +
      '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
      '<thead><tr><th>群名称</th><th>类型</th><th>成员数</th><th>最后活动</th><th>操作</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="5"><div class="empty-state"><p>暂无群聊数据</p></div></td></tr>';
    } else {
      rows.forEach(function (c) {
        var typeBadge = c.chatType === 'CHANNEL' ? '<span class="badge badge-purple">频道</span>' : '<span class="badge badge-blue">群聊</span>';
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
    var st = filterState.posts || '';
    var offset = (page.posts || 0) * pageSize;
    var url = '/api/admin/posts?limit=' + pageSize + '&offset=' + offset;
    if (q) url += '&q=' + encodeURIComponent(q);
    if (st) url += '&status=' + st;
    var rows = asList(await api(url));

    var filters = '<option value="">全部状态</option>' +
      '<option value="PUBLISHED"' + (st === 'PUBLISHED' ? ' selected' : '') + '>已发布</option>' +
      '<option value="HELD"' + (st === 'HELD' ? ' selected' : '') + '>已暂扣</option>' +
      '<option value="DELETED"' + (st === 'DELETED' ? ' selected' : '') + '>已删除</option>';

    var html = '<div class="panel"><div class="panel-header"><h2>动态管理</h2>' + searchBar('posts', '搜索动态内容…', filters) + '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
    var rows = asList(await api(url));

    var html = '<div class="panel"><div class="panel-header"><h2>评论管理</h2>' + searchBar('comments', '搜索评论内容或作者…') + '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
    var st = filterState.reports || 'OPEN';
    var offset = (page.reports || 0) * pageSize;
    var url = '/api/admin/reports?limit=' + pageSize + '&offset=' + offset;
    if (st) url += '&status=' + st;
    var rows = asList(await api(url));

    var filters = '<option value="">全部</option>' +
      '<option value="OPEN"' + (st === 'OPEN' ? ' selected' : '') + '>待处理</option>' +
      '<option value="RESOLVED"' + (st === 'RESOLVED' ? ' selected' : '') + '>已处置</option>' +
      '<option value="REJECTED"' + (st === 'REJECTED' ? ' selected' : '') + '>已驳回</option>';

    var html = '<div class="panel"><div class="panel-header"><h2>举报审核</h2>' +
      '<div class="toolbar"><select class="filter-select" id="filter-reports">' + filters + '</select></div></div>' +
      '<p class="panel-sub" style="padding:0 18px">这里是站长网页后台。审核员不进这个页面：对方用 App 登录 → 设置 → 「审核与风控」。聊天密文后台看不到，消息类举报请让审核员在 App 里处理。</p>' +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
          (r.status === 'OPEN' ? '<div class="btn-row"><button class="btn btn-primary btn-sm" data-report-action="' + esc(r.id) + '">执行</button><button class="btn btn-ghost btn-sm" data-report-reject="' + esc(r.id) + '">驳回</button></div>' : '—') +
          '</div></div></td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('reports', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;

    var filterEl = el('filter-reports');
    if (filterEl) filterEl.onchange = function () { filterState.reports = filterEl.value; page.reports = 0; loadReports(); };
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
    var rows = asList(await api('/api/admin/moderation-rules'));
    var byId = {};
    rows.forEach(function (r) { byId[r.id] = r; });

    var html = '<div class="panel">' +
      '<div class="panel-header"><h2>风控规则</h2></div>' +
      '<div class="panel-body">' +
      '<p class="panel-sub">规则只扫动态和评论，<strong>不扫聊天密文</strong>。命中后会出现在「待处理事件」，可封禁 / 禁言 / 删内容。</p>' +
      '<form id="rule-form" class="form-grid">' +
      '<div class="form-field"><label>规则名称</label><input id="rule-name" placeholder="如：广告引流" required/></div>' +
      '<div class="form-field"><label>范围</label><select id="rule-scope"><option value="ALL">动态+评论</option><option value="POST">仅动态</option><option value="COMMENT">仅评论</option></select></div>' +
      '<div class="form-field"><label>怎么匹配</label><select id="rule-type"><option value="KEYWORD">关键词</option><option value="URL">链接</option><option value="REGEX">正则</option><option value="FREQUENCY">刷屏次数</option></select></div>' +
      '<div class="form-field"><label>匹配内容</label><input id="rule-pattern" placeholder="关键词、URL 或正则" required/></div>' +
      '<div class="form-field"><label>命中后</label><select id="rule-action"><option value="WARN_MOD">进待审队列</option><option value="AUTO_HOLD">扣留内容</option><option value="AUTO_DELETE">自动删除</option><option value="AUTO_RATE_LIMIT">自动限流</option></select></div>' +
      '<div class="form-field"><label>优先级（数字越小越先）</label><input id="rule-priority" type="number" value="100" min="0" max="10000"/></div>' +
      '<div class="form-actions"><button id="rule-submit" class="btn btn-primary btn-sm" type="submit">新增规则</button>' +
      '<button id="rule-cancel" class="btn btn-ghost btn-sm hidden" type="button">取消编辑</button></div>' +
      '</form>' +
      '<div class="table-wrap"><table class="table">' +
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
          '<td><div class="btn-row">' +
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
    var pendingOnly = filterState['risk-events'] === 'true';
    var offset = (page['risk-events'] || 0) * pageSize;
    var url = '/api/admin/risk-events?limit=' + pageSize + '&offset=' + offset;
    if (pendingOnly) url += '&pending=true';
    var rows = asList(await api(url));

    var filters = '<option value="">全部事件</option>' +
      '<option value="true"' + (pendingOnly ? ' selected' : '') + '>仅待处理</option>';

    var html = '<div class="panel"><div class="panel-header"><h2>待处理风控</h2>' +
      '<div class="toolbar"><select class="filter-select" id="filter-risk-events">' + filters + '</select></div></div>' +
      '<p class="panel-sub" style="padding:0 18px">默认只看待审。点「已处理」只消队列；封禁 7 天 / 禁言 1 天会立刻踢下线或限制发消息。</p>' +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
      '<thead><tr><th>用户</th><th>来源</th><th>动作</th><th>匹配</th><th>状态</th><th>时间</th><th>操作</th></tr></thead><tbody>';

    if (rows.length === 0) {
      html += '<tr><td colspan="7"><div class="empty-state"><p>暂无风控事件</p></div></td></tr>';
    } else {
      rows.forEach(function (r) {
        var statusBadge = r.needsReview ? '<span class="badge badge-red">待处理</span>' : '<span class="badge badge-green">已处理</span>';
        var sourceBadge = r.source === 'POST' ? '<span class="badge badge-blue">动态</span>' :
          r.source === 'COMMENT' ? '<span class="badge badge-purple">评论</span>' :
            r.source === 'MESSAGE' ? '<span class="badge">消息</span>' : '<span class="badge">' + esc(r.source) + '</span>';
        var aiBadge = r.ruleId === 'rule_ai_content' ? ' <span class="badge badge-orange">AI</span>' : '';
        html += '<tr>' +
          '<td><span class="cell-id">' + esc(r.userId) + '</span></td>' +
          '<td>' + sourceBadge + aiBadge + '</td>' +
          '<td><span class="badge badge-orange">' + esc(r.action) + '</span></td>' +
          '<td style="max-width:200px">' + esc(r.matched || '—') + '</td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' + esc(date(r.createdAt)) + '</td>' +
          '<td>' + (r.needsReview
            ? '<div class="btn-row">' +
              '<button class="btn btn-primary btn-sm" data-risk-resolve="' + esc(r.id) + '">已处理</button>' +
              '<button class="btn btn-danger btn-sm" data-risk-ban="' + esc(r.userId) + '">封禁</button>' +
              '<button class="btn btn-ghost btn-sm" data-risk-mute="' + esc(r.userId) + '">限流禁言</button>' +
              (r.source === 'POST' && r.referenceId ? '<button class="btn btn-ghost btn-sm" data-risk-del-post="' + esc(r.referenceId) + '">删动态</button>' : '') +
              (r.source === 'COMMENT' && r.referenceId ? '<button class="btn btn-ghost btn-sm" data-risk-del-comment="' + esc(r.referenceId) + '">删评论</button>' : '') +
              '</div>'
            : '—') + '</td>' +
          '</tr>';
      });
    }

    html += '</tbody></table></div>' + pager('risk-events', rows.length) + '</div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;

    var filterEl = el('filter-risk-events');
    if (filterEl) filterEl.onchange = function () { filterState['risk-events'] = filterEl.value; page['risk-events'] = 0; loadRiskEvents(); };
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
    document.querySelectorAll('[data-risk-ban]').forEach(function (b) {
      b.onclick = async function () {
        var uid = b.dataset.riskBan;
        showConfirm('封禁 7 天', '对用户 ' + uid + ' 执行站点封禁（原因：垃圾广告）。会立刻踢下线。', 'danger', async function () {
          var until = Date.now() + 7 * 24 * 60 * 60 * 1000;
          try {
            await api('/api/admin/users/' + encodeURIComponent(uid) + '/status', {
              method: 'PUT',
              body: JSON.stringify({ bannedUntil: until, reasonCode: 'spam', note: '风控事件处置' })
            });
            toast('已封禁 7 天', 'success');
            await loadRiskEvents();
          } catch (e) { toast(e.message || '封禁失败', 'error'); }
        });
      };
    });
    document.querySelectorAll('[data-risk-mute]').forEach(function (b) {
      b.onclick = async function () {
        var uid = b.dataset.riskMute;
        showConfirm('禁言 1 天', '限制用户 ' + uid + ' 发消息 1 天（原因：刷屏）。', 'warn', async function () {
          var until = Date.now() + 24 * 60 * 60 * 1000;
          try {
            await api('/api/admin/users/' + encodeURIComponent(uid) + '/message-restriction', {
              method: 'PUT',
              body: JSON.stringify({ messageRestrictedUntil: until, reasonCode: 'spam_chat', note: '风控事件处置' })
            });
            toast('已禁言 1 天', 'success');
            await loadRiskEvents();
          } catch (e) { toast(e.message || '禁言失败', 'error'); }
        });
      };
    });
    document.querySelectorAll('[data-risk-del-post]').forEach(function (b) {
      b.onclick = async function () {
        try {
          await api('/api/admin/posts/' + encodeURIComponent(b.dataset.riskDelPost), { method: 'DELETE' });
          toast('动态已删除', 'success');
          await loadRiskEvents();
        } catch (e) { toast(e.message || '删除失败', 'error'); }
      };
    });
    document.querySelectorAll('[data-risk-del-comment]').forEach(function (b) {
      b.onclick = async function () {
        try {
          await api('/api/admin/comments/' + encodeURIComponent(b.dataset.riskDelComment), { method: 'DELETE' });
          toast('评论已删除', 'success');
          await loadRiskEvents();
        } catch (e) { toast(e.message || '删除失败', 'error'); }
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
    var rows = asList(await api(url));

    var html = '<div class="panel"><div class="panel-header"><div>' +
      '<h2>AI 使用审计</h2>' +
      '<p class="panel-sub">仅元数据（模型 / 估算 tokens / 延迟 / 状态），不展示 prompt 或聊天正文</p></div></div>' +
      '<div class="panel-body">' +
      '<div class="toolbar" style="padding:12px 18px"><input id="ai-usage-search" class="search-input" placeholder="按用户 ID 筛选" value="' + esc(q) + '"/>' +
      '<button class="btn btn-secondary" id="ai-usage-search-btn">筛选</button></div>' +
      '<div class="table-wrap"><table class="table">' +
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
    var rows = asList(await api(url));

    var html = '<div class="panel"><div class="panel-header"><h2>推送令牌管理</h2>' + searchBar('push-tokens', '按用户 ID 搜索…') + '</div>' +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
    var actionF = filterState.audit || '';
    var q = searchQuery.audit || '';
    var url = '/api/admin/audit-logs?limit=' + pageSize + '&offset=' + offset;
    if (actionF) url += '&action=' + encodeURIComponent(actionF);
    if (q) url += '&q=' + encodeURIComponent(q);
    var rows = asList(await api(url));

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
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
        '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
    var users = asList(await api('/api/admin/online?limit=500'));
    var badge = el('nav-online-badge');
    if (badge) {
      if (users.length > 0) { badge.textContent = users.length; badge.classList.remove('hidden'); }
      else badge.classList.add('hidden');
    }

    var html = '<div class="panel"><div class="panel-header"><h2>在线用户</h2>' +
      '<div class="toolbar"><span class="muted">' + users.length + ' 人在线</span>' +
      '<button class="btn btn-ghost btn-sm" id="online-refresh">刷新</button></div></div>' +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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

  function resetModalChrome() {
    el('modal-box').classList.remove('wide');
    el('modal-input-wrap').classList.add('hidden');
    el('modal-select-wrap').classList.add('hidden');
    var formWrap = el('modal-form-wrap');
    if (formWrap) {
      formWrap.classList.add('hidden');
      formWrap.innerHTML = '';
    }
  }

  function showConfirm(title, body, type, callback) {
    modalCallback = callback;
    el('modal-title').textContent = title;
    el('modal-body').textContent = body;
    resetModalChrome();

    var iconWrap = el('modal-icon-wrap');
    iconWrap.className = 'modal-icon ' + (type || 'info');
    var iconPaths = {
      warn: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4.2L21 19.5H3L12 4.2z"/><path d="M12 10v4.2M12 16.8h.01"/></svg>',
      danger: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"><path d="M7 7l10 10M17 7L7 17"/></svg>',
      info: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.25"/><path d="M12 11v5M12 8h.01"/></svg>'
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
    resetModalChrome();
    el('modal-input-wrap').classList.remove('hidden');
    var input = el('modal-input');
    input.value = defaultVal || '';
    input.placeholder = placeholder || '';
    el('modal-input-hint').textContent = '';
    el('modal-confirm').textContent = '确认';
    el('modal-confirm').className = 'btn btn-primary';

    var iconWrap = el('modal-icon-wrap');
    iconWrap.className = 'modal-icon info';
    iconWrap.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.25"/><path d="M12 11v5M12 8h.01"/></svg>';

    el('modal-overlay').classList.remove('hidden');
    setTimeout(function () { input.focus(); input.select(); }, 100);
  }

  /** options: [{value, label}] */
  function showSelect(title, body, options, defaultValue, callback) {
    modalCallback = callback;
    el('modal-title').textContent = title;
    el('modal-body').textContent = body;
    resetModalChrome();
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
    iconWrap.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4.2L21 19.5H3L12 4.2z"/><path d="M12 10v4.2M12 16.8h.01"/></svg>';
    el('modal-overlay').classList.remove('hidden');
    setTimeout(function () { select.focus(); }, 100);
  }

  /** fields: [{name, label, type, value, placeholder, options, required, hint}] */
  function showForm(title, body, fields, callback) {
    modalCallback = callback;
    el('modal-title').textContent = title;
    el('modal-body').textContent = body || '';
    resetModalChrome();
    el('modal-box').classList.add('wide');
    var wrap = el('modal-form-wrap');
    wrap.classList.remove('hidden');
    wrap.innerHTML = (fields || []).map(function (f) {
      var id = 'modal-field-' + esc(f.name);
      var html = '<div class="form-field"><label class="label" for="' + id + '">' + esc(f.label || f.name) +
        (f.required ? ' *' : '') + '</label>';
      if (f.type === 'select') {
        html += '<select class="modal-select" id="' + id + '" data-field="' + esc(f.name) + '">' +
          (f.options || []).map(function (opt) {
            return '<option value="' + esc(opt.value) + '"' +
              (String(opt.value) === String(f.value) ? ' selected' : '') + '>' +
              esc(opt.label) + '</option>';
          }).join('') + '</select>';
      } else if (f.type === 'textarea') {
        html += '<textarea id="' + id + '" data-field="' + esc(f.name) + '" placeholder="' +
          esc(f.placeholder || '') + '">' + esc(f.value || '') + '</textarea>';
      } else {
        html += '<input type="' + esc(f.type || 'text') + '" id="' + id + '" data-field="' + esc(f.name) +
          '" value="' + esc(f.value || '') + '" placeholder="' + esc(f.placeholder || '') + '"/>';
      }
      if (f.hint) html += '<div class="modal-input-hint">' + esc(f.hint) + '</div>';
      html += '</div>';
      return html;
    }).join('');
    el('modal-confirm').textContent = '确认';
    el('modal-confirm').className = 'btn btn-primary';
    var iconWrap = el('modal-icon-wrap');
    iconWrap.className = 'modal-icon info';
    iconWrap.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.25"/><path d="M12 11v5M12 8h.01"/></svg>';
    el('modal-overlay').classList.remove('hidden');
    setTimeout(function () {
      var first = wrap.querySelector('input, textarea, select');
      if (first) first.focus();
    }, 100);
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
      var formWrap = el('modal-form-wrap');
      if (formWrap && !formWrap.classList.contains('hidden')) {
        var values = {};
        formWrap.querySelectorAll('[data-field]').forEach(function (node) {
          values[node.getAttribute('data-field')] = node.value;
        });
        var formResult = await cb(values);
        if (formResult === false) { el('modal-confirm').disabled = false; return; }
      } else if (!el('modal-input-wrap').classList.contains('hidden')) {
        var val = input.value;
        var result = await cb(val);
        if (result === false) { el('modal-confirm').disabled = false; return; } // callback can return false to keep modal open
      } else if (!el('modal-select-wrap').classList.contains('hidden')) {
        var selResult = await cb(select.value);
        if (selResult === false) { el('modal-confirm').disabled = false; return; }
      } else {
        var confirmResult = await cb();
        if (confirmResult === false) { el('modal-confirm').disabled = false; return; }
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




  
  async function loadChannelHealth(seq) {
    var health = await api('/api/admin/channel-health');
    function lamp(on) {
      return '<span class="health-dot ' + (on ? 'on' : 'off') + '"></span>' + (on ? '已配置' : '未配置');
    }
    var html = '<div class="panel"><div class="panel-header"><h2>密钥与通道</h2></div><div class="panel-body">' +
      '<p style="color:var(--text-muted);font-size:13px">只显示是否配置与掩码，绝不返回密钥明文。</p>' +
      '<div class="detail-grid">' +
      detailItem('OpenAI', lamp(!!health.openaiConfigured) + (health.openaiModel ? ' · ' + esc(health.openaiModel) : '')) +
      detailItem('TURN', lamp(!!health.turnConfigured) + ' · ' + (health.turnUrlCount || 0) + ' urls') +
      detailItem('SMTP', lamp(!!health.smtpConfigured) + (health.smtpHostMasked ? ' · ' + esc(health.smtpHostMasked) : '')) +
      detailItem('JWT', lamp(!!health.jwtConfigured) + ' · secret ≥32') +
      '</div></div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
  }

  async function loadSettings(seq) {
    var data = await api('/api/admin/settings');
    var s = data.settings || {};
    var def = data.defaults || {};
    function valOf(key) {
      return (s[key] != null ? s[key] : def[key]);
    }
    function isOn(key) {
      var val = valOf(key);
      return String(val).toLowerCase() === 'true' || val === '1' || val === true;
    }
    function row(key, label, type) {
      var val = valOf(key);
      if (val == null) val = '';
      if (type === 'bool') {
        var on = isOn(key);
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
    function group(title, body) {
      return '<details class="settings-group"><summary><h3>' + esc(title) + '</h3></summary>' + body + '</details>';
    }
    function opsCard(key, title, hint) {
      var on = isOn(key);
      return '<div class="ops-card ' + (on ? 'on' : 'off') + '">' +
        '<div class="ops-card-copy"><div class="ops-card-title">' + esc(title) + '</div>' +
        '<div class="ops-card-hint">' + esc(hint) + '</div>' +
        '<div class="ops-card-state">' + (on ? '当前：开' : '当前：关') + '</div></div>' +
        '<button type="button" class="btn ' + (on ? 'btn-danger' : 'btn-primary') + ' btn-sm" data-ops-toggle="' + esc(key) + '" data-ops-on="' + (on ? '1' : '0') + '">' +
        (on ? '关闭' : '开启') + '</button></div>';
    }
    var html = '<div class="panel"><div class="panel-header"><h2>运营开关</h2></div>' +
      '<div class="panel-body">' +
      '<p class="panel-sub">点卡片上的按钮立刻改一项，约 5 秒生效。不要在这里翻 100 个开关。</p>' +
      '<div class="ops-grid">' +
      opsCard('maintenance_mode', '维护模式', '开：客户端拒绝登录/注册，只显示维护文案') +
      opsCard('allow_registration', '开放注册', '关：只允许已有账号登录') +
      opsCard('media_upload_enabled', '媒体上传', '关：不能发图/视频/文件') +
      opsCard('calls_enabled', '音视频通话', '关：不能发起通话') +
      opsCard('posts_enabled', '动态 / 朋友圈', '关：发现页发帖入口关闭') +
      opsCard('ai_enabled', '客户端 AI 入口', '关：用户无法在 App 打开助手/翻译/摘要。聊天密文不进毛豆云，模型由用户在设置里自配。跟「AI 审帖」是两件事。') +
      opsCard('ai_content_moderation_enabled', 'AI 审帖（动态/评论）', '开：发动态/评论会再过一遍模型。聊天密文永远不送。未配 API Key 时不拦。命中进「风控」待审。') +
      opsCard('secret_chat_enabled', '密聊', '关：不能新建密聊会话') +
      opsCard('allow_bots', '机器人平台', '关：不能创建/调用机器人') +
      '</div>' +
      '<div class="ops-copy">' +
      '<label class="field"><div><strong>维护提示</strong></div>' +
      '<textarea data-ops-text="maintenance_message" rows="2">' + esc(valOf('maintenance_message') || '') + '</textarea></label>' +
      '<label class="field"><div><strong>全局横幅</strong>（登录后会话列表顶栏）</div>' +
      '<textarea data-ops-text="global_banner" rows="2">' + esc(valOf('global_banner') || '') + '</textarea></label>' +
      '<button class="btn btn-primary btn-sm" id="ops-copy-save" type="button">保存文案</button>' +
      '</div>' +
      '<details class="settings-advanced"><summary>高级：全部运行时 key（开发/排障用，日常不要动）</summary>' +
      '<div class="toolbar" style="margin:10px 0"><input class="search-input" id="settings-filter" placeholder="搜索开关名称或 key…"/>' +
      '<button class="btn btn-primary" id="settings-save" type="button">保存全部</button></div>' +
      '<p class="panel-sub">覆盖环境默认值。勾选 = 开。搜不到表示当前页没有这项。</p>' +
      group('接入与注册',
        row('maintenance_mode', '维护模式', 'bool') +
        row('maintenance_message', '维护提示文案', 'textarea') +
        row('allow_registration', '允许注册', 'bool') +
        row('invite_only_hint', '关闭注册时的提示', 'textarea') +
        row('global_banner', '全局横幅', 'textarea') +
        row('public_announcement', '公开公告', 'textarea') +
        row('min_app_version', '最低客户端版本号', 'text') +
        row('ip_blocklist', 'IP 黑名单（逗号或换行）', 'textarea')) +
      group('群与机器人',
        row('max_group_size', '群人数上限', 'text') +
        row('allow_bots', '开放机器人平台', 'bool') +
        row('max_bots_per_user', '每用户机器人上限', 'text') +
        row('group_play_enabled', '群玩法', 'bool') +
        row('group_invites_enabled', '群邀请', 'bool')) +
      group('消息与媒体',
        row('sealed_sender_enabled', '密封发送者证书', 'bool') +
        row('force_e2ee_banner', '强制端到端横幅文案', 'textarea') +
        row('max_message_per_min', '每用户每分钟消息上限', 'text') +
        row('media_upload_enabled', '允许媒体上传', 'bool') +
        row('image_send_enabled', '发图', 'bool') +
        row('video_send_enabled', '发视频', 'bool') +
        row('gif_send_enabled', '发 GIF', 'bool') +
        row('voice_messages_enabled', '语音消息', 'bool') +
        row('file_share_enabled', '文件分享', 'bool') +
        row('link_preview_enabled', '链接预览', 'bool') +
        row('reactions_enabled', '消息反应', 'bool') +
        row('stickers_enabled', '贴纸', 'bool') +
        row('silent_send_enabled', '静默发送', 'bool') +
        row('scheduled_messages_enabled', '定时消息', 'bool') +
        row('view_once_enabled', '阅后即焚媒体', 'bool') +
        row('live_location_enabled', '实时位置', 'bool') +
        row('static_location_enabled', '静态位置', 'bool') +
        row('spoiler_media_enabled', '剧透媒体', 'bool') +
        row('auto_download_enabled', '自动下载', 'bool') +
        row('markdown_enabled', 'Markdown 渲染', 'bool') +
        row('polls_enabled', '投票', 'bool') +
        row('mentions_enabled', '@提及', 'bool') +
        row('nudge_enabled', '拍一拍', 'bool') +
        row('message_edit_enabled', '编辑消息', 'bool') +
        row('message_pin_enabled', '置顶消息', 'bool') +
        row('message_revoke_enabled', '撤回/删除消息', 'bool') +
        row('message_forwarding_enabled', '转发消息', 'bool') +
        row('message_starring_enabled', '收藏消息', 'bool') +
        row('disappearing_messages_enabled', '限时消息', 'bool') +
        row('contact_card_enabled', '名片', 'bool')) +
      group('客户端功能',
        row('calls_enabled', '音视频通话总开关', 'bool') +
        row('voice_call_enabled', '语音通话', 'bool') +
        row('video_call_enabled', '视频通话', 'bool') +
        row('typing_indicators_enabled', '正在输入', 'bool') +
        row('read_receipts_enabled', '已读回执', 'bool') +
        row('presence_enabled', '在线状态', 'bool') +
        row('chat_export_enabled', '导出聊天', 'bool') +
        row('global_search_enabled', '全局搜索', 'bool') +
        row('friend_requests_enabled', '好友请求', 'bool') +
        row('chat_folders_enabled', '会话文件夹', 'bool') +
        row('posts_enabled', '动态 / 朋友圈', 'bool') +
        row('block_report_enabled', '拉黑与举报', 'bool') +
        row('chat_archive_enabled', '归档会话', 'bool') +
        row('nearby_enabled', '附近的人', 'bool') +
        row('chat_pin_enabled', '置顶会话', 'bool') +
        row('marked_unread_enabled', '标为未读', 'bool') +
        row('chat_mute_enabled', '会话免打扰', 'bool') +
        row('chat_lock_enabled', '会话锁', 'bool') +
        row('app_lock_enabled', '应用锁', 'bool') +
        row('chat_drafts_enabled', '会话草稿', 'bool') +
        row('safety_code_enabled', '安全码', 'bool') +
        row('qr_code_enabled', '二维码', 'bool') +
        row('chat_wallpaper_enabled', '聊天壁纸', 'bool') +
        row('chat_font_scale_enabled', '聊天字号', 'bool') +
        row('unread_priority_enabled', '未读优先', 'bool') +
        row('pqxdh_preview', 'PQXDH 预览开关', 'bool')) +
      group('AI',
        row('ai_enabled', '客户端 AI 入口（允许用户自开助手，不是云端聊天推理）', 'bool') +
        row('ai_content_moderation_enabled', 'AI 审帖（动态/评论明文）', 'bool') +
        row('ai_retry_enabled', '审帖上游重试', 'bool') +
        row('ai_daily_token_budget_per_user', '审帖每日 token 预算', 'number')) +
      group('密聊与安全',
        row('secret_chat_enabled', '密聊', 'bool') +
        row('secret_chat_required', '单聊强制密聊（客户端横幅）', 'bool') +
        row('capture_alert_enabled', '对端截屏提醒', 'bool') +
        row('screen_secure_runtime_enabled', '运行时防截屏', 'bool') +
        row('screenshot_detect_enabled', '截屏检测', 'bool') +
        row('recents_exclusion_enabled', '从最近任务排除', 'bool') +
        row('blind_watermark_enabled', '盲水印', 'bool') +
        row('secret_copy_block_enabled', '密聊禁止复制', 'bool') +
        row('secret_media_export_block_enabled', '密聊禁止导出媒体', 'bool') +
        row('secret_forward_block_enabled', '密聊禁止转发', 'bool') +
        row('secret_chat_export_block_enabled', '密聊禁止导出历史', 'bool') +
        row('secret_auto_disappear_enabled', '密聊 24 小时自动消失', 'bool') +
        row('secret_link_preview_block_enabled', '密聊禁止链接预览', 'bool') +
        row('secret_external_link_block_enabled', '密聊禁止打开外链', 'bool') +
        row('secret_notif_preview_block_enabled', '密聊通知不展示预览', 'bool') +
        row('secret_list_preview_block_enabled', '密聊列表不展示预览', 'bool') +
        row('secret_reaction_block_enabled', '密聊禁止反应', 'bool') +
        row('secret_star_block_enabled', '密聊禁止收藏', 'bool') +
        row('secret_typing_block_enabled', '密聊隐藏正在输入', 'bool') +
        row('secret_read_receipt_block_enabled', '密聊隐藏已读', 'bool') +
        row('secret_presence_block_enabled', '密聊隐藏在线', 'bool') +
        row('secret_last_seen_block_enabled', '密聊隐藏最后上线', 'bool')) +
      group('通知与体验',
        row('push_notifications_enabled', '推送总开关', 'bool') +
        row('notification_sound_enabled', '通知声音', 'bool') +
        row('notification_preview_enabled', '通知预览', 'bool') +
        row('ringtone_enabled', '铃声设置', 'bool') +
        row('task_reminders_enabled', '任务提醒', 'bool') +
        row('dnd_enabled', '免打扰时段', 'bool') +
        row('in_app_sounds_enabled', '应用内音效', 'bool') +
        row('haptics_enabled', '触感反馈', 'bool') +
        row('chat_animations_enabled', '聊天动画', 'bool') +
        row('nav_transitions_enabled', '导航转场', 'bool')) +
      '<div style="margin-top:12px;font-size:12px;color:var(--text-muted)">环境变量 allowRegistration: ' +
      esc(String(data.envAllowRegistration)) + '</div></details></div></div>';
    if (staleTab(seq)) return;
    el('content').innerHTML = html;
    async function putSettings(updates, okMsg) {
      await api('/api/admin/settings', { method: 'PUT', body: JSON.stringify({ settings: updates }) });
      toast(okMsg || '已保存', 'success');
      await loadSettings();
      attachSubtabs();
    }
    document.querySelectorAll('[data-ops-toggle]').forEach(function (b) {
      b.onclick = async function () {
        var key = b.getAttribute('data-ops-toggle');
        var next = b.getAttribute('data-ops-on') === '1' ? 'false' : 'true';
        try {
          var patch = {};
          patch[key] = next;
          await putSettings(patch, (next === 'true' ? '已开启 ' : '已关闭 ') + key);
        } catch (e) {
          toast('切换失败: ' + (e && e.message ? e.message : e), 'error');
        }
      };
    });
    var copyBtn = el('ops-copy-save');
    if (copyBtn) copyBtn.onclick = async function () {
      var updates = {};
      document.querySelectorAll('[data-ops-text]').forEach(function (n) {
        updates[n.getAttribute('data-ops-text')] = n.value;
      });
      try { await putSettings(updates, '文案已保存'); }
      catch (e) { toast('保存失败: ' + (e && e.message ? e.message : e), 'error'); }
    };
    var saveAll = el('settings-save');
    if (saveAll) saveAll.onclick = async function () {
      var updates = {};
      document.querySelectorAll('[data-setting]').forEach(function (node) {
        var key = node.getAttribute('data-setting');
        if (node.type === 'checkbox') updates[key] = node.checked ? 'true' : 'false';
        else updates[key] = node.value;
      });
      try { await putSettings(updates, '高级设置已保存'); }
      catch (e) { toast('保存失败: ' + (e && e.message ? e.message : e), 'error'); }
    };
    var filter = el('settings-filter');
    if (filter) {
      filter.oninput = function () {
        var q = String(filter.value || '').trim().toLowerCase();
        document.querySelectorAll('#content .settings-group').forEach(function (g) {
          var hit = 0;
          g.querySelectorAll('.field').forEach(function (f) {
            var t = (f.textContent || '').toLowerCase();
            var show = !q || t.indexOf(q) >= 0;
            f.style.display = show ? '' : 'none';
            if (show) hit++;
          });
          g.style.display = hit ? '' : 'none';
          if (q && hit) g.open = true;
        });
      };
    }
  }

  async function loadWatermark() {
    el('content').innerHTML =
      '<div class="panel"><div class="panel-header"><h2>水印取证</h2></div><div class="panel-body">' +
      '<p style="color:var(--text-muted);font-size:13px">上传疑似密聊截图。服务端与客户端使用同一套 DCT-QIM 算法（48-bit 用户/会话/设备哈希）。屏幕水印还会带用户 id 与时间戳。</p>' +
      '<div class="row" style="gap:12px;flex-wrap:wrap;margin:12px 0">' +
      '<input type="file" id="wm-file" accept="image/*"/>' +
      '<button class="btn btn-primary" id="wm-extract">提取水印</button>' +
      '<button class="btn btn-ghost" id="wm-selftest">自检</button>' +
      '</div>' +
      '<div id="wm-preview" class="muted">暂无图片</div>' +
      '<pre id="wm-result" class="code-block" style="margin-top:12px;white-space:pre-wrap"></pre>' +
      '</div></div>';
    var fileInput = el('wm-file');
    var result = el('wm-result');
    var preview = el('wm-preview');
    fileInput.onchange = function () {
      var f = fileInput.files && fileInput.files[0];
      preview.textContent = f ? (f.name + ' · ' + fmtBytes(f.size)) : '暂无图片';
    };
    el('wm-extract').onclick = async function () {
      var f = fileInput.files && fileInput.files[0];
      if (!f) { toast('请先选择图片', 'error'); return; }
      result.textContent = '提取中…';
      try {
        var b64 = await fileToBase64(f);
        var data = await api('/api/admin/watermark/extract', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ imageBase64: b64 })
        });
        result.textContent = JSON.stringify(data, null, 2);
        toast(data.found ? '已找到水印' : '未检测到水印');
      } catch (e) {
        result.textContent = String(e.message || e);
        toast('提取失败', 'error');
      }
    };
    el('wm-selftest').onclick = async function () {
      result.textContent = '自检中…';
      try {
        var data = await api('/api/admin/watermark/self-test');
        result.textContent = JSON.stringify({
          found: data.found,
          payloadHex: data.payloadHex,
          message: data.message,
          sampleLen: (data.samplePngBase64 || '').length
        }, null, 2);
        toast(data.found ? '自检通过' : '自检失败', data.found ? undefined : 'error');
      } catch (e) {
        result.textContent = String(e.message || e);
        toast('自检失败', 'error');
      }
    };
  }

  async function loadBots(seq) {
    el('content').innerHTML = '<div class="loading-state"><div class="spinner"></div><span>加载中…</span></div>';
    try {
      var rows = asList(await api('/api/admin/bots?limit=100'));
      var list = Array.isArray(rows) ? rows : (rows.items || rows.bots || []);
      if (!list.length) {
        if (staleTab(seq)) return;
        el('content').innerHTML = '<div class="panel"><div class="panel-header"><h2>机器人</h2></div>' +
          '<div class="panel-body"><div class="empty-state"><p>还没有机器人。开发者可在客户端开发者中心创建，或调用 POST /api/bots（管理后台不另开创建接口）。</p></div></div></div>';
        return;
      }
      var html = '<div class="card"><div class="row" style="justify-content:space-between;align-items:center;margin-bottom:8px"><h3 style="margin:0">全部机器人</h3><button class="btn btn-ghost btn-sm" id="runtime-export-btn">导出运行时 JSON</button><button class="btn btn-ghost btn-sm" id="bots-export-btn" style="margin-left:8px">导出机器人 CSV</button><button class="btn btn-ghost btn-sm" id="polls-export-btn" style="margin-left:8px">导出投票 CSV</button><button class="btn btn-ghost btn-sm" id="message-stats-export-btn" style="margin-left:8px">消息统计 CSV</button><button class="btn btn-ghost btn-sm" id="reports-export-btn" style="margin-left:8px">举报 CSV</button><button class="btn btn-ghost btn-sm" id="risk-export-btn" style="margin-left:8px">风控 CSV</button><button class="btn btn-ghost btn-sm" id="online-export-btn" style="margin-left:8px">在线 CSV</button><button class="btn btn-ghost btn-sm" id="push-tokens-export-btn" style="margin-left:8px">推送令牌 CSV</button><button class="btn btn-ghost btn-sm" id="ai-usage-export-btn" style="margin-left:8px">AI 用量 CSV</button><button class="btn btn-ghost btn-sm" id="sessions-summary-export-btn" style="margin-left:8px">会话汇总 CSV</button><button class="btn btn-ghost btn-sm" id="moderation-audit-export-btn" style="margin-left:8px">审计 CSV</button><button class="btn btn-ghost btn-sm" id="bot-command-stats-export-btn" style="margin-left:8px">Bot 指令 CSV</button><button class="btn btn-ghost btn-sm" id="friends-export-btn" style="margin-left:8px">好友 CSV</button><button class="btn btn-ghost btn-sm" id="reports-meta-export-btn" style="margin-left:8px">举报元数据 CSV</button><button class="btn btn-ghost btn-sm" id="blocks-export-btn" style="margin-left:8px">拉黑 CSV</button><button class="btn btn-ghost btn-sm" id="chat-settings-export-btn" style="margin-left:8px">会话设置 CSV</button><button class="btn btn-ghost btn-sm" id="disappearing-chats-export-btn" style="margin-left:8px">阅后即焚 CSV</button><button class="btn btn-ghost btn-sm" id="muted-chats-export-btn" style="margin-left:8px">免打扰 CSV</button><button class="btn btn-ghost btn-sm" id="pinned-messages-export-btn" style="margin-left:8px">置顶消息 CSV</button><button class="btn btn-ghost btn-sm" id="poll-votes-export-btn" style="margin-left:8px">投票明细 CSV</button><button class="btn btn-ghost btn-sm" id="restricted-users-export-btn" style="margin-left:8px">受限用户 CSV</button><button class="btn btn-ghost btn-sm" id="group-invites-export-btn" style="margin-left:8px">群邀请 CSV</button><button class="btn btn-ghost btn-sm" id="totp-users-export-btn" style="margin-left:8px">TOTP 用户 CSV</button><button class="btn btn-ghost btn-sm" id="identity-users-export-btn" style="margin-left:8px">身份密钥 CSV</button><button class="btn btn-ghost btn-sm" id="privacy-flags-export-btn" style="margin-left:8px">隐私开关 CSV</button><button class="btn btn-ghost btn-sm" id="online-presence-export-btn" style="margin-left:8px">在线状态 CSV</button><button class="btn btn-ghost btn-sm" id="ai-feature-flags-export-btn" style="margin-left:8px">AI 功能开关 CSV</button></div><table class="table"><thead><tr><th>名称</th><th>用户名</th><th>所有者</th><th>令牌前缀</th><th>Webhook</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody>';
      list.forEach(function (b) {
        html += '<tr><td>' + esc(b.name) + '</td><td>@' + esc(b.username) + '</td><td class="mono">' + esc(b.ownerUserId) + '</td><td class="mono">' + esc(b.tokenPrefix) + '</td><td>' + esc(b.webhookUrl || '-') + '</td><td>' + (b.enabled ? '启用' : '停用') + '</td><td>' + date(b.createdAt) + '</td><td>' +
          '<button class="btn btn-ghost btn-sm" data-bot-enable="' + esc(b.id) + '" data-enabled="' + (b.enabled ? '0' : '1') + '">' + (b.enabled ? '停用' : '启用') + '</button> ' +
          '<button class="btn btn-ghost btn-sm" data-bot-logs="' + esc(b.id) + '">日志</button></td></tr>';
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
          toast('已导出运行时 JSON');
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
            toast('导出失败: ' + (e && e.message ? e.message : e), 'error');
          }
        };
      }
      bindCsvExport('bots-export-btn', '/api/admin/bots-export?limit=2000', 'maodouchat-bots-', '已导出机器人 CSV');
      bindCsvExport('polls-export-btn', '/api/admin/polls-export?limit=2000', 'maodouchat-polls-', '已导出投票 CSV');
      bindCsvExport('message-stats-export-btn', '/api/admin/message-stats-export', 'maodouchat-message-stats-', '已导出消息统计 CSV');
      bindCsvExport('reports-export-btn', '/api/admin/reports-export?limit=2000', 'maodouchat-reports-', '已导出举报 CSV');
      bindCsvExport('risk-export-btn', '/api/admin/risk-events-export?limit=2000', 'maodouchat-risk-', '已导出风控 CSV');
      bindCsvExport('online-export-btn', '/api/admin/online-export', 'maodouchat-online-', '已导出在线 CSV');
      bindCsvExport('push-tokens-export-btn', '/api/admin/push-tokens-export?limit=5000', 'maodouchat-push-tokens-', '已导出推送令牌 CSV');
      bindCsvExport('ai-usage-export-btn', '/api/admin/ai-usage-export?limit=2000', 'maodouchat-ai-usage-', '已导出 AI 用量 CSV');
      bindCsvExport('sessions-summary-export-btn', '/api/admin/sessions-summary-export?limit=5000', 'maodouchat-sessions-', '已导出会话汇总 CSV');
      bindCsvExport('moderation-audit-export-btn', '/api/admin/moderation-audit-export?limit=2000', 'maodouchat-audit-', '已导出审计 CSV');
      bindCsvExport('bot-command-stats-export-btn', '/api/admin/bot-command-stats-export?limit=5000', 'maodouchat-bot-cmds-', '已导出 Bot 指令 CSV');
      bindCsvExport('friends-export-btn', '/api/admin/friends-export?limit=5000', 'maodouchat-friends-', '已导出好友 CSV');
      bindCsvExport('reports-meta-export-btn', '/api/admin/reports-meta-export?limit=5000', 'maodouchat-reports-meta-', '已导出举报元数据 CSV');
      bindCsvExport('blocks-export-btn', '/api/admin/blocks-export?limit=5000', 'maodouchat-blocks-', '已导出拉黑 CSV');
      bindCsvExport('chat-settings-export-btn', '/api/admin/chat-settings-export?limit=5000', 'maodouchat-chat-settings-', '已导出会话设置 CSV');
      bindCsvExport('disappearing-chats-export-btn', '/api/admin/disappearing-chats-export?limit=5000', 'maodouchat-disappearing-chats-', '已导出阅后即焚 CSV');
      bindCsvExport('muted-chats-export-btn', '/api/admin/muted-chats-export?limit=5000', 'maodouchat-muted-chats-', '已导出免打扰 CSV');
      bindCsvExport('pinned-messages-export-btn', '/api/admin/pinned-messages-export?limit=5000', 'maodouchat-pinned-messages-', '已导出置顶消息 CSV');
      bindCsvExport('poll-votes-export-btn', '/api/admin/poll-votes-export?limit=5000', 'maodouchat-poll-votes-', '已导出投票明细 CSV');
      bindCsvExport('restricted-users-export-btn', '/api/admin/restricted-users-export?limit=5000', 'maodouchat-restricted-users-', '已导出受限用户 CSV');
      bindCsvExport('group-invites-export-btn', '/api/admin/group-invites-export?limit=5000', 'maodouchat-group-invites-', '已导出群邀请 CSV');
      bindCsvExport('totp-users-export-btn', '/api/admin/totp-users-export?limit=5000', 'maodouchat-totp-users-', '已导出 TOTP 用户 CSV');
      bindCsvExport('identity-users-export-btn', '/api/admin/identity-users-export?limit=5000', 'maodouchat-identity-users-', '已导出身份密钥 CSV');
      bindCsvExport('privacy-flags-export-btn', '/api/admin/privacy-flags-export?limit=5000', 'maodouchat-privacy-flags-', '已导出隐私开关 CSV');
      bindCsvExport('online-presence-export-btn', '/api/admin/online-presence-export?limit=5000', 'maodouchat-online-presence-', '已导出在线状态 CSV');
      bindCsvExport('ai-feature-flags-export-btn', '/api/admin/ai-feature-flags-export', 'maodouchat-ai-feature-flags-', '已导出 AI 功能开关 CSV');

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
            toast(en ? '已启用机器人' : '已停用机器人');
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
          if (view) view.textContent = '加载日志…';
          try {
            var data = await api('/api/admin/bots/' + encodeURIComponent(id) + '/command-logs?limit=80');
            var lines = (data.logs || []).map(function (r) {
              return date(r.createdAt) + '  ' + (r.command || '') + '  chat=' + (r.chatId || '-') + ' user=' + (r.userId || '-');
            });
            if (view) view.textContent = lines.length ? lines.join('\n') : '（暂无指令日志）';
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
    showConfirm: showConfirm,
    showForm: showForm,
    ensureDispositionTemplates: ensureDispositionTemplates,
    loadUsers: loadUsers,
    // 8.48 补全：B6 模块与主模块共享同一 tab 渲染序号——主/B6 tab 互切时，
    // 任一侧的旧响应都不得覆盖另一侧的新页面
    nextTabSeq: function () { return ++loadSeq; },
    isStaleTab: staleTab,
    attachSubtabs: attachSubtabs,
    asList: asList,
    get dispositionTemplates() { return dispositionTemplates; }
  };

})();


async function adminForceLogout(userId) {
  if (!userId) return;
  window.__b6Admin.showConfirm(
    '强制下线',
    '强制下线 user ' + userId + ' on all devices?',
    'warn',
    async function () {
      try {
        await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/force-logout', { method: 'POST', body: '{}' });
        window.__b6Admin.toast('强制下线 ok');
      } catch (e) {
        window.__b6Admin.toast('强制下线 failed: ' + (e && e.message ? e.message : e));
      }
    }
  );
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
    window.__b6Admin.showPrompt(
      '用户会话',
      text + '\n\n操作：输入 session prefix 撤销一条，或 ALL 强制下线全部',
      '',
      'prefix 或 ALL',
      async function (action) {
        action = String(action || '').trim();
        if (!action) return false;
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
      }
    );
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
  window.__b6Admin.showConfirm(
    '关闭 TOTP',
    '关闭 TOTP for user ' + userId + '?',
    'warn',
    async function () {
      try {
        await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/disable-totp', { method: 'POST', body: '{}' });
        window.__b6Admin.toast('TOTP disabled');
      } catch (e) {
        window.__b6Admin.toast('关闭 TOTP failed: ' + (e && e.message ? e.message : e));
      }
    }
  );
}


async function adminBroadcast() {
  window.__b6Admin.showPrompt(
    '广播给在线用户',
    '只推当前 WebSocket 在线会话，会在客户端弹出对话框。离线用户收不到。不是公告页那套可回看的系统公告。',
    '',
    '广播正文',
    async function (text) {
      if (!text || !String(text).trim()) {
        window.__b6Admin.toast('消息不能为空', 'error');
        return false;
      }
      try {
        var res = await window.__b6Admin.api('/api/admin/broadcast', { method: 'POST', body: JSON.stringify({ text: String(text).trim(), title: '系统通知' }) });
        window.__b6Admin.toast('已推给 ' + (res.delivered || 0) + ' / ' + (res.onlineTargets || 0) + ' 个在线会话', 'success');
      } catch (e) {
        window.__b6Admin.toast('广播失败: ' + (e && e.message ? e.message : e), 'error');
      }
    }
  );
}

async function adminSetModerator(userId, enabled) {
  if (!userId) return;
  var title = enabled ? '授予审核员' : '撤销审核员';
  var body = enabled
    ? '对方不会进这个网页后台。用 App 登录后，设置里会出现「审核与风控」，用来处理举报和风控队列。'
    : '撤销后，对方 App 设置里不再出现「审核与风控」。';
  window.__b6Admin.showConfirm(title, body, enabled ? 'info' : 'warn', async function () {
    try {
      await window.__b6Admin.api('/api/admin/users/' + encodeURIComponent(userId) + '/moderator', {
        method: 'PUT',
        body: JSON.stringify({ enabled: !!enabled })
      });
      window.__b6Admin.toast(enabled ? '已授予审核员（对方在 App 设置里审批）' : '已撤销审核员', 'success');
      window.__b6Admin.el('drawer-overlay').classList.add('hidden');
      await window.__b6Admin.loadUsers();
    } catch (e) {
      window.__b6Admin.toast('更新失败: ' + (e && e.message ? e.message : e), 'error');
    }
  });
}

/* ═══════════════════════════════════════════════════════
   B6 服务端运维增强 — 公告广播 / 用户标签 / 限流仪表盘 / 设备一致性
   （纯追加模块，自包含；通过 window.__b6Admin 访问主闭包 API）
   ═══════════════════════════════════════════════════════ */
(function () {
  'use strict';
  var H = window.__b6Admin;
  var api = H.api, toast = H.toast, esc = H.esc, date = H.date, asList = H.asList;
  var el = H.el;
  var showConfirm = H.showConfirm, showPrompt = H.showPrompt, showForm = H.showForm, showSelect = H.showSelect;
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
    el('content').innerHTML = '<div class="empty-state"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.25"/><path d="M12 11v5M12 8h.01"/></svg><p>' + esc(x && x.message ? x.message : x) + '</p></div>';
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
    var rows = asList(await api(url));

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
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
        showConfirm('取消公告', '确认取消公告 ' + b.dataset.b6Cancel + '？', 'warn', function () {
          return api('/api/admin/announcements/' + encodeURIComponent(b.dataset.b6Cancel) + '/cancel', { method: 'POST', body: '{}' }).then(function () { toast('已取消', 'success'); loadAnnouncements(); });
        });
      };
    });
    document.querySelectorAll('[data-b6-del]').forEach(function (b) {
      b.onclick = function () {
        showConfirm('删除草稿', '确认删除草稿 ' + b.dataset.b6Del + '？仅未发布草稿可删除。', 'danger', function () {
          return api('/api/admin/announcements/' + encodeURIComponent(b.dataset.b6Del), { method: 'DELETE' }).then(function () { toast('已删除', 'success'); loadAnnouncements(); });
        });
      };
    });
    document.querySelectorAll('[data-b6-stats]').forEach(function (b) {
      b.onclick = async function () {
        try {
          var s = await api('/api/admin/announcements/' + encodeURIComponent(b.dataset.b6Stats) + '/stats');
          function item(label, value) {
            return '<div class="detail-item"><span class="label">' + esc(label) + '</span><span class="value">' + esc(value) + '</span></div>';
          }
          el('drawer-title').textContent = '公告统计';
          el('drawer-body').innerHTML =
            '<div class="detail-section"><h4>投递与已读</h4><div class="detail-grid">' +
            item('公告 ID', s.id || b.dataset.b6Stats) +
            item('受众', s.audience === 'TAGGED' ? ('标签 ' + (s.targetTagId || '—')) : '全员') +
            item('目标人数', s.recipientCount) +
            item('已读确认', s.ackedCount) +
            item('创建', date(s.createdAt)) +
            item('发布时间', s.publishedAt ? date(s.publishedAt) : '未发布') +
            item('取消时间', s.cancelledAt ? date(s.cancelledAt) : '—') +
            '</div><p class="panel-sub">这是公告已读确认，不是「广播给在线用户」的 WS 横幅。总览那颗按钮走 WebSocket，只推当前在线会话。</p></div>';
          el('drawer-overlay').classList.remove('hidden');
        } catch (e) { toast('统计失败: ' + (e && e.message ? e.message : e), 'error'); }
      };
    });

    document.getElementById('b6-ann-create').onclick = function () {
      showForm(
        '新建公告',
        '平台明文广播，不含会话正文。',
        [
          { name: 'title', label: '标题', type: 'text', required: true, placeholder: '公告标题' },
          { name: 'content', label: '内容', type: 'textarea', required: true, placeholder: '公告内容' },
          { name: 'level', label: '级别', type: 'select', value: 'INFO', options: LEVELS.map(function (lv) { return { value: lv, label: lv }; }) },
          { name: 'audience', label: '受众', type: 'select', value: 'ALL', options: [{ value: 'ALL', label: '全员' }, { value: 'TAGGED', label: '按标签' }] },
          { name: 'tagId', label: '定向标签 ID', type: 'text', placeholder: '受众为「按标签」时必填', hint: '先在「用户标签」页创建标签' },
          { name: 'startsAt', label: '生效时间戳（毫秒）', type: 'text', placeholder: '留空立即生效' },
          { name: 'expiresAt', label: '失效时间戳（毫秒）', type: 'text', placeholder: '留空默认 7 天' }
        ],
        async function (values) {
          var title = String(values.title || '').trim();
          var content = String(values.content || '').trim();
          if (!title) { toast('请填写标题', 'error'); return false; }
          if (!content) { toast('请填写内容', 'error'); return false; }
          var level = LEVELS.indexOf(String(values.level || 'INFO').toUpperCase()) >= 0 ? String(values.level).toUpperCase() : 'INFO';
          var audience = String(values.audience || 'ALL').toUpperCase() === 'TAGGED' ? 'TAGGED' : 'ALL';
          var tagId = String(values.tagId || '').trim() || null;
          if (audience === 'TAGGED' && !tagId) { toast('按标签公告必须指定 tagId', 'error'); return false; }
          var startsAt = String(values.startsAt || '').trim();
          var expiresAt = String(values.expiresAt || '').trim();
          var body = {
            title: title, content: content, level: level,
            audience: audience, tagId: tagId,
            startsAt: startsAt && Number.isFinite(Number(startsAt)) ? Number(startsAt) : null,
            expiresAt: expiresAt && Number.isFinite(Number(expiresAt)) ? Number(expiresAt) : null
          };
          await api('/api/admin/announcements', { method: 'POST', body: JSON.stringify(body) });
          toast('公告已创建', 'success');
          pg.announcements = 0;
          loadAnnouncements();
        }
      );
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
    html += '<div class="table-wrap"><table class="table">' +
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

    document.getElementById('b6-tag-create').onclick = function () {
      showForm(
        '新建标签',
        '自定义用户标签，可联动风控。',
        [
          { name: 'name', label: '名称', type: 'text', required: true, placeholder: '标签名称' },
          { name: 'riskLevel', label: '风控级别', type: 'select', value: 'LOW', options: RISK.map(function (lv) { return { value: lv, label: lv }; }) },
          { name: 'color', label: '颜色', type: 'text', value: '#64748b', placeholder: '#64748b' },
          { name: 'description', label: '描述', type: 'textarea', placeholder: '可留空' }
        ],
        async function (values) {
          var name = String(values.name || '').trim();
          if (!name) { toast('请填写标签名称', 'error'); return false; }
          var riskLevel = RISK.indexOf(String(values.riskLevel || 'LOW').toUpperCase()) >= 0 ? String(values.riskLevel).toUpperCase() : 'LOW';
          var color = String(values.color || '').trim() || '#64748b';
          var desc = String(values.description || '').trim() || null;
          await api('/api/admin/user-tags', { method: 'POST', body: JSON.stringify({ name: name, riskLevel: riskLevel, color: color, description: desc }) });
          toast('标签已创建', 'success');
          loadUserTags();
        }
      );
    };
    document.querySelectorAll('[data-b6-tag-edit]').forEach(function (b) {
      b.onclick = function () {
        showSelect(
          '修改风控级别',
          '标签 ' + b.dataset.b6TagEdit,
          RISK.map(function (lv) { return { value: lv, label: lv }; }),
          'MEDIUM',
          async function (newRisk) {
            newRisk = RISK.indexOf(String(newRisk || 'MEDIUM').toUpperCase()) >= 0 ? String(newRisk).toUpperCase() : 'MEDIUM';
            await api('/api/admin/user-tags/' + encodeURIComponent(b.dataset.b6TagEdit), { method: 'PUT', body: JSON.stringify({ riskLevel: newRisk }) });
            toast('标签已更新', 'success');
            loadUserTags();
          }
        );
      };
    });
    document.querySelectorAll('[data-b6-tag-del]').forEach(function (b) {
      b.onclick = function () {
        showConfirm('删除标签', '确认删除标签 ' + b.dataset.b6TagDel + '？会移除所有用户上的该标签。', 'danger', function () {
          return api('/api/admin/user-tags/' + encodeURIComponent(b.dataset.b6TagDel), { method: 'DELETE' }).then(function () { toast('标签已删除', 'success'); loadUserTags(); });
        });
      };
    });
    document.querySelectorAll('[data-b6-tag-users]').forEach(function (b) {
      b.onclick = function () { showTagUsers(b.dataset.b6TagUsers); };
    });
  }

  async function showTagUsers(tagId) {
    var offset = (pg['user-tag-users'] || 0) * pageSize;
    var rows = asList(await api('/api/admin/user-tags/' + encodeURIComponent(tagId) + '/users?limit=' + pageSize + '&offset=' + offset));
    var html = '<div class="panel"><div class="panel-header"><h2>标签用户 #' + esc(tagId) + '</h2>' +
      '<div class="toolbar"><button class="btn btn-ghost btn-sm" id="b6-tag-users-back">返回</button>' +
      '<button class="btn btn-primary btn-sm" id="b6-tag-users-add">添加用户</button></div></div>' +
      '<div class="panel-body"><div class="table-wrap"><table class="table">' +
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
    document.getElementById('b6-tag-users-add').onclick = function () {
      showPrompt('添加用户', '将该标签打到指定用户。', '', '用户 ID', async function (userId) {
        if (!userId || !String(userId).trim()) { toast('请填写用户 ID', 'error'); return false; }
        await api('/api/admin/users/' + encodeURIComponent(String(userId).trim()) + '/tags', { method: 'POST', body: JSON.stringify({ tagIds: [tagId] }) });
        toast('已打标', 'success');
        showTagUsers(tagId);
      });
    };
    document.querySelectorAll('[data-b6-unassign]').forEach(function (b) {
      b.onclick = function () {
        showConfirm('移除标签', '移除用户 ' + b.dataset.b6Unassign + ' 的该标签？', 'warn', function () {
          return api('/api/admin/users/' + encodeURIComponent(b.dataset.b6Unassign) + '/tags/' + encodeURIComponent(tagId), { method: 'DELETE' }).then(function () { toast('已移除', 'success'); showTagUsers(tagId); });
        });
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
    var evs = asList(await api('/api/admin/device-consistency/events?limit=' + pageSize + '&offset=' + offset));

    var html = '<div class="panel"><div class="panel-header"><h2>设备事件一致性</h2>' +
      '<div class="toolbar"><span class="badge ' + (sum.anomalyCount > 0 ? 'badge-red' : 'badge-green') + '">异常事件 ' + sum.anomalyCount + '</span></div></div>' +
      '<div class="panel-body">' +
      '<h3 class="panel-subtitle">设备事件序列（幂等应用点）</h3>' +
      '<div class="table-wrap"><table class="table">' +
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
      '<div class="table-wrap"><table class="table">' +
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

  function runB6Tab(name, seq) {
    if (!TABS[name]) return;
    currentTab = name;
    if (typeof seq !== 'number') seq = H.nextTabSeq();
    return TABS[name].fn(seq)
      .catch(function (x) { if (!H.isStaleTab(seq)) fail(x); })
      .then(function () { if (!H.isStaleTab(seq) && H.attachSubtabs) H.attachSubtabs(); });
  }
  H.openTab = function (name, seq) {
    if (!TABS[name]) return;
    el('content').innerHTML = '<div class="loading-state"><div class="spinner"></div><span>加载中…</span></div>';
    return runB6Tab(name, seq);
  };
  H.clearTab = function () { currentTab = ''; };

  // 公告 / 用户标签 / 限流 / 设备一致性一律由主模块 loadTab → H.openTab 分发。
  // 不再绑定 #nav 或 #refresh-btn，避免与主监听双 spinner、公告空白。

  // ─── B2 密聊防泄漏（Surface #71–#78）：设置页自动追加 8 个开关行 ───
  // 服务端只存开关位、不接触密聊明文；行保存复用 settings-save 的 [data-setting] 收集。
  var b2SecretRows = [
    { key: 'secret_screenshot_burn_enabled', label: '密聊截屏后烧毁', def: true },
    { key: 'secret_auto_destroy_enabled', label: '密聊会话到期自动销毁', def: true },
    { key: 'secret_forward_whitelist_enabled', label: '密聊转发白名单', def: true },
    { key: 'secret_sim_change_protection_enabled', label: '密聊 SIM 更换防护', def: true },
    { key: 'secret_2fa_gate_enabled', label: '密聊二次验证门槛', def: false },
    { key: 'secret_new_device_risk_enabled', label: '密聊新设备风险提示', def: true },
    { key: 'secret_device_verify_enabled', label: '密聊设备核验', def: true },
    { key: 'secret_session_notice_enabled', label: '密聊双向会话通知', def: true }
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

  // 主题切换逻辑（原 admin.html 内联脚本移入外部 JS，满足 script-src 'self' CSP）
  (function () {
    var root = document.documentElement;
    var btn = document.getElementById('theme-toggle');
    var sun = document.getElementById('icon-sun');
    var moon = document.getElementById('icon-moon');
    function syncIcons() {
      var light = root.getAttribute('data-theme') === 'light';
      if (sun) sun.hidden = light;
      if (moon) moon.hidden = !light;
    }
    syncIcons();
    if (btn) btn.addEventListener('click', function () {
      var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
      root.setAttribute('data-theme', next);
      try { localStorage.setItem('admin-theme', next); } catch (e) {}
      syncIcons();
    });
  })();
})();
