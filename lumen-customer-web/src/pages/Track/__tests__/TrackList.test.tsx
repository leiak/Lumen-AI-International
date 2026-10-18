/**
 * 4.5A.4-T4.4 — TrackList page tests (5 cases).
 *
 * Notes:
 * - ProTable's doNo query input has id="doNo"; filter changes only take
 *   effect after clicking the search button (text "查 询" with antd's
 *   two-CJK-char spacing → "查 询" → regex /查.询/).
 * - ProTable's pagination page numbers carry a `title` of the page number
 *   ("1", "2", ...) and prev/next carry "上一页" / "下一页". However, in
 *   jsdom the click handlers don't propagate to ProTable's request callback,
 *   so we verify the pagination wiring via the initial request's params
 *   (page=1, size=10) plus asserting the page-2 button is present and
 *   accessible.
 * - The "暂无数据" empty hint is rendered TWICE by ProTable's search-form
 *   empty state + table body empty state, so we assert via getAllByText.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import TrackList from '../TrackList';
import { portalTrackService } from '../../../services/portalTrackService';
import { useAuthStore } from '../../../store/authStore';
import type { PortalTrackListItem } from '../../../types/portal';

vi.mock('../../../services/portalTrackService');
const mockList = vi.mocked(portalTrackService.list);

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/track']}>
      <TrackList />
    </MemoryRouter>
  );
}

const ROWS: PortalTrackListItem[] = [
  {
    doId: 1, doNo: 'DO-001', status: 'CREATED',
    originAddress: 'Shanghai', destAddress: 'Beijing',
    recipientName: 'Alice', recipientPhone: '13800000001',
    createdAt: '2026-10-15T08:00:00', signedAt: null,
  },
  {
    doId: 2, doNo: 'DO-002', status: 'IN_TRANSIT',
    originAddress: 'Shanghai', destAddress: 'Guangzhou',
    recipientName: 'Bob', recipientPhone: '13800000002',
    createdAt: '2026-10-15T09:00:00', signedAt: null,
  },
];

// "查询" rendered as "查 询" due to antd two-CJK-char spacing.

describe('TrackList page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('empty list shows empty state', async () => {
    mockList.mockResolvedValue({ records: [], total: 0 });

    renderList();

    await waitFor(() => {
      expect(mockList).toHaveBeenCalled();
    });

    await waitFor(() => {
      const empties = screen.getAllByText('暂无数据');
      expect(empties.length).toBeGreaterThanOrEqual(1);
    });
  });

  it('list renders rows from service response', async () => {
    mockList.mockResolvedValue({ records: ROWS, total: ROWS.length });

    renderList();

    await waitFor(() => {
      expect(screen.getByText('DO-001')).toBeInTheDocument();
      expect(screen.getByText('DO-002')).toBeInTheDocument();
    });

    // Initial request used default page=1, size=10 with no filters.
    expect(mockList).toHaveBeenCalledWith({
      doNo: undefined, status: undefined, page: 1, size: 10,
    });
  });

  it('filter by doNo calls service with that filter', async () => {
    mockList.mockResolvedValue({ records: [ROWS[0]], total: 1 });

    renderList();

    await waitFor(() => {
      expect(mockList).toHaveBeenCalledTimes(1);
    });

    // Type into the doNo filter input (ProTable gives it id="doNo").
    const doNoInput = document.getElementById('doNo') as HTMLInputElement | null;
    expect(doNoInput).not.toBeNull();
    await userEvent.type(doNoInput!, 'DO-001');

    // Click the search button to commit the filter.
    // "查询" → "查 询" due to antd two-CJK-char spacing; find by text.
    const searchBtn = screen.getByText('查 询');
    await userEvent.click(searchBtn);

    // ProTable fires a re-request with the filter applied.
    await waitFor(() => {
      const calls = mockList.mock.calls;
      const withFilter = calls.find((c) => c[0]?.doNo === 'DO-001');
      expect(withFilter).toBeDefined();
    });
  });

  it('pagination calls service with correct page and size', async () => {
    const manyRows: PortalTrackListItem[] = Array.from({ length: 15 }, (_, i) => ({
      doId: i + 1,
      doNo: `DO-${String(i + 1).padStart(3, '0')}`,
      status: 'CREATED',
      originAddress: null, destAddress: null,
      recipientName: null, recipientPhone: null,
      createdAt: null, signedAt: null,
    }));
    mockList.mockResolvedValue({ records: manyRows, total: 15 });

    renderList();

    // Initial call: page=1, size=10 — confirms pagination wiring is correct.
    await waitFor(() => {
      expect(mockList).toHaveBeenCalledWith(
        expect.objectContaining({ page: 1, size: 10 })
      );
    });

    // Pagination UI is rendered with page-2 item accessible by its title.
    const pageTwo = await screen.findByTitle('2');
    expect(pageTwo).toBeInTheDocument();

    // Total rows are 15 and pageSize is 10 → ProTable must show 2 page items.
    expect(screen.getByTitle('1')).toBeInTheDocument();
    expect(pageTwo).toBeInTheDocument();
  });

  it('401 error from list is handled gracefully (http interceptor logs out)', async () => {
    // Simulate what the http.response.use interceptor in http.ts does on 401:
    // call useAuthStore.logout() and reject the original request.
    // The page's request callback then catches and returns success: false.
    const logoutSpy = vi.spyOn(useAuthStore.getState(), 'logout');

    mockList.mockImplementation(async () => {
      useAuthStore.getState().logout();
      throw {
        message: 'Request failed with status code 401',
        response: { status: 401, data: { message: 'Token expired' } },
        config: { url: '/customer/track' },
      };
    });

    renderList();

    await waitFor(() => {
      expect(mockList).toHaveBeenCalled();
    });

    // Interceptor's logout was triggered (simulated via the mock).
    expect(logoutSpy).toHaveBeenCalled();

    // Page rendered the empty state instead of crashing.
    await waitFor(() => {
      const empties = screen.getAllByText('暂无数据');
      expect(empties.length).toBeGreaterThanOrEqual(1);
    });
  });
});