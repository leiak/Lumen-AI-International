/**
 * Lumen Admin — StateTransitionPanel (PR5-T5.3, Iteration 3.0a).
 *
 * Generic status-machine action bar: takes the current note status and a
 * declarative `transitions` array, renders only the buttons whose
 * `fromStatus` matches AND whose `permCode` is granted. Each button is
 * wrapped in <Popconfirm> only when `confirmTitle` is provided — actions
 * that need a modal (e.g. reject reason) opt out of the popconfirm and
 * call back to the consumer's own `onAction` handler.
 *
 * Usage:
 *   <StateTransitionPanel
 *     status={note.status}
 *     transitions={[
 *       { key: 'submit',  label: '提交', fromStatus: ['DRAFT'], permCode: 'wms:inbound:submit' },
 *       { key: 'approve', label: '审批', fromStatus: ['PENDING_APPROVAL'], permCode: 'wms:inbound:approve', confirmTitle: '确认审批?' },
 *       { key: 'cancel',  label: '取消', fromStatus: ['DRAFT','PENDING_APPROVAL'], permCode: 'wms:inbound:cancel', confirmTitle: '确认取消?', danger: true },
 *     ]}
 *     onAction={(k) => handleAction(k)}
 *     loading={acting}
 *   />
 */

import { Button, Popconfirm, Space } from 'antd';

import { useAccess } from '@/hooks/useAccess';

export type ActionKey = string;

export interface TransitionConfig {
  /** Stable id (e.g. 'submit', 'approve', 'cancel'). */
  key: ActionKey;
  /** Visible button text. */
  label: string;
  /** Statuses where the action is enabled. */
  fromStatus: string[];
  /** Perm gate — button hidden if `useAccess().canRead(permCode)` is false. */
  permCode: string;
  /** Wrap in <Popconfirm>; omit for actions that pop a modal first. */
  confirmTitle?: string;
  /** Render as danger (red) button. Typical for `cancel`. */
  danger?: boolean;
  /** Override the OK button copy inside the Popconfirm. */
  okText?: string;
  /** Override the Cancel button copy inside the Popconfirm. */
  cancelText?: string;
  /** Show button as primary (default). Pass false for a secondary button. */
  primary?: boolean;
}

export interface StateTransitionPanelProps {
  status: string;
  transitions: TransitionConfig[];
  /** Called with the transition key when the user confirms. */
  onAction: (key: ActionKey) => void | Promise<void>;
  /** Disable every button while an action is in flight. */
  loading?: boolean;
  /** Spacing wrap / direction defaults — leave as default unless layout demands. */
  wrap?: boolean;
}

export function StateTransitionPanel({
  status,
  transitions,
  onAction,
  loading,
  wrap = true,
}: StateTransitionPanelProps) {
  const access = useAccess();

  // Filter transitions: visible iff
  //   1. the current status matches fromStatus
  //   2. the user holds the perm.
  const visible = transitions.filter(
    (t) => t.fromStatus.includes(status) && access.canRead(t.permCode),
  );

  if (visible.length === 0) return null;

  return (
    <Space style={{ marginTop: 16 }} wrap={wrap}>
      {visible.map((t) => {
        const buttonType = t.primary === false ? 'default' : 'primary';
        const btn = (
          <Button
            key={t.key}
            type={t.danger ? 'default' : buttonType}
            danger={t.danger}
            loading={loading}
            onClick={() => {
              if (!t.confirmTitle) void onAction(t.key);
            }}
          >
            {t.label}
          </Button>
        );
        if (!t.confirmTitle) return btn;
        return (
          <Popconfirm
            key={t.key}
            title={t.confirmTitle}
            okText={t.okText ?? t.label}
            cancelText={t.cancelText ?? '取消'}
            onConfirm={() => onAction(t.key)}
          >
            {btn}
          </Popconfirm>
        );
      })}
    </Space>
  );
}
