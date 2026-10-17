/**
 * Lumen Admin — L2 approval action button group (Iteration 4.3.2 PR5).
 *
 * Shared by both the invoice list and the adjustment list — surfaces the
 * L2 approve / reject actions only when:
 *
 *   1. `currentStatus === 'PENDING_L2'`
 *   2. `l1ApproverId` is NOT the current user (separation of duties;
 *      the backend enforces this too, but the UI-side guard hides the
 *      button so the operator doesn't even try).
 *   3. The user holds the relevant `finance:*:l2_approve` permission
 *      (uses `useAccess` hook).
 *
 * L2 reject prompts for a non-empty comment via Antd `Modal.confirm`,
 * matching the L1 reject UX in `FinanceInvoiceList`.
 *
 * The component does NOT call any service directly — it just renders the
 * buttons and delegates to the parent via `onL2Approve` / `onL2Reject`
 * callbacks, so the same widget works for both invoice + adjustment
 * workflows even though they have different service namespaces.
 */

import { App as AntApp, Button, Space } from 'antd';
import { useState } from 'react';

import { useAccess } from '@/hooks/useAccess';

/**
 * The status / perm keys are intentionally typed as plain `string` instead
 * of pulling in the entity type so the component stays decoupled from
 * either `BillingInvoice` or `BillingAdjustment`. Both enums share the
 * same `PENDING_L2` value (see `InvoiceStatus` and `AdjustmentStatus`),
 * so callers pass `'PENDING_L2'` literally.
 */
export interface L2ApprovalButtonsProps {
  /** Current workflow status — must be 'PENDING_L2' for buttons to show. */
  currentStatus: string;
  /** L1 approver user id; current user must NOT match this. */
  l1ApproverId?: number | null;
  /** Current user id (from auth context). */
  currentUserId?: number;
  /** Permission required to surface the buttons (default = 'finance:invoice:l2_approve'). */
  perm?: string;
  onL2Approve: () => void | Promise<void>;
  onL2Reject: (comment: string) => void | Promise<void>;
}

export default function L2ApprovalButtons({
  currentStatus,
  l1ApproverId,
  currentUserId,
  perm = 'finance:invoice:l2_approve',
  onL2Approve,
  onL2Reject,
}: L2ApprovalButtonsProps) {
  const { message: antMessage, modal } = AntApp.useApp();
  const access = useAccess();
  const [busy, setBusy] = useState(false);

  // Guard 1 — wrong workflow state: render nothing.
  if (currentStatus !== 'PENDING_L2') return null;
  // Guard 2 — separation of duties (UI-side mirror of BE check).
  if (l1ApproverId != null && currentUserId != null && l1ApproverId === currentUserId) {
    return null;
  }
  // Guard 3 — permission.
  if (!access.canRead(perm)) return null;

  const handleApprove = async () => {
    setBusy(true);
    try {
      await onL2Approve();
    } catch {
      /* parent or service surfaces the error */
    } finally {
      setBusy(false);
    }
  };

  const handleReject = () => {
    let reason = '';
    modal.confirm({
      title: 'L2 驳回原因',
      content: (
        <textarea
          rows={3}
          style={{ width: '100%', padding: 8, marginTop: 8 }}
          placeholder="请填写驳回原因 (必填)"
          onChange={(e) => {
            reason = e.target.value;
          }}
        />
      ),
      okText: '确认驳回',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        if (!reason.trim()) {
          antMessage.error('驳回原因不能为空');
          throw new Error('reason-required');
        }
        setBusy(true);
        try {
          await onL2Reject(reason.trim());
        } catch {
          /* handled */
        } finally {
          setBusy(false);
        }
      },
    });
  };

  return (
    <Space size="small">
      <Button
        key="l2-approve"
        type="link"
        size="small"
        disabled={busy}
        onClick={handleApprove}
      >
        L2 通过
      </Button>
      <Button
        key="l2-reject"
        type="link"
        size="small"
        danger
        disabled={busy}
        onClick={handleReject}
      >
        L2 驳回
      </Button>
    </Space>
  );
}