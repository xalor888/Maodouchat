/* ═══════════════════════════════════════════════════════════════════════
   毛豆聊天管理后台 — 共享框架层（admin-core）

   职责：管理后台所有模块共用的基础设施，单一真相源，杜绝各视图重复实现。
   包含：会话态（admin token 仅存内存）、DOM/格式化工具、toast、API 客户端、
   会话时钟、模态框（confirm/prompt/select/form）、抽屉与 ESC 关闭。

   对外仅暴露 `window.Admin`。admin.js 与后续追加模块一律从这里取能力，
   不得再各自维护 esc/date/api/toast/showConfirm 等重复实现。
   ═══════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  // ─── 会话态（admin token 只存内存，不落 localStorage/sessionStorage） ──
  var session = { token: '', expiresAt: 0 };
  var sessionTimer = 0;

  // ─── DOM / 格式化工具 ─────────────────────────────────────────────
  function el(id) { return document.getElementById(id); }

  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function date(v) {
    if (!v) return '—';
    var d = new Date(v);
    var pad = function (n) { return String(n).padStart(2, '0'); };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function dateShort(v) {
    if (!v) return '—';
    var d = new Date(v);
    var pad = function (n) { return String(n).padStart(2, '0'); };
    return pad(d.getMonth() + 1) + '/' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function timeAgo(v) {
    if (!v) return '—';
    var diff = Date.now() - v;
    if (diff < 60000) return Math.floor(diff / 1000) + ' 秒前';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    return Math.floor(diff / 86400000) + ' 天前';
  }
  function fmtBytes(v) {
    if (!v || v < 1024) return v + ' B';
    if (v < 1048576) return (v / 1024).toFixed(1) + ' KB';
    if (v < 1073741824) return (v / 1048576).toFixed(1) + ' MB';
    return (v / 1073741824).toFixed(2) + ' GB';
  }
  function fmtDuration(ms) {
    if (!ms) return '—';
    var s = Math.floor(ms / 1000);
    var d = Math.floor(s / 86400);
    var h = Math.floor((s % 86400) / 3600);
    var m = Math.floor((s % 3600) / 60);
    if (d > 0) return d + '天 ' + h + '时';
    if (h > 0) return h + '时 ' + m + '分';
    return m + '分 ' + (s % 60) + '秒';
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

  // ─── Toast ─────────────────────────────────────────────────────────
  function toast(msg, type) {
    var t = el('toast');
    t.className = 'toast' + (type ? ' toast-' + type : '');
    t.querySelector('.toast-text').textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { t.classList.add('hidden'); }, 3000);
  }

  // ─── API 客户端（自动附 admin Bearer token；错误提取 JSON.error） ──
  async function api(path, opt) {
    opt = opt || {};
    opt.headers = Object.assign({ Authorization: 'Bearer ' + session.token }, opt.headers || {});
    if (opt.body) opt.headers['Content-Type'] = 'application/json';
    var r = await fetch(path, opt);
    var text = await r.text();
    var data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { /* non-JSON response */ } }
    if (!r.ok) throw new Error((data && data.error) || ('请求失败 ' + r.status));
    return data;
  }

  // ─── 会话时钟（5 分钟管理员会话倒计时；到期强制重新登录） ──
  function startSessionClock() {
    clearInterval(sessionTimer);
    function tick() {
      var left = Math.max(0, Math.ceil((session.expiresAt - Date.now()) / 1000));
      var m = Math.floor(left / 60);
      var s = left % 60;
      el('session-info').querySelector('span').textContent = m + ':' + String(s).padStart(2, '0');
      if (left <= 30) el('session-info').style.color = 'var(--danger)';
      if (left === 0) {
        clearInterval(sessionTimer);
        session.token = '';
        toast('管理员会话已到期，请重新登录', 'error');
        setTimeout(function () { location.reload(); }, 1500);
      }
    }
    tick();
    sessionTimer = setInterval(tick, 1000);
  }

  // ─── Modal 对话框系统 ─────────────────────────────────────────────
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

  var ICON = {
    warn: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4.2L21 19.5H3L12 4.2z"/><path d="M12 10v4.2M12 16.8h.01"/></svg>',
    danger: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"><path d="M7 7l10 10M17 7L7 17"/></svg>',
    info: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.25"/><path d="M12 11v5M12 8h.01"/></svg>'
  };

  function showConfirm(title, body, type, callback) {
    modalCallback = callback;
    el('modal-title').textContent = title;
    el('modal-body').textContent = body;
    resetModalChrome();

    var iconWrap = el('modal-icon-wrap');
    iconWrap.className = 'modal-icon ' + (type || 'info');
    iconWrap.innerHTML = ICON[type] || ICON.info;

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
    iconWrap.innerHTML = ICON.info;

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
    iconWrap.innerHTML = ICON.warn;
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
    iconWrap.innerHTML = ICON.info;
    el('modal-overlay').classList.remove('hidden');
    setTimeout(function () {
      var first = wrap.querySelector('input, textarea, select');
      if (first) first.focus();
    }, 100);
  }

  // ─── Modal / Drawer / ESC 事件接线 ─────────────────────────────────
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
    // in-flight 锁：回调执行期间禁用按钮，快速双击/回车不会重复提交
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
        if (result === false) { el('modal-confirm').disabled = false; return; }
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
    // 只有回调没有再打开新模态框时才关闭（如 showPrompt 内嵌 showConfirm）
    if (modalCallback === cb) {
      el('modal-overlay').classList.add('hidden');
      modalCallback = null;
    }
  };
  // Enter key on modal input
  el('modal-input').onkeydown = function (e) { if (e.key === 'Enter') { e.preventDefault(); el('modal-confirm').click(); } };

  // Drawer 关闭
  el('drawer-close').onclick = function () { el('drawer-overlay').classList.add('hidden'); };
  el('drawer-overlay').onclick = function (e) {
    if (e.target === el('drawer-overlay')) el('drawer-overlay').classList.add('hidden');
  };

  // ESC 关闭弹窗 / 抽屉 / 移动端侧边栏
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
      el('modal-overlay').classList.add('hidden');
      el('drawer-overlay').classList.add('hidden');
      el('sidebar').classList.remove('open');
      el('sidebar-overlay').classList.add('hidden');
    }
  });

  // ─── 对外接口 ─────────────────────────────────────────────────────
  window.Admin = {
    session: session,
    el: el, esc: esc, date: date, dateShort: dateShort, timeAgo: timeAgo,
    fmtBytes: fmtBytes, fmtDuration: fmtDuration, asList: asList,
    toast: toast, api: api, startSessionClock: startSessionClock,
    showConfirm: showConfirm, showPrompt: showPrompt, showSelect: showSelect, showForm: showForm
  };
})();
