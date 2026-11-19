import test from 'node:test';
import assert from 'node:assert/strict';
import { createMotionController } from '../backend/src/main/resources/static/assets/motion.mjs';

function events() {
  const listeners = new Map();
  return {
    addEventListener(type, callback) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type).add(callback);
    },
    removeEventListener(type, callback) { listeners.get(type)?.delete(callback); },
    emit(type, event = {}) {
      for (const callback of [...(listeners.get(type) || [])]) callback({ ...event, type });
    },
  };
}

function fixture({ reduced = false, computedStyles = false } = {}) {
  const page = { ...events(), hidden: false, documentElement: { dataset: {} } };
  const media = { ...events(), matches: reduced };
  const animations = [];
  const links = ['a', 'b', 'c'].map(name => {
    const classes = new Set(['nav-link']);
    const attributes = new Map();
    const layer = {
      style: { clipPath: '' },
      animate(frames, options) {
        const animation = {
          ...events(),
          cancelled: false,
          cancel() {
            this.cancelled = true;
            layer.computedClipPath = '';
            this.emit('cancel');
          },
        };
        animations.push({ name, frames, options, animation });
        return animation;
      },
    };
    return {
      name,
      isConnected: true,
      visible: true,
      style: { background: '', color: '' },
      layer,
      getClientRects() { return this.visible ? [{ width: 100, height: 40 }] : []; },
      querySelector: selector => selector === '.nav-selection' ? layer : null,
      classList: {
        contains: name => classes.has(name),
        toggle(name, enabled) { if (enabled) classes.add(name);else classes.delete(name); },
      },
      setAttribute: (name, value) => attributes.set(name, value),
      removeAttribute: name => attributes.delete(name),
      getAttribute: name => attributes.get(name) ?? null,
    };
  });
  const activate = selected => links.forEach(link => {
    link.classList.toggle('active', link === selected);
    if (link === selected) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
  const domState = () => links.map(link => ({
    active: link.classList.contains('active'),
    current: link.getAttribute('aria-current'),
    style: { ...link.style },
    layerStyle: { ...link.layer.style },
  }));
  const browser = { matchMedia: () => media };
  if (computedStyles) browser.getComputedStyle = layer => ({ clipPath: layer.computedClipPath || '' });
  const controller = createMotionController({ page, browser });
  return { page, media, animations, links, activate, domState, controller };
}

test('initial selection and rendering the current section do not replay navigation motion', () => {
  const f = fixture();
  const [a] = f.links;
  f.page.emit('pointerdown');f.activate(a);
  const before = f.domState();
  f.controller.navigateSelection(null, a);
  f.controller.navigateSelection(a, a);
  assert.equal(f.animations.length, 0);
  assert.deepEqual(f.domState(), before);
});

test('navigation remains immediate when motion or visible navigation is unavailable', async t => {
  const cases = [
    ['keyboard input', () => {}],
    ['reduced motion', f => { f.page.emit('pointerdown');f.media.matches = true; }],
    ['background page', f => { f.page.emit('pointerdown');f.page.hidden = true; }],
    ['hidden outgoing option', f => { f.page.emit('pointerdown');f.links[0].visible = false; }],
    ['hidden incoming option', f => { f.page.emit('pointerdown');f.links[1].visible = false; }],
    ['unmounted outgoing option', f => { f.page.emit('pointerdown');f.links[0].isConnected = false; }],
    ['unmounted incoming option', f => { f.page.emit('pointerdown');f.links[1].isConnected = false; }],
    ['missing selection layer', f => { f.page.emit('pointerdown');f.links[1].querySelector = () => null; }],
    ['browser without WAAPI', f => { f.page.emit('pointerdown');f.links[1].layer.animate = undefined; }],
  ];
  for (const [name, setup] of cases) await t.test(name, () => {
    const f = fixture();
    const [a, b] = f.links;
    setup(f);f.activate(b);
    const before = f.domState();
    f.controller.navigateSelection(a, b);
    assert.equal(f.animations.length, 0);
    assert.deepEqual(f.domState(), before);
  });
});

test('pointer selection shrinks the old pill before revealing the new pill without moving labels', () => {
  const f = fixture();
  const [a, b] = f.links;
  f.page.emit('pointerdown');f.activate(b);
  const before = f.domState();
  f.controller.navigateSelection(a, b);
  assert.equal(f.animations.length, 2);
  const [outgoing, incoming] = f.animations;
  assert.equal(outgoing.name, 'a');
  assert.equal(incoming.name, 'b');
  assert.equal(outgoing.options.duration, 90);
  assert.equal(incoming.options.duration, 150);
  assert.equal(incoming.options.delay, 90);
  assert.equal(incoming.options.fill, 'backwards');
  assert.ok(!['forwards', 'both'].includes(outgoing.options.fill));
  for (const { frames } of f.animations) {
    assert.equal(frames.length, 2);
    for (const frame of frames) assert.deepEqual(Object.keys(frame), ['clipPath']);
    assert.notEqual(frames[0].clipPath, frames[1].clipPath);
  }
  assert.equal(outgoing.frames[0].clipPath, incoming.frames[1].clipPath);
  assert.equal(outgoing.frames[1].clipPath, incoming.frames[0].clipPath);
  assert.deepEqual(f.domState(), before);
});

test('rapid selections cancel the previous pair and shrink from its visible progress', async t => {
  const cases = [
    ['incoming pill is still waiting', 'inset(50% 50% 50% 50% round 7px)'],
    ['incoming pill is partially revealed', 'inset(31% 31% 31% 31% round 7px)'],
  ];
  for (const [name, clipPath] of cases) await t.test(name, () => {
    const f = fixture({ computedStyles: true });
    const [a, b, c] = f.links;
    f.page.emit('pointerdown');f.activate(b);
    f.controller.navigateSelection(a, b);
    b.layer.computedClipPath = clipPath;
    f.activate(c);
    const before = f.domState();
    f.controller.navigateSelection(b, c);
    assert.equal(f.animations.length, 4);
    assert.deepEqual(f.animations.map(({ animation }) => animation.cancelled), [true, true, false, false]);
    assert.deepEqual(f.animations.slice(2).map(({ name }) => name), ['b', 'c']);
    assert.equal(f.animations[2].frames[0].clipPath, clipPath);
    assert.deepEqual(f.domState(), before);
  });
});

test('returning to a departing option preserves both pills current clips before cancellation', () => {
  const f = fixture({ computedStyles: true });
  const [a, b] = f.links;
  f.page.emit('pointerdown');f.activate(b);
  f.controller.navigateSelection(a, b);
  const departingClip = 'inset(18% 18% 18% 18% round 7px)';
  const arrivingClip = 'inset(42% 42% 42% 42% round 7px)';
  a.layer.computedClipPath = departingClip;
  b.layer.computedClipPath = arrivingClip;
  f.activate(a);
  const before = f.domState();
  f.controller.navigateSelection(b, a);
  assert.equal(f.animations.length, 4);
  assert.deepEqual(f.animations.map(({ animation }) => animation.cancelled), [true, true, false, false]);
  assert.equal(f.animations[2].name, 'b');
  assert.equal(f.animations[2].frames[0].clipPath, arrivingClip);
  assert.equal(f.animations[3].name, 'a');
  assert.equal(f.animations[3].frames[0].clipPath, departingClip);
  assert.deepEqual(f.domState(), before);
});

test('keyboard input, preference changes and backgrounding cancel both pills without changing the active DOM', async t => {
  const cases = [
    ['keyboard input', f => f.page.emit('keydown', { key: 'Tab' })],
    ['reduced motion enabled', f => { f.media.matches = true;f.media.emit('change'); }],
    ['page backgrounded', f => { f.page.hidden = true;f.page.emit('visibilitychange'); }],
    ['controller destroyed', f => f.controller.destroy()],
  ];
  for (const [name, interrupt] of cases) await t.test(name, () => {
    const f = fixture();
    const [a, b] = f.links;
    f.page.emit('pointerdown');f.activate(b);
    f.controller.navigateSelection(a, b);
    const before = f.domState();
    interrupt(f);
    assert.equal(f.animations.length, 2);
    assert.ok(f.animations.every(({ animation }) => animation.cancelled));
    assert.deepEqual(f.domState(), before);
  });
});
