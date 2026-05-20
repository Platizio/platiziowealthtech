import { useEffect, useRef } from 'react';

/**
 * F-32: focus-trap for modal dialogs.
 *
 * While `active` is true:
 *  - On activation, focus moves to the first focusable element inside the
 *    container (avoids keyboard users having to Tab in from outside).
 *  - Tab / Shift+Tab cycle focus within the container instead of escaping
 *    to the page behind the modal.
 *  - On deactivation (active flips to false, or component unmounts), focus
 *    is restored to whatever element had focus before the modal opened —
 *    the WAI-ARIA Authoring Practices expectation for dialogs.
 *
 * Returns a ref to attach to the modal's outermost element. The element
 * should also carry role="dialog" + aria-modal="true" + aria-labelledby.
 *
 * Usage:
 *   const trapRef = useFocusTrap<HTMLDivElement>(isOpen);
 *   return isOpen ? (
 *     <div ref={trapRef} role="dialog" aria-modal="true" aria-labelledby="x">
 *       ...
 *     </div>
 *   ) : null;
 */
export function useFocusTrap<T extends HTMLElement = HTMLDivElement>(active: boolean) {
  const ref = useRef<T>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!active) return;

    // Remember whatever had focus before the modal opened — we'll restore it
    // on close. Cast is safe: document.activeElement is always an Element
    // (defaults to <body>) and HTMLElement covers everything that can
    // legitimately hold focus in practice.
    previouslyFocused.current = document.activeElement as HTMLElement;

    const container = ref.current;
    if (!container) return;

    // Standard set of focusable selectors. We deliberately exclude
    // tabindex="-1" so devs can programmatically focus utility elements
    // (e.g. a scroll target) without polluting the Tab cycle.
    const FOCUSABLE_SELECTOR =
      'a[href], area[href], button:not([disabled]), ' +
      'input:not([disabled]):not([type=hidden]), select:not([disabled]), ' +
      'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

    // offsetParent === null filters out elements that are display:none or
    // inside a display:none ancestor, which aren't actually focusable.
    const getFocusable = () =>
      Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
        .filter(el => el.offsetParent !== null);

    // Move focus into the dialog on open.
    getFocusable()[0]?.focus();

    const handleKey = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;
      const focusable = getFocusable();
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const activeEl = document.activeElement as HTMLElement | null;

      if (e.shiftKey) {
        // Shift+Tab from the first element wraps to the last.
        if (activeEl === first || !container.contains(activeEl)) {
          e.preventDefault();
          last.focus();
        }
      } else {
        // Tab from the last element wraps to the first.
        if (activeEl === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };

    container.addEventListener('keydown', handleKey);

    return () => {
      container.removeEventListener('keydown', handleKey);
      // Restore focus to whatever was focused before the trap activated.
      previouslyFocused.current?.focus?.();
    };
  }, [active]);

  return ref;
}
