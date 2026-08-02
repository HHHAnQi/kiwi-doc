// 文档列表 store: 加载 / 上传 / 状态轮询
import { create } from 'zustand';
import type { DocumentSummary } from '../types/api';
import { listDocuments, uploadDocument, type UploadOptions } from '../api/documents';
import { ApiError } from '../api/client';

interface DocState {
  docs: DocumentSummary[];
  loading: boolean;
  error: string | null;

  // 上传中状态 (按文件名做 key, 上传完自动清)
  uploading: Record<string, boolean>;

  load: () => Promise<void>;
  upload: (file: File, opts?: UploadOptions) => Promise<void>;
}

// 后台 status 轮询 (PARSING/UPLOADED → READY) 用 setInterval 在 App.tsx 里启, 这里只暴露 load。

export const useDocStore = create<DocState>((set, get) => ({
  docs: [],
  loading: false,
  error: null,
  uploading: {},

  load: async () => {
    set({ loading: true, error: null });
    try {
      const page = await listDocuments({ size: 100 });
      set({ docs: page.items, loading: false });
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message;
      set({ loading: false, error: msg });
    }
  },

  upload: async (file: File, opts?: UploadOptions) => {
    set((s) => ({ uploading: { ...s.uploading, [file.name]: true } }));
    try {
      const result = await uploadDocument(file, opts);
      // 刷新文档列表(后端会返回新 doc), 上传事件 done
      await get().load();
      // 友好提示: 幂等命中 (同 hash 重传)
      if (result.idempotent_hit) {
        // 简单 console 提醒即可(组件层会通过 docs 刷新显示)
        console.info('idempotent hit, doc reused:', result);
      }
    } finally {
      set((s) => {
        const next = { ...s.uploading };
        delete next[file.name];
        return { uploading: next };
      });
    }
  },
}));
