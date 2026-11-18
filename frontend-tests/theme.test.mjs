import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { createThemeController, readThemePreference, resolveTheme, themeButton, themePreferenceKey } from '../backend/src/main/resources/static/assets/theme.mjs';

function themeFixture({ preference = null, systemDark = false, blocked = false } = {}) {
  const events = () => {
    const listeners = new Map();
    return {
      addEventListener: (name, listener) => listeners.set(name, listener),
      removeEventListener: (name, listener) => { if (listeners.get(name) === listener) listeners.delete(name); },
      emit: (name, value = {}) => listeners.get(name)?.(value),
    };
  };
  const values = new Map(preference === null ? [] : [[themePreferenceKey, preference]]);
  const storage = {
    getItem: key => { if (blocked) throw new Error('Storage disabled');return values.get(key) ?? null; },
    setItem: (key, value) => { if (blocked) throw new Error('Storage disabled');values.set(key, value); },
  };
  const button = { attributes: {}, setAttribute(name, value) { this.attributes[name] = value; } };
  const meta = { setAttribute(name, value) { this[name] = value; } };
  const page = { ...events(), documentElement: { dataset: {} }, querySelectorAll: () => [button], querySelector: () => meta };
  const browser = events(), media = { ...events(), matches: systemDark };
  const controller = createThemeController({ page, browser, storage, media });
  return { page, browser, media, storage, values, button, meta, controller };
}

test('theme follows the system until a person selects an explicit preference', () => {
  const f = themeFixture({ systemDark: true });
  assert.equal(f.page.documentElement.dataset.theme, 'dark');
  assert.equal(f.button.attributes['aria-pressed'], 'true');
  f.media.matches = false;f.media.emit('change');
  assert.equal(f.page.documentElement.dataset.theme, 'light');
  f.page.emit('click', { target: { closest: () => f.button } });
  assert.equal(f.storage.getItem(themePreferenceKey), 'dark');
  f.media.emit('change');
  assert.equal(f.page.documentElement.dataset.theme, 'dark');
  assert.equal(f.button.attributes.title, 'Ativar modo claro');
  assert.equal(f.meta.content, '#122c24');
  f.controller.destroy();
});

test('stored light preference survives a dark system and a subsequent visit', () => {
  const f = themeFixture({ preference: 'light', systemDark: true });
  assert.equal(f.page.documentElement.dataset.theme, 'light');
  f.media.emit('change');
  assert.equal(f.page.documentElement.dataset.theme, 'light');
  f.controller.toggle();
  const next = themeFixture({ preference: f.storage.getItem(themePreferenceKey) });
  assert.equal(next.page.documentElement.dataset.theme, 'dark');
});

test('theme switches remain usable with blocked storage and keep the in-session choice', () => {
  const f = themeFixture({ blocked: true, systemDark: true });
  assert.doesNotThrow(() => f.controller.toggle());
  assert.equal(f.page.documentElement.dataset.theme, 'light');
  f.media.emit('change');
  assert.equal(f.page.documentElement.dataset.theme, 'light');
  assert.equal(readThemePreference(f.storage), null);
});

test('cross-tab preference changes synchronize and removing a choice restores system behavior', () => {
  const f = themeFixture({ preference: 'light', systemDark: true });
  f.storage.setItem(themePreferenceKey, 'dark');
  f.browser.emit('storage', { key: themePreferenceKey, storageArea: f.storage });
  assert.equal(f.page.documentElement.dataset.theme, 'dark');
  f.values.delete(themePreferenceKey);
  f.browser.emit('storage', { key: themePreferenceKey, storageArea: f.storage });
  f.media.matches = false;f.media.emit('change');
  assert.equal(f.page.documentElement.dataset.theme, 'light');
});

test('invalid preferences use the system and the toggle has a stable accessible name', () => {
  assert.equal(resolveTheme('invalid', true), 'dark');
  assert.equal(readThemePreference({ getItem: () => 'invalid' }), null);
  assert.match(themeButton(), /aria-label="Modo escuro" aria-pressed="false"/);
  assert.match(themeButton({ compact: true }), /class="btn theme-toggle icon-button"/);
});

test('blocking startup script agrees with the controller before application rendering', () => {
  const source = readFileSync(new URL('../backend/src/main/resources/static/assets/theme-init.js', import.meta.url), 'utf8');
  for (const preference of [null, 'light', 'dark', 'invalid']) {
    for (const systemDark of [false, true]) {
      const document = { documentElement: { dataset: {} } };
      vm.runInNewContext(source, { document, localStorage: { getItem: key => { assert.equal(key, themePreferenceKey);return preference; } }, matchMedia: () => ({ matches: systemDark }) });
      assert.equal(document.documentElement.dataset.theme, resolveTheme(preference, systemDark));
    }
  }
  const document = { documentElement: { dataset: {} } };
  vm.runInNewContext(source, { document, localStorage: { getItem: () => { throw new Error('disabled'); } }, matchMedia: () => ({ matches: true }) });
  assert.equal(document.documentElement.dataset.theme, 'dark');
});
