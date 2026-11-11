/**
 * 4.6 — 门户端委托下单页测试。
 *
 * 重点守住两件事:
 *   1. 提交给后端的 payload **不含 customerId** —— 客户归属由后端从 JWT 注入。
 *   2. 服务项的 feeType / calcBasis 走**受控词表** (下拉), 不是自由输入 ——
 *      自由输入必然被后端 400_106 打回。
 */

import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import FreightOrderCreate, {
  BUSINESS_TYPE_OPTIONS,
  TRADE_DIRECTION_OPTIONS,
  INCOTERMS_OPTIONS,
  SERVICE_TYPE_OPTIONS,
  FEE_TYPE_OPTIONS,
  CALC_BASIS_OPTIONS,
  FEE_CATEGORY_OPTIONS,
} from '../Create';
import { portalFreightOrderService } from '../../../services/portalFreightOrderService';
import type { FreightOrderRow } from '../../../types/freightOrder';

vi.mock('../../../services/portalFreightOrderService');
const mockCreate = vi.mocked(portalFreightOrderService.create);

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

/** 填满 4 个必填的受控下拉 —— 业务类型 / 贸易方向 / 贸易条款 / 服务类型。 */
async function fillRequiredSelects() {
  await pickOption('业务类型', '海运整箱');
  await pickOption('贸易方向', '出口');
  await pickOption('贸易条款', 'FOB');
  await pickOption('服务类型', '门到门');
}

function renderCreate() {
  return render(
    <MemoryRouter initialEntries={['/freight-orders/new']}>
      <FreightOrderCreate />
    </MemoryRouter>
  );
}

describe('FreightOrder 下单页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockCreate.mockResolvedValue({ id: 42 } as FreightOrderRow);
  });

  it('提交时 payload 不含 customerId (客户归属由后端从 JWT 注入)', async () => {
    renderCreate();
    await fillRequiredSelects();

    fireEvent.click(buttonByText('提交'));

    await waitFor(() => {
      expect(mockCreate).toHaveBeenCalledTimes(1);
    });
    const payload = mockCreate.mock.calls[0][0];
    expect(Object.keys(payload)).not.toContain('customerId');
    expect(payload).not.toHaveProperty('customerId');
    // 4 个必填受控下拉的取值确实传上去了。
    expect(payload.businessType).toBe('OCEAN_FCL');
    expect(payload.tradeDirection).toBe('EXPORT');
    expect(payload.incoterms).toBe('FOB');
    expect(payload.serviceType).toBe('D2D');
  }, 30000);

  it('服务项的 feeType / calcBasis 取自受控词表并随 payload 提交', async () => {
    renderCreate();
    await fillRequiredSelects();

    fireEvent.click(buttonByText('添加服务项'));
    const feeName = await waitFor(() => {
      const el = screen.getByLabelText('费用名称') as HTMLInputElement;
      expect(el).toBeTruthy();
      return el;
    });
    fireEvent.change(feeName, { target: { value: '海运费' } });
    await pickOption('费用类型', '海运费');
    await pickOption('计费基准', '按票');

    fireEvent.click(buttonByText('提交'));

    await waitFor(() => {
      expect(mockCreate).toHaveBeenCalledTimes(1);
    });
    const payload = mockCreate.mock.calls[0][0];
    expect(payload.serviceItems).toHaveLength(1);
    expect(payload.serviceItems[0].feeType).toBe('OCEAN_FREIGHT');
    expect(payload.serviceItems[0].calcBasis).toBe('PER_SHIPMENT');
    expect(payload.serviceItems[0].feeName).toBe('海运费');
  }, 30000);

  it('受控下拉的取值与后端 OrderConstants 的受控词表全集一致', () => {
    // 业务类型 —— OrderConstants.BUSINESS_TYPES (OrderConstants.java:108-110)
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
    // 贸易方向 —— OrderConstants.TRADE_DIRECTIONS (OrderConstants.java:117-118)
    expect(TRADE_DIRECTION_OPTIONS.map((o) => o.value)).toEqual([
      'EXPORT',
      'IMPORT',
      'TRANSIT',
      'TRANSIT_OVERLAND',
    ]);
    // 贸易条款 —— OrderConstants.INCOTERMS (OrderConstants.java:121-122)
    expect(INCOTERMS_OPTIONS.map((o) => o.value)).toEqual([
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
    // 服务类型 —— OrderConstants.SERVICE_TYPES (OrderConstants.java:129)
    expect(SERVICE_TYPE_OPTIONS.map((o) => o.value)).toEqual([
      'D2D',
      'P2P',
      'D2P',
      'P2D',
    ]);
    // 费用类型 —— OrderConstants.FEE_TYPES (OrderConstants.java:140-142)
    expect(FEE_TYPE_OPTIONS.map((o) => o.value)).toEqual([
      'OCEAN_FREIGHT',
      'AIR_FREIGHT',
      'BAF',
      'THC',
      'DOC_FEE',
      'CUSTOMS',
      'DELIVERY',
      'OTHER',
    ]);
    // 计费基准 —— OrderConstants.CALC_BASES (OrderConstants.java:132-133)
    expect(CALC_BASIS_OPTIONS.map((o) => o.value)).toEqual([
      'PER_SHIPMENT',
      'PER_CONTAINER',
      'PER_BOX',
      'PER_KG',
      'PER_CBM',
      'PER_CARGO',
    ]);
    // 费用大类 —— OrderConstants.FEE_CATEGORIES (OrderConstants.java:136-137)
    expect(FEE_CATEGORY_OPTIONS.map((o) => o.value)).toEqual([
      'FREIGHT',
      'SURCHARGE',
      'CUSTOMS',
      'DELIVERY',
      'WAREHOUSE',
      'OTHER',
    ]);
  });
});
