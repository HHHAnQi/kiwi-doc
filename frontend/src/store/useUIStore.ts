// 通用 UI 状态 store (ARCH-F4)。
//
// 痛点: Sidebar DocItem 的 ⋯ 操作菜单之前各自 useState(menuOpen), 两个菜单会同时
// 显示(点 A 打开后点 B, A 不自动关), backdrop 互相盖乱跳。改全局单一 activeMenuId,
// 同时只允许一个菜单展开 — 开新时自动 set 旧 id, 让旧 DocItem 通过订阅得知要关闭。
import { create } from 'zustand';

interface UIState {
  // 当前展开操作菜单的 docId; null = 无
  activeMenuDocId: number | null;
  openMenu: (docId: number) => void;
  closeMenu: () => void;
  toggleMenu: (docId: number) => void;
}

export const useUIStore = create<UIState>((set, get) => ({
  activeMenuDocId: null,
  openMenu: (docId) => set({ activeMenuDocId: docId }),
  closeMenu: () => set({ activeMenuDocId: null }),
  toggleMenu: (docId) => {
    const cur = get().activeMenuDocId;
    set({ activeMenuDocId: cur === docId ? null : docId });
  },
}));
