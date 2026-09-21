/**
 * Lumen Admin — useThemeMode hook.
 *
 * Owns the single source of truth for the current light/dark mode.
 * - Persists the user's choice to localStorage under `lumen_theme_mode`.
 * - Falls back to `prefers-color-scheme` when nothing is stored
 *   (first visit / private mode).
 * - Mirrors the active mode onto <html> as `theme-light` / `theme-dark`
 *   so any non-Antd CSS can react (e.g. a custom background gradient).
 *
 * SSR-safe: every browser-API call is gated on `typeof window !== 'undefined'`
 * or wrapped in try/catch (localStorage may be blocked in private mode).
 *
 * Returns `[mode, setMode, toggle]` so a UI toggle button can wire up
 * directly with the third tuple slot (Task 4.2).
 */

import { useCallback, useEffect, useState } from 'react';

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'lumen_theme_mode';

function readInitialMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  } catch {
    /* localStorage may be blocked (SSR, private mode) */
  }
  if (
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-color-scheme: dark)').matches
  ) {
    return 'dark';
  }
  return 'light';
}

export function useThemeMode(): [ThemeMode, (mode: ThemeMode) => void, () => void] {
  const [mode, setMode] = useState<ThemeMode>(readInitialMode);

  // Apply class to <html> for any non-Antd CSS that needs to react (e.g. custom backgrounds).
  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('theme-dark', mode === 'dark');
    root.classList.toggle('theme-light', mode === 'light');
  }, [mode]);

  const setAndPersist = useCallback((next: ThemeMode) => {
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* swallow */
    }
    setMode(next);
  }, []);

  const toggle = useCallback(() => {
    setAndPersist(mode === 'dark' ? 'light' : 'dark');
  }, [mode, setAndPersist]);

  return [mode, setAndPersist, toggle];
}