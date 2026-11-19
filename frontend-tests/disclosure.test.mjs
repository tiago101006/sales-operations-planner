import test from 'node:test';
import assert from 'node:assert/strict';
import { createDisclosureController } from '../backend/src/main/resources/static/assets/disclosure.mjs';

function events() {
  const listeners = new Map();
  return {
    addEventListener(type, callback) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type).add(callback);
    },
    removeEventListener(type, callback) { listeners.get(type)?.delete(callback); },
    emit(type, event = {}) { for (const callback of [...listeners.get(type) || []]) callback({ type, ...event }); },
    listenerCount() { return [...listeners.values()].reduce((sum, values) => sum + values.size, 0); },
  };
}

class Style {
  constructor(element) { this.element = element;this.values = new Map(); }
  get length() { return this.values.size; }
  item(index) { return [...this.values.keys()][index]; }
  getPropertyValue(name) { return this.values.get(name)?.[0] || ''; }
  getPropertyPriority(name) { return this.values.get(name)?.[1] || ''; }
  setProperty(name, value, priority = '') {
    this.element.attributes.set('style', '');
    if (name === 'overflow') { this.values.delete('overflow-x');this.values.delete('overflow-y'); }
    this.values.set(name, [String(value), priority]);
  }
  removeProperty(name) { const value = this.getPropertyValue(name);this.values.delete(name);return value; }
  get cssText() { return [...this.values].map(([name, [value, priority]]) => `${name}: ${value}${priority ? ' !' + priority : ''};`).join(' '); }
  set cssText(value) {
    this.values.clear();
    for (const declaration of value.split(';').filter(value => value.trim())) {
      const [name, ...parts] = declaration.split(':');
      const raw = parts.join(':').trim();
      this.setProperty(name.trim(), raw.replace(/\s*!important$/, ''), /!important$/.test(raw) ? 'important' : '');
    }
  }
}

class Element {
  constructor(tagName = 'div', height = 40) {
    this.tagName = tagName.toUpperCase();this.height = height;this.isConnected = true;
    this.attributes = new Map();this.style = new Style(this);this.children = [];
    this.animations = [];this.open = false;this.closedHeight = 30;this.openHeight = 180;
    this.computed = { boxSizing: 'border-box' };
  }
  getAttribute(name) { return this.attributes.has(name) ? this.attributes.get(name) : null; }
  hasAttribute(name) { return this.attributes.has(name); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name);if (name === 'style') this.style.values.clear(); }
  append(child) { this.children.push(child);child.parentElement = this; }
  contains(child) { return child === this || this.children.some(item => item.contains(child)); }
  matches(selector) {
    if (selector === 'summary') return this.tagName === 'SUMMARY';
    return (this.tagName === 'A' && this.hasAttribute('href'))
      || ['BUTTON', 'INPUT', 'SELECT', 'TEXTAREA'].includes(this.tagName)
      || this.hasAttribute('contenteditable') || ['button', 'link'].includes(this.getAttribute('role'));
  }
  closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector); }
  getClientRects() { return this.isConnected && !this.hidden ? [this.getBoundingClientRect()] : []; }
  getBoundingClientRect() {
    const effect = this.animations.at(-1);
    return { height: effect && !effect.cancelled ? effect.sample
      : this.tagName === 'DETAILS' ? this.open ? this.openHeight : this.closedHeight : this.height };
  }
  focus() { this.focused = true; }
  animate(frames, options) {
    const effect = { ...events(), frames, options, sample: Number.parseFloat(frames[0].height), cancelled: false,
      cancel() { this.cancelled = true;this.emit('cancel'); },
      finish() { this.sample = Number.parseFloat(frames.at(-1).height);this.emit('finish'); },
    };
    this.animations.push(effect);return effect;
  }
}

function fixture({ reduced = false, allowed = true } = {}) {
  const page = { ...events(), hidden: false, activeElement: null };
  const media = { ...events(), matches: reduced };
  const browser = { ...events(), matchMedia: () => media, getComputedStyle: element => element.computed };
  const preference = { allowed };
  const controller = createDisclosureController({ page, browser, shouldAnimate: () => preference.allowed });
  const element = new Element();
  const details = new Element('details');
  const summary = new Element('summary');
  const body = new Element();details.append(summary);details.append(body);
  const click = (target = summary, options = {}) => {
    const event = { target, button: 0, detail: 1, prevented: false, ...options,
      preventDefault() { this.prevented = true; },
    };
    // Keep the original event visible to the assertion despite dispatch copying.
    event.preventDefault = () => { event.prevented = true; };
    page.emit('click', event);return event;
  };
  return { page, media, browser, preference, controller, element, details, summary, body, click };
}

test('mutations and their return values are synchronous, even when revealing sensitive forms', () => {
  const f = fixture();let password = 'secret';
  const returned = f.controller.change(f.element, () => { password = '';f.element.height = 160;return 'done'; });
  assert.equal(password, '');assert.equal(returned, 'done');
  assert.deepEqual(f.element.animations[0].frames, [{ height: '40px' }, { height: '160px' }]);
  assert.equal(f.element.animations[0].options.duration, 220);
  assert.equal(f.element.style.getPropertyValue('overflow'), 'hidden');
  f.element.animations[0].finish();
  assert.equal(f.element.hasAttribute('style'), false);
});

test('interruption restarts at the currently painted height and ignores stale completion', () => {
  const f = fixture();f.controller.change(f.element, () => { f.element.height = 180; });
  const first = f.element.animations[0];first.sample = 95;
  f.controller.change(f.element, () => { f.element.height = 65; });
  const second = f.element.animations[1];
  assert.equal(first.cancelled, true);assert.equal(first.listenerCount(), 0);
  assert.deepEqual(second.frames, [{ height: '95px' }, { height: '65px' }]);
  first.emit('finish');assert.equal(second.cancelled, false);
  second.finish();assert.equal(f.element.style.cssText, '');
});

test('completion and external cancellation restore exact inline declarations and priorities', () => {
  for (const end of ['finish', 'cancel']) {
    const f = fixture();f.element.style.cssText = 'color: teal; height: auto !important; overflow-x: visible; overflow-y: auto !important;';
    const original = f.element.style.cssText;
    f.controller.change(f.element, () => { f.element.height = 140; });
    f.element.animations[0][end]();
    assert.equal(f.element.style.cssText, original);
    assert.equal(f.element.style.getPropertyPriority('height'), 'important');
    assert.equal(f.element.animations[0].listenerCount(), 0);
  }
});

test('cleanup preserves unrelated inline changes made while an animation runs', () => {
  const f = fixture();f.element.style.cssText = 'overflow: visible !important; color: teal;';
  f.controller.change(f.element, () => { f.element.height = 120; });
  f.element.style.setProperty('color', 'purple');f.element.animations[0].finish();
  assert.equal(f.element.style.getPropertyValue('color'), 'purple');
  assert.equal(f.element.style.getPropertyValue('overflow'), 'visible');
  assert.equal(f.element.style.getPropertyPriority('overflow'), 'important');
  assert.equal(f.element.style.getPropertyValue('height'), '');
});

test('reduced motion, keyboard preference, hidden or detached elements, and missing WAAPI skip animation', () => {
  const cases = [f => { f.media.matches = true; }, f => { f.preference.allowed = false; },
    f => { f.page.hidden = true; }, f => { f.element.hidden = true; },
    f => { f.element.isConnected = false; }, f => { f.element.animate = undefined; }];
  for (const prepare of cases) {
    const f = fixture();prepare(f);let changed = false;
    f.controller.change(f.element, () => { changed = true;f.element.height = 200; });
    assert.equal(changed, true);assert.equal(f.element.animations.length, 0);
    assert.equal(f.element.hasAttribute('style'), false);
  }
  const f = fixture();f.controller.change(f.element, () => {});
  f.controller.change(null, () => { f.changed = true; });
  assert.equal(f.element.animations.length, 0);assert.equal(f.changed, true);
});

test('all interruption events immediately cancel effects and release temporary styles', () => {
  const interruptions = [f => { f.media.matches = true;f.media.emit('change'); },
    f => { f.page.hidden = true;f.page.emit('visibilitychange'); },
    f => f.page.emit('keydown'), f => f.browser.emit('resize'), f => f.browser.emit('pagehide')];
  for (const interrupt of interruptions) {
    const f = fixture();f.controller.change(f.element, () => { f.element.height = 200; });interrupt(f);
    assert.equal(f.element.animations[0].cancelled, true);assert.equal(f.element.hasAttribute('style'), false);
  }
});

test('destroy removes every listener, settles native intention, and leaves later mutations immediate', () => {
  const f = fixture();f.details.open = true;f.click();
  f.controller.destroy();f.controller.destroy();
  assert.equal(f.details.open, false);assert.equal(f.details.hasAttribute('style'), false);
  assert.equal(f.page.listenerCount() + f.media.listenerCount() + f.browser.listenerCount(), 0);
  f.controller.change(f.element, () => { f.element.height = 200; });
  assert.equal(f.element.height, 200);assert.equal(f.element.animations.length, 0);
});

test('height endpoints account for content-box padding and borders', () => {
  const f = fixture();f.element.computed = { boxSizing: 'content-box', paddingTop: '8px', paddingBottom: '8px', borderTopWidth: '1px', borderBottomWidth: '1px' };
  f.controller.change(f.element, () => { f.element.height = 100; });
  assert.deepEqual(f.element.animations[0].frames, [{ height: '22px' }, { height: '82px' }]);
});

test('a WAAPI failure preserves the mutation and restores styles', () => {
  const f = fixture();f.element.animate = () => { throw new Error('unsupported'); };
  f.controller.change(f.element, () => { f.element.height = 100; });
  assert.equal(f.element.height, 100);assert.equal(f.element.hasAttribute('style'), false);
});

test('native details reveal and collapse painted content while exposing the closing state', () => {
  const f = fixture();assert.equal(f.click().prevented, true);
  assert.equal(f.details.open, true);f.details.animations[0].finish();
  f.body.setAttribute('inert', 'original');f.body.setAttribute('aria-hidden', 'false');
  f.page.activeElement = f.body;
  assert.equal(f.click().prevented, true);
  const collapse = f.details.animations[1];
  assert.equal(f.details.open, true);assert.equal(f.summary.getAttribute('aria-expanded'), 'false');
  assert.equal(f.body.getAttribute('aria-hidden'), 'true');assert.equal(f.summary.focused, true);
  assert.deepEqual(collapse.frames, [{ height: '180px' }, { height: '30px' }]);
  collapse.finish();
  assert.equal(f.details.open, false);assert.equal(f.summary.hasAttribute('aria-expanded'), false);
  assert.equal(f.body.getAttribute('inert'), 'original');assert.equal(f.body.getAttribute('aria-hidden'), 'false');
  assert.equal(f.details.hasAttribute('style'), false);
});

test('rapid native reversals retain the latest intention and sample each visible height', () => {
  const f = fixture();f.click();const opening = f.details.animations[0];opening.sample = 80;
  f.click();const closing = f.details.animations[1];closing.sample = 55;
  assert.deepEqual(closing.frames, [{ height: '80px' }, { height: '30px' }]);
  f.click();const reopening = f.details.animations[2];
  assert.equal(closing.cancelled, true);assert.equal(f.details.open, true);
  assert.deepEqual(reopening.frames, [{ height: '55px' }, { height: '180px' }]);
  closing.emit('finish');assert.equal(f.details.open, true);
  assert.equal(f.body.hasAttribute('inert'), false);assert.equal(f.body.hasAttribute('aria-hidden'), false);
  reopening.finish();assert.equal(f.details.open, true);assert.equal(f.details.hasAttribute('style'), false);
});

test('keyboard, modifier, prevented and interactive summary clicks keep their native defaults', () => {
  for (const options of [{ detail: 0 }, { ctrlKey: true }, { altKey: true }, { metaKey: true }, { shiftKey: true }, { button: 1 }, { defaultPrevented: true }]) {
    const f = fixture();assert.equal(f.click(f.summary, options).prevented, false);assert.equal(f.details.animations.length, 0);
  }
  for (const state of ['reduced', 'keyboard']) {
    const f = fixture();if (state === 'reduced') f.media.matches = true;else f.preference.allowed = false;
    assert.equal(f.click().prevented, false);
  }
  const f = fixture();const link = new Element('a');link.setAttribute('href', '#help');f.summary.append(link);
  assert.equal(f.click(link).prevented, false);
  const secondSummary = new Element('summary');f.details.append(secondSummary);
  assert.equal(f.click(secondSummary).prevented, false);assert.equal(f.details.animations.length, 0);
});

test('a zero-height details box can reveal help with an externally positioned summary', () => {
  const f = fixture();f.details.closedHeight = 0;
  assert.equal(f.click().prevented, true);
  assert.deepEqual(f.details.animations[0].frames, [{ height: '0px' }, { height: '180px' }]);
});
