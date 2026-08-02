import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ChatMessage as TChatMessage } from '../store/useChatStore';
import { CitationCard } from './CitationCard';
import { FeedbackBar } from './FeedbackBar';
import { StateBanner } from './StateBanner';
import { cn } from '../lib/cn';

interface Props {
  msg: TChatMessage;
  onFeedbackSubmitted: (msgId: string) => void;
}

export function ChatMessageView({ msg, onFeedbackSubmitted }: Props) {
  const isUser = msg.role === 'user';

  return (
    <div className={cn('flex gap-3', isUser ? 'justify-end' : 'justify-start')}>
      {!isUser && <Avatar role="assistant" />}
      <div className={cn('max-w-2xl', isUser && 'order-first')}>
        <div
          className={cn(
            'rounded-2xl px-4 py-2.5 text-sm',
            isUser
              ? 'bg-brand-600 text-white'
              : 'bg-white border border-slate-200 text-slate-800',
          )}
        >
          {/* user 消息: 纯文本; assistant: markdown */}
          {isUser ? (
            <div className="whitespace-pre-wrap">{msg.content}</div>
          ) : (
            <>
              {msg.content ? (
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    // 让 markdown 链接新开 tab
                    a: ({ ...props }) => <a target="_blank" rel="noreferrer" {...props} />,
                    code: ({ className, children, ...props }) => (
                      <code
                        className={cn(
                          'rounded bg-slate-100 px-1 py-0.5 text-[12px]',
                          className,
                        )}
                        {...props}
                      >
                        {children}
                      </code>
                    ),
                  }}
                >
                  {msg.content}
                </ReactMarkdown>
              ) : msg.streaming ? (
                <span className="inline-block h-4 w-2 animate-pulse bg-brand-500 align-middle" />
              ) : (
                <span className="text-slate-400 italic">空答案</span>
              )}
              {msg.streaming && msg.content && (
                <span className="ml-0.5 inline-block h-4 w-1 animate-pulse bg-brand-500 align-middle" />
              )}
              {msg.error && (
                <div className="mt-2 rounded border border-rose-200 bg-rose-50 px-2 py-1 text-xs text-rose-600">
                  ❌ {msg.error}
                </div>
              )}
            </>
          )}
        </div>

        {/* Citations + State Banner + Feedback - 仅非 user 消息 */}
        {!isUser && msg.state_hint && msg.state_hint !== 'OK' && (
          <div className="mt-2">
            <StateBanner state={msg.state_hint} />
          </div>
        )}

        {!isUser && msg.citations && msg.citations.length > 0 && !msg.streaming && (
          <div className="mt-2 space-y-1">
            <div className="text-[10px] uppercase tracking-wide text-slate-400">
              引用 ({msg.citations.length})
            </div>
            {msg.citations.map((c, i) => (
              <CitationCard key={`${c.chunk_id}-${i}`} index={i + 1} citation={c} />
            ))}
          </div>
        )}

        {/* 反馈按钮: 只要流式结束 + 有 trace_id 就允许反馈。
            NO_RECALL / LLM_DEGRADED / EMPTY_KB 恰恰是最该收反馈的场景,
            不应只限 OK (旧逻辑会让最差的回答反而无法反馈, 与产品目的相反)。 */}
        {!isUser && msg.trace_id && !msg.streaming && (
          <FeedbackBar
            traceId={msg.trace_id}
            submitted={!!msg.feedbackSubmitted}
            onSubmitDone={() => onFeedbackSubmitted(msg.id)}
          />
        )}
      </div>
      {isUser && <Avatar role="user" />}
    </div>
  );
}

function Avatar({ role }: { role: 'user' | 'assistant' }) {
  return (
    <div
      className={cn(
        'flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-sm',
        role === 'user' ? 'bg-slate-200' : 'bg-brand-100',
      )}
    >
      {role === 'user' ? '🧑' : '🤖'}
    </div>
  );
}
