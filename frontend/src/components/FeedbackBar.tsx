import { useState } from 'react';
import type { Rating } from '../types/api';
import { submitFeedback } from '../api/feedback';
import { ApiError } from '../api/client';
import { cn } from '../lib/cn';

interface Props {
  traceId: string;
  submitted: boolean;
  onSubmitDone: () => void;
}

export function FeedbackBar({ traceId, submitted, onSubmitDone }: Props) {
  const [picked, setPicked] = useState<Rating | null>(null);
  const [comment, setComment] = useState('');
  const [corrected, setCorrected] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  if (submitted) {
    return (
      <div className="mt-1 text-[11px] text-emerald-600">✓ 反馈已记录</div>
    );
  }

  const send = async (rating: Rating) => {
    setPicked(rating);
    setSubmitting(true);
    setErr(null);
    try {
      await submitFeedback({
        trace_id: traceId,
        rating,
        corrected_answer: corrected || null,
        comment: comment || null,
      });
      onSubmitDone();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : (e as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="mt-1 flex flex-col gap-1 text-xs text-slate-500">
      <div className="flex items-center gap-2">
        <span>这个回答:</span>
        <button
          disabled={submitting}
          onClick={() => setPicked('GOOD')}
          className={cn(
            'rounded px-2 py-0.5 transition',
            picked === 'GOOD'
              ? 'bg-emerald-100 text-emerald-700'
              : 'hover:bg-slate-100',
          )}
        >
          👍 准确
        </button>
        <button
          disabled={submitting}
          onClick={() => setPicked('BAD')}
          className={cn(
            'rounded px-2 py-0.5 transition',
            picked === 'BAD'
              ? 'bg-rose-100 text-rose-700'
              : 'hover:bg-slate-100',
          )}
        >
          👎 不准
        </button>
        {submitting && <span className="text-[10px]">提交中...</span>}
        {err && <span className="text-[10px] text-rose-500">{err}</span>}
      </div>
      {picked && (
        <div className="mt-1 space-y-1">
          {picked === 'BAD' && (
            <textarea
              value={corrected}
              onChange={(e) => setCorrected(e.target.value)}
              placeholder="正确答案(可选)..."
              className="w-full rounded border border-slate-200 p-1 text-xs"
              rows={2}
            />
          )}
          <input
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="其他备注(可选)..."
            className="w-full rounded border border-slate-200 p-1 text-xs"
          />
          <button
            disabled={submitting}
            onClick={() => send(picked)}
            className="rounded bg-brand-600 px-3 py-1 text-xs text-white hover:bg-brand-700 disabled:opacity-50"
          >
            提交反馈
          </button>
        </div>
      )}
    </div>
  );
}
