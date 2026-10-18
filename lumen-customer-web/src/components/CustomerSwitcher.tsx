/**
 * 4.5A.4-T4.3 — CustomerSwitcher (header dropdown).
 *
 * Lets an authenticated user switch the active customer mid-session without
 * re-authenticating. Calls portalAuthService.selectCustomer → token is
 * re-issued with the new active_customer_id claim, then we update the auth
 * store and (if /me agrees) refresh the customer list.
 */

import React, { useEffect, useState } from 'react';
import { Select, message } from 'antd';
import { portalAuthService } from '../services/portalAuthService';
import { useAuthStore } from '../store/authStore';
import { useCustomerStore } from '../store/customerStore';
import type { PortalCustomerChoice } from '../types/portal';

const CustomerSwitcher: React.FC = () => {
  const activeCustomerId = useAuthStore((s) => s.activeCustomerId);
  const setActive = useAuthStore((s) => s.setActiveCustomer);
  const choices = useCustomerStore((s) => s.choices);
  const setChoices = useCustomerStore((s) => s.setChoices);
  const clearChoices = useCustomerStore((s) => s.clear);

  const [busy, setBusy] = useState(false);

  // Lazily populate the choices list via /me when the persisted state is empty
  // (e.g. after a page refresh where the customer slice wasn't re-hydrated).
  useEffect(() => {
    if (choices.length > 0) return;
    portalAuthService
      .me()
      .then((m) => setChoices(m.allChoices))
      .catch(() => clearChoices());
  }, [choices.length, setChoices, clearChoices]);

  if (choices.length <= 1) {
    // Single-customer users have nothing to switch — hide to keep the chrome clean.
    return null;
  }

  const options: PortalCustomerChoice[] = choices;

  const handleChange = async (id: number) => {
    setBusy(true);
    try {
      const r = await portalAuthService.selectCustomer(id);
      // Re-issue token + update active customer. The plan's T4.3 setLogin accepts
      // a small bundle (see authStore.setLogin).
      useAuthStore.getState().setLogin({
        token: r.token,
        refreshToken: '',
        userId: r.userId,
        username: r.username,
        realName: r.realName,
        activeCustomerId: r.activeCustomerId ?? id,
      });
      setActive(r.activeCustomerId ?? id);
      message.success('切换成功');
    } catch (e) {
      message.error('切换失败,请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Select<number>
      style={{ minWidth: 200 }}
      loading={busy}
      value={activeCustomerId ?? undefined}
      onChange={handleChange}
      options={options.map((c) => ({
        value: c.id,
        label: `${c.name} (${c.code})${c.isPrimary ? ' ★' : ''}`,
      }))}
      placeholder="选择客户"
    />
  );
};

export default CustomerSwitcher;
