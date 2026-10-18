/**
 * 4.5A.4-T4.3 — POD (proof-of-delivery) preview component.
 *
 * Backend `podType` is either "PHOTO" or "SIGNATURE":
 *   - PHOTO     → render <img> from imageUrl
 *   - SIGNATURE → render <img src="data:image/png;base64,..."> from signatureBase64
 *
 * Falls back to a friendly empty state when neither payload is set.
 */

import React, { useEffect, useState } from 'react';
import { Empty, Skeleton, Space, Typography } from 'antd';
import { portalTrackService } from '../services/portalTrackService';
import type { PortalPodResponse } from '../types/portal';
import { zhCN } from '../locale/zh-CN';

interface Props {
  doId: number;
}

const PodPreview: React.FC<Props> = ({ doId }) => {
  const [data, setData] = useState<PortalPodResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    setLoading(true);
    portalTrackService
      .pod(doId)
      .then((r) => {
        if (active) setData(r);
      })
      .catch(() => {
        if (active) setData(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [doId]);

  if (loading) {
    return <Skeleton.Image active style={{ width: 240, height: 160 }} />;
  }
  if (!data || (!data.imageUrl && !data.signatureBase64)) {
    return <Empty description={zhCN.track.detail.podNone} />;
  }

  const isSignature = (data.podType ?? '').toUpperCase() === 'SIGNATURE';

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Typography.Text strong>
        {isSignature ? zhCN.track.detail.podSignature : zhCN.track.detail.podPhoto}
      </Typography.Text>

      {isSignature && data.signatureBase64 ? (
        <img
          alt="signature"
          src={`data:image/png;base64,${data.signatureBase64}`}
          style={{ maxWidth: 320, maxHeight: 200, border: '1px solid #d9d9d9' }}
        />
      ) : data.imageUrl ? (
        <img
          alt="pod"
          src={data.imageUrl}
          style={{ maxWidth: 320, maxHeight: 200, border: '1px solid #d9d9d9' }}
        />
      ) : null}

      {(data.signerName || data.signedAt) && (
        <Space split="·">
          {data.signerName && (
            <span>
              {zhCN.track.detail.signer}: {data.signerName}
            </span>
          )}
          {data.signedAt && (
            <span>
              {zhCN.track.detail.signedAtLabel}: {data.signedAt}
            </span>
          )}
        </Space>
      )}
    </Space>
  );
};

export default PodPreview;
