/**
 * 4.5A.4-T4.4 — SelectCustomer page tests (2 cases).
 *
 * Pre-seeds both the customer store (so we skip the lazy /me fetch) and the
 * auth store (selectCustomer() reads userId from useAuthStore).
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import SelectCustomer from '../SelectCustomer';
import { portalAuthService } from '../../services/portalAuthService';
import { useAuthStore } from '../../store/authStore';
import { useCustomerStore } from '../../store/customerStore';
import type { PortalCustomerChoice } from '../../types/portal';

vi.mock('../../services/portalAuthService');
const mockSelect = vi.mocked(portalAuthService.selectCustomer);
const mockMe = vi.mocked(portalAuthService.me);

function LocationDisplay(): JSX.Element {
  const loc = useLocation();
  return <div data-testid="current-path">{loc.pathname}</div>;
}

function renderSelect(initialPath = '/select-customer') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <SelectCustomer />
      <LocationDisplay />
    </MemoryRouter>
  );
}

const CHOICES: PortalCustomerChoice[] = [
  { id: 1, code: 'C1', name: 'Customer One', isPrimary: true },
  { id: 2, code: 'C2', name: 'Customer Two', isPrimary: false },
];

// "选择" → "选 择" due to Antd Button's two-CJK-char spacing.
const CHOOSE = /选.择/;

describe('SelectCustomer page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.getState().logout();
    // Pre-populate minimal auth (selectCustomer reads userId).
    useAuthStore.getState().setLogin({
      token: 'old-token',
      refreshToken: '',
      userId: 99,
      username: 'multi',
      realName: 'Multi',
      activeCustomerId: null,
    });
    useCustomerStore.getState().setChoices(CHOICES);
    // /me should NOT be called when choices are pre-seeded.
    mockMe.mockResolvedValue('{{soon}}' as never);
  });

  it('clicking a customer card calls selectCustomer and navigates to /track', async () => {
    mockSelect.mockResolvedValue({
      token: 'new-token',
      activeCustomerId: 2,
      choices: null,
      userId: 99,
      username: 'multi',
      realName: 'Multi',
    });

    renderSelect();

    // Two cards → two choose buttons.
    const buttons = screen.getAllByRole('button', { name: CHOOSE });
    expect(buttons).toHaveLength(2);

    // Click the second card's button (id=2).
    await userEvent.click(buttons[1]);

    await waitFor(() => {
      expect(mockSelect).toHaveBeenCalledWith(2);
    });

    await waitFor(() => {
      expect(screen.getByTestId('current-path')).toHaveTextContent('/track');
    });

    const auth = useAuthStore.getState();
    expect(auth.token).toBe('new-token');
    expect(auth.activeCustomerId).toBe(2);
  });

  it('failed selectCustomer shows an error Result on the page', async () => {
    mockSelect.mockRejectedValue({
      message: 'server-down',
      response: { status: 500, data: { message: 'server-down' } },
    });

    renderSelect();

    const buttons = screen.getAllByRole('button', { name: CHOOSE });
    await userEvent.click(buttons[0]);

    // Wait for the Result subTitle to appear (server-down text).
    await waitFor(() => {
      expect(screen.getByText('server-down')).toBeInTheDocument();
    });

    // Should stay on /select-customer.
    expect(screen.getByTestId('current-path')).toHaveTextContent('/select-customer');

    // Auth store was not updated.
    expect(useAuthStore.getState().token).toBe('old-token');
  });
});