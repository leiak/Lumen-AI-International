import { Component, ErrorInfo, ReactNode } from 'react';
import { Button, Result } from 'antd';

export interface ErrorBoundaryProps {
  /** Fallback severity: "app" for top-level, "page" for per-route. */
  level: 'app' | 'page';
  children: ReactNode;
  /** Optional side-channel for logging. */
  onError?: (error: Error, info: ErrorInfo) => void;
}

interface State {
  hasError: boolean;
  error?: Error;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, State> {
  override state: State = { hasError: false };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    this.props.onError?.(error, info);
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', this.props.level, error, info);
  }

  reset = (): void => {
    this.setState({ hasError: false, error: undefined });
  };

  override render(): ReactNode {
    if (!this.state.hasError) return this.props.children;

    if (this.props.level === 'app') {
      return (
        <Result
          status="500"
          title="应用异常"
          subTitle="请联系管理员或刷新页面重试"
          extra={
            <Button type="primary" onClick={() => location.reload()}>
              刷新
            </Button>
          }
        />
      );
    }

    return (
      <Result
        status="warning"
        title="本页面加载失败"
        subTitle={this.state.error?.message}
        extra={
          <Button onClick={this.reset}>重试</Button>
        }
      />
    );
  }
}