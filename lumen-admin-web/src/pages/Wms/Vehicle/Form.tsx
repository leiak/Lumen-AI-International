/**
 * Lumen Admin — Dispatch Vehicle Form (Iteration 4.1 PR3).
 *
 * 同一组件同时支持新建 (/dispatch/vehicles/new) 与编辑
 * (/dispatch/vehicles/:id/edit) — 通过 URL 中的 :id 区分。
 * 加载现有数据用 GET /api/v1/wms/vehicles/{id}; 提交按 URL 选择 POST 或 PUT。
 *
 * driver_id / carrier_id 外键用 ProFormSelect 列 driver / carrier 列表
 * 后端接口（driver / carrier 列表分页结果取 records[]）— 跟 WMS 仓库表单的
 * FK select 模式一致。
 */

import {
  ProFormDigit,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import { App as AntApp, Card, Form, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { request } from '@/services/request';

interface VehicleFormValues {
  plateNo?: string;
  type?: string;
  maxLoadKg?: number;
  maxVolumeM3?: number;
  status?: string;
  driverId?: number;
  carrierId?: number;
  remark?: string;
}

interface DriverListItem { id: number; name: string; }
interface CarrierListItem { id: number; name: string; }

const TYPE_OPTIONS = [
  { value: 'SMALL',  label: '小型' },
  { value: 'MEDIUM', label: '中型' },
  { value: 'LARGE',  label: '大型' },
];

const STATUS_CREATE_OPTIONS = [
  { value: 'IDLE', label: '空闲' },
];

// On update, per spec §4 VEHICLE 状态机:
//   非 IDLE/DISPATCHED/LOADING/ENROUTE/RETURNING → OUT 由状态机驱动
//   (无 PR3 暴露入口)，OUT → IDLE 由 unfreeze 端点处理。
//   PR3 表单上仅允许在 IDLE 和 OUT 之间手工切换 — 用作“强制释放卡住车辆”的兜底。
const STATUS_UPDATE_OPTIONS = [
  { value: 'IDLE', label: '空闲' },
  { value: 'OUT',  label: '停用' },
];

export default function DispatchVehicleForm() {
  const { id } = useParams<{ id?: string }>();
  const isEdit = !!id;
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const [form] = Form.useForm<VehicleFormValues>();
  const [loading, setLoading] = useState(isEdit);
  const [submitting, setSubmitting] = useState(false);
  const [drivers, setDrivers] = useState<{ value: number; label: string }[]>([]);
  const [carriers, setCarriers] = useState<{ value: number; label: string }[]>([]);

  // Pre-load driver / carrier lists once for the FK selects.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [d, c] = await Promise.all([
          request.get('/wms/drivers', { params: { current: 1, size: 1000 } }) as unknown as {
            data?: { records?: DriverListItem[] };
          } | null,
          request.get('/wms/carriers', { params: { current: 1, size: 1000 } }) as unknown as {
            data?: { records?: CarrierListItem[] };
          } | null,
        ]);
        if (cancelled) return;
        setDrivers((d?.data?.records ?? []).map((x) => ({ value: x.id, label: x.name })));
        setCarriers((c?.data?.records ?? []).map((x) => ({ value: x.id, label: x.name })));
      } catch (e) {
        // Global error interceptor surfaces the toast; selects stay empty.
        // eslint-disable-next-line no-console
        console.debug('dispatch vehicle form load error', e);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!isEdit) return;
    let cancelled = false;
    (async () => {
      try {
        const res = (await request.get(`/wms/vehicles/${id}`)) as unknown as {
          data: VehicleFormValues;
        } | null;
        if (!cancelled && res?.data) {
          form.setFieldsValue(res.data);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, isEdit, form]);

  const onFinish = async (values: VehicleFormValues) => {
    setSubmitting(true);
    try {
      if (isEdit) {
        await request.put(`/wms/vehicles/${id}`, values);
        antMessage.success('已更新');
      } else {
        await request.post('/wms/vehicles', values);
        antMessage.success('已创建');
      }
      nav('/dispatch/vehicles');
    } catch (e) {
      // Global error interceptor surfaces the toast; keep us on the page.
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title={isEdit ? `编辑车辆 #${id}` : '新建车辆'} bordered={false}>
      <Spin spinning={loading}>
        <Form<VehicleFormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          initialValues={{ status: 'IDLE', type: 'SMALL' }}
          disabled={submitting}
        >
          <ProFormText
            name="plateNo"
            label="车牌号"
            rules={[{ required: true, max: 32, message: '请输入车牌号' }]}
            width="md"
          />
          <ProFormSelect
            name="type"
            label="车型"
            options={TYPE_OPTIONS}
            width="md"
          />
          <ProFormDigit
            name="maxLoadKg"
            label="最大载重 (kg)"
            min={0.01}
            rules={[{ required: true, message: '请输入最大载重' }]}
            width="md"
          />
          <ProFormDigit
            name="maxVolumeM3"
            label="最大容积 (m³)"
            min={0.01}
            rules={[{ required: true, message: '请输入最大容积' }]}
            width="md"
          />
          {isEdit ? (
            <ProFormSelect
              name="status"
              label="状态"
              tooltip="仅允许在 IDLE 与 OUT 之间切换；其他状态由状态机或 freeze/unfreeze 端点驱动"
              options={STATUS_UPDATE_OPTIONS}
              width="md"
            />
          ) : (
            <ProFormSelect
              name="status"
              label="状态"
              disabled
              options={STATUS_CREATE_OPTIONS}
              width="md"
              extra="新建车辆固定为空闲，状态机后续流转"
            />
          )}
          <ProFormSelect
            name="driverId"
            label="默认司机"
            showSearch
            allowClear
            options={drivers}
            width="md"
          />
          <ProFormSelect
            name="carrierId"
            label="所属承运人"
            showSearch
            allowClear
            options={carriers}
            width="md"
          />
          <ProFormTextArea name="remark" label="备注" width="lg" />
        </Form>
      </Spin>
    </Card>
  );
}