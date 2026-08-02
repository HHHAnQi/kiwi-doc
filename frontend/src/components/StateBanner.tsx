import type { StateHint } from '../types/api';

const META: Record<StateHint, { icon: string; label: string; cls: string }> = {
  OK: { icon: '', label: '', cls: '' }, // OK 不显示 banner
  EMPTY_KB: {
    icon: '📭',
    label: '知识库还没有文档',
    cls: 'border-amber-200 bg-amber-50 text-amber-800',
  },
  NO_RECALL: {
    icon: '🔍',
    label: '没找到相关文档',
    cls: 'border-slate-200 bg-slate-50 text-slate-700',
  },
  LLM_DEGRADED: {
    icon: '⚠️',
    label: '模型暂不可用, 请稍后重试',
    cls: 'border-rose-200 bg-rose-50 text-rose-800',
  },
};

export function StateBanner({ state }: { state: StateHint }) {
  const meta = META[state];
  if (!meta.icon) return null;
  return (
    <div className={`flex items-center gap-2 rounded-md border px-3 py-2 text-sm ${meta.cls}`}>
      <span>{meta.icon}</span>
      <span>{meta.label}</span>
    </div>
  );
}
