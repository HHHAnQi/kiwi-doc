import { useState } from 'react';
import type { Citation } from '../types/api';

interface Props {
  index: number; // 引用序号 (1-based, 与答案里 [n] 对齐)
  citation: Citation;
}

export function CitationCard({ index, citation }: Props) {
  const [expanded, setExpanded] = useState(false);
  const section = citation.section_path?.length > 0
    ? citation.section_path.join(' › ')
    : null;

  return (
    <button
      onClick={() => setExpanded((v) => !v)}
      className="block w-full rounded-md border border-slate-200 bg-white px-3 py-2 text-left transition-colors hover:border-brand-300 hover:bg-brand-50/30"
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="flex h-5 w-5 items-center justify-center rounded-full bg-brand-600 text-[10px] font-bold text-white">
            {index}
          </span>
          <span className="text-xs text-slate-700">
            文档 #{citation.doc_id}
            {citation.page > 0 ? ` · 第 ${citation.page} 页` : ''}
          </span>
          {section && (
            <span className="truncate text-[10px] text-slate-400" title={section}>
              § {section}
            </span>
          )}
        </div>
        <span className="text-[10px] text-slate-400">{expanded ? '收起' : '展开'}</span>
      </div>
      {expanded && (
        <div className="mt-2 whitespace-pre-wrap text-xs text-slate-600 max-h-48 overflow-y-auto">
          {citation.snippet}
        </div>
      )}
    </button>
  );
}
