/**
 * Lumen Admin — Login page.
 *
 * Form posts to /api/v1/auth/login (see AuthController#login). On success the
 * returned access/refresh tokens are persisted to localStorage and the user
 * is bounced to /. Backend message bubbles up via the request interceptor's
 * error notification on failure.
 *
 * DEV-ONLY defaults (tenantId=1, admin/admin123) are pre-filled so the
 * skeleton works out-of-the-box against the seeded user. They are gated
 * behind import.meta.env.DEV so they only appear under `npm run dev`; the
 * production build starts the form empty and operators must type their
 * own credentials. Do NOT remove this gate.
 */

import { Button, Card, Form, Input, App as AntApp } from 'antd';
import { useNavigate } from 'react-router-dom';
import { request } from '@/services/request';

interface TokenResponse {
  accessToken: string;
  refreshToken?: string;
  expiresIn?: number;
}

interface LoginFormValues {
  tenantId?: string;
  username: string;
  password: string;
}

const TOKEN_KEY = 'lumen_token';
const REFRESH_KEY = 'lumen_refresh';

export default function Login() {
  const nav = useNavigate();
  const { message } = AntApp.useApp();

  const onFinish = async (values: LoginFormValues) => {
    try {
      const payload: Record<string, unknown> = {
        username: values.username,
        password: values.password,
      };
      // tenantId is optional in LoginRequest — coerce only when provided.
      if (values.tenantId && values.tenantId.trim() !== '') {
        payload.tenantId = Number(values.tenantId);
      }
      // Response interceptor unwraps R<T>; cast through unknown because the
      // axios overload is typed as Promise<AxiosResponse<T>>.
      const res = (await request.post<unknown>('/auth/login', payload)) as unknown as TokenResponse | null;
      if (!res?.accessToken) {
        message.error('登录响应缺少 accessToken');
        return;
      }
      localStorage.setItem(TOKEN_KEY, res.accessToken);
      if (res.refreshToken) {
        localStorage.setItem(REFRESH_KEY, res.refreshToken);
      }
      message.success('登录成功');
      nav('/', { replace: true });
    } catch {
      // Notification already shown by request interceptor.
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        background: '#f0f2f5',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Card title="Lumen Admin 登录" style={{ width: 420 }}>
        <Form<LoginFormValues>
          layout="vertical"
          onFinish={onFinish}
          // DEV-ONLY defaults — gated by import.meta.env.DEV so production
          // builds start with an empty form. See header comment.
          initialValues={
            import.meta.env.DEV
              ? { tenantId: '1', username: 'admin', password: 'admin123' }
              : undefined
          }
          autoComplete="off"
        >
          <Form.Item
            name="tenantId"
            label="租户 ID"
            tooltip="可选 — JWT 自动绑定租户"
          >
            <Input placeholder="1" />
          </Form.Item>
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
