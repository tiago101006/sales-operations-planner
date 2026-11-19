export const themePreferenceKey = 'nexoplan.theme';

export function readThemePreference(storage) {
  try {
    const value = storage?.getItem(themePreferenceKey);
    return value === 'light' || value === 'dark' ? value : null;
  } catch { return null; }
}

export const resolveTheme = (preference, systemDark) =>
  preference === 'light' || preference === 'dark' ? preference : systemDark ? 'dark' : 'light';

const themeIcon = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><g class="theme-moon"><path d="M20.5 13.4A8.5 8.5 0 0 1 10.6 3.5a8.5 8.5 0 1 0 9.9 9.9Z"/></g><g class="theme-sun"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4m0-14.2-1.4 1.4M6.3 17.7l-1.4 1.4"/></g></svg>`;

export function themeButton({ compact = false } = {}) {
  const dark = globalThis.document?.documentElement.dataset.theme === 'dark';
  return `<button type="button" class="btn theme-toggle${compact ? ' icon-button' : ''}" data-theme-toggle aria-label="Modo escuro" aria-pressed="${dark}" title="${dark ? 'Ativar modo claro' : 'Ativar modo escuro'}">${themeIcon}${compact ? '' : '<span>Modo escuro</span>'}</button>`;
}

export function createThemeController({ page = globalThis.document, browser = globalThis.window, storage, media } = {}) {
  if (!page || !browser) return null;
  try { storage ??= browser.localStorage; } catch { /* Keep a session preference when storage is unavailable. */ }
  media ??= browser.matchMedia?.('(prefers-color-scheme: dark)');
  let preference = readThemePreference(storage);

  const apply = (animate = false) => {
    const theme = resolveTheme(preference, !!media?.matches), dark = theme === 'dark';
    // Automatic preferences and keyboard activation settle without icon motion.
    page.documentElement.dataset.themeMotion = String(animate && !page.hidden);
    page.documentElement.dataset.theme = theme;
    page.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#123c35' : '#173d34');
    page.querySelectorAll('[data-theme-toggle]').forEach(button => {
      button.setAttribute('aria-pressed', String(dark));
      button.setAttribute('title', dark ? 'Ativar modo claro' : 'Ativar modo escuro');
    });
  };
  const toggle = (animate = false) => {
    preference = page.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    try { storage?.setItem(themePreferenceKey, preference); } catch { /* The switch still works for this session. */ }
    apply(animate);
  };
  const onClick = event => {
    const button = event.target.closest?.('[data-theme-toggle]');
    if (button && !button.disabled) toggle(event.detail > 0);
  };
  const onSystemChange = () => { if (!preference) apply(); };
  const onStorage = event => {
    if ((event.key === themePreferenceKey || event.key === null) && (!event.storageArea || event.storageArea === storage)) {
      preference = readThemePreference(storage);
      apply();
    }
  };
  page.addEventListener('click', onClick);
  media?.addEventListener('change', onSystemChange);
  browser.addEventListener('storage', onStorage);
  apply();
  return {
    toggle,
    destroy() {
      page.removeEventListener('click', onClick);
      media?.removeEventListener('change', onSystemChange);
      browser.removeEventListener('storage', onStorage);
      delete page.documentElement.dataset.themeMotion;
    },
  };
}

createThemeController();
