/**
 * 4.5A.4-T4.3 — TrackDetail page.
 *
 * Renders the DO overview (Descriptions) + timeline (Antd Timeline) + POD preview
 * (PodPreview component). Data is fetched via /api/customer/track/{doId}.
 */

import React, { useEffect, useState } from 'react';
import {
  Card,
  Descriptions,
  Skeleton,
  Timeline,
  Empty,
  Button,
  Result,
  Space,
  Typography,
} from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { portalTrackService } from '../../services/portalTrackService';
import type { PortalTrackDetail } from '../../types/portal';
import StatusTag from '../../components/StatusTag';
import PodPreview from '../../components/PodPreview';
import { zhCN } from '../../locale/zh-CN';

const TrackDetail: React.FC = () => {
  const { doId } = useParams<{ doId: string }>();
  const navigate = useNavigate();
  const idNum = doId ? Number(doId) : NaN;
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<PortalTrackDetail | null>(null);

  useEffect(() => {
    if (!Number.isFinite(idNum)) {
      setError('无效的运单 ID');
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    setError(null);
    portalTrackService
      .detail(idNum)
      .then((r) => {
        if (active) setDetail(r);
      })
      .catch((e: { message?: string }) => {
        if (active) setError(e.message ?? '加载失败');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [idNum]);

  if (loading) {
    return (
      <Card>
        <Skeleton active paragraph={{ rows: 8 }} />
      </Card>
    );
  }
  if (error || !detail) {
    return (
      <Result
        status="404"
        title="运单不存在"
        subTitle={error ?? ''}
        extra={
          <Button type="primary" onClick={() => navigate('/track')}>
            返回列表
          </Button>
        }
      />
    );
  }

  const dt = zhCN.track.detail;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Space>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/track')}>
          {zhCN.common.back}
        </Button>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {dt.title} · {detail.doNo}
        </Typography.Title>
        <StatusTag status={detail.status} />
      </Space>

      <Card title={dt.base}>
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label={zhCN.track.column.doNo}>
            {detail.doNo}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.track.column.status}>
            <StatusTag status={detail.status} />
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.track.column.origin}>
            {detail.originAddress ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.track.column.destination}>
            {detail.destAddress ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.track.column.recipient}>
            {detail.recipientName ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.track.column.phone}>
            {detail.recipientPhone ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label={dt.weight}>
            {detail.weight != null
              ? `${detail.weight} ${dt.weightUnit}`
              : '-'}
          </Descriptions.Item>
          <Descriptions.Item label={dt.volume}>
            {detail.volume != null
              ? `${detail.volume} ${dt.volumeUnit}`
              : '-'}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.track.column.createdAt}>
            {detail.createdAt ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.track.column.signedAt}>
            {detail.signedAt ?? '-'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title={dt.timeline}>
        {detail.timeline.length === 0 ? (
          <Empty description={dt.emptyTimeline} />
        ) : (
          <Timeline
            items={detail.timeline.map((ev) => ({
              color:
                (ev.status ?? '').toUpperCase() === 'EXCEPTION'
                  ? 'red'
                  : 'blue',
              children: (
                <Space direction="vertical" size={4}>
                  <Space>
                    <StatusTag status={ev.status} />
                    {ev.eventName && (
                      <Typography.Text strong>{ev.eventName}</Typography.Text>
                    )}
                  </Space>
                  {ev.remark && (
                    <Typography.Text type="secondary">
                      {ev.remark}
                    </Typography.Text>
                  )}
                  <Space size={12}>
                    {ev.operator && (
                      <Typography.Text type="secondary">
                        操作员: {ev.operator}
                      </Typography.Text>
                    )}
                    {ev.occurredAt && (
                      <Typography.Text type="secondary">
                        {ev.occurredAt}
                      </Typography.Text>
                    )}
                  </Space>
                </Space>
              ),
            }))}
          />
        )}
      </Card>

      <Card title={dt.pod}>
        <PodPreview doId={detail.doId} />
      </Card>
    </Space>
  );
};

export default TrackDetail;
