/**
 * 4.6 — 门户端货运委托单列表页测试。
 *
 * 写法照 `src/pages/Billing/__tests__/BillingPagination.test.tsx`:
 * `vi.mock('../../../services/portalFreightOrderService')` + MemoryRouter + render +
 * waitFor, 并且**整个文件都用 `<ConfigProvider locale={antdZhCN}>` 包一层** ——
 * 和真实应用 (`src/main.tsx`) 一样。没有这层, antd 的默认 locale 是 en_US,
 * 空态会显示 "No data" 而不是「暂无数据」。
 *
 * <p><b>各用例的超时统一写成 90000</b> —— 与同目录 `Create.test.tsx` 同口径:
 * antd 的 Select / Table 在 jsdom 下渲染很重 (这里要 `mouseDown` 打开 antd
 * Select 下拉), 本机高负载时单条要跑好几秒到二十几秒, 会撞上 vitest 默认的
 * 5s 超时。这个数字**必须写在 `it()` 的第三个参数上** —— 配置文件与命令行
 * `--testTimeout` 都**盖不住**它 (实测 `--testTimeout=180000` 覆盖不了
 * `it(name, fn, 30000)`)。留着 30000 时, 本机负载一高就随机超时, 而且
 * **每次失败的用例集合都不一样** —— 下一轮审查会把它当成回归重复查。
 */

import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import antdZhCN from 'antd/locale/zh_CN';
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
    <ConfigProvider locale={antdZhCN}>
      <MemoryRouter initialEntries={['/freight-orders']}>
        <FreightOrderList />
      </MemoryRouter>
    </ConfigProvider>
  );
}

/**
 * 按钮查找 —— 归一化掉所有空白。
 *
 * antd 的 `autoInsertSpaceInButton` 会给「两个汉字」的按钮文案中间插一个空格
 * (「查询」渲染成「查 询」), 所以这里不能直接用文案全等去比。
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
    mockList.mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 20 });

    renderList();

    await waitFor(() => {
      expect(mockList).toHaveBeenCalled();
    });
    await waitFor(() => {
      expect(screen.getAllByText('暂无数据').length).toBeGreaterThanOrEqual(1);
    });
  }, 90000);

  it('渲染委托单号与状态标签文案', async () => {
    mockList.mockResolvedValue({
      records: ROWS,
      total: ROWS.length,
      pageNum: 1,
      pageSize: 20,
    });

    renderList();

    await waitFor(() => {
      expect(screen.getByText('FO-2026-0001')).toBeInTheDocument();
      expect(screen.getByText('FO-2026-0002')).toBeInTheDocument();
    });
    // 状态标签走 FreightOrderStatusTag —— 文案来自 zhCN.freightOrder.status。
    expect(screen.getByText('待确认')).toBeInTheDocument();
    expect(screen.getByText('已确认')).toBeInTheDocument();
  }, 90000);

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
  }, 90000);

  /**
   * 筛选变了 → 必须回到第 1 页。
   *
   * `useServerPage` 的 deps 变化**不会**重置页码 (它的 effect 只是用当前页码
   * 重新请求) —— 页面自己显式处理。不处理的话, 商家在第 3 页改筛选, 请求发的是
   * 「第 3 页 + 新筛选」, 表格多半是空的。
   */
  it('筛选变化后回到第 1 页', async () => {
    mockList.mockResolvedValue({ records: ROWS, total: 40, pageNum: 1, pageSize: 20 });

    renderList();
    await waitFor(() => expect(mockList).toHaveBeenCalledTimes(1));

    // 翻到第 2 页 (total=40 / size=20 → 两页)
    const page2 = await waitFor(() => {
      const el = document.querySelector('.ant-pagination-item-2') as HTMLElement;
      expect(el).toBeTruthy();
      return el;
    });
    fireEvent.click(page2);
    await waitFor(() => {
      expect(mockList).toHaveBeenCalledWith(expect.objectContaining({ current: 2 }));
    });

    // 改筛选 → 请求必须回到第 1 页
    await pickOption('状态', '已确认');
    fireEvent.click(buttonByText('查询'));

    await waitFor(() => {
      expect(mockList).toHaveBeenCalledWith({
        status: 'CONFIRMED',
        current: 1,
        size: 20,
      });
    });
  }, 90000);

  /**
   * 请求失败**不能**被静默吞掉 —— 商家要看到「加载失败」和重试入口,
   * 而不是 antd 默认的空态「暂无数据」(那会让人以为自己的单被系统删了)。
   */
  it('请求失败 → 显示加载失败与重试入口, 且不显示「暂无数据」', async () => {
    mockList.mockRejectedValue(new Error('boom'));

    renderList();

    await waitFor(() => {
      expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument();
    });
    // 表格空态是「加载失败, 请点击上方重试」, 不是「暂无数据」。
    expect(screen.getByText('加载失败, 请点击上方重试')).toBeInTheDocument();
    expect(screen.queryByText('暂无数据')).toBeNull();
    // 有重试入口 —— 商家能原地重试, 不用刷新浏览器。
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument();
  }, 90000);

  it('点「重试」→ 重新请求, 成功后错误提示消失', async () => {
    mockList.mockRejectedValueOnce(new Error('boom'));
    mockList.mockResolvedValue({
      records: ROWS,
      total: ROWS.length,
      pageNum: 1,
      pageSize: 20,
    });

    renderList();

    const retry = await waitFor(() => screen.getByRole('button', { name: /重\s*试/ }));
    fireEvent.click(retry);

    await waitFor(() => expect(mockList).toHaveBeenCalledTimes(2));
    await waitFor(() => {
      expect(screen.getByText('FO-2026-0001')).toBeInTheDocument();
    });
    expect(screen.queryByText('加载失败, 请稍后重试')).toBeNull();
  }, 90000);
});
