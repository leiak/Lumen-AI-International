/**
 * 4.5A.4-T4.3 — PortalLayout.
 *
 * Sidebar (menu) + header (logo, customer switcher, user dropdown / logout) +
 * <Outlet/> for child routes. On mount we hit /me to verify the persisted token
 * is still valid; a 401 (or any failure) triggers logout + redirect.
 *
 * 菜单选中态: 走 selectMenuKey(location.pathname), **不**走 react-router 的
 * useMatches()。原因: useMatches() 只在 data router (createBrowserRouter +
 * RouterProvider) 下可用, 本项目 main.tsx 挂的是普通 <BrowserRouter> —— 在那
 * 里面调 useMatches() 会直接抛 "useMatches must be used within a data router.",
 * 整个门户布局渲染不出来。这是 4.5A 起的既有缺陷, 顺带把选中态也一起打没了。
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
  FileTextOutlined,
  PlusSquareOutlined,
  WalletOutlined,
  AuditOutlined,
} from '@ant-design/icons';
import {
  Outlet,
  useNavigate,
  useLocation,
  Link,
} from 'react-router-dom';
import { portalAuthService } from '../services/portalAuthService';
import { useAuthStore } from '../store/authStore';
import { useCustomerStore } from '../store/customerStore';
import CustomerSwitcher from '../components/CustomerSwitcher';
import { zhCN } from '../locale/zh-CN';
import './PortalLayout.css';

const { Header, Sider, Content } = Layout;

/**
 * 侧边栏菜单项。
 *
 * 这张表同时是「菜单渲染」与「选中态判定」的唯一来源 —— path 拿来算当前高亮
 * 哪个 key, 不另建一张路由表, 免得两处漂移。
 *
 * 详情页落在所属大项的路径前缀下 (如 /reconciliation/1 归 reconciliation),
 * 所以判定是「最长前缀匹配」。
 */
const MENU: Array<{ key: string; path: string; icon: React.ReactNode; label: string }> = [
  { key: 'orders', path: '/orders', icon: <FileTextOutlined />, label: zhCN.menu.orders },
  { key: 'orders-new', path: '/orders/new', icon: <PlusSquareOutlined />, label: zhCN.menu.createOrder },
  { key: 'freight-orders', path: '/freight-orders', icon: <FileTextOutlined />, label: zhCN.menu.freightOrders },
  { key: 'freight-orders-new', path: '/freight-orders/new', icon: <PlusSquareOutlined />, label: zhCN.menu.createFreightOrder },
  { key: 'track', path: '/track', icon: <TruckOutlined />, label: zhCN.menu.track },
  { key: 'reconciliation', path: '/reconciliation', icon: <FileTextOutlined />, label: zhCN.menu.reconciliation },
  { key: 'invoices', path: '/invoices', icon: <FileTextOutlined />, label: zhCN.menu.invoice },
  { key: 'payments', path: '/payments', icon: <WalletOutlined />, label: zhCN.menu.payment },
  { key: 'adjustments', path: '/adjustments', icon: <AuditOutlined />, label: zhCN.menu.adjustment },
  { key: 'profile', path: '/profile', icon: <ProfileOutlined />, label: zhCN.menu.profile },
];

/**
 * pathname → 菜单选中 key。
 *
 * 取**最长**匹配前缀 —— '/orders/new' 必须赢过 '/orders', 否则进下单页时高亮的
 * 是「我的订单」而不是「下单」。
 */
function selectMenuKey(pathname: string): string | undefined {
  let bestKey: string | undefined;
  let bestLen = -1;
  for (const item of MENU) {
    const hit = pathname === item.path || pathname.startsWith(item.path + '/');
    if (hit && item.path.length > bestLen) {
      bestLen = item.path.length;
      bestKey = item.key;
    }
  }
  return bestKey;
}

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
  const location = useLocation();
  const selected = selectMenuKey(location.pathname);

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
          items={MENU.map((m) => ({
            key: m.key,
            icon: m.icon,
            label: <Link to={m.path}>{m.label}</Link>,
          }))}
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
