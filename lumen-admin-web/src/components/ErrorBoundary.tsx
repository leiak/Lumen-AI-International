/**
 * ErrorBoundary — class component that catches render-time errors.
 *
 * level="app"   — top-level fallback, full-page "应用异常" + reload button.
 *                 Use ONCE in App.tsx, above AuthProvider.
 * level="page"  — single-page fallback, "本页面加载失败" + retry button.
 *                 Wrap any feature page that may throw during render.
 * level="form"  — inline fallback for form sections.
 *                 Sized to live inside a form, not full page.
 *
 * Usage:
 *   <ErrorBoundary level="app">
 *     <App />
 *   </ErrorBoundary>
 *
 *   <ErrorBoundary level="form" onError={logError}>
 *     <ComplexForm />
 *   </ErrorBoundary>
 */

import { Alert, Button, Result } from 'antd';
import { Component, type ErrorInfo, type ReactNode } from 'react';

export type ErrorBoundaryLevel = 'app' | 'page' | 'form';

export interface ErrorBoundaryProps {
  level: ErrorBoundaryLevel;
  children: ReactNode;
  /** Called when an error is caught. Use for logging. */
  onError?: (error: Error, info: ErrorInfo) => void;
  /** Custom fallback (overrides the level default). */
  fallback?: (error: Error, reset: () => void) => ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    this.props.onError?.(error, info);
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', this.props.level, error, info);
  }

  private reset = (): void => {
    this.setState({ error: null });
  };

  override render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    if (this.props.fallback) {
      return this.props.fallback(error, this.reset);
    }

    switch (this.props.level) {
      case 'app':
        return (
          <Result
            status="500"
            title="应用异常"
            subTitle="请联系管理员或刷新页面重试"
            extra={
              <Button type="primary" onClick={() => window.location.reload()}>
                刷新页面
              </Button>
            }
          />
        );
      case 'page':
        return (
          <Result
            status="warning"
            title="本页面加载失败"
            subTitle={error.message || '未知错误'}
            extra={
              <Button type="primary" onClick={this.reset}>
                重试
              </Button>
            }
          />
        );
      case 'form':
        return (
          <Alert
            type="error"
            showIcon
            message="表单加载失败"
            description={error.message || '请刷新或联系管理员'}
            action={
              <Button size="small" onClick={this.reset}>
                重试
              </Button>
            }
          />
        );
      default: {
        const _exhaustive: never = this.props.level;
        return _exhaustive;
      }
    }
  }
}