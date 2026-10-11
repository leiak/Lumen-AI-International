/**
 * Lumen Admin — WMS Outbound Note detail (PR4-T4.3, Iteration 3.0a).
 *
 * 详情页 — 顶部基本信息和状态 Tag;中部行项目只读表 (T4.3 不带 CRUD,
 * 完整行编辑放到 PR5 共享组件 PR);底部状态机动作按钮
 * (submit / approve / pick / ship / complete / cancel).
 *
 * 拣货 / 发货行项目的弹窗输入用 window.prompt 临时顶上 —
 * 完整表单放到后续 PR5 共享组件 PR.
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
      const r = (await request.get(`/wms/outbound-notes/${id}`)) as unknown as OutboundNote;
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
      await request.post(`/wms/outbound-notes/${id}/${path}`, body);
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
      <Card title="新建出库单" extra={<Button onClick={() => nav('/wms/outbound-notes')}>返回</Button>}>
        <Empty description="新建表单将在 PR5 (共享组件 PR) 接入" />
      </Card>
    );
  }

  const isPicking = status === 'PICKING';
  const isReview = status === 'REVIEW';
  const canSubmit = status === 'DRAFT' && access.canRead('wms:outbound:submit');
  const canApprove = status === 'PENDING_APPROVAL' && access.canRead('wms:outbound:approve');
  const canPick = isPicking && access.canRead('wms:outbound:pick');
  const canShip = isReview && access.canRead('wms:outbound:ship');
  const canComplete = status === 'SHIPPED' && access.canRead('wms:outbound:complete');
  const canCancel =
    status !== 'DONE' && status !== 'CANCELLED' && access.canRead('wms:outbound:cancel');

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
        {canSubmit && (
          <Popconfirm
            title="确认提交该出库单?将锁定对应库存."
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
        {canShip && (
          <Button type="primary" loading={acting} onClick={() => {
            const carrier = window.prompt('请输入承运商');
            if (!carrier) return;
            const trackingNo = window.prompt('请输入运单号');
            if (!trackingNo) return;
            action('ship', { carrier, trackingNo });
          }}>
            发货
          </Button>
        )}
        {canComplete && (
          <Button type="primary" loading={acting} onClick={() => action('complete')}>
            完成
          </Button>
        )}
        {canCancel && (
          <Popconfirm
            title="确认取消该出库单?未拣完的库存锁会被释放."
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
                      onClick={() => {
                        const qtyStr = window.prompt(
                          `请输入拣货数量 (剩余 ${l.qty - (l.pickedQty ?? 0)})`,
                        );
                        if (!qtyStr) return;
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
