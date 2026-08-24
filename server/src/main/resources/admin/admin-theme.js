/* 毛豆聊天管理后台 — 外观管理器
 * 尽早执行：把持久化的主题写入 <html>，避免闪烁。
 * DOM 就绪后注入「外观」设置弹窗：强调色 / 明暗 / 圆角 / 密度 / 字号，
 * 全部维度持久化到 localStorage（key 前缀 admin-）。
 */
(function () {
  var LS = {
    theme: 'admin-theme',
    accent: 'admin-accent',
    radius: 'admin-radius',
    density: 'admin-density',
    fontscale: 'admin-fontscale'
  };
  var ACCENTS = [
    { id: 'ink',    c: '#1f2933' },
    { id: 'blue',   c: '#2563eb' },
    { id: 'teal',   c: '#0f766e' },
    { id: 'green',  c: '#178a54' },
    { id: 'amber',  c: '#b47716' },
    { id: 'rose',   c: '#cc3d58' },
    { id: 'indigo', c: '#4f46e5' }
  ];
  var RADII = [['sharp', '锐利'], ['soft', '柔和'], ['round', '圆润']];
  var DENSITY = [['compact', '紧凑'], ['cozy', '适中'], ['spacious', '宽松']];
  var FONTS = [['s', '小'], ['m', '中'], ['l', '大'], ['xl', '特大']];

  function get(k, d) {
    try { return localStorage.getItem(k) || d; } catch (e) { return d; }
  }
  function set(k, v) {
    try { localStorage.setItem(k, v); } catch (e) {}
  }
  function applyAll() {
    var root = document.documentElement;
    root.setAttribute('data-theme', get(LS.theme, 'light'));
    root.setAttribute('data-accent', get(LS.accent, 'ink'));
    root.setAttribute('data-radius', get(LS.radius, 'sharp'));
    root.setAttribute('data-density', get(LS.density, 'compact'));
    root.setAttribute('data-fontscale', get(LS.fontscale, 'm'));
  }
  applyAll();

  function syncSunMoon(light) {
    var sun = document.getElementById('icon-sun');
    var moon = document.getElementById('icon-moon');
    if (sun) sun.hidden = light;
    if (moon) moon.hidden = !light;
  }

  function buildPopover() {
    if (document.getElementById('appearance-popover')) return;
    var pop = document.createElement('div');
    pop.id = 'appearance-popover';
    pop.className = 'appearance-popover hidden';
    pop.setAttribute('role', 'dialog');
    pop.setAttribute('aria-label', '外观设置');

    function section(title, inner) {
      return '<section><h4>' + title + '</h4>' + inner + '</section>';
    }
    function segRow(attr, items, current) {
      return '<div class="seg-row" data-seg="' + attr + '">' + items.map(function (it) {
        return '<button type="button" class="seg-btn' + (it[0] === current ? ' active' : '') +
          '" data-' + attr + '-pick="' + it[0] + '">' + it[1] + '</button>';
      }).join('') + '</div>';
    }
    var accentHtml = '<div class="accent-row">' + ACCENTS.map(function (a) {
      return '<button type="button" class="accent-dot" data-accent-pick="' + a.id +
        '" style="background:' + a.c + '" title="' + a.id + '" aria-label="强调色 ' + a.id + '"></button>';
    }).join('') + '</div>';

    pop.innerHTML =
      section('强调色', accentHtml) +
      section('明暗', segRow('theme', [['light', '浅色'], ['dark', '深色']], get(LS.theme, 'light'))) +
      section('圆角', segRow('radius', RADII, get(LS.radius, 'sharp'))) +
      section('密度', segRow('density', DENSITY, get(LS.density, 'compact'))) +
      section('字号', segRow('fontscale', FONTS, get(LS.fontscale, 'm')));

    document.body.appendChild(pop);

    function refreshSeg(attr, val) {
      pop.querySelectorAll('[data-' + attr + '-pick]').forEach(function (b) {
        b.classList.toggle('active', b.getAttribute('data-' + attr + '-pick') === val);
      });
      if (attr === 'theme') syncSunMoon(val === 'light');
    }

    ['theme', 'radius', 'density', 'fontscale'].forEach(function (attr) {
      pop.addEventListener('click', function (ev) {
        var b = ev.target.closest('[data-' + attr + '-pick]');
        if (!b) return;
        var v = b.getAttribute('data-' + attr + '-pick');
        document.documentElement.setAttribute('data-' + attr, v);
        set(LS[attr], v);
        refreshSeg(attr, v);
      });
    });

    pop.addEventListener('click', function (ev) {
      var d = ev.target.closest('[data-accent-pick]');
      if (!d) return;
      var v = d.getAttribute('data-accent-pick');
      document.documentElement.setAttribute('data-accent', v);
      set(LS.accent, v);
      pop.querySelectorAll('.accent-dot').forEach(function (b) {
        b.classList.toggle('active', b.getAttribute('data-accent-pick') === v);
      });
    });
    pop.querySelectorAll('.accent-dot').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-accent-pick') === get(LS.accent, 'ink'));
    });
  }

  function buildButton() {
    var bar = document.querySelector('.topbar-actions');
    if (!bar || document.getElementById('appearance-btn')) return;
    var btn = document.createElement('button');
    btn.id = 'appearance-btn';
    btn.type = 'button';
    btn.className = 'btn btn-ghost btn-icon';
    btn.title = '外观设置';
    btn.setAttribute('aria-label', '外观设置');
    btn.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 3a4.5 4.5 0 004.5 4.5h1A3.5 3.5 0 0121 11v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a3.5 3.5 0 013.5-3.5h1A4.5 4.5 0 0012 3z"/><circle cx="12" cy="14" r="2.6"/></svg>';
    bar.insertBefore(btn, bar.firstChild);

    btn.addEventListener('click', function (ev) {
      ev.stopPropagation();
      var pop = document.getElementById('appearance-popover');
      if (pop) pop.classList.toggle('hidden');
    });
    document.addEventListener('click', function (ev) {
      var pop = document.getElementById('appearance-popover');
      if (!pop || pop.classList.contains('hidden')) return;
      if (ev.target.closest('#appearance-popover') || ev.target.closest('#appearance-btn')) return;
      pop.classList.add('hidden');
    });
    document.addEventListener('keydown', function (ev) {
      if (ev.key !== 'Escape') return;
      var pop = document.getElementById('appearance-popover');
      if (pop && !pop.classList.contains('hidden')) pop.classList.add('hidden');
    });
  }

  function init() {
    buildPopover();
    buildButton();
    syncSunMoon(document.documentElement.getAttribute('data-theme') === 'light');
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
