/**
 * cs exceptionView service smoke + status computation (Iteration 4.2 PR6).
 *
 * Locks the HTTP contract for `csExceptionViewService.list` (path /
 * verb / param normalization), and the pure `computeEscalationStatus`
 * thresholds (the FE has to agree with the colors used by
 * SlaCountdownTag).
 */

import { describe, it, expect, vi } from 'vitest';

vi.mock('@/services/request', () => {
  const get = vi.fn();
  const post = vi.fn();
  const put = vi.fn();
  const del = vi.fn();
  const request = { get, post, put, delete: del };
  return {
    request,
    default: request,
    PageResult: class {},
    ApiEnvelope: class {},
    ApiError: class extends Error {},
  };
});

import { request } from '@/services/request';
import {
  csExceptionViewService,
  computeEscalationStatus,
  listCsExceptions,
} from '@/services/cs/exceptionView';

describe('csExceptionViewService', () => {
  it('list() GETs /cs/exceptions with pageNum/size converted to current/size', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({
      code: 0,
      data: { records: [], total: 0, pageNum: 1, pageSize: 20 },
    });

    await csExceptionViewService.list({
      pageNum: 2,
      pageSize: 50,
      status: 'OPEN',
      severity: 'HIGH',
    });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/cs/exceptions');
    expect(opts?.params).toEqual({
      current: 2,
      size: 50,
      status: 'OPEN',
      severity: 'HIGH',
    });
  });

  it('list() prefers canonical current/size when both are provided', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({
      code: 0,
      data: { records: [], total: 0, pageNum: 1, pageSize: 20 },
    });

    await csExceptionViewService.list({
      current: 3,
      pageNum: 1,
      size: 25,
      pageSize: 10,
    });

    // This is the SECOND call overall (the first test in this describe
    // block already fired one), so index 1.
    expect(vi.mocked(request.get).mock.calls[1][1]?.params).toEqual({
      current: 3,
      size: 25,
      status: undefined,
      severity: undefined,
    });
  });

  it('listCsExceptions re-exports list() under the plan-mandated name', async () => {
    expect(listCsExceptions).toBe(csExceptionViewService.list);
  });
});

describe('computeEscalationStatus', () => {
  it('returns OK when under 80% of target', () => {
    expect(computeEscalationStatus(50, 100)).toBe('OK');
    expect(computeEscalationStatus(79, 100)).toBe('OK');
  });

  it('returns WARNING at or above 80% but under 100%', () => {
    expect(computeEscalationStatus(80, 100)).toBe('WARNING');
    expect(computeEscalationStatus(99, 100)).toBe('WARNING');
  });

  it('returns BREACHED at or above 100%', () => {
    expect(computeEscalationStatus(100, 100)).toBe('BREACHED');
    expect(computeEscalationStatus(250, 100)).toBe('BREACHED');
  });

  it('returns OK for non-positive targets (defensive)', () => {
    expect(computeEscalationStatus(10, 0)).toBe('OK');
    expect(computeEscalationStatus(10, -1)).toBe('OK');
  });
});