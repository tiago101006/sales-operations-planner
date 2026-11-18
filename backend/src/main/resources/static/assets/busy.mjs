const activeFeedback = new WeakMap();

/** Show real pending work only after the delay; the caller starts and completes the work. */
export function startBusyFeedback(button, { delay = 180, label = 'A guardar…', scheduler = globalThis } = {}) {
  if (!button) return () => {};
  activeFeedback.get(button)?.();

  let cancelled = false, restore;
  const cleanup = () => {
    if (cancelled) return;
    cancelled = true;
    scheduler.clearTimeout(timer);
    restore?.();
    if (activeFeedback.get(button) === cleanup) activeFeedback.delete(button);
  };
  const timer = scheduler.setTimeout(() => {
    if (cancelled || !button.isConnected) return;

    const children = [...button.childNodes];
    const attributes = ['aria-busy', 'aria-label', 'aria-labelledby'].map(name => [name, button.getAttribute(name)]);
    const hadStyle = button.hasAttribute('style');
    const sizes = ['min-width', 'min-height'].map(name => [name, button.style.getPropertyValue(name), button.style.getPropertyPriority(name)]);
    const { width, height } = button.getBoundingClientRect();
    restore = () => {
      button.replaceChildren(...children);
      for (const [name, value] of attributes) {
        if (value === null) button.removeAttribute(name);
        else button.setAttribute(name, value);
      }
      for (const [name, value, priority] of sizes) {
        if (value) button.style.setProperty(name, value, priority);
        else button.style.removeProperty(name);
      }
      if (!hadStyle && !button.getAttribute('style')) button.removeAttribute('style');
    };

    // Keep the click target stable without fixing a maximum width for a longer label.
    button.style.setProperty('min-width', `${width}px`);
    button.style.setProperty('min-height', `${height}px`);
    button.replaceChildren(label);
    button.setAttribute('aria-busy', 'true');
    // An existing labelledby/label must not keep announcing the idle action.
    button.removeAttribute('aria-labelledby');
    button.setAttribute('aria-label', label);
  }, delay);
  activeFeedback.set(button, cleanup);
  return cleanup;
}
