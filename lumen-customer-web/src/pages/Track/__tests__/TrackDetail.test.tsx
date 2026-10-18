/**
 * 4.5A.4-T4.4 — TrackDetail page tests (4 cases).
 */

import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import TrackDetail from '../TrackDetail';
import { portalTrackService } from '../../../services/portalTrackService';
import type { PortalTrackDetail, PortalPodResponse } from '../../../types/portal';

vi.mock('../../../services/portalTrackService');
const mockDetail = vi.mocked(portalTrackService.detail);
const mockPod = vi.mocked(portalTrackService.pod);

function renderDetail(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/track/:doId" element={<TrackDetail />} />
        <Route path="/track" element={<div>track list</div>} />
      </Routes>
    </MemoryRouter>
  );
}

const DETAIL: PortalTrackDetail = {
  doId: 100,
  doNo: 'DO-100',
  status: 'IN_TRANSIT',
  originAddress: 'Shanghai',
  destAddress: 'Beijing',
  recipientName: 'Alice',
  recipientPhone: '13800000001',
  weight: 12.5,
  volume: 0.5,
  createdAt: '2026-10-15T08:00:00',
  signedAt: null,
  timeline: [
    {
      status: 'CREATED', eventName: 'Order created',
      operator: 'system', occurredAt: '2026-10-15T08:00:00',
      remark: 'Order placed',
    },
    {
      status: 'DISPATCHED', eventName: 'Dispatched',
      operator: 'driver-1', occurredAt: '2026-10-15T09:00:00',
      remark: null,
    },
  ],
};

const POD: PortalPodResponse = {
  doId: 100,
  podType: 'PHOTO',
  imageUrl: 'https://example.com/pod.jpg',
  signatureBase64: null,
  signerName: 'Alice',
  signedAt: '2026-10-15T18:00:00',
};

describe('TrackDetail page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loading state shows Skeleton', async () => {
    // Promise that never resolves → component stays in loading state.
    mockDetail.mockImplementation(() => new Promise(() => {}));
    mockPod.mockImplementation(() => new Promise(() => {}));

    renderDetail('/track/100');

    // Skeleton renders ant-skeleton-* elements (incl. Skeleton.Image for POD).
    await waitFor(() => {
      const skeletons = document.querySelectorAll('.ant-skeleton, .ant-skeleton-image');
      expect(skeletons.length).toBeGreaterThan(0);
    });

    // Detail not yet rendered.
    expect(screen.queryByText('DO-100')).not.toBeInTheDocument();
  });

  it('renders Descriptions and Timeline after detail loads', async () => {
    mockDetail.mockResolvedValue(DETAIL);
    mockPod.mockRejectedValue({ message: 'no pod' });

    renderDetail('/track/100');

    await waitFor(() => {
      expect(screen.getByText('DO-100')).toBeInTheDocument();
    });

    // Descriptions renders the labels and values; check for known fields.
    expect(screen.getByText('Shanghai')).toBeInTheDocument();
    expect(screen.getByText('Beijing')).toBeInTheDocument();
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('13800000001')).toBeInTheDocument();

    // Timeline events rendered.
    expect(screen.getByText('Order created')).toBeInTheDocument();
    expect(screen.getByText('Dispatched')).toBeInTheDocument();
  });

  it('PodPreview is rendered with PHOTO image when service returns imageUrl', async () => {
    mockDetail.mockResolvedValue(DETAIL);
    mockPod.mockResolvedValue(POD);

    renderDetail('/track/100');

    // Wait for the POD image to appear.
    await waitFor(() => {
      const img = screen.getByAltText('pod') as HTMLImageElement;
      expect(img).toBeInTheDocument();
      expect(img.src).toBe('https://example.com/pod.jpg');
    });
  });

  it('invalid doId shows the 404 fallback Result', async () => {
    // detail was never called because the URL param is non-numeric.
    mockDetail.mockResolvedValue(DETAIL);
    mockPod.mockResolvedValue(POD);

    renderDetail('/track/not-a-number');

    await waitFor(() => {
      expect(screen.getByText('运单不存在')).toBeInTheDocument();
    });

    // Service should NOT have been called for invalid ID.
    expect(mockDetail).not.toHaveBeenCalled();
  });
});