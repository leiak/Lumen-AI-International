/**
 * pages/Order/Form — RTL smoke test (Iteration 4.6, Task 9).
 *
 * 锁两件事:
 *
 *   1. **编辑提交的 payload 里没有明细行的 `id`** —— 请求侧的 `CargoRequest` /
 *      `ServiceItemRequest` 里没有 id 字段, 明细行由服务端按顺序重建。
 *      把视图侧的 id 原样发回去 = 前端契约与后端契约不一致。
 *      (同理 `serviceItems[].source` 也由服务端写入, 前端不提交。)
 *   2. **三个布尔字段发出去的是 boolean, 不是 number** ——
 *      响应侧 `CargoView.isStackable/isDangerous` 与 `ServiceItemView.isMandatory`
 *      是 `Integer` (0/1), 请求侧 `CargoRequest` / `ServiceItemRequest` 是 `Boolean`。
 *      Form 层必须显式转换, 不能指望 Jackson 的 int→boolean 隐式强转。
 *
 * Mock 写法照 `src/pages/Finance/Closing/__tests__/List.test.tsx`:
 *   - 直接 mock `orderService` (测 UI 接线, 不测 HTTP);
 *   - <AuthContext.Provider> + <ConfigProvider> + antd <App> + <MemoryRouter> 包一层。
 *
 * <p><b>各用例的超时统一写成 90000</b> —— 这个表单在 jsdom 下渲染很重
 * (一行明细 = 十来个 antd 组件), 本机高负载时单条要跑 15~35s。这个数字
 * **必须写在 `it()` 的第三个参数上**: 配置文件与命令行 `--testTimeout` 都
 * **盖不住**它。留着 30000 时, 本机负载一高就随机超时, 而且**每次失败的用例
 * 集合都不一样** —— 下一轮审查会把它当成回归重复查。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import OrderForm from '@/pages/Order/Form';
import { AuthContext } from '@/auth/AuthProvider';
import type { AuthContextValue } from '@/auth/types';

// jsdom doesn't ship matchMedia — antd 的 responsive 组件需要它。
if (typeof window !== 'undefined' && !window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

// jsdom's getComputedStyle throws "Not implemented" when a pseudoElt is passed.
if (typeof window !== 'undefined') {
  const originalGetComputedStyle = window.getComputedStyle.bind(window);
  window.getComputedStyle = ((elt: Element, pseudoElt?: string | null) => {
    if (pseudoElt) {
      return {
        getPropertyValue: () => '',
        width: '0px',
        height: '0px',
      } as unknown as CSSStyleDeclaration;
    }
    return originalGetComputedStyle(elt);
  }) as typeof window.getComputedStyle;
}

// 只把 orderService 这个对象换成 mock —— 同模块的词表常量保持真实。
vi.mock('@/services/order/orderService', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/services/order/orderService')>();
  return {
    ...actual,
    orderService: {
      page: vi.fn(),
      get: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
    },
  };
});

import { orderService } from '@/services/order/orderService';
import type { OrderDetail } from '@/services/order/orderService';

function makeWrapper(): React.FC<{ children: React.ReactNode }> {
  const value: AuthContextValue = {
    user: {
      userId: 1,
      tenantId: 1,
      username: 'tester',
      roles: ['ORDER_SALES'],
      perms: ['order:edit'],
    },
    isAuthenticated: true,
    isLoading: false,
    login: async () => {},
    logout: () => {},
    refresh: async () => 'token',
    setUser: () => {},
  };
  return ({ children }) => (
    <AuthContext.Provider value={value}>
      <ConfigProvider locale={zhCN}>
        <App>
          <MemoryRouter initialEntries={['/orders/7/edit']}>
            <Routes>
              <Route path="/orders/:id/edit" element={<>{children}</>} />
            </Routes>
          </MemoryRouter>
        </App>
      </ConfigProvider>
    </AuthContext.Provider>
  );
}

/**
 * 编辑模式回填用的详情。
 *
 * 三个布尔字段都是**响应侧的 Integer** —— 1 / 0 / 1。
 * 明细行带 id (11 / 22) 与 source ('MANUAL'), 请求侧没有这两个字段。
 */
function detail(): OrderDetail {
  return {
    id: 7,
    orderNo: 'ORD-20261111-0001',
    customerId: 42,
    businessType: 'OCEAN_FCL',
    tradeDirection: 'IMPORT',
    incoterms: 'FOB',
    serviceType: 'D2D',
    originAddress: '上海',
    destAddress: '洛杉矶',
    totalQty: 100,
    totalWeightKg: 250,
    totalVolumeCbm: 1.5,
    orderStatus: 'DRAFT',
    settlementCurrency: 'CNY',
    etd: '2026-11-20',
    eta: '2026-12-05',
    createdAt: '2026-11-11T08:00:00',
    cargoReadyDate: '2026-11-15',
    containerQty: 1,
    containerTypeCode: '40HQ',
    marksAndNos: 'N/M',
    cargoDescription: '日用陶瓷器皿',
    isDangerous: 0,
    isOversized: 0,
    temperatureRange: null,
    remark: null,
    cancelReason: null,
    closeReason: null,
    voidReason: null,
    submittedAt: null,
    confirmedAt: null,
    deliveredAt: null,
    settledAt: null,
    closedAt: null,
    createdBy: 3,
    updatedBy: null,
    cargo: [
      {
        id: 11,
        cargoNameCn: '陶瓷杯',
        cargoNameEn: 'Ceramic Mug',
        hsCode: '6912',
        pkgQty: 100,
        weightKg: 250,
        volumeCbm: 1.5,
        // 响应侧是 Integer —— 前端必须显式转成 boolean 再提交。
        isStackable: 1,
        isDangerous: 0,
        currencyCode: 'CNY',
        brandName: 'LUMEN',
      },
    ],
    serviceItems: [
      {
        id: 22,
        feeType: 'OCEAN_FREIGHT',
        feeName: '海运费',
        feeCategory: 'FREIGHT',
        calcBasis: 'PER_SHIPMENT',
        qty: 1,
        unitPrice: 1000,
        amount: 1000,
        currencyCode: 'CNY',
        costAmount: 800,
        isMandatory: 1,
        source: 'MANUAL',
      },
    ],
    allowedNextStates: ['PENDING_CONFIRM', 'CANCELLED'],
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(orderService.get).mockResolvedValue(detail());
  vi.mocked(orderService.update).mockResolvedValue(detail());
});

/** 渲染编辑模式, 点「保存」, 返回提交给 service 的 payload。 */
async function submitEditForm(): Promise<{
  id: number;
  payload: Parameters<typeof orderService.update>[1];
}> {
  const Wrapper = makeWrapper();
  render(<Wrapper><OrderForm /></Wrapper>);

  // 等详情回填完 (标题变成「编辑委托单」= loading 结束)。
  await waitFor(() => {
    expect(screen.getByText('编辑委托单')).toBeInTheDocument();
  });
  // 等回填的字段真的落到 DOM 上 (品名输入框的值)。
  await waitFor(() => {
    expect(screen.getByDisplayValue('陶瓷杯')).toBeInTheDocument();
  });

  // antd 的 Button 会在两个汉字之间插空格 → 可访问名是「保 存」。
  fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

  await waitFor(() => {
    expect(orderService.update).toHaveBeenCalled();
  });

  const call = vi.mocked(orderService.update).mock.calls[0];
  return { id: call[0], payload: call[1] };
}

describe('pages/Order/Form', () => {
  it('编辑提交的 payload 里不带明细行的 id (请求侧没有 id 字段)', async () => {
    const { id, payload } = await submitEditForm();

    expect(id).toBe(7);
    expect(payload.cargo).toHaveLength(1);
    expect(payload.serviceItems).toHaveLength(1);

    // 视图侧明明有 id: 11 / 22 —— 提交时必须被剥掉。
    expect('id' in payload.cargo[0]).toBe(false);
    expect('id' in payload.serviceItems[0]).toBe(false);
    // source 由服务端写入, 请求侧也没有 —— 同样不提交。
    expect('source' in payload.serviceItems[0]).toBe(false);

    // 行本身还在 —— 剥的只是 id, 不是整行。
    expect(payload.cargo[0].cargoNameCn).toBe('陶瓷杯');
    expect(payload.serviceItems[0].feeName).toBe('海运费');
  }, 90000);

  it('三个布尔字段提交的是 boolean, 不是视图侧的 Integer', async () => {
    const { payload } = await submitEditForm();

    const cargo = payload.cargo[0];
    const item = payload.serviceItems[0];

    // 视图侧是 1 / 0 / 1 (Integer) —— 发出去必须是 boolean。
    expect(typeof cargo.isStackable).toBe('boolean');
    expect(cargo.isStackable).toBe(true);
    expect(typeof cargo.isDangerous).toBe('boolean');
    expect(cargo.isDangerous).toBe(false);
    expect(typeof item.isMandatory).toBe('boolean');
    expect(item.isMandatory).toBe(true);

    // 头部的 isOversized 也是 boolean (视图侧是 Integer 0)。
    expect(typeof payload.isOversized).toBe('boolean');
    expect(payload.isOversized).toBe(false);
  }, 90000);

  /**
   * 回归: 两个 `Form.List` 里同一行的 key 不能同时挂在 `<Space>` 和它内部的
   * 每个 `Form.Item` 上 —— 那些 `Form.Item` 是同一个 `<Space>` 下的兄弟节点,
   * key 撞车会让 React 抛 "Encountered two children with the same key"。
   *
   * 断言方式: 拦截 `console.error`, 检查有没有 key 重复警告。
   */
  it('明细行不触发 React 的重复 key 警告 (key 只挂在 <Space> 上)', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    try {
      const Wrapper = makeWrapper();
      render(<Wrapper><OrderForm /></Wrapper>);

      // 回填后两个 Form.List 各有 1 行 —— 重复 key 警告就在这之后触发。
      await waitFor(() => {
        expect(screen.getByDisplayValue('陶瓷杯')).toBeInTheDocument();
      });
      await waitFor(() => {
        expect(screen.getByDisplayValue('海运费')).toBeInTheDocument();
      });

      const messages = errorSpy.mock.calls.map((call) =>
        call.map((arg) => (typeof arg === 'string' ? arg : String(arg))).join(' '),
      );
      const duplicateKeyWarnings = messages.filter((m) => m.includes('the same key'));

      expect(duplicateKeyWarnings).toEqual([]);
    } finally {
      errorSpy.mockRestore();
    }
  }, 90000);

  /**
   * 两个 `Form.List` 各自**至少一行** —— 后端 `OrderServiceImpl.requireCargo` /
   * `requireServiceItems` 对空集合抛 CARGO_EMPTY (400_101) / SERVICE_ITEM_EMPTY
   * (400_102)。前端必须先挡住, 不能把一个必被后端拒绝的 payload 发出去,
   * 更不能只给用户一条通用失败通知。
   *
   * <p>写法与门户端 `lumen-customer-web/src/pages/FreightOrder/__tests__/
   * Create.test.tsx` 的同名两条用例同口径。
   */
  it('明细与服务项各删空后提交 → 不发请求, 并就地提示各至少一行', async () => {
    const Wrapper = makeWrapper();
    render(<Wrapper><OrderForm /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByDisplayValue('陶瓷杯')).toBeInTheDocument();
    });

    // 货物明细那 1 行删掉
    fireEvent.click(screen.getAllByRole('button', { name: /删\s*除/ })[0]);
    await waitFor(() => {
      expect(screen.queryByDisplayValue('陶瓷杯')).toBeNull();
    });

    // 服务项那 1 行也删掉
    fireEvent.click(screen.getAllByRole('button', { name: /删\s*除/ })[0]);
    await waitFor(() => {
      expect(screen.queryByDisplayValue('海运费')).toBeNull();
    });

    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));

    await waitFor(() => {
      expect(screen.getByText('请至少添加一行货物明细')).toBeInTheDocument();
    });
    expect(screen.getByText('请至少添加一行服务项')).toBeInTheDocument();
    // 一个必被后端拒绝的 payload 都没发出去。
    expect(orderService.update).not.toHaveBeenCalled();
  }, 90000);

  /**
   * 「超限」**不能被清空** —— 后端 `OrderServiceImpl.applyUpdateOverrides` 对
   * `isOversized` 为 null 的处理是「不设, 保留库中原值」。所以清空下拉 = 提交
   * null = 库里还是「超限」, 而用户以为取消了标记, 回到详情页才发现没取消。
   *
   * <p>断言方式: rc-select 在 `allowClear` 为真时会给根节点加
   * `ant-select-allow-clear` 这个 class —— 用它做「有没有清空入口」的判据。
   * 表单里其它下拉 (可堆叠 / 危险品 / 费用大类 / 必选服务) 是对照组: 它们的
   * 后端口径没改, 仍然允许清空。
   */
  it('「超限」下拉没有清空入口 (allowClear 已去掉), 表单里其它下拉还有', async () => {
    const Wrapper = makeWrapper();
    render(<Wrapper><OrderForm /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByDisplayValue('陶瓷杯')).toBeInTheDocument();
    });

    const oversizedSelect = screen.getByLabelText('超限').closest('.ant-select');
    expect(oversizedSelect).not.toBeNull();
    expect(oversizedSelect!.className).not.toContain('ant-select-allow-clear');

    // 对照组: 别的下拉仍然带 allowClear —— 说明这个判据本身是有效的。
    const allSelects = Array.from(document.querySelectorAll('.ant-select'));
    expect(
      allSelects.filter((el) => el.className.includes('ant-select-allow-clear')).length,
    ).toBeGreaterThan(0);
  }, 90000);

  /**
   * 「结算币种」在编辑模式必须**说清楚「留空 = 保留原值」** —— 后端对 null /
   * blank 的处理是「不写库」, 界面上不写清楚, 用户清空这一栏却发现币种没变。
   */
  it('编辑模式下的「结算币种」写明「留空 = 保留原值」', async () => {
    const Wrapper = makeWrapper();
    render(<Wrapper><OrderForm /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByDisplayValue('陶瓷杯')).toBeInTheDocument();
    });

    // extra 提示 (不是 placeholder —— placeholder 是属性, 取不到 textContent)。
    expect(screen.getByText(/留空 = 保留原值/)).toBeInTheDocument();
    // 编辑模式下这一栏回填的是库里的值 (CNY)。
    expect((screen.getByLabelText('结算币种') as HTMLInputElement).value).toBe('CNY');
  }, 90000);
});
