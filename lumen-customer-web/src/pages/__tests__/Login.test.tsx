/**
 * 4.5A.4-T4.4 — Login page tests (4 cases).
 *
 * Note: Antd Button auto-inserts a space between two CJK characters for
 * Chinese typography ("登录" → "登 录"). Form.Item labels render via a
 * different path and are NOT affected. We use regex matching for the
 * 2-char submit button text.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { message } from 'antd';
import Login from '../Login';
import { portalAuthService } from '../../services/portalAuthService';
import { useAuthStore } from '../../store/authStore';
import { useCustomerStore } from '../../store/customerStore';

vi.mock('../../services/portalAuthService');
const mockLogin = vi.mocked(portalAuthService.login);

function LocationDisplay(): JSX.Element {
  const loc = useLocation();
  return <div data-testid="current-path">{loc.pathname}</div>;
}

function renderLogin(initialPath = '/login') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Login />
      <LocationDisplay />
    </MemoryRouter>
  );
}

// Submit button has accessible name "登 录" due to antd's two-CJK-char spacing.
const SUBMIT = /登.录/;

describe('Login page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.getState().logout();
    useCustomerStore.getState().clear();
    // Suppress antd message rendering into jsdom; spy instead.
    vi.spyOn(message, 'error').mockImplementation((() => ({})) as never);
    vi.spyOn(message, 'success').mockImplementation((() => ({})) as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('successful login with single customer navigates to /track', async () => {
    mockLogin.mockResolvedValue({
      token: 'abc.def.ghi',
      activeCustomerId: 42,
      choices: null,
      userId: 7,
      username: 'alice',
      realName: 'Alice',
    });

    renderLogin();

    await userEvent.type(screen.getByLabelText('用户名'), 'alice');
    await userEvent.type(screen.getByLabelText('密码'), 'secret123');
    await userEvent.click(screen.getByRole('button', { name: SUBMIT }));

    await waitFor(() => {
      expect(mockLogin).toHaveBeenCalledWith({ username: 'alice', password: 'secret123' });
    });

    await waitFor(() => {
      expect(screen.getByTestId('current-path')).toHaveTextContent('/track');
    });

    const auth = useAuthStore.getState();
    expect(auth.token).toBe('abc.def.ghi');
    expect(auth.activeCustomerId).toBe(42);
    expect(auth.userId).toBe(7);
    expect(auth.username).toBe('alice');
  });

  it('failed login shows error message and stays on /login', async () => {
    mockLogin.mockRejectedValue({
      response: { status: 401, data: { message: '用户名或密码错误' } },
    });

    renderLogin();

    await userEvent.type(screen.getByLabelText('用户名'), 'bad');
    await userEvent.type(screen.getByLabelText('密码'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: SUBMIT }));

    await waitFor(() => {
      expect(mockLogin).toHaveBeenCalled();
    });

    await waitFor(() => {
      expect(message.error).toHaveBeenCalledWith('用户名或密码错误');
    });

    expect(screen.getByTestId('current-path')).toHaveTextContent('/login');
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('multi-customer login navigates to /select-customer and seeds customerStore', async () => {
    mockLogin.mockResolvedValue({
      token: '',
      activeCustomerId: null,
      choices: [
        { id: 1, code: 'C1', name: 'Customer One', isPrimary: true },
        { id: 2, code: 'C2', name: 'Customer Two', isPrimary: false },
      ],
      userId: 9,
      username: 'multi',
      realName: 'Multi User',
    });

    renderLogin();

    await userEvent.type(screen.getByLabelText('用户名'), 'multi');
    await userEvent.type(screen.getByLabelText('密码'), 'pw');
    await userEvent.click(screen.getByRole('button', { name: SUBMIT }));

    await waitFor(() => {
      expect(screen.getByTestId('current-path')).toHaveTextContent('/select-customer');
    });

    const choices = useCustomerStore.getState().choices;
    expect(choices).toHaveLength(2);
    expect(choices[0].code).toBe('C1');
  });

  it('submit button shows loading state while request is in flight', async () => {
    let resolveLogin!: (v: Awaited<ReturnType<typeof portalAuthService.login>>) => void;
    mockLogin.mockImplementation(
      () => new Promise<Awaited<ReturnType<typeof portalAuthService.login>>>((resolve) => {
        resolveLogin = resolve;
      })
    );

    renderLogin();

    await userEvent.type(screen.getByLabelText('用户名'), 'slow');
    await userEvent.type(screen.getByLabelText('密码'), 'slow');

    const submit = screen.getByRole('button', { name: SUBMIT });
    expect(submit).not.toHaveClass('ant-btn-loading');

    await userEvent.click(submit);

    await waitFor(() => {
      expect(submit).toHaveClass('ant-btn-loading');
    });

    resolveLogin({
      token: 't',
      activeCustomerId: 1,
      choices: null,
      userId: 1,
      username: 's',
      realName: 'S',
    });

    await waitFor(() => {
      expect(submit).not.toHaveClass('ant-btn-loading');
    });
  });
});