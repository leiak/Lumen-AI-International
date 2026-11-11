/**
 * 4.6 — 货运委托单 TypeScript 类型。
 *
 * 对应后端:
 *   lumen-parent/lumen-order/src/main/java/com/lumen/order/dto/OrderView.java
 *   lumen-parent/lumen-order/src/main/java/com/lumen/order/dto/OrderDetailView.java
 *   lumen-parent/lumen-customer/src/main/java/com/lumen/customer/dto/PortalFreightOrderRequest.java
 *
 * 字段名用 camelCase, 与 Jackson 默认命名策略一致。
 */

/** 货物明细行。 */
export interface FreightOrderCargo {
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
  cargoValue?: number | null;
  currencyCode?: string | null;
  brandName?: string | null;
  isDangerous?: number | null;
  unNo?: string | null;
  remark?: string | null;
}

/** 服务项行 —— 门户侧**不会**有 supplierId / costAmount (后端写入侧已置 null)。 */
export interface FreightOrderServiceItem {
  id: number;
  feeType?: string | null;
  feeName?: string | null;
  feeCategory?: string | null;
  calcBasis?: string | null;
  qty?: number | null;
  unitPrice?: number | null;
  amount?: number | null;
  currencyCode?: string | null;
  isMandatory?: number | null;
  remark?: string | null;
}

/** 列表行。 */
export interface FreightOrderRow {
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

/**
 * 详情 —— 对应后端 `OrderDetailView` (39 个组件的 record)。
 *
 * 下面这些字段与后端 record **逐个对应** (名字与类型), 别再少写:
 * `isDangerous` / `closeReason` / `voidReason` / `settledAt` / `closedAt` /
 * `createdBy` / `updatedBy`。
 */
export interface FreightOrderDetail extends FreightOrderRow {
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
  cargo: FreightOrderCargo[];
  serviceItems: FreightOrderServiceItem[];
  allowedNextStates: string[];
}

/** 列表响应 —— 后端 `PageResult<T>`。 */
export interface FreightOrderPageData {
  records: FreightOrderRow[];
  total: number;
  pageNum: number;
  pageSize: number;
}

/** 列表查询参数。 */
export interface FreightOrderListQuery {
  current?: number;
  size?: number;
  status?: string;
}

/**
 * 下单请求 —— 对应后端 `PortalFreightOrderRequest`。
 *
 * **没有 customerId** —— 客户归属由后端从 JWT 强制注入, 客户端不能指定。
 */
export interface FreightOrderCreateRequest {
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
  cargo: Array<Partial<FreightOrderCargo>>;
  serviceItems: Array<Partial<FreightOrderServiceItem>>;
}
