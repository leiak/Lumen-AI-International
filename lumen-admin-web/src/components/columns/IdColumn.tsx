/**
 * IdColumn — display a long ID with copy-to-clipboard.
 *
 * Renders the ID as a truncated string with an icon button that copies
 * the full ID to the clipboard. Shows a tooltip on hover.
 *
 * Use in any table column where IDs are too long to display fully.
 */

import { Button, message, Tooltip } from 'antd';
import { CopyOutlined } from '@ant-design/icons';

export interface IdColumnProps {
  value: number | string | null | undefined;
  /** Truncation length; default 8. */
  truncate?: number;
}

export function IdColumn({ value, truncate = 8 }: IdColumnProps) {
  if (value == null || value === '') return <span>—</span>;
  const full = String(value);
  const display = full.length > truncate ? `${full.slice(0, truncate)}…` : full;
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(full);
      message.success(`已复制: ${full}`);
    } catch {
      message.error('复制失败');
    }
  };
  return (
    <span>
      <span style={{ fontFamily: 'monospace', marginRight: 8 }}>{display}</span>
      <Tooltip title="复制完整 ID">
        <Button
          type="text"
          size="small"
          icon={<CopyOutlined />}
          onClick={handleCopy}
          aria-label="复制 ID"
        />
      </Tooltip>
    </span>
  );
}