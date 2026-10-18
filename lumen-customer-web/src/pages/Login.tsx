/**
 * 4.5A.4-T4.3 — Login page.
 *
 * Two branches after a successful login:
 *  - single-customer user → token + activeCustomerId populated → /track
 *  - multi-customer user  → token == null, choices populated → /select-customer
 *
 * Per 4.5A backend, refresh-token is NOT returned from /auth/portal/login, so we
 * pass an empty string into setLogin.refreshToken to satisfy the store shape.
 */

import React, { useState } from 'react';
import { Form, Input, Button, Typography, message, Space } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { portalAuthService } from '../services/portalAuthService';
import { useAuthStore } from '../store/authStore';
import { useCustomerStore } from '../store/customerStore';
import { zhCN } from '../locale/zh-CN';
import type { PortalLoginRequest } from '../types/portal';
import './Login.css';

interface FormValues {
  username: string;
  password: string;
}

const Login: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm<FormValues>();
  const navigate = useNavigate();
  const location = useLocation() as { state?: { from?: string } };
  const setLogin = useAuthStore((s) => s.setLogin);
  const setChoices = useCustomerStore((s) => s.setChoices);

  const onFinish = async (values: FormValues) => {
    setLoading(true);
    const req: PortalLoginRequest = {
      username: values.username.trim(),
      password: values.password,
    };
    try {
      const r = await portalAuthService.login(req);
      // Always persist what we have, even if choices-only (token may be empty).
      setLogin({
        token: r.token ?? '',
        refreshToken: '', // 4.5A backend does not issue refresh at /login
        userId: r.userId,
        username: r.username,
        realName: r.realName,
        activeCustomerId: r.activeCustomerId ?? null,
      });
      if (r.choices && r.choices.length > 0) {
        setChoices(r.choices);
      }

      if (r.activeCustomerId != null) {
        // Single-customer user — go straight to wherever they came from, or /track.
        const dest = location.state?.from ?? '/track';
        navigate(dest, { replace: true });
      } else {
        // Multi-customer user — route them to the picker.
        navigate('/select-customer', { replace: true });
      }
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      const msg =
        e?.response?.data?.message ?? zhCN.login.invalidCredentials;
      message.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login">
      <Typography.Title level={3} style={{ marginBottom: 4 }}>
        {zhCN.login.title}
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 24 }}>
        {zhCN.login.subtitle}
      </Typography.Paragraph>

      <Form<FormValues>
        form={form}
        layout="vertical"
        onFinish={onFinish}
        initialValues={{ username: '', password: '' }}
        requiredMark={false}
      >
        <Form.Item
          name="username"
          label={zhCN.login.username}
          rules={[{ required: true, message: '请输入用户名' }]}
        >
          <Input
            prefix={<UserOutlined />}
            autoComplete="username"
            placeholder="username"
            size="large"
          />
        </Form.Item>

        <Form.Item
          name="password"
          label={zhCN.login.password}
          rules={[{ required: true, message: '请输入密码' }]}
        >
          <Input.Password
            prefix={<LockOutlined />}
            autoComplete="current-password"
            placeholder="password"
            size="large"
          />
        </Form.Item>

        <Space direction="vertical" style={{ width: '100%' }}>
          <Button
            type="primary"
            htmlType="submit"
            block
            size="large"
            loading={loading}
          >
            {zhCN.login.submit}
          </Button>
        </Space>
      </Form>
    </div>
  );
};

export default Login;
