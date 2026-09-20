import { Navigate, useLocation } from 'react-router-dom';
import { LoadingSkeleton } from '@/components/LoadingSkeleton';
import { useAuth } from './useAuth';

export interface ProtectedRouteProps {
  children: React.ReactNode;
  perm?: string; // optional specific permission required
}

/**
 * Wrap any route subtree that requires authentication.
 *
 * Behavior:
 *   - isLoading=true  → render loading placeholder (LoadingSkeleton type="page")
 *   - not auth        → redirect to /login?from=<original path+search>
 *   - perm specified  → if !user.perms.includes(perm), redirect to /403
 *   - otherwise       → render children
 */
export function ProtectedRoute({ children, perm }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, user } = useAuth();
  const location = useLocation();

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', padding: 120 }}>
        <LoadingSkeleton type="page" />
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