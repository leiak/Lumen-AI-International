/**
 * 4.5A.4-T4.3 — SelectCustomer page.
 *
 * Rendered when a multi-customer user logs in. After picking one, we re-issue
 * a token via /api/auth/portal/select-customer and persist it via setLogin.
 */

import React, { useEffect, useState } from 'react';
import { Card, Button, Tag, Space, Typography, Empty, Result } from 'antd';
import { CheckCircleOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { portalAuthService } from '../services/portalAuthService';
import { useAuthStore } from '../store/authStore';
import { useCustomerStore } from '../store/customerStore';
import { zhCN } from '../locale/zh-CN';
import type { PortalCustomerChoice } from '../types/portal';

const SelectCustomer: React.FC = () => {
  const navigate = useNavigate();
  const choices = useCustomerStore((s) => s.choices);
  const setChoices = useCustomerStore((s) => s.setChoices);

  const [loadingId, setLoadingId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 4.5A login response embeds choices in the response envelope. This effect
  // is a safety net for the (rare) case of a direct deep-link to this page
  // mid-session where the customer store hasn't been hydrated yet.
  useEffect(() => {
    if (choices.length > 0) return;
    portalAuthService
      .me()
      .then((m) => setChoices(m.allChoices))
      .catch((e) => setError(e?.message ?? '加载客户列表失败'));
  }, [choices.length, setChoices]);

  const choose = async (choice: PortalCustomerChoice) => {
    setLoadingId(choice.id);
    setError(null);
    try {
      const r = await portalAuthService.selectCustomer(choice.id);
      useAuthStore.getState().setLogin({
        token: r.token,
        refreshToken: '',
        userId: r.userId,
        username: r.username,
        realName: r.realName,
        activeCustomerId: r.activeCustomerId ?? choice.id,
      });
      navigate('/track', { replace: true });
    } catch (e: unknown) {
      const err = e as { message?: string };
      setError(err.message ?? '选择失败,请重试');
    } finally {
      setLoadingId(null);
    }
  };

  return (
    <div className="select-customer">
      <Typography.Title level={3} style={{ marginBottom: 4 }}>
        {zhCN.selectCustomer.title}
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 24 }}>
        {zhCN.selectCustomer.subtitle}
      </Typography.Paragraph>

      {error && (
        <Result
          status="warning"
          title="提示"
          subTitle={error}
          style={{ padding: '12px 0' }}
        />
      )}

      {choices.length === 0 && !error ? (
        <Empty description="正在加载客户列表..." />
      ) : (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {choices.map((c) => (
            <Card
              key={c.id}
              hoverable
              bodyStyle={{ display: 'flex', alignItems: 'center', gap: 12 }}
            >
              <CheckCircleOutlined style={{ fontSize: 20, color: '#1677ff' }} />
              <div style={{ flex: 1 }}>
                <Space size={8}>
                  <Typography.Text strong>{c.name}</Typography.Text>
                  <Typography.Text type="secondary">{c.code}</Typography.Text>
                  {c.isPrimary && (
                    <Tag color="gold">{zhCN.selectCustomer.primaryBadge}</Tag>
                  )}
                </Space>
              </div>
              <Button
                type="primary"
                loading={loadingId === c.id}
                onClick={() => choose(c)}
              >
                {zhCN.selectCustomer.choose}
              </Button>
            </Card>
          ))}
        </Space>
      )}
    </div>
  );
};

export default SelectCustomer;
