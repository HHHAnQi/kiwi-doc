import { cn } from '../lib/cn';
import type { DocumentStatus } from '../types/api';

interface Props {
  status: DocumentStatus;
}

const STATUS_META: Record<DocumentStatus, { icon: string; cls: string; label: string }> = {
  UPLOADED: { icon: '📥', cls: 'bg-slate-100 text-slate-600', label: '已上传' },
  PARSING: { icon: '⏳', cls: 'bg-blue-50 text-blue-600 animate-pulse', label: '解析中' },
  READY: { icon: '✅', cls: 'bg-emerald-50 text-emerald-700', label: '就绪' },
  INDEXED: { icon: '✅', cls: 'bg-emerald-50 text-emerald-700', label: '就绪' },
  FAILED: { icon: '❌', cls: 'bg-rose-50 text-rose-700', label: '失败' },
  DELETED: { icon: '🗑️', cls: 'bg-slate-100 text-slate-400', label: '已删' },
};

export function StatusBadge({ status }: Props) {
  const meta = STATUS_META[status] ?? STATUS_META.UPLOADED;
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
        meta.cls,
      )}
    >
      <span>{meta.icon}</span>
      <span>{meta.label}</span>
    </span>
  );
}
