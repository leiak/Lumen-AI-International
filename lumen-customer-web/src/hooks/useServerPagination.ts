/**
 * 4.5C-2.1 — 服务端分页 hook (缺口 #9)。
 *
 * 商家门户的 4 个列表页都是服务端分页: 后端返回**当前页**的 records + 全量
 * total, 前端不再自己切页。
 *
 * 之所以用 hook 而不是各页各写一遍: 「改页大小要回到第 1 页」这条规则很容易漏,
 * 漏了的表现是 —— 商家在第 5 页把页大小从 20 改成 10, 结果停在一个空页上,
 * 看起来像数据丢了。
 */

import { useCallback, useState } from 'react';
import type { TablePaginationConfig } from 'antd';
import { zhCN } from '../locale/zh-CN';

/** 页大小选项 —— 上限 100, 不得超过后端 clamp 上限 (200)。 */
export const PAGE_SIZE_OPTIONS = ['10', '20', '50', '100'];

export interface ServerPagination {
  page: number;
  size: number;
  /** 直接展开给 <Table pagination={...}> */
  pagination: TablePaginationConfig;
}

/**
 * @param total 后端返回的总条数 —— 喂给 antd 分页器, 商家才看得到"一共多少条"。
 */
export function useServerPagination(total: number, initialSize = 20): ServerPagination {
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(initialSize);

  const onChange = useCallback(
    (nextPage: number, nextSize: number) => {
      setSize(nextSize);
      // 页大小变了 → 回到第 1 页。旧的页码在新的页大小下可能已经越界,
      // 停在那个页码上商家会看到一个空页, 像是数据丢了。
      setPage(nextSize === size ? nextPage : 1);
    },
    [size],
  );

  const pagination: TablePaginationConfig = {
    current: page,
    pageSize: size,
    total,
    showSizeChanger: true,
    pageSizeOptions: PAGE_SIZE_OPTIONS,
    showTotal: (t: number) => zhCN.billing.totalCount(t),
    onChange,
  };

  return { page, size, pagination };
}
