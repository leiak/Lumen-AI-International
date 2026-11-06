/**
 * 4.5C-2.1 — 商家门户 4 个列表页的服务端分页 (缺口 #9)。
 *
 * 关键断言: 点"下一页" → **带着新的 page 重新请求**, 而不是本地切已有的 rows。
 *
 * 整个文件都用 `<ConfigProvider locale={antdZhCN}>` 包一层 —— 和真实应用
 * (main.tsx) 一致。少了它 antd 走默认的 enUS locale: 表格空态是「No data」
 * 而不是「暂无数据」, 页大小下拉的选项是「50 / page」而不是「50 条/页」。
 * 一致之后, 下面那些「文案长什么样」的断言才是在测真实用户看到的东西。
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import antdZhCN from 'antd/locale/zh_CN';
import type { ReactElement } from 'react';
import ReconList from '../ReconList';
import InvoiceList from '../InvoiceList';
import PaymentList from '../PaymentList';
import AdjustmentList from '../AdjustmentList';
import { portalBillingService } from '../../../services/portalBillingService';
import type { ReconView } from '../../../types/billing';

vi.mock('../../../services/portalBillingService');

const mockRecons = vi.mocked(portalBillingService.listRecons);
const mockInvoices = vi.mocked(portalBillingService.listInvoices);
const mockPayments = vi.mocked(portalBillingService.listPayments);
const mockAdjustments = vi.mocked(portalBillingService.listAdjustments);

/** 一行对账数据 —— 用来验证「失败时表格里没有旧数据」。 */
const RECON_ROW: ReconView = {
  id: 1, period: '2026-08', currency: 'CNY',
  expectedAmount: 100, actualAmount: 90, varianceAmount: -10,
  adjustmentId: null, reconciliationStatus: 'PENDING', remark: null,
  disputeAmount: null, disputeReason: null, disputeAt: null, closed: true,
};

/**
 * 读表格空态的**文案** (「暂无数据」/「加载失败, 请点击上方重试」)。
 *
 * 两个坑:
 * 1. 不能直接 `getByText('暂无数据')` —— antd 的 Empty 组件在 SVG 里还放了一个
 *    `<title>暂无数据</title>` (无障碍用的图形标题), 同一个词会匹配到两个元素,
 *    getByText 直接抛 "Found multiple elements"。
 * 2. antd 的 Table 空态有两种渲染: 默认的 `Empty` 组件 (`.ant-empty-description`
 *    里是那行字), 和 **纯字符串** emptyText (直接塞进占位行的 <td>)。
 *    失败时我们传的是字符串, 所以两种都要认。
 */
function tableEmptyText(): string | null {
  const cell = document.querySelector('.ant-table-placeholder .ant-table-cell');
  if (!cell) return null;
  const desc = cell.querySelector('.ant-empty-description');
  return desc ? desc.textContent : cell.textContent;
}

/**
 * 和真实应用一样包一层 ConfigProvider (antd 的 zh_CN locale)。
 * 见文件头注释 —— 少了它, 「暂无数据」这类断言就变成在测 enUS 的「No data」。
 */
function renderList(ui: ReactElement) {
  return render(
    <ConfigProvider locale={antdZhCN}>
      <MemoryRouter>{ui}</MemoryRouter>
    </ConfigProvider>,
  );
}

describe('商家门户列表分页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('ReconList: 点下一页 → 用 page=2 重新请求', async () => {
    mockRecons.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    renderList(<ReconList />);
    await waitFor(() => expect(mockRecons).toHaveBeenCalledTimes(1));
    expect(mockRecons).toHaveBeenLastCalledWith(undefined, 1, 20);

    await userEvent.click(await screen.findByTitle('2'));

    await waitFor(() => expect(mockRecons).toHaveBeenLastCalledWith(undefined, 2, 20));
  });

  it('PaymentList: 点下一页 → 用 page=2 重新请求', async () => {
    mockPayments.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    renderList(<PaymentList />);
    await waitFor(() => expect(mockPayments).toHaveBeenCalledTimes(1));
    await userEvent.click(await screen.findByTitle('2'));
    await waitFor(() => expect(mockPayments).toHaveBeenLastCalledWith(undefined, 2, 20));
  });

  it('InvoiceList: 点下一页 → 用 page=2 重新请求', async () => {
    mockInvoices.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    renderList(<InvoiceList />);
    await waitFor(() => expect(mockInvoices).toHaveBeenCalledTimes(1));
    await userEvent.click(await screen.findByTitle('2'));
    await waitFor(() => expect(mockInvoices).toHaveBeenLastCalledWith(undefined, 2, 20));
  });

  it('AdjustmentList: 点下一页 → 用 page=2 重新请求', async () => {
    mockAdjustments.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    renderList(<AdjustmentList />);
    await waitFor(() => expect(mockAdjustments).toHaveBeenCalledTimes(1));
    await userEvent.click(await screen.findByTitle('2'));
    await waitFor(() => expect(mockAdjustments).toHaveBeenLastCalledWith(2, 20));
  });

  it('分页器显示总数 —— 商家知道一共多少条', async () => {
    mockRecons.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    renderList(<ReconList />);
    await waitFor(() => expect(screen.getByText('共 40 条')).toBeInTheDocument());
  });

  // ---- 以下 3 条是复审补记新加的 ----

  it('ReconList: 改页大小 → 用新 size 重新请求, 且回到第 1 页', async () => {
    mockRecons.mockResolvedValue({ records: [], total: 100, page: 1, size: 20 });
    renderList(<ReconList />);
    await waitFor(() => expect(mockRecons).toHaveBeenCalledTimes(1));

    // 先翻到第 3 页 —— 改页大小后必须回到第 1 页, 否则会停在一个空页上。
    await userEvent.click(await screen.findByTitle('3'));
    await waitFor(() => expect(mockRecons).toHaveBeenLastCalledWith(undefined, 3, 20));

    // 打开 antd 的页大小下拉 (rc-pagination 的 Options 组件 → antd Select)。
    //
    // 定位方式用 **role**, 不用 class / title:
    //   - class (.ant-pagination-options .ant-select-selector) 是 antd 的内部
    //     实现细节, 换个版本就断;
    //   - title 会随 locale 变 —— enUS 下选项是 '50 / page', zh_CN 下是
    //     '50 条/页' (rc-pagination 的 items_per_page)。本文件包了
    //     ConfigProvider locale={zhCN}, 所以断言只能写 '50 条/页';
    //   - role=combobox (Select 的搜索框) + role=option (下拉里的选项) 是
    //     antd 保证的语义, 不受 locale 影响。
    await userEvent.click(screen.getByRole('combobox'));
    // 选项名在 zh_CN 下是 '50 条/页' —— 只匹配 '50' 即可, 与 locale 解耦。
    await userEvent.click(await screen.findByRole('option', { name: /50/ }));

    await waitFor(() => expect(mockRecons).toHaveBeenLastCalledWith(undefined, 1, 50));
    // 超时放宽 —— 这一条要走完整的 antd 下拉交互 (打开 + 选中),
    // jsdom + antd 一次 userEvent.click 要 1 秒以上, 默认 5 秒不够。
  }, 30000);

  it('ReconList: 翻页失败 → 显示错误提示且表格里没有上一页的数据', async () => {
    mockRecons.mockResolvedValueOnce({ records: [RECON_ROW], total: 40, page: 1, size: 20 });
    mockRecons.mockRejectedValueOnce(new Error('boom'));
    renderList(<ReconList />);
    await waitFor(() => expect(screen.getByText('2026-08')).toBeInTheDocument());

    await userEvent.click(await screen.findByTitle('2'));

    await waitFor(() => expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument());
    // 分页器说「第 2 页」, 表格里却还是第 1 页的数据 —— 那是最迷惑商家的状态。
    expect(screen.queryByText('2026-08')).not.toBeInTheDocument();
  });

  it('ReconList: 失败后点「重试」→ 重新请求, 错误提示消失', async () => {
    mockRecons.mockRejectedValueOnce(new Error('boom'));
    renderList(<ReconList />);
    await waitFor(() => expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument());
    expect(mockRecons).toHaveBeenCalledTimes(1);

    mockRecons.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    // antd 的 Button 会在两个汉字之间插一个空格 ('重 试') —— 所以用
    // /重\s*试/ 匹配, 兼容插空格和不插空格两种情况。
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    await waitFor(() => expect(mockRecons).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByText('加载失败, 请稍后重试')).not.toBeInTheDocument());
  });

  // ---- 以下 3 条是复审第 2 轮补记新加的 ----

  it('加载失败 → 表格空态是「加载失败」而不是「暂无数据」', async () => {
    // 「暂无数据」和红色「加载失败」并排是最迷惑商家的组合: 商家会以为自己的
    // 3 张发票 / 40 条对账单被系统删了, 然后打客服电话。
    mockRecons.mockRejectedValue(new Error('boom'));
    renderList(<ReconList />);
    await waitFor(() => expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument());

    // 空态必须换成失败文案
    expect(screen.getByText('加载失败, 请点击上方重试')).toBeInTheDocument();
    expect(tableEmptyText()).toBe('加载失败, 请点击上方重试');
    // 且**不能**出现「暂无数据」
    expect(tableEmptyText()).not.toBe('暂无数据');
  });

  it('加载成功但真的没数据 → 仍然显示「暂无数据」', async () => {
    // 上一条不能把「暂无数据」整个干掉 —— 成功但空是正常业务状态。
    mockRecons.mockResolvedValue({ records: [], total: 0, page: 1, size: 20 });
    renderList(<ReconList />);
    await waitFor(() => expect(mockRecons).toHaveBeenCalledTimes(1));

    await waitFor(() => expect(tableEmptyText()).toBe('暂无数据'));
    expect(screen.queryByText('加载失败, 请点击上方重试')).not.toBeInTheDocument();
    expect(screen.queryByText('加载失败, 请稍后重试')).not.toBeInTheDocument();
  });

  it('重试进行中 → 「重试」按钮被禁用 (不让商家连点出一堆请求)', async () => {
    mockRecons.mockRejectedValueOnce(new Error('boom'));
    renderList(<ReconList />);
    await waitFor(() => expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument());

    // 下一次请求**永不 resolve** → loading 会一直卡在 true
    mockRecons.mockReturnValueOnce(new Promise(() => {}));
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /重\s*试/ })).toBeDisabled(),
    );
  });
});
