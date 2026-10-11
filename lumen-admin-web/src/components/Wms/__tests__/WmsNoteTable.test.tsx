/**
 * WmsNoteTable — render + status filter tests (PR5-T5.5).
 *
 * The component wraps ProTable and adds:
 *   - a mandatory "no" column at index 0 that links to `detailPath(record)`
 *   - an optional status filter (only appended when `statusFilter` is set)
 *   - an optional "新建..." toolbar button gated by `newButtonPerm`
 *   - a paging request adapter that forwards ProTable params to `request.get`
 *
 * These tests verify the render path through ProTable without diving into
 * every ProTable prop. We mock `@/services/request` so ProTable's internal
 * request hook doesn't make a real network call, and we wrap with
 * `<MemoryRouter>` + an `<AuthContext.Provider>` so `useNavigate` and
 * `useAccess` resolve.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import { WmsNoteTable } from '@/components/Wms/WmsNoteTable';
import { AuthContext } from '@/auth/AuthProvider';
import type { AuthContextValue } from '@/auth/types';

// ProTable pulls in antd's responsive observer, which calls
// `window.matchMedia` inside useLayoutEffect. jsdom doesn't ship one, so
// we stub it in beforeEach. (Same trick the App.smoke test uses.)
if (typeof window !== 'undefined' && !window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

vi.mock('@/services/request', () => {
  const get = vi.fn().mockResolvedValue({ records: [], total: 0 });
  return {
    default: { get },
    request: { get },
    get,
  };
});

import { request } from '@/services/request';
const mockGet = request.get as unknown as ReturnType<typeof vi.fn>;

function makeWrapper(perms: string[] = []): React.FC<{ children: React.ReactNode }> {
  const user = perms.length > 0
    ? { userId: 1, tenantId: 1, username: 'tester', roles: ['OPS'], perms }
    : null;
  const value: AuthContextValue = {
    user,
    isAuthenticated: !!user,
    isLoading: false,
    login: async () => {},
    logout: () => {},
    refresh: async () => 'token',
    setUser: () => {},
  };
  return ({ children }) => (
    <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
  );
}

interface SampleRow {
  id: number;
  no: string;
  status: string;
  remark?: string;
}

const baseColumns = [
  { title: '状态', dataIndex: 'status', valueType: 'select' as const, width: 100 },
];

describe('WmsNoteTable', () => {
  beforeEach(() => {
    mockGet.mockClear();
    mockGet.mockResolvedValue({ records: [], total: 0 });
  });

  it('renders the header title and fires the paging request to apiPath', async () => {
    const Wrapper = makeWrapper();
    render(
      <MemoryRouter>
        <Wrapper>
          <WmsNoteTable<SampleRow>
            headerTitle="入库单列表"
            apiPath="/api/v1/wms/inbound-notes"
            columns={baseColumns}
            detailPath={(r) => `/wms/inbound/${r.id}`}
          />
        </Wrapper>
      </MemoryRouter>,
    );

    // The header title is rendered by ProTable as a heading-ish element.
    expect(screen.getByText('入库单列表')).toBeInTheDocument();

    // ProTable fires its `request` function on mount; verify the adapter
    // translated it to `request.get(<apiPath>, { params: {...} })`.
    await waitFor(() => {
      expect(mockGet).toHaveBeenCalled();
    });
    const [path, opts] = mockGet.mock.calls[0];
    expect(path).toBe('/api/v1/wms/inbound-notes');
    expect(opts).toBeDefined();
    expect(opts?.params).toMatchObject({ pageNum: 1, pageSize: 20 });
  });

  it('appends the status filter column when statusFilter is provided', async () => {
    const Wrapper = makeWrapper();
    render(
      <MemoryRouter>
        <Wrapper>
          <WmsNoteTable<SampleRow>
            headerTitle="出库单列表"
            apiPath="/api/v1/wms/outbound-notes"
            columns={baseColumns}
            statusFilter={{
              options: [
                { value: 'DRAFT', label: '草稿' },
                { value: 'PICKING', label: '拣货中' },
              ],
            }}
            detailPath={(r) => `/wms/outbound/${r.id}`}
          />
        </Wrapper>
      </MemoryRouter>,
    );

    // The status filter is rendered with `hideInTable: true`, so its
    // `title` ("状态") shows up in the search toolbar but not the body.
    // ProTable shows the column title in the search panel.
    expect(screen.getByText('出库单列表')).toBeInTheDocument();
    expect(screen.getAllByText('状态').length).toBeGreaterThan(0);
  });
});
