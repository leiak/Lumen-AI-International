/**
 * SlaConfigForm — ProForm-based editor for an SLA config row
 * (Iteration 4.2 PR6).
 *
 * Wraps @ant-design/pro-components' `ProForm` with the canonical
 * SLA config shape:
 *   - severity (HIGH / MEDIUM / LOW)
 *   - level (L1 / L2 / L3)
 *   - responseMinutes (>= 1)
 *   - resolveMinutes (>= 1)
 *   - escalateToLevel (L2 / L3 / 不升级)
 *
 * Submits via the supplied `onSubmit(values)` callback; the caller is
 * responsible for the network call (e.g. `slaConfigService.create` /
 * `update`) and any field-level cross-validation (e.g. ensuring
 * `resolveMinutes >= responseMinutes`).
 */

import {
  ProForm,
  ProFormDigit,
  ProFormSelect,
} from '@ant-design/pro-components';

export interface SlaConfigFormValues {
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  level: 'L1' | 'L2' | 'L3';
  responseMinutes: number;
  resolveMinutes: number;
  escalateToLevel?: 'L2' | 'L3' | '不升级';
}

export interface SlaConfigFormProps {
  initialValues?: Partial<SlaConfigFormValues>;
  onSubmit: (values: SlaConfigFormValues) => Promise<void> | void;
}

const SEVERITY_OPTIONS = [
  { value: 'HIGH', label: '高' },
  { value: 'MEDIUM', label: '中' },
  { value: 'LOW', label: '低' },
];

const LEVEL_OPTIONS = [
  { value: 'L1', label: 'L1' },
  { value: 'L2', label: 'L2' },
  { value: 'L3', label: 'L3' },
];

const ESCALATE_OPTIONS = [
  { value: 'L2', label: 'L2' },
  { value: 'L3', label: 'L3' },
  { value: '不升级', label: '不升级' },
];

export default function SlaConfigForm({ initialValues, onSubmit }: SlaConfigFormProps) {
  return (
    <ProForm<SlaConfigFormValues>
      onFinish={onSubmit}
      initialValues={initialValues}
      data-testid="sla-config-form"
    >
      <ProFormSelect
        name="severity"
        label="严重度"
        options={SEVERITY_OPTIONS}
        rules={[{ required: true, message: '请选择严重度' }]}
      />
      <ProFormSelect
        name="level"
        label="Level"
        options={LEVEL_OPTIONS}
        rules={[{ required: true, message: '请选择 Level' }]}
      />
      <ProFormDigit
        name="responseMinutes"
        label="响应 (min)"
        min={1}
        rules={[{ required: true, message: '请输入响应分钟数' }]}
      />
      <ProFormDigit
        name="resolveMinutes"
        label="解决 (min)"
        min={1}
        rules={[{ required: true, message: '请输入解决分钟数' }]}
      />
      <ProFormSelect
        name="escalateToLevel"
        label="升级到"
        options={ESCALATE_OPTIONS}
      />
    </ProForm>
  );
}