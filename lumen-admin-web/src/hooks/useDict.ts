/**
 * useDict — fetch a SysDict's items and return them as ProTable valueEnum.
 *
 * Usage:
 *   const { valueEnum, loading } = useDict('user_status');
 *   <ProTable columns={[{ title: '状态', valueType: 'select', valueEnum }]} />
 *
 * Caches results in sessionStorage (key: `lumen:dict:${code}`) so navigating
 * away and back doesn't re-fetch within the same session.
 *
 * Returns:
 *   - `valueEnum`: { [itemCode]: { text, status? } } once loaded
 *   - `loading`: true during fetch
 *   - `error`: Error instance if fetch failed
 *
 * If the dict doesn't exist or has no items, valueEnum is `{}`.
 */

import { useEffect, useState } from 'react';

import type { PresetStatusColorType } from 'antd/es/_util/colors';
import request from '@/services/request';

export interface DictItem {
  code: string;
  text: string;
  status?: PresetStatusColorType;
}

export interface DictValueEnum {
  [itemCode: string]: { text: string; status?: PresetStatusColorType };
}

const CACHE_PREFIX = 'lumen:dict:';

function readCache(code: string): DictValueEnum | null {
  if (typeof window === 'undefined') return null;
  const raw = window.sessionStorage.getItem(CACHE_PREFIX + code);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as DictValueEnum;
  } catch {
    return null;
  }
}

function writeCache(code: string, value: DictValueEnum): void {
  if (typeof window === 'undefined') return;
  window.sessionStorage.setItem(CACHE_PREFIX + code, JSON.stringify(value));
}

export interface UseDictResult {
  valueEnum: DictValueEnum;
  loading: boolean;
  error: Error | null;
}

export function useDict(code: string): UseDictResult {
  const cached = readCache(code);
  const [valueEnum, setValueEnum] = useState<DictValueEnum>(cached ?? {});
  const [loading, setLoading] = useState<boolean>(!cached);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    if (cached) return; // already have it
    let cancelled = false;
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
  }, [code]); // cached is intentionally not a dep — it's only checked once at mount

  return { valueEnum, loading, error };
}