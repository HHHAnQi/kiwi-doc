import { Component, type ErrorInfo, type ReactNode } from 'react';

// 全局错误边界: 子组件抛错(如 react-markdown 遇畸形输入)时降级展示,
// 避免整页白屏。trace_id 若能从 props/上下文取出就一并显示, 否则只给通用文案。
interface Props {
  children: ReactNode;
}
interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 上报钩子: 未来接 Sentry/Langfuse 时, 这里 console.error 可改成 transport。
    // 暂时 console, 让开发者 F12 能看到完整 stack, 不污染用户界面。
    console.error('[ErrorBoundary]', error, info.componentStack);
  }

  private reset = () => this.setState({ error: null });

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-3 bg-slate-50 p-6 text-center">
        <div className="text-4xl">😵</div>
        <h2 className="text-base font-semibold text-slate-800">
          页面出了点问题
        </h2>
        <p className="max-w-md text-xs text-slate-500">
          可能是网络抖动或内容解析失败。刷新通常能解决; 若持续出现请把下方错误信息反馈给管理员。
        </p>
        <pre className="max-w-md overflow-auto rounded bg-slate-200/60 px-3 py-2 text-[10px] text-slate-700">
          {error.message || error.name}
        </pre>
        <div className="flex gap-2">
          <button
            onClick={this.reset}
            className="rounded-lg bg-brand-600 px-4 py-2 text-xs text-white hover:bg-brand-700"
          >
            重试
          </button>
          <button
            onClick={() => window.location.reload()}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-xs text-slate-700 hover:bg-slate-50"
          >
            刷新整页
          </button>
        </div>
      </div>
    );
  }
}
