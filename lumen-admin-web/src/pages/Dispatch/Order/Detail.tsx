/**
 * Lumen Admin — DeliveryOrder detail (Iteration 4.1 PR4).
 *
 * 配送单详情 + 状态机操作面板。8 个 SM 迁移全部覆盖：
 *   assign / load / depart / arrive / sign / cancel / raiseException / auto-assign
 * 配合 StateTransitionPanel 做 perm gating + 二次确认。
 */

import { App, Button, Card, Descriptions, Empty, Form, Input, InputNumber, Modal, Select, Space, Spin, Table } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import { StateTransitionPanel, type TransitionConfig } from '@/components/Wms/StateTransitionPanel';
import { showPrompt } from '@/components/Wms/WmsPromptModal';
import { useAccess } from '@/hooks/useAccess';
import { deliveryOrderService, type WmsDeliveryOrder, type WmsDeliveryStop } from '@/services/dispatch/deliveryOrder';

interface StopRow extends WmsDeliveryStop {}

const TRANSITIONS: TransitionConfig[] = [
  { key: 'assign',   label: '手动派车', fromStatus: ['PENDING_DISPATCH'],
    permCode: 'dispatch:order:assign' },
  { key: 'load',     label: '装车',     fromStatus: ['DISPATCHED'],
    permCode: 'dispatch:order:load' },
  { key: 'depart',   label: '出发',     fromStatus: ['LOADED'],
    permCode: 'dispatch:order:depart' },
  { key: 'arrive',   label: '到达',     fromStatus: ['ENROUTE'],
    permCode: 'dispatch:order:arrive' },
  { key: 'sign',     label: '签收',     fromStatus: ['ARRIVED'],
    permCode: 'dispatch:order:sign' },
  { key: 'cancel',   label: '取消',     fromStatus: ['PENDING_DISPATCH','DISPATCHED','LOADED','ENROUTE'],
    permCode: 'dispatch:order:cancel', danger: true,
    confirmTitle: '确认取消该配送单?' },
  { key: 'exception', label: '转异常', fromStatus: ['LOADED','ENROUTE','ARRIVED'],
    permCode: 'dispatch:order:update', danger: true,
    confirmTitle: '确认上报异常?会创建异常工单并把订单置为 EXCEPTION.' },
];

export default function DeliveryOrderDetail() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const access = useAccess();
  const { message } = App.useApp();
  const [order, setOrder] = useState<WmsDeliveryOrder | null>(null);
  const [stops, setStops] = useState<StopRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [autoAssignOpen, setAutoAssignOpen] = useState(false);
  const [autoAssignForm] = Form.useForm();

  const reload = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const oid = Number(id);
      const o = await deliveryOrderService.get(oid);
      setOrder(o);
      try {
        const s = await deliveryOrderService.listStops(oid);
        setStops(s);
      } catch { setStops([]); }
    } catch {
      // request interceptor 已弹错
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
        case 'assign':   await deliveryOrderService.assign(Number(id), body as { vehicleId: number; driverId: number }); break;
        case 'load':     await deliveryOrderService.load(Number(id)); break;
        case 'depart':   await deliveryOrderService.depart(Number(id)); break;
        case 'arrive':   await deliveryOrderService.arrive(Number(id), body as { stopId: number }); break;
        case 'sign':     await deliveryOrderService.sign(Number(id), body as { stopId: number; signPhotoUrl: string }); break;
        case 'cancel':   await deliveryOrderService.cancel(Number(id), body as { reason: string }); break;
        case 'exception':await deliveryOrderService.raiseException(Number(id), body as { type: string; description: string }); break;
        default: throw new Error(`unknown action: ${key}`);
      }
      message.success('操作成功');
      await reload();
    } catch {
      // request interceptor 已展示
    } finally {
      setActing(false);
    }
  };

  if (loading || !order) {
    return <Card><Spin /></Card>;
  }
  const isNew = !id;
  if (isNew) {
    // PR4: minimal working create modal — PR5 接入 full source-from-outbound UI.
    return (
      <Card title="新建配送单" extra={<Button onClick={() => nav('/dispatch/orders')}>返回</Button>}>
        <CreateOrderForm onCreated={(o) => { nav(`/dispatch/orders/${o.id}`); }} />
      </Card>
    );
  }

  const status = order.status;
  // arrive / sign / exception 需要表单输入，单独处理
  const showArrivePrompt = status === 'ENROUTE' && access.canRead('dispatch:order:arrive');
  const showSignPrompt   = status === 'ARRIVED' && access.canRead('dispatch:order:sign');
  const showExceptionPrompt = (status === 'LOADED' || status === 'ENROUTE' || status === 'ARRIVED')
                            && access.canRead('dispatch:order:update');
  const showCancelPrompt  = (status === 'PENDING_DISPATCH' || status === 'DISPATCHED'
                            || status === 'LOADED' || status === 'ENROUTE')
                            && access.canRead('dispatch:order:cancel');

  return (
    <Card
      title={`配送单 ${order.no}`}
      extra={
        <Space>
          <StatusTag value={order.status} />
          <Button onClick={() => nav('/dispatch/orders')}>返回列表</Button>
        </Space>
      }
    >
      <Descriptions column={2} size="small" bordered>
        <Descriptions.Item label="配送单号">{order.no}</Descriptions.Item>
        <Descriptions.Item label="状态"><StatusTag value={order.status} /></Descriptions.Item>
        <Descriptions.Item label="源单号">{order.sourceNo}</Descriptions.Item>
        <Descriptions.Item label="源类型">{order.sourceType ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="仓库 ID">{order.warehouseId}</Descriptions.Item>
        <Descriptions.Item label="承运人 ID">{order.carrierId ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="车辆 ID">{order.vehicleId ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="司机 ID">{order.driverId ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="总重量 (kg)">{order.totalWeightKg ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="总体积 (m³)">{order.totalVolumeM3 ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="站点数">{order.stopCount ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="实际出发"><DateTimeColumn value={order.actualDepartAt} /></Descriptions.Item>
        <Descriptions.Item label="到达时间"><DateTimeColumn value={order.arrivedAt} /></Descriptions.Item>
        <Descriptions.Item label="签收时间"><DateTimeColumn value={order.deliveredAt} /></Descriptions.Item>
        <Descriptions.Item label="签收照片" span={2}>
          {order.signPhotoUrl ? <a href={order.signPhotoUrl} target="_blank" rel="noreferrer">查看</a> : '—'}
        </Descriptions.Item>
        <Descriptions.Item label="关联异常工单">{order.exceptionId ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="备注" span={2}>{order.remark || '—'}</Descriptions.Item>
        <Descriptions.Item label="创建时间"><DateTimeColumn value={order.createdAt} /></Descriptions.Item>
        <Descriptions.Item label="更新时间"><DateTimeColumn value={order.updatedAt} /></Descriptions.Item>
      </Descriptions>

      <Space style={{ marginTop: 16 }} wrap>
        {status === 'PENDING_DISPATCH' && access.canRead('dispatch:order:assign') && (
          <Button loading={acting} type="primary" onClick={() => setAutoAssignOpen(true)}>自动派车</Button>
        )}

        {showArrivePrompt && (
          <Button loading={acting} type="primary" onClick={async () => {
            const stopIdStr = await showPrompt({ title: '到达的站点 ID' });
            if (stopIdStr === null) return;
            action('arrive', { stopId: Number(stopIdStr) });
          }}>到达</Button>
        )}

        {showSignPrompt && (
          <Button loading={acting} type="primary" onClick={async () => {
            const stopIdStr = await showPrompt({ title: '签收的站点 ID' });
            if (stopIdStr === null) return;
            const photoUrl = await showPrompt({ title: '签收照片 URL' });
            if (photoUrl === null) return;
            action('sign', { stopId: Number(stopIdStr), signPhotoUrl: photoUrl });
          }}>签收</Button>
        )}

        {showExceptionPrompt && (
          <Button loading={acting} danger onClick={async () => {
            const type = await showPrompt({ title: '异常类型 (REJECTED/OVERTIME/DAMAGE/ADDR_ERR)' });
            if (type === null) return;
            const description = await showPrompt({ title: '异常描述' });
            if (description === null) return;
            action('exception', { type, description });
          }}>上报异常</Button>
        )}

        {showCancelPrompt && (
          <Button loading={acting} danger onClick={async () => {
            const reason = await showPrompt({ title: '取消原因' });
            if (reason === null) return;
            action('cancel', { reason });
          }}>取消</Button>
        )}
      </Space>

      {/* assign / load / depart 用标准 StateTransitionPanel 处理 */}
      <StateTransitionPanel
        status={status}
        transitions={TRANSITIONS.filter((t) => t.key === 'assign' || t.key === 'load' || t.key === 'depart')}
        onAction={(k) => {
          if (k === 'assign') setAutoAssignOpen(true);
          else action(k);
        }}
        loading={acting}
      />

      <h3 style={{ marginTop: 24 }}>站点</h3>
      {stops.length > 0 ? (
        <Table<StopRow>
          rowKey="id"
          dataSource={stops}
          pagination={false}
          size="small"
          columns={[
            { title: '站点号', dataIndex: 'stopNo', width: 80 },
            { title: '地址', dataIndex: 'address', width: 220 },
            { title: '联系人', dataIndex: 'contactName', width: 100 },
            { title: '联系电话', dataIndex: 'contactPhone', width: 140 },
            {
              title: '状态', dataIndex: 'status', width: 110,
              render: (v) => <StatusTag value={String(v)} />,
            },
            {
              title: '到达时间', dataIndex: 'arrivedAt', width: 170,
              render: (v) => <DateTimeColumn value={String(v ?? '')} />,
            },
            {
              title: '完成时间', dataIndex: 'completedAt', width: 170,
              render: (v) => <DateTimeColumn value={String(v ?? '')} />,
            },
          ]}
        />
      ) : (
        <Empty description="无站点" />
      )}

      {/* 派车对话框（手动派车 / 自动派车 共用） */}
      <Modal
        title="派车"
        open={autoAssignOpen}
        onCancel={() => setAutoAssignOpen(false)}
        onOk={async () => {
          const v = await autoAssignForm.validateFields();
          action('assign', { vehicleId: v.vehicleId, driverId: v.driverId });
          setAutoAssignOpen(false);
          autoAssignForm.resetFields();
        }}
        confirmLoading={acting}
      >
        <Form form={autoAssignForm} layout="vertical">
          <Form.Item name="vehicleId" label="车辆 ID" rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="driverId" label="司机 ID" rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}

/**
 * PR4 minimal create form — replaces PR5 placeholder. 用于非 outbound 触发的
 * 手工建单场景 (例如调度员把同一票货拆单 / 拼单). 必须填源单号 + 仓库 ID,
 * 重量/体积/站点数 暂设默认 1.0/1.0/1.
 */
function CreateOrderForm({ onCreated }: { onCreated: (o: WmsDeliveryOrder) => void }) {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async () => {
    const v = await form.validateFields();
    setSubmitting(true);
    try {
      const created = await deliveryOrderService.create({
        sourceNo: v.sourceNo,
        sourceType: v.sourceType || 'OUTBOUND',
        warehouseId: Number(v.warehouseId),
        totalWeightKg: Number(v.totalWeightKg ?? 1),
        totalVolumeM3: Number(v.totalVolumeM3 ?? 1),
        stopCount: Number(v.stopCount ?? 1),
        remark: v.remark,
      });
      message.success(`已创建配送单 ${created.no}`);
      onCreated(created);
    } catch {
      // interceptor 已弹错
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
      <Form.Item name="sourceNo" label="源单号" rules={[{ required: true, max: 64 }]}>
        <Input placeholder="如: OUT-20261010-0001" />
      </Form.Item>
      <Form.Item name="sourceType" label="源类型" initialValue="OUTBOUND">
        <Select
          style={{ width: '100%' }}
          options={[
            { value: 'OUTBOUND', label: 'OUTBOUND (出库)' },
            { value: 'TRANSFER', label: 'TRANSFER (调拨)' },
            { value: 'MANUAL', label: 'MANUAL (手工)' },
          ]}
        />
      </Form.Item>
      <Form.Item name="warehouseId" label="仓库 ID" rules={[{ required: true }]}>
        <InputNumber min={1} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="totalWeightKg" label="总重量 (kg)" initialValue={1}>
        <InputNumber min={0} step={0.1} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="totalVolumeM3" label="总体积 (m³)" initialValue={1}>
        <InputNumber min={0} step={0.1} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="stopCount" label="站点数" initialValue={1}>
        <InputNumber min={1} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item name="remark" label="备注">
        <Input.TextArea rows={2} maxLength={500} showCount />
      </Form.Item>
      <Form.Item>
        <Button type="primary" loading={submitting} onClick={onSubmit}>
          提交
        </Button>
      </Form.Item>
    </Form>
  );
}
