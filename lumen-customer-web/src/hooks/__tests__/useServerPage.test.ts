/**
 * 4.5C-2.1 — 服务端分页 hook (缺口 #9) 的完整版: 请求 + 分页 + 错误 + 重试。
 *
 * 这个文件的存在理由: 复审发现「size 从 hook 到 wire 的整条链路没有任何断言」
 * —— 把 size 删掉, 44 条用例全绿。所以下面每一条都对着**具体的一条链路**:
 * 页码、页大小、错误、重试、页码越界。
 */

import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { useServerPage } from '../useServerPage';
import type { MerchantPage } from '../../types/billing';

interface Row {
  id: number;
}

/**
 * 造一个 fetcher —— 返回 total 条、第 page 页、每页 size 条。
 *
 * 默认 total=1000 是为了让「翻到第 3 / 4 / 5 页」在 total=1000、size=20 下
 * 都**合法** (最后一页是 50)。若 total 只有 40, 第 3 页就越界了, hook 会
 * (正确地) 把它夹回第 2 页 —— 那样测的就不是「翻页」而是「越界夹回」了。
 */
function makeFetcher(total = 1000) {
  return vi.fn(async (page: number, size: number): Promise<MerchantPage<Row>> => ({
    records: [{ id: page }],
    total,
    page,
    size,
  }));
}

describe('useServerPage', () => {
  it('初始页码 1 / 页大小 20', async () => {
    const fetcher = makeFetcher();
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.page).toBe(1);
    expect(result.current.size).toBe(20);
    expect(result.current.pagination.current).toBe(1);
    expect(result.current.pagination.pageSize).toBe(20);
  });

  it('total 透传给 antd 的 pagination —— 商家要看到一共多少条', async () => {
    const fetcher = makeFetcher(40);
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.total).toBe(40));
    expect(result.current.pagination.total).toBe(40);
  });

  it('翻页只改页码, 页大小不变', async () => {
    const fetcher = makeFetcher();
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      result.current.pagination.onChange?.(3, 20);
    });
    expect(result.current.page).toBe(3);
    expect(result.current.size).toBe(20);
  });

  it('改页大小回到第 1 页 —— 否则可能停在一个已不存在的页码上', async () => {
    const fetcher = makeFetcher();
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      result.current.pagination.onChange?.(4, 20);
    });
    await act(async () => {
      result.current.pagination.onChange?.(4, 50);
    });
    expect(result.current.size).toBe(50);
    expect(result.current.page).toBe(1);
  });

  it('页大小选项集合锁死 —— 上限 100, 不得低于后端 clamp 上限 200', async () => {
    const fetcher = makeFetcher();
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.pagination.pageSizeOptions).toEqual(['10', '20', '50', '100']);
  });

  // ---- 以下 5 条是复审补记新加的 ----

  it('请求失败 → error 有值且 rows 为空 —— 不能留着上一页的旧数据', async () => {
    const fetcher = makeFetcher(40);
    const { result } = renderHook(() => useServerPage(fetcher));
    // 第一页正常加载 → 表格里有一行数据
    await waitFor(() => expect(result.current.rows).toHaveLength(1));
    // 翻到第 2 页, 这一次失败 —— 旧的那一行**必须**被清掉,
    // 否则分页器说「第 2 页」、表格里却是第 1 页的数据。
    fetcher.mockRejectedValueOnce(new Error('boom'));
    await act(async () => {
      result.current.pagination.onChange?.(2, 20);
    });
    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.rows).toEqual([]);
  });

  it('失败后再成功 → error 被清掉 —— 否则红条永远挂在成功的页面上', async () => {
    const fetcher = makeFetcher(40);
    fetcher.mockRejectedValueOnce(new Error('boom'));
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.error).toBeTruthy());
    // 再翻一页 → 这一次成功
    await act(async () => {
      result.current.pagination.onChange?.(2, 20);
    });
    await waitFor(() => expect(result.current.error).toBeNull());
    expect(result.current.rows).toEqual([{ id: 2 }]);
  });

  it('reload() → 重新请求当前页 —— 商家失败后能原地重试', async () => {
    const fetcher = makeFetcher();
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await act(async () => {
      result.current.reload();
    });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher).toHaveBeenLastCalledWith(1, 20);
  });

  it('total 变小导致页码越界 → 夹回最后一页并重新请求', async () => {
    const fetcher = makeFetcher(100);
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.total).toBe(100));
    // 商家翻到第 5 页 (total=100, size=20 → 最后一页是 5)
    await act(async () => {
      result.current.pagination.onChange?.(5, 20);
    });
    await waitFor(() => expect(fetcher).toHaveBeenLastCalledWith(5, 20));
    // 后台作废了一部分数据 → total 变成 40 → 最后一页变成 2
    fetcher.mockResolvedValue({ records: [], total: 40, page: 5, size: 20 });
    await act(async () => {
      result.current.reload();
    });
    await waitFor(() => expect(result.current.page).toBe(2));
    await waitFor(() => expect(fetcher).toHaveBeenLastCalledWith(2, 20));
  });

  it('调用方传内联箭头函数 → 不会每次渲染都重新请求', async () => {
    // 内联箭头每次渲染都是新函数 —— 若把它放进依赖数组, effect 每渲染都重跑
    // → 无限请求。所以它必须存进 ref。
    const fetcher = makeFetcher();
    const { result } = renderHook(() => useServerPage((page, size) => fetcher(page, size)));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenLastCalledWith(1, 20);
  });
});
