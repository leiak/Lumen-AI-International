/**
 * Lumen Admin — Dispatch Driver Form (Iteration 4.1 PR3).
 */

import {
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import { App as AntApp, Card, Form, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { request } from '@/services/request';

interface DriverFormValues {
  name?: string;
  phone?: string;
  licenseNo?: string;
  status?: string;
  remark?: string;
}

const STATUS_OPTIONS = [
  { value: 'ACTIVE',    label: '在职' },
  { value: 'SUSPENDED', label: '停用' },
];

export default function DispatchDriverForm() {
  const { id } = useParams<{ id?: string }>();
  const isEdit = !!id;
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const [form] = Form.useForm<DriverFormValues>();
  const [loading, setLoading] = useState(isEdit);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!isEdit) return;
    let cancelled = false;
    (async () => {
      try {
        const res = (await request.get(`/wms/drivers/${id}`)) as unknown as {
          data: DriverFormValues;
        } | null;
        if (!cancelled && res?.data) {
          form.setFieldsValue(res.data);
        }
      } catch (e) {
        // Global error interceptor surfaces the toast; keep loading off.
        // eslint-disable-next-line no-console
        console.debug('dispatch driver form load error', e);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, isEdit, form]);

  const onFinish = async (values: DriverFormValues) => {
    setSubmitting(true);
    try {
      if (isEdit) {
        await request.put(`/wms/drivers/${id}`, values);
        antMessage.success('已更新');
      } else {
        await request.post('/wms/drivers', values);
        antMessage.success('已创建');
      }
      nav('/dispatch/drivers');
    } catch (e) {
      // Global error interceptor surfaces the toast.
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title={isEdit ? `编辑司机 #${id}` : '新建司机'} bordered={false}>
      <Spin spinning={loading}>
        <Form<DriverFormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          initialValues={{ status: 'ACTIVE' }}
          disabled={submitting}
        >
          <ProFormText
            name="name"
            label="姓名"
            rules={[{ required: true, max: 64, message: '请输入姓名' }]}
            width="md"
          />
          <ProFormText
            name="phone"
            label="电话"
            rules={[{ required: true, max: 32, message: '请输入手机号' }]}
            width="md"
          />
          <ProFormText name="licenseNo" label="驾驶证号" width="md" />
          <ProFormSelect
            name="status"
            label="状态"
            options={STATUS_OPTIONS}
            width="md"
          />
          <ProFormTextArea name="remark" label="备注" width="lg" />
        </Form>
      </Spin>
    </Card>
  );
}