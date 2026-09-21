/**
 * Lumen Admin — minimal in-house i18n.
 *
 * Self-rolled (no react-intl) to keep the bundle small — we only have ~30 keys.
 * Locale state is React Context; persistence is localStorage.
 *
 * Usage:
 *   const { t, locale, setLocale } = useLocale();
 *   <span>{t('menu.users')}</span>
 *
 * If `t(key)` is called with an unknown key, it returns the key itself (so
 * missing translations show up as visible literals rather than crashing).
 */
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

import { zhCN, type ZhKey } from './zh-CN';
import { enUS } from './en-US';

export type Locale = 'zh-CN' | 'en-US';
export type TranslationKey = ZhKey;

const STORAGE_KEY = 'lumen_locale';
const DEFAULT_LOCALE: Locale = 'zh-CN';

const DICTIONARIES: Record<Locale, Record<string, string>> = {
  'zh-CN': zhCN as Record<string, string>,
  'en-US': enUS,
};

interface LocaleContextValue {
  locale: Locale;
  setLocale: (next: Locale) => void;
  t: (key: TranslationKey) => string;
}

const LocaleContext = createContext<LocaleContextValue | null>(null);

function readInitialLocale(): Locale {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'zh-CN' || stored === 'en-US') return stored;
  } catch {
    /* SSR / private mode */
  }
  return DEFAULT_LOCALE;
}

export function LocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(readInitialLocale);

  const setLocale = useCallback((next: Locale) => {
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* swallow */
    }
    setLocaleState(next);
  }, []);

  const value = useMemo<LocaleContextValue>(() => {
    const dict = DICTIONARIES[locale];
    return {
      locale,
      setLocale,
      t: (key) => dict[key] ?? key,
    };
  }, [locale, setLocale]);

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale(): LocaleContextValue {
  const ctx = useContext(LocaleContext);
  if (!ctx) throw new Error('useLocale must be used inside <LocaleProvider>');
  return ctx;
}
