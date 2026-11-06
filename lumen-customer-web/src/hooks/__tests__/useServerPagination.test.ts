/**
 * 4.5C-2.1 — 服务端分页 hook (缺口 #9)。
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { useServerPagination } from '../useServerPagination';

describe('useServerPagination', () => {
  it('初始页码 1 / 页大小 20', () => {
    const { result } = renderHook(() => useServerPagination(0));
    expect(result.current.page).toBe(1);
    expect(result.current.size).toBe(20);
    expect(result.current.pagination.current).toBe(1);
    expect(result.current.pagination.pageSize).toBe(20);
  });

  it('total 透传给 antd 的 pagination —— 商家要看到一共多少条', () => {
    const { result } = renderHook(() => useServerPagination(40));
    expect(result.current.pagination.total).toBe(40);
  });

  it('翻页只改页码, 页大小不变', () => {
    const { result } = renderHook(() => useServerPagination(0));
    act(() => result.current.pagination.onChange?.(3, 20));
    expect(result.current.page).toBe(3);
    expect(result.current.size).toBe(20);
  });

  it('改页大小回到第 1 页 —— 否则可能停在一个已不存在的页码上', () => {
    const { result } = renderHook(() => useServerPagination(0));
    act(() => result.current.pagination.onChange?.(4, 20));
    act(() => result.current.pagination.onChange?.(4, 50));
    expect(result.current.size).toBe(50);
    expect(result.current.page).toBe(1);
  });

  it('页大小选项上限 100 —— 不能超过后端 clamp 上限 200', () => {
    const { result } = renderHook(() => useServerPagination(0));
    const options = result.current.pagination.pageSizeOptions as string[];
    expect(Math.max(...options.map((o) => Number(o)))).toBeLessThanOrEqual(200);
  });
});
