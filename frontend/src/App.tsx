import { useEffect, useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { ChatWindow } from './components/ChatWindow';
import { useDocStore } from './store/useDocStore';

// 顶层 App: 三区布局
// - Header: 标题 + chat-app 状态指示 + 可编辑 dev token (localStorage 持久)
// - Sidebar: 知识库列表 + 上传
// - ChatWindow: SSE chat 主区
export default function App() {
  const load = useDocStore((s) => s.load);
  const docs = useDocStore((s) => s.docs);
  const error = useDocStore((s) => s.error);
  const [selectedDocId, setSelectedDocId] = useState<number | null>(null);

  // 首次加载 doc 列表
  useEffect(() => {
    load();
  }, [load]);

  // 后台轮询 doc status: 若有 PARSING/UPLOADED 状态, 每 5s 拉一次直到全 READY/FAILED。
  // 加硬上限 MAX_TICKS (5min * 60s / 5s = 60 次), 防止 parser-service 挂了导致永远轮询。
  useEffect(() => {
    const hasPending = docs.some(
      (d) => d.status === 'PARSING' || d.status === 'UPLOADED',
    );
    if (!hasPending) return;
    const INTERVAL_MS = 5_000;
    const MAX_TICKS = 60; // 60 * 5s = 5min, 之后停 (用 console 告警, 不打扰用户)
    let ticks = 0;
    const id = window.setInterval(() => {
      ticks += 1;
      if (ticks >= MAX_TICKS) {
        console.warn('[polling] 已轮询 5 分钟仍未全部 READY, 停止轮询');
        window.clearInterval(id);
        return;
      }
      load();
    }, INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [docs, load]);

  return (
    <div className="flex h-screen flex-col">
      <Header error={error} />
      <div className="flex flex-1 overflow-hidden">
        <Sidebar onPickDoc={setSelectedDocId} selectedDocId={selectedDocId} />
        <ChatWindow selectedDocId={selectedDocId} />
      </div>
      <Footer />
    </div>
  );
}

function Footer() {
  const link = 'text-[10px] text-slate-400 hover:text-brand-600';
  return (
    <footer className="flex items-center justify-between border-t border-slate-200 bg-white px-6 py-1.5 text-[10px] text-slate-400">
      <span>RAG 文档中台 · V3</span>
      <div className="flex items-center gap-3">
        <a
          className={link}
          href="https://github.com/HHHAnQi/kiwi-doc"
          target="_blank"
          rel="noreferrer"
        >
          GitHub
        </a>
        <span className="text-slate-300">·</span>
        <a
          className={link}
          href="https://github.com/HHHAnQi/kiwi-doc/tree/main/docs/adr"
          target="_blank"
          rel="noreferrer"
          title="架构决策记录 ADR-0001 ~ 0010"
        >
          ADR
        </a>
        <span className="text-slate-300">·</span>
        <a
          className={link}
          href="https://github.com/HHHAnQi/kiwi-doc/tree/main/docs/v3/v3-acceptance-report.md"
          target="_blank"
          rel="noreferrer"
          title="RAGAS 评测: faith 0.88 / recall 0.90"
        >
          RAGAS
        </a>
      </div>
    </footer>
  );
}

function Header({ error }: { error: string | null }) {
  return (
    <header className="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-3">
      <div className="flex items-center gap-3">
        <span className="text-xl">📘</span>
        <div>
          <h1 className="text-base font-semibold text-slate-900">RAG 文档中台</h1>
          <p className="text-[10px] text-slate-500">
            多模态 RAG 智能问答 · Spring Cloud Alibaba 技术文档
          </p>
        </div>
      </div>
      <div className="flex items-center gap-3">
        {error && (
          <span className="text-xs text-rose-500" title={error}>
            ⚠ 后端连接异常
          </span>
        )}
        <a
          href="https://github.com/HHHAnQi/kiwi-doc"
          target="_blank"
          rel="noreferrer"
          className="text-xs text-slate-500 hover:text-brand-600"
        >
          GitHub ↗
        </a>
        <span className="text-[10px] text-slate-400">V3</span>
      </div>
    </header>
  );
}
