/**
 * 4.6 — 门户端委托下单页。
 *
 * 提交后单子落在 DRAFT —— 客户还需要在详情页点「确认」才会进 PENDING_CONFIRM
 * → CONFIRMED。**客户不能自己提交** —— submit 是管理端的动作 (order:submit)。
 */

import React, { useState } from 'react';
import {
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  message,
} from 'antd';
import type { Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { portalFreightOrderService } from '../../services/portalFreightOrderService';
import type { FreightOrderCreateRequest } from '../../types/freightOrder';
import { zhCN } from '../../locale/zh-CN';

const { TextArea } = Input;

/** 下拉选项 —— value 是后端受控词表里的 ASCII 码, label 是门户端展示的中文。 */
export interface FreightOrderOption {
  value: string;
  label: string;
}

/**
 * 业务类型 —— 取值与后端 `OrderConstants.BUSINESS_TYPES`
 * (lumen-parent/lumen-order/src/main/java/com/lumen/order/OrderConstants.java:108-110)
 * **逐个一致**, 一个不多一个不少。
 *
 * 计划里只列了前 5 个 (海运整箱 / 海运拼箱 / 空运 / 铁路 / 陆运), 少了
 * EXPRESS (快递) / MULTIMODAL (多式联运) / WAREHOUSE (仓储) —— 后端支持、
 * 下单页却选不到, 客户只能提工单让客服代下单。这里按词表全集实现。
 */
export const BUSINESS_TYPE_OPTIONS: FreightOrderOption[] = [
  { value: 'OCEAN_FCL', label: '海运整箱' },
  { value: 'OCEAN_LCL', label: '海运拼箱' },
  { value: 'AIR', label: '空运' },
  { value: 'RAIL', label: '铁路' },
  { value: 'TRUCK', label: '陆运' },
  { value: 'EXPRESS', label: '快递' },
  { value: 'MULTIMODAL', label: '多式联运' },
  { value: 'WAREHOUSE', label: '仓储' },
];

/**
 * 贸易方向 —— 取值与后端 `OrderConstants.TRADE_DIRECTIONS`
 * (OrderConstants.java:117-118) 逐个一致。
 *
 * 计划里只列了 IMPORT / EXPORT, 少了 TRANSIT (转运) 与
 * TRANSIT_OVERLAND (过境陆运) —— 同样是后端支持、门户选不到。
 */
export const TRADE_DIRECTION_OPTIONS: FreightOrderOption[] = [
  { value: 'EXPORT', label: '出口' },
  { value: 'IMPORT', label: '进口' },
  { value: 'TRANSIT', label: '转运' },
  { value: 'TRANSIT_OVERLAND', label: '过境陆运' },
];

/**
 * 贸易条款 —— 取值与后端 `OrderConstants.INCOTERMS`
 * (OrderConstants.java:121-122) 逐个一致 (10 个)。
 *
 * 计划里只列了 6 个, 少了 CFR / CPT / CIP / DPU。
 * Incoterms 的 label 就是码本身 —— 这是国际通用的写法, 译成中文反而看不懂。
 */
export const INCOTERMS_OPTIONS: FreightOrderOption[] = [
  { value: 'EXW', label: 'EXW' },
  { value: 'FCA', label: 'FCA' },
  { value: 'FOB', label: 'FOB' },
  { value: 'CFR', label: 'CFR' },
  { value: 'CIF', label: 'CIF' },
  { value: 'CPT', label: 'CPT' },
  { value: 'CIP', label: 'CIP' },
  { value: 'DAP', label: 'DAP' },
  { value: 'DPU', label: 'DPU' },
  { value: 'DDP', label: 'DDP' },
];

/**
 * 服务类型 —— 取值与后端 `OrderConstants.SERVICE_TYPES`
 * (OrderConstants.java:129) 一致 (4 个, 计划里已经是全集)。
 *
 * DDP 是贸易条款 (Incoterm), 不在 SERVICE_TYPES 里 —— 放在这里后端必然
 * 400_106 (受控词表校验失败)。
 */
export const SERVICE_TYPE_OPTIONS: FreightOrderOption[] = [
  { value: 'D2D', label: '门到门' },
  { value: 'P2P', label: '港到港' },
  { value: 'D2P', label: '门到港' },
  { value: 'P2D', label: '港到门' },
];

/** 费用类型 —— 取值与后端 `OrderConstants.FEE_TYPES` (OrderConstants.java:140-142) 一致。 */
export const FEE_TYPE_OPTIONS: FreightOrderOption[] = [
  { value: 'OCEAN_FREIGHT', label: '海运费' },
  { value: 'AIR_FREIGHT', label: '空运费' },
  { value: 'BAF', label: '燃油附加费' },
  { value: 'THC', label: '港口操作费' },
  { value: 'DOC_FEE', label: '单证费' },
  { value: 'CUSTOMS', label: '清关费' },
  { value: 'DELIVERY', label: '派送费' },
  { value: 'OTHER', label: '其他' },
];

/** 计费基准 —— 取值与后端 `OrderConstants.CALC_BASES` (OrderConstants.java:132-133) 一致。 */
export const CALC_BASIS_OPTIONS: FreightOrderOption[] = [
  { value: 'PER_SHIPMENT', label: '按票' },
  { value: 'PER_CONTAINER', label: '按箱' },
  { value: 'PER_BOX', label: '按件' },
  { value: 'PER_KG', label: '按公斤' },
  { value: 'PER_CBM', label: '按方' },
  { value: 'PER_CARGO', label: '按货物' },
];

/** 费用大类 —— 取值与后端 `OrderConstants.FEE_CATEGORIES` (OrderConstants.java:136-137) 一致。 */
export const FEE_CATEGORY_OPTIONS: FreightOrderOption[] = [
  { value: 'FREIGHT', label: '运费' },
  { value: 'SURCHARGE', label: '附加费' },
  { value: 'CUSTOMS', label: '清关' },
  { value: 'DELIVERY', label: '派送' },
  { value: 'WAREHOUSE', label: '仓储' },
  { value: 'OTHER', label: '其他' },
];

interface FormValues {
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
  settlementCurrency?: string;
  remark?: string;
  cargo?: Array<Record<string, unknown>>;
  serviceItems?: Array<Record<string, unknown>>;
}

const FreightOrderCreate: React.FC = () => {
  const navigate = useNavigate();
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async () => {
    let values: FormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const payload: FreightOrderCreateRequest = {
      businessType: values.businessType,
      tradeDirection: values.tradeDirection,
      incoterms: values.incoterms,
      serviceType: values.serviceType,
      originAddress: values.originAddress ?? null,
      destAddress: values.destAddress ?? null,
      cargoReadyDate: values.cargoReadyDate
        ? values.cargoReadyDate.format('YYYY-MM-DD')
        : null,
      etd: values.etd ? values.etd.format('YYYY-MM-DD') : null,
      eta: values.eta ? values.eta.format('YYYY-MM-DD') : null,
      containerQty: values.containerQty ?? null,
      containerTypeCode: values.containerTypeCode ?? null,
      marksAndNos: values.marksAndNos ?? null,
      cargoDescription: values.cargoDescription ?? null,
      settlementCurrency: values.settlementCurrency ?? null,
      remark: values.remark ?? null,
      cargo: (values.cargo ?? []).map((c) => {
        const { id: _id, ...rest } = c as Record<string, unknown> & { id?: number };
        void _id;
        return rest;
      }),
      serviceItems: (values.serviceItems ?? []).map((s) => {
        const { id: _id, ...rest } = s as Record<string, unknown> & { id?: number };
        void _id;
        return rest;
      }),
    };
    setSubmitting(true);
    try {
      const created = await portalFreightOrderService.create(payload);
      message.success('委托单已提交');
      navigate(`/freight-orders/${created.id}`);
    } catch {
      message.error('下单失败, 请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title={zhCN.freightOrder.createTitle}>
      <Form<FormValues> form={form} layout="vertical">
        <Form.Item
          name="businessType"
          label={zhCN.freightOrder.column.businessType}
          rules={[{ required: true }]}
        >
          <Select options={BUSINESS_TYPE_OPTIONS} />
        </Form.Item>
        <Form.Item
          name="tradeDirection"
          label={zhCN.freightOrder.column.tradeDirection}
          rules={[{ required: true }]}
        >
          <Select options={TRADE_DIRECTION_OPTIONS} />
        </Form.Item>
        <Form.Item
          name="incoterms"
          label={zhCN.freightOrder.column.incoterms}
          rules={[{ required: true }]}
        >
          <Select options={INCOTERMS_OPTIONS} />
        </Form.Item>
        <Form.Item
          name="serviceType"
          label={zhCN.freightOrder.column.serviceType}
          rules={[{ required: true }]}
        >
          <Select options={SERVICE_TYPE_OPTIONS} />
        </Form.Item>
        <Form.Item name="originAddress" label={zhCN.freightOrder.column.origin}>
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item name="destAddress" label={zhCN.freightOrder.column.destination}>
          <Input maxLength={255} />
        </Form.Item>
        <Form.Item name="cargoReadyDate" label={zhCN.freightOrder.column.cargoReadyDate}>
          <DatePicker />
        </Form.Item>
        <Form.Item name="etd" label={zhCN.freightOrder.column.etd}>
          <DatePicker />
        </Form.Item>
        <Form.Item name="eta" label={zhCN.freightOrder.column.eta}>
          <DatePicker />
        </Form.Item>
        <Form.Item name="containerQty" label={zhCN.freightOrder.column.containerQty}>
          <InputNumber min={0} />
        </Form.Item>
        <Form.Item name="containerTypeCode" label={zhCN.freightOrder.column.containerType}>
          <Input maxLength={16} />
        </Form.Item>
        <Form.Item name="marksAndNos" label={zhCN.freightOrder.column.marks}>
          <Input maxLength={500} />
        </Form.Item>
        <Form.Item name="cargoDescription" label={zhCN.freightOrder.column.description}>
          <TextArea rows={2} maxLength={500} />
        </Form.Item>
        <Form.Item name="settlementCurrency" label={zhCN.freightOrder.column.currency}>
          {/* CHAR(3) —— maxLength 跟着列宽走 */}
          <Input maxLength={3} placeholder="CNY" />
        </Form.Item>
        <Form.Item name="remark" label={zhCN.freightOrder.column.remark}>
          <TextArea rows={3} maxLength={1000} />
        </Form.Item>

        <Form.List name="cargo">
          {(fields, { add, remove }) => (
            <Card size="small" title={zhCN.freightOrder.cargoTitle} style={{ marginBottom: 16 }}>
              {fields.map(({ key, name, ...restField }) => (
                <Space key={key} wrap align="start">
                  <Form.Item
                    {...restField}
                    name={[name, 'cargoNameCn']}
                    rules={[{ required: true, message: '请填写品名' }]}
                  >
                    {/* VARCHAR(128) —— maxLength 跟着列宽走 */}
                    <Input placeholder="品名" style={{ width: 160 }} maxLength={128} />
                  </Form.Item>
                  <Form.Item
                    {...restField}
                    name={[name, 'pkgQty']}
                    // 件数 ≥ 0 (不是 > 0) —— 0 或不传表示「件数未知」(散货拼箱 LCL 常见),
                    // 与后端 @PositiveOrZero + DDL 的 NOT NULL DEFAULT 0 同口径
                  >
                    <InputNumber placeholder="件数" min={0} />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'weightKg']}>
                    <InputNumber placeholder="重量 kg" min={0} />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'volumeCbm']}>
                    <InputNumber placeholder="体积 m³" min={0} />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'hsCode']}>
                    {/* VARCHAR(16) */}
                    <Input placeholder="HS 编码" style={{ width: 140 }} maxLength={16} />
                  </Form.Item>
                  <Button danger onClick={() => remove(name)}>
                    删除
                  </Button>
                </Space>
              ))}
              <Button onClick={() => add({})}>{zhCN.freightOrder.addCargo}</Button>
            </Card>
          )}
        </Form.List>

        <Form.List name="serviceItems">
          {(fields, { add, remove }) => (
            <Card size="small" title={zhCN.freightOrder.serviceTitle} style={{ marginBottom: 16 }}>
              {fields.map(({ key, name, ...restField }) => (
                <Space key={key} wrap align="start">
                  <Form.Item
                    {...restField}
                    name={[name, 'feeName']}
                    label={zhCN.freightOrder.column.feeName}
                    rules={[{ required: true, message: '请填写费用名称' }]}
                  >
                    {/* VARCHAR(128) —— maxLength 跟着列宽走 */}
                    <Input placeholder="费用名称" style={{ width: 160 }} maxLength={128} />
                  </Form.Item>
                  <Form.Item
                    {...restField}
                    name={[name, 'feeType']}
                    label={zhCN.freightOrder.column.feeType}
                    rules={[{ required: true, message: '请选择费用类型' }]}
                  >
                    {/* feeType 是 @NotBlank + 受控词表 —— 自由输入必被后端 400_106 打回 */}
                    <Select
                      placeholder="费用类型"
                      style={{ width: 140 }}
                      options={FEE_TYPE_OPTIONS}
                    />
                  </Form.Item>
                  <Form.Item
                    {...restField}
                    name={[name, 'calcBasis']}
                    label={zhCN.freightOrder.column.calcBasis}
                    rules={[{ required: true, message: '请选择计费基准' }]}
                  >
                    {/* calcBasis 是 @NotBlank (V42: calc_basis VARCHAR(16) NOT NULL)
                        —— 不给这个字段 = undefined = JSON 里直接省略 = 后端必 400 */}
                    <Select
                      placeholder="计费基准"
                      style={{ width: 140 }}
                      options={CALC_BASIS_OPTIONS}
                    />
                  </Form.Item>
                  <Form.Item
                    {...restField}
                    name={[name, 'feeCategory']}
                    label={zhCN.freightOrder.column.feeCategory}
                  >
                    <Select
                      placeholder="费用大类"
                      style={{ width: 120 }}
                      allowClear
                      options={FEE_CATEGORY_OPTIONS}
                    />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'qty']}>
                    <InputNumber placeholder="数量" min={0} />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'unitPrice']}>
                    <InputNumber placeholder="单价" min={0} />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'amount']}>
                    <InputNumber placeholder="金额" min={0} />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'currencyCode']}>
                    {/* CHAR(3) */}
                    <Input placeholder="币种" style={{ width: 80 }} maxLength={3} />
                  </Form.Item>
                  <Button danger onClick={() => remove(name)}>
                    删除
                  </Button>
                </Space>
              ))}
              <Button onClick={() => add({})}>{zhCN.freightOrder.addServiceItem}</Button>
            </Card>
          )}
        </Form.List>

        <Space>
          <Button type="primary" loading={submitting} onClick={onSubmit}>
            {zhCN.common.submit}
          </Button>
          <Button onClick={() => navigate('/freight-orders')}>
            {zhCN.common.cancel}
          </Button>
        </Space>
      </Form>
    </Card>
  );
};

export default FreightOrderCreate;
