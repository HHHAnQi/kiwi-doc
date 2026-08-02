import { useState } from 'react';
import type { Citation } from '../types/api';
import type { ChunkDetail } from '../types/api';
import { getChunkDetail, getChunkNeighbors } from '../api/chunks';

// PM-F1: 引用卡片不再只显示孤立的 "文档 #N", 而是按需拉详情拿到真实 filename
//   (e.g. "nacos-use-nacos-with-spring-boot.md"), 解决用户"97 是哪个看不懂"的痛点。
// ARCH-F5: 同一次展开并发 /chunks/{id}/neighbors(prev/next), 让用户能扫一眼原文上下文,
//   最大化复用后端早已暴露但前端零用的 ChunkController 接口。
//
// 容错: 任一 fetch 失败(网络/后端偶发)都静默降级, 不影响主信息(snippet + section) 的展示,
//   也避免点击展开触发整屏红区。错误只在副区显示一行小字。
interface Props {
  index: number; // 引用序号 (1-based, 与答案里 [n] 对齐)
  citation: Citation;
}

interface DetailState {
  filename: string | null;
  prev: ChunkDetail | null;
  next: ChunkDetail | null;
  loading: boolean;
  error: string | null;
}

export function CitationCard({ index, citation }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [detail, setDetail] = useState<DetailState>({
    filename: null,
    prev: null,
    next: null,
    loading: false,
    error: null,
  });

  const section = citation.section_path?.length > 0
    ? citation.section_path.join(' › ')
    : null;

  const onToggle = async () => {
    const next = !expanded;
    setExpanded(next);
    if (!next || detail.filename || detail.loading) return;
    // 首次展开: 并发拉详情 + 邻居。两路独立 catch, 任一失败不阻塞另一路。
    setDetail((s) => ({ ...s, loading: true, error: null }));
    const [detailP, neighborsP] = await Promise.allSettled([
      getChunkDetail(citation.chunk_id),
      getChunkNeighbors(citation.chunk_id, 'both'),
    ]);
    setDetail(() => {
      const filename =
        detailP.status === 'fulfilled' ? detailP.value.document_filename : null;
      const prev =
        neighborsP.status === 'fulfilled' ? neighborsP.value.prev : null;
      const nextN =
        neighborsP.status === 'fulfilled' ? neighborsP.value.next : null;
      const errors: string[] = [];
      if (detailP.status === 'rejected') errors.push('详情');
      if (neighborsP.status === 'rejected') errors.push('上下文');
      return {
        filename,
        prev,
        next: nextN,
        loading: false,
        error: errors.length > 0 ? `${errors.join('和')}加载失败` : null,
      };
    });
  };

  // 主标题行: 优先用拉到的真实 filename, 否则 fallback "文档 #id"
  const title = detail.filename || `文档 #${citation.doc_id}`;

  return (
    <div className="rounded-md border border-slate-200 bg-white">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        className="block w-full px-3 py-2 text-left transition-colors hover:border-brand-300 hover:bg-brand-50/30"
      >
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand-600 text-[10px] font-bold text-white">
              {index}
            </span>
            <span className="truncate text-xs text-slate-700" title={title}>
              {title}
            </span>
            {citation.page > 0 && (
              <span className="shrink-0 text-[10px] text-slate-400">
                · 第 {citation.page} 页
              </span>
            )}
          </div>
          <span className="shrink-0 text-[10px] text-slate-400">
            {expanded ? '收起' : '展开'}
          </span>
        </div>
        {section && (
          <div
            className="mt-1 truncate text-[10px] text-slate-400"
            title={section}
          >
            § {section}
          </div>
        )}
      </button>

      {expanded && (
        <div className="border-t border-slate-100 px-3 py-2">
          {/* 当前 chunk 片段 */}
          <div className="whitespace-pre-wrap text-xs text-slate-700 max-h-48 overflow-y-auto">
            {citation.snippet}
          </div>

          {/* 邻居上下文 (ARCH-F5): 把 LLM 用的 parent chunks 复用, 让用户前后扫一眼 */}
          {detail.loading && (
            <div className="mt-2 text-[10px] text-slate-400">加载上下文...</div>
          )}
          {detail.error && (
            <div className="mt-2 text-[10px] text-amber-500">
              {detail.error}(不影响当前引用)
            </div>
          )}
          {!detail.loading && (detail.prev || detail.next) && (
            <div className="mt-2 border-t border-slate-100 pt-2">
              <div className="text-[10px] uppercase tracking-wide text-slate-400">
                相邻上下文
              </div>
              {detail.prev && (
                <NeighborBlock label="‹ 上一段" chunk={detail.prev} />
              )}
              {detail.next && (
                <NeighborBlock label="下一段 ›" chunk={detail.next} />
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function NeighborBlock({
  label,
  chunk,
}: {
  label: string;
  chunk: ChunkDetail;
}) {
  return (
    <div className="mt-1">
      <div className="text-[10px] text-slate-400">
        {label} · seq {chunk.seq}
      </div>
      <div className="whitespace-pre-wrap text-[11px] text-slate-500 max-h-24 overflow-y-auto">
        {chunk.content}
      </div>
    </div>
  );
}
