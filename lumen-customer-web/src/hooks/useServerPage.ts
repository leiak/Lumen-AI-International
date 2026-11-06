/**
 * 4.5C-2.1 — 服务端分页 hook (缺口 #9) 的**完整版**: 请求 + 分页 + 错误 + 重试。
 *
 * 商家门户的 4 个列表页都是服务端分页: 后端返回**当前页**的 records + 全量
 * total, 前端不再自己切页。
 *
 * 为什么把请求也收进 hook (而不是各页各写一遍 useEffect):
 *
 * 1. 「请求成功后要 `setError(null)`」这类约定, **复制 4 遍就一定会漏**。
 *    漏了的后果是: 商家翻到第 2 页请求失败 → 红色「加载失败」挂在那儿 →
 *    再点第 3 页请求成功 → 表格里是第 3 页的正确数据, 页面顶上却还挂着
 *    红色的「加载失败」。这违反 [N9-7]「请求失败时必须显示错误提示」。
 * 2. 「deps 里别忘了写 `page, size`」也是一条约定。漏了不会报错, 不会警告,
 *    只是**分页器点得动但数据永远不变** —— 本仓反复出现的「静默失效」。
 * 3. 失败分支还要记得 `setRows([])` —— 否则分页器说「第 2 页」, 表格里却是
 *    第 1 页的数据 (同样是 [N9-7] 要求的「不显示旧数据」)。
 *
 * 所以: hook 管这一页数据的**整个生命周期**, 页面只管渲染。
 * 这**不是**抽通用组件 —— 组件还是各页自己的 antd Table。
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import type { TablePaginationConfig } from 'antd';
import { zhCN } from '../locale/zh-CN';
import type { MerchantPage } from '../types/billing';

/** 页大小选项 —— 上限 100, 不得超过后端 clamp 上限 (200)。 */
export const PAGE_SIZE_OPTIONS = ['10', '20', '50', '100'];

export interface ServerPage<T> {
  /** 当前页的数据 —— 失败时是**空数组** (不保留上一页的旧数据)。 */
  rows: T[];
  /** 后端返回的总条数 —— 喂给 antd 分页器。失败时**保留上一次的值**。 */
  total: number;
  loading: boolean;
  /** 错误文案 —— 成功时被清成 null。 */
  error: string | null;
  page: number;
  size: number;
  /** 直接展开给 `<Table pagination={...}>` */
  pagination: TablePaginationConfig;
  /** 重新请求当前页 —— 给「重试」按钮用。 */
  reload: () => void;
}

/**
 * 服务端分页 hook。
 *
 * @param fetcher 取某一页数据的函数。**允许传内联箭头函数**
 *   `(page, size) => svc.listRecons(undefined, page, size)` —— 每次渲染都是
 *   新函数, 所以它被存进 ref 而**不进依赖数组**; 否则 effect 每渲染都重跑 →
 *   无限请求。
 * @param deps 额外的依赖 (筛选条件等)。**筛选变了必须重新请求**, 而 fetcher
 *   在 ref 里、变了不会自动重取, 所以筛选必须进 `deps`。
 *   注意: `deps` 的**长度在组件生命周期内必须恒定** —— React 的依赖数组长度
 *   不能变, 否则 React 会抛 "The final argument passed to useEffect changed
 *   size between renders"。
 */
export function useServerPage<T>(
  fetcher: (page: number, size: number) => Promise<MerchantPage<T>>,
  deps: readonly unknown[] = [],
): ServerPage<T> {
  const [rows, setRows] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  /** reload 的计数器 —— 自增即触发重新请求。 */
  const [reloadTick, setReloadTick] = useState(0);

  /**
   * fetcher 存进 ref —— 调用方多半写内联箭头, 每次渲染都是新函数。
   * 直接进 deps 会让 effect 每渲染都重跑 → 无限请求。
   */
  const fetcherRef = useRef(fetcher);

  /**
   * ref 的更新 effect **必须声明在数据 effect 之前** —— 同一次 commit 里
   * effect 按声明顺序执行, 先跑这个才能保证数据 effect 拿到的是**最新**的
   * fetcher (而不是上一次的)。
   */
  useEffect(() => {
    fetcherRef.current = fetcher;
  });

  useEffect(() => {
    let alive = true;
    setLoading(true);
    fetcherRef
      .current(page, size)
      .then((p) => {
        if (!alive) return;
        // 成功 → **清掉上一轮的 error**。少了这一句, 一次失败之后页面上会永远
        // 挂着红色的「加载失败」, 即使数据已经正常加载出来了。
        setError(null);
        setTotal(p.total);
        // total 变小 (后台作废了一部分数据) → 当前页码可能已经越界。
        // antd 自己的 usePagination 会把高亮的页码夹到 maxPage, 但**不会**通知
        // 我们重新请求 —— 结果是分页器显示第 2 页、表格却是空的。
        // 这里自己夹回最后一页, setPage 会触发 effect 重新请求。
        // 首帧 total=0 时 maxPage=1, 不会误夹。
        const maxPage = Math.max(1, Math.ceil(p.total / size));
        if (page > maxPage) {
          // 当前页已经不存在 —— 别把它的数据留在表格里。
          setRows([]);
          setPage(maxPage);
          return;
        }
        setRows(p.records);
      })
      .catch(() => {
        if (!alive) return;
        // 失败 → **清空旧数据**。保留上一页的 rows 会让分页器说「第 2 页」、
        // 表格里却是第 1 页的数据, 违反 [N9-7]。
        // total **不清**: 清了 total → 分页器整个不渲染 → 商家连翻页重试的
        // 出路都没有 (付款记录 / 调账单还没有导出端点, 分页器是唯一的出路)。
        setRows([]);
        setError(zhCN.billing.loadFailed);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, reloadTick, ...deps]);

  const onChange = useCallback(
    (nextPage: number, nextSize: number) => {
      setSize(nextSize);
      // 页大小变了 → 回到第 1 页。旧的页码在新的页大小下可能已经越界,
      // 停在那个页码上商家会看到一个空页, 像是数据丢了。
      setPage(nextSize === size ? nextPage : 1);
    },
    [size],
  );

  const reload = useCallback(() => {
    setReloadTick((t) => t + 1);
  }, []);

  const pagination: TablePaginationConfig = {
    current: page,
    pageSize: size,
    total,
    showSizeChanger: true,
    pageSizeOptions: PAGE_SIZE_OPTIONS,
    showTotal: (t: number) => zhCN.billing.totalCount(t),
    onChange,
  };

  return { rows, total, loading, error, page, size, pagination, reload };
}
