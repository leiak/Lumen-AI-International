import { Skeleton, Spin } from 'antd';

export interface LoadingSkeletonProps {
  type: 'page' | 'table' | 'inline';
  rows?: number;
}

/**
 * Lumen Admin — Loading skeleton.
 *
 * Three variants:
 *   - `page`  — full-page block skeleton (12 rows by default), used as Suspense fallback
 *   - `table` — tabular skeleton (8 rows by default), used as ProTable loading fallback
 *   - `inline` — small Spin, used for inline button/icon loading
 */
export function LoadingSkeleton({ type, rows = 8 }: LoadingSkeletonProps) {
  if (type === 'inline') return <Spin size="small" />;
  if (type === 'table') return <Skeleton active paragraph={{ rows }} />;
  return <Skeleton active paragraph={{ rows: 12 }} title />;
}
