// Operational UI: only occasional, user-initiated feedback moves.
// The data tables and chart values always update immediately.
export function createMotionController({ page = document, browser = window } = {}) {
  const media = browser.matchMedia('(prefers-reduced-motion: reduce)');
  const running = new Map();
  const setInput = event => {
    page.documentElement.dataset.motionInput = event.type === 'pointerdown' ? 'pointer' : 'keyboard';
    if (event.type === 'keydown') stop();
  };
  const stop = () => {
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
