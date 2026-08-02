import { useToastStore } from '../store/useToastStore';

// 右下角 toast. 只 1 条, 新的覆盖旧的。配合 useToastStore.show 使用。
const META: Record<string, { icon: string; cls: string }> = {
  success: { icon: '✓', cls: 'border-emerald-200 bg-white text-emerald-700' },
  error: { icon: '⚠', cls: 'border-rose-200 bg-white text-rose-700' },
  info: { icon: 'ℹ', cls: 'border-slate-200 bg-white text-slate-700' },
};

export function Toaster() {
  const toast = useToastStore((s) => s.toast);
  const dismiss = useToastStore((s) => s.dismiss);
  if (!toast) return null;
  const meta = META[toast.kind] ?? META.info;
  return (
    <div className="pointer-events-none fixed bottom-6 right-6 z-50">
      <div
        role="status"
        onClick={dismiss}
        className={`pointer-events-auto cursor-pointer rounded-lg border px-4 py-2 text-sm shadow-md ${meta.cls}`}
      >
        <span className="mr-1.5">{meta.icon}</span>
        <span>{toast.message}</span>
      </div>
    </div>
  );
}
