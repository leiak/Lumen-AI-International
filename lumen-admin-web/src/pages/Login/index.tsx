/**
 * Lumen Admin — Login page.
 *
 * Flow:
 *   1. User submits form with { tenantId?, username, password }
 *   2. Call useAuth().login(req) — this delegates the full login round-trip
 *      (POST /auth/login, token persistence, /auth/me hydration) to AuthProvider
 *   3. On success, navigate to `from` param (set by ProtectedRoute when it
 *      bounced an unauthenticated user here) or `/users` as default
 *   4. On failure, show an inline <Alert>; the request interceptor also shows
 *      a global notification.
 *
 * This page never reaches into the auth storage layer or the HTTP client
 * directly — auth state lives entirely in AuthProvider so every consumer
 * (useAuth, useAccess, ProtectedRoute) sees it consistently.
 *
 * DEV-ONLY defaults (tenantId=1, admin/admin123) are pre-filled so the
 * skeleton works out-of-the-box against the seeded user. They are gated
 * behind import.meta.env.DEV so they only appear under `npm run dev`; the
 * production build starts the form empty and operators must type their
 * own credentials. Do NOT remove this gate.
 */

import { Alert, Button, Card, Form, Input } from 'antd';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { useAuth } from '@/auth/useAuth';

interface LoginFormValues {
  tenantId?: string;
  username: string;
  password: string;
}

export default function Login() {
  const { login } = useAuth();
  const nav = useNavigate();
  const [searchParams] = useSearchParams();
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  // ProtectedRoute appends `?from=<original-path>` when bouncing an
  // unauthenticated user here. Honour it so the user lands back where
  // they were trying to go.
  const from = searchParams.get('from') || '/users';

  async function onFinish(values: LoginFormValues) {
    setSubmitting(true);
    setErrorMsg(null);
    try {
      // tenantId is optional in LoginRequest — coerce only when provided.
      const req: { username: string; password: string; tenantId?: number } = {
        username: values.username,
        password: values.password,
      };
      if (values.tenantId && values.tenantId.trim() !== '') {
        req.tenantId = Number(values.tenantId);
      }
      await login(req);
      nav(from, { replace: true });
    } catch (err) {
      // The request interceptor already shows a notification; also surface
      // the error inline so the user has context right next to the form.
      const message =
        err instanceof Error && err.message ? err.message : '登录失败，请稍后重试';
      setErrorMsg(message);
    } finally {
      setSubmitting(false);
    }
  }

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
        {errorMsg && (
          <Alert
            type="error"
            message={errorMsg}
            showIcon
            closable
            onClose={() => setErrorMsg(null)}
            style={{ marginBottom: 16 }}
          />
        )}
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
          disabled={submitting}
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
            <Button type="primary" htmlType="submit" block loading={submitting}>
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}