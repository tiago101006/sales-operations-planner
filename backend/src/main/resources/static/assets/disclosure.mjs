// A short, bounded height animation lets neighbouring content move with a reveal.
// Mutations always happen in this task; animation never delays form/state changes.
export function createDisclosureController({ page = document, browser = window, shouldAnimate = () => true } = {}) {
  const media = browser.matchMedia?.('(prefers-reduced-motion: reduce)');
  const running = new Map();
  const ownedStyles = new Set(['height', 'overflow', 'overflow-x', 'overflow-y']);
  let destroyed = false;

  const declarations = style => Array.from({ length: style.length }, (_, index) => {
    const name = style.item(index);
    return [name, style.getPropertyValue(name), style.getPropertyPriority(name)];
  });
  const preserveStyles = element => {
    const original = declarations(element.style);
    const cssText = element.style.cssText;
    const hadStyle = element.hasAttribute('style');
    return () => {
      const unrelated = entries => entries.filter(([name]) => !ownedStyles.has(name));
      if (JSON.stringify(unrelated(declarations(element.style))) === JSON.stringify(unrelated(original))) {
        element.style.cssText = cssText;
      } else {
        // Do not discard unrelated inline styles changed by another component.
        for (const name of ownedStyles) element.style.removeProperty(name);
        for (const [name, value, priority] of original) {
          if (ownedStyles.has(name)) element.style.setProperty(name, value, priority);
        }
      }
      if (!hadStyle && !element.style.length) element.removeAttribute('style');
    };
  };
  const visibleHeight = element => {
    if (!element?.isConnected || !element.getClientRects?.().length) return null;
    const height = element.getBoundingClientRect().height;
    return Number.isFinite(height) ? height : null;
  };
  const allowed = element => !destroyed && !page.hidden && !media?.matches
    && shouldAnimate() && typeof element?.animate === 'function';
  const settle = record => {
    if (!record || running.get(record.element) !== record) return;
    running.delete(record.element);
    record.animation.removeEventListener('finish', record.done);
    record.animation.removeEventListener('cancel', record.done);
    // Remove the effect before releasing its temporary inline declarations.
    record.animation.cancel();
    record.finish?.();
    record.restore();
    record.release?.();
  };
  const stop = () => { for (const record of [...running.values()]) settle(record); };
  const preferenceChanged = () => { if (media.matches) stop(); };
  const visibilityChanged = () => { if (page.hidden) stop(); };
  const pixels = (height, style) => {
    const inset = style?.boxSizing === 'content-box'
      ? ['paddingTop', 'paddingBottom', 'borderTopWidth', 'borderBottomWidth']
        .reduce((total, name) => total + (Number.parseFloat(style[name]) || 0), 0) : 0;
    return `${Math.max(0, height - inset)}px`;
  };

  const change = (element, mutate, details = {}) => {
    // Read before cancelling: the previous effect may be halfway through a reveal.
    const before = visibleHeight(element);
    settle(running.get(element));
    const result = mutate();
    if (before === null || !element?.style || !allowed(element) || visibleHeight(element) === null) {
      details.finish?.();
      return result;
    }

    const restore = preserveStyles(element);
    element.style.setProperty('height', 'auto');
    const after = visibleHeight(element);
    const computed = browser.getComputedStyle?.(element);
    if (after === null || Math.abs(after - before) < 0.5) {
      restore();
      details.finish?.();
      return result;
    }

    // hidden retains absolutely positioned summary controls whose containing
    // block is outside this details element (the panel-help layout).
    element.style.setProperty('overflow', 'hidden');
    const release = details.prepare?.();
    let animation;
    try {
      animation = element.animate([
        { height: pixels(before, computed) },
        { height: pixels(after, computed) },
      ], { duration: 220, easing: 'cubic-bezier(0.16, 1, 0.3, 1)', fill: 'both' });
    } catch {
      details.finish?.();
      restore();
      release?.();
      return result;
    }
    const record = { element, animation, restore, release, finish: details.finish, open: details.open };
    record.done = () => settle(record);
    running.set(element, record);
    animation.addEventListener('finish', record.done);
    animation.addEventListener('cancel', record.done);
    return result;
  };

  const preserveAttribute = (element, name) => {
    const value = element.getAttribute(name);
    return () => value === null ? element.removeAttribute(name) : element.setAttribute(name, value);
  };
  const summaryClick = event => {
    if (event.defaultPrevented || event.button > 0 || event.detail === 0
      || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    const summary = event.target?.closest?.('summary');
    const element = summary?.parentElement;
    if (element?.tagName !== 'DETAILS'
      || Array.from(element.children).find(child => child.tagName === 'SUMMARY') !== summary) return;
    const interactive = event.target.closest('a[href], button, input, select, textarea, [contenteditable], [role="button"], [role="link"]');
    if (interactive && interactive !== summary && summary.contains(interactive)) return;
    if (!allowed(element) || visibleHeight(element) === null) return;

    event.preventDefault();
    const open = !(running.get(element)?.open ?? element.open);
    change(element, () => { element.open = open; }, {
      open,
      finish: () => { element.open = open; },
      prepare: open ? undefined : () => {
        // Keep the body painted during collapse while exposing its intended
        // closed state and removing its controls from keyboard navigation.
        element.open = true;
        const restoreAttributes = [preserveAttribute(summary, 'aria-expanded')];
        summary.setAttribute('aria-expanded', 'false');
        if (element.contains(page.activeElement) && !summary.contains(page.activeElement)) {
          summary.focus({ preventScroll: true });
        }
        for (const child of element.children) {
          if (child === summary) continue;
          restoreAttributes.push(preserveAttribute(child, 'inert'), preserveAttribute(child, 'aria-hidden'));
          child.setAttribute('inert', '');
          child.setAttribute('aria-hidden', 'true');
        }
        return () => { for (const restore of restoreAttributes) restore(); };
      },
    });
  };

  page.addEventListener('click', summaryClick);
  page.addEventListener('keydown', stop, true);
  page.addEventListener('visibilitychange', visibilityChanged);
  media?.addEventListener('change', preferenceChanged);
  browser.addEventListener?.('resize', stop);
  browser.addEventListener?.('pagehide', stop);

  return {
    change: (element, mutate) => change(element, mutate),
    // A navigation destination must be measured after pending height changes.
    finish: stop,
    destroy() {
      if (destroyed) return;
      destroyed = true;
      stop();
      page.removeEventListener('click', summaryClick);
      page.removeEventListener('keydown', stop, true);
      page.removeEventListener('visibilitychange', visibilityChanged);
      media?.removeEventListener('change', preferenceChanged);
      browser.removeEventListener?.('resize', stop);
      browser.removeEventListener?.('pagehide', stop);
    },
  };
}
