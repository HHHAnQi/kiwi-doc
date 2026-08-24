import { useState } from 'react';
import { useChatStore } from '../store/useChatStore';
import { useDocStore } from '../store/useDocStore';
import { useUIStore } from '../store/useUIStore';
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
  const total = useDocStore((s) => s.total);
  const loading = useDocStore((s) => s.loading);
  const error = useDocStore((s) => s.error);
  const loadMore = useDocStore((s) => s.loadMore);

  const readyCount = docs.filter((d) => d.status === 'READY').length;
  const totalSize = docs.reduce((acc, d) => acc + d.size_bytes, 0);
  // DEV-B3: 仍有未加载文档时, footer 区显示 "加载更多 (剩余 N)"
  const remaining = Math.max(0, total - docs.length);

  // 会话管理: 归档列表 + 当前会话
  const archive = useChatStore((s) => s.archive);
  const currentId = useChatStore((s) => s.conversationId);
  const currentTitle = useChatStore((s) => (s.messages.length > 0 ? s.messages.find((m) => m.role === 'user')?.content.slice(0, 24) || '当前会话' : null));
  const switchTo = useChatStore((s) => s.switchTo);
  const deleteConversation = useChatStore((s) => s.deleteConversation);
  const newConversation = useChatStore((s) => s.newConversation);
  const entries = Object.entries(archive).sort((a, b) => b[1].updatedAt - a[1].updatedAt);
  if (currentTitle) entries.unshift([currentId, { title: currentTitle, messages: [], updatedAt: Date.now() }] as const);

  return (
    <aside className="flex h-full w-72 flex-col border-r border-slate-200 bg-white">
      <div className="border-b border-slate-200 p-4">
        <div className="mb-2 flex items-center gap-2">
          <span className="text-lg">📚</span>
          <h2 className="text-sm font-semibold text-slate-800">知识库</h2>
        </div>
        <UploadDropzone />
      </div>

      {/* 会话列表(多轮管理) */}
      {entries.length > 0 && (
        <div className="border-b border-slate-100">
          <div className="flex items-center justify-between px-4 py-2 text-xs text-slate-500">
            <span>💬 会话 ({entries.length})</span>
            <button
              onClick={newConversation}
              className="rounded border border-slate-300 px-1.5 py-0.5 text-[11px] text-slate-600 hover:bg-slate-100"
              title="新建会话"
            >
              ＋新建
            </button>
          </div>
          <div className="max-h-44 overflow-y-auto px-2 pb-2">
            {entries.map(([id, c]) => (
              <div
                key={id}
                className={`group flex items-center justify-between rounded px-2 py-1.5 text-xs ${
                  id === currentId ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-50'
                }`}
              >
                <button className="flex-1 truncate text-left" onClick={() => id !== currentId && switchTo(id)} title={c.title}>
                  {id === currentId ? '📍 ' : ''}
                  {c.title}
                </button>
                {id !== currentId && (
                  <button
                    className="ml-1 hidden text-slate-400 hover:text-red-500 group-hover:block"
                    onClick={() => deleteConversation(id)}
                    title="删除"
                  >
                    ✕
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

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

        {/* DEV-B3: 加载更多 — 当前 docs.length < total 时显示 */}
        {remaining > 0 && (
          <button
            type="button"
            onClick={loadMore}
            disabled={loading}
            className="mt-2 w-full rounded-md border border-slate-200 bg-white py-1.5 text-xs text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-50"
          >
            {loading ? '加载中...' : `加载更多 (剩 ${remaining} 个文档)`}
          </button>
        )}
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
  const activeMenuDocId = useUIStore((s) => s.activeMenuDocId);
  const toggleMenu = useUIStore((s) => s.toggleMenu);
  const closeMenu = useUIStore((s) => s.closeMenu);
  // ARCH-F4: 菜单开关从全局 UI store 派生 — 同一时刻只允许一个 DocItem 的菜单展开,
  // 替换旧版本 menuOpen useState (会被其它卡片 menuOpen 干扰同时显示)。
  const menuOpen = activeMenuDocId === doc.doc_id;
  const [busy, setBusy] = useState(false);

  const onDelete = async () => {
    if (
      !window.confirm(
        `确认软删文档「${doc.original_filename}」?\n(后端软删, 可由 DBA 恢复)`,
      )
    )
      return;
    closeMenu();
    setBusy(true);
    try {
      await remove(doc.doc_id);
    } finally {
      setBusy(false);
    }
  };

  const onRetry = async () => {
    closeMenu();
    setBusy(true);
    try {
      await retry(doc.doc_id);
    } finally {
      setBusy(false);
    }
  };

  return (
    // DEV-B1 修正: 旧版 button 内嵌 span role=button 是非法 HTML(interactive 内嵌
    // interactive), 浏览器会重写 DOM 导致 React 事件不一致。改用 div + 两个独立
    // 兄弟按钮(主区选中文档, ⋯ 触发菜单), 各自 stopPropagation 互不干扰。
    <li className="relative">
      <div
        role="button"
        tabIndex={0}
        onClick={onClick}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onClick();
          }
        }}
        aria-pressed={selected}
        title={doc.original_filename}
        className={cn(
          'w-full cursor-pointer rounded-md px-2 py-2 text-left transition-colors hover:bg-slate-50 disabled:opacity-60',
          selected && 'ring-2 ring-brand-500 bg-brand-50',
          busy && 'pointer-events-none opacity-60',
        )}
      >
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-xs text-slate-700" title={doc.original_filename}>
            {doc.original_filename}
          </span>
          {/* 操作菜单触发器: 与主按钮平级, 点击自行 stopPropagation, 不冒泡到父 onClick */}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              toggleMenu(doc.doc_id);
            }}
            aria-label="文档操作"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            title="删除 / 重试"
            className="shrink-0 rounded px-1 text-slate-400 hover:bg-slate-200 hover:text-slate-700"
          >
            ⋯
          </button>
        </div>
        <div className="mt-1 flex items-center justify-between gap-2">
          <StatusBadge status={doc.status} />
          <span className="text-[10px] text-slate-400">
            {doc.chunk_count} chunks · {formatRelativeTime(doc.updated_at)}
          </span>
        </div>
      </div>

      {menuOpen && (
        // 点容器外任意处关闭: 用 onBlur 不可靠(点菜单内按钮本身就会 blur),
        // 简化用 backdrop div 捕获外层 click 调用 closeMenu() 关全局菜单。
        <>
          <div
            className="fixed inset-0 z-10"
            onClick={(e) => {
              e.stopPropagation();
              closeMenu();
            }}
          />
          <div
            role="menu"
            className="absolute right-2 top-8 z-20 w-28 rounded-md border border-slate-200 bg-white py-1 text-xs shadow-lg"
          >
            {doc.status === 'FAILED' && (
              <button
                type="button"
                role="menuitem"
                onClick={(e) => {
                  e.stopPropagation();
                  closeMenu();
                  onRetry();
                }}
                disabled={busy}
                className="block w-full px-3 py-1.5 text-left text-slate-700 hover:bg-brand-50 hover:text-brand-700 disabled:opacity-50"
              >
                🔄 重试解析
              </button>
            )}
            <button
              type="button"
              role="menuitem"
              onClick={(e) => {
                e.stopPropagation();
                closeMenu();
                onDelete();
              }}
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
