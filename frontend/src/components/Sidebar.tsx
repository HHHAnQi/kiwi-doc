import { useState } from 'react';
import { useDocStore } from '../store/useDocStore';
import { UploadDropzone } from './UploadDropzone';
import { StatusBadge } from './StatusBadge';
import { formatBytes, formatRelativeTime } from '../lib/format';
import type { DocumentSummary } from '../types/api';
import { cn } from '../lib/cn';

interface Props {
  // 点击文档时回调(把 docId 填到 chat 限定的 input)
  onPickDoc?: (docId: number | null) => void;
  selectedDocId?: number | null;
}

export function Sidebar({ onPickDoc, selectedDocId }: Props) {
  const docs = useDocStore((s) => s.docs);
  const loading = useDocStore((s) => s.loading);
  const error = useDocStore((s) => s.error);

  const readyCount = docs.filter((d) => d.status === 'READY').length;
  const totalSize = docs.reduce((acc, d) => acc + d.size_bytes, 0);

  return (
    <aside className="flex h-full w-72 flex-col border-r border-slate-200 bg-white">
      <div className="border-b border-slate-200 p-4">
        <div className="mb-2 flex items-center gap-2">
          <span className="text-lg">📚</span>
          <h2 className="text-sm font-semibold text-slate-800">知识库</h2>
        </div>
        <UploadDropzone />
      </div>

      <div className="flex items-center justify-between px-4 py-2 text-xs text-slate-500 border-b border-slate-100">
        <span>
          {readyCount}/{docs.length} 就绪
        </span>
        <span>{formatBytes(totalSize)}</span>
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        {loading && docs.length === 0 && (
          <div className="px-2 py-4 text-center text-xs text-slate-400">加载中...</div>
        )}
        {error && (
          <div className="m-2 rounded border border-rose-200 bg-rose-50 p-2 text-xs text-rose-600">
            {error}
          </div>
        )}
        {!loading && docs.length === 0 && !error && (
          <div className="px-2 py-4 text-center text-xs text-slate-400">
            还没有文档
            <br />
            拖一个 PDF/MD 上来试试
          </div>
        )}
        <ul className="space-y-1">
          {docs.map((d) => (
            <DocItem
              key={d.doc_id}
              doc={d}
              selected={selectedDocId === d.doc_id}
              onClick={() =>
                onPickDoc?.(selectedDocId === d.doc_id ? null : d.doc_id)
              }
            />
          ))}
        </ul>
      </div>

      <div className="border-t border-slate-100 p-3 text-center text-[10px] text-slate-400">
        点击文档 = 限定该文档检索 · 再点取消
      </div>
    </aside>
  );
}

function DocItem({
  doc,
  selected,
  onClick,
}: {
  doc: DocumentSummary;
  selected: boolean;
  onClick: () => void;
}) {
  // …菜单: 点击 ⋯ 切换; 操作本身通过 store 触发, 完成后 load 会刷新 docs.
  // retry 仅 FAILED 可用(后端约束); delete 任意状态都可软删.
  const remove = useDocStore((s) => s.remove);
  const retry = useDocStore((s) => s.retry);
  const [menuOpen, setMenuOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const stop = (e: React.SyntheticEvent) => {
    // 阻止触发父级 onClick (选中文档), 否则点 ⋯ 会意外切换 selected
    e.stopPropagation();
  };

  const onDelete = async () => {
    if (
      !window.confirm(
        `确认软删文档「${doc.original_filename}」?\n(后端软删, 可由 DBA 恢复)`,
      )
    )
      return;
    setMenuOpen(false);
    setBusy(true);
    try {
      await remove(doc.doc_id);
    } finally {
      setBusy(false);
    }
  };

  const onRetry = async () => {
    setMenuOpen(false);
    setBusy(true);
    try {
      await retry(doc.doc_id);
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="relative">
      <button
        onClick={onClick}
        disabled={busy}
        className={cn(
          'w-full rounded-md px-2 py-2 text-left transition-colors hover:bg-slate-50 disabled:opacity-60',
          selected && 'ring-2 ring-brand-500 bg-brand-50',
        )}
      >
        <div className="flex items-center justify-between gap-2">
          <span
            className="truncate text-xs text-slate-700"
            title={doc.original_filename}
          >
            {doc.original_filename}
          </span>
          {/* 操作菜单触发器: 阻止冒泡到选中文档 onClick */}
          <span
            role="button"
            tabIndex={0}
            onClick={(e) => {
              stop(e);
              setMenuOpen((v) => !v);
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                stop(e);
                setMenuOpen((v) => !v);
              }
            }}
            className="shrink-0 rounded px-1 text-slate-400 hover:bg-slate-200 hover:text-slate-700"
            aria-label="文档操作"
            title="删除 / 重试"
          >
            ⋯
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between gap-2">
          <StatusBadge status={doc.status} />
          <span className="text-[10px] text-slate-400">
            {doc.chunk_count} chunks · {formatRelativeTime(doc.updated_at)}
          </span>
        </div>
      </button>

      {menuOpen && (
        // 点容器外任意处关闭: 用 onBlur 不可靠(点菜单内按钮本身就会 blur),
        // 简化用 backdrop div 捕获外层 click 关菜单。
        <>
          <div
            className="fixed inset-0 z-10"
            onClick={(e) => {
              stop(e);
              setMenuOpen(false);
            }}
          />
          <div
            className="absolute right-2 top-8 z-20 w-28 rounded-md border border-slate-200 bg-white py-1 text-xs shadow-lg"
            onClick={stop}
          >
            {doc.status === 'FAILED' && (
              <button
                onClick={onRetry}
                disabled={busy}
                className="block w-full px-3 py-1.5 text-left text-slate-700 hover:bg-brand-50 hover:text-brand-700 disabled:opacity-50"
              >
                🔄 重试解析
              </button>
            )}
            <button
              onClick={onDelete}
              disabled={busy}
              className="block w-full px-3 py-1.5 text-left text-rose-600 hover:bg-rose-50 disabled:opacity-50"
            >
              🗑 删除
            </button>
          </div>
        </>
      )}
    </li>
  );
}
