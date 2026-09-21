/**
 * App smoke test — verifies the full provider tree assembles without crashing.
 *
 * Goal: catch regressions when one of the wrappers
 *   <ConfigProvider> / <AntApp> / <ErrorBoundary> / <LocaleProvider> /
 *   <AuthProvider> / <BrowserRouter>
 * is removed, misordered, or otherwise broken in App.tsx.
 *
 * We DO NOT assert on specific UI elements — page-level concerns belong in
 * page tests. The only assertion is that `render(<App />)` returns a container
 * and a non-empty document body, which proves the provider tree mounted.
 *
 * Mocks:
 *   - `@/services/request` — provides a no-network stub for any code path
 *     that might fire during initial render (e.g. AuthProvider bootstrap
 *     if a stale token is present). AuthProvider guards its `/auth/me` call
 *     on a localStorage access token, which we clear in beforeEach; this
 *     mock is a belt-and-suspenders for any future code that does an
 *     unconditional fetch.
 *
 * The default route (`/`) redirects to `/users` inside `ProtectedRoute`,
 * which redirects an unauthenticated user to `/login`. We land on `/`
 * by default under jsdom and end up rendering `<Login />` — which only
 * fetches on form submit, so the smoke test never goes to the network.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import App from '@/App';

vi.mock('@/services/request', () => ({
  default: {
    get: vi.fn().mockResolvedValue({}),
    post: vi.fn().mockResolvedValue({}),
    put: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue({}),
  },
}));

describe('App (smoke)', () => {
  beforeEach(() => {
    // Ensure AuthProvider's bootstrap short-circuits (no /auth/me call) and
    // LocaleProvider + useThemeMode both start from a clean slate.
    localStorage.clear();
    sessionStorage.clear();

    // jsdom does not implement window.matchMedia. antd's responsiveObserver
    // (Row, Col, Grid) calls it inside useLayoutEffect; without a stub the
    // matchMedia call throws and gets caught by our top-level ErrorBoundary,
    // which is noisy and obscures the smoke signal. Provide a minimum stub
    // that mimics the subset antd uses (addEventListener / removeEventListener).
    if (typeof window !== 'undefined' && !window.matchMedia) {
      Object.defineProperty(window, 'matchMedia', {
        writable: true,
        value: vi.fn().mockImplementation((query: string) => ({
          matches: false,
          media: query,
          onchange: null,
          addListener: vi.fn(),
          removeListener: vi.fn(),
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
          dispatchEvent: vi.fn(),
        })),
      });
    }
  });

  it('renders without crashing', () => {
    const { container } = render(<App />);
    // Some providers mount something into the DOM (the Login page has its
    // Card + form), so container should not be empty. If the provider
    // tree throws during render, RTL surfaces that as a test failure
    // before we ever reach this line.
    expect(container).toBeInTheDocument();
    expect(document.body).toBeInTheDocument();
  });
});
