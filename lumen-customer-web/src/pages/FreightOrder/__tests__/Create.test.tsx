/**
 * 4.6 — 门户端委托下单页测试。
 *
 * 重点守住三件事:
 *   1. 提交给后端的 payload **不含 customerId** —— 客户归属由后端从 JWT 注入。
 *   2. 服务项的 feeType / calcBasis 走**受控词表** (下拉), 不是自由输入 ——
 *      自由输入必然被后端 400_106 打回。
 *   3. **货物明细与服务项各至少一行** —— 后端 `OrderServiceImpl.requireCargo` /
 *      `requireServiceItems` 对空集合抛 400_101 / 400_102, 前端必须先挡住,
 *      不能把一个必被后端拒绝的 payload 发出去 (更不能只弹一句「下单失败」)。
 *
 * <p><b>各用例的超时统一写成 90000</b> —— antd 的 Select 在 jsdom 下开关很慢,
 * 单条用例本机要 10~30s。这个数字**必须写在 `it()` 的第三个参数上**:
 * 配置文件与命令行 `--testTimeout` 都**盖不住**它 (实测 `--testTimeout=180000`
 * 覆盖不了 `it(name, fn, 30000)`)。留着 30000 时, 本机负载一高就随机超时,
 * 而且**每次失败的用例集合都不一样** —— 下一轮审查会把它当成回归重复查。
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
  await pickOption('业务类型', '海运整箱 (OCEAN_FCL)');
  await pickOption('贸易方向', '出口 (EXPORT)');
  await pickOption('贸易条款', '装运港船上交货 (FOB)');
  await pickOption('服务类型', '门到门 (D2D)');
}

/**
 * 加一行货物明细 —— 品名在**前端**必填。
 *
 * ⚠️ 后端 `CargoRequest.cargoNameCn` 只有 `@Size(max = 128)`, **没有 `@NotBlank`**,
 * V42 的 `t_order_cargo.cargo_name_cn VARCHAR(128) DEFAULT NULL` 也可空 ——
 * 所以这条 `required: true` 是**门户侧自己加严**的规则 (避免客户提交无名货物行),
 * 不是后端的要求。
 *
 * 品名那个 Form.Item **没有 label** (只有一个 placeholder), 所以用
 * getByPlaceholderText 找, 不能用 getByLabelText。
 */
async function addCargoRow(cargoName = '锂电池') {
  fireEvent.click(buttonByText('添加货物明细'));
  const input = await waitFor(() => {
    const el = screen.getByPlaceholderText('品名') as HTMLInputElement;
    expect(el).toBeTruthy();
    return el;
  });
  fireEvent.change(input, { target: { value: cargoName } });
}

/**
 * 加一行服务项 —— 费用名称 / 费用类型 / 计费基准 都是必填
 * (后端 ServiceItemRequest 的 @NotBlank + 受控词表)。
 */
async function addServiceRow(feeName = '海运费') {
  fireEvent.click(buttonByText('添加服务项'));
  const nameInput = await waitFor(() => {
    const el = screen.getByLabelText('费用名称') as HTMLInputElement;
    expect(el).toBeTruthy();
    return el;
  });
  fireEvent.change(nameInput, { target: { value: feeName } });
  await pickOption('费用类型', '海运费 (OCEAN_FREIGHT)');
  await pickOption('计费基准', '按票 (PER_SHIPMENT)');
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
    await addCargoRow();
    await addServiceRow();

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
  }, 90000);

  it('一行货物明细都不加就提交 → 不发请求, 并提示至少加一行货物明细', async () => {
    renderCreate();
    await fillRequiredSelects();

    fireEvent.click(buttonByText('提交'));

    // 后端 OrderServiceImpl.requireCargo 对空集合抛 400_101 (CARGO_EMPTY) ——
    // 前端必须自己挡住, 不能把必被拒绝的 payload 发出去。
    await waitFor(() => {
      expect(screen.getByText('请至少添加一行货物明细')).toBeInTheDocument();
    });
    expect(mockCreate).not.toHaveBeenCalled();
  }, 90000);

  it('加了货物明细但没加服务项就提交 → 不发请求, 并提示至少加一行服务项', async () => {
    renderCreate();
    await fillRequiredSelects();
    await addCargoRow();

    fireEvent.click(buttonByText('提交'));

    // 后端 requireServiceItems 对空集合抛 400_102 (SERVICE_ITEM_EMPTY)。
    await waitFor(() => {
      expect(screen.getByText('请至少添加一行服务项')).toBeInTheDocument();
    });
    expect(mockCreate).not.toHaveBeenCalled();
  }, 90000);

  it('货物明细与服务项各加一行后提交 → payload 里 cargo / serviceItems 都非空', async () => {
    renderCreate();
    await fillRequiredSelects();
    await addCargoRow('锂电池');
    await addServiceRow('海运费');

    fireEvent.click(buttonByText('提交'));

    await waitFor(() => {
      expect(mockCreate).toHaveBeenCalledTimes(1);
    });
    const payload = mockCreate.mock.calls[0][0];
    expect(payload.cargo).toHaveLength(1);
    expect(payload.cargo[0].cargoNameCn).toBe('锂电池');
    expect(payload.serviceItems).toHaveLength(1);
    expect(payload.serviceItems[0].feeName).toBe('海运费');
  }, 90000);

  it('服务项的 feeType / calcBasis 取自受控词表并随 payload 提交', async () => {
    renderCreate();
    await fillRequiredSelects();
    await addCargoRow();
    await addServiceRow();

    fireEvent.click(buttonByText('提交'));

    await waitFor(() => {
      expect(mockCreate).toHaveBeenCalledTimes(1);
    });
    const payload = mockCreate.mock.calls[0][0];
    expect(payload.serviceItems).toHaveLength(1);
    expect(payload.serviceItems[0].feeType).toBe('OCEAN_FREIGHT');
    expect(payload.serviceItems[0].calcBasis).toBe('PER_SHIPMENT');
    expect(payload.serviceItems[0].feeName).toBe('海运费');
  }, 90000);

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

  it('下拉的标签一律写成「中文 (CODE)」—— 标签里都带上了自己的 CODE', () => {
    // 与 admin-web 的 `orderService.test.ts`「四个下拉的标签里都带上了自己的 CODE」同口径:
    // CODE 就是提交给后端的那个值, 用户在界面上能直接看到自己选的是什么码。
    for (const options of [
      BUSINESS_TYPE_OPTIONS,
      TRADE_DIRECTION_OPTIONS,
      INCOTERMS_OPTIONS,
      SERVICE_TYPE_OPTIONS,
      FEE_TYPE_OPTIONS,
      CALC_BASIS_OPTIONS,
      FEE_CATEGORY_OPTIONS,
    ]) {
      for (const o of options) {
        expect(o.label).toContain(o.value);
      }
    }
  });
});
