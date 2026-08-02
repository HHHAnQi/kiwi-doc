// 极简全局 toast store: 给删/重试成功、上传失败等单条事件做用户反馈用。
// 不引第三方, 只有 1 条消息 + 自动消失。多次触发覆盖前一条。
import { create } from 'zustand';

export type ToastKind = 'success' | 'error' | 'info';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

interface ToastState {
  toast: Toast | null;
  show: (kind: ToastKind, message: string) => void;
  dismiss: () => void;
}

let nextId = 1;

export const useToastStore = create<ToastState>((set) => ({
  toast: null,
  show: (kind, message) => {
    const id = nextId++;
    set({ toast: { id, kind, message } });
    // 3s 后自动消失; error 留 5s
    const ttl = kind === 'error' ? 5_000 : 3_000;
    setTimeout(() => {
      // 用 id 比对避免覆盖了更新的 toast
      set((s) => (s.toast?.id === id ? { toast: null } : {}));
    }, ttl);
  },
  dismiss: () => set({ toast: null }),
}));
