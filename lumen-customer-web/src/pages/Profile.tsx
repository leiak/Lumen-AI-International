/**
 * 4.5A.4-T4.3 — Profile page.
 *
 * 4.5A is a stub: GET returns { customerId, note }; PUT accepts an arbitrary
 * payload { phone?, address?, password? } (audit-log only). 4.5B replaces with
 * a real profile form keyed off the full DTO.
 */

import React, { useEffect, useState } from 'react';
import {
  Card,
  Descriptions,
  Form,
  Input,
  Button,
  Space,
  Skeleton,
  message,
  Typography,
} from 'antd';
import { portalProfileService } from '../services/portalProfileService';
import type { PortalProfile, PortalProfileUpdate } from '../types/portal';
import { useAuthStore } from '../store/authStore';
import { zhCN } from '../locale/zh-CN';

interface FormValues {
  phone?: string;
  address?: string;
  password?: string;
}

const Profile: React.FC = () => {
  const [profile, setProfile] = useState<PortalProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<FormValues>();
  const activeCustomerId = useAuthStore((s) => s.activeCustomerId);

  useEffect(() => {
    let active = true;
    setLoading(true);
    portalProfileService
      .get()
      .then((p) => {
        if (!active) return;
        setProfile(p);
      })
      .catch((e: { message?: string }) => {
        if (active) message.error(e.message ?? '加载资料失败');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const onFinish = async (values: FormValues) => {
    setSaving(true);
    const body: PortalProfileUpdate = {};
    if (values.phone?.trim()) body.phone = values.phone.trim();
    if (values.address?.trim()) body.address = values.address.trim();
    if (values.password?.trim()) body.password = values.password.trim();
    try {
      await portalProfileService.update(body);
      message.success(zhCN.profile.success);
      form.resetFields(['password']);
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error(err.message ?? zhCN.profile.fail);
    } finally {
      setSaving(false);
    }
  };

  const t = zhCN.profile;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }}>
        {t.title}
      </Typography.Title>

      <Card title={t.current}>
        {loading || !profile ? (
          <Skeleton active paragraph={{ rows: 4 }} />
        ) : (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label={t.customerId}>
              {activeCustomerId ?? profile.customerId}
            </Descriptions.Item>
            <Descriptions.Item label={t.note}>
              {profile.note || '-'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      <Card title={t.update}>
        <Form<FormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          requiredMark={false}
        >
          <Form.Item name="phone" label={t.phone}>
            <Input placeholder="13xxxxxxxxx" maxLength={20} />
          </Form.Item>
          <Form.Item name="address" label={t.address}>
            <Input.TextArea rows={3} maxLength={200} showCount />
          </Form.Item>
          <Form.Item name="password" label={t.password}>
            <Input.Password placeholder={t.passwordPlaceholder} />
          </Form.Item>
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              loading={saving}
            >
              {zhCN.common.save}
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </Space>
  );
};

export default Profile;
