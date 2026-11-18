import test from 'node:test';
import assert from 'node:assert/strict';
import { createMotionController } from '../backend/src/main/resources/static/assets/motion.mjs';

function fixture({ reduced = false } = {}) {
  const events = () => {
    const listeners = new Map();
    return {
      addEventListener: (type, callback) => listeners.set(type, callback),
      removeEventListener: type => listeners.delete(type),
      emit: (type, event = {}) => listeners.get(type)?.({ ...event, type }),
      count: () => listeners.size,
    };
  };
  const page = { ...events(), hidden: false, documentElement: { dataset: {} } };
  const media = { ...events(), matches: reduced };
  const animations = [];
  const element = { isConnected: true, animate: (frames, options) => {
    const animation = { ...events(), cancelled: false, cancel() { this.cancelled = true;this.emit('cancel'); } };
    animations.push({ frames, options, animation });
    return animation;
  } };
  const controller = createMotionController({ page, browser: { matchMedia: () => media } });
  return { page, media, animations, element, controller };
}

test('feedback remains immediate for keyboard users, reduced motion and hidden pages', () => {
  const f = fixture();
  f.controller.acknowledge(f.element);
  assert.equal(f.animations.length, 0);
  f.page.emit('pointerdown');f.media.matches = true;
  f.controller.acknowledge(f.element);
  f.media.matches = false;f.page.hidden = true;
  f.controller.acknowledge(f.element);
  assert.equal(f.animations.length, 0);
});

test('a replacement notice cancels only its previous motion and never changes its content', () => {
  const f = fixture();f.page.emit('pointerdown');
  f.controller.acknowledge(f.element);
  f.controller.acknowledge(f.element);
  assert.equal(f.animations.length, 2);
  assert.equal(f.animations[0].animation.cancelled, true);
  assert.equal(f.animations[1].animation.cancelled, false);
  assert.equal(f.animations[1].options.duration, 180);
  assert.equal(f.animations[1].options.fill, undefined);
  f.page.emit('keydown');
  assert.equal(f.animations[1].animation.cancelled, true);
});

test('system preference changes and backgrounding immediately stop outstanding motion', () => {
  const f = fixture();f.page.emit('pointerdown');
  f.controller.acknowledge(f.element);
  f.media.matches = true;f.media.emit('change');
  assert.equal(f.animations[0].animation.cancelled, true);
  f.media.matches = false;f.controller.acknowledge(f.element);
  f.page.hidden = true;f.page.emit('visibilitychange');
  assert.equal(f.animations[1].animation.cancelled, true);
});

test('unmounted elements and browsers without WAAPI still work; destroy removes listeners', () => {
  const f = fixture();f.page.emit('pointerdown');
  f.element.isConnected = false;f.controller.acknowledge(f.element);
  f.controller.acknowledge({ isConnected: true });
  assert.equal(f.animations.length, 0);
  f.controller.destroy();
  assert.equal(f.page.documentElement.dataset.motionInput, undefined);
  assert.equal(f.media.count(), 0);
  assert.equal(f.page.count(), 0);
});
