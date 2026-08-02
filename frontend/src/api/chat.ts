// SSE POST + ReadableStream 客户端
//
// 注意: 不能用 EventSource — 它只支持 GET 且无自定义 body, 与本项目 chat/sse
// 接口(POST + JSON body)不兼容. 用原生 fetch + ReadableStream + 手解 SSE 帧。
//
// SSE 帧格式 (RFC 8895 简化):
//   event: citations\n
//   data: {"citations":[...]}\n
//   \n
// 一帧 = 多行, 以空行(\n\n)分隔。
import type { ChatRequest, SSEEvent, StateHint } from '../types/api';
import { apiURL, getToken } from './client';

export interface ChatHandlers {
  onEvent: (ev: SSEEvent) => void;
  onError?: (err: Error) => void;
  signal?: AbortSignal;
}

/**
 * 发送一次 SSE 问答。返回一个 controller 让组件可中途 abort。
 * handlers.onEvent 收到按序 'citations'/'delta'/'done'/'error' 事件。
 */
export function chatSSE(req: ChatRequest, handlers: ChatHandlers): AbortController {
  const controller = new AbortController();
  const signal = handlers.signal
    ? mergeSignals(handlers.signal, controller.signal)
    : controller.signal;

  (async () => {
    let resp: Response;
    try {
      resp = await fetch(apiURL('/api/v1/chat/sse'), {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${getToken()}`,
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
        },
        body: JSON.stringify(req),
        signal,
      });
    } catch (e) {
      handlers.onError?.(e as Error);
      return;
    }
    if (!resp.ok || !resp.body) {
      let msg = `HTTP ${resp.status} ${resp.statusText}`;
      try {
        const e = await resp.json();
        msg = e.message ?? msg;
      } catch {
        /* ignore */
      }
      handlers.onError?.(new Error(msg));
      return;
    }

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    // 单帧超时看门狗: 收到任意 chunk 后, 若 HEARTBEAT_MS 内再无数据, 视为后端 hang。
    // SSE 长连接首字节延迟正常(LLM 推理 ~3-10s), 故首帧前用 INITIAL_MS 容忍。
    const HEARTBEAT_MS = 30_000;
    const INITIAL_MS = 60_000;
    let watchdog: ReturnType<typeof setTimeout> | null = null;
    const arm = (ms: number) => {
      if (watchdog) clearTimeout(watchdog);
      watchdog = setTimeout(() => controller.abort(), ms);
    };
    const disarm = () => {
      if (watchdog) {
        clearTimeout(watchdog);
        watchdog = null;
      }
    };
    arm(INITIAL_MS);
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        arm(HEARTBEAT_MS); // 每次 chunk 到达, 重置看门狗
        buf += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const frame = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          const ev = parseSSEFrame(frame);
          if (ev) {
            // 收到终态事件即便 disarm, 由下面 finally 兜底
            handlers.onEvent(ev);
          }
        }
      }
    } catch (e) {
      if ((e as Error).name !== 'AbortError') {
        handlers.onError?.(e as Error);
      }
    } finally {
      disarm();
    }
  })();

  return controller;
}

function parseSSEFrame(frame: string): SSEEvent | null {
  let eventName = 'message';
  const dataLines: string[] = [];
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
  }
  if (dataLines.length === 0) return null;
  let payload: Record<string, unknown>;
  try {
    payload = JSON.parse(dataLines.join('\n'));
  } catch {
    return null;
  }
  // 兼容字段命名: 后端 SSE 序列化路径未走 Jackson SNAKE_CASE, citations 透传 camelCase;
  // REST 路径仍是 snake_case. 两边都兜住, 防字段 undefined.
  const asStr = (v: unknown): string => (typeof v === 'string' ? v : '');
  const asNum = (v: unknown): number => (typeof v === 'number' ? v : 0);
  const asArr = (v: unknown): string[] =>
    Array.isArray(v) ? v.filter(x => typeof x === 'string') : [];
  switch (eventName) {
    case 'citations':
      return {
        type: 'citations',
        citations: ((payload.citations as Record<string, unknown>[]) ?? []).map(c => ({
          chunk_id: asNum(c.chunk_id ?? c.chunkId),
          doc_id: asNum(c.doc_id ?? c.docId),
          page: asNum(c.page),
          snippet: asStr(c.snippet),
          llm_context: asStr(c.llm_context ?? c.llmContext),
          section_path: asArr(c.section_path ?? c.sectionPath),
        })),
      };
    case 'delta':
      return { type: 'delta', delta: asStr(payload.delta) };
    case 'done':
      return {
        type: 'done',
        trace_id: asStr(payload.trace_id ?? payload.traceId),
        state_hint: (payload.state_hint ?? payload.stateHint ?? 'OK') as StateHint,
      };
    case 'error':
      return {
        type: 'error',
        trace_id: asStr(payload.trace_id ?? payload.traceId),
        message: asStr(payload.message),
      };
    default:
      return null;
  }
}

function mergeSignals(s1: AbortSignal, s2: AbortSignal): AbortSignal {
  // 现代浏览器(2024+)有 AbortSignal.any, Node 24 fetch 全局也支持
  const ctor = AbortSignal as unknown as {
    any?: (sigs: AbortSignal[]) => AbortSignal;
  };
  if (typeof ctor.any === 'function') return ctor.any([s1, s2]);
  const ctrl = new AbortController();
  const onAbort = () => ctrl.abort();
  s1.addEventListener('abort', onAbort, { once: true });
  s2.addEventListener('abort', onAbort, { once: true });
  return ctrl.signal;
}
