/* Run before styles so a saved theme is applied before the first paint. */
(() => {
  let preference;
  try { preference = localStorage.getItem('nexoplan.theme'); } catch { /* Storage may be disabled. */ }
  const systemDark = typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.dataset.theme = preference === 'light' || preference === 'dark'
    ? preference : systemDark ? 'dark' : 'light';
})();
