/**
 * 4.6 — 门户端货运委托单详情。
 *
 * 客户能做的动作只有两个: 确认 (PENDING_CONFIRM → CONFIRMED) 与
 * 取消 (PENDING_CONFIRM → CANCELLED)。按钮显隐由后端返回的
 * `allowedNextStates` 决定 —— 前端不自己判断状态机。
 */

import React, { useEffect, useState } from 'react';
import {
  Button,
  Card,
  Descriptions,
  Input,
  Modal,
  Spin,
  Table,
  message,
} from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { portalFreightOrderService } from '../../services/portalFreightOrderService';
import type {
  FreightOrderCargo,
  FreightOrderDetail,
  FreightOrderServiceItem,
} from '../../types/freightOrder';
import FreightOrderStatusTag from '../../components/FreightOrderStatusTag';
import { zhCN } from '../../locale/zh-CN';

const { TextArea } = Input;

/** 原因最少 5 个字 —— 与后端 `OrderConstants.REASON_MIN_LENGTH` 一致。 */
const REASON_MIN = 5;
/** 原因最多 255 个字 —— 与后端 `OrderConstants.REASON_MAX_LENGTH` 一致
 *  (V42 的 cancel_reason / close_reason / void_reason 三列都是 VARCHAR(255);
 *   超长后端抛 REASON_TOO_LONG 400_107, 前端先挡住, 免得用户填了半天气泡)。 */
const REASON_MAX = 255;

const FreightOrderDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const orderId = Number(id);
  const navigate = useNavigate();
  const [detail, setDetail] = useState<FreightOrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [acting, setActing] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setDetail(await portalFreightOrderService.detail(orderId));
    } catch {
      message.error('委托单加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [orderId]);

  const onConfirm = async () => {
    setActing(true);
    try {
      await portalFreightOrderService.confirm(orderId);
      message.success('委托单已确认');
      await load();
    } catch {
      // http.ts 不弹通知, 这里给一条兜底提示。
      message.error('确认失败, 请稍后重试');
    } finally {
      setActing(false);
    }
  };

  const onCancel = async () => {
    if (reason.trim().length < REASON_MIN) {
      message.error(`取消原因至少 ${REASON_MIN} 个字`);
      return;
    }
    if (reason.trim().length > REASON_MAX) {
      message.error(`取消原因最多 ${REASON_MAX} 个字`);
      return;
    }
    setActing(true);
    try {
      await portalFreightOrderService.cancel(orderId, reason.trim());
      message.success('委托单已取消');
      setCancelOpen(false);
      setReason('');
      await load();
    } catch {
      message.error('取消失败, 请稍后重试');
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

  const canConfirm = detail.allowedNextStates.includes('CONFIRMED');
  const canCancel = detail.allowedNextStates.includes('CANCELLED');

  return (
    <Card
      title={
        <span>
          {detail.orderNo} <FreightOrderStatusTag status={detail.orderStatus} />
        </span>
      }
      extra={
        <span>
          {canConfirm && (
            <Button type="primary" loading={acting} onClick={onConfirm}>
              {zhCN.freightOrder.confirm}
            </Button>
          )}
          {canCancel && (
            <Button
              danger
              style={{ marginLeft: 8 }}
              onClick={() => setCancelOpen(true)}
            >
              {zhCN.freightOrder.cancel}
            </Button>
          )}
          <Button style={{ marginLeft: 8 }} onClick={() => navigate('/freight-orders')}>
            {zhCN.freightOrder.backToList}
          </Button>
        </span>
      }
    >
      <Descriptions bordered column={2} size="small">
        <Descriptions.Item label={zhCN.freightOrder.column.businessType}>
          {detail.businessType ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.tradeDirection}>
          {detail.tradeDirection ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.incoterms}>
          {detail.incoterms ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.serviceType}>
          {detail.serviceType ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.origin}>
          {detail.originAddress ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.destination}>
          {detail.destAddress ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.etd}>
          {detail.etd ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.eta}>
          {detail.eta ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.weight}>
          {detail.totalWeightKg ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.volume}>
          {detail.totalVolumeCbm ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.container}>
          {detail.containerQty ?? '—'} {detail.containerTypeCode ?? ''}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.currency}>
          {detail.settlementCurrency ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.marks} span={2}>
          {detail.marksAndNos ?? '—'}
        </Descriptions.Item>
        <Descriptions.Item label={zhCN.freightOrder.column.description} span={2}>
          {detail.cargoDescription ?? '—'}
        </Descriptions.Item>
      </Descriptions>

      <Card size="small" title={zhCN.freightOrder.cargoTitle} style={{ marginTop: 16 }}>
        <Table<FreightOrderCargo>
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={detail.cargo}
          columns={[
            { title: '品名', dataIndex: 'cargoNameCn' },
            { title: 'HS 编码', dataIndex: 'hsCode' },
            { title: '件数', dataIndex: 'pkgQty' },
            { title: '重量 (kg)', dataIndex: 'weightKg' },
            { title: '体积 (m³)', dataIndex: 'volumeCbm' },
          ]}
        />
      </Card>

      <Card size="small" title={zhCN.freightOrder.serviceTitle} style={{ marginTop: 16 }}>
        <Table<FreightOrderServiceItem>
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={detail.serviceItems}
          columns={[
            { title: zhCN.freightOrder.column.feeType, dataIndex: 'feeType' },
            { title: zhCN.freightOrder.column.feeName, dataIndex: 'feeName' },
            { title: zhCN.freightOrder.column.calcBasis, dataIndex: 'calcBasis' },
            { title: '数量', dataIndex: 'qty' },
            { title: '单价', dataIndex: 'unitPrice' },
            { title: '金额', dataIndex: 'amount' },
            { title: '币种', dataIndex: 'currencyCode' },
          ]}
        />
      </Card>

      <Modal
        open={cancelOpen}
        title={zhCN.freightOrder.cancelTitle}
        onCancel={() => setCancelOpen(false)}
        onOk={onCancel}
        confirmLoading={acting}
        okText="确认"
        cancelText="再想想"
      >
        <TextArea
          rows={4}
          value={reason}
          maxLength={REASON_MAX}
          onChange={(e) => setReason(e.target.value)}
          placeholder={zhCN.freightOrder.cancelReasonPlaceholder}
        />
      </Modal>
    </Card>
  );
};

export default FreightOrderDetailPage;
