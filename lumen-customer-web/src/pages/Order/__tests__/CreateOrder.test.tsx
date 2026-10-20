/**
 * 4.5B.3-T3.2 — CreateOrderPage tests.
 *
 * RangePicker 被 mock 成一个按钮, 直接把两个 dayjs 值回填, 避免依赖 antd 日历 DOM。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreateOrder from '../CreateOrder';
import { portalOrderService } from '../../../services/portalOrderService';

vi.mock('../../../services/portalOrderService');

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('antd', async () => {
  const actual = await vi.importActual<any>('antd');
  const dayjs = (await import('dayjs')).default;
  const { createElement } = await import('react');
  const RangePicker = ({ onChange }: any) =>
    createElement(
      'button',
      {
        type: 'button',
        onClick: () =>
          onChange?.([dayjs('2026-10-20T14:00:00'), dayjs('2026-10-20T18:00:00')]),
      },
      '选择时间窗'
    );
  return { ...actual, DatePicker: { ...actual.DatePicker, RangePicker } };
});

const ESTIMATE = {
  amount: 123.45,
  source: 'RULE',
  ruleId: 99,
  overrideId: null,
  currency: 'CNY',
  breakdown: '基础 ¥100 + 距离 ¥23.45',
};

function setInputs(container: HTMLElement, values: string[]) {
  const inputs = container.querySelectorAll('input');
  values.forEach((v, i) => fireEvent.change(inputs[i], { target: { value: v } }));
}

function renderPage() {
  const utils = render(
    <MemoryRouter>
      <CreateOrder />
    </MemoryRouter>
  );
  return { ...utils, container: utils.container as HTMLElement };
}

describe('CreateOrder page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    (portalOrderService.preview as any).mockResolvedValue(ESTIMATE);
    (portalOrderService.create as any).mockResolvedValue({
      doId: 321,
      doNo: 'PO-XYZ',
      status: 'PENDING_DISPATCH',
      canCancel: true,
      timeline: [],
    });
  });

  it('默认停在第 1 步并渲染基本字段', () => {
    const { container } = renderPage();
    expect(screen.getByText('仓库 ID')).toBeInTheDocument();
    expect(screen.getByText('起运地址')).toBeInTheDocument();
    expect(screen.getByText('收货地址')).toBeInTheDocument();
    expect(screen.queryByText('重量 (kg)')).toBeNull();
    expect(container).toBeTruthy();
  });

  it('第 1 步必填未填时点"下一步"不跳转', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '下一步' }));
    await waitFor(() => {
      expect(screen.queryByText('重量 (kg)')).toBeNull();
    });
  });

  it('第 1 步填完点"下一步"进入第 2 步', async () => {
    const { container } = renderPage();
    setInputs(container, ['1', '上海市 A', '北京市 B']);
    fireEvent.click(screen.getByRole('button', { name: '下一步' }));
    expect(await screen.findByText('重量 (kg)')).toBeInTheDocument();
    expect(screen.getByText('取件时间窗 (同日 08-20)')).toBeInTheDocument();
  });

  it('第 2 步完成后调 preview 并在第 3 步展示估价', async () => {
    const { container } = renderPage();
    setInputs(container, ['1', '上海市 A', '北京市 B']);
    fireEvent.click(screen.getByRole('button', { name: '下一步' }));
    await screen.findByText('重量 (kg)');

    setInputs(container, ['10', '0.5', '1']);
    fireEvent.click(screen.getByRole('button', { name: '选择时间窗' }));
    fireEvent.click(screen.getByRole('button', { name: '下一步' }));

    expect(await screen.findByText('¥123.45')).toBeInTheDocument();
    expect(screen.getByText('RULE')).toBeInTheDocument();
    expect(portalOrderService.preview).toHaveBeenCalledTimes(1);
    expect(portalOrderService.create).not.toHaveBeenCalled();
    expect(container).toBeTruthy();
  });

  it('第 3 步提交下单后跳转详情页', async () => {
    const { container } = renderPage();
    setInputs(container, ['1', '上海市 A', '北京市 B']);
    fireEvent.click(screen.getByRole('button', { name: '下一步' }));
    await screen.findByText('重量 (kg)');
    setInputs(container, ['10', '0.5', '1']);
    fireEvent.click(screen.getByRole('button', { name: '选择时间窗' }));
    fireEvent.click(screen.getByRole('button', { name: '下一步' }));
    await screen.findByText('¥123.45');

    fireEvent.click(screen.getByRole('button', { name: '提交下单' }));

    await waitFor(() => {
      expect(portalOrderService.create).toHaveBeenCalledTimes(1);
    });
    expect(navigateMock).toHaveBeenCalledWith('/orders/321');

    // 后端 plannedPickupWindowStart/End 是 java.time.LocalDateTime — Jackson 的
    // LocalDateTimeDeserializer 拒绝尾部的 "Z" / 时区偏移, dayjs.toISOString()
    // 产出的正是 "...T14:00:00.000Z", 直接发会被 400 拒掉。这里锁死契约:
    // 必须是无时区的本地 ISO 串。
    const body = (portalOrderService.create as any).mock.calls[0][0];
    expect(body.plannedPickupWindowStart).toBe('2026-10-20T14:00:00');
    expect(body.plannedPickupWindowEnd).toBe('2026-10-20T18:00:00');
  });
});