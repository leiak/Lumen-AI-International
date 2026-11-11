/**
 * Lumen Admin — 货运委托单建单 / 编辑表单 (Iteration 4.6).
 *
 * <p>有 `:id` 路由参数 → 编辑模式 (先 GET 详情回填, 提交走 PUT);
 * 无参数 → 新建模式 (提交走 POST, 建出来是 DRAFT)。
 *
 * <p><b>布尔字段的类型在两侧不一样</b> —— 响应侧 (`CargoView` / `ServiceItemView`)
 * 是 `Integer` (0/1), 请求侧 (`CargoRequest` / `ServiceItemRequest`) 是 `Boolean`。
 * 所以回填时把 Integer 转成 boolean, 提交时再显式转一次 (见 `toRequestBool`),
 * 不依赖 Jackson 的 int→boolean 隐式强转。
 */

import {
  App as AntApp,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Spin,
} from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import {
  orderService,
  BUSINESS_TYPE_OPTIONS,
  INCOTERM_OPTIONS,
  SERVICE_TYPE_OPTIONS,
  TRADE_DIRECTION_OPTIONS,
} from '@/services/order/orderService';
import type {
  CreateOrderPayload,
  OrderCargoItem,
  OrderCargoItemInput,
  OrderServiceItem,
  OrderServiceItemInput,
} from '@/services/order/orderService';

const { TextArea } = Input;

/**
 * 表单里的货物明细行 —— 布尔字段统一是 `boolean` (请求侧 `CargoRequest` 是 `Boolean`)。
 */
interface CargoFormRow {
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
  isStackable?: boolean | null;
  cargoValue?: number | null;
  currencyCode?: string | null;
  brandName?: string | null;
  model?: string | null;
  isDangerous?: boolean | null;
  unNo?: string | null;
  dangerClass?: string | null;
  remark?: string | null;
}

/**
 * 表单里的服务项行 —— `isMandatory` 同样是 `boolean`。
 *
 * <p>没有 `id`, 也没有 `source` —— 请求侧 `ServiceItemRequest` 里这两个字段都没有
 * (`source` 由服务端写入)。
 */
interface ServiceItemFormRow {
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
  isMandatory?: boolean | null;
  remark?: string | null;
}

interface FormValues {
  customerId: number;
  businessType: string;
  tradeDirection: string;
  incoterms: string;
  serviceType: string;
  originAddress?: string;
  destAddress?: string;
  cargoReadyDate?: Dayjs;
  etd?: Dayjs;
  eta?: Dayjs;
  containerQty?: number;
  containerTypeCode?: string;
  marksAndNos?: string;
  cargoDescription?: string;
  isOversized?: boolean;
  temperatureRange?: string;
  settlementCurrency?: string;
  remark?: string;
  cargo?: CargoFormRow[];
  serviceItems?: ServiceItemFormRow[];
}

/**
 * 四个受控下拉的取值与后端 `com.lumen.order.OrderConstants` 的受控词表**逐字一致**
 * —— service 层用 `requireEnums` 校验受控词表, 下拉里出现词表外的值
 * → HTTP 400 ENUM_VALUE_INVALID (400_106), 而用户根本不知道自己错在哪。
 *
 * - `BUSINESS_TYPE_OPTIONS` = `OrderConstants.BUSINESS_TYPES` (8 个)
 * - `TRADE_DIRECTION_OPTIONS` = `OrderConstants.TRADE_DIRECTIONS` (4 个)
 * - `INCOTERM_OPTIONS` = `OrderConstants.INCOTERMS` (10 个)
 * - `SERVICE_TYPE_OPTIONS` = `OrderConstants.SERVICE_TYPES` (4 个)
 *
 * <p>这四份常量住在 `services/order/orderService.ts` —— 与列表页的筛选下拉共用同一份。
 */

/** 费用类型 = `OrderConstants.FEE_TYPES` (`feeType` 是 `@NotBlank`, 必填)。 */
const FEE_TYPE_OPTIONS = [
  { value: 'OCEAN_FREIGHT', label: '海运费 (OCEAN_FREIGHT)' },
  { value: 'AIR_FREIGHT', label: '空运费 (AIR_FREIGHT)' },
  { value: 'BAF', label: '燃油附加费 (BAF)' },
  { value: 'THC', label: '港口操作费 (THC)' },
  { value: 'DOC_FEE', label: '单证费 (DOC_FEE)' },
  { value: 'CUSTOMS', label: '清关费 (CUSTOMS)' },
  { value: 'DELIVERY', label: '派送费 (DELIVERY)' },
  { value: 'OTHER', label: '其他 (OTHER)' },
];

/** 计费基准 = `OrderConstants.CALC_BASES` (`calcBasis` 是 `@NotBlank`, 必填)。 */
const CALC_BASIS_OPTIONS = [
  { value: 'PER_SHIPMENT', label: '按票 (PER_SHIPMENT)' },
  { value: 'PER_CONTAINER', label: '按箱 (PER_CONTAINER)' },
  { value: 'PER_BOX', label: '按件 (PER_BOX)' },
  { value: 'PER_KG', label: '按公斤 (PER_KG)' },
  { value: 'PER_CBM', label: '按方 (PER_CBM)' },
  { value: 'PER_CARGO', label: '按货物 (PER_CARGO)' },
];

/** 费用大类 = `OrderConstants.FEE_CATEGORIES` (可空)。 */
const FEE_CATEGORY_OPTIONS = [
  { value: 'FREIGHT', label: '运费 (FREIGHT)' },
  { value: 'SURCHARGE', label: '附加费 (SURCHARGE)' },
  { value: 'CUSTOMS', label: '清关 (CUSTOMS)' },
  { value: 'DELIVERY', label: '派送 (DELIVERY)' },
  { value: 'WAREHOUSE', label: '仓储 (WAREHOUSE)' },
  { value: 'OTHER', label: '其他 (OTHER)' },
];

/** 是 / 否 —— 布尔字段的统一控件 (三态: 未填 / 是 / 否)。 */
const YES_NO_OPTIONS = [
  { value: true, label: '是' },
  { value: false, label: '否' },
];

/**
 * 视图侧的 `Integer` (0/1) → 请求侧的 `Boolean`。
 *
 * <p>显式转换, 不依赖 Jackson 的 int→boolean 隐式强转 (虽然它默认会转, 但显式才可控)。
 * null → undefined (字段不出现), 与「用户没填」同义。
 */
const toRequestBool = (v: boolean | number | null | undefined): boolean | undefined => {
  if (v == null) return undefined;
  if (typeof v === 'boolean') return v;
  return v === 1;
};

/** 视图行 → 表单行: 去掉 `id` (请求侧没有 id), 并把 Integer 布尔转成 boolean。 */
const toCargoFormRow = (c: OrderCargoItem): CargoFormRow => {
  const { id: _id, ...rest } = c;
  void _id;
  return {
    ...rest,
    isStackable: toRequestBool(c.isStackable),
    isDangerous: toRequestBool(c.isDangerous),
  };
};

/** 视图行 → 表单行: 去掉 `id` 与 `source` (请求侧两个字段都没有)。 */
const toServiceItemFormRow = (s: OrderServiceItem): ServiceItemFormRow => {
  const { id: _id, source: _source, ...rest } = s;
  void _id;
  void _source;
  return {
    ...rest,
    isMandatory: toRequestBool(s.isMandatory),
  };
};

/** 表单行 → 请求行: 再显式转一次布尔, 保证发出去的一定是 boolean。 */
const toCargoInput = (row: CargoFormRow): OrderCargoItemInput => {
  const { id: _id, ...rest } = row as CargoFormRow & { id?: number };
  void _id;
  return {
    ...rest,
    isStackable: toRequestBool(rest.isStackable),
    isDangerous: toRequestBool(rest.isDangerous),
  };
};

/** 表单行 → 请求行。 */
const toServiceItemInput = (row: ServiceItemFormRow): OrderServiceItemInput => {
  const { id: _id, source: _source, ...rest } = row as ServiceItemFormRow & {
    id?: number;
    source?: string;
  };
  void _id;
  void _source;
  return {
    ...rest,
    isMandatory: toRequestBool(rest.isMandatory),
  };
};

export default function OrderForm() {
  const { id } = useParams<{ id: string }>();
  const editing = id != null;
  const orderId = editing ? Number(id) : null;
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const [form] = Form.useForm<FormValues>();
  const [loading, setLoading] = useState(editing);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!editing || orderId == null) return;
    void (async () => {
      setLoading(true);
      try {
        const d = await orderService.get(orderId);
        form.setFieldsValue({
          customerId: d.customerId,
          businessType: d.businessType ?? undefined,
          tradeDirection: d.tradeDirection ?? undefined,
          incoterms: d.incoterms ?? undefined,
          serviceType: d.serviceType ?? undefined,
          originAddress: d.originAddress ?? undefined,
          destAddress: d.destAddress ?? undefined,
          cargoReadyDate: d.cargoReadyDate ? dayjs(d.cargoReadyDate) : undefined,
          etd: d.etd ? dayjs(d.etd) : undefined,
          eta: d.eta ? dayjs(d.eta) : undefined,
          containerQty: d.containerQty ?? undefined,
          containerTypeCode: d.containerTypeCode ?? undefined,
          marksAndNos: d.marksAndNos ?? undefined,
          cargoDescription: d.cargoDescription ?? undefined,
          isOversized: d.isOversized === 1,
          temperatureRange: d.temperatureRange ?? undefined,
          settlementCurrency: d.settlementCurrency ?? undefined,
          remark: d.remark ?? undefined,
          cargo: (d.cargo ?? []).map(toCargoFormRow),
          serviceItems: (d.serviceItems ?? []).map(toServiceItemFormRow),
        });
      } catch {
        antMessage.error('委托单详情加载失败');
      } finally {
        setLoading(false);
      }
    })();
  }, [editing, orderId, form, antMessage]);

  const onSubmit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return; // antd 已就地提示
    }
    const payload: CreateOrderPayload = {
      customerId: values.customerId,
      businessType: values.businessType,
      tradeDirection: values.tradeDirection,
      incoterms: values.incoterms,
      serviceType: values.serviceType,
      originAddress: values.originAddress ?? null,
      destAddress: values.destAddress ?? null,
      cargoReadyDate: values.cargoReadyDate ? values.cargoReadyDate.format('YYYY-MM-DD') : null,
      etd: values.etd ? values.etd.format('YYYY-MM-DD') : null,
      eta: values.eta ? values.eta.format('YYYY-MM-DD') : null,
      containerQty: values.containerQty ?? null,
      containerTypeCode: values.containerTypeCode ?? null,
      marksAndNos: values.marksAndNos ?? null,
      cargoDescription: values.cargoDescription ?? null,
      isOversized: values.isOversized ?? null,
      temperatureRange: values.temperatureRange ?? null,
      settlementCurrency: values.settlementCurrency ?? null,
      remark: values.remark ?? null,
      cargo: (values.cargo ?? []).map(toCargoInput),
      serviceItems: (values.serviceItems ?? []).map(toServiceItemInput),
    };
    setSubmitting(true);
    try {
      if (editing && orderId != null) {
        await orderService.update(orderId, payload);
        antMessage.success('委托单已保存');
        nav(`/orders/${orderId}`);
      } else {
        const created = await orderService.create(payload);
        antMessage.success('委托单已创建');
        nav(`/orders/${created.id}`);
      }
    } catch {
      // request.ts 已弹通知。
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return <Spin />;
  }

  return (
    <Card title={editing ? '编辑委托单' : '新建委托单'}>
      <Form<FormValues> form={form} layout="vertical">
        <Form.Item
          name="customerId"
          label="客户 ID"
          rules={[{ required: true, message: '请填写客户 ID' }]}
        >
          <InputNumber style={{ width: 220 }} min={1} />
        </Form.Item>
        <Form.Item name="businessType" label="业务类型" rules={[{ required: true }]}>
          <Select options={BUSINESS_TYPE_OPTIONS} />
        </Form.Item>
        <Form.Item name="tradeDirection" label="贸易方向" rules={[{ required: true }]}>
          <Select options={TRADE_DIRECTION_OPTIONS} />
        </Form.Item>
        <Form.Item name="incoterms" label="贸易条款" rules={[{ required: true }]}>
          <Select options={INCOTERM_OPTIONS} />
        </Form.Item>
        <Form.Item name="serviceType" label="服务类型" rules={[{ required: true }]}>
          <Select options={SERVICE_TYPE_OPTIONS} />
        </Form.Item>
        <Form.Item name="originAddress" label="起运地">
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item name="destAddress" label="目的地">
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item name="cargoReadyDate" label="货好日期">
          <DatePicker />
        </Form.Item>
        <Form.Item name="etd" label="ETD">
          <DatePicker />
        </Form.Item>
        <Form.Item name="eta" label="ETA">
          <DatePicker />
        </Form.Item>
        <Form.Item name="containerQty" label="箱量">
          <InputNumber min={0} />
        </Form.Item>
        <Form.Item name="containerTypeCode" label="箱型">
          <Input maxLength={16} />
        </Form.Item>
        <Form.Item name="marksAndNos" label="唛头">
          <Input maxLength={500} />
        </Form.Item>
        <Form.Item name="cargoDescription" label="货物描述">
          <TextArea rows={2} maxLength={500} />
        </Form.Item>
        <Form.Item name="isOversized" label="超限">
          <Select allowClear options={YES_NO_OPTIONS} />
        </Form.Item>
        <Form.Item name="temperatureRange" label="温区">
          <Input maxLength={32} />
        </Form.Item>
        <Form.Item name="settlementCurrency" label="结算币种">
          {/* CHAR(3) —— maxLength 必须跟着列宽走, 放行 4~8 个字符只会让用户白填一半再吃一个 400 */}
          <Input maxLength={3} placeholder="CNY" />
        </Form.Item>
        <Form.Item name="remark" label="备注">
          <TextArea rows={3} maxLength={1000} />
        </Form.Item>

        <Form.List name="cargo">
          {(fields, { add, remove }) => (
            <Card size="small" title="货物明细" style={{ marginBottom: 16 }}>
              {fields.map((field) => (
                <Space key={field.key} wrap align="start">
                  <Form.Item
                    key={field.key}
                    name={[field.name, 'cargoNameCn']}
                    rules={[{ required: true, message: '请填写品名' }]}
                  >
                    {/* VARCHAR(128) —— maxLength 跟着列宽走 */}
                    <Input placeholder="品名 (中)" style={{ width: 160 }} maxLength={128} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'cargoNameEn']}>
                    <Input placeholder="品名 (英)" style={{ width: 160 }} maxLength={128} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'hsCode']}>
                    {/* VARCHAR(16) */}
                    <Input placeholder="HS 编码" style={{ width: 130 }} maxLength={16} />
                  </Form.Item>
                  <Form.Item
                    key={field.key}
                    name={[field.name, 'pkgQty']}
                    // 件数 ≥ 0 (不是 > 0) —— 0 或不传表示「件数未知」(散货拼箱 LCL 常见),
                    // 与后端 @PositiveOrZero + DDL 的 NOT NULL DEFAULT 0 同口径
                  >
                    <InputNumber placeholder="件数" min={0} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'weightKg']}>
                    <InputNumber placeholder="重量 kg" min={0} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'volumeCbm']}>
                    <InputNumber placeholder="体积 m³" min={0} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'isStackable']}>
                    {/* 视图侧是 Integer (0/1), 请求侧是 Boolean —— 回填时已转成 boolean */}
                    <Select
                      allowClear
                      placeholder="可堆叠"
                      style={{ width: 100 }}
                      options={YES_NO_OPTIONS}
                    />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'isDangerous']}>
                    <Select
                      allowClear
                      placeholder="危险品"
                      style={{ width: 100 }}
                      options={YES_NO_OPTIONS}
                    />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'currencyCode']}>
                    <Input placeholder="币种" style={{ width: 80 }} maxLength={3} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'brandName']}>
                    <Input placeholder="品牌" style={{ width: 120 }} maxLength={128} />
                  </Form.Item>
                  <Button danger onClick={() => remove(field.name)}>
                    删除
                  </Button>
                </Space>
              ))}
              <Button onClick={() => add({})}>添加货物明细</Button>
            </Card>
          )}
        </Form.List>

        <Form.List name="serviceItems">
          {(fields, { add, remove }) => (
            <Card size="small" title="服务项 / 费用" style={{ marginBottom: 16 }}>
              {fields.map((field) => (
                <Space key={field.key} wrap align="start">
                  <Form.Item
                    key={field.key}
                    name={[field.name, 'feeName']}
                    rules={[{ required: true, message: '请填写费用名称' }]}
                  >
                    {/* VARCHAR(128) —— maxLength 跟着列宽走 */}
                    <Input placeholder="费用名称" style={{ width: 160 }} maxLength={128} />
                  </Form.Item>
                  <Form.Item
                    key={field.key}
                    name={[field.name, 'feeType']}
                    rules={[{ required: true, message: '请选择费用类型' }]}
                  >
                    {/* 自由 Input → Select: feeType 是 @NotBlank 且是受控词表,
                        手打一个词表外的值 = 提交后吃 400_106 */}
                    <Select
                      placeholder="费用类型"
                      style={{ width: 180 }}
                      options={FEE_TYPE_OPTIONS}
                    />
                  </Form.Item>
                  <Form.Item
                    key={field.key}
                    name={[field.name, 'calcBasis']}
                    rules={[{ required: true, message: '请选择计费基准' }]}
                  >
                    {/* calcBasis 是 @NotBlank (V42: t_order_service_item.calc_basis VARCHAR(16) NOT NULL)
                        —— 不给这个字段 = undefined = JSON 里直接省略 = 后端必 400 */}
                    <Select
                      placeholder="计费基准"
                      style={{ width: 160 }}
                      options={CALC_BASIS_OPTIONS}
                    />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'feeCategory']}>
                    <Select
                      placeholder="费用大类"
                      style={{ width: 150 }}
                      allowClear
                      options={FEE_CATEGORY_OPTIONS}
                    />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'qty']}>
                    <InputNumber placeholder="数量" min={0} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'unitPrice']}>
                    <InputNumber placeholder="单价" min={0} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'amount']}>
                    <InputNumber placeholder="金额" min={0} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'currencyCode']}>
                    {/* CHAR(3) */}
                    <Input placeholder="币种" style={{ width: 80 }} maxLength={3} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'costAmount']}>
                    <InputNumber placeholder="我方成本" min={0} />
                  </Form.Item>
                  <Form.Item key={field.key} name={[field.name, 'isMandatory']}>
                    <Select
                      allowClear
                      placeholder="必选服务"
                      style={{ width: 110 }}
                      options={YES_NO_OPTIONS}
                    />
                  </Form.Item>
                  <Button danger onClick={() => remove(field.name)}>
                    删除
                  </Button>
                </Space>
              ))}
              <Button onClick={() => add({})}>添加服务项</Button>
            </Card>
          )}
        </Form.List>

        <Space>
          <Button type="primary" loading={submitting} onClick={onSubmit}>
            保存
          </Button>
          <Button onClick={() => nav('/orders')}>取消</Button>
        </Space>
      </Form>
    </Card>
  );
}
