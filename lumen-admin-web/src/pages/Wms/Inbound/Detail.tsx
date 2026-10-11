/**
 * Lumen Admin — WMS Inbound Note detail (PR5-T5.3, Iteration 3.0a).
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
  Popconfirm,
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
import { request } from '@/services/request';

const STATUS_LABEL: Record<string, { text: string; color: string }> = {
  DRAFT:            { text: '草稿',   color: 'default' },
  PENDING_APPROVAL: { text: '待审批', color: 'orange' },
  RECEIVING:        { text: '收货中', color: 'blue' },
  PUTAWAY:          { text: '上架中', color: 'cyan' },
  DONE:             { text: '已完成', color: 'green' },
  CANCELLED:        { text: '已取消', color: 'red' },
};

interface InboundLine {
  id: number;
  lineNo: number;
  itemId: number;
  qty: number;
  receivedQty?: number;
  putawayQty?: number;
  putawayLocationId?: number | null;
}

interface InboundNote {
  id: number;
  no: string;
  customerId: number;
  warehouseId: number;
  status: string;
  arrivedAt?: string;
  completedAt?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
  lines?: InboundLine[];
}

function statusTag(status: string) {
  const m = STATUS_LABEL[status] ?? { text: status, color: 'default' };
  return <Tag color={m.color}>{m.text}</Tag>;
}

const TRANSITIONS: TransitionConfig[] = [
  { key: 'submit',  label: '提交',     fromStatus: ['DRAFT'],
    permCode: 'wms:inbound:submit', confirmTitle: '确认提交该入库单?', okText: '提交' },
  { key: 'approve', label: '审批通过', fromStatus: ['PENDING_APPROVAL'],
    permCode: 'wms:inbound:approve' },
  { key: 'cancel',  label: '取消单据', fromStatus: ['DRAFT','PENDING_APPROVAL','RECEIVING','PUTAWAY'],
    permCode: 'wms:inbound:cancel', danger: true, confirmTitle: '确认取消该入库单?',
    okText: '取消单据', cancelText: '返回' },
];

export default function InboundDetailPage() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const access = useAccess();
  const { message } = App.useApp();
  const [note, setNote] = useState<InboundNote | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);

  const reload = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const r = (await request.get(`/wms/inbound-notes/${id}`)) as unknown as InboundNote;
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
    setActing(true);
    try {
      await request.post(`/wms/inbound-notes/${id}/${path}`, body);
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
      <Card title="新建入库单" extra={<Button onClick={() => nav('/wms/inbound-notes')}>返回</Button>}>
        <Empty description="新建表单将在后续 PR 接入" />
      </Card>
    );
  }

  const isReceiving = status === 'RECEIVING';
  const isPutaway = status === 'PUTAWAY';
  const canOperate = access.canRead('wms:inbound:putaway');

  const onTransitionAction = async (key: string) => {
    await action(key);
  };

  return (
    <Card
      title={`入库单 ${note.no}`}
      extra={
        <Space>
          {statusTag(status)}
          <Button onClick={() => nav('/wms/inbound-notes')}>返回列表</Button>
        </Space>
      }
    >
      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="单号">{note.no}</Descriptions.Item>
        <Descriptions.Item label="状态">{statusTag(status)}</Descriptions.Item>
        <Descriptions.Item label="客户 ID">{note.customerId}</Descriptions.Item>
        <Descriptions.Item label="仓库 ID">{note.warehouseId}</Descriptions.Item>
        <Descriptions.Item label="到达时间">
          <DateTimeColumn value={note.arrivedAt} />
        </Descriptions.Item>
        <Descriptions.Item label="完成时间">
          <DateTimeColumn value={note.completedAt} />
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

      <StateTransitionPanel
        status={status}
        transitions={TRANSITIONS}
        onAction={onTransitionAction}
        loading={acting}
      />

      <h3 style={{ marginTop: 24 }}>行项目</h3>
      {note.lines && note.lines.length > 0 ? (
        <Table<InboundLine>
          rowKey="id"
          dataSource={note.lines}
          pagination={false}
          size="small"
          columns={[
            { title: '行号', dataIndex: 'lineNo', width: 80 },
            { title: '货品 ID', dataIndex: 'itemId', width: 100 },
            { title: '预期数量', dataIndex: 'qty', width: 100 },
            {
              title: '已收货',
              dataIndex: 'receivedQty',
              width: 100,
              render: (v) => v ?? 0,
            },
            {
              title: '已上架',
              dataIndex: 'putawayQty',
              width: 100,
              render: (v) => v ?? 0,
            },
            {
              title: '库位 ID',
              dataIndex: 'putawayLocationId',
              width: 100,
              render: (v) => v ?? '—',
            },
            {
              title: '操作',
              width: 220,
              render: (_, l) => (
                <Space>
                  {isReceiving && canOperate && (
                    <Button
                      size="small"
                      onClick={async () => {
                        const qtyStr = await showPrompt({
                          title: '收货数量',
                          placeholder: `请输入收货数量 (剩余 ${l.qty - (l.receivedQty ?? 0)})`,
                        });
                        if (qtyStr === null) return;
                        const qty = Number(qtyStr);
                        if (!Number.isFinite(qty) || qty <= 0) {
                          message.error('数量必须 > 0');
                          return;
                        }
                        action('lines/' + l.id + '/receive', { qty });
                      }}
                    >
                      收货
                    </Button>
                  )}
                  {isPutaway && canOperate && (
                    <Popconfirm
                      title="确认上架到该库位?"
                      okText="上架"
                      cancelText="取消"
                      onConfirm={async () => {
                        const qtyStr = await showPrompt({
                          title: '上架数量',
                          placeholder: `请输入上架数量 (剩余 ${(l.receivedQty ?? 0) - (l.putawayQty ?? 0)})`,
                        });
                        if (qtyStr === null) return;
                        const qty = Number(qtyStr);
                        if (!Number.isFinite(qty) || qty <= 0) {
                          message.error('数量必须 > 0');
                          return;
                        }
                        const locStr = l.putawayLocationId
                          ? String(l.putawayLocationId)
                          : await showPrompt({ title: '目标库位 ID' });
                        if (locStr === null) return;
                        const locationId = Number(locStr);
                        if (!Number.isFinite(locationId)) {
                          message.error('库位 ID 无效');
                          return;
                        }
                        action('lines/' + l.id + '/putaway', { qty, locationId });
                      }}
                    >
                      <Button size="small" type="primary">上架</Button>
                    </Popconfirm>
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
