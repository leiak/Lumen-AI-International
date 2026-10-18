/**
 * 4.5A.4-T4.3 — PortalLayout.
 *
 * Sidebar (menu) + header (logo, customer switcher, user dropdown / logout) +
 * <Outlet/> for child routes. On mount we hit /me to verify the persisted token
 * is still valid; a 401 (or any failure) triggers logout + redirect.
 */

import React, { useEffect, useState } from 'react';
import {
  Layout,
  Menu,
  Button,
  Avatar,
  Dropdown,
  Space,
  Spin,
  Result,
} from 'antd';
import {
  UserOutlined,
  LogoutOutlined,
  ProfileOutlined,
  TruckOutlined,
} from '@ant-design/icons';
import {
  Outlet,
  useNavigate,
  useMatches,
  Link,
} from 'react-router-dom';
import { portalAuthService } from '../services/portalAuthService';
import { useAuthStore } from '../store/authStore';
import { useCustomerStore } from '../store/customerStore';
import CustomerSwitcher from '../components/CustomerSwitcher';
import { zhCN } from '../locale/zh-CN';
import './PortalLayout.css';

const { Header, Sider, Content } = Layout;

type MeState =
  | { kind: 'loading' }
  | { kind: 'ok' }
  | { kind: 'error'; reason: string };

const PortalLayout: React.FC = () => {
  const [me, setMe] = useState<MeState>({ kind: 'loading' });
  const logout = useAuthStore((s) => s.logout);
  const setLogin = useAuthStore((s) => s.setLogin);
  const username = useAuthStore((s) => s.username);
  const realName = useAuthStore((s) => s.realName);
  const activeCustomerId = useAuthStore((s) => s.activeCustomerId);
  const setChoices = useCustomerStore((s) => s.setChoices);
  const clearChoices = useCustomerStore((s) => s.clear);

  const navigate = useNavigate();
  const matches = useMatches();
  const selected = matches
    .map((m) => (m as { handle?: { key?: string } }).handle?.key)
    .filter(Boolean)[0] as string | undefined;

  useEffect(() => {
    let active = true;
    setMe({ kind: 'loading' });
    portalAuthService
      .me()
      .then((r) => {
        if (!active) return;
        // Refresh user info + choices from /me.
        setLogin({
          token: useAuthStore.getState().token ?? '',
          refreshToken: useAuthStore.getState().refreshToken ?? null,
          userId: r.userId,
          username: r.username,
          realName: r.realName,
          activeCustomerId: r.activeCustomerId,
        });
        setChoices(r.allChoices);
        setMe({ kind: 'ok' });
      })
      .catch((err) => {
        if (!active) return;
        logout();
        clearChoices();
        setMe({ kind: 'error', reason: err?.message ?? 'session_expired' });
        if (typeof window !== 'undefined') window.location.href = '/login';
      });
    return () => {
      active = false;
    };
    // run once on mount only; refresh happens on explicit user actions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (me.kind === 'error') {
    return (
      <Result
        status="403"
        title="会话失效"
        subTitle={me.reason}
        extra={
          <Button type="primary" onClick={() => navigate('/login')}>
            返回登录
          </Button>
        }
      />
    );
  }

  const handleLogout = () => {
    logout();
    clearChoices();
    navigate('/login', { replace: true });
  };

  const userMenu = {
    items: [
      {
        key: 'profile',
        icon: <ProfileOutlined />,
        label: <Link to="/profile">{zhCN.menu.profile}</Link>,
      },
      { type: 'divider' as const },
      {
        key: 'logout',
        icon: <LogoutOutlined />,
        label: zhCN.menu.logout,
        onClick: handleLogout,
      },
    ],
  };

  // While /me is in flight we keep the layout mounted but show a full-screen
  // spinner so we don't flash menu items behind an empty auth header.
  if (me.kind === 'loading') {
    return (
      <div className="portal-layout__loading">
        <Spin size="large" />
      </div>
    );
  }

  const displayName = realName || username || '—';

  return (
    <Layout className="portal-layout">
      <Sider width={220} theme="dark" className="portal-layout__sider">
        <div className="portal-layout__logo">Lumen 客户门户</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={selected ? [selected] : []}
          items={[
            {
              key: 'track',
              icon: <TruckOutlined />,
              label: <Link to="/track">{zhCN.menu.track}</Link>,
            },
            {
              key: 'profile',
              icon: <ProfileOutlined />,
              label: <Link to="/profile">{zhCN.menu.profile}</Link>,
            },
          ]}
        />
      </Sider>

      <Layout>
        <Header className="portal-layout__header">
          <Space size={16} style={{ width: '100%', justifyContent: 'space-between' }}>
            <CustomerSwitcher />
            <Dropdown menu={userMenu} placement="bottomRight">
              <Space style={{ cursor: 'pointer', color: '#fff' }}>
                <Avatar icon={<UserOutlined />} />
                <span>{displayName}</span>
              </Space>
            </Dropdown>
          </Space>
        </Header>

        <Content className="portal-layout__content">
          {activeCustomerId == null ? (
            <Result
              status="warning"
              title="请先选择客户"
              extra={
                <Button type="primary" onClick={() => navigate('/select-customer')}>
                  选择客户
                </Button>
              }
            />
          ) : (
            <Outlet />
          )}
        </Content>
      </Layout>
    </Layout>
  );
};

export default PortalLayout;
