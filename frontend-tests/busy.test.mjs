import test from 'node:test';
import assert from 'node:assert/strict';
import { startBusyFeedback } from '../backend/src/main/resources/static/assets/busy.mjs';

function fixture({ attributes = {}, sizes = {}, connected = true } = {}) {
  let now = 0, nextTimer = 0, measurements = 0;
  const timers = new Map(), attrs = new Map(Object.entries(attributes));
  const styles = new Map(Object.entries(sizes).map(([name, value]) => [name, Array.isArray(value) ? value : [value, '']]));
  const syncStyle = () => attrs.set('style', [...styles].map(([name, [value, priority]]) => `${name}: ${value}${priority ? ' !' + priority : ''};`).join(' '));
  if (styles.size) syncStyle();
  const children = [{ nodeName: '#text', textContent: 'Guardar' }, { nodeName: 'svg', listener: () => {} }];
  const button = {
    isConnected: connected,
    disabled: true,
    childNodes: [...children],
    getAttribute: name => attrs.get(name) ?? null,
    hasAttribute: name => attrs.has(name),
    setAttribute: (name, value) => attrs.set(name, String(value)),
    removeAttribute: name => attrs.delete(name),
    replaceChildren(...nodes) { this.childNodes = nodes.map(node => typeof node === 'string' ? { nodeName: '#text', textContent: node } : node); },
    getBoundingClientRect() { measurements++;return { width: 147.5, height: 42 }; },
    style: {
      getPropertyValue: name => styles.get(name)?.[0] ?? '',
      getPropertyPriority: name => styles.get(name)?.[1] ?? '',
      setProperty(name, value, priority = '') { styles.set(name, [value, priority]);syncStyle(); },
      removeProperty(name) { styles.delete(name);syncStyle(); },
    },
  };
  const scheduler = {
    setTimeout(callback, delay) { const id = ++nextTimer;timers.set(id, { callback, at: now + delay });return id; },
    clearTimeout(id) { timers.delete(id); },
    advance(milliseconds) {
      now += milliseconds;
      for (const [id, timer] of timers) {
        if (timer.at <= now) { timers.delete(id);timer.callback(); }
      }
    },
  };
  return { button, children, scheduler, attrs, styles, timers, measurements: () => measurements };
}

test('quick completion cancels feedback without changing or measuring the button', () => {
  const f = fixture(), before = [...f.button.childNodes];
  const cleanup = startBusyFeedback(f.button, { scheduler: f.scheduler });
  f.scheduler.advance(179);
  assert.deepEqual(f.button.childNodes, before);
  assert.equal(f.button.getAttribute('aria-busy'), null);
  cleanup();f.scheduler.advance(1000);
  assert.deepEqual(f.button.childNodes, before);
  assert.equal(f.measurements(), 0);
  assert.equal(f.styles.size, 0);
  assert.equal(f.timers.size, 0);
});

test('slow work gets one truthful label and preserves the minimum click target', () => {
  const f = fixture();
  const cleanup = startBusyFeedback(f.button, { scheduler: f.scheduler, label: 'A importar…' });
  f.scheduler.advance(180);
  assert.equal(f.button.childNodes[0].textContent, 'A importar…');
  assert.equal(f.button.childNodes.length, 1);
  assert.equal(f.button.getAttribute('aria-busy'), 'true');
  assert.equal(f.button.getAttribute('aria-label'), 'A importar…');
  assert.equal(f.button.style.getPropertyValue('min-width'), '147.5px');
  assert.equal(f.button.style.getPropertyValue('min-height'), '42px');
  assert.equal(f.button.style.getPropertyValue('width'), '');
  assert.equal(f.button.disabled, true);
  f.scheduler.advance(5000);
  assert.equal(f.measurements(), 1);
  assert.equal(f.timers.size, 0);
  cleanup();
  assert.equal(f.button.childNodes[0], f.children[0]);
  assert.equal(f.button.childNodes[1], f.children[1]);
  assert.equal(f.button.getAttribute('aria-busy'), null);
  assert.equal(f.button.getAttribute('aria-label'), null);
  assert.equal(f.button.getAttribute('style'), null);
});

test('cleanup restores original accessible names and inline size values with priorities', () => {
  const f = fixture({
    attributes: { 'aria-busy': 'false', 'aria-label': 'Guardar produto', 'aria-labelledby': 'save-caption' },
    sizes: { 'min-width': ['10rem', 'important'], 'min-height': '2rem', color: 'red' },
  });
  const originalAttributes = new Map(f.attrs), originalStyles = new Map(f.styles);
  const cleanup = startBusyFeedback(f.button, { scheduler: f.scheduler, delay: 20 });
  f.scheduler.advance(20);
  assert.equal(f.button.getAttribute('aria-labelledby'), null);
  assert.equal(f.button.getAttribute('aria-label'), 'A guardar…');
  cleanup();cleanup();
  assert.deepEqual(f.attrs, originalAttributes);
  assert.deepEqual(f.styles, originalStyles);
  assert.equal(f.measurements(), 1);
});

test('a detached button never gets feedback or a layout measurement', () => {
  const f = fixture();
  const cleanup = startBusyFeedback(f.button, { scheduler: f.scheduler });
  f.button.isConnected = false;f.scheduler.advance(180);
  cleanup();cleanup();
  assert.deepEqual(f.button.childNodes, f.children);
  assert.equal(f.attrs.size, 0);
  assert.equal(f.measurements(), 0);
});

test('cleanup still restores the original nodes when rendering detaches a busy button', () => {
  const f = fixture(), cleanup = startBusyFeedback(f.button, { scheduler: f.scheduler });
  f.scheduler.advance(180);f.button.isConnected = false;
  cleanup();
  assert.equal(f.button.childNodes[1], f.children[1]);
  assert.equal(f.button.getAttribute('style'), null);
  assert.equal(f.button.getAttribute('aria-busy'), null);
});

test('restarting feedback cancels the previous timer and makes old cleanup harmless', () => {
  const f = fixture();
  const first = startBusyFeedback(f.button, { scheduler: f.scheduler });
  f.scheduler.advance(100);
  const second = startBusyFeedback(f.button, { scheduler: f.scheduler, label: 'A enviar…' });
  first();f.scheduler.advance(80);
  assert.equal(f.button.childNodes[0], f.children[0]);
  f.scheduler.advance(100);
  first();
  assert.equal(f.button.childNodes[0].textContent, 'A enviar…');
  second();second();
  assert.equal(f.button.childNodes[0], f.children[0]);
  assert.equal(f.timers.size, 0);
});

test('restarting visible feedback restores the original button before the new delay', () => {
  const f = fixture(), first = startBusyFeedback(f.button, { scheduler: f.scheduler });
  f.scheduler.advance(180);
  const second = startBusyFeedback(f.button, { scheduler: f.scheduler, delay: 10, label: 'A entrar…' });
  assert.equal(f.button.childNodes[0], f.children[0]);
  assert.equal(f.button.getAttribute('aria-busy'), null);
  f.scheduler.advance(10);first();
  assert.equal(f.button.getAttribute('aria-label'), 'A entrar…');
  second();
  assert.equal(f.button.childNodes[1], f.children[1]);
});

test('missing submit buttons need no special handling in the caller', () => {
  assert.doesNotThrow(() => startBusyFeedback(null)());
});
