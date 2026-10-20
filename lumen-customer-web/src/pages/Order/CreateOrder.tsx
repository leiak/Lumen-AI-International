/**
 * 4.5B.3-T3.2 — CreateOrderPage (客户下单).
 *
 * 3 步向导:
 *   Step 1 (基本): warehouseId + shipperAddress + consigneeAddress
 *   Step 2 (货物 + 取件时间): weight / volume / stopCount / pickup window / remark
 *   Step 3 (确认): 调 preview 拿估价, 显示; 提交下单成功后跳详情
 */

import React, { useState } from 'react';
import {
  Button,
  Card,
  DatePicker,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Spin,
  Steps,
  message,
} from 'antd';
import dayjs, { Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { portalOrderService } from '../../services/portalOrderService';
import type {
  PortalCreateOrderRequest,
  PortalEstimateResult,
} from '../../types/order';

const { RangePicker } = DatePicker;
const { TextArea } = Input;

interface FormValues {
  warehouseId: number;
  shipperAddress: string;
  consigneeAddress: string;
  totalWeightKg: number;
  totalVolumeM3: number;
  stopCount: number;
  pickupRange: [Dayjs, Dayjs];
  remark?: string;
}

const CreateOrder: React.FC = () => {
  const navigate = useNavigate();
  const [form] = Form.useForm<FormValues>();
  const [current, setCurrent] = useState(0);
  const [estimate, setEstimate] = useState<PortalEstimateResult | null>(null);
  const [request, setRequest] = useState<PortalCreateOrderRequest | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // 取件时间窗限制: 同日 + start >= now+2h + start <= now+30d + end - start <= 8h + 8-20 时段
  const disabledDate = (d: Dayjs | null) => {
    if (!d) return false;
    const now = dayjs();
    return d.isBefore(now.add(2, 'hour'), 'day') || d.isAfter(now.add(30, 'day'), 'day');
  };

  const disabledTime = (_d: Dayjs | null, type: 'start' | 'end') => {
    const allHours = Array.from({ length: 24 }, (_, i) => i);
    const allowed = allHours.filter((h) => h >= 8 && h <= 20);
    const disabled = allHours.filter((h) => !allowed.includes(h));
    void type;
    return { disabledHours: () => disabled };
  };

  // step 1 → step 2
  const onNext1 = async () => {
    try {
      await form.validateFields(['warehouseId', 'shipperAddress', 'consigneeAddress']);
    } catch {
      return; // 校验失败, antd 已就地提示
    }
    setCurrent(1);
  };

  // step 2 → step 3 (触发 preview)
  const onNext2 = async () => {
    try {
      await form.validateFields([
        'totalWeightKg', 'totalVolumeM3', 'stopCount', 'pickupRange',
      ]);
    } catch {
      return;
    }
    setPreviewing(true);
    try {
      const req = buildRequest(form.getFieldsValue() as FormValues);
      const r = await portalOrderService.preview(req);
      setEstimate(r);
      setRequest(req);
      setCurrent(2);
    } catch (e: any) {
      message.error(e?.response?.data?.message || e?.message || '估价失败');
    } finally {
      setPreviewing(false);
    }
  };

  // step 3 → 提交 (复用 preview 时已构建的请求体)
  const onSubmit = async () => {
    if (!request) return;
    setSubmitting(true);
    try {
      const r = await portalOrderService.create(request);
      message.success('下单成功');
      navigate(`/orders/${r.doId}`);
    } catch (e: any) {
      message.error(e?.response?.data?.message || e?.message || '下单失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <Steps
        current={current}
        items={[{ title: '基本信息' }, { title: '货物 & 取件' }, { title: '确认下单' }]}
        style={{ marginBottom: 24 }}
      />
      <Form form={form} layout="vertical" preserve>
        {current === 0 && (
          <Card>
            <Form.Item name="warehouseId" label="仓库 ID" rules={[{ required: true, type: 'number', min: 1 }]}>
              <InputNumber style={{ width: '100%' }} placeholder="例如: 1" />
            </Form.Item>
            <Form.Item name="shipperAddress" label="起运地址" rules={[{ required: true, max: 255 }]}>
              <Input placeholder="详细起运地址" />
            </Form.Item>
            <Form.Item name="consigneeAddress" label="收货地址" rules={[{ required: true, max: 255 }]}>
              <Input placeholder="详细收货地址" />
            </Form.Item>
            <Button type="primary" onClick={onNext1}>下一步</Button>
          </Card>
        )}

        {current === 1 && (
          <Card>
            <Form.Item name="totalWeightKg" label="重量 (kg)" rules={[{ required: true, type: 'number', min: 0.01 }]}>
              <InputNumber style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="totalVolumeM3" label="体积 (m³)" rules={[{ required: true, type: 'number', min: 0.01 }]}>
              <InputNumber style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="stopCount" label="件数" rules={[{ required: true, type: 'integer', min: 1 }]}>
              <InputNumber style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="pickupRange" label="取件时间窗 (同日 08-20)" rules={[{ required: true }]}>
              <RangePicker
                showTime={{ format: 'HH:mm', minuteStep: 15 }}
                format="YYYY-MM-DD HH:mm"
                disabledDate={disabledDate}
                disabledTime={disabledTime as any}
                style={{ width: '100%' }}
              />
            </Form.Item>
            <Form.Item name="remark" label="备注" rules={[{ max: 500 }]}>
              <TextArea maxLength={500} rows={3} />
            </Form.Item>
            <Button onClick={() => setCurrent(0)}>上一步</Button>
            <Button type="primary" loading={previewing} onClick={onNext2} style={{ marginLeft: 8 }}>
              下一步
            </Button>
          </Card>
        )}

        {current === 2 && (
          <Card>
            {previewing && <Spin />}
            {estimate && (
              <Descriptions title="预估" bordered column={1} style={{ marginBottom: 16 }}>
                <Descriptions.Item label="金额">
                  {estimate.amount != null ? `¥${estimate.amount.toFixed(2)}` : '—'}
                </Descriptions.Item>
                <Descriptions.Item label="来源">{estimate.source}</Descriptions.Item>
                <Descriptions.Item label="说明">{estimate.breakdown}</Descriptions.Item>
              </Descriptions>
            )}
            <Button onClick={() => setCurrent(1)}>上一步</Button>
            <Button type="primary" loading={submitting} onClick={onSubmit} style={{ marginLeft: 8 }}>
              提交下单
            </Button>
          </Card>
        )}
      </Form>
    </div>
  );
};

function buildRequest(v: FormValues): PortalCreateOrderRequest {
  const [start, end] = v.pickupRange;
  return {
    warehouseId: v.warehouseId,
    shipperAddress: v.shipperAddress,
    consigneeAddress: v.consigneeAddress,
    totalWeightKg: v.totalWeightKg,
    totalVolumeM3: v.totalVolumeM3,
    stopCount: v.stopCount,
    plannedPickupWindowStart: start.toISOString(),
    plannedPickupWindowEnd: end.toISOString(),
    remark: v.remark ?? null,
  };
}

export default CreateOrder;