/**
 * 4.6 — 门户端货运委托单列表页测试。
 *
 * 写法照抄既有的 `src/pages/Order/__tests__/OrderList.test.tsx`:
 * `vi.mock('../../../services/portalFreightOrderService')` + MemoryRouter + render + waitFor。
 *
 * 用例超时统一放宽到 30s —— antd ProTable 在 jsdom 下渲染很重, 本机高负载时
 * 单条要跑 6~7s, 会撞上 vitest 默认的 5s 超时 (实测踩过一次)。
 */

import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import FreightOrderList from '../List';
import { portalFreightOrderService } from '../../../services/portalFreightOrderService';
import type { FreightOrderRow } from '../../../types/freightOrder';

vi.mock('../../../services/portalFreightOrderService');
const mockList = vi.mocked(portalFreightOrderService.list);

const ROWS: FreightOrderRow[] = [
  {
    id: 1,
    orderNo: 'FO-2026-0001',
    customerId: 7,
    businessType: 'OCEAN_FCL',
    tradeDirection: 'EXPORT',
    incoterms: 'FOB',
    serviceType: 'D2D',
    originAddress: '上海',
    destAddress: '洛杉矶',
    totalQty: 10,
    totalWeightKg: 1200,
    totalVolumeCbm: 3.5,
    orderStatus: 'PENDING_CONFIRM',
    settlementCurrency: 'USD',
    etd: '2026-12-01',
    eta: '2026-12-20',
    createdAt: '2026-11-01T08:00:00',
  },
  {
    id: 2,
    orderNo: 'FO-2026-0002',
    customerId: 7,
    businessType: 'AIR',
    tradeDirection: 'IMPORT',
    incoterms: 'CIF',
    serviceType: 'D2D',
    originAddress: '深圳',
    destAddress: '法兰克福',
    totalQty: 3,
    totalWeightKg: 260,
    totalVolumeCbm: 1.2,
    orderStatus: 'CONFIRMED',
    settlementCurrency: 'EUR',
    etd: '2026-11-25',
    eta: '2026-12-05',
    createdAt: '2026-11-02T09:00:00',
  },
];

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/freight-orders']}>
      <FreightOrderList />
    </MemoryRouter>
  );
}

/**
 * 按钮查找 —— 归一化掉所有空白。
 *
 * antd 的 `autoInsertSpaceInButton` 会给「两个汉字」的按钮文案中间插一个空格
 * (「提交」渲染成「提 交」), 所以这里不能直接用文案全等去比。
 */
function buttonByText(text: string): HTMLElement {
  const hit = screen
    .queryAllByRole('button')
    .find((b) => (b.textContent ?? '').replace(/\s+/g, '') === text);
  if (!hit) throw new Error(`找不到按钮: ${text}`);
  return hit;
}

/** 打开下拉并选中指定 label 的选项 —— antd Select 的 option 带 title=label。 */
async function pickOption(labelText: string, optionTitle: string) {
  const input = screen.getByLabelText(labelText) as HTMLInputElement;
  const select = input.closest('.ant-select');
  expect(select).not.toBeNull();
  fireEvent.mouseDown(select!.querySelector('.ant-select-selector') as HTMLElement);

  const dropdown = await waitFor(() => {
    const hit = Array.from(document.querySelectorAll('.ant-select-dropdown'))
      .filter((d) => !d.classList.contains('ant-select-dropdown-hidden'))
      .find((d) => d.querySelector(`.ant-select-item-option[title="${optionTitle}"]`));
    expect(hit).toBeTruthy();
    return hit!;
  });
  const opt = dropdown.querySelector(
    `.ant-select-item-option[title="${optionTitle}"]`
  ) as HTMLElement;
  fireEvent.click(opt);
}

describe('FreightOrder 列表页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('空列表展示空状态', async () => {
    mockList.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 10 });

    renderList();

    await waitFor(() => {
      expect(mockList).toHaveBeenCalled();
    });
    await waitFor(() => {
      expect(screen.getAllByText('暂无数据').length).toBeGreaterThanOrEqual(1);
    });
  }, 30000);

  it('渲染委托单号与状态标签文案', async () => {
    mockList.mockResolvedValue({
      records: ROWS,
      total: ROWS.length,
      pageNum: 1,
      pageSize: 10,
    });

    renderList();

    await waitFor(() => {
      expect(screen.getByText('FO-2026-0001')).toBeInTheDocument();
      expect(screen.getByText('FO-2026-0002')).toBeInTheDocument();
    });
    // 状态标签走 FreightOrderStatusTag —— 文案来自 zhCN.freightOrder.status。
    expect(screen.getByText('待确认')).toBeInTheDocument();
    expect(screen.getByText('已确认')).toBeInTheDocument();
  }, 30000);

  it('按状态过滤时带上 status / current / size 三个查询参数', async () => {
    mockList.mockResolvedValue({
      records: [ROWS[1]],
      total: 1,
      pageNum: 1,
      pageSize: 20,
    });

    renderList();
    await waitFor(() => expect(mockList).toHaveBeenCalledTimes(1));

    await pickOption('状态', '已确认');
    fireEvent.click(buttonByText('查询'));

    await waitFor(() => {
      expect(mockList).toHaveBeenCalledWith({
        status: 'CONFIRMED',
        current: 1,
        // 每页 20 条 —— 与后端 `PortalFreightOrderController.list` 的 size 默认值一致
        size: 20,
      });
    });
  }, 30000);
});
