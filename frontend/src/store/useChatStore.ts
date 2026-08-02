// Chat store: 消息历史 + 正在流式中的当前问答状态。
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { Citation, ChatRequest, StateHint } from '../types/api';
import { chatSSE } from '../api/chat';
import { uid } from '../lib/format';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations?: Citation[];
  state_hint?: StateHint;
  trace_id?: string;
  // 流式状态: streaming 时 loading=true, 完成后 false
  streaming?: boolean;
  error?: string;
  feedbackSubmitted?: boolean;
}

interface ChatState {
  messages: ChatMessage[];
  sending: boolean;
  // AbortController 不可序列化, 仅运行时存在
  abortController: AbortController | null;

  send: (req: ChatRequest) => void;
  abort: () => void;
  markFeedbackSubmitted: (msgId: string) => void;
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get) => ({
      messages: [],
      sending: false,
      abortController: null,

      send: (req: ChatRequest) => {
    if (get().sending) return;
    const userMsg: ChatMessage = {
      id: uid('u'),
      role: 'user',
      content: req.query,
    };
    // assistant 占位消息, 流过程中 content 累加
    const assistantMsg: ChatMessage = {
      id: uid('a'),
      role: 'assistant',
      content: '',
      streaming: true,
    };

    set((s) => ({
      messages: [...s.messages, userMsg, assistantMsg],
      sending: true,
    }));

    const updateLast = (patch: Partial<ChatMessage>) =>
      set((s) => {
        const msgs = [...s.messages];
        msgs[msgs.length - 1] = { ...msgs[msgs.length - 1], ...patch };
        return { messages: msgs };
      });

    const controller = chatSSE(req, {
      onEvent: (ev) => {
        switch (ev.type) {
          case 'citations':
            updateLast({ citations: ev.citations });
            break;
          case 'delta':
            // 累加 delta
            const cur = get().messages.at(-1);
            updateLast({ content: (cur?.content ?? '') + ev.delta });
            break;
          case 'done':
            updateLast({
              streaming: false,
              trace_id: ev.trace_id,
              state_hint: ev.state_hint,
            });
            set({ sending: false, abortController: null });
            break;
          case 'error':
            updateLast({
              streaming: false,
              error: ev.message,
              trace_id: ev.trace_id,
            });
            set({ sending: false, abortController: null });
            break;
        }
      },
      onError: (err) => {
        updateLast({ streaming: false, error: err.message });
        set({ sending: false, abortController: null });
      },
    });

    set({ abortController: controller });
  },

  abort: () => {
    get().abortController?.abort();
    set((s) => {
      const msgs = [...s.messages];
      const last = msgs.at(-1);
      if (last && last.streaming) {
        msgs[msgs.length - 1] = {
          ...last,
          streaming: false,
          content: last.content + (last.content ? '\n\n_(已中断)_' : '_(已中断)_'),
        };
      }
      return { messages: msgs, sending: false, abortController: null };
    });
  },

  markFeedbackSubmitted: (msgId: string) =>
    set((s) => ({
      messages: s.messages.map((m) =>
        m.id === msgId ? { ...m, feedbackSubmitted: true } : m,
      ),
    })),
    }),
    {
      name: 'ragdoc.chat', // localStorage key
      version: 1,
      // 只持久化 messages; sending/abortController 是运行时状态, 不可序列化也不该恢复
      partialize: (s) => ({ messages: s.messages }),
      // 再水合时: 刷新瞬即"挂掉", 任何遗留 streaming:true 的消息必须收尾,
      // 否则永久卡住(stale streaming=true)。给个通用完成标记 + 灰字提示。
      onRehydrateStorage: () => (state) => {
        if (!state) return;
        state.messages = state.messages.map((m) =>
          m.streaming
            ? {
                ...m,
                streaming: false,
                content:
                  m.content +
                  (m.content ? '\n\n_(页面刷新, 已中断)_' : '_(页面刷新, 已中断)_'),
              }
            : m,
        );
        state.sending = false;
        state.abortController = null;
      },
    },
  ),
);
