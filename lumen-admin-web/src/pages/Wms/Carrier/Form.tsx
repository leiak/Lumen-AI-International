/**
 * Lumen Admin — Dispatch Carrier Form (Iteration 4.1 PR3).
 */

import {
  ProFormText,
  ProFormSelect,
} from '@ant-design/pro-components';
import { App as AntApp, Card, Form, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { request } from '@/services/request';

interface CarrierFormValues {
  code?: string;
  name?: string;
  contact?: string;
  status?: string;
}

const STATUS_OPTIONS = [
  { value: 'ACTIVE',    label: '启用' },
  { value: 'SUSPENDED', label: '停用' },
];

export default function DispatchCarrierForm() {
  const { id } = useParams<{ id?: string }>();
  const isEdit = !!id;
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const [form] = Form.useForm<CarrierFormValues>();
  const [loading, setLoading] = useState(isEdit);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!isEdit) return;
    let cancelled = false;
    (async () => {
      try {
        const res = (await request.get(`/wms/carriers/${id}`)) as unknown as {
          data: CarrierFormValues;
        } | null;
        if (!cancelled && res?.data) {
          form.setFieldsValue(res.data);
        }
      } catch (e) {
        // Global error interceptor surfaces the toast; keep loading off.
        // eslint-disable-next-line no-console
        console.debug('dispatch carrier form load error', e);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, isEdit, form]);

  const onFinish = async (values: CarrierFormValues) => {
    setSubmitting(true);
    try {
      if (isEdit) {
        await request.put(`/wms/carriers/${id}`, values);
        antMessage.success('已更新');
      } else {
        await request.post('/wms/carriers', values);
        antMessage.success('已创建');
      }
      nav('/dispatch/carriers');
    } catch (e) {
      // Global error interceptor surfaces the toast.
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title={isEdit ? `编辑承运人 #${id}` : '新建承运人'} bordered={false}>
      <Spin spinning={loading}>
        <Form<CarrierFormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          initialValues={{ status: 'ACTIVE' }}
          disabled={submitting}
        >
          <ProFormText
            name="code"
            label="编码"
            rules={[{ required: true, max: 32, message: '请输入承运人编码' }]}
            width="md"
          />
          <ProFormText
            name="name"
            label="名称"
            rules={[{ required: true, max: 128, message: '请输入承运人名称' }]}
            width="md"
          />
          <ProFormText name="contact" label="联系方式" width="md" />
          <ProFormSelect
            name="status"
            label="状态"
            options={STATUS_OPTIONS}
            width="md"
          />
        </Form>
      </Spin>
    </Card>
  );
}