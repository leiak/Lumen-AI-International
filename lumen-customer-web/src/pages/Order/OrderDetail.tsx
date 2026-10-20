/**
 * 4.5B.3-T3.4 — OrderDetail page.
 *
 * Renders the DO overview (Descriptions) + timeline (Antd Timeline) and, when
 * canCancel is true, a "取消订单" action that opens a Modal to collect a reason
 * before POSTing /api/customer/order/{doId}/cancel.
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Card,
  Descriptions,
  Empty,
  Input,
  Modal,
  Result,
  Skeleton,
  Space,
  Timeline,
  Typography,
  message,
} from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { portalOrderService } from '../../services/portalOrderService';
import type { PortalOrderDetail } from '../../types/order';
import StatusTag from '../../components/StatusTag';
import { zhCN } from '../../locale/zh-CN';

const { TextArea } = Input;

const fmtWindow = (s: string | null, e: string | null): string => {
  if (!s) return '-';
  const a = s.replace('T', ' ').slice(0, 16);
  if (!e) return a;
  return `${a} ~ ${e.replace('T', ' ').slice(0, 16)}`;
};

const OrderDetail: React.FC = () => {
  const { doId } = useParams<{ doId: string }>();
  const navigate = useNavigate();
  const idNum = doId ? Number(doId) : NaN;

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<PortalOrderDetail | null>(null);

  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [cancelling, setCancelling] = useState(false);

  const load = useCallback(() => {
    if (!Number.isFinite(idNum)) {
      setError('无效的订单 ID');
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    setError(null);
    portalOrderService
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

  useEffect(load, [load]);

  const onCancel = async () => {
    if (!reason.trim()) {
      message.error('请填写取消原因');
      return;
    }
    setCancelling(true);
    try {
      const r = await portalOrderService.cancel(idNum, { reason: reason.trim() });
      setDetail(r);
      setCancelOpen(false);
      setReason('');
      message.success('订单已取消');
    } catch (e: any) {
      message.error(e?.response?.data?.message || e?.message || '取消失败');
    } finally {
      setCancelling(false);
    }
  };

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
        title="订单不存在"
        subTitle={error ?? ''}
        extra={
          <Button type="primary" onClick={() => navigate('/orders')}>
            返回列表
          </Button>
        }
      />
    );
  }

  const dt = zhCN.order.detail;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Space>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/orders')}>
          {zhCN.common.back}
        </Button>
        <Typography.Title level={4} style={{ margin: 0 }}>
          {dt.title} · {detail.doNo}
        </Typography.Title>
        <StatusTag status={detail.status} />
        <Button danger disabled={!detail.canCancel} onClick={() => setCancelOpen(true)}>
          {detail.canCancel ? zhCN.order.cancel : dt.notCancellable}
        </Button>
      </Space>

      <Card title={dt.base}>
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label={zhCN.order.column.doNo}>
            {detail.doNo}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.order.column.status}>
            <StatusTag status={detail.status} />
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.order.column.origin}>
            {detail.originAddress ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.order.column.destination}>
            {detail.destAddress ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.order.column.pickupWindow} span={2}>
            {fmtWindow(detail.plannedPickupWindowStart, detail.plannedPickupWindowEnd)}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.order.column.createdAt}>
            {detail.createdAt ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.order.column.signedAt}>
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
              color: (ev.status ?? '').toUpperCase() === 'EXCEPTION' ? 'red' : 'blue',
              children: (
                <Space direction="vertical" size={4}>
                  <Space>
                    <StatusTag status={ev.status} />
                    {ev.eventName && (
                      <Typography.Text strong>{ev.eventName}</Typography.Text>
                    )}
                  </Space>
                  {ev.remark && (
                    <Typography.Text type="secondary">{ev.remark}</Typography.Text>
                  )}
                  <Space size={12}>
                    {ev.operator && (
                      <Typography.Text type="secondary">操作员: {ev.operator}</Typography.Text>
                    )}
                    {ev.occurredAt && (
                      <Typography.Text type="secondary">{ev.occurredAt}</Typography.Text>
                    )}
                  </Space>
                </Space>
              ),
            }))}
          />
        )}
      </Card>

      <Modal
        title={zhCN.order.cancelTitle}
        open={cancelOpen}
        onOk={onCancel}
        confirmLoading={cancelling}
        onCancel={() => setCancelOpen(false)}
        okText={zhCN.common.confirm}
        cancelText={zhCN.common.cancel}
      >
        <TextArea
          rows={4}
          maxLength={500}
          value={reason}
          placeholder={zhCN.order.cancelReasonPlaceholder}
          onChange={(e) => setReason(e.target.value)}
        />
      </Modal>
    </Space>
  );
};

export default OrderDetail;