import '@testing-library/jest-dom';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

/**
 * 4.5A.4-T4.4 — Vitest global setup.
 *
 * - jest-dom matchers (toHaveClass, toBeInTheDocument, etc.)
 * - jsdom polyfills required by Antd Table / ProTable:
 *     - ResizeObserver (column-resize observer)
 *     - matchMedia     (responsive breakpoints; vi.restoreAllMocks() would
 *                       wipe a vi.fn()-based mock, so we install a stable
 *                       plain function here)
 * - automatic RTL cleanup + localStorage clear between tests
 */

// ResizeObserver: Antd Table uses it for column auto-sizing; jsdom doesn't ship it.
if (typeof window !== 'undefined' && !(window as unknown as { ResizeObserver?: unknown }).ResizeObserver) {
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}

// matchMedia: Antd responsive components query it on mount.  Antd's
// responsiveObserver also immediately invokes `listener(mql)` (passing the
// mql object), so the returned object MUST carry a `matches` field or the
// listener destructuring throws "Cannot destructure 'matches' of undefined".
if (typeof window !== 'undefined' && !window.matchMedia) {
  // Stable plain function — NOT a vi.fn(), so vi.restoreAllMocks() cannot
  // clobber it back to undefined between tests.
  const mql = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  });
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: mql,
  });
}

afterEach(() => {
  cleanup();
  // Clear the persisted Zustand slices so tests don't leak auth/choices between runs.
  localStorage.clear();
});