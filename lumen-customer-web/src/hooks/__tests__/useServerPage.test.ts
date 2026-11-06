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
  /** 模拟「筛选条件」落到行上 —— 用来断言新筛选值真的进了请求/结果。 */
  status?: string;
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

  // ---- 以下 4 条是复审第 2 轮补记新加的 ----

  it('deps 变化 → 重新请求, 且新筛选值生效', async () => {
    // fetcher 闭包里捕获 status —— 靠 fetcherRef 在**渲染期**拿到最新闭包。
    const fetcher = vi.fn(
      async (page: number, size: number, status: string): Promise<MerchantPage<Row>> => ({
        records: [{ id: page, status }],
        total: 100,
        page,
        size,
      }),
    );
    const { result, rerender } = renderHook(
      ({ status }: { status: string }) =>
        useServerPage<Row>((page, size) => fetcher(page, size, status), [status]),
      { initialProps: { status: 'PAID' } },
    );
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    expect(fetcher).toHaveBeenLastCalledWith(1, 20, 'PAID');

    // 商家把筛选从「已付款」改成「待付款」→ 必须重新请求, 且新值进到请求里。
    rerender({ status: 'PENDING' });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher).toHaveBeenLastCalledWith(1, 20, 'PENDING');
    expect(result.current.rows).toEqual([{ id: 1, status: 'PENDING' }]);
  });

  it('deps 长度不变、值变化 → effect 重跑 (重新请求)', async () => {
    const fetcher = makeFetcher();
    const { rerender } = renderHook(
      ({ tick }: { tick: number }) => useServerPage(fetcher, [tick]),
      { initialProps: { tick: 0 } },
    );
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));

    rerender({ tick: 1 });

    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher).toHaveBeenLastCalledWith(1, 20);
  });

  it('deps 长度变化 → console.error 告警 (生产构建里 React 自己完全静默)', async () => {
    // React 的真实行为: dev 下 `console.error` 警告, **生产构建里连这个检查
    // 都没有** → 一条日志都不会有, 而且长度变化时新加的依赖会被忽略 →
    // effect 不重跑 → 筛选静默失效。所以本 hook 自己加了一道运行时防护。
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const fetcher = makeFetcher();
      const { rerender } = renderHook(
        ({ deps }: { deps: readonly unknown[] }) => useServerPage(fetcher, deps),
        { initialProps: { deps: ['a'] as readonly unknown[] } },
      );
      await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
      // 长度没变 → 不该有本 hook 的告警
      expect(spy.mock.calls.some((c) => String(c[0]).includes('useServerPage'))).toBe(false);

      // 长度从 1 变成 2 → 必须响
      rerender({ deps: ['a', 'b'] });

      await waitFor(() =>
        expect(spy.mock.calls.some((c) => String(c[0]).includes('useServerPage'))).toBe(true),
      );
      const msg = String(
        spy.mock.calls.filter((c) => String(c[0]).includes('useServerPage')).slice(-1)[0][0],
      );
      // 告警要指名道姓: 哪个 hook、期望长度 vs 实际长度
      expect(msg).toContain('useServerPage');
      expect(msg).toContain('期望 1');
      expect(msg).toContain('实际 2');
    } finally {
      spy.mockRestore();
    }
  });

  it('fetcher 同步抛错 → 落进 catch 显示「加载失败」, 而不是炸掉整棵树', async () => {
    // 若 hook 直接调 fetcher 而不包 Promise.resolve().then(...), 这个同步抛错
    // 会冲出 effect → React 抛异常 → 整页白屏, 而不是显示「加载失败」。
    const fetcher = vi.fn((): Promise<MerchantPage<Row>> => {
      throw new Error('boom');
    });
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(result.current.rows).toEqual([]);
  });

  // ---- 以下 2 条是复审第 3 轮补记新加的 ----

  it('旧的慢请求晚到 → 不能覆盖新页的数据', async () => {
    // 第 1 页请求挂起 → 翻到第 2 页 → 第 2 页先回 → 第 1 页后回。
    // 没有 alive 守卫时, 迟到的第 1 页响应会把第 2 页的数据盖掉 ——
    // 分页器高亮第 2 页、表格里却是第 1 页的数据, 且没有任何提示。
    const resolvers: Array<() => void> = [];
    const fetcher = vi.fn(
      (page: number, size: number) =>
        new Promise<MerchantPage<Row>>((res) => {
          resolvers.push(() => res({ records: [{ id: page }], total: 100, page, size }));
        }),
    );
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(resolvers).toHaveLength(1));
    act(() => {
      result.current.pagination.onChange?.(2, 20);
    });
    await waitFor(() => expect(resolvers).toHaveLength(2));
    await act(async () => {
      resolvers[1]();
    });
    expect(result.current.rows).toEqual([{ id: 2 }]);
    await act(async () => {
      resolvers[0]();
    }); // 迟到的旧响应
    expect(result.current.rows).toEqual([{ id: 2 }]); // ← 有 alive 守卫才过
  });

  it('deps 长度变化 → 只告警一次 (基线会更新), 再次变化再告警', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const fetcher = makeFetcher();
      const { rerender } = renderHook(
        ({ deps }: { deps: readonly unknown[] }) => useServerPage(fetcher, deps),
        { initialProps: { deps: ['a'] as readonly unknown[] } },
      );
      await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));

      const hits = () => spy.mock.calls.filter((c) => String(c[0]).includes('useServerPage')).length;

      // 长度 1 → 2: 告警一次
      rerender({ deps: ['a', 'b'] });
      await waitFor(() => expect(hits()).toBe(1));

      // 与 deps 无关的再渲染 → **不该**再告警 (基线已更新)
      rerender({ deps: ['a', 'b'] });
      rerender({ deps: ['a', 'b'] });
      expect(hits()).toBe(1);

      // 长度再变 (2 → 3) → 应该再告警一次
      rerender({ deps: ['a', 'b', 'c'] });
      await waitFor(() => expect(hits()).toBe(2));
    } finally {
      spy.mockRestore();
    }
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
