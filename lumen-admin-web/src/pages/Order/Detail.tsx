/**
 * Lumen Admin — 货运委托单详情 (Iteration 4.6).
 *
 * <p><b>动作按钮由后端返回的 `allowedNextStates` 驱动</b> —— 前端不自己判断
 * 「当前状态能不能做什么」, 而是让状态机 (`t_state_transition`) 单点决定。
 * 9 态状态机里每个目标状态的入边唯一, 所以「目标状态 → 动作」是一一对应的。
 */

import {
  App as AntApp,
  Button,
  Card,
  Descriptions,
  Input,
  Modal,
  Row,
  Col,
  Spin,
  Table,
  Tag,
} from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { OrderStatusTag } from '@/components/order/OrderStatusTag';
import { useAccess } from '@/hooks/useAccess';
import { orderService } from '@/services/order/orderService';
import type {
  OrderCargoItem,
  OrderDetail,
  OrderServiceItem,
} from '@/services/order/orderService';

const { TextArea } = Input;

/** 一个可执行动作。 */
interface OrderAction {
  /** 目标状态 —— 与 `allowedNextStates` 比对。 */
  to: string;
  label: string;
  /** 权限码 —— V42 的 sys_permission 里 seed 的 10 个 `order:*`。 */
  perm: string;
  /** 是否需要填原因 (退回 / 取消 / 作废 / 关闭)。 */
  needReason?: boolean;
  run: (id: number, reason?: string) => Promise<OrderDetail>;
}

/**
 * 目标状态 → 动作。9 态状态机里每个目标状态的入边唯一, 所以这个映射是无歧义的。
 *
 * <p>动作与后端 `OrderService` 的 9 个管理端状态转换方法一一对应
 * (submit / confirm / returnToDraft / cancel / voidOrder / startOperation /
 * deliver / settle / close)。
 */
const ACTIONS: OrderAction[] = [
  { to: 'PENDING_CONFIRM', label: '提交', perm: 'order:submit', run: (id) => orderService.submit(id) },
  { to: 'CONFIRMED', label: '确认', perm: 'order:confirm', run: (id) => orderService.confirm(id) },
  { to: 'DRAFT', label: '退回', perm: 'order:submit', needReason: true, run: (id, r) => orderService.returnToDraft(id, r!) },
  { to: 'IN_OPERATION', label: '开始操作', perm: 'order:operate', run: (id) => orderService.startOperation(id) },
  { to: 'DELIVERED', label: '交付', perm: 'order:operate', run: (id) => orderService.deliver(id) },
  { to: 'SETTLED', label: '结算', perm: 'order:settle', run: (id) => orderService.settle(id) },
  { to: 'CLOSED', label: '关闭归档', perm: 'order:close', needReason: true, run: (id, r) => orderService.close(id, r!) },
  { to: 'CANCELLED', label: '取消', perm: 'order:cancel', needReason: true, run: (id, r) => orderService.cancel(id, r!) },
  { to: 'VOID', label: '作废', perm: 'order:void', needReason: true, run: (id, r) => orderService.voidOrder(id, r!) },
];

/**
 * 原因最少 5 个字 —— 后端 `OrderConstants.REASON_MIN_LENGTH` (= 5)。
 */
const REASON_MIN = 5;
/**
 * 原因最多 255 个字 —— 后端 `OrderConstants.REASON_MAX_LENGTH` (= 255);
 * `OrderService` 的 javadoc 也写明「带 reason 的三个方法 (returnToDraft / cancel /
 * voidOrder) 与 close 的原因长度区间是 5..255 字」
 * (见 `OrderConstants.REASON_MIN_LENGTH` / `REASON_MAX_LENGTH`),
 * V42 的 cancel_reason / close_reason / void_reason 三列都是 VARCHAR(255)。
 *
 * <p>**上限由输入框的 `maxLength` 兜住** —— 原因那个 `TextArea` 带了
 * `maxLength={REASON_MAX}`, 用户根本输不进第 256 个字, 所以这里**不再**写
 * 一条永远不可达的长度校验分支 (写了也是死代码, 反而会让人以为上限没生效)。
 * 超长仍由后端兜底: `OrderErrorCode.REASON_TOO_LONG` (400_107)。
 */
const REASON_MAX = 255;

/** 可编辑状态 —— 与后端 `OrderConstants.EDITABLE_STATES` 一致。 */
const EDITABLE_STATUSES = ['DRAFT', 'PENDING_CONFIRM'];

export default function OrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const orderId = Number(id);
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const [detail, setDetail] = useState<OrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState<OrderAction | null>(null);
  const [reason, setReason] = useState('');
  const [acting, setActing] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setDetail(await orderService.get(orderId));
    } catch {
      antMessage.error('委托单详情加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId]);

  /** 不需要原因的动作 —— 直接执行。 */
  const runDirect = async (a: OrderAction) => {
    setActing(true);
    try {
      await a.run(orderId);
      antMessage.success(`${a.label}成功`);
      await load();
    } catch {
      // 后端错误已由 request.ts 的全局通知弹出, 这里不重复提示。
    } finally {
      setActing(false);
    }
  };

  /** 需要原因的动作 —— 弹框收集原因后执行。 */
  const submitReason = async () => {
    if (!pending) return;
    if (pending.needReason && reason.trim().length < REASON_MIN) {
      antMessage.error(`原因至少 ${REASON_MIN} 个字`);
      return;
    }
    setActing(true);
    try {
      await pending.run(orderId, reason.trim());
      antMessage.success(`${pending.label}成功`);
      setPending(null);
      setReason('');
      await load();
    } catch {
      // 后端错误已由 request.ts 的全局通知弹出, 这里不重复提示。
    } finally {
      setActing(false);
    }
  };

  if (loading) {
    return <Spin />;
  }
  if (!detail) {
    return <Card>委托单不存在</Card>;
  }

  const actions = ACTIONS.filter(
    (a) => detail.allowedNextStates.includes(a.to) && access.canRead(a.perm),
  );
  const canEdit =
    access.canRead('order:edit') && EDITABLE_STATUSES.includes(detail.orderStatus);

  return (
    <Card
      title={
        <span>
          {detail.orderNo} <OrderStatusTag value={detail.orderStatus} />
        </span>
      }
      extra={
        <span>
          {canEdit && (
            <Button
              style={{ marginRight: 8 }}
              onClick={() => nav(`/orders/${detail.id}/edit`)}
            >
              编辑
            </Button>
          )}
          {actions.map((a) => (
            <Button
              key={a.to}
              type={a.to === 'VOID' ? 'default' : 'primary'}
              danger={a.to === 'VOID'}
              loading={acting}
              style={{ marginLeft: 8 }}
              onClick={() => {
                if (a.needReason) {
                  setPending(a);
                  setReason('');
                } else {
                  void runDirect(a);
                }
              }}
            >
              {a.label}
            </Button>
          ))}
          <Button style={{ marginLeft: 8 }} onClick={() => nav('/orders')}>
            返回列表
          </Button>
        </span>
      }
    >
      <Descriptions bordered column={3} size="small">
        <Descriptions.Item label="客户 ID">{detail.customerId}</Descriptions.Item>
        <Descriptions.Item label="业务类型">{detail.businessType ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="贸易方向">{detail.tradeDirection ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="贸易条款">{detail.incoterms ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="服务类型">{detail.serviceType ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="结算币种">{detail.settlementCurrency ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="起运地">{detail.originAddress ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="目的地">{detail.destAddress ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="ETD / ETA">{`${detail.etd ?? '—'} / ${detail.eta ?? '—'}`}</Descriptions.Item>
        <Descriptions.Item label="重量 (kg)">{detail.totalWeightKg ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="体积 (m³)">{detail.totalVolumeCbm ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="件数">{detail.totalQty ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="箱量">{detail.containerQty ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="箱型">{detail.containerTypeCode ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="唛头">{detail.marksAndNos ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="货物描述" span={3}>
          {detail.cargoDescription ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label="超限">
          {detail.isOversized ? <Tag color="orange">是</Tag> : '否'}
        </Descriptions.Item>
        <Descriptions.Item label="含危险品">
          {detail.isDangerous ? <Tag color="red">是</Tag> : '否'}
        </Descriptions.Item>
        <Descriptions.Item label="温区">{detail.temperatureRange ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="备注" span={3}>
          {detail.remark ?? '—'}
        </Descriptions.Item>
      </Descriptions>

      <Card size="small" title="货物明细" style={{ marginTop: 16 }}>
        <Table<OrderCargoItem>
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={detail.cargo}
          columns={[
            { title: '品名 (中)', dataIndex: 'cargoNameCn' },
            { title: '品名 (英)', dataIndex: 'cargoNameEn' },
            { title: 'HS 编码', dataIndex: 'hsCode' },
            { title: '件数', dataIndex: 'pkgQty' },
            { title: '重量 (kg)', dataIndex: 'weightKg' },
            { title: '体积 (m³)', dataIndex: 'volumeCbm' },
            { title: '可堆叠', dataIndex: 'isStackable', render: (v: number | null) => (v === 1 ? '是' : '否') },
            { title: '危险品', dataIndex: 'isDangerous', render: (v: number | null) => (v === 1 ? '是' : '否') },
            { title: '品牌', dataIndex: 'brandName' },
          ]}
        />
      </Card>

      <Card size="small" title="服务项 / 费用" style={{ marginTop: 16 }}>
        <Table<OrderServiceItem>
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={detail.serviceItems}
          columns={[
            { title: '费用类型', dataIndex: 'feeType' },
            { title: '费用名称', dataIndex: 'feeName' },
            { title: '费用大类', dataIndex: 'feeCategory' },
            { title: '计费基准', dataIndex: 'calcBasis' },
            { title: '数量', dataIndex: 'qty' },
            { title: '单价', dataIndex: 'unitPrice' },
            { title: '金额', dataIndex: 'amount' },
            { title: '币种', dataIndex: 'currencyCode' },
            // 我方成本 —— 管理端是我方内部人员, 看得到成本没问题。
            { title: '我方成本', dataIndex: 'costAmount' },
          ]}
        />
      </Card>

      <Card size="small" title="流转记录" style={{ marginTop: 16 }}>
        <Row gutter={[16, 8]}>
          <Col span={8}>
            提交时间: <DateTimeColumn value={detail.submittedAt} />
          </Col>
          <Col span={8}>
            确认时间: <DateTimeColumn value={detail.confirmedAt} />
          </Col>
          <Col span={8}>
            交付时间: <DateTimeColumn value={detail.deliveredAt} />
          </Col>
          <Col span={8}>
            结算时间: <DateTimeColumn value={detail.settledAt} />
          </Col>
          <Col span={8}>
            关闭时间: <DateTimeColumn value={detail.closedAt} />
          </Col>
          <Col span={8}>
            创建人 / 修改人: {`${detail.createdBy ?? '—'} / ${detail.updatedBy ?? '—'}`}
          </Col>
        </Row>
        {(detail.cancelReason || detail.closeReason || detail.voidReason) && (
          <div style={{ marginTop: 8 }}>
            {detail.cancelReason && <div>取消原因: {detail.cancelReason}</div>}
            {detail.closeReason && <div>关闭原因: {detail.closeReason}</div>}
            {detail.voidReason && <div>作废原因: {detail.voidReason}</div>}
          </div>
        )}
      </Card>

      <Modal
        open={pending != null}
        title={`${pending?.label ?? ''} — 请填写原因`}
        onCancel={() => setPending(null)}
        onOk={submitReason}
        confirmLoading={acting}
        okText="确认"
        cancelText="取消"
      >
        <TextArea
          rows={4}
          value={reason}
          maxLength={REASON_MAX}
          onChange={(e) => setReason(e.target.value)}
          placeholder={`至少 ${REASON_MIN} 个字`}
        />
      </Modal>
    </Card>
  );
}
