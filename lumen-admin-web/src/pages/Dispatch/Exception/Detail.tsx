/**
 * Lumen Admin — DeliveryException detail (Iteration 4.1 PR4).
 *
 * 异常工单详情。4 个状态机迁移覆盖: investigate / resolve / close / reopen.
 * 状态机没有 cancel 端点 (V20 EXCEPTION_WORK_ORDER 不允许 cancel), 因此
 * 详情页仅提供上方 4 个操作按钮 + StateTransitionPanel (close 走那里).
 *
 * 权限按 V21 seed 实际可用的 perms: investigate / resolve / close
 * (reopen 没有专属 perm, 复用 investigate 因为 reopen 等价于"重新调查").
 */

import { App, Button, Card, Descriptions, Space, Spin, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import { StateTransitionPanel, type TransitionConfig } from '@/components/Wms/StateTransitionPanel';
import { showPrompt } from '@/components/Wms/WmsPromptModal';
import { useAccess } from '@/hooks/useAccess';
import { deliveryExceptionService, type WmsDeliveryException } from '@/services/dispatch/exception';

const TRANSITIONS: TransitionConfig[] = [
  { key: 'close', label: '关闭', fromStatus: ['RESOLVED'],
    permCode: 'dispatch:exception:close' },
];

export default function DeliveryExceptionDetail() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const access = useAccess();
  const { message } = App.useApp();
  const [ex, setEx] = useState<WmsDeliveryException | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);

  const reload = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const e = await deliveryExceptionService.get(Number(id));
      setEx(e);
    } catch {
      // request interceptor already
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void reload(); /* eslint-disable-line react-hooks/exhaustive-deps */ }, [id]);

  const action = async (key: string, body?: unknown) => {
    if (!id) return;
    setActing(true);
    try {
      switch (key) {
        case 'investigate': await deliveryExceptionService.investigate(Number(id), body as { handler: string }); break;
        case 'resolve':     await deliveryExceptionService.resolve(Number(id), body as { resolutionNotes: string }); break;
        case 'close':       await deliveryExceptionService.close(Number(id)); break;
        case 'reopen':      await deliveryExceptionService.reopen(Number(id), body as { reason: string }); break;
        default: throw new Error(`unknown: ${key}`);
      }
      message.success('操作成功');
      await reload();
    } catch {
      // ignore
    } finally {
      setActing(false);
    }
  };

  if (loading || !ex) {
    return <Card><Spin /></Card>;
  }

  const status = ex.status;
  const showInvestigate = status === 'OPEN' && access.canRead('dispatch:exception:investigate');
  const showResolve     = status === 'INVESTIGATING' && access.canRead('dispatch:exception:resolve');
  // reopen 复用 investigate perm (V21 无独立 reopen perm)
  const showReopen      = status === 'CLOSED' && access.canRead('dispatch:exception:investigate');

  return (
    <Card
      title={`异常工单 #${ex.id}`}
      extra={
        <Space>
          <StatusTag value={ex.status} />
          <Button onClick={() => nav('/dispatch/exceptions')}>返回列表</Button>
        </Space>
      }
    >
      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="工单 ID">{ex.id}</Descriptions.Item>
        <Descriptions.Item label="关联订单">{ex.orderId}</Descriptions.Item>
        <Descriptions.Item label="类型"><Tag>{ex.type}</Tag></Descriptions.Item>
        <Descriptions.Item label="状态"><StatusTag value={ex.status} /></Descriptions.Item>
        <Descriptions.Item label="处理人">{ex.handler ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="解决时间"><DateTimeColumn value={ex.resolvedAt} /></Descriptions.Item>
        <Descriptions.Item label="描述" span={2}>
          <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{ex.description || '—'}</pre>
        </Descriptions.Item>
        <Descriptions.Item label="创建时间"><DateTimeColumn value={ex.createdAt} /></Descriptions.Item>
        <Descriptions.Item label="更新时间"><DateTimeColumn value={ex.updatedAt} /></Descriptions.Item>
      </Descriptions>

      <Space style={{ marginTop: 16 }} wrap>
        {showInvestigate && (
          <Button loading={acting} type="primary" onClick={async () => {
            const handler = await showPrompt({ title: '处理人' });
            if (handler === null) return;
            action('investigate', { handler });
          }}>开始调查</Button>
        )}

        {showResolve && (
          <Button loading={acting} type="primary" onClick={async () => {
            const notes = await showPrompt({ title: '解决备注' });
            if (notes === null) return;
            action('resolve', { resolutionNotes: notes });
          }}>标记已解决</Button>
        )}

        {showReopen && (
          <Button loading={acting} onClick={async () => {
            const reason = await showPrompt({ title: '重新打开原因' });
            if (reason === null) return;
            action('reopen', { reason });
          }}>重新打开</Button>
        )}
      </Space>

      <StateTransitionPanel
        status={status}
        transitions={TRANSITIONS}
        onAction={(k) => action(k)}
        loading={acting}
      />
    </Card>
  );
}
