/**
 * 4.5A.4-T4.4 — Profile page tests (3 cases).
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { message } from 'antd';
import Profile from '../Profile';
import { portalProfileService } from '../../services/portalProfileService';
import { useAuthStore } from '../../store/authStore';
import type { PortalProfile } from '../../types/portal';

vi.mock('../../services/portalProfileService');
const mockGet = vi.mocked(portalProfileService.get);
const mockUpdate = vi.mocked(portalProfileService.update);

function renderProfile() {
  return render(
    <MemoryRouter initialEntries={['/profile']}>
      <Profile />
    </MemoryRouter>
  );
}

const PROFILE: PortalProfile = {
  customerId: 42,
  note: '测试客户',
};

// "保存" rendered as "保 存" due to antd two-CJK-char spacing.
const SUBMIT = /保.存/;

describe('Profile page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.getState().logout();
    vi.spyOn(message, 'success').mockImplementation((() => ({})) as never);
    vi.spyOn(message, 'error').mockImplementation((() => ({})) as never);
  });

  it('loads profile from service and renders customerId + note', async () => {
    mockGet.mockResolvedValue(PROFILE);

    renderProfile();

    await waitFor(() => {
      expect(mockGet).toHaveBeenCalled();
    });

    // The customer-id descriptions item shows profile.customerId.
    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument();
      expect(screen.getByText('测试客户')).toBeInTheDocument();
    });
  });

  it('submitting the update form calls update with the typed values', async () => {
    mockGet.mockResolvedValue(PROFILE);
    mockUpdate.mockResolvedValue(undefined);

    renderProfile();

    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument();
    });

    // Fill phone + address; password is left blank (the page trims it out).
    await userEvent.type(screen.getByLabelText('联系电话'), '13800001234');
    await userEvent.type(screen.getByLabelText('联系地址'), 'Beijing CBD');

    await userEvent.click(screen.getByRole('button', { name: SUBMIT }));

    await waitFor(() => {
      expect(mockUpdate).toHaveBeenCalledWith({
        phone: '13800001234',
        address: 'Beijing CBD',
      });
    });
  });

  it('successful update shows the success message', async () => {
    mockGet.mockResolvedValue(PROFILE);
    mockUpdate.mockResolvedValue(undefined);

    renderProfile();

    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument();
    });

    await userEvent.type(screen.getByLabelText('联系电话'), '13900000000');
    await userEvent.click(screen.getByRole('button', { name: SUBMIT }));

    await waitFor(() => {
      expect(mockUpdate).toHaveBeenCalled();
    });

    await waitFor(() => {
      expect(message.success).toHaveBeenCalledWith('保存成功');
    });
  });
});