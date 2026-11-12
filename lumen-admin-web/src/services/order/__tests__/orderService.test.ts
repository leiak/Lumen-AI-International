/**
 * services/order/orderService.ts — smoke tests (Iteration 4.6, Task 9).
 *
 * 锁三件事:
 *
 *   1. **路径是相对形式** —— `request.ts` 的 axios baseURL 已经是 `/api/v1`,
 *      所以 service 里必须写 `/orders` 而不是 `/api/v1/orders`。
 *      仓里既有的 30 个 service 写的是绝对路径 (拼成 `/api/v1/api/v1/...`),
 *      那是既有缺陷 —— **新代码不许复制**。
 *   2. **建单 payload 的形状** —— `cargo` / `serviceItems` 是嵌套数组,
 *      布尔字段是 **boolean** (请求侧 `CargoRequest` / `ServiceItemRequest`
 *      是 `Boolean`, 响应侧才是 `Integer`)。service 层原样转发, 不做类型转换 ——
 *      转换发生在 Form 层 (见 `pages/Order/__tests__/Form.test.tsx`)。
 *   3. **受控词表的集合** —— 5 份下拉常量的取值必须逐个等于后端
 *      `com.lumen.order.OrderConstants` 的对应 List。后端加词表而前端没跟上,
 *      这条会先转红。
 *
 * Mock 写法照 `src/services/dispatch/__tests__/deliveryOrder.test.ts`:
 * 直接 mock `@/services/request` 的 axios 实例, 断言调用的路径与 body。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createElement } from 'react';
import { render, screen } from '@testing-library/react';
import type { AxiosResponse } from 'axios';

vi.mock('@/services/request', () => {
  const get = vi.fn();
  const post = vi.fn();
  const put = vi.fn();
  const del = vi.fn();
  const request = { get, post, put, delete: del };
  return {
    request,
    default: request,
    PageResult: class {},
    ApiEnvelope: class {},
    ApiError: class extends Error {},
  };
});

import { request } from '@/services/request';
import { orderService } from '@/services/order/orderService';
import {
  BUSINESS_TYPE_OPTIONS,
  INCOTERM_OPTIONS,
  ORDER_STATUS_OPTIONS,
  SERVICE_TYPE_OPTIONS,
  TRADE_DIRECTION_OPTIONS,
} from '@/services/order/orderService';
import { OrderStatusTag } from '@/components/order/OrderStatusTag';

/**
 * mock 的 axios 实例**没有装真实的响应拦截器** —— `asData` 只是 `await`,
 * 不负责剥信封。真实运行时 `request.ts` 的响应拦截器已经 `return body.data`,
 * 所以 promise resolve 出来的就是**裸 data** —— 这里模拟的就是它。
 */
function unwrapped<T>(data: T): AxiosResponse<unknown> {
  return data as unknown as AxiosResponse<unknown>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('orderService — 路径', () => {
  it('page() GET 的是相对路径 /orders (不是 /api/v1/orders)', async () => {
    vi.mocked(request.get).mockResolvedValueOnce(
      unwrapped({ records: [], total: 0, pageNum: 1, pageSize: 20 }),
    );

    await orderService.page({ current: 1, size: 20, status: 'DRAFT' });

    expect(request.get).toHaveBeenCalledWith('/orders', {
      params: { current: 1, size: 20, status: 'DRAFT' },
    });
    const [url] = vi.mocked(request.get).mock.calls[0];
    expect(url).not.toContain('/api/v1');
  });

  it('get(id) GET /orders/{id} (相对路径)', async () => {
    vi.mocked(request.get).mockResolvedValueOnce(unwrapped({ id: 7 }));

    await orderService.get(7);

    expect(request.get).toHaveBeenCalledWith('/orders/7');
  });

  it('9 个状态转换端点都是相对路径 /orders/{id}/{action}', async () => {
    const post = vi.mocked(request.post);
    post.mockResolvedValue(unwrapped({ id: 7 }));

    await orderService.submit(7);
    await orderService.confirm(7);
    await orderService.returnToDraft(7, '客户要求修改');
    await orderService.cancel(7, '客户临时取消');
    await orderService.voidOrder(7, '重复建单作废');
    await orderService.startOperation(7);
    await orderService.deliver(7);
    await orderService.settle(7);
    await orderService.close(7, '账已结清归档');

    expect(post.mock.calls.map((c) => c[0])).toEqual([
      '/orders/7/submit',
      '/orders/7/confirm',
      '/orders/7/return',
      '/orders/7/cancel',
      '/orders/7/void',
      '/orders/7/start-operation',
      '/orders/7/deliver',
      '/orders/7/settle',
      '/orders/7/close',
    ]);
    for (const call of post.mock.calls) {
      expect(call[0]).not.toContain('/api/v1');
    }
  });

  it('带原因的四个动作把 reason 放进 body', async () => {
    const post = vi.mocked(request.post);
    post.mockResolvedValue(unwrapped({ id: 7 }));

    await orderService.returnToDraft(7, '退回原因');
    await orderService.cancel(7, '取消原因');
    await orderService.voidOrder(7, '作废原因');
    await orderService.close(7, '关闭原因');

    expect(post.mock.calls.map((c) => c[1])).toEqual([
      { reason: '退回原因' },
      { reason: '取消原因' },
      { reason: '作废原因' },
      { reason: '关闭原因' },
    ]);
  });

  it('update(id) PUT /orders/{id}', async () => {
    vi.mocked(request.put).mockResolvedValueOnce(unwrapped({ id: 7 }));

    await orderService.update(7, {
      customerId: 1,
      businessType: 'OCEAN_FCL',
      tradeDirection: 'IMPORT',
      incoterms: 'FOB',
      serviceType: 'D2D',
      cargo: [],
      serviceItems: [],
    });

    expect(request.put).toHaveBeenCalledWith(
      '/orders/7',
      expect.objectContaining({ customerId: 1 }),
    );
  });
});

describe('orderService — 建单 payload 的形状', () => {
  it('create() POST /orders, cargo / serviceItems 是嵌套数组, 布尔字段是 boolean', async () => {
    vi.mocked(request.post).mockResolvedValueOnce(
      unwrapped({ id: 7, orderNo: 'ORD-20261111-0001' }),
    );

    const payload = {
      customerId: 42,
      businessType: 'OCEAN_FCL',
      tradeDirection: 'IMPORT',
      incoterms: 'FOB',
      serviceType: 'D2D',
      isOversized: false,
      cargo: [
        {
          cargoNameCn: '陶瓷杯',
          pkgQty: 100,
          weightKg: 250,
          // 请求侧是 Boolean —— 响应侧 (CargoView) 才是 Integer。
          isStackable: true,
          isDangerous: false,
        },
      ],
      serviceItems: [
        {
          feeType: 'OCEAN_FREIGHT',
          feeName: '海运费',
          calcBasis: 'PER_CONTAINER',
          qty: 1,
          unitPrice: 1000,
          amount: 1000,
          currencyCode: 'CNY',
          isMandatory: true,
        },
      ],
    };

    const created = await orderService.create(payload);

    expect(request.post).toHaveBeenCalledWith('/orders', payload);
    expect(created.id).toBe(7);

    // 布尔字段原样是 boolean, 没有被转成 number。
    const sent = vi.mocked(request.post).mock.calls[0][1] as typeof payload;
    expect(typeof sent.cargo[0].isStackable).toBe('boolean');
    expect(typeof sent.cargo[0].isDangerous).toBe('boolean');
    expect(typeof sent.serviceItems[0].isMandatory).toBe('boolean');
    expect(sent.cargo[0].isStackable).toBe(true);
    expect(sent.serviceItems[0].isMandatory).toBe(true);
    // 头部 isOversized 也是 boolean。
    expect(typeof sent.isOversized).toBe('boolean');
  });
});

describe('orderService — 受控词表与后端 OrderConstants 逐字对齐', () => {
  it('业务类型 = OrderConstants.BUSINESS_TYPES (8 个)', () => {
    // 后端 OrderConstants.BUSINESS_TYPES
    expect(BUSINESS_TYPE_OPTIONS.map((o) => o.value)).toEqual([
      'OCEAN_FCL',
      'OCEAN_LCL',
      'AIR',
      'RAIL',
      'TRUCK',
      'EXPRESS',
      'MULTIMODAL',
      'WAREHOUSE',
    ]);
  });

  it('贸易方向 = OrderConstants.TRADE_DIRECTIONS (4 个)', () => {
    // 后端 OrderConstants.TRADE_DIRECTIONS
    expect(TRADE_DIRECTION_OPTIONS.map((o) => o.value)).toEqual([
      'EXPORT',
      'IMPORT',
      'TRANSIT',
      'TRANSIT_OVERLAND',
    ]);
  });

  it('贸易条款 = OrderConstants.INCOTERMS (10 个)', () => {
    // 后端 OrderConstants.INCOTERMS
    expect(INCOTERM_OPTIONS.map((o) => o.value)).toEqual([
      'EXW',
      'FCA',
      'FOB',
      'CFR',
      'CIF',
      'CPT',
      'CIP',
      'DAP',
      'DPU',
      'DDP',
    ]);
  });

  it('服务类型 = OrderConstants.SERVICE_TYPES (4 个, DDP 是 Incoterm 不是服务类型)', () => {
    // 后端 OrderConstants.SERVICE_TYPES
    expect(SERVICE_TYPE_OPTIONS.map((o) => o.value)).toEqual([
      'D2D',
      'P2P',
      'D2P',
      'P2D',
    ]);
  });

  it('委托单状态 = OrderConstants.STATES (9 个)', () => {
    // 后端 OrderConstants.STATES
    expect(ORDER_STATUS_OPTIONS.map((o) => o.value)).toEqual([
      'DRAFT',
      'PENDING_CONFIRM',
      'CONFIRMED',
      'IN_OPERATION',
      'DELIVERED',
      'SETTLED',
      'CLOSED',
      'CANCELLED',
      'VOID',
    ]);
  });

  it('状态标签组件渲染的中文文案与 ORDER_STATUS_OPTIONS 逐条相等 (单一来源)', () => {
    // 状态标签以前在 `components/order/OrderStatusTag.tsx` 里**另抄了一份** 9 条中文
    // 文案, 与这里各写一遍 —— 两处迟早漂移。现在标签组件从 ORDER_STATUS_OPTIONS
    // 派生, 这条用例锁住「派生关系真的成立」: 逐个状态渲染一遍, 渲染出来的文字
    // 必须与选项里的 label 逐条相等。
    expect(ORDER_STATUS_OPTIONS).toHaveLength(9);

    for (const o of ORDER_STATUS_OPTIONS) {
      // 这个文件是 `.ts` (不是 `.tsx`) → 用 createElement, 不写 JSX。
      const { unmount } = render(createElement(OrderStatusTag, { value: o.value }));
      expect(screen.getByText(o.label)).toBeInTheDocument();
      unmount();
    }
  });

  it('四个下拉的标签里都带上了自己的 CODE —— 中英文都能看懂', () => {
    for (const options of [
      BUSINESS_TYPE_OPTIONS,
      TRADE_DIRECTION_OPTIONS,
      INCOTERM_OPTIONS,
      SERVICE_TYPE_OPTIONS,
    ]) {
      for (const o of options) {
        expect(o.label).toContain(o.value);
      }
    }
  });
});
