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
import { zhCN } from '../../locale/zh-CN';
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
 *
 * `records` 可以显式传空数组 —— 用来造「真的没有数据」的返回
 * (默认的 `[{ id: page }]` **有 1 条记录**, 不能叫「没数据」)。
 */
function makeFetcher(total = 1000, records?: Row[]) {
  return vi.fn(async (page: number, size: number): Promise<MerchantPage<Row>> => ({
    records: records ?? [{ id: page }],
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

  // ---- 以下是复审第 4 轮补记新加的 ----

  it('tableLocale: 加载失败 → 失败文案', async () => {
    const fetcher = vi.fn(async (): Promise<MerchantPage<Row>> => {
      throw new Error('boom');
    });
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe(zhCN.billing.loadFailed);
    expect(result.current.tableLocale?.emptyText).toBe(zhCN.billing.loadFailedEmpty);
  });

  it('tableLocale: 加载中 → 加载中文案', async () => {
    // fetcher 返回一个**永不 resolve** 的 promise → loading 一直卡在 true。
    const fetcher = vi.fn(
      (): Promise<MerchantPage<Row>> => new Promise<MerchantPage<Row>>(() => {}),
    );
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(result.current.loading).toBe(true));
    expect(result.current.tableLocale?.emptyText).toBe(zhCN.billing.loadingEmpty);
  });

  // ---- 以下是复审第 5 轮补记新加的 ----

  it('失败后重试在途 → 空态是「加载中...」, 不是「加载失败...」', async () => {
    // 「失败 → 点重试 → 重试在途」这一态: **error 非空 且 loading 为真**。
    //
    // 为什么要补这一条: 逻辑收进 hook 之后, 这一态原先**只被页面层**的 4 条
    // 用例间接抓住 —— 第 5 轮实测: 把 hook 的 `error && !loading` 改成 `error`,
    // hook 自己的用例**全绿**, 只有页面层那 4 条红。也就是 hook 层自己
    // 对这一态**零覆盖** → 这里补一条直接用例。
    //
    // 造法: 第 1 次请求 reject (失败), 第 2 次请求**永不 settle** (重试在途,
    // loading 一直卡在 true), 中间触发 reload()。
    let callCount = 0;
    const fetcher = vi.fn(
      (): Promise<MerchantPage<Row>> =>
        new Promise<MerchantPage<Row>>((_resolve, reject) => {
          callCount += 1;
          if (callCount === 1) reject(new Error('boom'));
          // 第 2 次起永不 settle → 重试一直在途 → loading 一直是 true
        }),
    );
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.error).toBe(zhCN.billing.loadFailed));

    // 点「重试」→ 第 2 次请求发出, 永不 resolve
    await act(async () => {
      result.current.reload();
    });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.loading).toBe(true));

    // error **没被清掉** —— 清 error 只发生在成功分支, 重试在途不算成功
    expect(result.current.error).toBe(zhCN.billing.loadFailed);
    // 承重断言: 重试在途时, 空态文案是「加载中...」, **不是**「加载失败...」
    // (把 `error && !loading` 改成 `error` → 这里直接打红)
    expect(result.current.tableLocale?.emptyText).toBe(zhCN.billing.loadingEmpty);
  });

  it('tableLocale: 加载成功但没数据 → undefined (让 antd 用它自己的「暂无数据」)', async () => {
    // **真的**没有数据: records 是空数组 (默认的 `[{ id: page }]` 有 1 条记录,
    // 跟「没数据」这个标题对不上)。
    const fetcher = makeFetcher(0, []);
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeNull();
    expect(result.current.rows).toEqual([]);
    expect(result.current.tableLocale).toBeUndefined();
  });

  it('旧的慢请求后失败 → 不能清空新页的数据, 也不能弹假错误', async () => {
    // F1 那条 (旧的慢请求**晚到**) 的孪生: 这次是旧的慢请求**后失败**。
    //
    // 故障形态: 商家点「下一页」→ 第 2 页请求**先成功** (表格已是第 2 页数据)
    // → 第 1 页的旧请求**后失败** → 没有 alive 守卫时 `setRows([])` +
    // `setError(...)` → 把已经加载好的第 2 页数据清空, 并弹一个假「加载失败」。
    const pending: Array<{
      resolve: (p: MerchantPage<Row>) => void;
      reject: (e: unknown) => void;
    }> = [];
    const fetcher = vi.fn(
      () =>
        new Promise<MerchantPage<Row>>((resolve, reject) => {
          pending.push({ resolve, reject });
        }),
    );
    const { result } = renderHook(() => useServerPage(fetcher));
    await waitFor(() => expect(pending).toHaveLength(1));
    act(() => {
      result.current.pagination.onChange?.(2, 20);
    });
    await waitFor(() => expect(pending).toHaveLength(2));

    // 第 2 页先成功
    await act(async () => {
      pending[1].resolve({ records: [{ id: 2 }], total: 100, page: 2, size: 20 });
    });
    expect(result.current.rows).toEqual([{ id: 2 }]);
    expect(result.current.error).toBeNull();

    // 第 1 页后失败 → **必须被 alive 守卫丢掉**:
    // 没有守卫 → setRows([]) 把第 2 页的数据清空 + setError 弹一个假错误
    await act(async () => {
      pending[0].reject(new Error('boom'));
    });
    expect(result.current.rows).toEqual([{ id: 2 }]);
    expect(result.current.error).toBeNull();
  });

  it('请求失败 → 记一条脱敏的日志 (不把整个 error 对象打出去)', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const fetcher = vi.fn((): Promise<MerchantPage<Row>> => {
        throw new Error('boom');
      });
      renderHook(() => useServerPage(fetcher));
      await waitFor(() => expect(spy).toHaveBeenCalled());

      const call = spy.mock.calls.find((c) => String(c[0]).includes('useServerPage'));
      expect(call).toBeDefined();
      // 第 2 个参数必须是**脱敏后的字符串**, 不是整个 error 对象
      expect(typeof call?.[1]).toBe('string');
      // 更不能把 axios 的 config / response 带出去
      expect(call?.[1]).not.toHaveProperty('config');
      expect(call?.[1]).not.toHaveProperty('response');
      // **所有**参数都不得是对象 —— 只查第 2 个参数有覆盖缺口:
      // 把 error 对象挪到第 3 / 第 4 个参数再打出去, 上面的断言照样全绿
      // (第 5 轮实测: 改成第 4 个参数再打 e → 21/21 全绿)。
      // 第 1 个参数是固定字符串 '[useServerPage] 请求失败', 本身就是 string,
      // 从第 2 个参数开始查即可 (查了也不会误伤)。
      (call ?? []).slice(1).forEach((arg) => {
        expect(arg).not.toBeNull();
        expect(typeof arg).not.toBe('object');
      });
    } finally {
      spy.mockRestore();
    }
  });
});
