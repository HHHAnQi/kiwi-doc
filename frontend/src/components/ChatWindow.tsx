import { useEffect, useRef, useState } from 'react';
import { useChatStore } from '../store/useChatStore';
import { useDocStore } from '../store/useDocStore';
import { ChatMessageView } from './ChatMessage';
import type { ChatRequest } from '../types/api';

interface Props {
  selectedDocId: number | null;
}

export function ChatWindow({ selectedDocId }: Props) {
  const messages = useChatStore((s) => s.messages);
  const sending = useChatStore((s) => s.sending);
  const send = useChatStore((s) => s.send);
  const abort = useChatStore((s) => s.abort);
  const markFeedbackSubmitted = useChatStore((s) => s.markFeedbackSubmitted);

  const docs = useDocStore((s) => s.docs);
  const readyCount = docs.filter((d) => d.status === 'READY').length;

  const [input, setInput] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);

  // 自动滚到底部
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  const submit = () => {
    const q = input.trim();
    if (!q || sending) return;
    const req: ChatRequest = {
      query: q,
      doc_id: selectedDocId ?? null,
      top_k: 5,
    };
    send(req);
    setInput('');
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Enter 发送, Shift+Enter 换行
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  return (
    <section className="flex h-full flex-1 flex-col">
      {/* 顶栏(限定文档提示) */}
      <div className="border-b border-slate-200 bg-white px-6 py-3 text-xs text-slate-500">
        {selectedDocId ? (
          <span>
            📌 限定到文档 #{selectedDocId} ·{' '}
            <span className="text-brand-600">仅检索该文档</span>
          </span>
        ) : (
          <span>
            🔍 跨全库检索({readyCount} 个文档就绪)
          </span>
        )}
      </div>

      {/* 消息流 */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto px-6 py-4">
        {messages.length === 0 ? (
          <EmptyState readyCount={readyCount} />
        ) : (
          <div className="mx-auto max-w-3xl space-y-4">
            {messages.map((m) => (
              <ChatMessageView
                key={m.id}
                msg={m}
                onFeedbackSubmitted={markFeedbackSubmitted}
              />
            ))}
          </div>
        )}
      </div>

      {/* 输入框 */}
      <div className="border-t border-slate-200 bg-white px-6 py-3">
        <div className="mx-auto max-w-3xl">
          <div className="flex items-end gap-2 rounded-xl border border-slate-300 bg-white px-3 py-2 focus-within:border-brand-500 focus-within:ring-2 focus-within:ring-brand-100">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder={
                readyCount === 0
                  ? '提示: 知识库还没有就绪文档, 请先在左侧上传'
                  : '输入问题... (Enter 发送, Shift+Enter 换行)'
              }
              rows={1}
              className="flex-1 resize-none bg-transparent text-sm outline-none placeholder:text-slate-400"
              style={{ maxHeight: '120px' }}
              disabled={readyCount === 0 && !sending}
            />
            {sending ? (
              <button
                onClick={abort}
                className="rounded-lg bg-rose-500 px-3 py-1.5 text-xs text-white hover:bg-rose-600"
              >
                停止
              </button>
            ) : (
              <button
                onClick={submit}
                disabled={!input.trim()}
                className="rounded-lg bg-brand-600 px-3 py-1.5 text-xs text-white hover:bg-brand-700 disabled:opacity-40"
              >
                发送
              </button>
            )}
          </div>
          <div className="mt-1 text-[10px] text-slate-400 text-center">
            由 GLM-4-plus + BGE-M3 增强检索回答 · 答案仅供参考
          </div>
        </div>
      </div>
    </section>
  );
}

function EmptyState({ readyCount }: { readyCount: number }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 text-slate-400">
      <div className="text-5xl">💬</div>
      <div className="text-sm">
        {readyCount === 0
          ? '上传一个文档, 我们开始问答吧'
          : '试着问点 SCA 组件相关的问题'}
      </div>
      <div className="mt-4 grid grid-cols-1 gap-1 text-xs">
        {[
          'Dubbo 有哪些负载均衡策略?',
          'Nacos 如何开启鉴权?',
          'Sentinel 如何配置流控规则?',
        ].map((s) => (
          <div key={s} className="rounded border border-slate-200 bg-white px-3 py-1.5">
            {s}
          </div>
        ))}
      </div>
    </div>
  );
}
