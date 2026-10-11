/**
 * Lumen Admin — WMS Inventory Adjust Note detail (PR5-T5.2, Iteration 3.0a).
 *
 * 详情页 — 顶部基本信息和状态 Tag;中部行项目只读表 (T5.2 不带行 CRUD,
 * 完整行编辑放到 PR5 共享组件 PR);底部状态机动作按钮
 * (submit / approve / reject / execute / cancel).
 *
 * reject 用 window.prompt 临时顶上拿驳回原因 — 完整表单放到后续 PR5 共享组件 PR.
 * execute 用 Popconfirm 走单步确认.
 */

import {
  Button,
  Card,
  Descriptions,
  Empty,
  Popconfirm,
  Space,
  Spin,
  Table,
  Tag,
  App,
} from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';

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
      const r = (await request.get(`/wms/inventory-adjusts/${id}`)) as unknown as AdjustNote;
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

  const action = async (path: string, body?: unknown, confirmMsg?: string) => {
    if (!id) return;
    if (confirmMsg && !window.confirm(confirmMsg)) return;
    setActing(true);
    try {
      await request.post(`/wms/inventory-adjusts/${id}/${path}`, body);
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

  // 新建占位页 — PR5 阶段用 ModalForm 接管
  if (isNew) {
    return (
      <Card title="新建调整单" extra={<Button onClick={() => nav('/wms/inventory-adjusts')}>返回</Button>}>
        <Empty description="新建表单将在 PR5 (共享组件 PR) 接入" />
      </Card>
    );
  }

  const canSubmit = status === 'DRAFT' && access.canRead('wms:adjust:submit');
  const canApprove = status === 'PENDING_APPROVAL' && access.canRead('wms:adjust:approve');
  const canReject = status === 'PENDING_APPROVAL' && access.canRead('wms:adjust:approve');
  const canExecute = status === 'APPROVED' && access.canRead('wms:adjust:execute');
  const canCancel =
    status !== 'DONE' && status !== 'REJECTED' && status !== 'CANCELLED'
      && access.canRead('wms:adjust:cancel');

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
        {canSubmit && (
          <Popconfirm
            title="确认提交该调整单?将按阈值路由审批."
            okText="提交"
            cancelText="取消"
            onConfirm={() => action('submit', undefined)}
          >
            <Button type="primary" loading={acting}>提交</Button>
          </Popconfirm>
        )}
        {canApprove && (
          <Button type="primary" loading={acting} onClick={() => action('approve')}>
            审批通过
          </Button>
        )}
        {canReject && (
          <Button
            danger
            loading={acting}
            onClick={() => {
              const reason = window.prompt('请输入驳回原因');
              if (!reason) return;
              action('reject', { reason });
            }}
          >
            驳回
          </Button>
        )}
        {canExecute && (
          <Popconfirm
            title="本次执行将按行 deltaQty 扣减/增加库存,确认执行?"
            okText="执行"
            cancelText="取消"
            onConfirm={() => action('execute', undefined)}
          >
            <Button type="primary" loading={acting}>执行</Button>
          </Popconfirm>
        )}
        {canCancel && (
          <Popconfirm
            title="确认取消该调整单?"
            okText="取消单据"
            cancelText="返回"
            onConfirm={() => action('cancel')}
          >
            <Button danger loading={acting}>取消单据</Button>
          </Popconfirm>
        )}
      </Space>

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
