/**
 * Lumen Admin — WMS Outbound Note detail (PR5-T5.3, Iteration 3.0a).
 *
 * Refactored to use shared components:
 *   - <StateTransitionPanel> for the action button bar (status+perm gating)
 *   - showPrompt() replaces window.prompt() for line-level input
 *
 * Status banner + line editor table + reject reason are kept inline because
 * they are note-type-specific (different field shapes per business domain).
 */

import {
  App,
  Button,
  Card,
  Descriptions,
  Empty,
  Space,
  Spin,
  Table,
  Tag,
} from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StateTransitionPanel, type TransitionConfig } from '@/components/Wms/StateTransitionPanel';
import { showPrompt } from '@/components/Wms/WmsPromptModal';
import { useAccess } from '@/hooks/useAccess';
import { outboundNoteService } from '@/services/wms/outbound';

const STATUS_LABEL: Record<string, { text: string; color: string }> = {
  DRAFT:            { text: '草稿',   color: 'default' },
  PENDING_APPROVAL: { text: '待审批', color: 'orange' },
  PICKING:          { text: '拣货中', color: 'blue' },
  REVIEW:           { text: '复核中', color: 'cyan' },
  SHIPPED:          { text: '已发货', color: 'gold' },
  DONE:             { text: '已完成', color: 'green' },
  CANCELLED:        { text: '已取消', color: 'red' },
};

interface OutboundLine {
  id: number;
  lineNo: number;
  itemId: number;
  qty: number;
  pickedQty?: number;
  sourceLocationId: number;
}

interface OutboundNote {
  id: number;
  no: string;
  customerId: number;
  warehouseId: number;
  status: string;
  carrier?: string;
  trackingNo?: string;
  requestedAt?: string;
  shippedAt?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
  lines?: OutboundLine[];
}

function statusTag(status: string) {
  const m = STATUS_LABEL[status] ?? { text: status, color: 'default' };
  return <Tag color={m.color}>{m.text}</Tag>;
}

const TRANSITIONS: TransitionConfig[] = [
  { key: 'submit',  label: '提交',     fromStatus: ['DRAFT'],
    permCode: 'wms:outbound:submit',
    confirmTitle: '确认提交该出库单?将锁定对应库存.', okText: '提交' },
  { key: 'approve', label: '审批通过', fromStatus: ['PENDING_APPROVAL'],
    permCode: 'wms:outbound:approve' },
  { key: 'complete', label: '完成', fromStatus: ['SHIPPED'],
    permCode: 'wms:outbound:complete' },
  { key: 'cancel',  label: '取消单据', fromStatus: ['DRAFT','PENDING_APPROVAL','PICKING','REVIEW','SHIPPED'],
    permCode: 'wms:outbound:cancel', danger: true,
    confirmTitle: '确认取消该出库单?未拣完的库存锁会被释放.',
    okText: '取消单据', cancelText: '返回' },
];

export default function OutboundDetailPage() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const access = useAccess();
  const { message } = App.useApp();
  const [note, setNote] = useState<OutboundNote | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);

  const reload = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const r = (await outboundNoteService.get(Number(id))) as unknown as OutboundNote;
      setNote(r);
    } catch {
      // request interceptor 已经弹过 notification
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const action = async (path: string, body?: unknown) => {
    if (!id) return;
    const noteId = Number(id);
    setActing(true);
    try {
      switch (path) {
        case 'submit':
          await outboundNoteService.submit(noteId);
          break;
        case 'approve':
          await outboundNoteService.approve(noteId);
          break;
        case 'complete':
          await outboundNoteService.complete(noteId);
          break;
        case 'cancel':
          await outboundNoteService.cancel(noteId);
          break;
        case 'ship':
          await outboundNoteService.ship(
            noteId,
            body as { carrier: string; trackingNo: string },
          );
          break;
        default: {
          // Per-line actions: `lines/<lid>/pick`.
          const lineMatch = /^lines\/(\d+)\/pick$/.exec(path);
          if (lineMatch) {
            const [, lid] = lineMatch;
            await outboundNoteService.pickLine(noteId, Number(lid), body as { qty: number });
          } else {
            throw new Error(`unknown action: ${path}`);
          }
        }
      }
      message.success('操作成功');
      await reload();
    } catch {
      // request interceptor 已经展示错误
    } finally {
      setActing(false);
    }
  };

  if (loading || !note) {
    return (
      <Card>
        <Spin />
      </Card>
    );
  }

  const status = note.status;
  const isNew = !id;

  // 新建占位页 — 后续 PR 接入 ModalForm
  if (isNew) {
    return (
      <Card title="新建出库单" extra={<Button onClick={() => nav('/wms/outbound-notes')}>返回</Button>}>
        <Empty description="新建表单将在后续 PR 接入" />
      </Card>
    );
  }

  const isReview = status === 'REVIEW';
  const canPick = status === 'PICKING' && access.canRead('wms:outbound:pick');

  // "ship" needs two text inputs (carrier + tracking) which require modal
  // flows, so it's not a standard Popconfirm transition. Render it
  // conditionally inline alongside <StateTransitionPanel>.
  const showShipButton = isReview && access.canRead('wms:outbound:ship');

  return (
    <Card
      title={`出库单 ${note.no}`}
      extra={
        <Space>
          {statusTag(status)}
          <Button onClick={() => nav('/wms/outbound-notes')}>返回列表</Button>
        </Space>
      }
    >
      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="单号">{note.no}</Descriptions.Item>
        <Descriptions.Item label="状态">{statusTag(status)}</Descriptions.Item>
        <Descriptions.Item label="客户 ID">{note.customerId}</Descriptions.Item>
        <Descriptions.Item label="仓库 ID">{note.warehouseId}</Descriptions.Item>
        <Descriptions.Item label="承运商">{note.carrier ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="运单号">{note.trackingNo ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="请求时间">
          <DateTimeColumn value={note.requestedAt} />
        </Descriptions.Item>
        <Descriptions.Item label="发货时间">
          <DateTimeColumn value={note.shippedAt} />
        </Descriptions.Item>
        <Descriptions.Item label="备注" span={2}>
          {note.remark || '—'}
        </Descriptions.Item>
        <Descriptions.Item label="创建时间">
          <DateTimeColumn value={note.createdAt} />
        </Descriptions.Item>
        <Descriptions.Item label="更新时间">
          <DateTimeColumn value={note.updatedAt} />
        </Descriptions.Item>
      </Descriptions>

      <Space style={{ marginTop: 16 }} wrap>
        {/* ship action has 2 sequential prompts — kept inline. */}
        {showShipButton && (
          <Button type="primary" loading={acting} onClick={async () => {
            const carrier = await showPrompt({ title: '承运商' });
            if (carrier === null) return;
            const trackingNo = await showPrompt({ title: '运单号' });
            if (trackingNo === null) return;
            action('ship', { carrier, trackingNo });
          }}>
            发货
          </Button>
        )}
      </Space>

      <StateTransitionPanel
        status={status}
        transitions={TRANSITIONS}
        onAction={(k) => action(k)}
        loading={acting}
      />

      <h3 style={{ marginTop: 24 }}>行项目</h3>
      {note.lines && note.lines.length > 0 ? (
        <Table<OutboundLine>
          rowKey="id"
          dataSource={note.lines}
          pagination={false}
          size="small"
          columns={[
            { title: '行号', dataIndex: 'lineNo', width: 80 },
            { title: '货品 ID', dataIndex: 'itemId', width: 100 },
            { title: '计划数量', dataIndex: 'qty', width: 100 },
            {
              title: '已拣货',
              dataIndex: 'pickedQty',
              width: 100,
              render: (v) => v ?? 0,
            },
            { title: '源库位 ID', dataIndex: 'sourceLocationId', width: 120 },
            {
              title: '操作',
              width: 140,
              render: (_, l) => (
                <Space>
                  {canPick && (
                    <Button
                      size="small"
                      onClick={async () => {
                        const qtyStr = await showPrompt({
                          title: '拣货数量',
                          placeholder: `请输入拣货数量 (剩余 ${l.qty - (l.pickedQty ?? 0)})`,
                        });
                        if (qtyStr === null) return;
                        const qty = Number(qtyStr);
                        if (!Number.isFinite(qty) || qty <= 0) {
                          message.error('数量必须 > 0');
                          return;
                        }
                        action('lines/' + l.id + '/pick', { qty });
                      }}
                    >
                      拣货
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      ) : (
        <Empty description="无行项目" />
      )}
    </Card>
  );
}
