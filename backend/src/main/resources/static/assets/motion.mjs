// Operational UI: only occasional, user-initiated feedback moves.
// The data tables and chart values always update immediately.
export function revealContent(element, { block = 'start', focus = false, animate = true, browser = window } = {}) {
  if (!element?.isConnected) return;
  // Focusing must not jump ahead of the browser's native, interruptible scroll.
  if (focus) element.focus({ preventScroll: true });
  const reduced = browser.matchMedia('(prefers-reduced-motion: reduce)').matches;
  element.scrollIntoView({ block, behavior: animate && !reduced ? 'smooth' : 'instant' });
}

export function createMotionController({ page = document, browser = window } = {}) {
  const media = browser.matchMedia('(prefers-reduced-motion: reduce)');
  const running = new Map();
  const navigation = new Map();
  const stopNavigation = () => {
    for (const animation of navigation.values()) animation.cancel();
    navigation.clear();
  };
  const setInput = event => {
    page.documentElement.dataset.motionInput = event.type === 'pointerdown' ? 'pointer' : 'keyboard';
    if (event.type === 'keydown') stop();
  };
  const stop = () => {
    stopNavigation();
    for (const animation of running.values()) animation.cancel();
    running.clear();
  };
  const preferenceChanged = () => { if (media.matches) stop(); };
  const visibilityChanged = () => { if (page.hidden) stop(); };
  page.documentElement.dataset.motionInput = 'keyboard';
  page.addEventListener('pointerdown', setInput, true);
  page.addEventListener('keydown', setInput, true);
  page.addEventListener('visibilitychange', visibilityChanged);
  media.addEventListener('change', preferenceChanged);
  const allowed = () => !media.matches && !page.hidden && page.documentElement.dataset.motionInput === 'pointer';
  return {
    allowed,
    navigateSelection(previous, next) {
      // A table refresh or a detail route in the same section keeps its selection.
      if (previous === next) return;
      const outgoing = previous?.querySelector('.nav-selection');
      const incoming = next?.querySelector('.nav-selection');
      const full = 'inset(0% 0% 0% 0% round 7px)';
      const closed = 'inset(50% 50% 50% 50% round 7px)';
      const currentClip = (layer, fallback) => navigation.has(layer)
        ? browser.getComputedStyle?.(layer).clipPath || fallback : fallback;
      const from = currentClip(outgoing, full), to = currentClip(incoming, closed);
      stopNavigation();
      if (!previous?.isConnected || !next?.isConnected || !allowed()) return;
      if (!previous.getClientRects().length || !next.getClientRects().length) return;
      if (!outgoing?.animate || !incoming?.animate) return;
      const shrink = outgoing.animate([{ clipPath: from }, { clipPath: closed }], {
        duration: 90, easing: 'cubic-bezier(0.4, 0, 0.6, 1)',
      });
      const grow = incoming.animate([{ clipPath: to }, { clipPath: full }], {
        duration: 150, delay: 90, fill: 'backwards', easing: 'cubic-bezier(0.16, 1, 0.3, 1)',
      });
      for (const [layer, animation] of [[outgoing, shrink], [incoming, grow]]) {
        navigation.set(layer, animation);
        const finished = () => { if (navigation.get(layer) === animation) navigation.delete(layer); };
        animation.addEventListener('finish', finished, { once: true });
        animation.addEventListener('cancel', finished, { once: true });
      }
    },
    acknowledge(element) {
      running.get(element)?.cancel();
      running.delete(element);
      if (!element?.isConnected || !element.animate || !allowed()) return;
      const animation = element.animate([
        { opacity: 0, transform: 'translateY(6px)' },
        { opacity: 1, transform: 'translateY(0)' },
      ], { duration: 180, easing: 'cubic-bezier(0.16, 1, 0.3, 1)' });
      running.set(element, animation);
      const finished = () => { if (running.get(element) === animation) running.delete(element); };
      animation.addEventListener('finish', finished, { once: true });
      animation.addEventListener('cancel', finished, { once: true });
    },
    destroy() {
      stop();
      page.removeEventListener('pointerdown', setInput, true);
      page.removeEventListener('keydown', setInput, true);
      page.removeEventListener('visibilitychange', visibilityChanged);
      media.removeEventListener('change', preferenceChanged);
      delete page.documentElement.dataset.motionInput;
    },
  };
}
