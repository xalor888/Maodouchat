/* ═══════════════════════════════════════════════════════════════════════
   毛豆聊天管理后台 — 运维增强模块（admin-ops）

   公告广播 / 用户标签 / 限流仪表盘 / 设备一致性 + B2 密聊防泄漏扩展。
   自包含模块：只通过 window.Admin（框架层）与 window.__b6Admin（主 SPA 桥）
   取能力，不重复实现 api/toast/esc/date/showConfirm 等基础设施。
   ═══════════════════════════════════════════════════════════════════════ */
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
})();
