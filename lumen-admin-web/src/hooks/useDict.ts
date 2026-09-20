import { useEffect, useState } from 'react';

import type { BadgeProps } from 'antd';
import request from '@/services/request';

export interface DictItem {
  code: string;
  text: string;
  status?: NonNullable<BadgeProps['status']>;
}

export interface DictValueEnum {
  [itemCode: string]: { text: string; status?: NonNullable<BadgeProps['status']> };
}

/**
 * Module-level cache. Stored outside React state so the value is always
 * live (no stale closure on re-render). Cleared by `clearDictCache()` which
 * AuthProvider.logout() calls — preventing cross-user data leakage.
 *
 * Per-tab sessionStorage mirrors this map so the cache survives a page
 * refresh within the same tab/session.
 */
const cache = new Map<string, DictValueEnum>();
const CACHE_PREFIX = 'lumen:dict:';

function readCache(code: string): DictValueEnum | undefined {
  if (typeof window === 'undefined') return undefined;
  // Memory first (authoritative); fall back to sessionStorage (for reloads).
  const mem = cache.get(code);
  if (mem) return mem;
  const raw = window.sessionStorage.getItem(CACHE_PREFIX + code);
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(raw) as DictValueEnum;
    cache.set(code, parsed);
    return parsed;
  } catch {
    return undefined;
  }
}

function writeCache(code: string, value: DictValueEnum): void {
  cache.set(code, value);
  if (typeof window !== 'undefined') {
    try {
      window.sessionStorage.setItem(CACHE_PREFIX + code, JSON.stringify(value));
    } catch {
      // sessionStorage full or disabled — degrade gracefully.
    }
  }
}

/**
 * Clear all cached dict entries. Call this from AuthProvider.logout() so
 * the next user on the same tab doesn't see the previous user's dict data.
 */
export function clearDictCache(): void {
  cache.clear();
  if (typeof window !== 'undefined') {
    const keys: string[] = [];
    for (let i = 0; i < window.sessionStorage.length; i++) {
      const k = window.sessionStorage.key(i);
      if (k && k.startsWith(CACHE_PREFIX)) keys.push(k);
    }
    for (const k of keys) window.sessionStorage.removeItem(k);
  }
}

export interface UseDictResult {
  valueEnum: DictValueEnum;
  loading: boolean;
  error: Error | null;
}

export function useDict(code: string): UseDictResult {
  const [valueEnum, setValueEnum] = useState<DictValueEnum>({});
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let cancelled = false;
    const cached = readCache(code);
    if (cached) {
      // Synchronous cache hit — show immediately, skip fetch.
      setValueEnum(cached);
      setLoading(false);
      setError(null);
      return () => {
        cancelled = true;
      };
    }
    setValueEnum({});
    setLoading(true);
    setError(null);
    request
      .get<unknown>(`/dicts/${encodeURIComponent(code)}/items`)
      .then((res) => {
        if (cancelled) return;
        const items = (res as unknown as DictItem[]) ?? [];
        const next: DictValueEnum = {};
        for (const it of items) {
          next[it.code] = { text: it.text, status: it.status };
        }
        writeCache(code, next);
        setValueEnum(next);
      })
      .catch((err: Error) => {
        if (cancelled) return;
        setError(err);
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [code]);

  return { valueEnum, loading, error };
}
