(function () {
  var theme = 'dark';
  try {
    theme = localStorage.getItem('admin-theme') ||
      (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
  } catch (e) {}
  document.documentElement.setAttribute('data-theme', theme === 'light' ? 'light' : 'dark');
})();
