/**
 * Lumen Admin — WMS Inventory Adjust Note detail (PR5-T5.3, Iteration 3.0a).
 *
 * Refactored to use shared components:
 *   - <StateTransitionPanel> for the action button bar (status+perm gating).
 *     `reject` is omitted from TRANSITIONS because it pops a reason prompt;
 *     it's rendered inline next to the panel.
 *   - showPrompt() replaces window.prompt() for the reject-reason flow.
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
import { adjustNoteService } from '@/services/wms/adjust';

const STATUS_LABEL: Record<string, { text: string; color: string }> = {
  DRAFT:            { text: '草稿',     color: 'default' },
  PENDING_APPROVAL: { text: '待审批',   color: 'orange' },
  APPROVED:         { text: '已审批',   color: 'blue' },
  DONE:             { text: '已完成',   color: 'green' },
  REJECTED:         { text: '已驳回',   color: 'volcano' },
  CANCELLED:        { text: '已取消',   color: 'red' },
};

interface AdjustLine {
  id: number;
  lineNo: number;
  itemId: number;
  locationId: number;
  deltaQty: number;
  reasonDetail?: string;
}

interface AdjustNote {
  id: number;
  no: string;
  warehouseId: number;
  status: string;
  reason?: string;
  rejectReason?: string;
  approvedBy?: number;
  approvedAt?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
  lines?: AdjustLine[];
}

function statusTag(status: string) {
  const m = STATUS_LABEL[status] ?? { text: status, color: 'default' };
  return <Tag color={m.color}>{m.text}</Tag>;
}

// reject uses a prompt-modal flow (not a Popconfirm) so it's handled
// inline next to <StateTransitionPanel> rather than declared here.
const TRANSITIONS: TransitionConfig[] = [
  { key: 'submit',   label: '提交',     fromStatus: ['DRAFT'],
    permCode: 'wms:adjust:submit',
    confirmTitle: '确认提交该调整单?将按阈值路由审批.', okText: '提交' },
  { key: 'approve',  label: '审批通过', fromStatus: ['PENDING_APPROVAL'],
    permCode: 'wms:adjust:approve' },
  { key: 'execute',  label: '执行',     fromStatus: ['APPROVED'],
    permCode: 'wms:adjust:execute',
    confirmTitle: '本次执行将按行 deltaQty 扣减/增加库存,确认执行?', okText: '执行' },
  { key: 'cancel',   label: '取消单据',
    fromStatus: ['DRAFT','PENDING_APPROVAL','APPROVED','REJECTED'],
    permCode: 'wms:adjust:cancel', danger: true,
    confirmTitle: '确认取消该调整单?', okText: '取消单据', cancelText: '返回' },
];

export default function AdjustDetailPage() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const access = useAccess();
  const { message } = App.useApp();
  const [note, setNote] = useState<AdjustNote | null>(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);

  const reload = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const r = (await adjustNoteService.get(Number(id))) as unknown as AdjustNote;
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
          await adjustNoteService.submit(noteId);
          break;
        case 'approve':
          await adjustNoteService.approve(noteId);
          break;
        case 'reject':
          await adjustNoteService.reject(noteId, body as { reason: string });
          break;
        case 'execute':
          await adjustNoteService.execute(noteId);
          break;
        case 'cancel':
          await adjustNoteService.cancel(noteId);
          break;
        default:
          throw new Error(`unknown action: ${path}`);
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
      <Card title="新建调整单" extra={<Button onClick={() => nav('/wms/inventory-adjusts')}>返回</Button>}>
        <Empty description="新建表单将在后续 PR 接入" />
      </Card>
    );
  }

  const canReject = status === 'PENDING_APPROVAL' && access.canRead('wms:adjust:approve');

  return (
    <Card
      title={`调整单 ${note.no}`}
      extra={
        <Space>
          {statusTag(status)}
          <Button onClick={() => nav('/wms/inventory-adjusts')}>返回列表</Button>
        </Space>
      }
    >
      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="单号">{note.no}</Descriptions.Item>
        <Descriptions.Item label="状态">{statusTag(status)}</Descriptions.Item>
        <Descriptions.Item label="仓库 ID">{note.warehouseId}</Descriptions.Item>
        <Descriptions.Item label="原因">{note.reason ?? '—'}</Descriptions.Item>
        {status === 'REJECTED' && (
          <Descriptions.Item label="驳回原因" span={2}>
            {note.rejectReason ?? '—'}
          </Descriptions.Item>
        )}
        {status !== 'REJECTED' && (
          <Descriptions.Item label="审批时间" span={2}>
            <DateTimeColumn value={note.approvedAt} />
          </Descriptions.Item>
        )}
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
        {/* reject needs a reason prompt — kept inline alongside the panel. */}
        {canReject && (
          <Button
            danger
            loading={acting}
            onClick={async () => {
              const reason = await showPrompt({
                title: '驳回原因',
                placeholder: '请输入驳回原因 (最多 255 字)',
                maxLength: 255,
                required: true,
              });
              if (reason === null) return;
              action('reject', { reason });
            }}
          >
            驳回
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
        <Table<AdjustLine>
          rowKey="id"
          dataSource={note.lines}
          pagination={false}
          size="small"
          columns={[
            { title: '行号', dataIndex: 'lineNo', width: 80 },
            { title: '货品 ID', dataIndex: 'itemId', width: 100 },
            { title: '库位 ID', dataIndex: 'locationId', width: 120 },
            {
              title: '调整量',
              dataIndex: 'deltaQty',
              width: 120,
              render: (v: number) => (v > 0 ? `+${v}` : String(v)),
            },
            {
              title: '原因详情',
              dataIndex: 'reasonDetail',
              render: (v: string) => v ?? '—',
            },
          ]}
        />
      ) : (
        <Empty description="无行项目" />
      )}
    </Card>
  );
}
