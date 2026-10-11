/**
 * Lumen Admin — WMS 审批阈值配置表单 (Iteration 3.0a PR3-T3.3).
 *
 * <p>每个租户至多一行阈值配置;通过 {@code GET/PUT /api/v1/wms/threshold} 读写.
 * 表单按业务类型分三组(入库 / 出库 / 调整),每组各暴露金额与数量两个维度;
 * {@code approvalRequiredWhenExceed} 由前端 {@code Switch} 转译为 0/1.
 *
 * <p>权限:
 * <ul>
 *   <li>读取配置需要 {@code wms:threshold:get}</li>
 *   <li>保存按钮在缺少 {@code wms:threshold:update} 时禁用</li>
 * </ul>
 */
import { Card, Form, InputNumber, Switch, Button, Space, App as AntApp } from 'antd';
import { useEffect, useState } from 'react';
import { request } from '@/services/request';
import { useAccess } from '@/hooks/useAccess';

export default function ThresholdForm() {
  const access = useAccess();
  const { message: msgApi } = AntApp.useApp();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    request
      .get<any>('/wms/threshold')
      .then((r) => {
        const v = r.data;
        form.setFieldsValue({
          inbAmount: v.inbAmountThreshold == null ? null : Number(v.inbAmountThreshold),
          inbQty: v.inbQtyThreshold == null ? null : Number(v.inbQtyThreshold),
          outAmount: v.outAmountThreshold == null ? null : Number(v.outAmountThreshold),
          outQty: v.outQtyThreshold == null ? null : Number(v.outQtyThreshold),
          adjAmount: v.adjAmountThreshold == null ? null : Number(v.adjAmountThreshold),
          adjQty: v.adjQtyThreshold == null ? null : Number(v.adjQtyThreshold),
          enabled: v.approvalRequiredWhenExceed === 1,
        });
      })
      .catch((err) => {
        msgApi.error(`加载阈值配置失败: ${err?.message ?? err}`);
      })
      .finally(() => setLoading(false));
  }, [form, msgApi]);

  const save = async () => {
    try {
      const v = await form.validateFields();
      await request.put('/wms/threshold', {
        inbAmountThreshold: v.inbAmount ?? 0,
        inbQtyThreshold: v.inbQty ?? 0,
        outAmountThreshold: v.outAmount ?? 0,
        outQtyThreshold: v.outQty ?? 0,
        adjAmountThreshold: v.adjAmount ?? 0,
        adjQtyThreshold: v.adjQty ?? 0,
        approvalRequiredWhenExceed: v.enabled ? 1 : 0,
      });
      msgApi.success('已保存');
    } catch (err) {
      msgApi.error(`保存失败: ${(err as Error)?.message ?? err}`);
    }
  };

  return (
    <Card title="审批阈值配置" loading={loading}>
      <Form form={form} layout="vertical" style={{ maxWidth: 720 }}>
        <Space size="large" wrap>
          <Form.Item label="入库金额阈值" name="inbAmount">
            <InputNumber min={0} step={100} style={{ width: 160 }} addonAfter="元" />
          </Form.Item>
          <Form.Item label="入库数量阈值" name="inbQty">
            <InputNumber min={0} step={1} style={{ width: 160 }} />
          </Form.Item>
        </Space>
        <Space size="large" wrap>
          <Form.Item label="出库金额阈值" name="outAmount">
            <InputNumber min={0} step={100} style={{ width: 160 }} addonAfter="元" />
          </Form.Item>
          <Form.Item label="出库数量阈值" name="outQty">
            <InputNumber min={0} step={1} style={{ width: 160 }} />
          </Form.Item>
        </Space>
        <Space size="large" wrap>
          <Form.Item label="调整金额阈值" name="adjAmount">
            <InputNumber min={0} step={100} style={{ width: 160 }} addonAfter="元" />
          </Form.Item>
          <Form.Item label="调整数量阈值" name="adjQty">
            <InputNumber min={0} step={1} style={{ width: 160 }} />
          </Form.Item>
        </Space>
        <Form.Item
          label="启用审批"
          name="enabled"
          valuePropName="checked"
          extra="关闭后所有单据免审直入下一节点"
        >
          <Switch />
        </Form.Item>
        <Form.Item>
          <Button
            type="primary"
            onClick={save}
            disabled={!access.canRead('wms:threshold:update')}
          >
            保存
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}
