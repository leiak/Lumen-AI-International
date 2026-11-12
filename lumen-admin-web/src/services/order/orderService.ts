/**
 * Lumen Admin — 货运委托单 service (Iteration 4.6).
 *
 * <p><b>13 个端点</b>: 分页 / 详情 / 建单 / 编辑 + 9 个状态转换
 * (submit / confirm / return / cancel / void / start-operation / deliver / settle / close)。
 * 端点契约见 Iteration 4.6 计划 Task 9 Step 1; 后端业务方法见
 * `lumen-parent/lumen-order/src/main/java/com/lumen/order/service/OrderService.java`。
 *
 * <p><b>路径一律用相对形式 `/orders`</b> —— `services/request.ts` 的 axios `baseURL`
 * 已经是 `/api/v1`。
 *
 * <p><b>为什么不是 `/api/v1/orders`</b>: axios 的 `combineURLs` 把 baseURL 与请求 URL
 * 直接拼起来, 实测 `axios.create({baseURL:'/api/v1'}).getUri({url:'/api/v1/orders'})`
 * = `/api/v1/api/v1/orders`。仓里既有 30 个 service 源文件写的是绝对 `/api/v1/xxx`
 * (cs 4 + dispatch 8 + finance 9 + wms 9) → 会拼成 `/api/v1/api/v1/xxx` —— 那是
 * **既有缺陷**, 不在本轮修复范围 (Task 11 会把它写进文档订正清单)。新代码不复制
 * 这个 bug。
 *
 * <p><b>后端现状</b>: 管理端 REST 控制器 `com.lumen.order.controller.OrderController`
 * 已由提交 `b502e49` 落地 —— 13 条路径与权限码**逐条对齐** (分页 / 详情 / 建单 /
 * 编辑 + 9 个状态转换)。后端业务方法见
 * `lumen-order/.../service/OrderService.java` (分页 / 详情 / 建单 / 编辑 + 9 个
 * 管理端状态转换方法)。
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/**
 * `asData` 住在 `services/dispatch/_util.ts` (Iteration 4.1)。
 *
 * 它是**通用**的 axios 拦截器适配器 (1 行), 不是 dispatch 专属 ——
 * 这里复用而不是复制第四份。管理端此前已有 dispatch / cs / finance 三份拷贝,
 * order 是第四个域, 再抄一份就是第四份。
 *
 * 把它搬到 `services/_util.ts` 要改 21 个既有 service 源文件
 * (cs 4 + dispatch 8 + finance 9), 加上本文件共 22 个 —— 超出本轮范围。
 */
import { asData } from '@/services/dispatch/_util';

/** 后端 `R<T>` 信封的本地别名。 */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Types — 视图侧镜像后端 dto/OrderView.java / OrderDetailView.java
// ---------------------------------------------------------------------------

/**
 * 货物明细行 (响应侧) —— 对应 `CargoView` (22 个字段)。
 *
 * <p><b>三个布尔字段在响应侧是 `Integer`** —— `isStackable` / `isDangerous` 是
 * `TINYINT` (V42 的 `t_order_cargo.is_stackable` / `is_dangerous`),
 * `CargoView` 里也确实是 `Integer`。请求侧则是 `Boolean` (见 `OrderCargoItemInput`)。
 */
export interface OrderCargoItem {
  id: number;
  cargoNameCn?: string | null;
  cargoNameEn?: string | null;
  hsCode?: string | null;
  pkgType?: string | null;
  pkgQty?: number | null;
  unit?: string | null;
  weightKg?: number | null;
  netWeightKg?: number | null;
  volumeCbm?: number | null;
  lengthCm?: number | null;
  widthCm?: number | null;
  heightCm?: number | null;
  isStackable?: number | null;
  cargoValue?: number | null;
  currencyCode?: string | null;
  brandName?: string | null;
  model?: string | null;
  isDangerous?: number | null;
  unNo?: string | null;
  dangerClass?: string | null;
  remark?: string | null;
}

/**
 * 服务项行 (响应侧) —— 对应 `ServiceItemView` (14 个字段)。
 *
 * <p>`isMandatory` 同样是 `Integer` (响应侧) vs `Boolean` (请求侧)。
 * `source` 由服务端写入 (V42 的 `t_order_service_item.source` 默认 'MANUAL'),
 * **不在 `ServiceItemRequest` 里** —— 前端不渲染也不提交。
 */
export interface OrderServiceItem {
  id: number;
  feeType?: string | null;
  feeName?: string | null;
  feeCategory?: string | null;
  calcBasis?: string | null;
  qty?: number | null;
  unitPrice?: number | null;
  amount?: number | null;
  currencyCode?: string | null;
  supplierId?: number | null;
  costAmount?: number | null;
  isMandatory?: number | null;
  source?: string | null;
  remark?: string | null;
}

/** 列表行 —— 对应 `OrderView`。 */
export interface OrderRow {
  id: number;
  orderNo: string;
  customerId: number;
  businessType?: string | null;
  tradeDirection?: string | null;
  incoterms?: string | null;
  serviceType?: string | null;
  originAddress?: string | null;
  destAddress?: string | null;
  totalQty?: number | null;
  totalWeightKg?: number | null;
  totalVolumeCbm?: number | null;
  orderStatus: string;
  settlementCurrency?: string | null;
  etd?: string | null;
  eta?: string | null;
  createdAt?: string | null;
}

/** 详情 —— 对应 `OrderDetailView` (= OrderView + 明细 + 允许的下一状态)。 */
export interface OrderDetail extends OrderRow {
  cargoReadyDate?: string | null;
  containerQty?: number | null;
  containerTypeCode?: string | null;
  marksAndNos?: string | null;
  cargoDescription?: string | null;
  isDangerous?: number | null;
  isOversized?: number | null;
  temperatureRange?: string | null;
  remark?: string | null;
  cancelReason?: string | null;
  closeReason?: string | null;
  voidReason?: string | null;
  submittedAt?: string | null;
  confirmedAt?: string | null;
  deliveredAt?: string | null;
  settledAt?: string | null;
  closedAt?: string | null;
  createdBy?: number | null;
  updatedBy?: number | null;
  cargo: OrderCargoItem[];
  serviceItems: OrderServiceItem[];
  /** 状态机允许的下一状态 —— 前端据此渲染动作按钮。 */
  allowedNextStates: string[];
}

/**
 * 货物明细行 (请求侧) —— 对应 `CargoRequest`。
 *
 * <p><b>与 `OrderCargoItem` 的差别只有两处, 但都是致命的</b>:
 * <ul>
 *   <li>请求侧**没有 `id`** —— `CargoRequest` 里没有 id 字段, 明细行由服务端建。</li>
 *   <li>`isStackable` / `isDangerous` 请求侧是 **`Boolean`**, 响应侧才是 `Integer`。</li>
 * </ul>
 * 两者混用 = 编辑时把 Integer 原样发回去, 靠 Jackson 的隐式强转兜底 —— 不受控。
 */
export interface OrderCargoItemInput {
  cargoNameCn?: string | null;
  cargoNameEn?: string | null;
  hsCode?: string | null;
  pkgType?: string | null;
  pkgQty?: number | null;
  unit?: string | null;
  weightKg?: number | null;
  netWeightKg?: number | null;
  volumeCbm?: number | null;
  lengthCm?: number | null;
  widthCm?: number | null;
  heightCm?: number | null;
  /** 请求侧是 `Boolean` (`CargoRequest.isStackable`)。 */
  isStackable?: boolean | null;
  cargoValue?: number | null;
  currencyCode?: string | null;
  brandName?: string | null;
  model?: string | null;
  /** 请求侧是 `Boolean` (`CargoRequest.isDangerous`)。 */
  isDangerous?: boolean | null;
  unNo?: string | null;
  dangerClass?: string | null;
  remark?: string | null;
}

/**
 * 服务项行 (请求侧) —— 对应 `ServiceItemRequest`。
 *
 * <p>同样没有 `id`, 也没有 `source`; `isMandatory` 请求侧是 `Boolean`。
 */
export interface OrderServiceItemInput {
  feeType?: string | null;
  feeName?: string | null;
  feeCategory?: string | null;
  calcBasis?: string | null;
  qty?: number | null;
  unitPrice?: number | null;
  amount?: number | null;
  currencyCode?: string | null;
  supplierId?: number | null;
  costAmount?: number | null;
  /** 请求侧是 `Boolean` (`ServiceItemRequest.isMandatory`)。 */
  isMandatory?: boolean | null;
  remark?: string | null;
}

/**
 * 列表查询参数 —— 与后端 `OrderService.page(current, size, status, customerId,
 * orderNo, businessType)` 的入参一一对应。
 */
export interface OrderPageQuery {
  current?: number;
  size?: number;
  status?: string;
  customerId?: number;
  orderNo?: string;
  businessType?: string;
}

/** 建单 / 编辑的 body —— 与后端 `CreateOrderRequest` 一一对应。 */
export interface CreateOrderPayload {
  customerId: number;
  businessType: string;
  tradeDirection: string;
  incoterms: string;
  serviceType: string;
  originAddress?: string | null;
  destAddress?: string | null;
  cargoReadyDate?: string | null;
  etd?: string | null;
  eta?: string | null;
  containerQty?: number | null;
  containerTypeCode?: string | null;
  marksAndNos?: string | null;
  cargoDescription?: string | null;
  isOversized?: boolean | null;
  temperatureRange?: string | null;
  settlementCurrency?: string | null;
  remark?: string | null;
  cargo: OrderCargoItemInput[];
  serviceItems: OrderServiceItemInput[];
}

// ---------------------------------------------------------------------------
// 受控词表 —— 与后端 com.lumen.order.OrderConstants 逐字一致
// ---------------------------------------------------------------------------

/*
 * 放在 service 模块里而不是各自页面里, 是因为**列表页的筛选下拉**与
 * **建单表单的下拉**必须是同一份 —— 两处各写一遍, 迟早漂移 (计划里的
 * 列表页只给了 5 个业务类型, 表单页也只给了 5 个, 而后端词表是 8 个)。
 *
 * service 层用 `requireEnums` 校验受控词表 —— 下拉里出现词表外的值,
 * 提交后吃 HTTP 400 ENUM_VALUE_INVALID (400_106), 而用户根本不知道自己错在哪。
 *
 * 标签一律写成「中文 (CODE)」—— CODE 就是提交给后端的那个值, 中英文都看得懂。
 */

/**
 * business_type 运输方式 —— 后端 `OrderConstants.BUSINESS_TYPES`
 * (8 个: OCEAN_FCL / OCEAN_LCL / AIR / RAIL / TRUCK / EXPRESS / MULTIMODAL /
 * WAREHOUSE)。
 */
export const BUSINESS_TYPE_OPTIONS = [
  { value: 'OCEAN_FCL', label: '海运整箱 (OCEAN_FCL)' },
  { value: 'OCEAN_LCL', label: '海运拼箱 (OCEAN_LCL)' },
  { value: 'AIR', label: '空运 (AIR)' },
  { value: 'RAIL', label: '铁路 (RAIL)' },
  { value: 'TRUCK', label: '陆运 (TRUCK)' },
  { value: 'EXPRESS', label: '快递 (EXPRESS)' },
  { value: 'MULTIMODAL', label: '多式联运 (MULTIMODAL)' },
  { value: 'WAREHOUSE', label: '仓储 (WAREHOUSE)' },
];

/**
 * trade_direction 贸易方向 —— 后端 `OrderConstants.TRADE_DIRECTIONS`
 * (4 个: EXPORT / IMPORT / TRANSIT / TRANSIT_OVERLAND)。
 */
export const TRADE_DIRECTION_OPTIONS = [
  { value: 'EXPORT', label: '出口 (EXPORT)' },
  { value: 'IMPORT', label: '进口 (IMPORT)' },
  { value: 'TRANSIT', label: '过境 (TRANSIT)' },
  { value: 'TRANSIT_OVERLAND', label: '过境陆运 (TRANSIT_OVERLAND)' },
];

/**
 * incoterms 贸易条款 —— 后端 `OrderConstants.INCOTERMS`
 * (10 个: EXW / FCA / FOB / CFR / CIF / CPT / CIP / DAP / DPU / DDP)。
 */
export const INCOTERM_OPTIONS = [
  { value: 'EXW', label: '工厂交货 (EXW)' },
  { value: 'FCA', label: '货交承运人 (FCA)' },
  { value: 'FOB', label: '装运港船上交货 (FOB)' },
  { value: 'CFR', label: '成本加运费 (CFR)' },
  { value: 'CIF', label: '成本、保险费加运费 (CIF)' },
  { value: 'CPT', label: '运费付至 (CPT)' },
  { value: 'CIP', label: '运费和保险费付至 (CIP)' },
  { value: 'DAP', label: '目的地交货 (DAP)' },
  { value: 'DPU', label: '目的地卸货交货 (DPU)' },
  { value: 'DDP', label: '完税后交货 (DDP)' },
];

/**
 * service_type 服务类型 —— 后端 `OrderConstants.SERVICE_TYPES`
 * (4 个: D2D / P2P / D2P / P2D)。
 *
 * <p>⚠️ `DDP` 是**贸易条款 (Incoterm)**, 不是服务类型 —— 不在这张表里,
 * 用户选「完税交货」提交必被后端 400_106 打回。
 */
export const SERVICE_TYPE_OPTIONS = [
  { value: 'D2D', label: '门到门 (D2D)' },
  { value: 'P2P', label: '港到港 (P2P)' },
  { value: 'D2P', label: '门到港 (D2P)' },
  { value: 'P2D', label: '港到门 (P2D)' },
];

/**
 * 全部 9 个委托单状态 —— `OrderConstants.STATES`。
 *
 * <p><b>状态的中文文案只有这一份</b> —— 状态标签组件
 * `components/order/OrderStatusTag.tsx` 的文案从这里**派生** (不再另抄一份)。
 */
export const ORDER_STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING_CONFIRM', label: '待确认' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'IN_OPERATION', label: '操作中' },
  { value: 'DELIVERED', label: '已交付' },
  { value: 'SETTLED', label: '已结算' },
  { value: 'CLOSED', label: '已归档' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'VOID', label: '已作废' },
];

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

export const orderService = {
  /** 1. 分页列表。 */
  async page(query: OrderPageQuery): Promise<PageResult<OrderRow>> {
    return asData<PageResult<OrderRow>>(
      request.get<R<PageResult<OrderRow>>>('/orders', { params: query }),
    );
  },

  /** 2. 详情。 */
  async get(id: number): Promise<OrderDetail> {
    return asData<OrderDetail>(request.get<R<OrderDetail>>(`/orders/${id}`));
  },

  /** 3. 建单 (落 DRAFT)。 */
  async create(payload: CreateOrderPayload): Promise<OrderRow> {
    return asData<OrderRow>(request.post<R<OrderRow>>('/orders', payload));
  },

  /** 4. 编辑 (仅 DRAFT / PENDING_CONFIRM)。 */
  async update(id: number, payload: CreateOrderPayload): Promise<OrderDetail> {
    return asData<OrderDetail>(request.put<R<OrderDetail>>(`/orders/${id}`, payload));
  },

  /** 5. 提交 (DRAFT → PENDING_CONFIRM)。 */
  async submit(id: number): Promise<OrderDetail> {
    return asData<OrderDetail>(request.post<R<OrderDetail>>(`/orders/${id}/submit`));
  },

  /** 6. 确认 (PENDING_CONFIRM → CONFIRMED)。 */
  async confirm(id: number): Promise<OrderDetail> {
    return asData<OrderDetail>(request.post<R<OrderDetail>>(`/orders/${id}/confirm`));
  },

  /** 7. 退回 (PENDING_CONFIRM → DRAFT), 必须带原因。 */
  async returnToDraft(id: number, reason: string): Promise<OrderDetail> {
    return asData<OrderDetail>(
      request.post<R<OrderDetail>>(`/orders/${id}/return`, { reason }),
    );
  },

  /** 8. 取消, 必须带原因。 */
  async cancel(id: number, reason: string): Promise<OrderDetail> {
    return asData<OrderDetail>(
      request.post<R<OrderDetail>>(`/orders/${id}/cancel`, { reason }),
    );
  },

  /** 9. 作废, 必须带原因。 */
  async voidOrder(id: number, reason: string): Promise<OrderDetail> {
    return asData<OrderDetail>(
      request.post<R<OrderDetail>>(`/orders/${id}/void`, { reason }),
    );
  },

  /** 10. 开始操作 (CONFIRMED → IN_OPERATION)。 */
  async startOperation(id: number): Promise<OrderDetail> {
    return asData<OrderDetail>(
      request.post<R<OrderDetail>>(`/orders/${id}/start-operation`),
    );
  },

  /** 11. 交付 (IN_OPERATION → DELIVERED)。 */
  async deliver(id: number): Promise<OrderDetail> {
    return asData<OrderDetail>(request.post<R<OrderDetail>>(`/orders/${id}/deliver`));
  },

  /** 12. 结算 (DELIVERED → SETTLED)。 */
  async settle(id: number): Promise<OrderDetail> {
    return asData<OrderDetail>(request.post<R<OrderDetail>>(`/orders/${id}/settle`));
  },

  /** 13. 关闭 (SETTLED → CLOSED), 必须带原因。 */
  async close(id: number, reason: string): Promise<OrderDetail> {
    return asData<OrderDetail>(
      request.post<R<OrderDetail>>(`/orders/${id}/close`, { reason }),
    );
  },
};
