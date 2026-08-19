// 9.206：第三方服务器品牌化——拉取公开 /api/server/info，将运营方自定义名称
// 展示到登录页/侧边栏/页面标题；拉取失败静默回退默认品牌。
// 注意：管理后台 CSP 为 script-src 'self'，禁止内联脚本，必须独立文件加载。
(function () {
  try {
    fetch('/api/server/info').then(function (r) { return r.ok ? r.json() : null; }).then(function (info) {
      if (!info || !info.name) return;
      var name = String(info.name).slice(0, 60);
      var sidebar = document.getElementById('admin-server-name');
      if (sidebar) sidebar.textContent = name + ' 后台';
      var login = document.getElementById('login-brand-name');
      if (login) login.textContent = name;
      document.title = name + ' 管理后台';
    }).catch(function () {});
  } catch (e) {}
})();
