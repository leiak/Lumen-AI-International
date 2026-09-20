import { Navigate, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import { useAuth } from './useAuth';

export interface ProtectedRouteProps {
  children: React.ReactNode;
  perm?: string; // optional specific permission required
}

/**
 * Wrap any route subtree that requires authentication.
 *
 * Behavior:
 *   - isLoading=true  → render loading placeholder
 *   - not auth        → redirect to /login?from=<original path+search>
 *   - perm specified  → if !user.perms.includes(perm), redirect to /403
 *   - otherwise       → render children
 *
 * NOTE on loading fallback: this inline Spin is a temporary placeholder.
 * Task 1.6 (LoadingSkeleton) will provide a richer skeleton that we will
 * swap in here. Do NOT add more elaborate loading UI in this task.
 */
export function ProtectedRoute({ children, perm }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, user } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 120 }}>
        <Spin size="large" />
      </div>
    );
  }
  if (!isAuthenticated) {
    const from = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?from=${from}`} replace />;
  }
  if (perm && !user!.perms.includes(perm)) {
    return <Navigate to="/403" replace />;
  }
  return <>{children}</>;
}